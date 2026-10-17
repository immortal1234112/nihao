package com.platform.core.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.core.crypto.AesUtils;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
public class ModelConfigService {

    /** 加密值前缀：DB 中以 enc: 开头的 api_key/api_secret 为 AES 密文 */
    private static final String ENC_PREFIX = "enc:";

    @Autowired(required = false)
    private ModelConfigMapper mapper;
    private final ConcurrentHashMap<String, ModelConfig> cache = new ConcurrentHashMap<>();

    /**
     * model-config.cache-ttl-ms：缓存自动过期时间（毫秒），默认 60 秒。
     * <p>
     * 为什么需要它 —— 这是跨进程一致性问题：
     * 缓存在**每个 JVM 进程内各存一份**，@PostConstruct 时加载一次。
     * 管理后台保存 API Key 时走的是 agent-core 的 AdminController →
     * saveConfig() → refreshCache()，**只刷新了 agent-core 自己的缓存**。
     * image-engine / video-engine 是独立进程，缓存仍是启动时的旧值，
     * 于是即使 Key 已入库，引擎的 healthCheck() 依旧返回 false，
     * 生成任务直接报 "No available generation model"。
     * <p>
     * 表现为"对话能用、图片/视频不能用"，且必须重启引擎才恢复，极难反查。
     * 加 TTL 后每个进程最多 60 秒自动追上，无需重启。
     */
    @Value("${model-config.cache-ttl-ms:60000}")
    private long cacheTtlMs;

    /** 上次成功加载的时间戳（0 = 从未加载） */
    private volatile long lastLoadAt = 0L;
    /** 保证同一进程内只有一个线程在重载，避免并发打库 */
    private final ReentrantLock reloadLock = new ReentrantLock();

    /** model-config.crypto-key：AES 加密密钥（≥16 字符）；未配置时保持明文模式（兼容存量部署） */
    @Value("${model-config.crypto-key:}")
    private String cryptoKey;

    @PostConstruct
    public void loadCache() {
        if (mapper == null) {
            log.info("ModelConfigService running in no-database mode (Gateway or no datasource)");
            return;
        }
        try {
            List<ModelConfig> configs = mapper.selectList(
                new LambdaQueryWrapper<ModelConfig>().eq(ModelConfig::getEnabled, 1)
            );
            int before = cache.size();
            cache.clear();
            for (ModelConfig c : configs) {
                cache.put(c.getProvider(), c);
            }
            lastLoadAt = System.currentTimeMillis();
            // 只在数量变化时打 INFO，避免 TTL 刷新把日志刷满
            if (before != cache.size() || before == 0) {
                log.info("ModelConfig cache loaded: {} providers", cache.size());
            } else {
                log.debug("ModelConfig cache refreshed: {} providers", cache.size());
            }
        } catch (Exception e) {
            log.warn("ModelConfigService: model_config table not available in current database, using fallback mode. ({})", e.getMessage());
        }
    }

    public void refreshCache() {
        loadCache();
    }

    /**
     * 缓存过期则重载（懒加载，无 @Scheduled 依赖）。
     * 每次读配置前调一次：只是一次时间戳比较，开销可忽略；
     * 真正打库最多每 {@code cacheTtlMs} 一次。
     */
    private void maybeReload() {
        if (mapper == null) return;                 // 网关等无数据源进程
        if (cacheTtlMs <= 0) return;                // 显式关闭自动刷新
        long now = System.currentTimeMillis();
        if (now - lastLoadAt < cacheTtlMs) return;
        if (!reloadLock.tryLock()) return;          // 已有线程在刷，本次用旧值
        try {
            // 二次检查：拿到锁时可能已被别的线程刷过
            if (System.currentTimeMillis() - lastLoadAt >= cacheTtlMs) {
                loadCache();
            }
        } finally {
            reloadLock.unlock();
        }
    }

    public ModelConfig getConfig(String provider) {
        maybeReload();
        return cache.get(provider);
    }

    public boolean isEnabled(String provider) {
        maybeReload();
        ModelConfig c = cache.get(provider);
        return c != null && c.getApiKey() != null && !c.getApiKey().isBlank();
    }

    public String getApiKey(String provider, String fallback) {
        maybeReload();
        ModelConfig c = cache.get(provider);
        if (c != null && c.getApiKey() != null && !c.getApiKey().isBlank()) {
            return decryptIfNeeded(c.getApiKey());
        }
        return fallback;
    }

    public String getApiSecret(String provider, String fallback) {
        maybeReload();
        ModelConfig c = cache.get(provider);
        if (c != null && c.getApiSecret() != null && !c.getApiSecret().isBlank()) {
            return decryptIfNeeded(c.getApiSecret());
        }
        return fallback;
    }

    public String getBaseUrl(String provider, String fallback) {
        maybeReload();
        ModelConfig c = cache.get(provider);
        if (c != null && c.getBaseUrl() != null && !c.getBaseUrl().isBlank()) {
            return c.getBaseUrl();
        }
        return fallback;
    }

    public String getModel(String provider, String fallback) {
        maybeReload();
        ModelConfig c = cache.get(provider);
        if (c != null && c.getModel() != null && !c.getModel().isBlank()) {
            return c.getModel();
        }
        return fallback;
    }

    public List<Map<String, Object>> listAll() {
        if (mapper == null) {
            return Collections.emptyList();
        }
        List<ModelConfig> all = mapper.selectList(null);
        List<Map<String, Object>> result = new ArrayList<>();
        for (ModelConfig c : all) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("provider", c.getProvider());
            m.put("apiKey", maskKey(decryptIfNeeded(c.getApiKey())));
            String apiSecret = decryptIfNeeded(c.getApiSecret());
            m.put("apiSecret", apiSecret != null && !apiSecret.isBlank() ? "****" : "");
            m.put("baseUrl", c.getBaseUrl());
            m.put("model", c.getModel());
            m.put("enabled", c.getEnabled() == 1);
            m.put("hasApiKey", apiKeyNonBlank(c.getApiKey()));
            m.put("hasSecret", apiSecret != null && !apiSecret.isBlank());
            result.add(m);
        }
        return result;
    }

    private String maskKey(String key) {
        if (key == null || key.length() <= 8) return key == null ? "" : "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    @Transactional
    public void saveConfig(String provider, String apiKey, String apiSecret, String baseUrl, String model, Integer enabled) {
        if (mapper == null) {
            throw new RuntimeException("数据库不可用，无法保存配置");
        }
        ModelConfig existing = mapper.selectOne(
            new LambdaQueryWrapper<ModelConfig>().eq(ModelConfig::getProvider, provider)
        );
        if (existing != null) {
            if (apiKey != null && !apiKey.equals("****")) existing.setApiKey(encryptIfNeeded(apiKey));
            if (apiSecret != null && !apiSecret.equals("****")) existing.setApiSecret(encryptIfNeeded(apiSecret));
            if (baseUrl != null) existing.setBaseUrl(baseUrl);
            if (model != null) existing.setModel(model);
            if (enabled != null) existing.setEnabled(enabled);
            mapper.updateById(existing);
        } else {
            ModelConfig config = new ModelConfig();
            config.setProvider(provider);
            config.setApiKey(apiKey != null ? encryptIfNeeded(apiKey) : "");
            config.setApiSecret(apiSecret != null ? encryptIfNeeded(apiSecret) : "");
            config.setBaseUrl(baseUrl != null ? baseUrl : "");
            config.setModel(model != null ? model : "");
            config.setEnabled(enabled != null ? enabled : 0);
            mapper.insert(config);
        }
        refreshCache();
    }

    // ============ 加密/解密 ============

    /** 写入时加密：未配置 crypto-key 或已是密文则原样（兼容明文模式与幂等保存） */
    private String encryptIfNeeded(String raw) {
        if (raw == null || raw.isBlank() || raw.startsWith(ENC_PREFIX) || cryptoKey == null || cryptoKey.isBlank()) {
            return raw;
        }
        try {
            return ENC_PREFIX + AesUtils.encrypt(raw, cryptoKey);
        } catch (Exception e) {
            log.warn("ModelConfig encrypt failed for provider, storing plaintext: {}", e.getMessage());
            return raw;
        }
    }

    /** 读取时解密：非密文（存量明文/未加密部署）原样返回；解密失败告警并原样返回 */
    private String decryptIfNeeded(String stored) {
        if (stored == null || !stored.startsWith(ENC_PREFIX) || cryptoKey == null || cryptoKey.isBlank()) {
            return stored;
        }
        try {
            return AesUtils.decrypt(stored.substring(ENC_PREFIX.length()), cryptoKey);
        } catch (Exception e) {
            log.warn("ModelConfig decrypt failed (key 不匹配或数据损坏): {}", e.getMessage());
            return stored;
        }
    }

    private boolean apiKeyNonBlank(String stored) {
        return stored != null && !stored.isBlank();
    }
}

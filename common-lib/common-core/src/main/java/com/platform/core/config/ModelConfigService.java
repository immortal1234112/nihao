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

@Slf4j
@Service
public class ModelConfigService {

    /** 加密值前缀：DB 中以 enc: 开头的 api_key/api_secret 为 AES 密文 */
    private static final String ENC_PREFIX = "enc:";

    @Autowired(required = false)
    private ModelConfigMapper mapper;
    private final ConcurrentHashMap<String, ModelConfig> cache = new ConcurrentHashMap<>();

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
            cache.clear();
            List<ModelConfig> configs = mapper.selectList(
                new LambdaQueryWrapper<ModelConfig>().eq(ModelConfig::getEnabled, 1)
            );
            for (ModelConfig c : configs) {
                cache.put(c.getProvider(), c);
            }
            log.info("ModelConfig cache loaded: {} providers", cache.size());
        } catch (Exception e) {
            log.warn("ModelConfigService: model_config table not available in current database, using fallback mode. ({})", e.getMessage());
        }
    }

    public void refreshCache() {
        loadCache();
    }

    public ModelConfig getConfig(String provider) {
        return cache.get(provider);
    }

    public boolean isEnabled(String provider) {
        ModelConfig c = cache.get(provider);
        return c != null && c.getApiKey() != null && !c.getApiKey().isBlank();
    }

    public String getApiKey(String provider, String fallback) {
        ModelConfig c = cache.get(provider);
        if (c != null && c.getApiKey() != null && !c.getApiKey().isBlank()) {
            return decryptIfNeeded(c.getApiKey());
        }
        return fallback;
    }

    public String getApiSecret(String provider, String fallback) {
        ModelConfig c = cache.get(provider);
        if (c != null && c.getApiSecret() != null && !c.getApiSecret().isBlank()) {
            return decryptIfNeeded(c.getApiSecret());
        }
        return fallback;
    }

    public String getBaseUrl(String provider, String fallback) {
        ModelConfig c = cache.get(provider);
        if (c != null && c.getBaseUrl() != null && !c.getBaseUrl().isBlank()) {
            return c.getBaseUrl();
        }
        return fallback;
    }

    public String getModel(String provider, String fallback) {
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

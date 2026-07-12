package com.platform.core.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ModelConfigService {

    @Autowired(required = false)
    private ModelConfigMapper mapper;
    private final ConcurrentHashMap<String, ModelConfig> cache = new ConcurrentHashMap<>();

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
            return c.getApiKey();
        }
        return fallback;
    }

    public String getApiSecret(String provider, String fallback) {
        ModelConfig c = cache.get(provider);
        if (c != null && c.getApiSecret() != null && !c.getApiSecret().isBlank()) {
            return c.getApiSecret();
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
            m.put("apiKey", maskKey(c.getApiKey()));
            m.put("apiSecret", c.getApiSecret() != null && !c.getApiSecret().isBlank() ? "****" : "");
            m.put("baseUrl", c.getBaseUrl());
            m.put("model", c.getModel());
            m.put("enabled", c.getEnabled() == 1);
            m.put("hasApiKey", c.getApiKey() != null && !c.getApiKey().isBlank());
            m.put("hasSecret", c.getApiSecret() != null && !c.getApiSecret().isBlank());
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
            if (apiKey != null && !apiKey.equals("****")) existing.setApiKey(apiKey);
            if (apiSecret != null && !apiSecret.equals("****")) existing.setApiSecret(apiSecret);
            if (baseUrl != null) existing.setBaseUrl(baseUrl);
            if (model != null) existing.setModel(model);
            if (enabled != null) existing.setEnabled(enabled);
            mapper.updateById(existing);
        } else {
            ModelConfig config = new ModelConfig();
            config.setProvider(provider);
            config.setApiKey(apiKey != null ? apiKey : "");
            config.setApiSecret(apiSecret != null ? apiSecret : "");
            config.setBaseUrl(baseUrl != null ? baseUrl : "");
            config.setModel(model != null ? model : "");
            config.setEnabled(enabled != null ? enabled : 0);
            mapper.insert(config);
        }
        refreshCache();
    }
}

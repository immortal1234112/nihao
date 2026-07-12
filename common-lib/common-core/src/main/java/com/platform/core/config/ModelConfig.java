package com.platform.core.config;

import com.baomidou.mybatisplus.annotation.TableName;
import com.platform.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("model_config")
public class ModelConfig extends BaseEntity {
    private String provider;
    private String apiKey;
    private String apiSecret;
    private String baseUrl;
    private String model;
    private Integer enabled;
}

package com.platform.core.util;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import org.springframework.stereotype.Component;

/**
 * 分布式雪花 ID 生成器。
 * 基于 Hutool 的 Snowflake 实现，workerId 和 datacenterId 可通过配置调整。
 */
@Component
public class SnowflakeIdGenerator {

    private final Snowflake snowflake;

    public SnowflakeIdGenerator() {
        // 默认 workerId=1, datacenterId=1，生产环境应从 Nacos 或 Redis 动态分配
        this.snowflake = IdUtil.getSnowflake(1, 1);
    }

    /**
     * 生成 19 位纯数字 ID
     */
    public String nextId() {
        return String.valueOf(snowflake.nextId());
    }

    /**
     * 生成带前缀的 ID，如 "task_7845123690123456789"
     */
    public String nextId(String prefix) {
        return prefix + nextId();
    }
}

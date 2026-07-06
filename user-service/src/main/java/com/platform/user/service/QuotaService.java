package com.platform.user.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * 配额服务：基于 Redis Lua 脚本的原子配额检查与消费。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaService {

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 固定窗口计数 Lua 脚本。
     * KEYS[1]: 计数 key
     * ARGV[1]: 限额
     * ARGV[2]: 窗口秒数
     * ARGV[3]: 要消费的数量
     * 返回: 剩余配额，-1 表示超限
     */
    private static final RedisScript<Long> QUOTA_SCRIPT = RedisScript.of("""
            local key = KEYS[1]
            local limit = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local amount = tonumber(ARGV[3])

            local current = redis.call('GET', key)
            if current and tonumber(current) + amount > limit then
                return -1
            end

            local newValue = redis.call('INCRBY', key, amount)
            if newValue == amount then
                redis.call('EXPIRE', key, window)
            end

            return limit - newValue
            """, Long.class);

    /**
     * 原子检查并消费配额。
     *
     * @param tenantId 租户 ID
     * @param userId   用户 ID
     * @param quotaType 配额类型 (IMAGE / VIDEO / STORAGE / API_CALL)
     * @param amount   消费数量
     * @param limit    限额
     * @param windowSeconds 窗口时长（秒），如 86400 表示每天
     * @return 剩余配额，-1 表示已超限
     */
    public long checkAndConsume(String tenantId, String userId, String quotaType,
                                 int amount, long limit, long windowSeconds) {
        String dateKey = getDateKey(windowSeconds);
        String key = String.format("quota:%s:%s:%s:%s", tenantId, userId != null ? userId : "all", quotaType, dateKey);

        List<String> keys = Arrays.asList(key);
        List<String> args = Arrays.asList(String.valueOf(limit), String.valueOf(windowSeconds), String.valueOf(amount));

        Long result = stringRedisTemplate.execute(QUOTA_SCRIPT, keys, (Object[]) args.toArray(new String[0]));
        return result != null ? result : -1;
    }

    /**
     * 查询剩余配额。
     *
     * @return 剩余数量，null 表示未初始化（无限制）
     */
    public Long getRemaining(String tenantId, String userId, String quotaType, long windowSeconds) {
        String dateKey = getDateKey(windowSeconds);
        String key = String.format("quota:%s:%s:%s:%s", tenantId, userId != null ? userId : "all", quotaType, dateKey);
        String value = stringRedisTemplate.opsForValue().get(key);
        if (value == null) {
            return null;
        }
        return Long.parseLong(value);
    }

    /**
     * 根据窗口大小生成日期键。
     */
    private String getDateKey(long windowSeconds) {
        if (windowSeconds <= 3600) {
            return String.valueOf(System.currentTimeMillis() / (windowSeconds * 1000));
        }
        // 天级：yyyy-MM-dd
        return java.time.LocalDate.now().toString();
    }
}

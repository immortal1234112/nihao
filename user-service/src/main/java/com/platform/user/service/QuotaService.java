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
     * 固定窗口计数 Lua 脚本（幂等消费）。
     * KEYS[1]: 计数 key
     * KEYS[2]: 幂等标记 key（值 = 本次消费的计数 key，供补偿精确定位；TTL 7 天覆盖补偿/重试窗口）
     * ARGV[1]: 限额
     * ARGV[2]: 窗口秒数
     * ARGV[3]: 要消费的数量
     * 返回: 剩余配额（幂等重放返回当前剩余，不重复扣减），-1 表示超限
     */
    private static final RedisScript<Long> QUOTA_SCRIPT = RedisScript.of("""
            local key = KEYS[1]
            local markerKey = KEYS[2]
            local limit = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local amount = tonumber(ARGV[3])

            -- 幂等：同幂等键已消费过（重试/重复投递）→ 不重复扣，返回当前剩余
            if redis.call('EXISTS', markerKey) == 1 then
                local cur = tonumber(redis.call('GET', key) or '0')
                return limit - cur
            end

            local current = redis.call('GET', key)
            if current and tonumber(current) + amount > limit then
                return -1
            end

            local newValue = redis.call('INCRBY', key, amount)
            if newValue == amount then
                redis.call('EXPIRE', key, window)
            end
            redis.call('SET', markerKey, key, 'EX', 604800)

            return limit - newValue
            """, Long.class);

    /**
     * 补偿 Lua 脚本（幂等，只退一次）。
     * KEYS[1]: 幂等标记 key（值 = 消费时的计数 key）
     * ARGV[1]: 要退还的数量
     * 返回: 1 已退还 / 0 no-op（未消费过或已补偿过）
     */
    private static final RedisScript<Long> COMPENSATE_SCRIPT = RedisScript.of("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
            local counterKey = redis.call('GET', KEYS[1])
            local amount = tonumber(ARGV[1])
            local v = tonumber(redis.call('GET', counterKey) or '0')
            if v - amount <= 0 then
                redis.call('DEL', counterKey)
            else
                redis.call('DECRBY', counterKey, amount)
            end
            redis.call('DEL', KEYS[1])
            return 1
            """, Long.class);

    /** 幂等标记 TTL：7 天（覆盖消费后的事务回滚/补偿窗口，过期自然清理） */
    private static final long MARKER_TTL_SECONDS = 604800;

    /**
     * 原子检查并消费配额（幂等：同 idempotencyKey 重复调用不重复扣减）。
     *
     * @param tenantId 租户 ID
     * @param userId   用户 ID
     * @param quotaType 配额类型 (IMAGE / VIDEO / STORAGE / API_CALL)
     * @param amount   消费数量
     * @param limit    限额
     * @param windowSeconds 窗口时长（秒），如 86400 表示每天
     * @param idempotencyKey 幂等键（如任务 ID），重复调用返回当前剩余而不重复扣减
     * @return 剩余配额，-1 表示已超限
     */
    public long checkAndConsume(String tenantId, String userId, String quotaType,
                                 int amount, long limit, long windowSeconds, String idempotencyKey) {
        String dateKey = getDateKey(windowSeconds);
        String key = String.format("quota:%s:%s:%s:%s", tenantId, userId != null ? userId : "all", quotaType, dateKey);
        String markerKey = String.format("quota:txn:%s:%s:%s", tenantId, userId != null ? userId : "all", idempotencyKey);

        List<String> keys = Arrays.asList(key, markerKey);
        List<String> args = Arrays.asList(String.valueOf(limit), String.valueOf(windowSeconds), String.valueOf(amount));

        Long result = stringRedisTemplate.execute(QUOTA_SCRIPT, keys, (Object[]) args.toArray(new String[0]));
        return result != null ? result : -1;
    }

    /**
     * 补偿（退还）配额 —— 幂等，只退一次。
     * 通过幂等标记精确定位消费时的计数 key（避免跨天退款打到新窗口 key）。
     *
     * @param tenantId 租户 ID
     * @param userId   用户 ID
     * @param amount   退还数量
     * @param idempotencyKey 与消费时相同的幂等键
     * @return true 已退还；false 未消费过或已退还过
     */
    public boolean compensate(String tenantId, String userId, int amount, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return false;
        }
        String markerKey = String.format("quota:txn:%s:%s:%s", tenantId, userId != null ? userId : "all", idempotencyKey);
        List<String> keys = Arrays.asList(markerKey);
        List<String> args = Arrays.asList(String.valueOf(amount));

        Long result = stringRedisTemplate.execute(COMPENSATE_SCRIPT, keys, (Object[]) args.toArray(new String[0]));
        return result != null && result == 1L;
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

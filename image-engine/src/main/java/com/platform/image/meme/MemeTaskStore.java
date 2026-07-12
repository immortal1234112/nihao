package com.platform.image.meme;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.image.meme.model.MemeTaskState;
import com.platform.image.meme.model.MemeTaskState.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 表情包任务状态存储（Redis）。
 * Key: meme:task:{taskId}
 * TTL: 1 小时
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemeTaskStore {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String KEY_PREFIX = "meme:task:";
    private static final Duration TTL = Duration.ofHours(1);

    public void save(String taskId, MemeTaskState state) {
        try {
            String json = objectMapper.writeValueAsString(state);
            redisTemplate.opsForValue().set(KEY_PREFIX + taskId, json, TTL);
        } catch (Exception e) {
            log.error("Failed to save meme task {}: {}", taskId, e.getMessage());
        }
    }

    public MemeTaskState get(String taskId) {
        try {
            String json = redisTemplate.opsForValue().get(KEY_PREFIX + taskId);
            if (json == null) return null;
            return objectMapper.readValue(json, MemeTaskState.class);
        } catch (Exception e) {
            log.warn("Failed to read meme task {}: {}", taskId, e.getMessage());
            return null;
        }
    }

    public void updateStatus(String taskId, Status status) {
        MemeTaskState state = get(taskId);
        if (state != null) {
            state.setStatus(status.name());
            save(taskId, state);
        }
    }

    public void updateDone(String taskId, String imageUrl, String memeText) {
        MemeTaskState state = get(taskId);
        if (state != null) {
            state.setStatus(Status.DONE.name());
            state.setImageUrl(imageUrl);
            state.setMemeText(memeText);
            save(taskId, state);
        }
    }

    public void updateFailed(String taskId, String errorInfo) {
        MemeTaskState state = get(taskId);
        if (state != null) {
            state.setStatus(Status.FAILED.name());
            state.setErrorInfo(errorInfo);
            save(taskId, state);
        }
    }
}

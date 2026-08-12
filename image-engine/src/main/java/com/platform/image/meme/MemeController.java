package com.platform.image.meme;

import com.platform.core.base.Result;
import com.platform.core.exception.BizException;
import com.platform.core.exception.ErrorCode;
import com.platform.image.meme.model.MemeGenerateRequest;
import com.platform.image.meme.model.MemeTaskState;
import com.platform.image.meme.model.MemeTaskState.Status;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * 表情包生成 REST 控制器。
 *
 * POST /api/v1/meme/generate  — 提交任务（异步）
 * GET  /api/v1/meme/result/{taskId} — 查询结果
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/meme")
@RequiredArgsConstructor
public class MemeController {

    private final MemeService memeService;
    private final MemeTaskStore taskStore;

    /**
     * 提交表情包生成任务（需登录，身份头由网关 JwtAuthFilter 注入）。
     */
    @PostMapping("/generate")
    public Result<Map<String, String>> generate(
            @Valid @RequestBody MemeGenerateRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        if (userId == null || userId.isBlank()) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        // 初始化 Redis 状态
        MemeTaskState state = MemeTaskState.builder()
                .status(Status.PENDING.name())
                .userId(userId)
                .createdAt(System.currentTimeMillis())
                .build();
        taskStore.save(taskId, state);

        // 异步执行
        memeService.executeAsync(taskId, request.getText());

        log.info("Meme task submitted: taskId={}, userId={}, text={}", taskId, userId, request.getText());
        return Result.ok(Map.of("taskId", taskId));
    }

    /**
     * 查询任务结果（仅限任务归属用户）。
     */
    @GetMapping("/result/{taskId}")
    public Result<MemeTaskState> result(
            @PathVariable String taskId,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        MemeTaskState state = taskStore.get(taskId);
        if (state == null) {
            return Result.fail(404, "任务不存在或已过期");
        }
        if (userId == null || !userId.equals(state.getUserId())) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        return Result.ok(state);
    }
}
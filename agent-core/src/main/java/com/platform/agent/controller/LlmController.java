package com.platform.agent.controller;

import com.platform.agent.adapter.LLMAdapter;
import com.platform.agent.chat.service.ChatService;
import com.platform.core.base.Result;
import com.platform.core.exception.BizException;
import com.platform.web.annotation.AuditLog;
import com.platform.web.context.TenantContext;
import com.platform.web.context.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 大模型对话控制器 — /api/v1/llm
 * 支持 DeepSeek、千问 等已注册的 LLMAdapter。
 * chat 委托 ChatService 编排多轮对话 + 会话持久化 + 记忆注入（conversationId 可选，缺省自动建会话）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/llm")
@RequiredArgsConstructor
public class LlmController {

    private final List<LLMAdapter> llmAdapters;
    private final ChatService chatService;

    /**
     * 列出所有可用的 LLM 模型。
     */
    @GetMapping("/models")
    public Result<List<Map<String, Object>>> listModels() {
        List<Map<String, Object>> models = llmAdapters.stream()
                .filter(LLMAdapter::healthCheck)
                .map(a -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("model", a.modelName());
                    m.put("provider", a.provider());
                    m.put("available", Boolean.TRUE);
                    return m;
                })
                .toList();
        return Result.ok(models);
    }

    /**
     * 发起对话（多轮）。
     * 请求可带 conversationId（续聊）；缺省自动创建新会话。
     * 响应新增 conversationId / title / messageId；其余字段（model/provider/content/durationMs/references）保持兼容。
     */
    @AuditLog(action = "LLM_CHAT")
    @PostMapping("/chat")
    public Result<Map<String, Object>> chat(@RequestBody Map<String, Object> request) {
        String message = (String) request.get("message");
        if (message == null || message.isEmpty()) {
            return Result.fail(1001, "message 不能为空");
        }
        try {
            return Result.ok(chatService.chat(request, UserContext.getUserId(), TenantContext.get()));
        } catch (BizException e) {
            return Result.fail(e.getCode(), e.getMessage());
        } catch (Exception e) {
            // 不透传底层错误细节（API Key/供应商异常可能泄露敏感信息），仅记录日志
            log.error("LLM chat failed: {}", e.getMessage(), e);
            return Result.fail(500, "对话失败，请稍后重试");
        }
    }
}

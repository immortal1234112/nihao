package com.platform.agent.controller;

import com.platform.agent.adapter.LLMAdapter;
import com.platform.agent.chat.service.ChatService;
import com.platform.agent.chat.service.ChatStreamService;
import com.platform.agent.service.PromptEnhancerService;
import com.platform.core.base.Result;
import com.platform.core.exception.BizException;
import com.platform.web.annotation.AuditLog;
import com.platform.web.context.TenantContext;
import com.platform.web.context.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
    private final ChatStreamService chatStreamService;
    private final PromptEnhancerService promptEnhancerService;

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

    /**
     * 提示词增强：中文 → 英文翻译/润色（供前端「AI 润色」按钮预览编辑）。
     * 纯英文原样返回；LLM 不可用时降级返回原文，不报错。
     * 与任务提交时的自动翻译共用同一逻辑（PromptEnhancerService）。
     */
    @PostMapping("/enhance-prompt")
    public Result<Map<String, Object>> enhancePrompt(@RequestBody Map<String, Object> request) {
        String prompt = (String) request.get("prompt");
        if (prompt == null || prompt.isEmpty()) {
            return Result.fail(1001, "prompt 不能为空");
        }
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("enhanced", promptEnhancerService.enhance(prompt));
            data.put("original", prompt);
            return Result.ok(data);
        } catch (Exception e) {
            // 不透传底层错误细节，仅记录日志
            log.error("Prompt enhance failed: {}", e.getMessage(), e);
            return Result.fail(500, "润色失败，请稍后重试");
        }
    }

    /**
     * 流式对话（SSE）。事件协议见 {@link ChatStreamService}。
     * <p>
     * 与 /chat 的差异仅在「怎么把结果给出去」：上下文组装、持久化、记忆提取全部复用同一套代码。
     * 本方法必须立刻返回 emitter —— 流水线在 chatStreamExecutor 上跑，不占用 Tomcat 线程。
     * <p>
     * 注意：错误不走 {@code Result}（流一旦开始就无法改状态码），一律以 {@code event: error} 帧下发。
     * 仅在<b>流建立前</b>的失败（如未鉴权）才会返回非 SSE 响应体。
     */
    @AuditLog(action = "LLM_CHAT_STREAM")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody Map<String, Object> request) {
        // ThreadLocal 只在本线程（Tomcat）有效：必须在此刻取出并显式传参，工作线程拿不到
        return chatStreamService.stream(request, UserContext.getUserId(), TenantContext.get());
    }
}

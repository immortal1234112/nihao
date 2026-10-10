package com.platform.agent.chat.service;

import com.platform.agent.adapter.StreamHandler;
import com.platform.agent.chat.entity.ChatConversation;
import com.platform.agent.chat.entity.ChatMessage;
import com.platform.agent.rag.dto.RagReference;
import com.platform.core.exception.BizException;
import com.platform.core.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE 流式对话。
 * <p>
 * 线程模型：Tomcat 线程只做「建 emitter + 取上下文 + 提交任务 + 返回」，
 * 整条流水线跑在 {@code chatStreamExecutor} 上，避免长连接占满 Servlet 线程。
 * <p>
 * 事件协议（前端消费契约）：
 * <pre>
 *   meta       {conversationId,title,provider,model}
 *   phase      {phase:"generate", status:"start"|"done"|"failed", count, message}
 *   references {references:[RagReference], webCount, ragCount}
 *   delta      {content:"增量文本"}
 *   done       {messageId,conversationId,durationMs,content,references}
 *   error      {code,message}
 * </pre>
 * 另每 {@code chat.stream.heartbeat-ms} 发一条 SSE 注释帧（{@code :ping}），
 * 用于穿透中间代理的 idle 超时并尽早发现死链——注释帧不会被任何 SSE 解析器当作事件。
 */
@Slf4j
@Service
public class ChatStreamService {

    private final ChatService chatService;
    private final Executor chatStreamExecutor;
    private final ScheduledExecutorService heartbeatScheduler;

    public ChatStreamService(ChatService chatService,
                             @Qualifier("chatStreamExecutor") Executor chatStreamExecutor,
                             @Qualifier("chatHeartbeatScheduler") ScheduledExecutorService heartbeatScheduler) {
        this.chatService = chatService;
        this.chatStreamExecutor = chatStreamExecutor;
        this.heartbeatScheduler = heartbeatScheduler;
    }

    @Value("${chat.stream.timeout-ms:300000}")
    private long timeoutMs;

    @Value("${chat.stream.heartbeat-ms:15000}")
    private long heartbeatMs;

    @Value("${chat.stream.max-duration-ms:180000}")
    private long maxDurationMs;

    /**
     * 发起一次流式对话。
     * <p>
     * 注意 userId/tenantId 必须由调用方（controller 线程）传入：ThreadLocal 不跨线程。
     */
    public SseEmitter stream(Map<String, Object> request, String userId, String tenantId) {
        // 显式指定超时：无参构造会继承 Tomcat 默认 30s 异步超时，长回答会被容器直接断开
        SseEmitter emitter = new SseEmitter(timeoutMs);
        StreamSession session = new StreamSession();
        emitter.onCompletion(session::cancel);
        emitter.onTimeout(session::cancel);
        emitter.onError(e -> session.cancel());

        ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleAtFixedRate(
                () -> sendHeartbeat(emitter, session), heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);

        chatStreamExecutor.execute(() -> {
            try {
                run(emitter, session, request, userId, tenantId);
            } catch (Throwable t) {
                log.error("chat stream crashed", t);
                // 兜底：run 内部已做细粒度处理，走到这里说明有意料之外的问题
                session.cancel();
                try {
                    emitter.completeWithError(t);
                } catch (Exception ignored) {
                    // emitter 可能已随客户端断开而失效
                }
            } finally {
                heartbeat.cancel(false);
            }
        });
        return emitter;
    }

    // ==================== 流水线 ====================

    private void run(SseEmitter emitter, StreamSession session, Map<String, Object> request,
                     String userId, String tenantId) {
        long start = System.currentTimeMillis();

        // 1. 组装上下文（步骤 1~5）
        ChatService.PreparedContext ctx;
        try {
            ctx = chatService.prepare(request, userId, tenantId);
        } catch (Exception e) {
            // 参数错误 / 模型不可用 / 会话不存在 —— 此时尚未产生 assistant 内容，无需留部分结果
            log.warn("chat stream prepare failed: {}", e.getMessage());
            failWithoutContent(emitter, session, e);
            return;
        }

        ChatConversation conversation = ctx.conversation();

        try {
            // 2. 首帧即给出 conversationId：新会话时前端侧栏可立刻高亮并刷新列表
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("conversationId", conversation.getId());
            meta.put("title", conversation.getTitle());
            meta.put("provider", ctx.adapter().provider());
            meta.put("model", ctx.adapter().modelName());
            emit(emitter, session, "meta", meta);

            // 3. 检索结果一次性下发，前端在首个 delta 前即可渲染参考资料
            //    webCount 保留：存量消息的 references_json 里可能有历史 WEB 引用
            int webCount = (int) ctx.references().stream().filter(r -> r.isWeb()).count();
            Map<String, Object> refsPayload = new LinkedHashMap<>();
            refsPayload.put("references", ctx.references());
            refsPayload.put("webCount", webCount);
            refsPayload.put("ragCount", ctx.references().size() - webCount);
            emit(emitter, session, "references", refsPayload);

            // 4. 逐增量生成
            emit(emitter, session, "phase", ChatService.ChatPhaseEvent.start("generate"));
            long genStart = System.currentTimeMillis();
            ctx.adapter().chatStream(ctx.systemPrompt(), ctx.history(), ctx.userMessage(), ctx.options(),
                    new StreamHandler() {
                        @Override
                        public void onDelta(String delta) {
                            if (System.currentTimeMillis() - genStart > maxDurationMs) {
                                session.timedOut = true;
                                throw new StreamAbortedException("生成超时（超过 " + maxDurationMs + "ms）");
                            }
                            session.acc.append(delta);
                            // 客户端断开时 emit 抛出 StreamAbortedException，中断适配器读循环
                            emit(emitter, session, "delta", Map.of("content", delta));
                        }

                        @Override
                        public boolean isCancelled() {
                            return session.cancelled.get();
                        }
                    });

            String answer = session.acc.toString();
            long duration = System.currentTimeMillis() - start;

            // 5. 落库（含引用）——用与同步路径完全相同的收尾方法
            ChatMessage assistantMsg = chatService.persistAssistant(
                    conversation, userId, tenantId, answer, ctx.references());

            Map<String, Object> done = new LinkedHashMap<>();
            done.put("messageId", assistantMsg.getId());
            done.put("conversationId", conversation.getId());
            done.put("durationMs", duration);
            // 权威全文：前端用它覆盖本地累积串，天然免疫漏帧/渲染漂移
            done.put("content", answer);
            done.put("references", ctx.references());
            emit(emitter, session, "done", done);
            safeComplete(emitter);

            // 6. 会话计数 + 异步记忆提取（在流关闭后做，不占用用户等待时间）
            chatService.afterTurn(conversation, ctx.adapter().provider(), userId, tenantId,
                    ctx.userMessage(), answer, ctx.history());

        } catch (StreamAbortedException e) {
            // 客户端断开或生成超时：用户消息已落库，留一条悬空 user 比留「部分回答」更糟
            log.info("chat stream aborted: {} (conversation={}, partial={}字)",
                    e.getMessage(), conversation.getId(), session.acc.length());
            persistPartial(conversation, userId, tenantId, ctx.references(), session);
            if (!session.cancelled.get()) {
                emitErrorQuietly(emitter, session, 5099, session.timedOut
                        ? "生成超时，已保留已生成内容" : "连接中断");
            }
            safeComplete(emitter);
        } catch (Exception e) {
            log.error("chat stream failed (conversation={})", conversation.getId(), e);
            persistPartial(conversation, userId, tenantId, ctx.references(), session);
            emitErrorQuietly(emitter, session, errorCodeOf(e), errorMessageOf(e));
            safeComplete(emitter);
        }
    }

    // ==================== 失败处理 ====================

    /** prepare 阶段失败：还没有生成任何内容，只发错误帧 */
    private void failWithoutContent(SseEmitter emitter, StreamSession session, Exception e) {
        emitErrorQuietly(emitter, session, errorCodeOf(e), errorMessageOf(e));
        safeComplete(emitter);
    }

    /** 把已生成的部分内容落库，避免用户消息悬空 */
    private void persistPartial(ChatConversation conversation, String userId, String tenantId,
                                List<RagReference> references, StreamSession session) {
        if (session.acc.length() == 0) {
            return;
        }
        try {
            chatService.persistAssistant(conversation, userId, tenantId, session.acc.toString(), references);
        } catch (Exception e) {
            log.warn("persist partial assistant message failed: {}", e.getMessage());
        }
    }

    private int errorCodeOf(Exception e) {
        return e instanceof BizException biz ? biz.getCode() : 5099;
    }

    private String errorMessageOf(Exception e) {
        // 不透传底层细节（可能含 provider 原始错误），与 LlmController 同一约定
        return e instanceof BizException ? e.getMessage() : "生成失败，请稍后重试";
    }

    // ==================== SSE 发送 ====================

    /**
     * 发送一个命名事件。
     * <p>
     * 必须用 {@code TEXT_PLAIN} + 已序列化的字符串：若用 {@code APPLICATION_JSON}，
     * Jackson 会把 String 再序列化一层，前端收到双重编码的 {@code data:"{\"content\":...}"}。
     */
    private void emit(SseEmitter emitter, StreamSession session, String event, Object data) {
        if (session.cancelled.get()) {
            throw new StreamAbortedException("客户端已取消");
        }
        try {
            emitter.send(SseEmitter.event().name(event)
                    .data(JsonUtils.toJson(data), MediaType.TEXT_PLAIN));
        } catch (IOException | IllegalStateException e) {
            session.cancel();
            throw new StreamAbortedException("客户端连接已断开: " + e.getMessage());
        }
    }

    /** 错误帧尽力发送：失败（客户端已断开）只记日志，不再抛 */
    private void emitErrorQuietly(SseEmitter emitter, StreamSession session, int code, String message) {
        if (session.cancelled.get()) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("code", code);
            payload.put("message", message);
            emitter.send(SseEmitter.event().name("error")
                    .data(JsonUtils.toJson(payload), MediaType.TEXT_PLAIN));
        } catch (Exception e) {
            log.debug("send error frame failed (client likely gone): {}", e.getMessage());
        }
    }

    private void sendHeartbeat(SseEmitter emitter, StreamSession session) {
        if (session.cancelled.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().comment("ping"));
        } catch (Exception e) {
            // 客户端已断开：标记取消，生成线程会在下一个增量处停止
            session.cancel();
        }
    }

    private void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("emitter complete failed (already closed): {}", e.getMessage());
        }
    }

    // ==================== 会话状态 ====================

    /** 单条流的可变状态（心跳线程与生成线程共享） */
    private static final class StreamSession {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final StringBuilder acc = new StringBuilder();
        private volatile boolean timedOut = false;

        void cancel() {
            cancelled.set(true);
        }
    }

    /** 中断生成的控制流异常（客户端断开 / 超时），非错误 */
    private static final class StreamAbortedException extends RuntimeException {
        StreamAbortedException(String message) {
            super(message);
        }
    }
}

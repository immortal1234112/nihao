package com.platform.agent.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agent.event.TaskStatusChangedEvent;
import com.platform.agent.service.TaskService;
import com.platform.security.jwt.JwtUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务进度 WebSocket — /ws/task-progress
 *
 * 协议（首帧必须鉴权，失败 close 4401）：
 *   {"type":"auth","token":"<access token>"}     → {"type":"auth_ok"} / close 4401
 *   {"type":"subscribe","taskId":"..."}          → 归属校验（防 IDOR），失败 {"type":"error","message":...}
 *   {"type":"unsubscribe","taskId":"..."}
 *   {"type":"ping"}                              → {"type":"pong"}
 * 服务端推送：{"type":"task_status","taskId":"...","status":"...","stepOrder":n,"timestamp":...}
 *
 * 多任务并发订阅复用单连接；任务快照由前端 GET /api/v1/tasks/{id} 拉取，事件只作增量通知。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskProgressWsHandler extends TextWebSocketHandler {

    /** sessionId → (taskId → session)，两级 map 便于事件路由与连接关闭清理 */
    private final Map<String, Set<String>> sessionTasks = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> sessionUser = new ConcurrentHashMap<>();

    /** 单次 sendMessage 最大耗时（ms），超时抛异常由装饰器捕获，避免慢客户端占住推送线程 */
    private static final int SEND_TIME_LIMIT_MS = 1000;
    /** 单会话待发送缓冲上限（字节），慢客户端积压超限直接断开 */
    private static final int SEND_BUFFER_LIMIT = 64 * 1024;

    private final JwtUtils jwtUtils;
    private final StringRedisTemplate stringRedisTemplate;
    private final TaskService taskService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // 等待首帧 auth；90s 未鉴权由 ServletServerContainerFactoryBean 的 idle timeout 兜底清理
        log.info("WS connected: session={}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            Map<?, ?> frame = objectMapper.readValue(message.getPayload(), Map.class);
            String type = frame.get("type") == null ? "" : frame.get("type").toString();
            switch (type) {
                case "auth" -> handleAuth(session, frame);
                case "subscribe" -> handleSubscribe(session, frame);
                case "unsubscribe" -> handleUnsubscribe(session, frame);
                case "ping" -> send(session, Map.of("type", "pong"));
                // 客户端对心跳 ping 的应答：合法帧，静默忽略（否则每 25s 回一条「未知消息类型」错误帧）
                case "pong" -> { }
                default -> sendError(session, "未知消息类型");
            }
        } catch (Exception e) {
            // 不打印 payload：auth 帧包含 access token，日志泄露窗口 = token 有效期的 2h
            log.warn("WS frame parse failed: session={}, payloadLength={}", session.getId(), message.getPayloadLength(), e);
            sendError(session, "消息格式错误");
        }
    }

    private void handleAuth(WebSocketSession session, Map<?, ?> frame) {
        String token = frame.get("token") == null ? "" : frame.get("token").toString();
        try {
            // 单次解析（签名 + 过期校验在 parseClaims 内完成），失败即拒绝
            var claims = jwtUtils.parseClaims(token);
            // 仅接受 access token（refresh token 不可用于连接）
            if (!"access".equals(claims.get("type", String.class))) {
                closeForbidden(session);
                return;
            }
            String jti = claims.get("jti", String.class);
            // 与网关同源校验：已登出的 access token 在黑名单中，拒绝连接
            if (jti != null && Boolean.TRUE.equals(stringRedisTemplate.hasKey(JwtUtils.ACCESS_BLACKLIST_PREFIX + jti))) {
                closeForbidden(session);
                return;
            }
            sessionUser.put(session.getId(), claims.getSubject());
            // 并发安全装饰器：心跳/事件推送多线程写同一会话；发送超时或缓冲溢出时抛异常而非阻塞
            sessions.put(session.getId(), new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT));
            send(session, Map.of("type", "auth_ok"));
            log.info("WS authed: session={}, userId={}", session.getId(), claims.getSubject());
        } catch (Exception e) {
            log.warn("WS auth failed: session={}", session.getId());
            closeForbidden(session);
        }
    }

    private void handleSubscribe(WebSocketSession session, Map<?, ?> frame) {
        String userId = sessionUser.get(session.getId());
        if (userId == null) {
            sendError(session, "未鉴权");
            return;
        }
        String taskId = frame.get("taskId") == null ? "" : frame.get("taskId").toString();
        if (taskId.isBlank()) {
            sendError(session, "缺少 taskId");
            return;
        }
        try {
            // 归属校验：只能订阅自己的任务（防 IDOR）
            taskService.getTask(taskId, userId);
            sessionTasks.computeIfAbsent(session.getId(), k -> ConcurrentHashMap.newKeySet()).add(taskId);
            send(session, Map.of("type", "subscribed", "taskId", taskId));
        } catch (Exception e) {
            // 带 taskId 回传，前端据此退订该任务（避免重连后无限重试）
            sendError(session, "无权订阅该任务", taskId);
        }
    }

    private void handleUnsubscribe(WebSocketSession session, Map<?, ?> frame) {
        String taskId = frame.get("taskId") == null ? "" : frame.get("taskId").toString();
        Set<String> tasks = sessionTasks.get(session.getId());
        if (tasks != null) tasks.remove(taskId);
    }

    /** 事件发布点（同一进程内）→ 推送给所有订阅了该任务的会话 */
    @EventListener
    public void onTaskStatusChanged(TaskStatusChangedEvent event) {
        sessionTasks.forEach((sessionId, tasks) -> {
            if (!tasks.contains(event.taskId())) return;
            WebSocketSession session = sessions.get(sessionId);
            if (session != null && session.isOpen()) {
                // stepOrder=null 表示任务级事件；步骤级事件带真实 stepOrder。
                // 前端靠它区分：步骤完成帧（status=DONE）不是任务终态，提前退订会冻结 PIPELINE/BATCH 详情页。
                // 不能把 null 归一为 0，也不能用 Map.of（禁止 null 值）
                Map<String, Object> frame = new java.util.HashMap<>();
                frame.put("type", "task_status");
                frame.put("taskId", event.taskId());
                frame.put("status", event.status());
                frame.put("stepOrder", event.stepOrder());
                frame.put("timestamp", event.timestamp());
                send(session, frame);
            }
        });
    }

    /** 25s 心跳：防 NAT/代理超时断连（客户端 30s 无帧也会主动探测） */
    @Scheduled(fixedRate = 25_000)
    public void heartbeat() {
        sessions.forEach((id, session) -> {
            if (session.isOpen()) {
                try {
                    session.sendMessage(new TextMessage("{\"type\":\"ping\"}"));
                } catch (Exception e) {
                    log.debug("WS heartbeat failed, session={}", id);
                }
            }
        });
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        sessionUser.remove(session.getId());
        sessionTasks.remove(session.getId());
        log.info("WS closed: session={}, reason={}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("WS transport error: session={}", session.getId(), exception);
        try {
            session.close(CloseStatus.SERVER_ERROR);
        } catch (Exception ignored) {
        }
    }

    private void closeForbidden(WebSocketSession session) {
        try {
            session.close(new CloseStatus(4401, "unauthorized"));
        } catch (Exception ignored) {
        }
    }

    private void send(WebSocketSession session, Map<String, Object> payload) {
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (Exception e) {
            log.debug("WS send failed: session={}", session.getId(), e);
        }
    }

    private void sendError(WebSocketSession session, String message) {
        send(session, Map.of("type", "error", "message", message));
    }

    private void sendError(WebSocketSession session, String message, String taskId) {
        send(session, Map.of("type", "error", "message", message, "taskId", taskId));
    }
}

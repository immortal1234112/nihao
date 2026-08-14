import { useEffect, useRef } from 'react';
import { useAuthStore } from '@/stores/authStore';

/** 任务状态推送事件（后端 TaskProgressWsHandler 推送的 task_status 帧） */
export interface TaskProgressEvent {
  taskId: string;
  status: string;
  stepOrder: number;
  timestamp: number;
}

export const TERMINAL_STATUSES = new Set(['DONE', 'FAILED', 'CANCELLED']);

/** 健康检查周期（ms）：每 10s 检查一次连接活性 */
const HEALTH_CHECK_MS = 10_000;
/** 半开连接判定阈值（ms）：30s 无任何服务端帧（心跳/事件）视为断流 */
const FRAME_STALE_MS = 30_000;
/** 连续连接失败达到该次数后，由健康检查周期发 RECONNECTED 伪事件（调用方回退轮询兜底） */
const MAX_FAILED_ATTEMPTS = 3;

type EventHandler = (event: TaskProgressEvent) => void;

/**
 * 任务实时进度 — 单例 WebSocket 连接。
 *
 * 多页面/多任务共享一条连接（浏览器同源连接数限制），指数退避重连
 * （1s→2s→5s→10s→30s 封顶），重连成功后自动重发 auth + 重订阅全部活跃任务。
 *
 * 可靠性兜底（三层）：
 * 1. 帧看门狗：10s 检查一次，30s 无任何服务端帧 → 强制断开触发重连（半开连接检测）；
 * 2. 轮询兜底：连续 3 次连接失败 → 每 10s 发一次 RECONNECTED 伪事件，订阅方刷新快照；
 * 3. 终态退订：收到任务级 DONE/FAILED/CANCELLED 帧（stepOrder 为 null）后自动退订该任务，无订阅时关闭连接释放资源。
 *
 * 用法：
 *   useTaskProgress(taskId, (event) => { ... }); // 组件卸载或任务终态自动退订
 */
export function useTaskProgress(taskId: string | undefined, onEvent: EventHandler) {
  const accessToken = useAuthStore((s) => s.accessToken);

  // 回调引用随每次渲染更新，避免闭包过期；注册本身只依赖 taskId
  const callbackRef = useRef(onEvent);
  callbackRef.current = onEvent;

  useEffect(() => {
    if (!taskId || !accessToken) return;
    const unsubscribe = subscribe(taskId, (event) => callbackRef.current(event), accessToken);
    return unsubscribe;
  }, [taskId, accessToken]);
}

// ==================== 单例连接管理 ====================

interface ConnectionState {
  ws: WebSocket | null;
  /** 活跃订阅：taskId → Set<回调>（重连后重发订阅所需） */
  subscriptions: Map<string, Set<EventHandler>>;
  token: string;
  reconnectAttempts: number;
  reconnectTimer: ReturnType<typeof setTimeout> | null;
  healthTimer: ReturnType<typeof setInterval> | null;
  /** 最近一次收到服务端帧的时间（帧看门狗依据） */
  lastFrameAt: number;
  closed: boolean;
}

let conn: ConnectionState | null = null;

const RETRY_DELAYS = [1000, 2000, 5000, 10000, 30000];

function buildWsUrl(token: string): string {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${window.location.host}/api/v1/tasks/ws?token=${encodeURIComponent(token)}`;
}

function send(frame: Record<string, unknown>) {
  if (conn?.ws?.readyState === WebSocket.OPEN) {
    conn.ws.send(JSON.stringify(frame));
  }
}

function scheduleReconnect() {
  if (!conn || conn.closed || conn.ws?.readyState === WebSocket.OPEN) return;
  const delay = RETRY_DELAYS[Math.min(conn.reconnectAttempts, RETRY_DELAYS.length - 1)];
  conn.reconnectTimer = setTimeout(() => {
    if (!conn || conn.closed) return;
    conn.reconnectAttempts += 1;
    open(conn.token);
  }, delay);
}

function resubscribeAll() {
  if (!conn) return;
  conn.subscriptions.forEach((_, taskId) => {
    send({ type: 'subscribe', taskId });
  });
}

/**
 * 帧看门狗 + 轮询兜底。
 * - ws 已连接但 30s 无帧（服务端崩溃后 TCP 半开）→ 强制断开触发重连；
 * - 连续 3 次连接失败 → 周期性发 RECONNECTED 伪事件，订阅方回退轮询拉快照。
 */
function startHealthCheck() {
  if (!conn || conn.healthTimer) return;
  conn.healthTimer = setInterval(() => {
    if (!conn || conn.closed) {
      if (conn?.healthTimer) {
        clearInterval(conn.healthTimer);
        conn.healthTimer = null;
      }
      return;
    }
    const ws = conn.ws;
    if (ws && ws.readyState === WebSocket.OPEN) {
      if (Date.now() - conn.lastFrameAt > FRAME_STALE_MS) {
        // 半开连接：置 null 后 close（onclose 的守卫会跳过重连，这里显式触发）
        conn.ws = null;
        ws.close();
        scheduleReconnect();
      }
    } else if (conn.reconnectAttempts >= MAX_FAILED_ATTEMPTS) {
      // 连接持续失败：伪事件驱动订阅方拉取全量快照（快照+增量模型的兜底）
      conn.subscriptions.forEach((handlers, taskId) => {
        handlers.forEach((cb) => cb({ taskId, status: 'RECONNECTED', stepOrder: 0, timestamp: Date.now() }));
      });
    }
  }, HEALTH_CHECK_MS);
}

function open(token: string) {
  if (!conn) return;
  try {
    const ws = new WebSocket(buildWsUrl(token));
    conn.ws = ws;
    conn.lastFrameAt = Date.now();
    startHealthCheck();

    ws.onopen = () => {
      // 守卫：连接已被替换（token 更换/重连重建）时忽略旧 socket 的事件
      if (!conn || conn.ws !== ws) return;
      send({ type: 'auth', token });
    };

    ws.onmessage = (msg) => {
      if (!conn || conn.ws !== ws) return;
      conn.lastFrameAt = Date.now();
      try {
        const frame = JSON.parse(msg.data as string);
        switch (frame.type) {
          case 'auth_ok': {
            // 仅重连时广播 RECONNECTED（首次连接的快照由页面初始加载完成）
            const wasReconnect = conn.reconnectAttempts > 0;
            conn.reconnectAttempts = 0; // 连接成功，重置退避
            resubscribeAll();
            if (wasReconnect) {
              conn.subscriptions.forEach((handlers, taskId) => {
                handlers.forEach((cb) => cb({ taskId, status: 'RECONNECTED', stepOrder: 0, timestamp: Date.now() }));
              });
            }
            break;
          }
          case 'task_status': {
            const event: TaskProgressEvent = {
              taskId: frame.taskId,
              status: frame.status,
              stepOrder: Number(frame.stepOrder ?? 0),
              timestamp: Number(frame.timestamp ?? Date.now()),
            };
            conn.subscriptions.get(event.taskId)?.forEach((cb) => cb(event));
            // 仅任务级终态帧退订：步骤级帧（stepOrder 非 null）status 为 DONE 只是单步完成，
            // PIPELINE/BATCH 还有后续步骤，此时退订会让详情页冻结在旧快照
            if (TERMINAL_STATUSES.has(event.status) && frame.stepOrder == null) {
              // 终态：自动退订该任务（快照已由回调拉取），无订阅时关闭连接释放资源
              conn.subscriptions.delete(event.taskId);
              send({ type: 'unsubscribe', taskId: event.taskId });
              closeIfIdle();
            }
            break;
          }
          case 'error': {
            // 服务端明确拒绝（如无权订阅该任务）：退订该任务，避免重连后无限重试
            // （无 error 分支时 hook 会一直订阅 → 30s 帧看门狗误判断流 → 重连 → 再被拒）
            if (frame.taskId) {
              conn.subscriptions.delete(frame.taskId);
              send({ type: 'unsubscribe', taskId: frame.taskId });
              closeIfIdle();
            }
            break;
          }
          case 'ping':
            send({ type: 'pong' });
            break;
          default:
            break;
        }
      } catch {
        // 非 JSON 帧忽略
      }
    };

    ws.onclose = (closeEvent) => {
      // 守卫：旧连接关闭不影响已替换的新连接
      if (!conn || conn.ws !== ws) return;
      // 4401：token 失效 → 清除登录态跳登录页
      if (closeEvent.code === 4401) {
        conn.closed = true;
        useAuthStore.getState().logout();
        window.location.href = '/login';
        return;
      }
      conn.ws = null;
      scheduleReconnect();
    };

    ws.onerror = () => {
      if (!conn || conn.ws !== ws) return;
      ws.close();
    };
  } catch {
    scheduleReconnect();
  }
}

/** 无任何订阅时关闭连接并释放定时器（下次订阅自动重建） */
function closeIfIdle() {
  if (!conn) return;
  if (conn.subscriptions.size === 0) {
    if (conn.reconnectTimer) clearTimeout(conn.reconnectTimer);
    if (conn.healthTimer) {
      clearInterval(conn.healthTimer);
      conn.healthTimer = null;
    }
    conn.ws?.close();
    conn = null;
  }
}

/**
 * 订阅任务进度。首次调用时建立单例连接。
 * 返回退订函数。
 */
function subscribe(taskId: string, handler: EventHandler, token: string): () => void {
  if (!conn || conn.token !== token) {
    // token 更换（登出→重登录）：先清旧连接的定时器与 socket，整体替换
    if (conn) {
      if (conn.reconnectTimer) clearTimeout(conn.reconnectTimer);
      if (conn.healthTimer) {
        clearInterval(conn.healthTimer);
      }
      conn.ws?.close();
    }
    conn = {
      ws: null,
      subscriptions: new Map(),
      token,
      reconnectAttempts: 0,
      reconnectTimer: null,
      healthTimer: null,
      lastFrameAt: 0,
      closed: false,
    };
    open(token);
  }

  const handlers = conn.subscriptions.get(taskId) ?? new Set<EventHandler>();
  handlers.add(handler);
  conn.subscriptions.set(taskId, handlers);
  send({ type: 'subscribe', taskId });

  return () => {
    if (!conn) return;
    const set = conn.subscriptions.get(taskId);
    if (!set) return;
    set.delete(handler);
    if (set.size === 0) {
      conn.subscriptions.delete(taskId);
      send({ type: 'unsubscribe', taskId });
    }
    closeIfIdle();
  };
}

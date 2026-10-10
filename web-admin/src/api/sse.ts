import { refreshTokens, TokenRotatedError } from './client';
import { tokenStorage } from '@/utils/token';
import { useAuthStore } from '@/stores/authStore';
import { ApiResult } from '@/types/api';

/**
 * SSE 流式请求（POST + 鉴权 + 手写帧解析）。
 *
 * 为什么不复用 axios 实例：
 *  1. 全局 timeout 30s —— 会把长回答在第 30 秒掐断；
 *  2. 响应拦截器按 `code !== 0` 判错并 reject —— SSE 响应体不是 Result 信封，会被当坏响应；
 *  3. 浏览器 EventSource 不支持 POST、也不能带 Authorization 头。
 * 所以用 fetch + ReadableStream 自己解析。
 */

/** 单个事件帧 */
export interface SseEvent {
  event: string;
  data: string;
}

export interface SseOptions {
  /** 停止按钮 / 组件卸载时中断连接 */
  signal?: AbortSignal;
  /** 每解析出一个完整帧回调一次（data 为原始字符串，由调用方 JSON.parse） */
  onEvent: (evt: SseEvent) => void;
}

/**
 * 流建立前的错误（HTTP 状态码 / 业务 code）。流一旦建立，错误改由 `event: error` 帧下发。
 */
export class SseRequestError extends Error {
  readonly code: number;
  constructor(code: number, message: string) {
    super(message);
    this.name = 'SseRequestError';
    this.code = code;
  }
}

/** 用户主动 abort（区别于网络失败：UI 应静默处理） */
export class SseAbortedError extends Error {
  constructor() {
    super('aborted');
    this.name = 'SseAbortedError';
  }
}

const SSE_CONTENT_TYPE = 'text/event-stream';

/** 发起流式 POST。返回的 Promise 在流正常结束/出错时 resolve/reject（不是流一建立就 resolve）。 */
export async function postSse(path: string, body: unknown, options: SseOptions): Promise<void> {
  const response = await requestWithAuth(path, body, options.signal);
  await readStream(response, options);
}

async function requestWithAuth(path: string, body: unknown, signal?: AbortSignal): Promise<Response> {
  let response = await send(path, body, signal);

  // 401 只可能发生在流建立前（网关先鉴权）→ 刷新后重试一次。
  // 必须走 client.ts 的单飞 refreshTokens：与 axios 并发刷新会因 refresh token 轮转而双双失效。
  if (response.status === 401 && tokenStorage.getRefreshToken()) {
    try {
      const tokens = await refreshTokens();
      response = await send(path, body, signal, tokens.accessToken);
    } catch (e) {
      if (e instanceof TokenRotatedError) {
        // 刷新在途期间登录态已变化（登出/换号）：静默放弃，交给登出流程
        throw new SseAbortedError();
      }
      tokenStorage.clearTokens();
      useAuthStore.getState().logout();
      window.location.href = '/login';
      throw new SseAbortedError();
    }
  }

  if (!response.ok) {
    throw new SseRequestError(response.status, `请求失败（HTTP ${response.status}）`);
  }

  // GlobalExceptionHandler 返回 HTTP 200 + Result JSON（无 @ResponseStatus）。
  // 不先看 Content-Type 就直接按 SSE 解析，这类错误会被静默吞掉、表现为「一片空白」。
  const contentType = response.headers.get('Content-Type') ?? '';
  if (!contentType.includes(SSE_CONTENT_TYPE)) {
    const text = await response.text();
    let code = -1;
    let message = '服务返回了非流式响应';
    try {
      const parsed = JSON.parse(text) as ApiResult;
      code = parsed.code ?? -1;
      message = parsed.message || message;
    } catch {
      message = text.slice(0, 200) || message;
    }
    throw new SseRequestError(code, message);
  }

  return response;
}

function send(path: string, body: unknown, signal?: AbortSignal, accessToken?: string): Promise<Response> {
  const token = accessToken ?? tokenStorage.getAccessToken();
  return fetch(`/api/v1${path}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: SSE_CONTENT_TYPE,
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
    signal,
  });
}

/** 逐帧解析。按 `\n\n` 切帧，帧内按行取 `event:` / `data:`（多行 data 用换行拼接，符合 SSE 规范）。 */
async function readStream(response: Response, options: SseOptions): Promise<void> {
  const reader = response.body?.getReader();
  if (!reader) {
    throw new SseRequestError(-1, '响应没有可读流');
  }
  const decoder = new TextDecoder('utf-8');
  let buffer = '';

  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });

      // 统一换行：服务端按 \n 输出，但中间代理/网关可能改写成 \r\n
      buffer = buffer.replace(/\r\n/g, '\n');

      let sep: number;
      while ((sep = buffer.indexOf('\n\n')) !== -1) {
        const rawFrame = buffer.slice(0, sep);
        buffer = buffer.slice(sep + 2);
        const evt = parseFrame(rawFrame);
        if (evt) options.onEvent(evt);
      }
    }
  } catch (e) {
    if (isAbort(e)) throw new SseAbortedError();
    throw e;
  } finally {
    // 提前 return/抛错时释放底层连接，否则「停止」后连接会挂到服务端超时
    reader.cancel().catch(() => undefined);
  }
}

function parseFrame(rawFrame: string): SseEvent | null {
  let event = 'message';
  const dataLines: string[] = [];

  for (const line of rawFrame.split('\n')) {
    // 空行 = 帧分隔（外层已切）；`:` 开头 = 注释帧（服务端心跳 `:ping`）→ 忽略
    if (line === '' || line.startsWith(':')) continue;
    if (line.startsWith('event:')) {
      event = line.slice(6).trim();
    } else if (line.startsWith('data:')) {
      // 规范：冒号后若有一个空格需去掉，其余原样保留
      const v = line.slice(5);
      dataLines.push(v.startsWith(' ') ? v.slice(1) : v);
    }
  }

  if (dataLines.length === 0) return null;
  return { event, data: dataLines.join('\n') };
}

function isAbort(e: unknown): boolean {
  return e instanceof DOMException && e.name === 'AbortError';
}

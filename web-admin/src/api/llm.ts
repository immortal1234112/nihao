import http from './client';
import { postSse } from './sse';
import {
  ApiResult,
  LlmModel,
  ChatRequest,
  ChatResult,
  ChatStreamDone,
  ChatStreamError,
  ChatStreamMeta,
  ChatStreamPhase,
  ChatStreamReferences,
  Conversation,
  ChatMessageItem,
  MemoryItem,
  PageResult,
} from '@/types/api';

/** 坏帧返回 undefined（调用方跳过），区别于合法 JSON 的 null */
function safeParse(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

/** 流式对话回调。除 onDone/onError 外均为可选：服务端保证顺序 meta → phase* → references → delta+ → done|error */
export interface ChatStreamHandlers {
  onMeta?: (data: ChatStreamMeta) => void;
  onPhase?: (data: ChatStreamPhase) => void;
  onReferences?: (data: ChatStreamReferences) => void;
  onDelta?: (content: string) => void;
  /** 权威收尾帧：content 为服务端全文，落定后应覆盖本地累积串 */
  onDone?: (data: ChatStreamDone) => void;
  /** 业务失败（含生成中途失败，此时可能已收到部分 delta）。连接层面的失败走 Promise reject。 */
  onError?: (data: ChatStreamError) => void;
}

export const llmApi = {
  listModels: () => http.get<ApiResult<LlmModel[]>>('/llm/models'),
  chat: (data: ChatRequest) => http.post<ApiResult<ChatResult>>('/llm/chat', data),

  /**
   * 流式对话（SSE）。与 {@link llmApi.chat} 共用后端同一套上下文组装/持久化逻辑。
   * 不走 axios 实例（超时与错误拦截都不适用于流），详见 api/sse.ts。
   */
  chatStream: (data: ChatRequest, handlers: ChatStreamHandlers, signal?: AbortSignal) =>
    postSse('/llm/chat/stream', data, {
      signal,
      onEvent: (evt) => {
        const payload = safeParse(evt.data);
        // 单帧解析失败只跳过：与服务端「容忍非法帧」一致，不为一个坏帧中断整条回答
        if (payload === undefined) return;
        switch (evt.event) {
          case 'meta':
            handlers.onMeta?.(payload as ChatStreamMeta);
            break;
          case 'phase':
            handlers.onPhase?.(payload as ChatStreamPhase);
            break;
          case 'references':
            handlers.onReferences?.(payload as ChatStreamReferences);
            break;
          case 'delta':
            handlers.onDelta?.((payload as { content: string }).content);
            break;
          case 'done':
            handlers.onDone?.(payload as ChatStreamDone);
            break;
          case 'error':
            handlers.onError?.(payload as ChatStreamError);
            break;
          default:
            // 未知事件类型（服务端将来新增，如深度思考的 reasoning）：忽略而非报错
            break;
        }
      },
    }),

  // ===== 会话 =====
  listConversations: (page = 1, size = 20) =>
    http.get<ApiResult<PageResult<Conversation>>>(`/llm/conversations?page=${page}&size=${size}`),
  createConversation: (data: { title?: string; provider?: string; datasetId?: string; systemPrompt?: string }) =>
    http.post<ApiResult<Conversation>>('/llm/conversations', data),
  renameConversation: (id: string, title: string) =>
    http.patch<ApiResult<Conversation>>(`/llm/conversations/${id}`, { title }),
  deleteConversation: (id: string) => http.delete<ApiResult<null>>(`/llm/conversations/${id}`),
  listMessages: (conversationId: string, page = 1, size = 50) =>
    http.get<ApiResult<PageResult<ChatMessageItem>>>(
      `/llm/conversations/${conversationId}/messages?page=${page}&size=${size}`,
    ),

  // ===== 记忆 =====
  listMemories: (page = 1, size = 10, category?: 'LONG_TERM' | 'SHORT_TERM') =>
    http.get<ApiResult<PageResult<MemoryItem>>>(
      `/llm/memories?page=${page}&size=${size}${category ? `&category=${category}` : ''}`,
    ),
  createMemory: (data: { content: string; memoryType?: string }) =>
    http.post<ApiResult<null>>('/llm/memories', data),
  updateMemory: (id: string, data: { content: string; memoryType?: string }) =>
    http.put<ApiResult<null>>(`/llm/memories/${id}`, data),
  deleteMemory: (id: string) => http.delete<ApiResult<null>>(`/llm/memories/${id}`),

  /** 提示词增强：中文 → 英文翻译/润色（纯英文原样返回；失败降级原文） */
  enhancePrompt: (prompt: string) =>
    http.post<ApiResult<{ enhanced: string; original: string }>>('/llm/enhance-prompt', { prompt }),
};

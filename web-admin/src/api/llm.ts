import http from './client';
import {
  ApiResult,
  LlmModel,
  ChatRequest,
  ChatResult,
  Conversation,
  ChatMessageItem,
  MemoryItem,
  PageResult,
} from '@/types/api';

export const llmApi = {
  listModels: () => http.get<ApiResult<LlmModel[]>>('/llm/models'),
  chat: (data: ChatRequest) => http.post<ApiResult<ChatResult>>('/llm/chat', data),

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

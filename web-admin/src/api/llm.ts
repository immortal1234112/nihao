import http from './client';
import { ApiResult, LlmModel, ChatRequest, ChatResult } from '@/types/api';

export const llmApi = {
  listModels: () => http.get<ApiResult<LlmModel[]>>('/llm/models'),
  chat: (data: ChatRequest) => http.post<ApiResult<ChatResult>>('/llm/chat', data),
};

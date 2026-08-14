import http from '@/api/client';
import { ApiResult, PageResult, PromptTemplate, PromptTemplateForm } from '@/types/api';

export const templateApi = {
  list: (params: { page?: number; size?: number; category?: string; engineType?: string; ownerId?: string }) =>
    http.get<ApiResult<PageResult<PromptTemplate>>>('/templates', { params }),
  create: (data: PromptTemplateForm) => http.post<ApiResult<PromptTemplate>>('/templates', data),
  update: (id: string, data: PromptTemplateForm) =>
    http.put<ApiResult<PromptTemplate>>(`/templates/${id}`, data),
  delete: (id: string) => http.delete<ApiResult<null>>(`/templates/${id}`),
  /** 点击「使用」后上报一次使用计数 */
  recordUsage: (id: string) => http.post<ApiResult<null>>(`/templates/${id}/use`),
};

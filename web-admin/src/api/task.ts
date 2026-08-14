import type { AxiosRequestConfig } from 'axios';
import http from './client';
import { ApiResult, PageResult, TaskSubmitRequest, AgentTask, TaskDetail, TaskResult } from '@/types/api';

export const taskApi = {
  submitImage: (data: TaskSubmitRequest) => http.post<ApiResult<TaskDetail>>('/tasks/image', data),
  submitVideo: (data: TaskSubmitRequest) => http.post<ApiResult<TaskDetail>>('/tasks/video', data),
  submitBatch: (data: TaskSubmitRequest) => http.post<ApiResult<TaskDetail>>('/tasks/batch', data),
  submitWorkflow: (data: TaskSubmitRequest) => http.post<ApiResult<TaskDetail>>('/tasks/workflow', data),
  list: (params: { page?: number; size?: number; status?: string }) =>
    http.get<ApiResult<PageResult<AgentTask>>>('/tasks', { params }),
  /** silent=true 时不弹错误提示（WS 兜底轮询每 10s 一次，失败弹 toast 会刷屏） */
  getById: (id: string, silent = false) =>
    http.get<ApiResult<TaskDetail>>(`/tasks/${id}`, { _silent: silent } as AxiosRequestConfig),
  getResult: (id: string) => http.get<ApiResult<TaskResult>>(`/tasks/${id}/result`),
  cancel: (id: string) => http.delete<ApiResult<null>>(`/tasks/${id}`),
  delete: (id: string) => http.delete<ApiResult<null>>(`/tasks/${id}/delete`),
};

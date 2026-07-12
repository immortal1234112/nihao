import http from './client';
import { ApiResult, PageResult, TaskSubmitRequest, AgentTask, TaskDetail, TaskResult } from '@/types/api';

export const taskApi = {
  submitImage: (data: TaskSubmitRequest) => http.post<ApiResult<TaskDetail>>('/tasks/image', data),
  submitVideo: (data: TaskSubmitRequest) => http.post<ApiResult<TaskDetail>>('/tasks/video', data),
  submitBatch: (data: TaskSubmitRequest) => http.post<ApiResult<TaskDetail>>('/tasks/batch', data),
  submitWorkflow: (data: TaskSubmitRequest) => http.post<ApiResult<TaskDetail>>('/tasks/workflow', data),
  list: (params: { page?: number; size?: number; status?: string }) =>
    http.get<ApiResult<PageResult<AgentTask>>>('/tasks', { params }),
  getById: (id: string) => http.get<ApiResult<TaskDetail>>(`/tasks/${id}`),
  getResult: (id: string) => http.get<ApiResult<TaskResult>>(`/tasks/${id}/result`),
  cancel: (id: string) => http.delete<ApiResult<null>>(`/tasks/${id}`),
  delete: (id: string) => http.delete<ApiResult<null>>(`/tasks/${id}/delete`),
};

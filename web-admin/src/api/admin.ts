import http from './client';
import { ApiResult, PageResult, AgentTask, TaskStats } from '@/types/api';

export const adminApi = {
  getStats: () => http.get<ApiResult<TaskStats>>('/admin/stats'),
  listAllTasks: (params: { page?: number; size?: number; status?: string; userId?: string }) =>
    http.get<ApiResult<PageResult<AgentTask>>>('/admin/tasks', { params }),
  reviewTask: (id: string, action: 'PASS' | 'REJECT') =>
    http.put<ApiResult<null>>(`/admin/tasks/${id}/review`, { action }),
};

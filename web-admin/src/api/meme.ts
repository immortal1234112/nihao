import http from './client';
import { ApiResult } from '@/types/api';

export interface MemeGenerateRes {
  taskId: string;
}

export interface MemeResultRes {
  status: string;       // PENDING / PROCESSING / DONE / FAILED
  imageUrl: string | null;
  memeText: string | null;
  errorInfo: string | null;
  createdAt: number;
}

export const memeApi = {
  /** 提交表情包生成任务 */
  generate: (text: string) =>
    http.post<ApiResult<MemeGenerateRes>>('/meme/generate', { text }),

  /** 查询任务结果 */
  getResult: (taskId: string) =>
    http.get<ApiResult<MemeResultRes>>(`/meme/result/${taskId}`),
};

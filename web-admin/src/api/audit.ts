import http from '@/api/client';
import { ApiResult, AuditLog, PageResult } from '@/types/api';

export interface AuditLogQuery {
  page?: number;
  size?: number;
  userId?: string;
  action?: string;
  result?: string;
  startTime?: string;
  endTime?: string;
}

export const auditApi = {
  list: (params: AuditLogQuery) =>
    http.get<ApiResult<PageResult<AuditLog>>>('/audit/logs', { params }),
};

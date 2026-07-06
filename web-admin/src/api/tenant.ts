import http from './client';
import { ApiResult, Tenant, TenantMember } from '@/types/api';

export const tenantApi = {
  getTenant: (id: string) => http.get<ApiResult<Tenant>>(`/tenants/${id}`),
  updateTenant: (id: string, data: Record<string, unknown>) => http.put<ApiResult<null>>(`/tenants/${id}`, data),
  getMembers: (id: string) => http.get<ApiResult<TenantMember[]>>(`/tenants/${id}/members`),
  addMember: (id: string, data: { userId: string; roleCode: string }) =>
    http.post<ApiResult<TenantMember>>(`/tenants/${id}/members`, data),
  removeMember: (id: string, userId: string) => http.delete<ApiResult<null>>(`/tenants/${id}/members/${userId}`),
};

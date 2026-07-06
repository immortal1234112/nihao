import http from './client';
import { ApiResult, UpdateUserRequest, QuotaInfo, ApiKey, CreateApiKeyRequest } from '@/types/api';

export const userApi = {
  getMe: () => http.get<ApiResult<Record<string, unknown>>>('/users/me'),
  updateMe: (data: UpdateUserRequest) => http.put<ApiResult<null>>('/users/me', data),
  getQuota: (type: string = 'IMAGE') => http.get<ApiResult<QuotaInfo>>('/users/me/quota', { params: { type } }),
  listApiKeys: () => http.get<ApiResult<ApiKey[]>>('/users/me/api-keys'),
  createApiKey: (data: CreateApiKeyRequest) => http.post<ApiResult<{ apiKey: string; hint: string }>>('/users/me/api-keys', data),
  revokeApiKey: (keyId: string) => http.delete<ApiResult<null>>(`/users/me/api-keys/${keyId}`),
};

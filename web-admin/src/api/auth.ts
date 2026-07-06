import http from './client';
import { ApiResult, LoginRequest, RegisterRequest, RefreshTokenRequest, TokenPair } from '@/types/api';

export const authApi = {
  login: (data: LoginRequest) => http.post<ApiResult<TokenPair>>('/auth/login', data),
  register: (data: RegisterRequest) => http.post<ApiResult<string>>('/auth/register', data),
  refresh: (data: RefreshTokenRequest) => http.post<ApiResult<TokenPair>>('/auth/refresh', data),
  logout: (data?: RefreshTokenRequest) => http.post<ApiResult<null>>('/auth/logout', data || {}),
};

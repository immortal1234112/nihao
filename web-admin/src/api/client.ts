import axios, { AxiosError, InternalAxiosRequestConfig } from 'axios';
import { message } from 'antd';
import { tokenStorage } from '@/utils/token';
import { ApiResult, TokenPair } from '@/types/api';

const http = axios.create({
  baseURL: '/api/v1',
  timeout: 30000,
});

let isRefreshing = false;

/** 刷新期间挂起的请求：记录原请求，刷新成功后重放，失败时 reject（避免 Promise 泄漏） */
interface PendingRequest {
  config: InternalAxiosRequestConfig;
  resolve: (value: unknown) => void;
  reject: (reason?: unknown) => void;
}
let refreshSubscribers: PendingRequest[] = [];

// 请求拦截器：附加 Token
http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = tokenStorage.getAccessToken();
  if (token && config.headers) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// 响应拦截器：Token 刷新 + 错误处理
http.interceptors.response.use(
  (response) => {
    const res = response.data as ApiResult;
    if (res.code !== 0) {
      message.error(res.message || '请求失败');
      return Promise.reject(new Error(res.message));
    }
    return response;
  },
  async (error: AxiosError<ApiResult>) => {
    if (!error.response) {
      message.error('网络连接失败，请检查网络');
      return Promise.reject(error);
    }

    const { status, data } = error.response;
    const code = data?.code;

    // Token 过期：尝试刷新
    if ((code === 2001 || code === 2002) && !error.config?.url?.includes('/auth/refresh')) {
      const refreshToken = tokenStorage.getRefreshToken();
      if (!refreshToken) {
        tokenStorage.clearTokens();
        window.location.href = '/login';
        return Promise.reject(error);
      }

      if (isRefreshing) {
        // 已有刷新在途：挂起本请求，等待刷新结果后重放或拒绝
        return new Promise((resolve, reject) => {
          refreshSubscribers.push({ config: error.config!, resolve, reject });
        });
      }

      isRefreshing = true;
      try {
        // D1：refresh 返回 Result 包装，Token 在 res.data.data
        const res = await axios.post<ApiResult<TokenPair>>('/api/v1/auth/refresh', { refreshToken });
        const tokens = res.data.data;
        tokenStorage.setTokens(tokens.accessToken, tokens.refreshToken, tokens.expiresIn);
        isRefreshing = false;
        refreshSubscribers.forEach((p) => {
          p.config.headers!.Authorization = `Bearer ${tokens.accessToken}`;
          p.resolve(http(p.config));
        });
        refreshSubscribers = [];
        if (error.config) {
          error.config.headers.Authorization = `Bearer ${tokens.accessToken}`;
          return http(error.config);
        }
      } catch (refreshError) {
        isRefreshing = false;
        // D2：刷新失败，所有挂起请求一并拒绝，清理 Token 并跳登录
        refreshSubscribers.forEach((p) => p.reject(refreshError));
        refreshSubscribers = [];
        tokenStorage.clearTokens();
        window.location.href = '/login';
      }
      return Promise.reject(error);
    }

    // 其他错误
    if (code) {
      message.error(data?.message || '请求失败');
    } else if (status === 429) {
      message.error('请求过于频繁，请稍后再试');
    } else if (status === 500) {
      message.error('服务器内部错误');
    }

    return Promise.reject(error);
  }
);

export default http;

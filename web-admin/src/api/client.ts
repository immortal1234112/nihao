import axios, { AxiosError, InternalAxiosRequestConfig } from 'axios';
import { message } from 'antd';
import { tokenStorage } from '@/utils/token';
import { ApiResult, TokenPair } from '@/types/api';

const http = axios.create({
  baseURL: '/api/v1',
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' },
});

let isRefreshing = false;
let refreshSubscribers: Array<(token: string) => void> = [];

function subscribeTokenRefresh(cb: (token: string) => void) {
  refreshSubscribers.push(cb);
}

function onTokenRefreshed(newToken: string) {
  refreshSubscribers.forEach((cb) => cb(newToken));
  refreshSubscribers = [];
}

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
        return new Promise((resolve) => {
          subscribeTokenRefresh((newToken: string) => {
            if (error.config) {
              error.config.headers.Authorization = `Bearer ${newToken}`;
              resolve(http(error.config));
            }
          });
        });
      }

      isRefreshing = true;
      try {
        const res = await axios.post<TokenPair>('/api/v1/auth/refresh', { refreshToken });
        const tokens = res.data;
        tokenStorage.setTokens(tokens.accessToken, tokens.refreshToken, tokens.expiresIn);
        isRefreshing = false;
        onTokenRefreshed(tokens.accessToken);
        if (error.config) {
          error.config.headers.Authorization = `Bearer ${tokens.accessToken}`;
          return http(error.config);
        }
      } catch {
        isRefreshing = false;
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

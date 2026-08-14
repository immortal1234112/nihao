import axios, { AxiosError, InternalAxiosRequestConfig } from 'axios';
import { tokenStorage } from '@/utils/token';
import { getFeedbackMessage } from '@/utils/feedback';
import { useAuthStore } from '@/stores/authStore';
import { ApiResult, TokenPair } from '@/types/api';

/** 扩展请求配置：重试标记（重放请求再次 401 直接登出）+ 静默标志（WS 兜底轮询等后台刷新不弹错误提示） */
interface RetriableConfig extends InternalAxiosRequestConfig {
  _retried?: boolean;
  _silent?: boolean;
}

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
      if (!(response.config as RetriableConfig)?._silent) {
        getFeedbackMessage().error(res.message || '请求失败');
      }
      return Promise.reject(new Error(res.message));
    }
    return response;
  },
  async (error: AxiosError<ApiResult>) => {
    if (!error.response) {
      if (!(error.config as RetriableConfig | undefined)?._silent) {
        getFeedbackMessage().error('网络连接失败，请检查网络');
      }
      return Promise.reject(error);
    }

    const { status, data } = error.response;
    const code = data?.code;
    const retried = (error.config as RetriableConfig | undefined)?._retried;

    // 重放请求再次 401：刷新链路已失效（新 token 仍被拒），清 token 登出，避免拿着死 token 无限刷错误
    if ((code === 2001 || code === 2002) && !error.config?.url?.includes('/auth/refresh') && retried) {
      tokenStorage.clearTokens();
      useAuthStore.getState().logout();
      window.location.href = '/login';
      return Promise.reject(error);
    }

    // Token 过期：尝试刷新
    if ((code === 2001 || code === 2002) && !error.config?.url?.includes('/auth/refresh')
        && !retried) {
      const refreshToken = tokenStorage.getRefreshToken();
      if (!refreshToken) {
        tokenStorage.clearTokens();
        useAuthStore.getState().logout();
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
      // 记录刷新前的 token：期间若发生登出/重登录（token 已变化），本次刷新结果作废
      const tokenAtRefreshStart = tokenStorage.getAccessToken();
      try {
        // D1：refresh 返回 Result 包装，Token 在 res.data.data
        const res = await axios.post<ApiResult<TokenPair>>('/api/v1/auth/refresh', { refreshToken });
        const tokens = res.data.data;
        if (tokenStorage.getAccessToken() !== tokenAtRefreshStart) {
          // 刷新在途期间登录态已变化（登出/换号）：丢弃本次结果，挂起请求直接拒绝
          isRefreshing = false;
          refreshSubscribers.forEach((p) => p.reject(error));
          refreshSubscribers = [];
          return Promise.reject(error);
        }
        tokenStorage.setTokens(tokens.accessToken, tokens.refreshToken, tokens.expiresIn);
        // D3：同步 zustand store —— WS 连接等从 store 读取 accessToken，
        //     不同步会导致静默刷新后用旧 token 连接被 4401 强制登出
        useAuthStore.getState().updateTokens(tokens);
        isRefreshing = false;
        refreshSubscribers.forEach((p) => {
          p.config.headers!.Authorization = `Bearer ${tokens.accessToken}`;
          p.resolve(http(p.config));
        });
        refreshSubscribers = [];
        if (error.config) {
          (error.config as RetriableConfig)._retried = true;
          error.config.headers.Authorization = `Bearer ${tokens.accessToken}`;
          return http(error.config);
        }
      } catch (refreshError) {
        isRefreshing = false;
        // D2：刷新失败，所有挂起请求一并拒绝，清理 Token 并跳登录
        refreshSubscribers.forEach((p) => p.reject(refreshError));
        refreshSubscribers = [];
        tokenStorage.clearTokens();
        useAuthStore.getState().logout();
        window.location.href = '/login';
      }
      return Promise.reject(error);
    }

    // 其他错误
    if (!(error.config as RetriableConfig | undefined)?._silent) {
      if (code) {
        getFeedbackMessage().error(data?.message || '请求失败');
      } else if (status === 429) {
        getFeedbackMessage().error('请求过于频繁，请稍后再试');
      } else if (status === 500) {
        getFeedbackMessage().error('服务器内部错误');
      }
    }

    return Promise.reject(error);
  }
);

export default http;

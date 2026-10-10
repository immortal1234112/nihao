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

/** 刷新在途期间登录态已变化（登出/换号）：本次结果作废，且不应触发登出跳转 */
export class TokenRotatedError extends Error {
  constructor() {
    super('token rotated during refresh');
    this.name = 'TokenRotatedError';
  }
}

/**
 * 单飞刷新 Token —— axios 拦截器与 SSE（fetch）共用同一次请求。
 * <p>
 * 后端 refresh token 是<b>轮转</b>的（用过即作废旧 token），所以并发刷新必然互相打死、
 * 一致把用户踢到登录页。SSE 走 fetch 不经过 axios 拦截器，若各刷各的就会重现这个问题。
 * <p>
 * 必须继续用 axios.post：client.test.ts 依赖它统计刷新调用次数。
 */
let refreshPromise: Promise<TokenPair> | null = null;

export function refreshTokens(): Promise<TokenPair> {
  if (refreshPromise) {
    return refreshPromise;
  }
  const refreshToken = tokenStorage.getRefreshToken();
  if (!refreshToken) {
    return Promise.reject(new Error('no refresh token'));
  }
  // 记录刷新前的 token：期间若发生登出/重登录（token 已变化），本次刷新结果作废
  const tokenAtRefreshStart = tokenStorage.getAccessToken();
  refreshPromise = axios
    // D1：refresh 返回 Result 包装，Token 在 res.data.data
    .post<ApiResult<TokenPair>>('/api/v1/auth/refresh', { refreshToken })
    .then((res) => {
      const tokens = res.data.data;
      if (tokenStorage.getAccessToken() !== tokenAtRefreshStart) {
        throw new TokenRotatedError();
      }
      tokenStorage.setTokens(tokens.accessToken, tokens.refreshToken, tokens.expiresIn);
      // D3：同步 zustand store —— WS 连接等从 store 读取 accessToken，
      //     不同步会导致静默刷新后用旧 token 连接被 4401 强制登出
      useAuthStore.getState().updateTokens(tokens);
      return tokens;
    })
    .finally(() => {
      refreshPromise = null;
    });
  return refreshPromise;
}

/** 刷新期间挂起的请求：记录原请求，刷新成功后重放，失败时 reject（避免 Promise 泄漏） */
interface PendingRequest {
  config: InternalAxiosRequestConfig;
  resolve: (value: unknown) => void;
  reject: (reason?: unknown) => void;
}
let refreshSubscribers: PendingRequest[] = [];

/** 取走并清空挂起队列（先清空再重放：重放过程若再次 401，不该混进这一批） */
function drainSubscribers(): PendingRequest[] {
  const pending = refreshSubscribers;
  refreshSubscribers = [];
  return pending;
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
      if (!tokenStorage.getRefreshToken()) {
        tokenStorage.clearTokens();
        useAuthStore.getState().logout();
        window.location.href = '/login';
        return Promise.reject(error);
      }

      if (refreshPromise) {
        // 已有刷新在途：挂起本请求，等待刷新结果后重放或拒绝
        return new Promise((resolve, reject) => {
          refreshSubscribers.push({ config: error.config!, resolve, reject });
        });
      }

      try {
        // 刷新本身（含「token 已变化则作废」判定与写回）都在单飞函数里，与 SSE 路径共用
        const tokens = await refreshTokens();
        drainSubscribers().forEach((p) => {
          p.config.headers!.Authorization = `Bearer ${tokens.accessToken}`;
          p.resolve(http(p.config));
        });
        if (error.config) {
          (error.config as RetriableConfig)._retried = true;
          error.config.headers.Authorization = `Bearer ${tokens.accessToken}`;
          return http(error.config);
        }
      } catch (refreshError) {
        drainSubscribers().forEach((p) => p.reject(refreshError));
        if (refreshError instanceof TokenRotatedError) {
          // 刷新在途期间登录态已变化（登出/换号）：丢弃本次结果，交由已在进行的登出流程处理
          return Promise.reject(error);
        }
        // D2：刷新失败，所有挂起请求一并拒绝，清理 Token 并跳登录
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

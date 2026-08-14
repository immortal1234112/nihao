import { describe, it, expect, vi, beforeEach, afterEach, beforeAll } from 'vitest';
import axios, { AxiosError, AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import http from './client';
import { tokenStorage } from '@/utils/token';

// feedback 提示 spy（vi.mock 工厂提升到文件顶部执行）
const { errorSpy } = vi.hoisted(() => ({ errorSpy: vi.fn() }));
vi.mock('@/utils/feedback', () => ({
  getFeedbackMessage: () => ({
    error: errorSpy,
    success: vi.fn(),
    loading: vi.fn(),
    warning: vi.fn(),
  }),
}));

/** 自定义 adapter：按测试注册的 handler 决定响应 */
type AdapterHandler = (config: InternalAxiosRequestConfig) => AxiosResponse | Promise<AxiosResponse | never>;
let handler: AdapterHandler;

const ok = (data: unknown, config: InternalAxiosRequestConfig): AxiosResponse => ({
  data, status: 200, statusText: 'OK', headers: {}, config,
});

const reject401 = (config: InternalAxiosRequestConfig) =>
  Promise.reject(new AxiosError('Unauthorized', 'ERR_BAD_REQUEST', config, null, {
    status: 401, data: { code: 2001, message: '登录已过期' }, statusText: '', headers: {}, config,
  }));

const rejectStatus = (config: InternalAxiosRequestConfig, status: number, data: unknown) =>
  Promise.reject(new AxiosError(`HTTP ${status}`, 'ERR_BAD_RESPONSE', config, null, {
    status, data, statusText: '', headers: {}, config,
  }));

const refreshSpy = vi.spyOn(axios, 'post');

beforeAll(() => {
  // jsdom 不支持导航（href 赋值会抛 Not implemented），替换为可写普通对象
  Object.defineProperty(window, 'location', {
    writable: true,
    value: { ...window.location, href: '' },
  });
});

beforeEach(() => {
  handler = (config) => ok({ code: 0, data: { ok: true } }, config);
  // adapter 必须返回 Promise（axios 直接 .then；同步对象会抛 TypeError）
  http.defaults.adapter = (config: InternalAxiosRequestConfig) => Promise.resolve(handler(config));
  localStorage.clear();
  // 默认带有效双 token，刷新路径可走；个别测试自行清空
  tokenStorage.setTokens('old-at', 'old-rt', 3600);
  errorSpy.mockClear();
  refreshSpy.mockReset();
});

afterEach(() => {
  // 不能 restoreAllMocks：恢复 axios.post 原函数后，refreshSpy.mockResolvedValue
  // 不再作用于 axios.post（spy 对象已脱离），刷新会走真实网络导致断言失败
  refreshSpy.mockReset();
});

const newTokenPair = {
  accessToken: 'new-at', refreshToken: 'new-rt', expiresIn: 7200,
};

describe('api/client 响应拦截器', () => {
  it('code=0 正常响应透传', async () => {
    await expect(http.get('/tasks')).resolves.toMatchObject({
      data: { code: 0, data: { ok: true } },
    });
  });

  it('业务错误（code!=0）提示并 reject', async () => {
    handler = (config) => ok({ code: 5001, message: '配额不足' }, config);
    await expect(http.get('/tasks')).rejects.toThrow('配额不足');
    expect(errorSpy).toHaveBeenCalledWith('配额不足');
  });

  it('401（code=2001）触发刷新，重放成功并更新 Authorization', async () => {
    handler = (config) =>
      config.headers?.Authorization === 'Bearer new-at'
        ? ok({ code: 0, data: { ok: true } }, config)
        : reject401(config);
    refreshSpy.mockResolvedValue({ data: { code: 0, data: newTokenPair } } as never);

    const res = await http.get('/tasks');
    expect(res.data.data).toEqual({ ok: true });
    expect(refreshSpy).toHaveBeenCalledTimes(1);
    // 新 token 已落盘
    expect(tokenStorage.getAccessToken()).toBe('new-at');
    expect(tokenStorage.getRefreshToken()).toBe('new-rt');
  });

  it('并发 401 只刷新一次，两个请求都重放', async () => {
    let refreshCount = 0;
    handler = (config) =>
      config.headers?.Authorization === 'Bearer new-at'
        ? ok({ code: 0, data: { ok: true } }, config)
        : reject401(config);
    refreshSpy.mockImplementation(() => {
      refreshCount += 1;
      return Promise.resolve({ data: { code: 0, data: newTokenPair } } as never);
    });

    const [r1, r2] = await Promise.all([http.get('/tasks'), http.get('/tasks')]);
    expect(refreshCount).toBe(1);
    expect(r1.data.data).toEqual({ ok: true });
    expect(r2.data.data).toEqual({ ok: true });
  });

  it('刷新失败：挂起请求拒绝、清 token、跳登录', async () => {
    handler = () => reject401({ headers: {} } as InternalAxiosRequestConfig);
    refreshSpy.mockRejectedValue(new Error('refresh failed'));

    const [r1, r2] = await Promise.all([
      http.get('/tasks').catch((e) => e),
      http.get('/tasks').catch((e) => e),
    ]);
    expect(r1).toBeInstanceOf(Error);
    expect(r2).toBeInstanceOf(Error);
    expect(tokenStorage.hasTokens()).toBe(false);
    expect(window.location.href).toBe('/login');
  });

  it('无 refreshToken：直接清 token 跳登录，不调刷新', async () => {
    tokenStorage.clearTokens();
    handler = () => reject401({ headers: {} } as InternalAxiosRequestConfig);
    await expect(http.get('/tasks')).rejects.toBeInstanceOf(AxiosError);
    expect(refreshSpy).not.toHaveBeenCalled();
    expect(tokenStorage.hasTokens()).toBe(false);
    expect(window.location.href).toBe('/login');
  });

  it('刷新成功同步 zustand store（WS 连接等依赖 store 中的 accessToken）', async () => {
    const { useAuthStore } = await import('@/stores/authStore');
    useAuthStore.setState({ accessToken: 'old-at', refreshToken: 'old-rt', isAuthenticated: true });
    handler = (config) =>
      config.headers?.Authorization === 'Bearer new-at'
        ? ok({ code: 0, data: { ok: true } }, config)
        : reject401(config);
    refreshSpy.mockResolvedValue({ data: { code: 0, data: newTokenPair } } as never);

    await http.get('/tasks');
    expect(useAuthStore.getState().accessToken).toBe('new-at');
    expect(useAuthStore.getState().refreshToken).toBe('new-rt');
  });

  it('刷新在途期间 token 已变化（登出/重登录）：丢弃刷新结果不写回', async () => {
    handler = (config) => reject401(config);
    let resolveRefresh: (v: unknown) => void = () => {};
    refreshSpy.mockImplementation(
      () => new Promise((resolve) => { resolveRefresh = resolve; })
    );

    const pending = http.get('/tasks').catch((e) => e);
    // 刷新在途时登出：localStorage 被清空
    tokenStorage.clearTokens();
    resolveRefresh({ data: { code: 0, data: newTokenPair } } as never);

    await pending;
    expect(tokenStorage.getAccessToken()).toBeNull(); // 刷新结果被丢弃，未写回
  });

  it('429 提示频率限制', async () => {
    handler = (config) => rejectStatus(config, 429, {});
    await expect(http.get('/tasks')).rejects.toBeInstanceOf(AxiosError);
    expect(errorSpy).toHaveBeenCalledWith('请求过于频繁，请稍后再试');
  });

  it('500 提示服务器错误', async () => {
    handler = (config) => rejectStatus(config, 500, {});
    await expect(http.get('/tasks')).rejects.toBeInstanceOf(AxiosError);
    expect(errorSpy).toHaveBeenCalledWith('服务器内部错误');
  });

  it('网络错误（无 response）提示断网', async () => {
    handler = (config) =>
      Promise.reject(new AxiosError('Network Error', 'ERR_NETWORK', config));
    await expect(http.get('/tasks')).rejects.toBeInstanceOf(AxiosError);
    expect(errorSpy).toHaveBeenCalledWith('网络连接失败，请检查网络');
  });
});


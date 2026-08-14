import { describe, it, expect, vi, beforeEach } from 'vitest';
import { useAuthStore } from './authStore';
import { tokenStorage } from '@/utils/token';

// mock authApi：logout 返回 Promise（store 内链式 .catch）
const { logoutMock } = vi.hoisted(() => ({ logoutMock: vi.fn(() => Promise.resolve()) }));
vi.mock('@/api/auth', () => ({
  authApi: { logout: logoutMock, login: vi.fn(), register: vi.fn(), refresh: vi.fn() },
}));

describe('authStore', () => {
  beforeEach(() => {
    localStorage.clear();
    logoutMock.mockClear();
    useAuthStore.setState({
      accessToken: null,
      refreshToken: null,
      expiresIn: 0,
      user: null,
      isAuthenticated: false,
    });
  });

  it('setTokens 写入本地并置为已认证', () => {
    useAuthStore.getState().setTokens({ accessToken: 'at-1', refreshToken: 'rt-1', expiresIn: 7200 });
    const state = useAuthStore.getState();
    expect(state.isAuthenticated).toBe(true);
    expect(state.accessToken).toBe('at-1');
    expect(tokenStorage.getAccessToken()).toBe('at-1');
    expect(tokenStorage.getRefreshToken()).toBe('rt-1');
  });

  it('setUser 更新用户信息', () => {
    const user = { id: 'u1', username: 'immortal', email: '', phone: '', avatarUrl: '', status: 'ACTIVE', roles: [], tenantId: 't1' };
    useAuthStore.getState().setUser(user);
    expect(useAuthStore.getState().user).toEqual(user);
  });

  it('logout 通知服务端撤销并清空本地', async () => {
    useAuthStore.getState().setTokens({ accessToken: 'at-2', refreshToken: 'rt-2', expiresIn: 3600 });
    useAuthStore.getState().logout();
    await Promise.resolve(); // 等 mock promise

    expect(logoutMock).toHaveBeenCalledWith({ refreshToken: 'rt-2', accessToken: 'at-2' });
    const state = useAuthStore.getState();
    expect(state.isAuthenticated).toBe(false);
    expect(state.accessToken).toBeNull();
    expect(tokenStorage.hasTokens()).toBe(false);
  });

  it('无 token 时 logout 不调后端', () => {
    useAuthStore.getState().logout();
    expect(logoutMock).not.toHaveBeenCalled();
  });

  it('updateTokens 只更新 token 不动用户', () => {
    const user = { id: 'u1', username: 'immortal', email: '', phone: '', avatarUrl: '', status: 'ACTIVE', roles: [], tenantId: 't1' };
    useAuthStore.getState().setUser(user);
    useAuthStore.getState().updateTokens({ accessToken: 'new-at', refreshToken: 'new-rt', expiresIn: 60 });
    const state = useAuthStore.getState();
    expect(state.accessToken).toBe('new-at');
    expect(state.user).toEqual(user);
    expect(tokenStorage.getAccessToken()).toBe('new-at');
  });
});

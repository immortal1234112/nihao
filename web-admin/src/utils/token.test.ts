import { describe, it, expect, beforeEach } from 'vitest';
import { tokenStorage } from './token';

describe('tokenStorage', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('setTokens 写入三个 key', () => {
    tokenStorage.setTokens('at-1', 'rt-1', 7200);
    expect(localStorage.getItem('access_token')).toBe('at-1');
    expect(localStorage.getItem('refresh_token')).toBe('rt-1');
    expect(localStorage.getItem('token_expires_in')).toBe('7200');
  });

  it('getAccessToken / getRefreshToken 读取已写入值', () => {
    tokenStorage.setTokens('at-2', 'rt-2', 3600);
    expect(tokenStorage.getAccessToken()).toBe('at-2');
    expect(tokenStorage.getRefreshToken()).toBe('rt-2');
  });

  it('未写入时返回 null', () => {
    expect(tokenStorage.getAccessToken()).toBeNull();
    expect(tokenStorage.getRefreshToken()).toBeNull();
  });

  it('hasTokens 仅在 access_token 存在时为 true', () => {
    expect(tokenStorage.hasTokens()).toBe(false);
    tokenStorage.setTokens('at-3', 'rt-3', 100);
    expect(tokenStorage.hasTokens()).toBe(true);
  });

  it('clearTokens 清空全部', () => {
    tokenStorage.setTokens('at-4', 'rt-4', 100);
    tokenStorage.clearTokens();
    expect(tokenStorage.getAccessToken()).toBeNull();
    expect(tokenStorage.getRefreshToken()).toBeNull();
    expect(localStorage.getItem('token_expires_in')).toBeNull();
    expect(tokenStorage.hasTokens()).toBe(false);
  });

  it('setTokens 覆盖旧值', () => {
    tokenStorage.setTokens('old-at', 'old-rt', 1);
    tokenStorage.setTokens('new-at', 'new-rt', 2);
    expect(tokenStorage.getAccessToken()).toBe('new-at');
    expect(tokenStorage.getRefreshToken()).toBe('new-rt');
  });
});

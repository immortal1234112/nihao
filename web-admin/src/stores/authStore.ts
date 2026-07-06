import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { UserInfo, TokenPair } from '@/types/api';
import { tokenStorage } from '@/utils/token';

interface AuthState {
  accessToken: string | null;
  refreshToken: string | null;
  expiresIn: number;
  user: UserInfo | null;
  isAuthenticated: boolean;

  setTokens: (tokens: TokenPair) => void;
  setUser: (user: UserInfo) => void;
  logout: () => void;
  updateTokens: (tokens: TokenPair) => void;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      accessToken: tokenStorage.getAccessToken(),
      refreshToken: tokenStorage.getRefreshToken(),
      expiresIn: 0,
      user: null,
      isAuthenticated: tokenStorage.hasTokens(),

      setTokens: (tokens: TokenPair) => {
        tokenStorage.setTokens(tokens.accessToken, tokens.refreshToken, tokens.expiresIn);
        set({
          accessToken: tokens.accessToken,
          refreshToken: tokens.refreshToken,
          expiresIn: tokens.expiresIn,
          isAuthenticated: true,
        });
      },

      setUser: (user: UserInfo) => set({ user }),

      logout: () => {
        tokenStorage.clearTokens();
        set({
          accessToken: null,
          refreshToken: null,
          expiresIn: 0,
          user: null,
          isAuthenticated: false,
        });
      },

      updateTokens: (tokens: TokenPair) => {
        tokenStorage.setTokens(tokens.accessToken, tokens.refreshToken, tokens.expiresIn);
        set({
          accessToken: tokens.accessToken,
          refreshToken: tokens.refreshToken,
          expiresIn: tokens.expiresIn,
        });
      },
    }),
    {
      name: 'auth-storage',
      partialize: (state) => ({
        accessToken: state.accessToken,
        refreshToken: state.refreshToken,
        isAuthenticated: state.isAuthenticated,
      }),
    }
  )
);

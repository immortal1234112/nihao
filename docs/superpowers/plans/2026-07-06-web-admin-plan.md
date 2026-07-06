# AI Agent 创作平台前端 — 实现计划

> **For agentic workers:** 使用 `superpowers:subagent-driven-development` 逐任务实现，每任务独立提交。

**Goal:** 构建 React + TypeScript + Ant Design 5 前端管理后台，覆盖认证、AI 创作、任务管理、文件管理、LLM 对话、租户管理、管理后台共 18 个页面。

**Architecture:** Vite 5 构建，Zustand 状态管理，React Router 6 路由，Axios + 拦截器实现 JWT 自动刷新。代码按功能分目录：api（请求层）、stores（状态）、pages（页面）、components（公共组件）、router（路由）、types（类型）。

**Tech Stack:** React 18, TypeScript 5, Vite 5, Ant Design 5, Zustand 4, React Router 6, Axios, @ant-design/charts

## Global Constraints

- 所有 API 请求通过 `web-admin/src/api/client.ts` 的 Axios 实例
- API 入口：开发环境 Vite proxy `localhost:8080`，响应格式 `{ code, message, data, requestId, timestamp }`
- JWT Access Token 过期时自动用 Refresh Token 刷新，刷新失败跳 `/login`
- 路由按角色控制：菜单隐藏 + AuthGuard 拦截
- 代码风格：每个文件 ≤ 300 行，CSS Modules 做样式隔离
- 每个不可逆操作（删除、取消）需 Ant Design Modal 二次确认

---

### Task 1: 项目初始化与基础配置

**Files:**
- Create: `web-admin/package.json`
- Create: `web-admin/index.html`
- Create: `web-admin/vite.config.ts`
- Create: `web-admin/tsconfig.json`
- Create: `web-admin/tsconfig.node.json`
- Create: `web-admin/src/main.tsx`
- Create: `web-admin/src/App.tsx`
- Create: `web-admin/src/vite-env.d.ts`

**Produces:** 可启动的空白 Vite + React + TS 项目，依赖已安装

- [ ] **Step 1: 创建 package.json**

```json
{
  "name": "ai-agent-platform-admin",
  "private": true,
  "version": "1.0.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc -b && vite build",
    "preview": "vite preview",
    "lint": "eslint ."
  },
  "dependencies": {
    "react": "^18.3.1",
    "react-dom": "^18.3.1",
    "react-router-dom": "^6.23.1",
    "antd": "^5.17.0",
    "@ant-design/icons": "^5.3.7",
    "@ant-design/charts": "^2.1.1",
    "zustand": "^4.5.2",
    "axios": "^1.7.2",
    "dayjs": "^1.11.11"
  },
  "devDependencies": {
    "@types/react": "^18.3.3",
    "@types/react-dom": "^18.3.0",
    "@vitejs/plugin-react": "^4.3.0",
    "typescript": "^5.4.5",
    "vite": "^5.2.12"
  }
}
```

- [ ] **Step 2: 创建 index.html**

```html
<!DOCTYPE html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>AI Agent 创作平台</title>
    <link rel="icon" type="image/svg+xml" href="/vite.svg" />
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

- [ ] **Step 3: 创建 vite.config.ts**

```typescript
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
    },
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
```

- [ ] **Step 4: 创建 tsconfig.json**

```json
{
  "compilerOptions": {
    "target": "ES2020",
    "useDefineForClassFields": true,
    "lib": ["ES2020", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "skipLibCheck": true,
    "moduleResolution": "bundler",
    "allowImportingTsExtensions": true,
    "resolveJsonModule": true,
    "isolatedModules": true,
    "noEmit": true,
    "jsx": "react-jsx",
    "strict": true,
    "noUnusedLocals": false,
    "noUnusedParameters": false,
    "noFallthroughCasesInSwitch": true,
    "baseUrl": ".",
    "paths": {
      "@/*": ["src/*"]
    }
  },
  "include": ["src"],
  "references": [{ "path": "./tsconfig.node.json" }]
}
```

- [ ] **Step 5: 创建 tsconfig.node.json**

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "lib": ["ES2023"],
    "module": "ESNext",
    "skipLibCheck": true,
    "moduleResolution": "bundler",
    "allowImportingTsExtensions": true,
    "resolveJsonModule": true,
    "isolatedModules": true,
    "noEmit": true,
    "strict": true
  },
  "include": ["vite.config.ts"]
}
```

- [ ] **Step 6: 创建 src/main.tsx**

```tsx
import React from 'react';
import ReactDOM from 'react-dom/client';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import App from './App';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider locale={zhCN} theme={{ token: { colorPrimary: '#1677ff' } }}>
      <App />
    </ConfigProvider>
  </React.StrictMode>
);
```

- [ ] **Step 7: 创建 src/App.tsx**

```tsx
const App = () => {
  return <div>AI Agent Platform Admin</div>;
};

export default App;
```

- [ ] **Step 8: 创建 src/vite-env.d.ts**

```typescript
/// <reference types="vite/client" />
```

- [ ] **Step 9: 安装依赖并验证启动**

```bash
cd web-admin && npm install && npm run dev
```

---

### Task 2: API 类型定义与 Axios 客户端

**Files:**
- Create: `web-admin/src/types/api.ts`
- Create: `web-admin/src/api/client.ts`

**Produces:** 类型定义 + 带拦截器的 Axios 实例

- [ ] **Step 1: 创建 src/types/api.ts**

```typescript
// ========== 通用 ==========
export interface ApiResult<T = unknown> {
  code: number;
  message: string;
  data: T;
  requestId: string | null;
  timestamp: number;
}

export interface PageInfo {
  page: number;
  size: number;
  total: number;
  totalPages: number;
}

export interface PageResult<T> {
  records: T[];
  pagination: PageInfo;
}

// ========== 认证 ==========
export interface LoginRequest {
  username: string;
  password: string;
}

export interface RegisterRequest {
  username: string;
  password: string;
  email: string;
  phone?: string;
}

export interface TokenPair {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
}

export interface RefreshTokenRequest {
  refreshToken: string;
}

// ========== 用户 ==========
export interface UserInfo {
  id: string;
  username: string;
  email: string;
  phone: string;
  avatarUrl: string;
  status: string;
  roles: string[];
  tenantId: string;
}

export interface UpdateUserRequest {
  email?: string;
  phone?: string;
  avatarUrl?: string;
}

export interface QuotaInfo {
  limit: number;
  used: number;
  remaining: number;
}

export interface ApiKey {
  id: string;
  name: string;
  keyPrefix: string;
  rateLimitQpm: number;
  lastUsedAt: string | null;
  expiresAt: string | null;
  status: string;
  createdAt: string;
}

export interface CreateApiKeyRequest {
  name: string;
  rateLimitQpm?: number;
  expiresAt?: string;
}

// ========== 任务 ==========
export type WorkflowType = 'SINGLE' | 'PIPELINE' | 'BATCH';
export type EngineType = 'IMAGE' | 'VIDEO';
export type TaskStatus =
  | 'CREATED' | 'VALIDATING' | 'PENDING' | 'PROCESSING'
  | 'DONE' | 'FAILED' | 'CANCELLED' | 'REVIEWING';

export interface TaskSubmitRequest {
  prompt: string;
  workflowType: WorkflowType;
  engineType: EngineType;
  size?: string;
  count?: number;
  style?: string;
  format?: string;
  quality?: string;
  negativePrompt?: string;
  aspectRatio?: string;
  duration?: number;
  fps?: number;
  resolution?: string;
  batchCount?: number;
  pipelineSteps?: Array<{ stepType: string; [key: string]: unknown }>;
}

export interface AgentTask {
  id: string;
  userId: string;
  tenantId: string;
  workflowType: WorkflowType;
  status: TaskStatus;
  requestParams: string;
  result: string | null;
  errorMsg: string | null;
  retryCount: number;
  priority: number;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
}

export interface WorkflowStep {
  id: string;
  taskId: string;
  stepOrder: number;
  stepType: string;
  status: string;
  input: string | null;
  output: string | null;
  startedAt: string | null;
  finishedAt: string | null;
}

export interface TaskDetail {
  task: AgentTask;
  steps: WorkflowStep[];
}

export interface TaskResult {
  status: TaskStatus;
  result: unknown;
  errorMsg: string | null;
  steps: WorkflowStep[];
}

// ========== 文件 ==========
export interface StorageFile {
  id: string;
  userId: string;
  tenantId: string;
  originalName: string;
  objectKey: string;
  contentType: string;
  fileSize: number;
  fileMd5: string | null;
  width: number | null;
  height: number | null;
  durationMs: number | null;
  thumbnailKey: string | null;
  provider: string;
  storageClass: string;
  visibility: string;
  expireAt: string | null;
  sourceTaskId: string | null;
  sourceType: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface UploadResult {
  id: string;
  objectKey: string;
  originalName: string;
  contentType: string;
  fileSize: number;
  storageClass: string;
}

// ========== LLM ==========
export interface LlmModel {
  model: string;
  provider: string;
  available: boolean;
}

export interface ChatRequest {
  provider?: string;
  systemPrompt?: string;
  message: string;
  options?: Record<string, unknown>;
}

export interface ChatResult {
  model: string;
  provider: string;
  content: string;
  durationMs: number;
}

// ========== 租户 ==========
export interface Tenant {
  id: string;
  name: string;
  status: string;
  quotaConfig: string;
  storageProvider: string;
  createdAt: string;
  updatedAt: string;
}

export interface TenantMember {
  userId: string;
  username: string;
  email: string;
  roleCode: string;
  joinedAt: string;
}

// ========== 管理 ==========
export interface TaskStats {
  total: number;
  pending: number;
  processing: number;
  done: number;
  failed: number;
  cancelled: number;
}

// ========== 错误码 ==========
export const ErrorCode = {
  PARAM_INVALID: 1001,
  TOKEN_EXPIRED: 2001,
  TOKEN_INVALID: 2002,
  NO_PERMISSION: 2003,
  API_KEY_INVALID: 2004,
  QUOTA_EXCEEDED: 3001,
  RATE_LIMITED: 3003,
  TASK_NOT_FOUND: 4001,
  FILE_NOT_FOUND: 4002,
  USER_NOT_FOUND: 4003,
  CONTENT_BLOCKED: 9001,
  SENSITIVE_PROMPT: 9002,
  INTERNAL_ERROR: 5099,
} as const;
```

- [ ] **Step 2: 创建 src/utils/token.ts**

```typescript
const ACCESS_TOKEN_KEY = 'access_token';
const REFRESH_TOKEN_KEY = 'refresh_token';
const EXPIRES_IN_KEY = 'token_expires_in';

export const tokenStorage = {
  getAccessToken(): string | null {
    return localStorage.getItem(ACCESS_TOKEN_KEY);
  },
  getRefreshToken(): string | null {
    return localStorage.getItem(REFRESH_TOKEN_KEY);
  },
  setTokens(accessToken: string, refreshToken: string, expiresIn: number): void {
    localStorage.setItem(ACCESS_TOKEN_KEY, accessToken);
    localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken);
    localStorage.setItem(EXPIRES_IN_KEY, String(expiresIn));
  },
  clearTokens(): void {
    localStorage.removeItem(ACCESS_TOKEN_KEY);
    localStorage.removeItem(REFRESH_TOKEN_KEY);
    localStorage.removeItem(EXPIRES_IN_KEY);
  },
  hasTokens(): boolean {
    return !!localStorage.getItem(ACCESS_TOKEN_KEY);
  },
};
```

- [ ] **Step 3: 创建 src/api/client.ts**

```typescript
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
```

---

### Task 3: Zustand 状态管理

**Files:**
- Create: `web-admin/src/stores/authStore.ts`
- Create: `web-admin/src/stores/appStore.ts`

**Produces:** 全局状态管理，authStore 持久化到 localStorage

- [ ] **Step 1: 创建 src/stores/authStore.ts**

```typescript
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
```

- [ ] **Step 2: 创建 src/stores/appStore.ts**

```typescript
import { create } from 'zustand';

interface AppState {
  sidebarCollapsed: boolean;
  toggleSidebar: () => void;
}

export const useAppStore = create<AppState>((set) => ({
  sidebarCollapsed: false,
  toggleSidebar: () => set((state) => ({ sidebarCollapsed: !state.sidebarCollapsed })),
}));
```

---

### Task 4: 路由配置与 AuthGuard

**Files:**
- Create: `web-admin/src/router/index.tsx`
- Create: `web-admin/src/components/AuthGuard/index.tsx`

**Produces:** 路由表 + 鉴权守卫组件

- [ ] **Step 1: 创建 src/components/AuthGuard/index.tsx**

```tsx
import React, { useEffect, useState } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { Spin } from 'antd';
import { useAuthStore } from '@/stores/authStore';
import http from '@/api/client';

const WHITE_LIST = ['/login', '/register'];

interface AuthGuardProps {
  children: React.ReactNode;
  requiredRoles?: string[];
}

const AuthGuard: React.FC<AuthGuardProps> = ({ children, requiredRoles }) => {
  const { isAuthenticated, user, setUser, logout } = useAuthStore();
  const location = useLocation();
  const [loading, setLoading] = useState(true);
  const [authorized, setAuthorized] = useState(true);

  useEffect(() => {
    const checkAuth = async () => {
      if (WHITE_LIST.includes(location.pathname)) {
        setLoading(false);
        return;
      }

      if (!isAuthenticated) {
        setLoading(false);
        return;
      }

      // 已有用户信息则直接校验角色
      if (user) {
        checkRole(user.roles || []);
        setLoading(false);
        return;
      }

      // 获取用户信息
      try {
        const res = await http.get('/users/me');
        const userData = res.data.data;
        const userInfo = {
          id: userData.id,
          username: userData.username,
          email: userData.email,
          phone: userData.phone || '',
          avatarUrl: userData.avatarUrl || '',
          status: userData.status,
          roles: userData.roles || [],
          tenantId: userData.tenantId,
        };
        setUser(userInfo);
        checkRole(userInfo.roles);
      } catch {
        logout();
      } finally {
        setLoading(false);
      }
    };

    checkAuth();
  }, [location.pathname]);

  const checkRole = (roles: string[]) => {
    if (requiredRoles && requiredRoles.length > 0) {
      const hasRole = requiredRoles.some((r) => roles.includes(r));
      setAuthorized(hasRole);
    }
  };

  if (loading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100vh' }}>
        <Spin size="large" tip="加载中..." />
      </div>
    );
  }

  if (WHITE_LIST.includes(location.pathname)) {
    return <>{children}</>;
  }

  if (!isAuthenticated) {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }

  if (!authorized) {
    return <Navigate to="/dashboard" replace />;
  }

  return <>{children}</>;
};

export default AuthGuard;
```

- [ ] **Step 2: 创建 src/router/index.tsx**

```tsx
import { lazy, Suspense } from 'react';
import { createBrowserRouter, Navigate } from 'react-router-dom';
import { Spin } from 'antd';
import AuthGuard from '@/components/AuthGuard';

const LazyLoad = (Component: React.LazyExoticComponent<React.ComponentType>) => (
  <Suspense fallback={<Spin size="large" style={{ display: 'block', margin: '200px auto' }} />}>
    <Component />
  </Suspense>
);

// 页面懒加载
const LoginPage = lazy(() => import('@/pages/auth/LoginPage'));
const RegisterPage = lazy(() => import('@/pages/auth/RegisterPage'));
const MainLayout = lazy(() => import('@/components/Layout/MainLayout'));
const DashboardPage = lazy(() => import('@/pages/dashboard/DashboardPage'));
const ImageGenPage = lazy(() => import('@/pages/workspace/ImageGenPage'));
const VideoGenPage = lazy(() => import('@/pages/workspace/VideoGenPage'));
const BatchGenPage = lazy(() => import('@/pages/workspace/BatchGenPage'));
const PipelinePage = lazy(() => import('@/pages/workspace/PipelinePage'));
const TaskListPage = lazy(() => import('@/pages/tasks/TaskListPage'));
const TaskDetailPage = lazy(() => import('@/pages/tasks/TaskDetailPage'));
const FileListPage = lazy(() => import('@/pages/files/FileListPage'));
const ModelListPage = lazy(() => import('@/pages/llm/ModelListPage'));
const ChatPage = lazy(() => import('@/pages/llm/ChatPage'));
const ProfilePage = lazy(() => import('@/pages/user/ProfilePage'));
const QuotaPage = lazy(() => import('@/pages/user/QuotaPage'));
const ApiKeyPage = lazy(() => import('@/pages/user/ApiKeyPage'));
const TenantInfoPage = lazy(() => import('@/pages/tenant/TenantInfoPage'));
const MemberPage = lazy(() => import('@/pages/tenant/MemberPage'));
const StatsPage = lazy(() => import('@/pages/admin/StatsPage'));
const ReviewPage = lazy(() => import('@/pages/admin/ReviewPage'));

export const router = createBrowserRouter([
  {
    path: '/login',
    element: <AuthGuard>{LazyLoad(LoginPage)}</AuthGuard>,
  },
  {
    path: '/register',
    element: <AuthGuard>{LazyLoad(RegisterPage)}</AuthGuard>,
  },
  {
    path: '/',
    element: <AuthGuard>{LazyLoad(MainLayout)}</AuthGuard>,
    children: [
      { index: true, element: <Navigate to="/dashboard" replace /> },
      { path: 'dashboard', element: LazyLoad(DashboardPage) },
      { path: 'workspace/image', element: LazyLoad(ImageGenPage) },
      { path: 'workspace/video', element: LazyLoad(VideoGenPage) },
      { path: 'workspace/batch', element: LazyLoad(BatchGenPage) },
      { path: 'workspace/pipeline', element: LazyLoad(PipelinePage) },
      { path: 'tasks', element: LazyLoad(TaskListPage) },
      { path: 'tasks/:id', element: LazyLoad(TaskDetailPage) },
      { path: 'files', element: LazyLoad(FileListPage) },
      { path: 'llm/models', element: LazyLoad(ModelListPage) },
      { path: 'llm/chat', element: LazyLoad(ChatPage) },
      { path: 'user/profile', element: LazyLoad(ProfilePage) },
      { path: 'user/quota', element: LazyLoad(QuotaPage) },
      { path: 'user/api-keys', element: LazyLoad(ApiKeyPage) },
      { path: 'tenant/info', element: LazyLoad(TenantInfoPage) },
      {
        path: 'tenant/members',
        element: (
          <AuthGuard requiredRoles={['TENANT_ADMIN', 'SUPER_ADMIN']}>
            {LazyLoad(MemberPage)}
          </AuthGuard>
        ),
      },
      {
        path: 'admin/stats',
        element: (
          <AuthGuard requiredRoles={['TENANT_ADMIN', 'SUPER_ADMIN']}>
            {LazyLoad(StatsPage)}
          </AuthGuard>
        ),
      },
      {
        path: 'admin/review',
        element: (
          <AuthGuard requiredRoles={['TENANT_ADMIN', 'SUPER_ADMIN']}>
            {LazyLoad(ReviewPage)}
          </AuthGuard>
        ),
      },
    ],
  },
]);
```

- [ ] **Step 3: 更新 src/App.tsx** 使用路由

```tsx
import { RouterProvider } from 'react-router-dom';
import { router } from '@/router';

const App = () => {
  return <RouterProvider router={router} />;
};

export default App;
```

---

### Task 5: 主布局（MainLayout + SideMenu）

**Files:**
- Create: `web-admin/src/components/Layout/MainLayout.tsx`
- Create: `web-admin/src/components/Layout/SideMenu.tsx`
- Create: `web-admin/src/components/Layout/MainLayout.module.css`

**Produces:** Ant Design 标准三栏布局（Sider + Header + Content）

- [ ] **Step 1: 创建 src/components/Layout/MainLayout.tsx**

```tsx
import { useState } from 'react';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { Layout, Button, Dropdown, Avatar, Space, theme } from 'antd';
import {
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  UserOutlined,
  LogoutOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import { useAuthStore } from '@/stores/authStore';
import { useAppStore } from '@/stores/appStore';
import SideMenu from './SideMenu';
import styles from './MainLayout.module.css';

const { Header, Sider, Content } = Layout;

const MainLayout: React.FC = () => {
  const [collapsed, setCollapsed] = useState(false);
  const { user, logout } = useAuthStore();
  const navigate = useNavigate();
  const {
    token: { colorBgContainer, borderRadiusLG },
  } = theme.useToken();

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  const userMenuItems = {
    items: [
      {
        key: 'profile',
        icon: <UserOutlined />,
        label: '个人信息',
        onClick: () => navigate('/user/profile'),
      },
      {
        key: 'settings',
        icon: <SettingOutlined />,
        label: '配额管理',
        onClick: () => navigate('/user/quota'),
      },
      { type: 'divider' as const },
      {
        key: 'logout',
        icon: <LogoutOutlined />,
        label: '退出登录',
        onClick: handleLogout,
      },
    ],
  };

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider
        trigger={null}
        collapsible
        collapsed={collapsed}
        theme="dark"
        width={220}
        style={{
          overflow: 'auto',
          height: '100vh',
          position: 'fixed',
          left: 0,
          top: 0,
          bottom: 0,
          zIndex: 100,
        }}
      >
        <div className={styles.logo}>
          {collapsed ? 'AI' : 'AI Agent 创作平台'}
        </div>
        <SideMenu />
      </Sider>
      <Layout style={{ marginLeft: collapsed ? 80 : 220, transition: 'margin-left 0.2s' }}>
        <Header
          style={{
            padding: '0 24px',
            background: colorBgContainer,
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            position: 'sticky',
            top: 0,
            zIndex: 99,
            boxShadow: '0 1px 4px rgba(0,0,0,0.08)',
          }}
        >
          <Button
            type="text"
            icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
            onClick={() => setCollapsed(!collapsed)}
          />
          <Dropdown menu={userMenuItems} placement="bottomRight">
            <Space style={{ cursor: 'pointer' }}>
              <Avatar icon={<UserOutlined />} />
              <span>{user?.username || '用户'}</span>
            </Space>
          </Dropdown>
        </Header>
        <Content
          style={{
            margin: 24,
            padding: 24,
            background: colorBgContainer,
            borderRadius: borderRadiusLG,
            minHeight: 280,
          }}
        >
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
};

export default MainLayout;
```

- [ ] **Step 2: 创建 src/components/Layout/SideMenu.tsx**

```tsx
import { useNavigate, useLocation } from 'react-router-dom';
import { Menu } from 'antd';
import {
  DashboardOutlined,
  PictureOutlined,
  VideoCameraOutlined,
  AppstoreOutlined,
  NodeIndexOutlined,
  UnorderedListOutlined,
  FolderOpenOutlined,
  ClusterOutlined,
  MessageOutlined,
  UserOutlined,
  KeyOutlined,
  TeamOutlined,
  UsergroupAddOutlined,
  BarChartOutlined,
  AuditOutlined,
} from '@ant-design/icons';
import { useAuthStore } from '@/stores/authStore';

const SideMenu: React.FC = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const { user } = useAuthStore();
  const roles = user?.roles || [];

  const isAdmin = roles.includes('TENANT_ADMIN') || roles.includes('SUPER_ADMIN');

  const menuItems = [
    { key: '/dashboard', icon: <DashboardOutlined />, label: '首页仪表盘' },
    {
      type: 'group' as const,
      label: 'AI 创作',
      children: [
        { key: '/workspace/image', icon: <PictureOutlined />, label: '图片生成' },
        { key: '/workspace/video', icon: <VideoCameraOutlined />, label: '视频生成' },
        { key: '/workspace/batch', icon: <AppstoreOutlined />, label: '批量生成' },
        { key: '/workspace/pipeline', icon: <NodeIndexOutlined />, label: '流水线' },
      ],
    },
    { key: '/tasks', icon: <UnorderedListOutlined />, label: '任务管理' },
    { key: '/files', icon: <FolderOpenOutlined />, label: '文件管理' },
    {
      type: 'group' as const,
      label: 'LLM 对话',
      children: [
        { key: '/llm/models', icon: <ClusterOutlined />, label: '模型广场' },
        { key: '/llm/chat', icon: <MessageOutlined />, label: 'AI 对话' },
      ],
    },
    {
      type: 'group' as const,
      label: '个人中心',
      children: [
        { key: '/user/profile', icon: <UserOutlined />, label: '个人信息' },
        { key: '/user/quota', icon: <DashboardOutlined />, label: '配额管理' },
        { key: '/user/api-keys', icon: <KeyOutlined />, label: 'API Key' },
      ],
    },
    {
      type: 'group' as const,
      label: '租户管理',
      children: [
        { key: '/tenant/info', icon: <TeamOutlined />, label: '租户信息' },
        ...(isAdmin
          ? [{ key: '/tenant/members' as const, icon: <UsergroupAddOutlined />, label: '成员管理' }]
          : []),
      ],
    },
    ...(isAdmin
      ? [
          {
            type: 'group' as const,
            label: '管理后台',
            children: [
              { key: '/admin/stats' as const, icon: <BarChartOutlined />, label: '系统统计' },
              { key: '/admin/review' as const, icon: <AuditOutlined />, label: '任务审核' },
            ],
          },
        ]
      : []),
  ];

  return (
    <Menu
      theme="dark"
      mode="inline"
      selectedKeys={[location.pathname]}
      defaultOpenKeys={['/workspace', '/llm', '/user', '/tenant', '/admin']}
      items={menuItems}
      onClick={({ key }) => navigate(key)}
    />
  );
};

export default SideMenu;
```

- [ ] **Step 3: 创建 src/components/Layout/MainLayout.module.css**

```css
.logo {
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 18px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
  padding: 0 16px;
}
```

---

### Task 6: API 请求模块（按服务拆分）

**Files:**
- Create: `web-admin/src/api/auth.ts`
- Create: `web-admin/src/api/user.ts`
- Create: `web-admin/src/api/task.ts`
- Create: `web-admin/src/api/file.ts`
- Create: `web-admin/src/api/tenant.ts`
- Create: `web-admin/src/api/llm.ts`
- Create: `web-admin/src/api/admin.ts`

**Produces:** 所有后端 API 封装函数

- [ ] **Step 1: 创建 src/api/auth.ts**

```typescript
import http from './client';
import { ApiResult, LoginRequest, RegisterRequest, RefreshTokenRequest, TokenPair } from '@/types/api';

export const authApi = {
  login: (data: LoginRequest) => http.post<ApiResult<TokenPair>>('/auth/login', data),
  register: (data: RegisterRequest) => http.post<ApiResult<string>>('/auth/register', data),
  refresh: (data: RefreshTokenRequest) => http.post<ApiResult<TokenPair>>('/auth/refresh', data),
  logout: (data?: RefreshTokenRequest) => http.post<ApiResult<null>>('/auth/logout', data || {}),
};
```

- [ ] **Step 2: 创建 src/api/user.ts**

```typescript
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
```

- [ ] **Step 3: 创建 src/api/task.ts**

```typescript
import http from './client';
import { ApiResult, PageResult, TaskSubmitRequest, AgentTask, TaskDetail, TaskResult } from '@/types/api';

export const taskApi = {
  submitImage: (data: TaskSubmitRequest) => http.post<ApiResult<TaskDetail>>('/tasks/image', data),
  submitVideo: (data: TaskSubmitRequest) => http.post<ApiResult<TaskDetail>>('/tasks/video', data),
  submitBatch: (data: TaskSubmitRequest) => http.post<ApiResult<TaskDetail>>('/tasks/batch', data),
  submitWorkflow: (data: TaskSubmitRequest) => http.post<ApiResult<TaskDetail>>('/tasks/workflow', data),
  list: (params: { page?: number; size?: number; status?: string }) =>
    http.get<ApiResult<PageResult<AgentTask>>>('/tasks', { params }),
  getById: (id: string) => http.get<ApiResult<TaskDetail>>(`/tasks/${id}`),
  getResult: (id: string) => http.get<ApiResult<TaskResult>>(`/tasks/${id}/result`),
  cancel: (id: string) => http.delete<ApiResult<null>>(`/tasks/${id}`),
};
```

- [ ] **Step 4: 创建 src/api/file.ts**

```typescript
import http from './client';
import { ApiResult, PageResult, StorageFile, UploadResult } from '@/types/api';

export const fileApi = {
  upload: (file: File, storageClass: string = 'ASSET') => {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('storageClass', storageClass);
    return http.post<ApiResult<UploadResult>>('/files/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  },
  getPresignedUrl: (filename: string, contentType: string = 'application/octet-stream') =>
    http.get<ApiResult<{ uploadUrl: string }>>('/files/presign', { params: { filename, contentType } }),
  presignCallback: (fileId: string) =>
    http.post<ApiResult<null>>('/files/presign/callback', null, { params: { fileId } }),
  list: (params: { page?: number; size?: number }) =>
    http.get<ApiResult<PageResult<StorageFile>>>('/files', { params }),
  getById: (id: string) => http.get<ApiResult<StorageFile>>(`/files/${id}`),
  getDownloadUrl: (id: string) => `/api/v1/files/${id}/download`,
  delete: (id: string) => http.delete<ApiResult<null>>(`/files/${id}`),
};
```

- [ ] **Step 5: 创建 src/api/tenant.ts**

```typescript
import http from './client';
import { ApiResult, Tenant, TenantMember } from '@/types/api';

export const tenantApi = {
  getTenant: (id: string) => http.get<ApiResult<Tenant>>(`/tenants/${id}`),
  updateTenant: (id: string, data: Record<string, unknown>) => http.put<ApiResult<null>>(`/tenants/${id}`, data),
  getMembers: (id: string) => http.get<ApiResult<TenantMember[]>>(`/tenants/${id}/members`),
  addMember: (id: string, data: { userId: string; roleCode: string }) =>
    http.post<ApiResult<TenantMember>>(`/tenants/${id}/members`, data),
  removeMember: (id: string, userId: string) => http.delete<ApiResult<null>>(`/tenants/${id}/members/${userId}`),
};
```

- [ ] **Step 6: 创建 src/api/llm.ts**

```typescript
import http from './client';
import { ApiResult, LlmModel, ChatRequest, ChatResult } from '@/types/api';

export const llmApi = {
  listModels: () => http.get<ApiResult<LlmModel[]>>('/llm/models'),
  chat: (data: ChatRequest) => http.post<ApiResult<ChatResult>>('/llm/chat', data),
};
```

- [ ] **Step 7: 创建 src/api/admin.ts**

```typescript
import http from './client';
import { ApiResult, PageResult, AgentTask, TaskStats } from '@/types/api';

export const adminApi = {
  getStats: () => http.get<ApiResult<TaskStats>>('/admin/stats'),
  listAllTasks: (params: { page?: number; size?: number; status?: string; userId?: string }) =>
    http.get<ApiResult<PageResult<AgentTask>>>('/admin/tasks', { params }),
  reviewTask: (id: string, action: 'PASS' | 'REJECT') =>
    http.put<ApiResult<null>>(`/admin/tasks/${id}/review`, { action }),
};
```

---

### Task 7: 登录与注册页面

**Files:**
- Create: `web-admin/src/pages/auth/LoginPage.tsx`
- Create: `web-admin/src/pages/auth/RegisterPage.tsx`

**Produces:** 可用的登录/注册流程

- [ ] **Step 1: 创建 src/pages/auth/LoginPage.tsx**

```tsx
import { useState } from 'react';
import { useNavigate, Link, useLocation } from 'react-router-dom';
import { Form, Input, Button, Card, Typography, message } from 'antd';
import { UserOutlined, LockOutlined } from '@ant-design/icons';
import { useAuthStore } from '@/stores/authStore';
import { authApi } from '@/api/auth';

const { Title, Text } = Typography;

const LoginPage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const { setTokens, setUser } = useAuthStore();
  const navigate = useNavigate();
  const location = useLocation();

  const from = (location.state as { from?: { pathname: string } })?.from?.pathname || '/dashboard';

  const onFinish = async (values: { username: string; password: string }) => {
    setLoading(true);
    try {
      const res = await authApi.login(values);
      const { accessToken, refreshToken, expiresIn } = res.data.data;
      setTokens({ accessToken, refreshToken, expiresIn });

      // 获取用户信息
      const userRes = await import('@/api/user').then((m) =>
        m.userApi.getMe()
      );
      const userData = userRes.data.data;
      setUser({
        id: userData.id as string,
        username: userData.username as string,
        email: userData.email as string,
        phone: (userData.phone as string) || '',
        avatarUrl: (userData.avatarUrl as string) || '',
        status: userData.status as string,
        roles: (userData.roles as string[]) || [],
        tenantId: userData.tenantId as string,
      });

      message.success('登录成功');
      navigate(from, { replace: true });
    } catch {
      // 错误已在拦截器中处理
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{ minHeight: '100vh', display: 'flex', justifyContent: 'center', alignItems: 'center', background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)' }}>
      <Card style={{ width: 400, boxShadow: '0 8px 24px rgba(0,0,0,0.15)' }}>
        <div style={{ textAlign: 'center', marginBottom: 32 }}>
          <Title level={2} style={{ marginBottom: 4 }}>AI Agent 创作平台</Title>
          <Text type="secondary">登录您的账户</Text>
        </div>
        <Form name="login" onFinish={onFinish} size="large">
          <Form.Item name="username" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input prefix={<UserOutlined />} placeholder="用户名" />
          </Form.Item>
          <Form.Item name="password" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password prefix={<LockOutlined />} placeholder="密码" />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" loading={loading} block>
              登 录
            </Button>
          </Form.Item>
        </Form>
        <div style={{ textAlign: 'center' }}>
          <Text type="secondary">
            还没有账户？<Link to="/register">立即注册</Link>
          </Text>
        </div>
      </Card>
    </div>
  );
};

export default LoginPage;
```

- [ ] **Step 2: 创建 src/pages/auth/RegisterPage.tsx**

```tsx
import { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { Form, Input, Button, Card, Typography, message } from 'antd';
import { UserOutlined, LockOutlined, MailOutlined, PhoneOutlined } from '@ant-design/icons';
import { authApi } from '@/api/auth';

const { Title, Text } = Typography;

const RegisterPage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();

  const onFinish = async (values: { username: string; password: string; email: string; phone?: string }) => {
    setLoading(true);
    try {
      await authApi.register(values);
      message.success('注册成功，请登录');
      navigate('/login');
    } catch {
      // 错误已在拦截器中处理
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{ minHeight: '100vh', display: 'flex', justifyContent: 'center', alignItems: 'center', background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)' }}>
      <Card style={{ width: 420, boxShadow: '0 8px 24px rgba(0,0,0,0.15)' }}>
        <div style={{ textAlign: 'center', marginBottom: 32 }}>
          <Title level={2} style={{ marginBottom: 4 }}>创建账户</Title>
          <Text type="secondary">加入 AI Agent 创作平台</Text>
        </div>
        <Form name="register" onFinish={onFinish} size="large">
          <Form.Item name="username" rules={[
            { required: true, message: '请输入用户名' },
            { min: 3, max: 32, message: '用户名长度 3-32 个字符' },
          ]}>
            <Input prefix={<UserOutlined />} placeholder="用户名 (3-32 字符)" />
          </Form.Item>
          <Form.Item name="password" rules={[
            { required: true, message: '请输入密码' },
            { min: 8, max: 64, message: '密码长度 8-64 个字符' },
          ]}>
            <Input.Password prefix={<LockOutlined />} placeholder="密码 (8-64 字符)" />
          </Form.Item>
          <Form.Item name="confirmPassword" dependencies={['password']} rules={[
            { required: true, message: '请确认密码' },
            ({ getFieldValue }) => ({
              validator(_, value) {
                if (!value || getFieldValue('password') === value) return Promise.resolve();
                return Promise.reject(new Error('两次输入的密码不一致'));
              },
            }),
          ]}>
            <Input.Password prefix={<LockOutlined />} placeholder="确认密码" />
          </Form.Item>
          <Form.Item name="email" rules={[
            { required: true, message: '请输入邮箱' },
            { type: 'email', message: '请输入有效的邮箱地址' },
          ]}>
            <Input prefix={<MailOutlined />} placeholder="邮箱" />
          </Form.Item>
          <Form.Item name="phone">
            <Input prefix={<PhoneOutlined />} placeholder="手机号（可选）" />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" loading={loading} block>
              注 册
            </Button>
          </Form.Item>
        </Form>
        <div style={{ textAlign: 'center' }}>
          <Text type="secondary">
            已有账户？<Link to="/login">返回登录</Link>
          </Text>
        </div>
      </Card>
    </div>
  );
};

export default RegisterPage;
```

---

### Task 8: 首页仪表盘

**Files:**
- Create: `web-admin/src/pages/dashboard/DashboardPage.tsx`

**Produces:** 统计卡片 + 快速入口 + 最近任务列表

- [ ] **Step 1: 创建 src/pages/dashboard/DashboardPage.tsx**

```tsx
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Row, Col, Card, Statistic, Typography, Table, Tag, Button, Space } from 'antd';
import {
  PictureOutlined,
  VideoCameraOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  PlusOutlined,
} from '@ant-design/icons';
import { taskApi } from '@/api/task';
import { adminApi } from '@/api/admin';
import { userApi } from '@/api/user';
import { AgentTask, TaskStats, QuotaInfo } from '@/types/api';
import { useAuthStore } from '@/stores/authStore';
import dayjs from 'dayjs';

const { Title } = Typography;

const statusColorMap: Record<string, string> = {
  CREATED: 'default',
  VALIDATING: 'processing',
  PENDING: 'warning',
  PROCESSING: 'processing',
  DONE: 'success',
  FAILED: 'error',
  CANCELLED: 'default',
  REVIEWING: 'warning',
};

const DashboardPage: React.FC = () => {
  const [stats, setStats] = useState<TaskStats | null>(null);
  const [recentTasks, setRecentTasks] = useState<AgentTask[]>([]);
  const [quota, setQuota] = useState<QuotaInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const navigate = useNavigate();
  const { user } = useAuthStore();
  const roles = user?.roles || [];
  const isAdmin = roles.includes('TENANT_ADMIN') || roles.includes('SUPER_ADMIN');

  useEffect(() => {
    const load = async () => {
      try {
        const [quotaRes, taskRes] = await Promise.all([
          userApi.getQuota('IMAGE').catch(() => null),
          taskApi.list({ page: 1, size: 5 }).catch(() => null),
        ]);
        if (quotaRes) setQuota(quotaRes.data.data);
        if (taskRes) setRecentTasks(taskRes.data.data.records);

        if (isAdmin) {
          const statsRes = await adminApi.getStats().catch(() => null);
          if (statsRes) setStats(statsRes.data.data);
        }
      } finally {
        setLoading(false);
      }
    };
    load();
  }, []);

  const columns = [
    { title: '任务ID', dataIndex: 'id', key: 'id', width: 200, render: (v: string) => v.slice(0, 12) + '...' },
    { title: '类型', dataIndex: 'workflowType', key: 'type', width: 80 },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (s: string) => <Tag color={statusColorMap[s] || 'default'}>{s}</Tag>,
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 180,
      render: (v: string) => dayjs(v).format('YYYY-MM-DD HH:mm'),
    },
  ];

  return (
    <div>
      <Title level={4} style={{ marginBottom: 24 }}>首页仪表盘</Title>

      {isAdmin && stats && (
        <Row gutter={16} style={{ marginBottom: 24 }}>
          <Col span={4}><Card><Statistic title="总任务" value={stats.total} /></Card></Col>
          <Col span={4}><Card><Statistic title="待处理" value={stats.pending} valueStyle={{ color: '#faad14' }} /></Card></Col>
          <Col span={4}><Card><Statistic title="处理中" value={stats.processing} valueStyle={{ color: '#1677ff' }} /></Card></Col>
          <Col span={4}><Card><Statistic title="已完成" value={stats.done} valueStyle={{ color: '#52c41a' }} /></Card></Col>
          <Col span={4}><Card><Statistic title="失败" value={stats.failed} valueStyle={{ color: '#ff4d4f' }} /></Card></Col>
          <Col span={4}><Card><Statistic title="已取消" value={stats.cancelled} /></Card></Col>
        </Row>
      )}

      <Row gutter={16} style={{ marginBottom: 24 }}>
        <Col span={6}>
          <Card hoverable onClick={() => navigate('/workspace/image')}>
            <Statistic title="图片生成配额剩余" value={quota?.remaining ?? '-'} prefix={<PictureOutlined />} />
          </Card>
        </Col>
        <Col span={6}>
          <Card hoverable onClick={() => navigate('/workspace/video')}>
            <Statistic title="视频生成配额剩余" value={quota?.remaining ?? '-'} prefix={<VideoCameraOutlined />} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title="进行中任务" value={stats?.processing ?? '-'} prefix={<ClockCircleOutlined />} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title="已完成任务" value={stats?.done ?? '-'} prefix={<CheckCircleOutlined />} />
          </Card>
        </Col>
      </Row>

      <Row gutter={16}>
        <Col span={12}>
          <Card
            title="快速开始"
            actions={[
              <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/workspace/image')}>图片生成</Button>,
              <Button icon={<PlusOutlined />} onClick={() => navigate('/workspace/video')}>视频生成</Button>,
              <Button icon={<PlusOutlined />} onClick={() => navigate('/workspace/batch')}>批量生成</Button>,
            ]}
          >
            <p>选择一种创作方式开始生成 AI 内容</p>
          </Card>
        </Col>
        <Col span={12}>
          <Card
            title="最近任务"
            extra={<Button type="link" onClick={() => navigate('/tasks')}>查看全部</Button>}
          >
            <Table
              dataSource={recentTasks}
              columns={columns}
              rowKey="id"
              loading={loading}
              size="small"
              pagination={false}
              onRow={(record) => ({
                onClick: () => navigate(`/tasks/${record.id}`),
                style: { cursor: 'pointer' },
              })}
            />
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default DashboardPage;
```

---

### Task 9: AI 创作工作台页面

**Files:**
- Create: `web-admin/src/pages/workspace/ImageGenPage.tsx`
- Create: `web-admin/src/pages/workspace/VideoGenPage.tsx`
- Create: `web-admin/src/pages/workspace/BatchGenPage.tsx`
- Create: `web-admin/src/pages/workspace/PipelinePage.tsx`

**Produces:** 4 个创作页面（图片、视频、批量、流水线）

- [ ] **Step 1: 创建 src/pages/workspace/ImageGenPage.tsx**

```tsx
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card, Form, Input, Select, Slider, Button, Row, Col, Typography, message, Space } from 'antd';
import { SendOutlined, ClearOutlined } from '@ant-design/icons';
import { taskApi } from '@/api/task';

const { Title } = Typography;
const { TextArea } = Input;

const ImageGenPage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const [form] = Form.useForm();
  const navigate = useNavigate();

  const onFinish = async (values: Record<string, unknown>) => {
    setLoading(true);
    try {
      const res = await taskApi.submitImage({
        prompt: values.prompt as string,
        workflowType: 'SINGLE',
        engineType: 'IMAGE',
        size: values.size as string,
        count: values.count as number,
        style: values.style as string,
        format: values.format as string,
        quality: values.quality as string,
        negativePrompt: values.negativePrompt as string,
        aspectRatio: values.aspectRatio as string,
      });
      message.success('任务提交成功');
      const taskId = res.data.data?.task?.id;
      if (taskId) navigate(`/tasks/${taskId}`);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      <Title level={4} style={{ marginBottom: 24 }}>AI 图片生成</Title>
      <Row gutter={24}>
        <Col span={16}>
          <Card>
            <Form form={form} layout="vertical" onFinish={onFinish}
              initialValues={{ count: 1, size: '1024x1024', format: 'png', quality: 'standard', aspectRatio: '1:1' }}>
              <Form.Item name="prompt" label="提示词" rules={[{ required: true, message: '请输入提示词' }]}>
                <TextArea rows={4} placeholder="描述你想要生成的图片内容..." maxLength={2000} showCount />
              </Form.Item>
              <Form.Item name="negativePrompt" label="负面提示词">
                <TextArea rows={2} placeholder="描述你不希望出现的元素（可选）" maxLength={500} />
              </Form.Item>
              <Row gutter={16}>
                <Col span={8}>
                  <Form.Item name="size" label="尺寸">
                    <Select options={[
                      { value: '512x512', label: '512×512' },
                      { value: '768x768', label: '768×768' },
                      { value: '1024x1024', label: '1024×1024' },
                      { value: '2048x2048', label: '2048×2048' },
                    ]} />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item name="style" label="风格">
                    <Select allowClear placeholder="选择风格" options={[
                      { value: 'realistic', label: '写实' },
                      { value: 'anime', label: '动漫' },
                      { value: 'oil_painting', label: '油画' },
                      { value: 'watercolor', label: '水彩' },
                    ]} />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item name="aspectRatio" label="宽高比">
                    <Select options={[
                      { value: '1:1', label: '1:1 正方形' },
                      { value: '16:9', label: '16:9 横屏' },
                      { value: '9:16', label: '9:16 竖屏' },
                      { value: '4:3', label: '4:3' },
                    ]} />
                  </Form.Item>
                </Col>
              </Row>
              <Row gutter={16}>
                <Col span={8}>
                  <Form.Item name="count" label="生成数量">
                    <Slider min={1} max={10} marks={{ 1: '1', 5: '5', 10: '10' }} />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item name="quality" label="画质">
                    <Select options={[
                      { value: 'standard', label: '标准' },
                      { value: 'hd', label: '高清' },
                    ]} />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item name="format" label="输出格式">
                    <Select options={[
                      { value: 'png', label: 'PNG' },
                      { value: 'jpeg', label: 'JPEG' },
                      { value: 'webp', label: 'WebP' },
                    ]} />
                  </Form.Item>
                </Col>
              </Row>
              <Form.Item>
                <Space>
                  <Button type="primary" htmlType="submit" icon={<SendOutlined />} loading={loading}>
                    提交生成
                  </Button>
                  <Button icon={<ClearOutlined />} onClick={() => form.resetFields()}>重置</Button>
                </Space>
              </Form.Item>
            </Form>
          </Card>
        </Col>
        <Col span={8}>
          <Card title="使用说明">
            <ul>
              <li>提示词建议使用英文，描述越详细效果越好</li>
              <li>生成一个图片消耗 1 次图片配额</li>
              <li>高清画质生成时间更长但质量更好</li>
              <li>负面提示词可用于排除不想要的元素</li>
            </ul>
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default ImageGenPage;
```

- [ ] **Step 2: 创建 src/pages/workspace/VideoGenPage.tsx**（类似图片，参数不同）

```tsx
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card, Form, Input, Select, Slider, Button, Row, Col, Typography, message, Space } from 'antd';
import { SendOutlined, ClearOutlined } from '@ant-design/icons';
import { taskApi } from '@/api/task';

const { Title } = Typography;
const { TextArea } = Input;

const VideoGenPage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const [form] = Form.useForm();
  const navigate = useNavigate();

  const onFinish = async (values: Record<string, unknown>) => {
    setLoading(true);
    try {
      const res = await taskApi.submitVideo({
        prompt: values.prompt as string,
        workflowType: 'SINGLE',
        engineType: 'VIDEO',
        duration: values.duration as number,
        fps: values.fps as number,
        resolution: values.resolution as string,
        style: values.style as string,
        negativePrompt: values.negativePrompt as string,
      });
      message.success('视频生成任务提交成功');
      const taskId = res.data.data?.task?.id;
      if (taskId) navigate(`/tasks/${taskId}`);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      <Title level={4} style={{ marginBottom: 24 }}>AI 视频生成</Title>
      <Row gutter={24}>
        <Col span={16}>
          <Card>
            <Form form={form} layout="vertical" onFinish={onFinish}
              initialValues={{ duration: 5, fps: 24, resolution: '1920x1080' }}>
              <Form.Item name="prompt" label="提示词" rules={[{ required: true, message: '请输入提示词' }]}>
                <TextArea rows={4} placeholder="描述你想要生成的视频内容..." maxLength={2000} showCount />
              </Form.Item>
              <Form.Item name="negativePrompt" label="负面提示词">
                <TextArea rows={2} placeholder="描述你不希望出现的元素（可选）" maxLength={500} />
              </Form.Item>
              <Row gutter={16}>
                <Col span={8}>
                  <Form.Item name="duration" label="时长（秒）">
                    <Slider min={1} max={30} marks={{ 1: '1s', 5: '5s', 15: '15s', 30: '30s' }} />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item name="fps" label="帧率">
                    <Select options={[{ value: 24, label: '24 fps' }, { value: 30, label: '30 fps' }]} />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item name="resolution" label="分辨率">
                    <Select options={[
                      { value: '1920x1080', label: '1080p' },
                      { value: '1280x720', label: '720p' },
                    ]} />
                  </Form.Item>
                </Col>
              </Row>
              <Form.Item name="style" label="风格">
                <Select allowClear placeholder="选择风格" options={[
                  { value: 'realistic', label: '写实' },
                  { value: 'anime', label: '动漫' },
                  { value: 'cinematic', label: '电影感' },
                ]} />
              </Form.Item>
              <Form.Item>
                <Space>
                  <Button type="primary" htmlType="submit" icon={<SendOutlined />} loading={loading}>
                    提交生成
                  </Button>
                  <Button icon={<ClearOutlined />} onClick={() => form.resetFields()}>重置</Button>
                </Space>
              </Form.Item>
            </Form>
          </Card>
        </Col>
        <Col span={8}>
          <Card title="使用说明">
            <ul>
              <li>视频生成耗时较长，请耐心等待</li>
              <li>生成一个视频消耗 1 次视频配额</li>
              <li>建议从较短时长开始尝试</li>
              <li>1080p 分辨率视频需要更多处理时间</li>
            </ul>
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default VideoGenPage;
```

- [ ] **Step 3: 创建 src/pages/workspace/BatchGenPage.tsx**

```tsx
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card, Form, Input, Select, InputNumber, Button, Row, Col, Typography, message, Space } from 'antd';
import { SendOutlined, ClearOutlined } from '@ant-design/icons';
import { taskApi } from '@/api/task';

const { Title } = Typography;
const { TextArea } = Input;

const BatchGenPage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const [form] = Form.useForm();
  const navigate = useNavigate();

  const onFinish = async (values: Record<string, unknown>) => {
    setLoading(true);
    try {
      const res = await taskApi.submitBatch({
        prompt: values.prompt as string,
        workflowType: 'BATCH',
        engineType: values.engineType as 'IMAGE' | 'VIDEO',
        batchCount: values.batchCount as number,
        size: values.size as string,
        style: values.style as string,
        quality: values.quality as string,
        format: values.format as string,
        resolution: values.resolution as string,
        duration: values.duration as number,
      });
      message.success('批量任务提交成功');
      const taskId = res.data.data?.task?.id;
      if (taskId) navigate(`/tasks/${taskId}`);
    } finally {
      setLoading(false);
    }
  };

  const engineType = Form.useWatch('engineType', form);

  return (
    <div>
      <Title level={4} style={{ marginBottom: 24 }}>批量生成</Title>
      <Row gutter={24}>
        <Col span={16}>
          <Card>
            <Form form={form} layout="vertical" onFinish={onFinish}
              initialValues={{ engineType: 'IMAGE', batchCount: 4, size: '1024x1024', quality: 'standard' }}>
              <Form.Item name="prompt" label="提示词" rules={[{ required: true, message: '请输入提示词' }]}>
                <TextArea rows={4} placeholder="描述你想要生成的内容..." maxLength={2000} showCount />
              </Form.Item>
              <Row gutter={16}>
                <Col span={8}>
                  <Form.Item name="engineType" label="引擎类型">
                    <Select options={[{ value: 'IMAGE', label: '图片' }, { value: 'VIDEO', label: '视频' }]} />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item name="batchCount" label="批量数量">
                    <InputNumber min={2} max={100} style={{ width: '100%' }} />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item name="style" label="风格">
                    <Select allowClear placeholder="选择风格" options={[
                      { value: 'realistic', label: '写实' },
                      { value: 'anime', label: '动漫' },
                    ]} />
                  </Form.Item>
                </Col>
              </Row>
              {engineType === 'IMAGE' ? (
                <Row gutter={16}>
                  <Col span={8}>
                    <Form.Item name="size" label="尺寸">
                      <Select options={[
                        { value: '512x512', label: '512×512' },
                        { value: '1024x1024', label: '1024×1024' },
                      ]} />
                    </Form.Item>
                  </Col>
                  <Col span={8}>
                    <Form.Item name="quality" label="画质">
                      <Select options={[{ value: 'standard', label: '标准' }, { value: 'hd', label: '高清' }]} />
                    </Form.Item>
                  </Col>
                  <Col span={8}>
                    <Form.Item name="format" label="格式">
                      <Select options={[{ value: 'png', label: 'PNG' }, { value: 'jpeg', label: 'JPEG' }]} />
                    </Form.Item>
                  </Col>
                </Row>
              ) : (
                <Row gutter={16}>
                  <Col span={12}>
                    <Form.Item name="duration" label="时长（秒）">
                      <InputNumber min={1} max={30} defaultValue={5} style={{ width: '100%' }} />
                    </Form.Item>
                  </Col>
                  <Col span={12}>
                    <Form.Item name="resolution" label="分辨率">
                      <Select options={[{ value: '1920x1080', label: '1080p' }, { value: '1280x720', label: '720p' }]} />
                    </Form.Item>
                  </Col>
                </Row>
              )}
              <Form.Item>
                <Space>
                  <Button type="primary" htmlType="submit" icon={<SendOutlined />} loading={loading}>
                    提交批量生成
                  </Button>
                  <Button icon={<ClearOutlined />} onClick={() => form.resetFields()}>重置</Button>
                </Space>
              </Form.Item>
            </Form>
          </Card>
        </Col>
        <Col span={8}>
          <Card title="批量生成说明">
            <ul>
              <li>批量生成可一次提交多个相同参数的任务</li>
              <li>图片批量最多 100 张，视频批量最多 10 个</li>
              <li>每个子任务独立消耗配额</li>
              <li>任务将以并行方式处理</li>
            </ul>
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default BatchGenPage;
```

- [ ] **Step 4: 创建 src/pages/workspace/PipelinePage.tsx**

```tsx
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card, Form, Input, Select, Button, Row, Col, Typography, message, Space, Steps } from 'antd';
import { SendOutlined, ClearOutlined, PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import { taskApi } from '@/api/task';

const { Title } = Typography;
const { TextArea } = Input;

const PipelinePage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const [form] = Form.useForm();
  const navigate = useNavigate();
  const [steps, setSteps] = useState<Array<{ stepType: string; prompt?: string }>>([
    { stepType: 'IMAGE' },
    { stepType: 'VIDEO' },
  ]);

  const addStep = () => setSteps([...steps, { stepType: 'IMAGE' }]);
  const removeStep = (index: number) => setSteps(steps.filter((_, i) => i !== index));

  const onFinish = async (values: { prompt: string }) => {
    if (steps.length < 2) {
      message.warning('流水线至少需要 2 个步骤');
      return;
    }
    setLoading(true);
    try {
      const res = await taskApi.submitWorkflow({
        prompt: values.prompt,
        workflowType: 'PIPELINE',
        engineType: 'IMAGE',
        pipelineSteps: steps,
      });
      message.success('流水线任务提交成功');
      const taskId = res.data.data?.task?.id;
      if (taskId) navigate(`/tasks/${taskId}`);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      <Title level={4} style={{ marginBottom: 24 }}>流水线编排</Title>
      <Row gutter={24}>
        <Col span={16}>
          <Card>
            <Form form={form} layout="vertical" onFinish={onFinish}>
              <Form.Item name="prompt" label="初始提示词" rules={[{ required: true, message: '请输入提示词' }]}>
                <TextArea rows={3} placeholder="描述整体创作意图..." maxLength={2000} showCount />
              </Form.Item>

              <div style={{ marginBottom: 24 }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
                  <span style={{ fontWeight: 500 }}>流水线步骤 ({steps.length})</span>
                  <Button type="dashed" size="small" icon={<PlusOutlined />} onClick={addStep}>添加步骤</Button>
                </div>
                <Steps
                  direction="vertical"
                  current={-1}
                  items={steps.map((step, i) => ({
                    title: (
                      <Space>
                        <Select
                          value={step.stepType}
                          onChange={(v) => {
                            const newSteps = [...steps];
                            newSteps[i].stepType = v;
                            setSteps(newSteps);
                          }}
                          style={{ width: 120 }}
                          options={[
                            { value: 'IMAGE', label: '图片生成' },
                            { value: 'VIDEO', label: '视频生成' },
                            { value: 'AUDIT', label: '安全审核' },
                            { value: 'POST_PROCESS', label: '后处理' },
                          ]}
                        />
                        {steps.length > 2 && (
                          <Button type="text" danger size="small" icon={<DeleteOutlined />} onClick={() => removeStep(i)} />
                        )}
                      </Space>
                    ),
                    description: `步骤 ${i + 1}`,
                  })),
                }}
              />
              </div>

              <Form.Item>
                <Space>
                  <Button type="primary" htmlType="submit" icon={<SendOutlined />} loading={loading}>
                    提交流水线
                  </Button>
                  <Button icon={<ClearOutlined />} onClick={() => { form.resetFields(); setSteps([{ stepType: 'IMAGE' }, { stepType: 'VIDEO' }]); }}>
                    重置
                  </Button>
                </Space>
              </Form.Item>
            </Form>
          </Card>
        </Col>
        <Col span={8}>
          <Card title="流水线说明">
            <ul>
              <li>流水线支持多个步骤串联执行</li>
              <li>每个步骤的输出作为下一个步骤的输入</li>
              <li>支持图片→视频、图片→审核等组合</li>
              <li>中间环节失败会自动重试</li>
            </ul>
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default PipelinePage;
```

---

### Task 10: 任务列表与详情页

**Files:**
- Create: `web-admin/src/pages/tasks/TaskListPage.tsx`
- Create: `web-admin/src/pages/tasks/TaskDetailPage.tsx`

**Produces:** 任务列表（可筛选分页）+ 任务详情（步骤时间线 + 结果展示）

- [ ] **Step 1: 创建 src/pages/tasks/TaskListPage.tsx**

```tsx
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Table, Tag, Select, Button, Space, Typography, Popconfirm, message } from 'antd';
import { ReloadOutlined, EyeOutlined, StopOutlined } from '@ant-design/icons';
import { taskApi } from '@/api/task';
import { AgentTask } from '@/types/api';
import dayjs from 'dayjs';

const { Title } = Typography;

const statusColorMap: Record<string, string> = {
  CREATED: 'default', VALIDATING: 'processing', PENDING: 'warning',
  PROCESSING: 'processing', DONE: 'success', FAILED: 'error',
  CANCELLED: 'default', REVIEWING: 'warning',
};

const TaskListPage: React.FC = () => {
  const [tasks, setTasks] = useState<AgentTask[]>([]);
  const [loading, setLoading] = useState(false);
  const [pagination, setPagination] = useState({ page: 1, size: 20, total: 0 });
  const [statusFilter, setStatusFilter] = useState<string | undefined>(undefined);
  const navigate = useNavigate();

  const loadTasks = async (page = 1, size = 20, status?: string) => {
    setLoading(true);
    try {
      const res = await taskApi.list({ page, size, status });
      const data = res.data.data;
      setTasks(data.records);
      setPagination({ page: data.pagination.page, size: data.pagination.size, total: data.pagination.total });
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { loadTasks(1, 20, statusFilter); }, [statusFilter]);

  const handleCancel = async (id: string) => {
    try {
      await taskApi.cancel(id);
      message.success('任务已取消');
      loadTasks(pagination.page, pagination.size, statusFilter);
    } catch { /* handled */ }
  };

  const columns = [
    { title: '任务ID', dataIndex: 'id', key: 'id', width: 180, render: (v: string) => v.slice(0, 8) + '...' + v.slice(-4) },
    {
      title: '类型', dataIndex: 'workflowType', key: 'type', width: 80,
      render: (v: string) => ({ SINGLE: '单任务', PIPELINE: '流水线', BATCH: '批量' }[v] || v),
    },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 110,
      render: (s: string) => <Tag color={statusColorMap[s] || 'default'}>{s}</Tag>,
    },
    {
      title: 'Prompt', dataIndex: 'requestParams', key: 'prompt', ellipsis: true,
      render: (v: string) => {
        try { return JSON.parse(v).prompt?.slice(0, 80) || '-'; } catch { return '-'; }
      },
    },
    { title: '重试', dataIndex: 'retryCount', key: 'retry', width: 60 },
    {
      title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 170,
      render: (v: string) => dayjs(v).format('MM-DD HH:mm:ss'),
    },
    {
      title: '操作', key: 'actions', width: 150,
      render: (_: unknown, record: AgentTask) => (
        <Space>
          <Button type="link" size="small" icon={<EyeOutlined />} onClick={() => navigate(`/tasks/${record.id}`)}>详情</Button>
          {(record.status === 'PENDING' || record.status === 'PROCESSING') && (
            <Popconfirm title="确定取消该任务？" onConfirm={() => handleCancel(record.id)}>
              <Button type="link" size="small" danger icon={<StopOutlined />}>取消</Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <Title level={4} style={{ margin: 0 }}>任务管理</Title>
        <Space>
          <Select
            allowClear placeholder="筛选状态" style={{ width: 140 }} value={statusFilter}
            onChange={(v) => setStatusFilter(v)}
            options={[
              { value: 'PENDING', label: '等待中' }, { value: 'PROCESSING', label: '处理中' },
              { value: 'DONE', label: '已完成' }, { value: 'FAILED', label: '失败' },
              { value: 'CANCELLED', label: '已取消' },
            ]}
          />
          <Button icon={<ReloadOutlined />} onClick={() => loadTasks(pagination.page, pagination.size, statusFilter)}>
            刷新
          </Button>
        </Space>
      </div>
      <Table
        dataSource={tasks}
        columns={columns}
        rowKey="id"
        loading={loading}
        pagination={{
          current: pagination.page,
          pageSize: pagination.size,
          total: pagination.total,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (page, size) => loadTasks(page, size, statusFilter),
        }}
        onRow={(record) => ({ onClick: () => navigate(`/tasks/${record.id}`), style: { cursor: 'pointer' } })}
      />
    </div>
  );
};

export default TaskListPage;
```

- [ ] **Step 2: 创建 src/pages/tasks/TaskDetailPage.tsx**

```tsx
import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Card, Descriptions, Tag, Steps, Button, Spin, Typography, Image, Alert, Space } from 'antd';
import { ArrowLeftOutlined, ReloadOutlined } from '@ant-design/icons';
import { taskApi } from '@/api/task';
import { TaskDetail } from '@/types/api';
import dayjs from 'dayjs';

const { Title, Paragraph } = Typography;

const statusColorMap: Record<string, string> = {
  PENDING: 'default', PROCESSING: 'processing', DONE: 'success', FAILED: 'error', SKIPPED: 'warning',
};

const TaskDetailPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [detail, setDetail] = useState<TaskDetail | null>(null);
  const [loading, setLoading] = useState(true);

  const load = async () => {
    setLoading(true);
    try {
      const res = await taskApi.getById(id!);
      setDetail(res.data.data);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { if (id) load(); }, [id]);

  if (loading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;
  if (!detail) return <Alert type="error" message="任务不存在" />;

  const { task, steps } = detail;
  let params: Record<string, unknown> = {};
  try { params = JSON.parse(task.requestParams); } catch { /* */ }

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/tasks')}>返回列表</Button>
        <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
      </Space>

      <Card title={<Title level={4} style={{ margin: 0 }}>任务详情</Title>} style={{ marginBottom: 24 }}>
        <Descriptions bordered column={3} size="small">
          <Descriptions.Item label="任务ID">{task.id}</Descriptions.Item>
          <Descriptions.Item label="类型">{task.workflowType}</Descriptions.Item>
          <Descriptions.Item label="状态">
            <Tag color={statusColorMap[task.status] || 'default'}>{task.status}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label="提示词" span={3}>
            <Paragraph ellipsis={{ rows: 2, expandable: true }}>{params.prompt as string || '-'}</Paragraph>
          </Descriptions.Item>
          <Descriptions.Item label="创建时间">{dayjs(task.createdAt).format('YYYY-MM-DD HH:mm:ss')}</Descriptions.Item>
          <Descriptions.Item label="更新时间">{dayjs(task.updatedAt).format('YYYY-MM-DD HH:mm:ss')}</Descriptions.Item>
          <Descriptions.Item label="完成时间">{task.completedAt ? dayjs(task.completedAt).format('YYYY-MM-DD HH:mm:ss') : '-'}</Descriptions.Item>
          <Descriptions.Item label="重试次数">{task.retryCount}</Descriptions.Item>
          <Descriptions.Item label="优先级">{task.priority}</Descriptions.Item>
          <Descriptions.Item label="错误信息">{task.errorMsg || '-'}</Descriptions.Item>
        </Descriptions>
      </Card>

      {steps && steps.length > 0 && (
        <Card title="执行步骤">
          <Steps
            direction="vertical"
            current={steps.findIndex((s) => s.status === 'PROCESSING')}
            items={steps.map((step) => ({
              title: `步骤 ${step.stepOrder}: ${step.stepType}`,
              description: (
                <div>
                  <Tag color={statusColorMap[step.status]}>{step.status}</Tag>
                  {step.startedAt && <span>开始: {dayjs(step.startedAt).format('HH:mm:ss')}</span>}
                  {step.finishedAt && <span> 完成: {dayjs(step.finishedAt).format('HH:mm:ss')}</span>}
                  {step.errorMsg && <div style={{ color: '#ff4d4f' }}>错误: {step.errorMsg}</div>}
                </div>
              ),
              status: step.status === 'FAILED' ? 'error' : step.status === 'DONE' ? 'finish' : step.status === 'PROCESSING' ? 'process' : 'wait',
            }))}
          />
        </Card>
      )}

      {task.result && (
        <Card title="生成结果" style={{ marginTop: 24 }}>
          {(() => {
            try {
              const result = JSON.parse(task.result);
              if (result.url) {
                return <Image src={result.url} alt="生成结果" style={{ maxWidth: '100%', maxHeight: 500 }} />;
              }
              if (result.videoUrl) {
                return <video src={result.videoUrl} controls style={{ maxWidth: '100%', maxHeight: 500 }} />;
              }
              return <pre>{JSON.stringify(result, null, 2)}</pre>;
            } catch {
              return <Paragraph>{task.result}</Paragraph>;
            }
          })()}
        </Card>
      )}
    </div>
  );
};

export default TaskDetailPage;
```

---

### Task 11: 文件管理页

**Files:**
- Create: `web-admin/src/pages/files/FileListPage.tsx`

**Produces:** 文件列表 + 上传 + 下载 + 删除

- [ ] **Step 1: 创建 src/pages/files/FileListPage.tsx**

```tsx
import { useEffect, useState } from 'react';
import { Table, Upload, Button, Space, Typography, Tag, Popconfirm, message, Modal } from 'antd';
import { UploadOutlined, DownloadOutlined, DeleteOutlined, ReloadOutlined } from '@ant-design/icons';
import type { UploadFile } from 'antd';
import { fileApi } from '@/api/file';
import { StorageFile } from '@/types/api';
import dayjs from 'dayjs';

const { Title } = Typography;

const FileListPage: React.FC = () => {
  const [files, setFiles] = useState<StorageFile[]>([]);
  const [loading, setLoading] = useState(false);
  const [pagination, setPagination] = useState({ page: 1, size: 20, total: 0 });
  const [uploadOpen, setUploadOpen] = useState(false);

  const loadFiles = async (page = 1, size = 20) => {
    setLoading(true);
    try {
      const res = await fileApi.list({ page, size });
      const data = res.data.data;
      setFiles(data.records);
      setPagination({ page: data.pagination.page, size: data.pagination.size, total: data.pagination.total });
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { loadFiles(); }, []);

  const handleUpload = async (info: { file: UploadFile }) => {
    const file = info.file as unknown as File;
    if (!file) return;
    try {
      await fileApi.upload(file);
      message.success('上传成功');
      setUploadOpen(false);
      loadFiles();
    } catch { /* handled */ }
  };

  const handleDelete = async (id: string) => {
    try {
      await fileApi.delete(id);
      message.success('删除成功');
      loadFiles();
    } catch { /* handled */ }
  };

  const formatSize = (bytes: number) => {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  };

  const columns = [
    { title: '文件名', dataIndex: 'originalName', key: 'name', ellipsis: true },
    { title: '类型', dataIndex: 'contentType', key: 'type', width: 120, ellipsis: true },
    { title: '大小', dataIndex: 'fileSize', key: 'size', width: 100, render: (v: number) => formatSize(v) },
    {
      title: '存储类型', dataIndex: 'storageClass', key: 'class', width: 90,
      render: (v: string) => <Tag>{v}</Tag>,
    },
    {
      title: '来源', dataIndex: 'sourceType', key: 'source', width: 100,
      render: (v: string) => v || '-',
    },
    {
      title: '上传时间', dataIndex: 'createdAt', key: 'createdAt', width: 170,
      render: (v: string) => dayjs(v).format('MM-DD HH:mm:ss'),
    },
    {
      title: '操作', key: 'actions', width: 150,
      render: (_: unknown, record: StorageFile) => (
        <Space>
          <Button type="link" size="small" icon={<DownloadOutlined />}
            href={fileApi.getDownloadUrl(record.id)} target="_blank">
            下载
          </Button>
          <Popconfirm title="确定删除该文件？" onConfirm={() => handleDelete(record.id)}>
            <Button type="link" size="small" danger icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <Title level={4} style={{ margin: 0 }}>文件管理</Title>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => loadFiles()}>刷新</Button>
          <Button type="primary" icon={<UploadOutlined />} onClick={() => setUploadOpen(true)}>上传文件</Button>
        </Space>
      </div>

      <Table
        dataSource={files}
        columns={columns}
        rowKey="id"
        loading={loading}
        pagination={{
          current: pagination.page,
          pageSize: pagination.size,
          total: pagination.total,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (page, size) => loadFiles(page, size),
        }}
      />

      <Modal title="上传文件" open={uploadOpen} onCancel={() => setUploadOpen(false)} footer={null}>
        <Upload.Dragger
          customRequest={({ file, onSuccess }) => {
            handleUpload({ file: file as UploadFile });
            onSuccess?.('ok');
          }}
          showUploadList={false}
        >
          <p><UploadOutlined style={{ fontSize: 48 }} /></p>
          <p>点击或拖拽文件到此处上传</p>
          <p style={{ color: '#999' }}>支持任意格式文件</p>
        </Upload.Dragger>
      </Modal>
    </div>
  );
};

export default FileListPage;
```

---

### Task 12: LLM 对话页面

**Files:**
- Create: `web-admin/src/pages/llm/ModelListPage.tsx`
- Create: `web-admin/src/pages/llm/ChatPage.tsx`

**Produces:** 模型列表 + AI 对话界面

- [ ] **Step 1: 创建 src/pages/llm/ModelListPage.tsx**

```tsx
import { useEffect, useState } from 'react';
import { Card, Row, Col, Tag, Typography, Spin, Alert } from 'antd';
import { CheckCircleOutlined, CloseCircleOutlined } from '@ant-design/icons';
import { llmApi } from '@/api/llm';
import { LlmModel } from '@/types/api';

const { Title, Text } = Typography;

const ModelListPage: React.FC = () => {
  const [models, setModels] = useState<LlmModel[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    llmApi.listModels()
      .then((res) => setModels(res.data.data))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;

  return (
    <div>
      <Title level={4} style={{ marginBottom: 24 }}>模型广场</Title>
      {models.length === 0 ? (
        <Alert type="info" message="暂无可用的 AI 模型，请联系管理员配置" />
      ) : (
        <Row gutter={[16, 16]}>
          {models.map((model) => (
            <Col key={`${model.provider}-${model.model}`} span={8}>
              <Card
                title={model.model}
                extra={
                  model.available
                    ? <Tag color="success" icon={<CheckCircleOutlined />}>可用</Tag>
                    : <Tag color="error" icon={<CloseCircleOutlined />}>不可用</Tag>
                }
              >
                <Text type="secondary">提供商: {model.provider}</Text>
              </Card>
            </Col>
          ))}
        </Row>
      )}
    </div>
  );
};

export default ModelListPage;
```

- [ ] **Step 2: 创建 src/pages/llm/ChatPage.tsx**

```tsx
import { useState, useRef, useEffect } from 'react';
import { Card, Input, Button, Select, Typography, Space, Spin, Empty } from 'antd';
import { SendOutlined, DeleteOutlined } from '@ant-design/icons';
import ReactMarkdown from 'react-markdown';
import { llmApi } from '@/api/llm';
import { LlmModel } from '@/types/api';

const { Title } = Typography;
const { TextArea } = Input;

interface Message {
  role: 'user' | 'assistant';
  content: string;
  time: string;
}

const ChatPage: React.FC = () => {
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [models, setModels] = useState<LlmModel[]>([]);
  const [selectedProvider, setSelectedProvider] = useState<string>('');
  const chatEndRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    llmApi.listModels().then((res) => {
      const available = res.data.data.filter((m) => m.available);
      setModels(available);
      if (available.length > 0) setSelectedProvider(available[0].provider);
    }).catch(() => {});
  }, []);

  useEffect(() => { chatEndRef.current?.scrollIntoView({ behavior: 'smooth' }); }, [messages]);

  const handleSend = async () => {
    if (!input.trim() || loading) return;
    const userMsg: Message = { role: 'user', content: input.trim(), time: new Date().toLocaleTimeString() };
    setMessages((prev) => [...prev, userMsg]);
    setInput('');
    setLoading(true);

    try {
      const res = await llmApi.chat({ provider: selectedProvider, message: userMsg.content });
      const assistantMsg: Message = {
        role: 'assistant',
        content: res.data.data.content,
        time: new Date().toLocaleTimeString(),
      };
      setMessages((prev) => [...prev, assistantMsg]);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{ display: 'flex', height: 'calc(100vh - 200px)', gap: 16 }}>
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 12 }}>
          <Space>
            <Select
              value={selectedProvider}
              onChange={setSelectedProvider}
              style={{ width: 160 }}
              options={models.map((m) => ({ value: m.provider, label: `${m.provider} / ${m.model}` }))}
            />
          </Space>
          <Button icon={<DeleteOutlined />} onClick={() => setMessages([])} disabled={messages.length === 0}>
            清空对话
          </Button>
        </div>

        <Card
          style={{ flex: 1, overflow: 'auto', marginBottom: 12, background: '#fafafa' }}
          bodyStyle={{ padding: 16 }}
        >
          {messages.length === 0 ? (
            <Empty description="开始一段对话" style={{ marginTop: 80 }} />
          ) : (
            messages.map((msg, i) => (
              <div key={i} style={{ marginBottom: 16, textAlign: msg.role === 'user' ? 'right' : 'left' }}>
                <div style={{
                  display: 'inline-block',
                  maxWidth: '80%',
                  padding: '8px 16px',
                  borderRadius: 12,
                  background: msg.role === 'user' ? '#1677ff' : '#fff',
                  color: msg.role === 'user' ? '#fff' : '#000',
                  boxShadow: '0 1px 4px rgba(0,0,0,0.08)',
                  textAlign: 'left',
                }}>
                  {msg.role === 'assistant' ? (
                    <ReactMarkdown>{msg.content}</ReactMarkdown>
                  ) : (
                    msg.content
                  )}
                </div>
                <div style={{ fontSize: 12, color: '#999', marginTop: 4 }}>{msg.time}</div>
              </div>
            ))
          )}
          {loading && <div style={{ textAlign: 'center' }}><Spin /></div>}
          <div ref={chatEndRef} />
        </Card>

        <div style={{ display: 'flex', gap: 8 }}>
          <TextArea
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onPressEnter={(e) => { if (!e.shiftKey) { e.preventDefault(); handleSend(); } }}
            placeholder="输入消息，Enter 发送，Shift+Enter 换行"
            rows={3}
            disabled={loading}
            style={{ flex: 1 }}
          />
          <Button type="primary" icon={<SendOutlined />} onClick={handleSend} loading={loading} style={{ height: 'auto' }}>
            发送
          </Button>
        </div>
      </div>
    </div>
  );
};

export default ChatPage;
```

注意：需要安装 `react-markdown` — 在 package.json 添加依赖。

---

### Task 13: 个人中心页面

**Files:**
- Create: `web-admin/src/pages/user/ProfilePage.tsx`
- Create: `web-admin/src/pages/user/QuotaPage.tsx`
- Create: `web-admin/src/pages/user/ApiKeyPage.tsx`

**Produces:** 个人信息编辑、配额查看、API Key 管理

- [ ] **Step 1: 创建 src/pages/user/ProfilePage.tsx**

```tsx
import { useEffect, useState } from 'react';
import { Card, Form, Input, Button, Descriptions, Typography, message, Tag, Space, Spin } from 'antd';
import { SaveOutlined } from '@ant-design/icons';
import { useAuthStore } from '@/stores/authStore';
import { userApi } from '@/api/user';
import { UserInfo } from '@/types/api';

const { Title } = Typography;

const ProfilePage: React.FC = () => {
  const { user, setUser } = useAuthStore();
  const [editing, setEditing] = useState(false);
  const [loading, setLoading] = useState(false);
  const [form] = Form.useForm();

  useEffect(() => {
    if (user) {
      form.setFieldsValue({ email: user.email, phone: user.phone });
    }
  }, [user]);

  const handleSave = async (values: { email: string; phone: string }) => {
    setLoading(true);
    try {
      await userApi.updateMe(values);
      if (user) setUser({ ...user, email: values.email, phone: values.phone });
      message.success('更新成功');
      setEditing(false);
    } finally { setLoading(false); }
  };

  if (!user) return <Spin />;

  return (
    <div>
      <Title level={4} style={{ marginBottom: 24 }}>个人信息</Title>
      <Row gutter={24}>
        <Col span={12}>
          <Card title="基本信息">
            <Descriptions column={1} bordered size="small">
              <Descriptions.Item label="用户ID">{user.id}</Descriptions.Item>
              <Descriptions.Item label="用户名">{user.username}</Descriptions.Item>
              <Descriptions.Item label="邮箱">{user.email || '-'}</Descriptions.Item>
              <Descriptions.Item label="手机号">{user.phone || '-'}</Descriptions.Item>
              <Descriptions.Item label="租户ID">{user.tenantId}</Descriptions.Item>
              <Descriptions.Item label="状态">
                <Tag color={user.status === 'ACTIVE' ? 'success' : 'error'}>{user.status}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="角色">
                <Space>{user.roles.map((r) => <Tag key={r}>{r}</Tag>)}</Space>
              </Descriptions.Item>
            </Descriptions>
          </Card>
        </Col>
        <Col span={12}>
          <Card title="编辑信息" extra={
            <Button type={editing ? 'default' : 'primary'} onClick={() => setEditing(!editing)}>
              {editing ? '取消' : '编辑'}
            </Button>
          }>
            <Form form={form} layout="vertical" onFinish={handleSave}>
              <Form.Item name="email" label="邮箱" rules={[{ type: 'email', message: '请输入有效的邮箱' }]}>
                <Input disabled={!editing} />
              </Form.Item>
              <Form.Item name="phone" label="手机号">
                <Input disabled={!editing} />
              </Form.Item>
              {editing && (
                <Form.Item>
                  <Button type="primary" htmlType="submit" icon={<SaveOutlined />} loading={loading}>
                    保存修改
                  </Button>
                </Form.Item>
              )}
            </Form>
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default ProfilePage;
```

- [ ] **Step 2: 创建 src/pages/user/QuotaPage.tsx**

```tsx
import { useEffect, useState } from 'react';
import { Card, Row, Col, Progress, Typography, Spin, Empty } from 'antd';
import { PictureOutlined, VideoCameraOutlined, DatabaseOutlined, ApiOutlined } from '@ant-design/icons';
import { userApi } from '@/api/user';

const { Title, Text } = Typography;

interface QuotaCard {
  type: string; label: string; icon: React.ReactNode;
}

const quotaCards: QuotaCard[] = [
  { type: 'IMAGE', label: '图片生成', icon: <PictureOutlined /> },
  { type: 'VIDEO', label: '视频生成', icon: <VideoCameraOutlined /> },
  { type: 'STORAGE', label: '存储空间', icon: <DatabaseOutlined /> },
  { type: 'API_CALL', label: 'API 调用', icon: <ApiOutlined /> },
];

const QuotaPage: React.FC = () => {
  const [quotas, setQuotas] = useState<Record<string, { limit: number; used: number; remaining: number }>>({});
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    Promise.allSettled(quotaCards.map((q) => userApi.getQuota(q.type)))
      .then((results) => {
        const map: Record<string, { limit: number; used: number; remaining: number }> = {};
        results.forEach((r, i) => {
          if (r.status === 'fulfilled') map[quotaCards[i].type] = r.value.data.data;
        });
        setQuotas(map);
      })
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;

  const hasData = Object.keys(quotas).length > 0;

  return (
    <div>
      <Title level={4} style={{ marginBottom: 24 }}>配额管理</Title>
      {!hasData ? (
        <Empty description="暂无配额数据" />
      ) : (
        <Row gutter={[16, 16]}>
          {quotaCards.map((qc) => {
            const q = quotas[qc.type];
            if (!q) return null;
            const percent = q.limit > 0 ? Math.round((q.used / q.limit) * 100) : 0;
            return (
              <Col key={qc.type} span={6}>
                <Card>
                  <div style={{ textAlign: 'center', marginBottom: 16 }}>
                    <div style={{ fontSize: 32, color: '#1677ff' }}>{qc.icon}</div>
                    <Title level={5} style={{ marginTop: 8 }}>{qc.label}</Title>
                  </div>
                  <Progress percent={percent} status={percent > 80 ? 'exception' : 'active'} />
                  <div style={{ textAlign: 'center', marginTop: 8 }}>
                    <Text type="secondary">{q.used} / {q.limit} (剩余 {q.remaining})</Text>
                  </div>
                </Card>
              </Col>
            );
          })}
        </Row>
      )}
    </div>
  );
};

export default QuotaPage;
```

- [ ] **Step 3: 创建 src/pages/user/ApiKeyPage.tsx**

```tsx
import { useEffect, useState } from 'react';
import { Table, Button, Modal, Form, Input, InputNumber, DatePicker, Tag, Space, Popconfirm, Typography, message, Alert } from 'antd';
import { PlusOutlined, DeleteOutlined, CopyOutlined } from '@ant-design/icons';
import { userApi } from '@/api/user';
import { ApiKey } from '@/types/api';
import dayjs from 'dayjs';

const { Title, Text } = Typography;

const ApiKeyPage: React.FC = () => {
  const [keys, setKeys] = useState<ApiKey[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [newKey, setNewKey] = useState<{ apiKey: string; hint: string } | null>(null);
  const [form] = Form.useForm();

  const loadKeys = async () => {
    setLoading(true);
    try {
      const res = await userApi.listApiKeys();
      setKeys(res.data.data);
    } finally { setLoading(false); }
  };

  useEffect(() => { loadKeys(); }, []);

  const handleCreate = async (values: { name: string; rateLimitQpm: number; expiresAt?: dayjs.Dayjs }) => {
    try {
      const res = await userApi.createApiKey({
        name: values.name,
        rateLimitQpm: values.rateLimitQpm,
        expiresAt: values.expiresAt?.toISOString(),
      });
      setNewKey(res.data.data);
      form.resetFields();
      setModalOpen(false);
      loadKeys();
    } catch { /* handled */ }
  };

  const handleRevoke = async (keyId: string) => {
    try {
      await userApi.revokeApiKey(keyId);
      message.success('API Key 已撤销');
      loadKeys();
    } catch { /* handled */ }
  };

  const columns = [
    { title: '名称', dataIndex: 'name', key: 'name' },
    { title: '前缀', dataIndex: 'keyPrefix', key: 'prefix', render: (v: string) => <code>{v}...</code> },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 80,
      render: (v: string) => <Tag color={v === 'ACTIVE' ? 'success' : 'error'}>{v}</Tag>,
    },
    { title: 'QPM限制', dataIndex: 'rateLimitQpm', key: 'qpm', width: 80 },
    {
      title: '最后使用', dataIndex: 'lastUsedAt', key: 'lastUsed', width: 170,
      render: (v: string) => v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '-',
    },
    {
      title: '过期时间', dataIndex: 'expiresAt', key: 'expires', width: 170,
      render: (v: string) => v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '永久',
    },
    {
      title: '创建时间', dataIndex: 'createdAt', key: 'created', width: 170,
      render: (v: string) => dayjs(v).format('YYYY-MM-DD HH:mm'),
    },
    {
      title: '操作', key: 'actions', width: 80,
      render: (_: unknown, record: ApiKey) => (
        record.status === 'ACTIVE' && (
          <Popconfirm title="撤销后该 Key 将无法使用，确定？" onConfirm={() => handleRevoke(record.id)}>
            <Button type="link" danger size="small" icon={<DeleteOutlined />}>撤销</Button>
          </Popconfirm>
        )
      ),
    },
  ];

  return (
    <div>
      <Title level={4} style={{ marginBottom: 24 }}>API Key 管理</Title>

      {newKey && (
        <Alert
          type="warning"
          showIcon
          closable
          onClose={() => setNewKey(null)}
          message="新 API Key 已生成，请立即复制保存，关闭后将无法再查看！"
          description={
            <Space direction="vertical">
              <code style={{ fontSize: 16, userSelect: 'all' }}>{newKey.apiKey}</code>
              <Button size="small" icon={<CopyOutlined />}
                onClick={() => { navigator.clipboard.writeText(newKey.apiKey); message.success('已复制'); }}>
                复制
              </Button>
              <Text type="secondary">{newKey.hint}</Text>
            </Space>
          }
          style={{ marginBottom: 16 }}
        />
      )}

      <div style={{ marginBottom: 16, textAlign: 'right' }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setModalOpen(true)}>
          创建 API Key
        </Button>
      </div>

      <Table dataSource={keys} columns={columns} rowKey="id" loading={loading} />

      <Modal title="创建 API Key" open={modalOpen} onCancel={() => setModalOpen(false)} footer={null}>
        <Form form={form} layout="vertical" onFinish={handleCreate}>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="例如：生产环境、测试项目" />
          </Form.Item>
          <Form.Item name="rateLimitQpm" label="每分钟限制" initialValue={60}>
            <InputNumber min={1} max={1000} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="expiresAt" label="过期时间（可选，不设则永久有效）">
            <DatePicker showTime style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" block>创建</Button>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default ApiKeyPage;
```

---

### Task 14: 租户管理与管理员页面

**Files:**
- Create: `web-admin/src/pages/tenant/TenantInfoPage.tsx`
- Create: `web-admin/src/pages/tenant/MemberPage.tsx`
- Create: `web-admin/src/pages/admin/StatsPage.tsx`
- Create: `web-admin/src/pages/admin/ReviewPage.tsx`

**Produces:** 租户信息 + 成员管理 + 系统统计 + 任务审核

- [ ] **Step 1: 创建 src/pages/tenant/TenantInfoPage.tsx**

```tsx
import { useEffect, useState } from 'react';
import { Card, Descriptions, Tag, Typography, Spin, Empty } from 'antd';
import { useAuthStore } from '@/stores/authStore';
import { tenantApi } from '@/api/tenant';
import { Tenant } from '@/types/api';

const { Title } = Typography;

const TenantInfoPage: React.FC = () => {
  const { user } = useAuthStore();
  const [tenant, setTenant] = useState<Tenant | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (user?.tenantId) {
      tenantApi.getTenant(user.tenantId)
        .then((res) => setTenant(res.data.data))
        .catch(() => {})
        .finally(() => setLoading(false));
    }
  }, [user]);

  if (loading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;
  if (!tenant) return <Empty description="租户信息不可用" />;

  return (
    <div>
      <Title level={4} style={{ marginBottom: 24 }}>租户信息</Title>
      <Card>
        <Descriptions bordered column={2}>
          <Descriptions.Item label="租户ID">{tenant.id}</Descriptions.Item>
          <Descriptions.Item label="租户名称">{tenant.name}</Descriptions.Item>
          <Descriptions.Item label="状态">
            <Tag color={tenant.status === 'ACTIVE' ? 'success' : 'error'}>{tenant.status}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label="存储提供商">{tenant.storageProvider}</Descriptions.Item>
          <Descriptions.Item label="创建时间">{tenant.createdAt}</Descriptions.Item>
          <Descriptions.Item label="更新时间">{tenant.updatedAt}</Descriptions.Item>
          <Descriptions.Item label="配额配置" span={2}>
            <pre style={{ margin: 0, fontSize: 12 }}>{JSON.stringify(JSON.parse(tenant.quotaConfig || '{}'), null, 2)}</pre>
          </Descriptions.Item>
        </Descriptions>
      </Card>
    </div>
  );
};

export default TenantInfoPage;
```

- [ ] **Step 2: 创建 src/pages/tenant/MemberPage.tsx**

```tsx
import { useEffect, useState } from 'react';
import { Table, Button, Modal, Form, Input, Select, Tag, Space, Typography, Popconfirm, message } from 'antd';
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import { useAuthStore } from '@/stores/authStore';
import { tenantApi } from '@/api/tenant';
import { TenantMember } from '@/types/api';

const { Title } = Typography;

const roleOptions = [
  { value: 'TENANT_ADMIN', label: '租户管理员' },
  { value: 'DEVELOPER', label: '开发者' },
  { value: 'CONTENT_CREATOR', label: '内容创作者' },
  { value: 'VIEWER', label: '观察者' },
];

const MemberPage: React.FC = () => {
  const { user } = useAuthStore();
  const [members, setMembers] = useState<TenantMember[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [form] = Form.useForm();

  const loadMembers = async () => {
    if (!user?.tenantId) return;
    setLoading(true);
    try {
      const res = await tenantApi.getMembers(user.tenantId);
      setMembers(res.data.data);
    } finally { setLoading(false); }
  };

  useEffect(() => { loadMembers(); }, [user]);

  const handleAdd = async (values: { userId: string; roleCode: string }) => {
    try {
      await tenantApi.addMember(user!.tenantId, values);
      message.success('成员添加成功');
      setModalOpen(false);
      form.resetFields();
      loadMembers();
    } catch { /* handled */ }
  };

  const handleRemove = async (userId: string) => {
    try {
      await tenantApi.removeMember(user!.tenantId, userId);
      message.success('成员已移除');
      loadMembers();
    } catch { /* handled */ }
  };

  const columns = [
    { title: '用户ID', dataIndex: 'userId', key: 'userId', width: 200, render: (v: string) => v.slice(0, 12) + '...' },
    { title: '用户名', dataIndex: 'username', key: 'username' },
    { title: '邮箱', dataIndex: 'email', key: 'email' },
    {
      title: '角色', dataIndex: 'roleCode', key: 'role', width: 120,
      render: (v: string) => <Tag color={v === 'TENANT_ADMIN' ? 'blue' : 'default'}>{v}</Tag>,
    },
    {
      title: '操作', key: 'actions', width: 80,
      render: (_: unknown, record: TenantMember) => (
        record.roleCode !== 'TENANT_ADMIN' && (
          <Popconfirm title="确定移除该成员？" onConfirm={() => handleRemove(record.userId)}>
            <Button type="link" danger size="small" icon={<DeleteOutlined />}>移除</Button>
          </Popconfirm>
        )
      ),
    },
  ];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <Title level={4} style={{ margin: 0 }}>成员管理</Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setModalOpen(true)}>添加成员</Button>
      </div>
      <Table dataSource={members} columns={columns} rowKey="userId" loading={loading} />

      <Modal title="添加成员" open={modalOpen} onCancel={() => setModalOpen(false)} footer={null}>
        <Form form={form} layout="vertical" onFinish={handleAdd}>
          <Form.Item name="userId" label="用户ID" rules={[{ required: true, message: '请输入用户ID' }]}>
            <Input placeholder="输入要添加的用户ID" />
          </Form.Item>
          <Form.Item name="roleCode" label="角色" rules={[{ required: true, message: '请选择角色' }]}>
            <Select options={roleOptions} />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" block>添加</Button>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default MemberPage;
```

- [ ] **Step 3: 创建 src/pages/admin/StatsPage.tsx**

```tsx
import { useEffect, useState } from 'react';
import { Card, Row, Col, Statistic, Typography, Spin, Table } from 'antd';
import { adminApi } from '@/api/admin';
import { TaskStats } from '@/types/api';

const { Title } = Typography;

const StatsPage: React.FC = () => {
  const [stats, setStats] = useState<TaskStats | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    adminApi.getStats()
      .then((res) => setStats(res.data.data))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />;

  const chartData = stats ? [
    { key: 'total', label: '总任务', value: stats.total, color: '#1677ff' },
    { key: 'pending', label: '待处理', value: stats.pending, color: '#faad14' },
    { key: 'processing', label: '处理中', value: stats.processing, color: '#1677ff' },
    { key: 'done', label: '已完成', value: stats.done, color: '#52c41a' },
    { key: 'failed', label: '失败', value: stats.failed, color: '#ff4d4f' },
    { key: 'cancelled', label: '已取消', value: stats.cancelled, color: '#999' },
  ] : [];

  return (
    <div>
      <Title level={4} style={{ marginBottom: 24 }}>系统统计</Title>
      <Row gutter={[16, 16]}>
        {chartData.map((item) => (
          <Col key={item.key} span={8}>
            <Card>
              <Statistic title={item.label} value={item.value} valueStyle={{ color: item.color }} />
            </Card>
          </Col>
        ))}
      </Row>
    </div>
  );
};

export default StatsPage;
```

- [ ] **Step 4: 创建 src/pages/admin/ReviewPage.tsx**

```tsx
import { useEffect, useState } from 'react';
import { Table, Tag, Button, Space, Popconfirm, Typography, message } from 'antd';
import { CheckOutlined, CloseOutlined, ReloadOutlined } from '@ant-design/icons';
import { adminApi } from '@/api/admin';
import { AgentTask } from '@/types/api';
import dayjs from 'dayjs';

const { Title } = Typography;

const ReviewPage: React.FC = () => {
  const [tasks, setTasks] = useState<AgentTask[]>([]);
  const [loading, setLoading] = useState(false);
  const [pagination, setPagination] = useState({ page: 1, size: 20, total: 0 });

  const loadTasks = async (page = 1, size = 20) => {
    setLoading(true);
    try {
      const res = await adminApi.listAllTasks({ page, size, status: 'REVIEWING' });
      const data = res.data.data;
      setTasks(data.records);
      setPagination({ page: data.pagination.page, size: data.pagination.size, total: data.pagination.total });
    } finally { setLoading(false); }
  };

  useEffect(() => { loadTasks(); }, []);

  const handleReview = async (id: string, action: 'PASS' | 'REJECT') => {
    try {
      await adminApi.reviewTask(id, action);
      message.success(action === 'PASS' ? '已通过' : '已拒绝');
      loadTasks();
    } catch { /* handled */ }
  };

  const columns = [
    { title: '任务ID', dataIndex: 'id', key: 'id', width: 180, render: (v: string) => v.slice(0, 8) + '...' + v.slice(-4) },
    { title: '用户ID', dataIndex: 'userId', key: 'userId', width: 180, render: (v: string) => v.slice(0, 8) + '...' },
    { title: '类型', dataIndex: 'workflowType', key: 'type', width: 80 },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 100,
      render: (s: string) => <Tag color="warning">{s}</Tag>,
    },
    { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 170, render: (v: string) => dayjs(v).format('MM-DD HH:mm:ss') },
    {
      title: '操作', key: 'actions', width: 160,
      render: (_: unknown, record: AgentTask) => (
        <Space>
          <Popconfirm title="确定通过该任务？" onConfirm={() => handleReview(record.id, 'PASS')}>
            <Button type="link" size="small" icon={<CheckOutlined />} style={{ color: '#52c41a' }}>通过</Button>
          </Popconfirm>
          <Popconfirm title="确定拒绝该任务？" onConfirm={() => handleReview(record.id, 'REJECT')}>
            <Button type="link" size="small" danger icon={<CloseOutlined />}>拒绝</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <Title level={4} style={{ margin: 0 }}>任务审核</Title>
        <Button icon={<ReloadOutlined />} onClick={() => loadTasks()}>刷新</Button>
      </div>
      <Table
        dataSource={tasks}
        columns={columns}
        rowKey="id"
        loading={loading}
        pagination={{
          current: pagination.page,
          pageSize: pagination.size,
          total: pagination.total,
          showTotal: (t) => `共 ${t} 条待审核`,
          onChange: (page, size) => loadTasks(page, size),
        }}
      />
    </div>
  );
};

export default ReviewPage;
```

---

### Task 15: 最终检查与验证

**Files:**
- Modify: `web-admin/package.json`（确认 react-markdown 依赖已添加）

**Produces:** 完整可运行的前端应用

- [ ] **Step 1: 更新 package.json 添加 react-markdown**

在 dependencies 中添加：`"react-markdown": "^9.0.1"`

- [ ] **Step 2: 安装依赖**

```bash
cd web-admin && npm install
```

- [ ] **Step 3: 验证 TS 编译**

```bash
cd web-admin && npx tsc --noEmit
```

- [ ] **Step 4: 验证 dev 启动**

```bash
cd web-admin && npm run dev
```

访问 `http://localhost:3000`，确认：
- 登录页可访问
- 注册流程可走通
- 登录后跳转到 Dashboard
- 侧边菜单完整，角色菜单正确显示/隐藏
- 各页面可正常渲染

# AI Agent 创作平台 — 前端管理后台设计文档

> 日期: 2026-07-06 | 状态: 已确认

## 1. 概述

为 AI Agent 企业级创作平台构建前后端分离的前端管理后台。覆盖用户认证、AI 图片/视频生成、任务编排、文件管理、LLM 对话、租户管理和系统管理全部功能。

### 1.1 约束

- 前后端分离，通过 Gateway (8080) 统一 API 入口通信
- JWT 双 Token 鉴权（Access Token 2h + Refresh Token 7d）
- RBAC 5 级角色：SUPER_ADMIN > TENANT_ADMIN > DEVELOPER > CONTENT_CREATOR > VIEWER
- 开发环境 Vite proxy 解决跨域，生产环境 Nginx 反向代理

## 2. 技术栈

| 层次 | 选型 | 版本 |
|------|------|------|
| 框架 | React | 18 |
| 语言 | TypeScript | 5.x |
| 构建 | Vite | 5 |
| UI 库 | Ant Design | 5 |
| 状态管理 | Zustand | 4 |
| 路由 | React Router | 6 |
| HTTP | Axios | 1.x |
| 图表 | @ant-design/charts | 2.x |
| 样式 | CSS Modules + Ant Design Token | — |

## 3. 项目结构

```
web-admin/
├── index.html
├── vite.config.ts
├── tsconfig.json
├── package.json
├── src/
│   ├── main.tsx                   # 入口
│   ├── App.tsx                    # 根组件（路由 + 布局）
│   ├── api/                       # API 请求层
│   │   ├── client.ts              # Axios 实例 + 拦截器
│   │   ├── auth.ts                # 登录/注册/刷新/登出
│   │   ├── user.ts                # 用户信息/配额/API Key
│   │   ├── task.ts                # 任务提交/列表/详情/取消
│   │   ├── file.ts                # 文件上传/列表/下载/删除
│   │   ├── tenant.ts              # 租户信息/成员管理
│   │   ├── llm.ts                 # 模型列表/对话
│   │   └── admin.ts               # 统计/全量任务/审核
│   ├── stores/                    # Zustand 状态
│   │   ├── authStore.ts           # Token + 用户信息
│   │   └── appStore.ts            # UI 状态
│   ├── hooks/                     # 自定义 Hooks
│   │   ├── useAuth.ts             # 鉴权 Hook
│   │   └── usePagination.ts       # 分页 Hook
│   ├── components/                # 公共组件
│   │   ├── Layout/
│   │   │   ├── MainLayout.tsx     # 主布局（Sider + Header + Content）
│   │   │   └── SideMenu.tsx       # 侧边导航
│   │   ├── AuthGuard/
│   │   │   └── index.tsx          # 路由鉴权守卫
│   │   └── common/
│   │       └── PageHeader.tsx     # 页面标题栏
│   ├── pages/                     # 页面（按功能分组）
│   │   ├── auth/
│   │   │   ├── LoginPage.tsx      # 登录
│   │   │   └── RegisterPage.tsx   # 注册
│   │   ├── dashboard/
│   │   │   └── DashboardPage.tsx  # 首页仪表盘
│   │   ├── workspace/
│   │   │   ├── ImageGenPage.tsx   # 图片生成
│   │   │   ├── VideoGenPage.tsx   # 视频生成
│   │   │   ├── BatchGenPage.tsx   # 批量生成
│   │   │   └── PipelinePage.tsx   # 流水线编排
│   │   ├── tasks/
│   │   │   ├── TaskListPage.tsx   # 任务列表
│   │   │   └── TaskDetailPage.tsx # 任务详情
│   │   ├── files/
│   │   │   └── FileListPage.tsx   # 文件管理
│   │   ├── llm/
│   │   │   ├── ModelListPage.tsx  # 模型广场
│   │   │   └── ChatPage.tsx       # AI 对话
│   │   ├── user/
│   │   │   ├── ProfilePage.tsx    # 个人信息
│   │   │   ├── QuotaPage.tsx      # 配额管理
│   │   │   └── ApiKeyPage.tsx     # API Key
│   │   ├── tenant/
│   │   │   ├── TenantInfoPage.tsx  # 租户信息
│   │   │   └── MemberPage.tsx     # 成员管理
│   │   └── admin/
│   │       ├── StatsPage.tsx      # 系统统计
│   │       └── ReviewPage.tsx     # 任务审核
│   ├── router/
│   │   └── index.tsx              # 路由配置表
│   ├── utils/
│   │   ├── token.ts               # Token 读写
│   │   └── format.ts              # 日期/文件大小格式化
│   └── types/
│       └── api.ts                 # 后端 API 类型映射
```

## 4. 路由与菜单

| 菜单 | 图标 | 路由 | 页面 | 角色要求 |
|------|------|------|------|----------|
| 首页仪表盘 | Dashboard | `/dashboard` | DashboardPage | 登录 |
| — | — | — | — | — |
| **AI 创作** | | | | |
| 图片生成 | Picture | `/workspace/image` | ImageGenPage | 登录 |
| 视频生成 | VideoCamera | `/workspace/video` | VideoGenPage | 登录 |
| 批量生成 | Appstore | `/workspace/batch` | BatchGenPage | 登录 |
| 流水线 | NodeIndex | `/workspace/pipeline` | PipelinePage | 登录 |
| — | — | — | — | — |
| **任务管理** | UnorderedList | `/tasks` | TaskListPage | 登录 |
| 任务详情 | — | `/tasks/:id` | TaskDetailPage | 登录 |
| — | — | — | — | — |
| **文件管理** | FolderOpen | `/files` | FileListPage | 登录 |
| — | — | — | — | — |
| **LLM 对话** | | | | |
| 模型广场 | Cluster | `/llm/models` | ModelListPage | 登录 |
| AI 对话 | Message | `/llm/chat` | ChatPage | 登录 |
| — | — | — | — | — |
| **个人中心** | User | `/user/profile` | ProfilePage | 登录 |
| 配额管理 | Dashboard | `/user/quota` | QuotaPage | 登录 |
| API Key | Key | `/user/api-keys` | ApiKeyPage | 登录 |
| — | — | — | — | — |
| **租户管理** | Team | | | |
| 租户信息 | InfoCircle | `/tenant/info` | TenantInfoPage | 登录 |
| 成员管理 | UsergroupAdd | `/tenant/members` | MemberPage | TENANT_ADMIN+ |
| — | — | — | — | — |
| **管理后台** | Setting | | | |
| 系统统计 | BarChart | `/admin/stats` | StatsPage | TENANT_ADMIN+ |
| 任务审核 | Audit | `/admin/review` | ReviewPage | TENANT_ADMIN+ |

## 5. 数据流

### 5.1 HTTP 请求流

```
用户操作 → Axios 实例
         → 请求拦截器：自动附加 Authorization: Bearer <accessToken>
         → 发送请求到 Gateway (localhost:8080)
         → 响应拦截器：
            ├── code === 0 → 返回 data
            ├── code === 2001/2002（Token 过期/无效）
            │   → 自动调 refresh API
            │   → 成功：更新 Token，重试原请求
            │   → 失败：清除 Token，跳转登录页
            └── 其他错误码 → notification.error(message)
```

### 5.2 状态管理

```
authStore (Zustand + persist):
  - accessToken, refreshToken, expiresIn
  - userInfo: { id, username, email, roles, tenantId }
  - isAuthenticated: boolean
  - login(), logout(), refreshToken(), fetchUserInfo()

appStore (Zustand):
  - sidebarCollapsed: boolean
  - toggleSidebar()
```

### 5.3 路由鉴权

```
路由加载 → AuthGuard 检查
          ├── 白名单路由 (/login, /register) → 直接渲染
          ├── 未登录（无 Token） → 重定向 /login
          ├── Token 存在但用户信息缺失 → fetchUserInfo() → 渲染
          ├── 需要角色但用户角色不匹配 → 重定向 /dashboard，提示无权限
          └── 全部通过 → 渲染页面
```

## 6. API 类型映射

### 6.1 通用响应

```typescript
interface ApiResult<T> {
  code: number;       // 0=成功
  message: string;
  data: T;
  requestId: string;
  timestamp: number;
}

interface PageResult<T> {
  records: T[];
  pagination: { page: number; size: number; total: number; totalPages: number };
}
```

### 6.2 认证相关

```typescript
interface LoginRequest { username: string; password: string; }
interface RegisterRequest { username: string; password: string; email: string; phone?: string; }
interface RefreshTokenRequest { refreshToken: string; }
interface TokenPair { accessToken: string; refreshToken: string; expiresIn: number; }
```

### 6.3 任务相关

```typescript
interface TaskSubmitRequest {
  prompt: string;
  workflowType: 'SINGLE' | 'PIPELINE' | 'BATCH';
  engineType: 'IMAGE' | 'VIDEO';
  size?: string;          // 图片: "1024x1024"
  count?: number;
  style?: string;         // "realistic", "anime"
  format?: string;        // "png", "jpeg"
  quality?: string;       // "standard", "hd"
  negativePrompt?: string;
  aspectRatio?: string;   // "16:9"
  duration?: number;      // 视频时长(秒)
  fps?: number;
  resolution?: string;    // "1920x1080"
  batchCount?: number;
  pipelineSteps?: Array<{ stepType: string; [key: string]: unknown }>;
}

type TaskStatus = 'CREATED' | 'VALIDATING' | 'PENDING' | 'PROCESSING' | 'DONE' | 'FAILED' | 'CANCELLED' | 'REVIEWING';
```

### 6.4 错误码

```typescript
enum ErrorCode {
  PARAM_INVALID = 1001,
  TOKEN_EXPIRED = 2001,
  TOKEN_INVALID = 2002,
  NO_PERMISSION = 2003,
  API_KEY_INVALID = 2004,
  QUOTA_EXCEEDED = 3001,
  RATE_LIMITED = 3003,
  TASK_NOT_FOUND = 4001,
  FILE_NOT_FOUND = 4002,
  USER_NOT_FOUND = 4003,
  CONTENT_BLOCKED = 9001,
  SENSITIVE_PROMPT = 9002,
  INTERNAL_ERROR = 5099,
}
```

## 7. 关键页面设计

### 7.1 AI 创作工作台（核心页面）

```
┌──────────────────────────────────────────┐
│  提示词输入区（TextArea, 支持换行）        │
│  [负面提示词]（可选, Collapse 收起）       │
├──────────────────────────────────────────┤
│  左侧: 参数面板                 右侧: 预览  │
│  引擎类型: [IMAGE] [VIDEO]      (结果展示)  │
│  尺寸: [下拉选择]                         │
│  风格: [标签选择]                         │
│  数量: [1-10]                            │
│  画质: [standard] [hd]                   │
│  宽高比: [1:1] [16:9] [9:16]            │
├──────────────────────────────────────────┤
│  [提交生成] [重置参数]                     │
└──────────────────────────────────────────┘
```

### 7.2 任务列表

```
表格列: ID | 类型 | 状态(标签色) | Prompt(省略) | 进度 | 创建时间 | 操作
筛选器: 状态下拉 + 搜索框
操作: 查看详情 | 取消(仅 pending/processing)
分页: 底部 Ant Table 分页
```

### 7.3 AI 对话

```
┌──────────────┬──────────────────────────┐
│  对话列表      │  聊天区域                  │
│  + 新对话     │  ┌──────────────────────┐ │
│  对话 1       │  │ 模型回复 (Markdown)   │ │
│  对话 2       │  └──────────────────────┘ │
│              │  ┌──────────────────────┐ │
│              │  │        用户输入       │ │
│              │  └──────────────────────┘ │
│              ├──────────────────────────┤
│              │  [输入框]          [发送]  │
└──────────────┴──────────────────────────┘
```

## 8. 错误处理策略

| 场景 | 处理方式 |
|------|----------|
| Token 过期 | 自动刷新，失败跳登录 |
| 网络错误 | 全局 notification + 重试按钮 |
| 后端业务错误 | message 内容直接展示 |
| 表单校验错误 | 字段级红色提示（Ant Form validate） |
| 文件上传失败 | 进度条变红 + 错误信息 |
| 角色不足 | 菜单项隐藏 + 路由重定向 + 提示 |
| 配额不足 | 弹窗提示当前用量 + 建议升级 |

## 9. 开发路径

1. **初始化项目** — Vite + React + TS + 依赖安装
2. **基础设施层** — Axios 拦截器、Token 管理、Zustand Store、路由守卫
3. **主布局** — Ant Layout + 侧边菜单 + 顶栏
4. **认证模块** — 登录页、注册页
5. **个人中心** — 个人信息、配额、API Key
6. **AI 创作** — 图片/视频/批量/流水线生成页
7. **任务管理** — 任务列表 + 详情页
8. **文件管理** — 文件列表 + 上传
9. **LLM 对话** — 模型列表 + 对话界面
10. **租户管理** — 租户信息 + 成员管理
11. **管理后台** — 统计仪表盘 + 任务审核

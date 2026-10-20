# AI Agent 创作平台

企业级 AI Agent 创作平台，核心能力包含 AI 图片生成、AI 视频生成与 AI 对话。支持内部管理后台与对外 OpenAPI 服务，具备完整的多租户隔离、RBAC 权限体系和数据安全流水线。

> 本文档基于源码实际核对（2026-10），与早期版本有若干出入，已在下文标注。
> 历史设计文档见 `docs/superpowers/specs/`（架构设计）与 `docs/superpowers/plans/`（实现计划）。

## 技术栈

| 层次 | 选型 |
|------|------|
| 语言 | Java 17 |
| 框架 | Spring Boot 3.2.5 + Spring Cloud 2023.0.1 + Spring Cloud Alibaba 2023.0.1.0 |
| 注册/配置 | Nacos 2.3（可选，lite 档用静态服务发现替代） |
| 网关 | Spring Cloud Gateway |
| 消息队列 | RabbitMQ 3.12 |
| 数据库 | MySQL 8.0 |
| 缓存 | Redis 7 |
| 对象存储 | MinIO（`StorageAdapter` 抽象，可切换云 OSS） |
| ORM | MyBatis-Plus 3.5.6 |
| 构建工具 | Maven（**14 模块**） |
| 前端 | React 18.3 + Vite 5 + TypeScript 5 + Ant Design 5 + zustand + axios |
| 本地推理 | Python FastAPI + diffusers（SDXL-Turbo / CogVideoX-2b） |

## 项目结构

```
woshijavagaoshou/
├── common-lib/           # 公共模块（7 个）
│   ├── common-core/      # Result / PageResult / BaseEntity / ErrorCode / BizException / JsonUtils
│   │                     # + AesUtils(AES-GCM) / ModelConfigService(API Key 加密存储)
│   ├── common-web/       # 上下文 ThreadLocal / 拦截器 / FeignConfig / @AuditLog / @RequireRole
│   ├── common-security/  # JwtUtils(双 Token + jti) / JwtProperties / BCryptUtils
│   ├── common-mq/        # 4 交换机 / 6 队列 / 绑定 + 3 个消息 record
│   ├── common-storage/   # StorageAdapter 接口 + DTO（纯接口无实现）
│   ├── common-feign/     # 3 个 Feign 客户端 + 降级实现 + 4 个 DTO
│   └── common-engine/    # ★ 引擎抽象基类（README 早期版本遗漏）
│                         #   AbstractTaskConsumer / AbstractModelRegistry / AbstractGenerationFacade
├── gateway/              # API 网关 (8080) —— 5 个 GlobalFilter
├── agent-core/           # 任务编排 + LLM 对话 + RAG + 项目 + 资产 (8082)
│   ├── adapter/          #   LLM 适配器 ×10
│   ├── chat/             #   多轮对话 + 长期记忆
│   ├── rag/              #   RAG 知识库（embedding / 向量检索 / 文档解析）
│   ├── project/          #   项目化创作（成员 / 简报 / 知识库挂载）
│   ├── asset/            #   资产中心（派生链 / 标签 / 任务关联）
│   ├── modelcap/         #   模型能力目录
│   ├── ws/               #   任务进度 WebSocket
│   └── job/              #   卡死任务恢复扫描
├── user-service/         # 用户 / 租户 / RBAC / 配额 / API Key (8081)
├── security-service/     # 敏感词审核 / 水印 / 审计落库 (8083)
├── image-engine/         # 图片生成引擎 (8085) —— 适配器 ×7 + 表情包子系统
├── video-engine/         # 视频生成引擎 (8086) —— 适配器 ×9
├── storage-service/      # 文件存储 (8084)
├── web-admin/            # 管理后台前端 (3000) —— 30 个页面文件 / 29 个已挂路由
├── inference/            # 本地 AI 推理服务 (7860, FastAPI + diffusers)
├── deploy/               # 生产部署方案（lite / medium / full 三档）
├── docs/                 # 设计文档与实现计划
├── sql/                  # 数据库脚本（6 个文件 / 25 张表）
├── docker-compose.yml    # 基础设施编排（开发环境）
└── pom.xml               # 父 POM
```

## 服务端口

| 服务 | 端口 | 说明 |
|------|------|------|
| Gateway | 8080 | 统一入口（唯一对外暴露的 Java 服务） |
| User-Service | 8081 | 用户 / 租户 / 角色 / 配额 / API Key |
| Agent-Core | 8082 | 任务编排 + LLM 对话 + RAG + 项目 + 资产 |
| Security-Service | 8083 | 审核 / 水印 / 审计 |
| Storage-Service | 8084 | 文件存储 |
| Image-Engine | 8085 | 图片生成（**无 REST 接口**，纯 MQ 消费；仅 meme 子系统暴露 REST） |
| Video-Engine | 8086 | 视频生成（**无 REST 接口**，纯 MQ 消费） |
| WebAdmin | 3000 | 管理后台前端（React + Vite） |
| Inference | 7860 | 本地 AI 推理服务（SDXL / CogVideoX，FastAPI） |

## 快速开始

### 0. 一键启动（Windows 推荐）

双击根目录 `start.bat`，自动完成：清理旧进程 → 启动 Docker 基础设施 → SQL 迁移（幂等）→ Maven 编译 → 后台静默启动全部 8 个服务（Gateway / User / Storage / AgentCore / Security / Image / Video / WebAdmin）。

所有服务日志统一写入 `logs\` 目录，不再弹出多个控制台窗口，只保留一个控制台面板：

| 菜单 | 功能 |
|------|------|
| `[1]` | 查看某个服务的日志（实时刷新，`V` 打开完整日志） |
| `[2]` | 一键搜索所有日志中的 ERROR / Exception |
| `[3]` | 刷新服务状态（运行中 / 启动中 / 未启动） |
| `[4]` | 停止所有服务并退出 |
| `[5]` | 退出（服务继续在后台运行） |

常用参数：`start.bat -SkipInfra`（跳过 Docker）、`start.bat -SkipBuild`（跳过编译，快速重启服务）。

启动后可用 PowerShell 检查本地端口是否全部就绪：

```powershell
powershell -ExecutionPolicy Bypass -File local-smoke.ps1
```

如果还启动了本地推理服务：

```powershell
powershell -ExecutionPolicy Bypass -File local-smoke.ps1 -IncludeInference
```

检查失败时，优先查看 `logs\` 下对应服务的日志。

AI 推理服务需单独启动：`inference\start.bat`（端口 7860）。

### 1. 启动基础设施

基础设施凭据全部从根目录 `.env` 读取（docker compose 自动加载，`.env` 已 gitignore），不再有内置默认密码：

```bash
# 首次运行二选一：
#   方式 A：直接用 start.bat（自动生成随机凭据写入 .env）
#   方式 B：手动复制示例并填写真实值
copy .env.example .env     # Windows
# cp .env.example .env     # Linux
docker compose up -d
```

等待所有服务健康：

```bash
docker compose ps
```

> ⚠️ 已有 `mysql_data` 卷升级时：若卷内 MySQL 密码与新 `.env` 不一致，服务将连不上库。请把旧密码写入 `.env`（保持卷不变），或先 `docker exec mysql mysqldump -uroot -p<旧密码> --all-databases > backup.sql` 备份后 `docker compose down -v` 重建（会清空全部数据卷）。

### 2. 初始化数据库

启动 MySQL 后，SQL 脚本会自动执行（挂载在 `./sql:/docker-entrypoint-initdb.d`）。

如有需要手动执行：

```bash
# <MYSQL_ROOT_PASSWORD> 替换为 .env 中的值
docker exec -i mysql mysql -uroot -p<MYSQL_ROOT_PASSWORD> < sql/init.sql
docker exec -i mysql mysql -uroot -p<MYSQL_ROOT_PASSWORD> < sql/rag.sql
docker exec -i mysql mysql -uroot -p<MYSQL_ROOT_PASSWORD> < sql/model_config.sql
docker exec -i mysql mysql -uroot -p<MYSQL_ROOT_PASSWORD> < sql/chat.sql
docker exec -i mysql mysql -uroot -p<MYSQL_ROOT_PASSWORD> < sql/prompt_template.sql
docker exec -i mysql mysql -uroot -p<MYSQL_ROOT_PASSWORD> < sql/phase1_project_asset.sql
docker exec -i mysql mysql -uroot -p<MYSQL_ROOT_PASSWORD> < sql/memory_space.sql
```

> 7 个 SQL 脚本全部幂等（`CREATE TABLE IF NOT EXISTS` + `INSERT IGNORE` + 幂等列检查），可重复执行。
> `rag.sql` / `model_config.sql` / `chat.sql` / `prompt_template.sql` / `phase1_project_asset.sql` / `memory_space.sql`
> 仅在全新数据库首次初始化时自动执行；已有数据库升级需手动执行上面 7 行。

### 3. 编译项目

```bash
mvn clean package -DskipTests
```

### 4. 启动微服务（推荐按以下顺序）

```bash
# 1. 基础设施服务
mvn spring-boot:run -pl gateway &
mvn spring-boot:run -pl user-service &
mvn spring-boot:run -pl storage-service &

# 2. 业务核心
mvn spring-boot:run -pl agent-core &
mvn spring-boot:run -pl security-service &

# 3. 计算引擎
mvn spring-boot:run -pl image-engine &
mvn spring-boot:run -pl video-engine &
```

## 数据库实例（重要：机器上有两台 MySQL，勿混淆）

| 实例 | 位置 | 端口 | 用途 |
|------|------|------|------|
| **项目 MySQL** | Docker 容器 `mysql`（数据卷 `mysql_data`） | **3307**（容器内 3306） | 本平台全部业务库：`agent_platform` / `user_service` / `security_service` / `storage_service` |
| 本机 MySQL | Windows 本机 `mysqld.exe`（独立安装） | 3306 | **与本项目无关**（`vehicle_mes` 等其他项目数据）；IDE 的 MCP MySQL 工具连接的是这台 |

- 所有微服务经 `jdbc:mysql://localhost:3307/...` 连接**项目实例**；SQL 脚本也一律执行在 **3307 项目实例**
- 访问项目库：`docker exec -it mysql mysql -uroot -p<MYSQL_ROOT_PASSWORD>`（密码见 .env），或 Navicat 等工具连 `127.0.0.1:3307`
- ⚠️ **不要在本机实例上执行本项目任何 SQL**；使用 MCP 工具前必须确认连接实例（`SHOW VARIABLES LIKE 'port'` / `SELECT DATABASE()`），或直接用 `docker exec`
- 备份：`docker exec mysql mysqldump -uroot -p<MYSQL_ROOT_PASSWORD> --all-databases > backup.sql`
- 风险：Docker Desktop 执行「Reset / Clean data」会清空 `mysql_data` 卷，重要数据请定期 dump

## 数据表总览（25 张，分布 4 个库）

| 库 | 表 | 用途 |
|----|----|------|
| `agent_platform` | `agent_task` / `agent_workflow_step` | 任务主表 + 工作流步骤 |
| | `project` / `project_member` / `creative_brief` / `project_dataset` | 项目化创作（阶段 1） |
| | `asset` / `asset_tag` / `asset_task_relation` | 资产中心（派生链 + 标签 + 任务关联） |
| | `model_capability` / `model_config` | 模型能力目录 / 模型配置（API Key 加密） |
| | `rag_dataset` / `rag_document` / `rag_chunk` | RAG 知识库（向量存 JSON 列） |
| | `chat_conversation` / `chat_message` / `user_memory` / `memory_space` | 多轮对话 + 记忆空间 + 长期记忆 |
| | `prompt_template` | 提示词模板 |
| `user_service` | `user_account` / `tenant` / `user_tenant` / `api_key` / `quota_usage` | 用户 / 租户 / RBAC / API Key / 配额 |
| `security_service` | `audit_log` | 审计日志 |
| `storage_service` | `storage_file` | 文件元数据 |

## API 路由

### 网关路由表

| 路径前缀 | 目标服务 | 鉴权 |
|---------|---------|------|
| `/api/v1/auth/**` | user-service | 白名单（login / register / refresh 跳过） |
| `/api/v1/users/**` | user-service | JWT |
| `/api/v1/tenants/**` | user-service | JWT + 角色 |
| `/api/v1/tasks/ws/**` | agent-core | JWT（**token 走 query 参数**，WebSocket 无法自定义头） |
| `/api/v1/tasks/**` | agent-core | JWT（任务归属校验） |
| `/api/v1/projects/**` | agent-core | JWT（项目角色校验） |
| `/api/v1/assets/**` | agent-core | JWT（资产归属校验） |
| `/api/v1/model-capabilities/**` | agent-core | JWT |
| `/api/v1/templates/**` | agent-core | JWT |
| `/api/v1/admin/**` | agent-core | JWT + 租户管理角色（**模型配置写仅 SUPER_ADMIN**） |
| `/api/v1/open/**` | agent-core | **API Key**（`X-Api-Key` 头） |
| `/api/v1/llm/chat/stream` | agent-core | JWT（**必须排在 llm 之前**，SSE 需禁用 response-timeout） |
| `/api/v1/llm/**` | agent-core | JWT（对话 + 会话 / 记忆 / 记忆空间管理） |
| `/api/v1/rag/**` | agent-core | JWT |
| `/api/v1/audit/**` | security-service | JWT + 租户管理角色 |
| `/api/v1/files/**` | storage-service | JWT（文件归属校验） |
| `/api/v1/public/files/**` | storage-service | **免鉴权**（HMAC 签名 + 10 分钟过期） |
| `/api/v1/meme/**` | image-engine | JWT（表情包生成） |

> 注意：**没有** `/api/v1/image/**` 或 `/api/v1/video/**` 路由 —— 两个引擎纯 MQ 驱动，只能通过 `/tasks/**` 异步提交任务。

### 网关 Filter 链（按 order 执行）

| order | Filter | 生效路径 | 职责 |
|-------|--------|---------|------|
| **-1** | `ApiKeyAuthFilter` | 仅 `/api/v1/open/**` | `X-Api-Key` → 调 user-service 内部接口校验 → 注入身份头 |
| **0** | `JwtAuthFilter` | 除白名单外全部 | 校验签名 + `type=access` + jti 黑名单 → 注入 `X-User-Id` / `X-Tenant-Id` / `X-User-Roles` |
| **1** | `RateLimitFilter` | `/api/**` | Redis 固定窗口 + Lua 原子脚本，默认 100 次 / 60 秒 |
| **2** | `TenantContextFilter` | `/api/**` | 确保 `X-Tenant-Id` 头存在（纯补位，不校验） |
| **3** | `AuditLogGatewayFilter` | `/api/**` | 记录耗时 / 状态码 / IP → 异步发 MQ 落库 |

### 统一响应体

```json
{
  "code": 0,
  "message": "success",
  "data": {},
  "requestId": "uuid",
  "timestamp": 1719999999000
}
```

### 错误码体系

| 区间 | 含义 |
|------|------|
| 0 | 成功 |
| 1xxx | 参数错误 |
| 2xxx | 认证授权（2001 Token 过期 / 2002 Token 无效 / 2003 无权限 / 2004-2005 API Key） |
| 3xxx | 业务限制（3001 配额超限 / 3003 限流 / 3005 最后一名 OWNER 保护） |
| 4xxx | 资源不存在 |
| 5xxx | 系统错误（5004 审核不可用 / 5005 配额不可用） |
| 9xxx | 安全拦截（9001 内容审核未通过 / 9002 Prompt 含敏感词） |

## 核心特性

- **三种工作流模式**：Single（单步）、Pipeline（串行流水线，支持 `IMAGE` / `VIDEO` / `RAG_RETRIEVE` 步骤）、Batch（并行批量，同 `batch_group_id` 归组）
- **任务状态机**：`CREATED → VALIDATING → PENDING → PROCESSING → DONE`，失败走 `FAILED`，可 `CANCELLED`
- **JWT 双 Token**：Access (2h) + Refresh (7d)，refresh 轮转（用过即作废），access 支持 jti 黑名单即时撤销
- **RBAC 五角色**：`SUPER_ADMIN` > `TENANT_ADMIN` > `DEVELOPER` > `CONTENT_CREATOR` > `VIEWER`
- **项目成员四角色**（与平台角色正交）：`OWNER` / `EDITOR` / `REVIEWER` / `VIEWER`
- **配额五维**：图片 / 视频 / 存储 / 并发 / API 调用，Redis Lua 原子消费 + 幂等键 + 回滚补偿
- **存储抽象层**：`StorageAdapter` 16 个方法，MinIO 默认实现，可切换云 OSS
- **模型适配器模式**：新增模型只需写一个 `@Component` 实现接口，Spring 自动收集注册，**零配置改动**
- **轻量 RAG 知识库**：自建 embedding 适配器（OpenAI / 通义 / 硅基流动）+ MySQL 向量检索，无需外部向量数据库
- **多轮对话 + 长期记忆**：会话 / 消息持久化，历史按 token 预算截断；每轮异步提取事实 / 偏好（BGE-M3 向量去重，相似度 >0.88 视为重复），跨会话自动检索注入系统提示
- **记忆空间（记忆分组）**：记忆按 `(user, tenant, space)` 隔离，每个用户可建多套「工作 / 写作 / 个人」记忆；**对话创建时绑定一个空间，注入与提取都限定在内**，从而实现同时开多个对话、各自参考不同记忆而互不污染。空间绑定后不可变更（换空间需新建对话，避免上下文撕裂）；存量记忆由迁移脚本全部归入「默认」空间，老对话行为不变

## 模型适配器矩阵

### LLM 文本生成（agent-core，10 个，全部实现）

| Provider | 适配器 | 默认模型 |
|---|---|---|
| openai | `OpenAiAdapter` | gpt-4o |
| deepseek | `DeepSeekAdapter` | deepseek-chat |
| qwen | `QwenTextAdapter` | qwen-turbo |
| anthropic | `ClaudeAdapter` | claude-sonnet-4 |
| google | `GeminiAdapter` | gemini-2.0-flash |
| zhipu | `GlmAdapter` | glm-4-plus |
| moonshot | `KimiAdapter` | moonshot-v1-8k |
| spark | `SparkAdapter` | 4.0Ultra |
| baidu | `ErnieAdapter` | ernie-4.0-8k |
| agnes | `AgnesAdapter` | agnes-2.5-flash |

### 联网搜索（agent-core，1 个）

| Provider 键 | 适配器 | 上游接口 | 说明 |
|---|---|---|---|
| `deepseek-search` | `DeepSeekSearchAdapter` | `POST /v1/tools/search`、`POST /v1/tools/fetch` | 官方自研搜索引擎；按成功请求计次（各 5 元/千次），无结果/失败不计费 |

**接入方式**：对话页「联网搜索」按钮按会话开关，命中结果以 `sourceType=WEB` 的引用返回并注入上下文。
API Key 配在 `model_config`（provider = `deepseek-search`），与 LLM 的 `deepseek` 行**分开**——两者是独立的用量与计费口径。
搜索是「增强」不是「阻塞」：上游失败只降级为无网页引用，对话照常进行。
调试接口：`GET /api/v1/search/status`、`POST /api/v1/search`、`POST /api/v1/search/fetch`。

### 图片生成（image-engine，7 个：6 完整 + 1 骨架）

| Provider 键 | 适配器 | 第三方 API | 状态 |
|---|---|---|---|
| `qwen-image` | `QwenImageAdapter` | DashScope 通义万相 `wanx-v1`（异步轮询） | ✅ 完整 |
| `stability` | `SdAdapter` | Stability AI `stable-diffusion-xl` | ✅ 完整 |
| `dalle` | `DalleAdapter` | OpenAI `dall-e-3` | ✅ 完整 |
| `agnes-image` | `AgnesImageAdapter` | Agnes `agnes-image-2.1-flash`（支持图生图 / 多图合成） | ✅ 完整 |
| `ernie-image` | `ErnieImageAdapter` | 百度文心一格 `ernie-vilg-v2`（自带 OAuth token 缓存） | ✅ 完整 |
| `local-sd` | `LocalImageAdapter` | 本地 FastAPI `sdxl-turbo` | ✅ 完整 |
| `midjourney` | `MidjourneyAdapter` | `midjourney-v6`（**协议为假设，无公开此形态 API**） | ⚠️ 骨架 |

### 视频生成（video-engine，9 个：4 完整 + 4 骨架 + 1 特殊）

| Provider 键 | 适配器 | 第三方 API | 状态 |
|---|---|---|---|
| `wan` | `WanVideoAdapter` | 通义万相 DashScope（轮询 240s） | ✅ 完整 |
| `cogvideo` | `CogVideoXFlashAdapter` | 智谱 CogVideoX-Flash 免费版（轮询 600s） | ✅ 完整 |
| `agnes-video` | `AgnesVideoAdapter` | Agnes `agnes-video-v2.0`（8n+1 帧对齐，唯一做 429 退避） | ✅ 完整 |
| `local-cogvideox` | `LocalVideoAdapter` | 本地 FastAPI CogVideoX-2b | ✅ 完整 |
| `runway` | `RunwayAdapter` | Runway gen3-alpha（**URL 有笔误，协议推测**） | ⚠️ 骨架 |
| `kling` | `KlingAdapter` | 可灵 kling-v1（**协议推测**） | ⚠️ 骨架 |
| `pika` | `PikaAdapter` | Pika 2.0（**协议推测**） | ⚠️ 骨架 |
| `luma` | `LumaAdapter` | Luma Dream Machine（**协议推测**） | ⚠️ 骨架 |
| `deepseek` | `DeepSeekVideoAdapter` | **非视频模型**：用 LLM 扩写提示词后委托给其他适配器 | 🔶 特殊 |

### Embedding（RAG / 记忆，3 个）

`qwen-embedding`（text-embedding-v3）、`openai-embedding`（text-embedding-3-small）、`siliconflow-embedding`（BAAI/bge-m3，国内免费推荐）

### 模型选择机制

`AbstractModelRegistry.select(provider, capabilityKey)` 三级策略：

1. **provider 精确匹配** —— 匹配 `modelName()` 或 `providerFromAdapter()` 推断值（忽略大小写）
2. **能力匹配** —— 图片按 `supportedStyles`，视频按 `supportedResolutions`
3. **兜底** —— 第一个 `healthCheck()` 通过的可用适配器

无可用模型时抛 `RuntimeException("No available generation model")`。

> 注意：`providerFromAdapter()` 靠**类名 contains** 推断（如 `SdAdapter` 含 "sd" → `stability`）。
> `LocalImageAdapter` 类名不含任何关键词 → 回退为 `"localimageadapter"`，所以本地模型必须用
> `modelProvider="local-sd"`（精确匹配 `modelName()`）才能选中。

## 核心链路

### 1. 图片 / 视频任务生成链路

```
前端 → Gateway(JWT 校验 + 身份头注入) → agent-core TaskService.submitTask()
  ├─ ① 预生成 taskId（雪花 ID）—— 同时作为「配额消费幂等键」
  ├─ ② Prompt 审核（Feign → security-service）      ← 先审核后扣配额，失败关闭
  ├─ ③ 配额消费（Feign → user-service）             ← 审核通过才扣，失败关闭
  ├─ ④ 项目归属校验 + 输入资产可见性校验（防跨租户引用）
  ├─ ⑤ 中文提示词自动翻译为英文（失败降级原文）
  ├─ ⑥ RAG 知识库增强（可选，失败降级）
  ├─ ⑦ 建 AgentTask + N 个 WorkflowStep
  ├─ ⑧ 参考图 → 转「10 分钟 HMAC 签名临时 URL」注入参数（云端厂商需公网可达）
  └─ ⑨ 事务提交后(afterCommit)：
        ├─ 发 WebSocket 状态事件
        └─ advanceStep() → CAS 抢 PENDING→PROCESSING → 发 RabbitMQ
              │
              ▼  task.image.single / task.video.single
        image-engine（同步消费 3-10 并发）| video-engine（异步消费 1-3 并发）
          ├─ 幂等锁 task:lock:{taskId}:{stepOrder}
          ├─ ModelRegistry 选模型 → 调第三方 API
          ├─ 下载 / 解码 → 上传 storage-service → 生成 200px 缩略图
          └─ 回执 task.result.done / failed（含 stepOrder）
              │
              ▼
        TaskResultConsumer 按 taskId + stepOrder 精确对账
          ├─ 步骤 DONE → advanceStep() 推进下一步
          ├─ 步骤 FAILED → handleStepFailure()（retry_count ≤3 重试）
          └─ 全部 DONE → 任务 DONE → AssetArchiveService 自动归档为资产 + 估算成本
```

**可靠性设计要点**（均为已修复过的坑，代码注释有记录）：

- **幂等锁 key 必须含 `stepOrder`** —— BATCH 任务 N 个步骤 N 条消息共用 `taskId`，只按 taskId 加锁会让先到步骤占锁、其余消息被误判重复 ACK 丢弃，任务永久卡 PROCESSING
- **ACK 必须先于释放锁** —— 反序则在删锁后崩溃会导致消息重投、重复生成、多扣第三方费用
- **异步模式先提交执行器成功再 ACK** —— 先 ACK 后提交，ACK 完崩溃就丢消息且无恢复机制
- **事务 afterCommit 才发 MQ** —— 防事务回滚后消息成为孤儿；回滚时补偿已扣配额（幂等，7 天标记兜底）
- **失败重试三处独立**：MQ 层 `x-death` 计数、业务层 `agent_task.retry_count` 原子自增、结果队列 `x-death`，阈值统一为 3，超限进死信队列
- **卡死恢复扫描**（`StuckTaskRecoveryJob`）：PROCESSING 超 2h → 判 FAILED；PENDING 超 30min → 重新分发。均用 CAS 更新防覆盖并发完成的回执
- **结果落库**：生成结果统一上传 storage-service，前端经 `/api/v1/files/{id}/download` 访问，文件归属校验防越权

### 2. AI 对话 + 长期记忆链路

```
前端 AI 对话 → POST /api/v1/llm/chat/stream (SSE)
  → ChatService.prepare()
      会话解析 → 存 user 消息 → 加载历史
      → 记忆注入（长期板块全量注入 + 短期板块向量检索 topK）
      → RAG 增强（可选）
      → 联网搜索增强（可选，webSearch=true；失败降级为无网页引用）
      → 按 token 预算截断历史
  → 流式调模型（10 家 LLM 适配器）
  → SSE 6 种事件：meta → phase* → references → delta+ → done | error
  → 持久化 assistant 消息 → 异步提取本轮事实 / 偏好写入记忆
```

会话与记忆归属校验（用户 + 租户）防止越权。

### 3. 认证鉴权链路

```
登录（用户名密码）→ 签发双 Token（Access 2h / Refresh 7d，均带 type claim）
  → 后续请求携带 Access Token
  → Gateway JwtAuthFilter 校验签名 + type=access（refresh 不可当 access 用）+ jti 黑名单
  → 注入 X-User-Id / X-Tenant-Id / X-User-Roles 请求头
  → 服务内 UserContext / TenantContext（ThreadLocal）
  → @RequireRole 角色校验 + 资源归属校验（任务 / 文件 / 会话 / 记忆 / 资产 / 项目 / 租户）
```

**前端配合**：refresh token 轮转意味着并发刷新会互相吊销，前端 `client.ts` 做了「单飞刷新 + 挂起队列」，axios 与 SSE 共用同一次刷新。

### 4. OpenAPI 外部调用链路

```
控制台创建 API Key（ak_ 前缀，仅展示一次；服务端存 bcrypt 哈希 + 前 12 位索引）
  → 调用 /api/v1/open/** 带 X-Api-Key 头
  → Gateway ApiKeyAuthFilter（order -1，先于 JWT）调 user-service 校验（状态 / 过期 / 归属）
  → 注入身份头 → agent-core 按 API Key 身份执行任务，配额与审计归属对应真实用户
```

### JWT_SECRET 配置（重要）

- 所有服务启动时校验 JWT 密钥（≥32 字符），缺失或弱密钥**拒绝启动**
- `start.bat` 首次启动自动生成 48 位随机密钥并持久化到根目录 `.jwt-secret`（已 gitignore，请勿提交）
- 手动启动服务时设置环境变量 `JWT_SECRET=<密钥>`（与 `.jwt-secret` 内容一致）
- ⚠️ 更换密钥会使所有存量 Token 失效，用户需重新登录；生产环境请妥善保管密钥

## RAG 知识库（辅助能力）

轻量自建 RAG，向量存 MySQL JSON 列、应用层余弦相似度检索（预留 `VectorStore` 接口可换 Milvus/pgvector）。

### 配置（管理员）

1. 建表：执行 `sql/rag.sql`（已有数据库时）；路由已内置到 gateway
2. 配置 Embedding Key（二选一）：
   - 管理后台 → 模型配置（分类「向量」）填 Key 并启用（推荐；qwen-embedding 可与通义千问共用 DashScope Key）
   - 或 Nacos `agent-core.yaml` 按现有 provider 模式配置，DB 配置优先
3. 国内环境推荐 `siliconflow-embedding`（BAAI/bge-m3，免费）或 `qwen-embedding`（DashScope，免翻墙）。创建知识库时选定 embedding 模型，**创建后不可更换**（换模型需重建知识库）

### 使用（终端用户）

| 入口 | 说明 |
|------|------|
| 知识库 → 知识库管理 | 新建知识库（选 embedding 模型）→ 上传 txt/md（≤2MB）→ 解析完成（失败可重试）→ 测试检索 |
| AI 对话 | 顶部「知识库」下拉选库后提问，回答自动附带参考资料（文档名 + 相似度 + 片段） |
| 图片 / 视频生成 | 表单勾选「知识库增强」并选库，检索结果自动丰富提示词 |
| 流水线 | 步骤类型选「知识库检索」，检索结果注入后续生成步骤 |
| 开发者 | `POST /api/v1/rag/search {datasetId, query, topK}` 编程调用 |

## 前端页面（30 个页面文件 / 29 个已挂路由）

| 分组 | 页面 |
|------|------|
| 认证 | 登录 / 注册 |
| 概览 | 仪表盘（配额卡 + 最近任务 + 快捷入口） |
| 项目 | 项目列表 / 项目工作台（概览·资产·创作·成员 四 Tab） |
| 资产 | 资产中心（网格/列表双视图 + 筛选 + 待审核快速审核） |
| 创作 | AI 图片 / AI 视频 / 批量生成 / 流水线编排 / 作品画廊 |
| 任务 | 任务列表 / 任务详情（WS 实时刷新） |
| 文件 | 文件管理（上传 / 下载 / 删除） |
| AI 对话 | 模型广场 / 对话（SSE 流式 + RAG 引用）/ 记忆管理 |
| 知识库 | 知识库列表 / 知识库详情（文档上传 / 分块预览 / 检索测试） |
| 个人 | 个人信息 / 配额 / API Key / 提示词模板 |
| 租户 | 租户信息 / 成员管理（管理员） |
| 管理后台 | 系统统计 / 任务审核 / 模型配置 / 审计日志（管理员） |

> `MemeGen.tsx`（表情包生成）**未挂路由、未进菜单**，属遗留功能；后端 `/api/v1/meme/**` 接口仍可用。

## 部署（生产环境）

完整手册见 [deploy/README.md](deploy/README.md)。三档配置，改 `deploy/deploy.conf` 的 `PROFILE` 一个变量即可切换，**代码零改动**：

| 档位 | 适用机器 | 服务数 | 服务发现 | 实测常驻 |
|------|---------|-------|---------|---------|
| `lite` | 2 核 2G | 5 个 JVM（关审核 + 关视频） | 静态服务发现 | ~1.8 GB |
| `medium` | 4 核 4G | 7 个 JVM | 静态服务发现 | ~3 GB |
| `full` | 4 核 8G | 7 个 JVM + Nacos | Nacos | ~4.5 GB |

```powershell
# 本地构建 + 上传
powershell -ExecutionPolicy Bypass -File deploy\build-local.ps1

# 服务器上（首次）
./deploy.sh install    # 装 JDK17/Nginx/Docker、建目录、配 swap、装 systemd+nginx
./deploy.sh config     # 生成 config/application.yml 与 services.env
./deploy.sh infra      # 拉起 MySQL/Redis/RabbitMQ/MinIO
./deploy.sh db         # 建库建表 + 种子数据（幂等）
./deploy.sh start      # 启动 Java 服务
./deploy.sh admin <用户名>   # ⚠️ 必做：提权第一个管理员
```

> ⚠️ **不做 `deploy.sh admin` 平台会处于「能登录但完全不能用」的状态**：注册接口只发 `TENANT_ADMIN`，
> 而模型配置写接口要求 `SUPER_ADMIN`，且所有云端 provider 默认 `enabled=0` 无 Key。
> 提权后需**重新登录**（角色写在 JWT 里）。

## 测试

| 范围 | 内容 |
|------|------|
| Java 单元测试 | `common-core/JsonUtilsTest`、`common-security/JwtUtilsTest`（**仅 2 个**） |
| 前端单元测试 | `api/client.test.ts`（覆盖 JWT 刷新单飞/重放/失败链路，质量较高）、`utils/token.test.ts`、`stores/authStore.test.ts`、`components/common/ResultPreview.test.tsx` |
| CI | `.github/workflows/ci.yml`：后端 `mvn test`（无 Docker）+ 前端 `lint / test / build` |

```bash
mvn test                    # 后端单元测试（无需 Docker）
cd web-admin && npm run test # 前端单元测试
```

> 测试覆盖是当前明显的短板：agent-core 最复杂的编排逻辑（WorkflowEngine / TaskResultConsumer）
> 目前**零测试覆盖**。父 POM 已配好 Testcontainers BOM 与 failsafe（`*IT.java` → `mvn verify`），
> 但尚无集成测试用例。

## 已知限制与待办

以下是源码核对后确认的**实现缺口**，不是猜测：

### 安全策略层（优先）

| 项 | 现状 |
|---|---|
| 生成后内容安全检测 | `ContentSafetyService.checkImage/checkVideo` 是**纯桩**，直接 `return new AuditResult(true, "通过（桩）")`，无任何外部内容安全 API |
| `AUDIT` 工作流步骤 | 常量存在但被**主动禁用**（`WorkflowEngine` 注释：无 REVIEWING 产生路径、无 UI 消费，创建后会卡 PROCESSING） |
| 水印 | `WatermarkService` 实现完整（AWT 半透明文字水印），但**全链路无调用方**，`SecurityFeignClient` 甚至未声明该方法 |
| 敏感词过滤 | 纯子串匹配，无分词 / 无同音变体；默认词表是英文 `violence,porn,weapon,drug,gambling`，**中文提示词基本拦不住** |
| 限流路径配置 | `RateLimitFilter.pathPatterns` 配置项声明了但代码未使用，硬编码 `startsWith("/api/")` |

### 可靠性

| 项 | 现状 |
|---|---|
| 熔断器 | `CircuitBreaker` 实现完整（10 次窗口 / 50% 阈值 / 30s OPEN / 3 次探测），但 `recordSuccess/recordFailure` **全仓库零调用** → 永不跳闸。video-engine 完全无熔断 |
| x-death 重试 | `basicNack(requeue=true)` 按 RabbitMQ 标准行为**不产生 x-death 条目**，`getXDeathCount() < 3` 恒真 → 实际是无限重投 |
| Feign 降级 | `fallback` 已声明但 POM 无 circuitbreaker / sentinel 依赖 → **降级不生效**（上层 try/catch 兜住了） |
| 审计日志注解路径 | `AuditLogAspect` 发 7 字段 `AuditInfo`（含 `elapsedMs`），`AuditLogConsumer` 收 6 字段 `AuditLogMessage` → 反序列化可能失败，该路径审计可能写不进库（网关路径正常） |
| 审计消费失败 | `audit.log.queue` 无死信，消费者 catch 后不 rethrow → 静默丢消息 |

### 功能骨架

| 项 | 现状 |
|---|---|
| 分片上传 | `MinioStorageAdapter` 的 4 个 multipart 方法全部 `throw new UnsupportedOperationException` |
| 视频缩略图 | `VideoPostProcessor` 用 `videoUrl + "?thumb=1"` 占位，未做 ffmpeg 抽帧 |
| 骨架适配器 | Midjourney（图片）、Runway / Kling / Pika / Luma（视频）—— 第三方契约均为推测 |
| 死代码 | `image-engine/client/ApiClient`、`video-engine/client/ApiClient`、前端 `useAppStore`、`@ant-design/charts` 依赖（装了零引用） |

### 前端

- 权限判断散落 6 处硬编码 `roles.includes('TENANT_ADMIN')`，无统一 hook
- 角色越权时 AuthGuard 静默 `Navigate /dashboard`，用户无「无权访问」提示
- 两份 token 真源（localStorage + zustand persist 又存一份），一致性靠人工同步

## 与早期文档的偏差

| 早期文档描述 | 实际情况 |
|---|---|
| 「Maven（13 模块）」「common-lib（6 个）」 | **14 模块 / common-lib 7 个**（`common-engine` 是后抽出来的） |
| 「大模型适配器模式：仅定义接口不实现」 | 已实现 **26 个适配器**（LLM 10 + 图片 7 + 视频 9） |
| 未提及 `project` / `asset` / `modelcap` / `chat` / `ws` / `job` | 均为后续新增的领域包（阶段 1 项目化创作 + 资产中心） |
| 未提及 `common-engine` | 两个引擎的公共抽象，是后端设计质量最高的部分 |

# AI Agent 企业级创作平台 — 架构设计文档

**日期**: 2026-07-05
**版本**: v1.0
**状态**: 待评审

---

## 1. 项目概述

### 1.1 项目定位

企业级 AI Agent 创作平台，核心能力包括 AI 图片生成和 AI 视频生成。提供内部管理后台和对外 OpenAPI 服务，支持多租户隔离。

### 1.2 核心目标

- 完整的任务编排能力（Single / Pipeline / Batch 三种工作流模式）
- 智能路由：根据请求意图自动匹配最优模型和策略
- 多租户隔离 + RBAC 权限体系
- 存储抽象层，默认 MinIO，可切换云 OSS
- 数据安全闭环：prompt 审核 → 生成 → 内容安全检测 → 水印 → 审计
- 中等规模起步，架构预留水平扩展能力

### 1.3 技术栈

| 层次 | 选型 |
|------|------|
| 语言 | Java 17 |
| 框架 | Spring Boot 3 + Spring Cloud |
| 注册/配置 | Nacos |
| 网关 | Spring Cloud Gateway |
| 消息队列 | RabbitMQ |
| 数据库 | MySQL 8.0 |
| 缓存 | Redis 7 |
| 对象存储 | MinIO（可切换阿里云 OSS / 腾讯云 COS） |
| 构建工具 | Maven（多模块） |

---

## 2. 整体架构

### 2.1 服务拓扑

```
                         Nginx/LB
                            │
                     Spring Cloud Gateway
                  (路由 / 限流 / JWT 鉴权 / 审计)
                            │
     ┌──────────┬───────────┼───────────┬──────────┐
     │          │           │           │          │
User-Service  Agent-Core  Security-Svc  Storage-Svc
(认证·鉴权)   (编排·路由)   (审核·加密)   (文件·存储)
(租户·配额)   (工作流)      (审计·水印)   (CDN·生命周期)
                  │
          ┌───────┴───────┐
          │               │
     Image-Engine    Video-Engine
     (图片生成)       (视频生成)
     (模型适配)       (模型适配)
          │               │
          └───────┬───────┘
                  │
     ┌────────────┼────────────┐
     │            │            │
   Nacos      RabbitMQ      Redis
 (注册配置)    (消息队列)     (缓存·锁)
     │            │            │
   MySQL        MinIO        (未来: ES / 监控)
   (元数据)      (对象存储)
```

### 2.2 服务职责

| # | 服务 | 核心职责 | 独立扩缩容 |
|---|------|---------|-----------|
| 1 | Gateway | 统一入口、路由转发、限流、JWT 鉴权、审计日志记录 | ✅ |
| 2 | Agent-Core | 任务接收与拆解、工作流引擎、智能路由、状态机管理 | ✅ |
| 3 | User-Service | 用户管理、角色权限（RBAC）、租户隔离、配额管理、API Key 管理 | ✅ |
| 4 | Security-Service | Prompt 审核、内容安全检测（NSFW）、数据脱敏、审计日志存储、密钥管理、水印注入 | ✅ |
| 5 | Image-Engine | 图片生成任务消费、模型适配、后处理（格式/缩放/裁剪）、结果上报 | ✅ (GPU 亲和) |
| 6 | Video-Engine | 视频生成任务消费、模型适配、后处理（帧处理/转码/缩略图）、结果上报 | ✅ (GPU 亲和) |
| 7 | Storage-Service | 存储抽象（MinIO/OSS）、文件元数据管理、分片上传、预签名 URL、CDN 策略 | ✅ |

### 2.3 通信模式

```
同步调用（OpenFeign）: Gateway → Agent-Core / User-Service
异步任务（RabbitMQ）: Agent-Core → Image-Engine / Video-Engine
回调通知（RabbitMQ）: Engine 完成 → Agent-Core 更新状态
文件操作（Feign）:     Engine → Storage-Service
```

---

## 3. Agent-Core — 任务编排与工作流引擎

### 3.1 任务生命周期状态机

```
CREATED → VALIDATING → PENDING → PROCESSING → DONE
                       ↘ FAILED    ↓
                                   RETRYING → PROCESSING
                                   ↓
                                   FAILED (重试耗尽)
DONE → REVIEWING → PASS / REJECT
```

### 3.2 三种工作流模式

1. **Single（简单任务）**: Prompt → 生成 → 审核 → 完成
2. **Pipeline（流水线）**: Step1: 文生图 → Step2: 图生视频 → Step3: 后处理
3. **Batch（批量并行）**: 同一 Prompt → 并行生成 N 个变体 → 汇总

### 3.3 智能路由

```
用户请求 → IntentParser（意图解析）→ StrategyMatch（策略匹配）→ ParamBuilder（参数构建）→ Dispatch（分发）
```

### 3.4 核心数据表

- `agent_task`: 任务主表（user_id, workflow_type, status, request_params, result, retry_count）
- `agent_workflow_step`: 工作流步骤表（task_id, step_order, step_type, status, input, output）

---

## 4. Image-Engine 与 Video-Engine

### 4.1 内部组件

```
TaskConsumer（MQ 消费）→ ModelAdapter（模型适配）→ PostProcessor（后处理）→ ResultReporter（结果上报）
```

核心组件:
- **ApiClient**: HTTP 客户端（OkHttp/WebClient）、重试策略（指数退避）、熔断器、限流器（令牌桶）
- **ModelRegistry**: 策略选择，按请求特征匹配适配器

### 4.2 模型适配器接口

```java
public interface ImageGenerationAdapter {
    String modelName();
    ModelCapability capability();
    List<GenerationResult> generate(GenerateRequest request);
    TaskStatus queryStatus(String externalTaskId);
    boolean healthCheck();
}
```

### 4.3 三种 API 调用模式

| 模式 | 说明 | 适用场景 |
|------|------|---------|
| 同步返回 | POST → 即时返回结果（阻塞等待 + 超时） | 多数文生图 API |
| 异步轮询 | POST → 202 → 定时 GET 轮询 → 完成 | 视频生成、高分辨率图片 |
| SSE 流式 | POST → SSE 流式接收进度和结果 | 支持流式推送的模型 |

### 4.4 错误处理分层

- **第一层（ApiClient）**: 连接超时→重试；429→退避+降级；5xx→熔断+重试；4xx→直接 FAIL
- **第二层（Adapter）**: 响应解析失败→FAIL+告警；异步超时→标记模型不可用
- **第三层（Engine）**: 所有适配器不可用→任务保持 PENDING+告警；重试耗尽→死信队列

### 4.5 模型配置（Nacos 动态配置）

每个模型通过 Nacos 配置 endpoint、apiKey、超时、限流、重试策略、能力声明等，支持动态上下线。

---

## 5. Security-Service — 数据安全

### 5.1 功能模块

| 模块 | 职责 | 触发时机 |
|------|------|---------|
| 内容安全 | NSFW 检测、违规图片/视频识别 | 生成完成后回调触发 |
| Prompt 审核 | 敏感词过滤、危险 prompt 拦截 | 提交任务时 Agent-Core 调用 |
| 数据脱敏 | 日志/响应中手机号、邮箱等脱敏 | Gateway 层 + 各服务调用 |
| 审计日志 | 全操作链路记录 | 各服务异步上报 |
| 密钥管理 | API Key、加密密钥统一生成/轮转 | 被各服务调用 |
| 水印注入 | 生成图片/视频注入数字水印 | 生成后处理环节 |

### 5.2 安全流水线

```
提交 Prompt → Prompt 审核（通过/拒绝）
生成完成 → 内容安全检测（通过/拦截/疑似→人工审核）
```

---

## 6. Storage-Service — 存储抽象

### 6.1 存储抽象层

```java
public interface StorageAdapter {
    UploadResult upload(InputStream data, String objectKey, String contentType, Map<String, String> metadata);
    String initiateMultipart(String objectKey, String contentType);
    UploadResult uploadPart(String uploadId, int partNumber, InputStream data);
    UploadResult completeMultipart(String uploadId, List<PartResult> parts);
    InputStream download(String objectKey);
    String presignedUrl(String objectKey, Duration ttl);
    boolean exists(String objectKey);
    FileMetadata metadata(String objectKey);
    void delete(String objectKey);
    InputStream thumbnail(String objectKey, int width, int height);
}
```

### 6.2 文件生命周期

| 级别 | 说明 | TTL |
|------|------|-----|
| TEMP | 生成中/审核中 | 24h 自动清理 |
| ASSET | 审核通过/可访问 | 用户配额控制 |
| ARCHIVE | 长期不访问 | 降冷到低成本存储 |

### 6.3 上传模式

- **服务端上传**: Engine → Storage-Service（文件流转发）
- **客户端直传**: Frontend → 预签名 URL → MinIO/OSS（绕过服务端，省带宽）

---

## 7. User-Service — 用户与鉴权

### 7.1 认证机制

- JWT 双 Token：Access Token（2h） + Refresh Token（7d）
- Refresh Token 轮转：刷新时同时更换新旧 Token
- API Key 鉴权：SHA-256 哈希存储，支持调用限额

### 7.2 RBAC 角色矩阵

| 角色 | 管理租户 | 管理成员 | API Key | 提交任务 | 查看全部任务 | 管理模型 | 查看统计 |
|------|---------|---------|---------|---------|------------|---------|---------|
| SUPER_ADMIN | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| TENANT_ADMIN | ❌ | ✅ | ✅ | ✅ | ✅ | ❌ | ✅ |
| DEVELOPER | ❌ | ❌ | ✅ | ✅ | ❌ | ✅ | ❌ |
| CONTENT_CREATOR | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ |
| VIEWER | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |

### 7.3 配额维度

| 维度 | 粒度 | 超出行为 |
|------|------|---------|
| 图片生成次数 | 用户/天、租户/月 | 拒绝新任务 |
| 视频生成次数 | 用户/天、租户/月 | 拒绝新任务 |
| 存储空间 | 租户总容量 | 拒绝上传 + 预警 |
| 并发任务数 | 用户同时运行 | 排队等待 |
| API 调用次数 | Key/分钟、Key/天 | 429 限流 |

---

## 8. Gateway + 统一 API 设计

### 8.1 Filter Chain

```
CorsFilter → RateLimitFilter → JwtAuthFilter → TenantContextFilter → AuditLogFilter → Route
```

### 8.2 API 路由

| 路径前缀 | 目标服务 | 鉴权方式 |
|---------|---------|---------|
| `/api/v1/auth/**` | user-service | 白名单（登录/注册跳过） |
| `/api/v1/users/**` | user-service | JWT |
| `/api/v1/tenants/**` | user-service | JWT + 角色 |
| `/api/v1/tasks/**` | agent-core | JWT |
| `/api/v1/files/**` | storage-service | JWT |
| `/api/v1/admin/**` | agent-core | JWT + ADMIN 角色 |
| `/api/v1/open/**` | agent-core | API Key |

### 8.3 统一响应体

```json
{
  "code": 0,
  "message": "success",
  "data": {},
  "requestId": "uuid",
  "timestamp": 1719999999000
}
```

### 8.4 错误码体系

| 区间 | 含义 |
|------|------|
| 0 | 成功 |
| 1xxx | 参数错误 |
| 2xxx | 认证授权 |
| 3xxx | 业务限制 |
| 4xxx | 资源不存在 |
| 5xxx | 系统错误 |
| 9xxx | 安全拦截 |

---

## 9. common-lib — 公共模块

### 9.1 子模块

| 模块 | 内容 | 依赖方 |
|------|------|--------|
| common-core | Result、BizException、ErrorCode、BaseEntity、SnowflakeId | 所有服务 |
| common-web | JacksonConfig、上下文拦截器、RequestContext/TenantContext/UserContext、AOP 注解（@AuditLog, @RateLimit, @RequireRole） | 需要 Web 上下文的服务 |
| common-security | JwtUtils、ApiKeyAuthFilter、AesUtils、BCryptUtils | Gateway、User-Service、Security-Service |
| common-mq | RabbitMqConfig、交换机/队列/消息体定义 | Agent-Core、Engine、Security-Service、Storage-Service |
| common-storage | StorageAdapter 接口、UploadResult、FileMetadata | Engine、Storage-Service |
| common-feign | Feign 接口声明 + 降级实现 | Agent-Core、Gateway |

### 9.2 上下文传递机制

Gateway 解析 JWT → 注入请求头（X-Request-Id, X-Tenant-Id, X-User-Id, X-User-Roles）→ 下游服务拦截器写入 ThreadLocal → Feign 调用时自动透传 → 请求结束清理。

### 9.3 分布式 ID

SnowflakeIdGenerator：workerId 从 Nacos 自动分配，返回 19 位纯数字字符串，支持带前缀（如 `task_xxx`）。

---

## 10. 大模型接入（预留）

### 10.1 适配器接口

```java
public interface ImageGenerationAdapter {
    String modelName();
    ModelCapability capability();
    List<GenerationResult> generate(GenerateRequest request);
    TaskStatus queryStatus(String externalTaskId);
    boolean healthCheck();
}
```

### 10.2 后续接入步骤

1. 在 `adapter/` 包下实现 `ImageGenerationAdapter` / `VideoGenerationAdapter` 接口
2. 在 Nacos 配置中添加模型元信息
3. ModelRegistry 自动发现并注册
4. 无需修改其他任何代码

---

## 11. 项目模块结构

```
woshijavagaoshou/
├── common-lib/
│   ├── common-core/
│   ├── common-web/
│   ├── common-security/
│   ├── common-mq/
│   ├── common-storage/
│   └── common-feign/
├── gateway/
├── agent-core/
├── user-service/
├── security-service/
├── image-engine/
├── video-engine/
├── storage-service/
├── docs/
│   └── superpowers/specs/
└── pom.xml (父 POM)
```

---

## 12. 待后续深入

- LLM 模块具体实现（模型 API 接入留待下一步）
- 监控告警体系（Prometheus + Grafana）
- 链路追踪（SkyWalking / Micrometer Tracing）
- CI/CD 流水线
- 容器化部署（Docker Compose → Kubernetes）

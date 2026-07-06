# AI Agent 企业级创作平台

企业级 AI Agent 创作平台，核心能力包含 AI 图片生成和 AI 视频生成。支持内部管理后台与对外 OpenAPI 服务，具备完整的多租户隔离、RBAC 权限体系和数据安全流水线。

## 技术栈

| 层次 | 选型 |
|------|------|
| 语言 | Java 17 |
| 框架 | Spring Boot 3.2.5 + Spring Cloud 2023.0.1 |
| 注册/配置 | Nacos 2.3 |
| 网关 | Spring Cloud Gateway |
| 消息队列 | RabbitMQ 3.12 |
| 数据库 | MySQL 8.0 |
| 缓存 | Redis 7 |
| 对象存储 | MinIO（可切换阿里云 OSS / 腾讯云 COS） |
| 构建工具 | Maven（13 模块） |

## 项目结构

```
woshijavagaoshou/
├── common-lib/           # 公共模块（6 个）
│   ├── common-core/      # Result, BaseEntity, ErrorCode, BizException, 雪花ID, JsonUtils
│   ├── common-web/       # 上下文拦截器, Feign配置, @AuditLog, @RequireRole
│   ├── common-security/  # JWT双Token, AES加密, BCrypt
│   ├── common-mq/        # RabbitMQ 交换机/队列/消息体定义
│   ├── common-storage/   # StorageAdapter 接口 + DTO
│   └── common-feign/     # Feign 客户端接口 + 降级实现
├── gateway/              # API 网关 (8080)
├── agent-core/           # 任务编排核心 (8082)
├── user-service/         # 用户与鉴权 (8081)
├── security-service/     # 安全检测与水印 (8083)
├── image-engine/         # 图片生成引擎 (8085)
├── video-engine/         # 视频生成引擎 (8086)
├── storage-service/      # 文件存储 (8084)
├── docs/                 # 设计文档与实现计划
├── sql/                  # 数据库初始化脚本
├── docker-compose.yml    # 基础设施编排
└── pom.xml               # 父 POM
```

## 快速开始

### 1. 启动基础设施

```bash
docker-compose up -d
```

等待所有服务健康：

```bash
docker-compose ps
```

### 2. 初始化数据库

启动 MySQL 后，SQL 脚本会自动执行（挂载在 `./sql:/docker-entrypoint-initdb.d`）。

如有需要手动执行：

```bash
docker exec -i mysql mysql -uroot -proot123 < sql/init.sql
```

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

## 服务端口

| 服务 | 端口 | 说明 |
|------|------|------|
| Gateway | 8080 | 统一入口 |
| User-Service | 8081 | 用户/租户/角色/配额 |
| Agent-Core | 8082 | 任务编排引擎 |
| Security-Service | 8083 | 审核/水印/审计 |
| Storage-Service | 8084 | 文件存储 |
| Image-Engine | 8085 | 图片生成 |
| Video-Engine | 8086 | 视频生成 |

## API 路由

| 路径前缀 | 目标服务 | 鉴权 |
|---------|---------|------|
| `/api/v1/auth/**` | user-service | 白名单（login/register/refresh 跳过） |
| `/api/v1/users/**` | user-service | JWT |
| `/api/v1/tenants/**` | user-service | JWT + 角色 |
| `/api/v1/tasks/**` | agent-core | JWT |
| `/api/v1/files/**` | storage-service | JWT |
| `/api/v1/admin/**` | agent-core | JWT + ADMIN 角色 |
| `/api/v1/open/**` | agent-core | API Key |

## 核心特性

- **三种工作流模式**: Single（简单）、Pipeline（串行流水线）、Batch（并行批量）
- **任务状态机**: CREATED → VALIDATING → PENDING → PROCESSING → DONE
- **JWT 双 Token**: Access Token (2h) + Refresh Token (7d)，轮转刷新
- **RBAC 五角色**: SUPER_ADMIN > TENANT_ADMIN > DEVELOPER > CONTENT_CREATOR > VIEWER
- **安全流水线**: Prompt 审核 → 生成 → 内容安全检测 → 水印注入 → 审计日志
- **存储抽象层**: MinIO 默认实现，可切换云 OSS
- **大模型适配器模式**: 仅定义接口不实现，后续模型只需实现接口即可接入



# AI Agent 创作平台

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
├── agent-core/           # 任务编排核心 (8082)（含 RAG 知识库：rag 包）
│   └── rag/              #   RAG 检索模块（embedding 适配器 / 向量存储 / 检索服务）
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

### 0. 一键启动（Windows 推荐）

双击根目录 `start.bat`，自动完成：清理旧进程 → 启动 Docker 基础设施 → Maven 编译 → 后台静默启动全部 8 个服务（Gateway / User / Storage / AgentCore / Security / Image / Video / WebAdmin）。

所有服务日志统一写入 `logs\` 目录，不再弹出多个控制台窗口，只保留一个控制台面板：

| 菜单 | 功能 |
|------|------|
| `[1]` | 查看某个服务的日志（实时刷新，`V` 打开完整日志） |
| `[2]` | 一键搜索所有日志中的 ERROR / Exception |
| `[3]` | 刷新服务状态（运行中 / 启动中 / 未启动） |
| `[4]` | 停止所有服务并退出 |
| `[5]` | 退出（服务继续在后台运行） |

常用参数：`start.bat -SkipInfra`（跳过 Docker）、`start.bat -SkipBuild`（跳过编译，快速重启服务）。

AI 推理服务需单独启动：`inference\start.bat`（端口 7860）。

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
docker exec -i mysql mysql -uroot -p123456 < sql/init.sql
docker exec -i mysql mysql -uroot -p123456 < sql/rag.sql
docker exec -i mysql mysql -uroot -p123456 < sql/model_config.sql
```

> `rag.sql`（RAG 知识库三张表）与 `model_config.sql`（embedding 模型配置行）仅在全新数据库首次初始化时自动执行；已有数据库升级需手动执行上面两行。

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

## 数据库实例（重要：机器上有两台 MySQL，勿混淆）

| 实例 | 位置 | 端口 | 用途 |
|------|------|------|------|
| **项目 MySQL** | Docker 容器 `mysql`（数据卷 `mysql_data`） | **3307**（容器内 3306） | 本平台全部业务库：`agent_platform` / `user_service` / `security_service` / `storage_service` |
| 本机 MySQL | Windows 本机 `mysqld.exe`（独立安装） | 3306 | **与本项目无关**（`vehicle_mes` 等其他项目数据）；IDE 的 MCP MySQL 工具连接的是这台 |

- 所有微服务经 `jdbc:mysql://localhost:3307/...` 连接**项目实例**；SQL 脚本（`init.sql` / `rag.sql` / `model_config.sql`）也一律执行在 **3307 项目实例**
- 访问项目库：`docker exec -it mysql mysql -uroot -p123456`，或 Navicat 等工具连 `127.0.0.1:3307`
- ⚠️ **不要在  本机实例上执行本项目任何 SQL**；使用 MCP 工具前必须确认连接实例（`SHOW VARIABLES LIKE 'port'` / `SELECT DATABASE()`），或直接用 `docker exec`
- 备份：`docker exec mysql mysqldump -uroot -p123456 --all-databases > backup.sql`
- 风险：Docker Desktop 执行「Reset / Clean data」会清空 `mysql_data` 卷，重要数据请定期 dump

## API 路由

| 路径前缀 | 目标服务 | 鉴权 |
|---------|---------|------|
| `/api/v1/auth/**` | user-service | 白名单（login/register/refresh 跳过） |
| `/api/v1/users/**` | user-service | JWT |
| `/api/v1/tenants/**` | user-service | JWT + 角色 |
| `/api/v1/tasks/**` | agent-core | JWT |
| `/api/v1/files/**` | storage-service | JWT |
| `/api/v1/admin/**` | agent-core | JWT + ADMIN 角色 |
| `/api/v1/rag/**` | agent-core | JWT |
| `/api/v1/open/**` | agent-core | API Key |

## 核心特性

- **三种工作流模式**: Single（简单）、Pipeline（串行流水线）、Batch（并行批量）
- **任务状态机**: CREATED → VALIDATING → PENDING → PROCESSING → DONE
- **JWT 双 Token**: Access Token (2h) + Refresh Token (7d)，轮转刷新
- **RBAC 五角色**: SUPER_ADMIN > TENANT_ADMIN > DEVELOPER > CONTENT_CREATOR > VIEWER
- **安全流水线**: Prompt 审核 → 生成 → 内容安全检测 → 水印注入 → 审计日志
- **存储抽象层**: MinIO 默认实现，可切换云 OSS
- **大模型适配器模式**: 仅定义接口不实现，后续模型只需实现接口即可接入
- **轻量 RAG 知识库**: 自建 embedding 适配器（OpenAI / 通义 compatible-mode）+ MySQL 向量检索，无需外部向量数据库；检索结果可注入 LLM 对话、生成提示词与流水线步骤

## RAG 知识库（辅助能力）

轻量自建 RAG，向量存 MySQL JSON 列、应用层余弦相似度检索（预留 VectorStore 接口可换 Milvus/pgvector）。

### 配置（管理员）

1. 建表：执行 `sql/rag.sql`（已有数据库时）；路由已内置到 gateway
2. 配置 Embedding Key（二选一）：
   - 管理后台 → 模型配置（分类「向量」）填 Key 并启用（推荐；qwen-embedding 可与通义千问共用 DashScope Key）
   - 或 Nacos `agent-core.yaml` 按现有 provider 模式配置，DB 配置优先
3. 国内环境推荐 `qwen-embedding`（DashScope，免翻墙）。创建知识库时选定 embedding 模型，**创建后不可更换**（换模型需重建知识库）

### 使用（终端用户）

| 入口 | 说明 |
|------|------|
| 知识库 → 知识库管理 | 新建知识库（选 embedding 模型）→ 上传 txt/md（≤2MB）→ 解析完成（失败可重试）→ 测试检索 |
| AI 对话 | 顶部「知识库」下拉选库后提问，回答自动附带参考资料（文档名 + 相似度 + 片段） |
| 图片 / 视频生成 | 表单勾选「知识库增强」并选库，检索结果自动丰富提示词 |
| 流水线 | 步骤类型选「知识库检索」，检索结果注入后续生成步骤 |
| 开发者 | `POST /api/v1/rag/search {datasetId, query, topK}` 编程调用 |



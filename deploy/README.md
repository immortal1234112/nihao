# 部署手册

目标环境：**一台 2 核 2G 的 Ubuntu 云服务器**，用 `http://<公网IP>` 访问。
域名备案通过后改一个配置项即可切成域名 + HTTPS。

---

## 1. 这套方案长什么样

```
                    浏览器
                       │  http://<IP>:80
                       ▼
              ┌─────────────────┐
              │  nginx (宿主)   │  静态文件 + /api 反代
              └────────┬────────┘
                       │ 127.0.0.1:8080
                       ▼
              ┌─────────────────┐
              │ gateway   (JVM) │  ← 唯一对外暴露的 Java 服务
              └────────┬────────┘
        ┌──────────────┼───────────────┬──────────────┐
        ▼              ▼               ▼              ▼
   user-service   agent-core     image-engine   storage-service
     :8081          :8082           :8085           :8084
        │              │               │              │
        └──────────────┴───────┬───────┴──────────────┘
                               ▼
              ┌────────────────────────────────┐
              │ Docker: MySQL / Redis /        │  端口只绑 127.0.0.1
              │         RabbitMQ / MinIO       │
              └────────────────────────────────┘
```

**和完整架构的差别**（这是为 2G 内存做的取舍，不是残缺版）：

| | 极简档 `lite` | 完整档 `full` |
|---|---|---|
| 服务数 | 5 个 JVM | 7 个 JVM |
| 服务发现 | Spring Cloud `SimpleDiscoveryClient`（静态表） | Nacos |
| 内容审核 | 关闭（提示词不过滤敏感词） | security-service |
| 视频生成 | 关闭 | video-engine |
| 实测常驻 | ~1.8 GB | ~4.5 GB |

切换档位只需要改 [deploy.conf](deploy.conf) 里的 `PROFILE`，重跑 `./deploy.sh all`。
**代码一行都不用改** —— 静态服务发现是 `spring-cloud-commons` 自带的能力，
`lb://` 路由、Feign、Gateway 全部照常工作。

---

## 2. 前置条件

**服务器**
- Ubuntu 22.04 / 24.04，2 核 2G 起步，20G 以上磁盘
- 安全组放行 **22**（SSH）和 **80**（HTTP）
  ⚠️ 这一步最容易忘。本机 `ufw` 不拦不影响 —— 云厂商的安全组在更外层，
  没放行就是浏览器一直转圈直到超时，服务器上看不到任何日志。

**本地开发机**
- JDK 17、Maven 3.8+
- Node 18+
- SSH 客户端（Windows 10+ 自带 `ssh` / `scp` / `tar`）

---

## 3. 首次部署

### 3.0 填配置

编辑 [deploy.conf](deploy.conf)，**只有前两项是必填的**：

```bash
SERVER_HOST="47.98.123.45"   # 服务器公网 IP —— 必填
SERVER_USER="root"
SSH_KEY=""                   # 留空用默认 ~/.ssh/id_rsa
PROFILE="lite"               # 2C2G 保持 lite
```

> `SERVER_HOST` 留空会让脚本直接报错退出。这是故意的：它会变成 CORS 白名单
> 和「云端厂商回拉参考图」的地址，填错的后果（WebSocket 握手被拒、图生图永久
> 失败）非常难反查到根因，所以宁可不让你跑。

### 3.1 本地构建 + 上传

```powershell
powershell -ExecutionPolicy Bypass -File deploy\build-local.ps1
```

做四件事：Maven 打包 → Vite 构建前端 → 打成一个 tar.gz（约 300MB）→ 上传并解包到 `/opt/ai-agent`。

首次会问 SSH 密码或使用密钥。上传慢是正常的，取决于上行带宽。

### 3.2 服务器上按顺序执行

```bash
ssh root@47.98.123.45
cd /opt/ai-agent

./deploy.sh install    # 装 JDK17/Nginx/Docker、建目录、配 swap、装 systemd+nginx、生成密钥
./deploy.sh config     # 生成 config/application.yml 与 services.env
./deploy.sh infra      # 拉起 MySQL/Redis/RabbitMQ/MinIO（等健康检查通过）
./deploy.sh db         # 建库建表 + 种子数据（幂等，重复跑无副作用）
./deploy.sh start      # 启动 5 个 Java 服务
```

`install` 会装东西、配 swap，需要几分钟。全部跑完后打开 `http://47.98.123.45` 就能看到登录页。

### 3.3 ⚠️ 提权第一个管理员（必做）

**不做这步平台会处于「能登录但完全不能用」的状态**：

- 注册接口只发 `TENANT_ADMIN` 角色
- 而模型配置（API Key / BaseUrl）的写接口要求 `SUPER_ADMIN`
- 且 `model_config` 表里所有云端 provider 都是 `enabled=0` 且没有 key

结果就是没人能配 API Key → 对话、图片生成、RAG embedding 全部不可用，
而且界面上没有任何自救路径。

```bash
# 先在浏览器里注册一个账号，然后：
./deploy.sh admin 你的用户名
```

> 提权后**需要重新登录** —— 角色写在 JWT 里，旧 token 还是老角色。

然后进「管理 → 模型配置」，给至少一个 LLM provider（如 DeepSeek）和
embedding provider（如硅基流动 BGE-M3）填上 API Key 并启用。

### 3.4 验证

```bash
./deploy.sh status     # 容器 + 服务状态 + 各自内存 + swap 用量
curl -I http://127.0.0.1/healthz
```

浏览器上值得走一遍的路径：
1. 登录 → 新建对话 → 发一条消息（验证 LLM 链路）
2. 生成一张图（验证 image-engine → RabbitMQ → storage-service → MinIO 链路）
3. 上传一个文档建知识库并提问（验证 RAG embedding + 向量检索）

---

## 4. 日常部署

改了代码之后：

```powershell
# 本地
powershell -ExecutionPolicy Bypass -File deploy\build-local.ps1 -Deploy
```

`-Deploy` 会上传完自动执行 `./deploy.sh all`（预检 + 生成配置 + 起基础设施 +
SQL 迁移 + 重启全部服务）。

只想传文件不重启：

```powershell
... build-local.ps1          # 不加 -Deploy，然后手工在服务器上跑
```

只改了前端 / 只改了配置：

```powershell
... build-local.ps1 -SkipBuild   # 不重新编译，只把上次的产物再传一遍
```

**常用运维命令**（都在服务器 `/opt/ai-agent` 下）：

```bash
./deploy.sh status            # 看状态
./deploy.sh logs agent-core   # 跟日志
./deploy.sh restart
./deploy.sh doctor            # 只做预检和审计队列治理，不重启
./deploy.sh admin <用户名>     # 提权管理员
```

---

## 5. 排障

### 页面能打开，但所有接口 502 / 504

Gateway 没起来或起得很慢。2 核机器上 5 个 JVM 逐个启动要 1～2 分钟。

```bash
./deploy.sh status
./deploy.sh logs gateway
```

### 服务反复重启

```bash
systemctl status ai-agent@agent-core
journalctl -u ai-agent@agent-core -n 100 --no-pager
```

几种典型原因：

| 现象 | 原因 | 处理 |
|---|---|---|
| 启动即退出，日志有 `JWT_SECRET` 相关 | `.env` 丢了或 JWT_SECRET 少于 32 字符 | 从备份恢复 `.env`；没有备份则只能重新生成，代价是全部用户掉线 |
| 提交任务报 `QUOTA_UNAVAILABLE` | `INTERNAL_AUTH_TOKEN` 为空 → 内部接口 401（失败关闭） | 检查 `/opt/ai-agent/services.env`，重跑 `./deploy.sh config` |
| 提交任务报 `AUDIT_UNAVAILABLE` | security-service 关了但降级开关没开 | 重跑 `./deploy.sh config`（会按档位自动推导 `PROMPT_AUDIT_DEGRADE_ALLOWED=true`） |
| 容器全在但服务连不上数据库 | MySQL 端口不对 | `services.env` 里 `MYSQL_PORT` 必须是 3306（jar 内默认值是开发用的 3307） |

### 经常被 OOM 杀掉 / SSH 卡

```bash
free -h
vmstat 1 5        # 看 si/so 列，长期不为 0 说明真的不够用
```

2C2G 跑这套是贴着极限的。已经做的缓解：JVM 显式限堆、每个服务有 `MemoryMax`
上限（一个服务失控时只杀它而不是整机）、4G swap。

如果长期靠 swap 撑着，说明该升配了 —— 升到 4C4G 后把 `deploy.conf` 的
`PROFILE` 改成 `medium`，重跑 `./deploy.sh all` 就行。

### 前端能开，任务进度条不动

WebSocket 握手失败。检查 `CORS_ALLOWED_ORIGINS` 是否等于浏览器的实际来源。
它由 `SERVER_HOST` 推导，所以 `SERVER_HOST` 填错就会这样。

### 图生图 / 图生视频一直失败

云端厂商要来下载你的参考图，用的是 `STORAGE_ENGINE_BASE_URL`
（= `http://<SERVER_HOST>`）。这意味着：

1. 该地址必须**公网可达**（不能是 localhost 或内网 IP）
2. 部分厂商要求 **https**，未备案阶段只有 http+IP 时可能被拒 —— 这种情况
   只能等备案通过换域名

---

## 6. 升配到完整档

```bash
# 1. 编辑 deploy.conf
PROFILE="medium"        # 4C4G：7 个服务，仍然不用 Nacos
# 或
PROFILE="full"          # 4C8G：7 个服务 + Nacos，回到原始架构

# 2. 重跑
cd /opt/ai-agent && ./deploy.sh all
```

`PROFILE=full` 时 `deploy.sh` 会自动：
- 用 `--profile full` 拉起 Nacos 容器
- 把 `SPRING_CLOUD_NACOS_*` 打开，改用 Nacos 做服务发现
- 生成额外的 JVM 参数与服务启停清单

### 切到域名 + HTTPS

1. `deploy.conf` 里填 `DOMAIN="your-domain.com"`
2. `./deploy.sh all`（会重写 nginx 配置、CORS 白名单、文件 URL 前缀）
3. 签证书：
   ```bash
   apt install -y certbot python3-certbot-nginx
   certbot --nginx -d your-domain.com -d www.your-domain.com
   ```
4. `certbot renew --dry-run` 验证自动续期

---

## 7. 备份

**必须备份的只有两样：**

```bash
# 1. 密钥文件（最容易忽略，丢了代价最大）
cp /opt/ai-agent/.env ~/ai-agent-env.backup

# 2. 数据库
docker exec ai-mysql mysqldump -uroot -p"$(grep ^MYSQL_ROOT_PASSWORD /opt/ai-agent/.env | cut -d= -f2)" \
  --databases agent_platform user_service security_service storage_service \
  > ~/ai-agent-db-$(date +%F).sql

# 3. 对象存储（用户上传的文件与生成的图片）
docker run --rm -v ai-agent_minio_data:/data -v ~:/backup alpine \
  tar czf /backup/ai-agent-minio-$(date +%F).tar.gz -C /data .
```

> ⚠️ `/opt/ai-agent/.env` 一旦丢失就**不可恢复**，而且后果是连锁的：
> `MYSQL_ROOT_PASSWORD` 变了连不上已有数据卷（数据还在但那不进去）、
> `JWT_SECRET` 变了所有用户被踢下线、`MODEL_CONFIG_CRYPTO_KEY` 变了
> `model_config` 里已加密的 API Key 全部解不开。
> **部署完第一件事就是把它拷到本地。**

---

## 8. 目录说明

```
/opt/ai-agent/
├── .env                     ★ 密钥，只生成一次，永不覆盖，务必备份
├── services.env             每次部署重新生成的派生配置（端口/主机/开关）
├── config/application.yml   静态服务发现 + 日志级别覆盖（高于 jar 内配置）
├── apps/                    7 个 fat jar
├── web/                     前端静态产物（nginx root）
├── logs/                    各服务日志（logrotate 每天切、留 7 天）
├── bin/run-service.sh       单服务启动器（systemd 调用）
├── deploy.sh                主脚本
├── remote-install.sh        系统环境初始化
├── docker-compose.infra.yml 基础设施编排
├── nginx/ai-agent.conf      nginx 站点模板（install 时替换占位符后安装）
└── systemd/ai-agent@.service systemd 模板单元
```

文件分工的原则：
**`.env` 是有状态密钥，`services.env` 是派生配置。**
前者只生成一次，后者每次部署重建 —— 这样重跑部署不会把数据库密码重置掉。

---

## 9. 已知限制

- **内容审核已关闭**。极简档没有 security-service，提示词不做敏感词过滤。
  云端模型厂商有自己的审核兜底，但那是第二道防线，不等于合规。
- **审计日志不再落库**。没有消费者，队列里的消息 1 小时后自动过期
  （`deploy.sh` 会自动给队列加 TTL 策略）。升到完整档会自动取消该策略。
- **单点无冗余**。Redis 挂了整个网关会不可用（网关鉴权要查 Redis 黑名单），
  MySQL 挂了全平台不可用。这是单机 2C2G 的必然代价。
- **静态服务发现不会自动感知实例上下线**。改端口/加减服务后必须重跑
  `./deploy.sh config`。

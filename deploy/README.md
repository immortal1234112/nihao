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
| 实测常驻 | ~1.7 GB | ~4.5 GB |

切换档位只需要改 [deploy.conf](deploy.conf) 里的 `PROFILE`，重跑 `./deploy.sh all`。
**代码一行都不用改** —— 静态服务发现是 `spring-cloud-commons` 自带的能力，
`lb://` 路由、Feign、Gateway 全部照常工作。

### 2C2G 内存账本（lite 档）

2G 内存跑这套是**贴着上限**的。下面是逐项实测值，部署前先对照自己的机器：

| 项 | 常驻 | 说明 |
|---|---|---|
| `gateway` | 150 MB | 堆 128m + Netty 堆外 96m |
| `user-service` | 180 MB | 堆 192m |
| `agent-core` | 280 MB | 堆 320m，Metaspace 160m（类最多） |
| `image-engine` | 180 MB | 堆 192m |
| `storage-service` | 170 MB | 堆 160m |
| **5 个 JVM 小计** | **960 MB** | |
| `mysql` | 250 MB | buffer pool 压到 128m、关 performance_schema 与 binlog |
| `rabbitmq` | 150 MB | 水位阀 0.3 |
| `minio` | 80 MB | |
| `redis` | 10 MB | maxmemory 128m 只是兜底阀 |
| **4 个容器小计** | **490 MB** | |
| Ubuntu + systemd + nginx | 215 MB | |
| **合计** | **~1665 MB** | 占 2048 MB 的 **81%** |
| **余量** | **~383 MB** | 靠 4G swap 兜底 |

> 余量只有 ~380 MB，这不是"绰绰有余"而是"刚好够用"。
> 所以 `install` 阶段会强制配 **4G swap**（`vm.swappiness=20`），
> 每个服务还有 systemd `MemoryMax` 硬限——失控时只杀那一个服务，不会拖着整机 OOM。

**如果要更多余量**，按影响从大到小：

1. 关掉 `image-engine`（省 180 MB）——但图片生成就没了，通常不值得
2. `agent-core` 堆降到 256m（省 64 MB）——对话长了更容易 OOM
3. 升配到 4C4G 改 `PROFILE="medium"`（多 2 个服务但余量反而更大）

### 2C2G 的 JVM 防御参数

[deploy.conf](deploy.conf) 的 `JVM_BASE` 里有几条是**小内存机器专用的保命项**，不要删：

| 参数 | 作用 |
|---|---|
| `-XX:+ExitOnOutOfMemoryError` | 堆耗尽时立刻退出，由 systemd 拉起。不加会疯狂 GC + 换页，swap 打满、SSH 卡死，整机假死 |
| `-XX:ActiveProcessorCount=2` | 强制 JVM 按 2 核算线程池。云主机上可能读到 16 核，ForkJoinPool/GC 线程会按 16 开，白吃内存 |
| `-XX:-UsePerfData` | 关掉 hsperfdata 写入，每个 JVM 省几 MB，5 个就是几十 MB |
| `-XX:MaxDirectMemorySize` | 限制 Netty/OkHttp 的堆外内存（默认等于 Xmx，会突破物理内存） |
| `-XX:+UseSerialGC` | 2 核 + 小堆下比 G1 更省（G1 要维护记忆集和并发标记线程） |
| `-Dfile.encoding=UTF-8` | Linux locale 可能是 POSIX/C，不加中文会乱码 |

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

做四件事：Maven 打包 → Vite 构建前端 → 打成一个 tar.gz → 上传并解包到 `/opt/ai-agent`。

上传体积按档位裁剪（只打包该档位会跑的服务）：

| 档位 | jar 数 | jar 体积 | + 前端 | 上传合计 |
|---|---|---|---|---|
| `lite` | 5 | 333 MB | 34 MB | **~367 MB** |
| `medium` / `full` | 7 | 466 MB | 34 MB | **~500 MB** |

jar 本身已是压缩包（含全部依赖的 fat jar），所以 tar.gz 再压也压不下去多少。

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
./deploy.sh restart           # 强制重启全部服务
./deploy.sh doctor            # 只做预检和审计队列治理，不重启
./deploy.sh admin <用户名>     # 提权管理员
```

### `start` / `restart` / `all` 的区别（重要）

| 命令 | 对**已在运行**的服务 | 什么时候用 |
|---|---|---|
| `start` | **jar 比进程新才重启**，否则跳过 | 传了新 jar 后最省事的做法 |
| `restart` | 无条件 stop + start | 只改了配置 / 要强制重来 |
| `all` | 走 `stop` + `start`，还会做配置生成、SQL 迁移 | 完整部署（`build-local.ps1 -Deploy` 用的就是它） |

> 历史坑：`start` 以前用 `systemctl enable --now`，对 active 的服务是**空操作**。
> 上传新 jar 后跑 `start` 会「以为部署了但实际跑旧代码」，日志里毫无异常。
> 现在 `start` 会比较 jar 的 mtime 和 `/proc/<pid>` 的 mtime（即进程启动时刻），
> 只有 jar 更新才重启。

**判断服务跑的是不是最新 jar：**

```bash
for s in gateway user-service agent-core image-engine storage-service; do
  jar=$(ls -1 --time-style=+%H:%M:%S -l /opt/ai-agent/apps/${s}-*.jar | awk '{print $6}')
  pid=$(systemctl show ai-agent@$s -p MainPID --value)
  started=$(stat -c %y /proc/$pid | cut -d' ' -f2 | cut -d. -f1)
  printf '%-16s jar=%s  进程启动=%s\n' "$s" "$jar" "$started"
done
```

`jar` 时间晚于 `进程启动` = 跑的是旧代码。

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
| 中文显示成 `é»˜è®¤` 这类乱码 | 数据写入时字符集不对（双重编码） | 见下方「中文乱码」一节 |
| 配了 API Key，但图片/视频报 `No available generation model` | 引擎进程的配置缓存未刷新 | 见下方「API Key 改了不生效」一节 |

### API Key 改了不生效（图片/视频能提交但立刻失败）

**症状**：管理后台里 provider 已启用且有 Key，对话能用，但提交图片/视频任务立刻
`FAILED`，`errorMsg` = `No available generation model`。

**原因**：`ModelConfigService` 的缓存在**每个 JVM 进程内各存一份**。管理后台保存
Key 时调的是 agent-core 里的 `saveConfig()`，只刷新了 agent-core 自己的缓存；
image-engine / video-engine 是独立进程，仍拿着启动时的旧配置，`healthCheck()`
因此返回 false，注册中心里"没有一个可用模型"。

**现状**：已加 60 秒 TTL 懒刷新（`model-config.cache-ttl-ms`），下一个请求进来时
会自动重载，**不再需要重启**。首次生效最多等 60 秒。

**如果是老版本（没有 TTL 的 jar）**：

```bash
systemctl restart ai-agent@image-engine
systemctl restart ai-agent@video-engine   # 若该档位启用了视频引擎
```

**验证缓存是否已加载到 Key**：

```bash
grep 'ModelConfig cache loaded' /opt/ai-agent/logs/image-engine.log | tail -3
# 期望看到 "cache loaded: N providers" 且 N ≥ 已启用的 provider 数
# 若为 0，说明 image-engine 启动时 model_config 表还是空的
```

### 中文乱码

两类完全不同的问题，别混：

**① 数据层面的双重编码**（页面上的中文变 `é»˜è®¤`）

用 `docker exec mysql mysql < file.sql` 导入含中文的脚本时，客户端
`character_set_client` 默认 `latin1`，会把 UTF-8 字节当 Latin-1 收下再编码一次。

预防：所有 `sql/*.sql` 开头已有 `SET NAMES utf8mb4`，导入命令也要带参数：

```bash
docker exec -i ai-mysql mysql -uroot -p"$PW" --default-character-set=utf8mb4 < sql/xxx.sql
```

`./deploy.sh db` 已经带了该参数，正常部署不会踩到。

已经坏了的，用这条 SQL 还原：

```sql
UPDATE 表名 SET 列名 = CONVERT(CAST(CONVERT(列名 USING latin1) AS BINARY) USING utf8mb4)
WHERE 列名 LIKE '%Ã%' OR 列名 LIKE '%é%';
```

判断依据：「默认」的正确 UTF-8 十六进制是 `E9BB98E8AEA4`，坏掉的是
`C3A9C2BBCB9CC3A8C2AEC2A4`（每个字节都被多编码了一次）。

**② JVM 层面的编码**

`JVM_BASE` 里已加 `-Dfile.encoding=UTF-8`。如果你的老部署没有这条，
日志或接口返回里的中文会是 `?`。重跑 `./deploy.sh config` 后 restart 即可。

### 经常被 OOM 杀掉 / SSH 卡

```bash
free -h
vmstat 1 5                    # 看 si/so 列，长期不为 0 说明真的不够用
systemctl status 'ai-agent@*' # 看哪个服务被 OOM kill 过
dmesg -T | grep -i 'killed process'   # 内核 OOM 记录
journalctl -u ai-agent@agent-core -n 50 --no-pager
```

2C2G 跑这套是贴着极限的（~1665 MB / 2048 MB）。已经做的缓解：

| 措施 | 位置 | 作用 |
|---|---|---|
| JVM 显式限堆 | `deploy.conf` 的 `JVM_OPTS_*` | 不让 JVM 按物理内存自动扩张 |
| `-XX:+ExitOnOutOfMemoryError` | `JVM_BASE` | 堆耗尽时快退，避免拖死整机 |
| `-XX:ActiveProcessorCount=2` | `JVM_BASE` | 防云主机误报核数导致线程池过大 |
| 每个服务 `MemoryMax` | `systemd/ai-agent@.service` | 失控时只杀那一个服务 |
| 4G swap + `swappiness=20` | `remote-install.sh` | 兜底，且不早换出 JVM 热堆 |
| MySQL 调优 | `docker-compose.infra.yml` | buffer pool 128m、关 perf_schema/binlog，省 ~150 MB |

**判断该升配的信号**：`vmstat 1 5` 的 si/so 长期不为 0，或者
`systemctl status` 里频繁出现 `status=9/KILL`。升到 4C4G 改 `PROFILE="medium"`
重跑 `./deploy.sh all` —— 多 2 个服务但余量反而更大。

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

## 9. 已知限制（lite / 2C2G 档）

**功能层面**

- **内容审核已关闭**。极简档没有 security-service，提示词不做敏感词过滤。
  云端模型厂商有自己的审核兜底，但那是第二道防线，不等于合规。
- **视频生成不可用**。video-engine 不启动，前端选视频会失败。
- **审计日志不再落库**。没有消费者，队列里的消息 1 小时后自动过期
  （`deploy.sh` 会自动给队列加 TTL 策略）。升到完整档会自动取消该策略。
- **提示词自动翻译默认关闭**。`PROMPT_ENHANCE_ENABLED=false`（省一次 LLM 调用
  和几秒等待）。配好 LLM provider 后可改成 `true` 重跑 `./deploy.sh config`。

**架构层面**

- **单点无冗余**。Redis 挂了整个网关会不可用（网关鉴权要查 Redis 黑名单），
  MySQL 挂了全平台不可用。这是单机 2C2G 的必然代价。
- **静态服务发现不会自动感知实例上下线**。改端口/加减服务后必须重跑
  `./deploy.sh config`。
- **Nacos 不可用**。`lite`/`medium` 档用 `SimpleDiscoveryClient` 静态表替代；
  只有 `full` 档才起 Nacos（它本身要 ~500 MB，2G 机器装不下）。

**2C2G 特有的性能约束**

| 约束 | 表现 | 缓解 |
|---|---|---|
| 内存余量仅 ~380 MB | 高峰期靠 swap，IO 变慢 | 4G swap + `swappiness=20` + 每服务 `MemoryMax` |
| 只有 2 核 | 并发对话/生成会排队 | `-XX:ActiveProcessorCount=2` 避免线程池虚开；RAG 文档解析串行 |
| 首次启动慢 | 5 个 JVM 逐个启动要 1~2 分钟 | 耐心等；`./deploy.sh status` 看进度 |
| Maven 不能在服务器编译 | 编译期 1.5G+ 会和运行中服务抢内存 | `BUILD_ON_SERVER=false`，本地构建后上传 |
| 长回答流式较慢 | 2 核上模型输出 token 速率受限 | SSE 已配 `proxy_buffering off`，不会"憋一大段再吐" |
| RAG 检索是应用层全量余弦 | 知识库 >2000 分块时明显变慢 | `rag.search.max-candidates` 调小，或控制单库文档量 |

**内存紧张时的取舍顺序**（按影响从小到大）

1. 关 `PROMPT_ENHANCE_ENABLED`（已默认关）—— 几乎无感
2. `agent-core` 堆 320m → 256m —— 对话窗口变短
3. 关 `image-engine` —— 省 180 MB，但图片生成没了
4. 升级到 4C4G 用 `medium` 档 —— 根本解法

#!/usr/bin/env bash
# =====================================================================
#  AI Agent Platform —— 服务器端部署脚本
#
#  在服务器上运行（由本地 build-local.ps1 上传后调用，也可手工执行）:
#
#  首次部署（按顺序跑一遍）:
#      ./deploy.sh install      系统环境：JDK17/Nginx/Docker、目录、swap、日志轮转、密钥
#      ./deploy.sh config       生成 config/application.yml 与 services.env
#      ./deploy.sh infra        启动 Docker 基础设施（MySQL/Redis/MQ/MinIO）
#      ./deploy.sh db           应用 sql/*.sql 迁移（幂等，可重复跑）
#      ./deploy.sh start        启动全部业务服务
#      ./deploy.sh admin <用户名>   把某个已注册用户提权为 SUPER_ADMIN（见 §8 说明）
#
#  日常/升级部署:
#      ./deploy.sh all          传输新产物后跑这一个：预检 + config + infra + 治理 + db + 重启
#
#  运维:
#      ./deploy.sh status       状态（容器 + 服务 + 各自内存 + swap 用量）
#      ./deploy.sh logs <svc>   跟踪某个服务的日志
#      ./deploy.sh stop|restart
#      ./deploy.sh doctor       只做预检与审计队列治理，不重启服务
#
#  设计要点:
#    * 只改 deploy.conf 一个文件就能在极简档/完整档之间切换，本脚本按其中的
#      开关决定拉起哪些容器、生成哪些静态服务发现条目。
#    * services.env 每次部署重新生成（派生配置），.env 只生成一次（密钥）。
#      两者分开是为了避免"重新部署把数据库密码重置了"这类事故。
# =====================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# deploy.conf 里定义了 REMOTE_ROOT，但脚本自身位置更可靠（支持换目录部署）
if [ ! -f "${SCRIPT_DIR}/deploy.conf" ]; then
  echo "错误: 找不到 ${SCRIPT_DIR}/deploy.conf" >&2
  exit 1
fi
# shellcheck source=deploy.conf
source "${SCRIPT_DIR}/deploy.conf"

ROOT="${REMOTE_ROOT}"

# ---- 输出helper ----
c_info()  { printf '\033[36m[deploy]\033[0m %s\n' "$*"; }
c_ok()    { printf '\033[32m[deploy]\033[0m %s\n' "$*"; }
c_warn()  { printf '\033[33m[deploy]\033[0m %s\n' "$*" >&2; }
c_die()   { printf '\033[31m[deploy]\033[0m %s\n' "$*" >&2; exit 1; }

need_root() {
  [ "$(id -u)" -eq 0 ] || c_die "需要 root 权限（systemd 与 docker 都要），请用 sudo 运行"
}

# 生成 N 位随机字母数字串。top-level 定义而不是定义在 ensure_secrets 里 ——
# bash 不支持 `local rand() {...}` 这种写法（函数声明不能带 local）。
rand_str() {
  LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c "$1"
}

# =====================================================================
#  服务清单
#  顺序即启动顺序: 先起被依赖的服务, gateway 放最后 —— gateway 起来了
#  就代表整条链路可用, 可以把它当作"部署完成"的信号。
# =====================================================================
enabled_services() {
  local out=()
  out+=("user-service")
  [ "${ENABLE_STORAGE_SERVICE}"  = "true" ] && out+=("storage-service")
  [ "${ENABLE_SECURITY_SERVICE}" = "true" ] && out+=("security-service")
  out+=("agent-core")
  [ "${ENABLE_IMAGE_ENGINE}"     = "true" ] && out+=("image-engine")
  [ "${ENABLE_VIDEO_ENGINE}"     = "true" ] && out+=("video-engine")
  out+=("gateway")
  printf '%s\n' "${out[@]}"
}

# 对外访问地址。域名未备案阶段 DOMAIN 为空, 退回 IP 访问。
#
# ⚠️ SERVER_HOST 为空时这里会产出 "http://" 这种没有主机的地址, 而它会被
#    写进 CORS 白名单 / STORAGE_ENGINE_BASE_URL。后果不是"访问不了页面"这种
#    一眼可见的故障, 而是: WebSocket 握手被拒（任务进度条不动）、云端厂商
#    拉不到参考图（图生图永久失败）——都很难反查到根因。所以这里直接拒绝。
public_base() {
  local scheme="http" host="${SERVER_HOST}"
  local suffix=""

  if [ -z "${DOMAIN}" ] && [ -z "${SERVER_HOST}" ]; then
    c_die "deploy.conf 里的 SERVER_HOST 还没填。填上服务器公网 IP（或备案后填 DOMAIN）再重跑。"
  fi
  if [ -n "${DOMAIN}" ]; then
    host="${DOMAIN}"
    [ "${HTTPS_ENABLED:-false}" = "true" ] && scheme="https"
  fi
  # 标准端口不写进 URL
  if [ "${PUBLIC_PORT}" != "80" ] && [ "${PUBLIC_PORT}" != "443" ]; then
    suffix=":${PUBLIC_PORT}"
  fi
  echo "${scheme}://${host}${suffix}"
}

# =====================================================================
#  1. 密钥文件 .env —— 只在不存在时生成, 之后永不覆盖
#
#  ⚠️ 为什么不能重新生成:
#     MYSQL_ROOT_PASSWORD 变了 → 连不上已有数据卷, 全部数据"消失"（其实还在,
#     只是登不进去）; JWT_SECRET 变了 → 所有用户被踢下线; MODEL_CONFIG_CRYPTO_KEY
#     变了 → model_config 表里已加密的 API key 全部解不开。
#     这三件事都是不可逆的, 所以这个文件必须当成有状态资产对待, 要备份。
# =====================================================================
ensure_secrets() {
  local f="${ROOT}/.env"

  if [ -f "${f}" ]; then
    c_info "复用已有密钥文件 ${f}（不覆盖）"
    return
  fi

  c_info "首次部署: 生成随机密钥 → ${f}"

  umask 077
  cat > "${f}" <<EOF
# =====================================================================
#  AI Agent Platform 密钥文件 —— 由 deploy.sh 首次部署时生成
#
#  ⚠️ 不要删、不要重新生成、不要提交到 git。
#     重新生成会导致: 数据库连不上、用户全部掉线、已存的 API key 解不开。
#     备份建议: cp ${f} ~/ai-agent-env.backup
#
#  同时被两处读取:
#    * docker compose（基础设施容器凭据）
#    * systemd EnvironmentFile（Java 服务凭据）
# =====================================================================

# ---- 基础设施 ----
MYSQL_ROOT_PASSWORD=$(rand_str 32)
RABBITMQ_USER=agent-mq
RABBITMQ_PASSWORD=$(rand_str 32)
MINIO_ROOT_USER=agent-minio
MINIO_ROOT_PASSWORD=$(rand_str 32)
REDIS_PASSWORD=$(rand_str 32)

# ---- 应用 ----
# 签发 JWT 的密钥, 必须 >= 32 字符（JwtUtils 会拒绝弱密钥并拒绝启动）
JWT_SECRET=$(rand_str 48)
# 服务间内部接口令牌。注意: 未配置时 /api/v1/internal/** 是**失败关闭**（一律 401）,
# 不是放行 —— 所以这个值必须存在, 否则服务间调用全部失败。
# 它同时是公开取图 URL 的 HMAC 签名密钥（storage-service PublicFileController）。
INTERNAL_AUTH_TOKEN=$(rand_str 32)
# model_config 表里 API key 的 AES 密钥。
# ⚠️ agent-core / image-engine / video-engine 三个服务必须读到同一个值,
#    否则引擎会把密文当明文 API key 用, 表现为"生成一直失败但日志不报错"。
MODEL_CONFIG_CRYPTO_KEY=$(rand_str 32)
EOF

  chmod 600 "${f}"
  c_ok "密钥已生成（权限 600）"
}

# 读取密钥到当前 shell（后续生成 services.env 要用）
load_secrets() {
  local f="${ROOT}/.env"
  [ -f "${f}" ] || c_die "缺少 ${f}，请先运行: ./deploy.sh install"
  set -a
  # shellcheck disable=SC1090
  source "${f}"
  set +a
  [ -n "${MYSQL_ROOT_PASSWORD:-}" ]        || c_die "${f} 里 MYSQL_ROOT_PASSWORD 为空"
  [ -n "${INTERNAL_AUTH_TOKEN:-}" ]        || c_die "${f} 里 INTERNAL_AUTH_TOKEN 为空"
  [ -n "${MODEL_CONFIG_CRYPTO_KEY:-}" ]    || c_die "${f} 里 MODEL_CONFIG_CRYPTO_KEY 为空"
}

# =====================================================================
#  2. 生成 services.env —— systemd 读的派生配置, 每次部署重新生成
#
#  与 .env 分开的原因: 这里的内容全部由 deploy.conf 推导, 改了档位/端口
#  重跑部署就生效; 而 .env 是有状态密钥, 不能被覆盖。混在一起的话,
#  要么切档位不生效, 要么有覆盖密钥的风险。
# =====================================================================
gen_services_env() {
  need_root
  load_secrets

  local f="${ROOT}/services.env"
  local base; base="$(public_base)"

  c_info "生成 ${f}"

  umask 077
  cat > "${f}" <<EOF
# 由 deploy.sh 生成于 $(date '+%F %T') —— 不要手工改, 下次部署会覆盖。
# 要调整请改 deploy.conf 后重跑 ./deploy.sh config

# ---------------------------------------------------------------------
# 中间件地址（基础设施跑在同机 Docker 里, 端口映射到 127.0.0.1）
#
# ⚠️ MYSQL_PORT 必须显式设置: 各服务 application.yml 里的默认值是 3307
#    （开发环境为了避让本机已有 MySQL 才用 3307）, 生产是标准 3306。
#    不设的话服务会去连 3307 然后连接被拒。
# ---------------------------------------------------------------------
MYSQL_HOST=127.0.0.1
MYSQL_PORT=${PORT_MYSQL}
MYSQL_USER=${DB_USER}
MYSQL_PASS=${MYSQL_ROOT_PASSWORD}

REDIS_HOST=127.0.0.1
REDIS_PORT=${PORT_REDIS}
REDIS_PASSWORD=${REDIS_PASSWORD}

RABBITMQ_HOST=127.0.0.1
RABBITMQ_PORT=${PORT_RABBITMQ}
RABBITMQ_USER=${RABBITMQ_USER}
RABBITMQ_PASS=${RABBITMQ_PASSWORD}

MINIO_ENDPOINT=http://127.0.0.1:${PORT_MINIO}
MINIO_ACCESS_KEY=${MINIO_ROOT_USER}
MINIO_SECRET_KEY=${MINIO_ROOT_PASSWORD}
MINIO_BUCKET=agent-platform

# ---------------------------------------------------------------------
# 密钥透传（值来自 .env，这里只是换个变量名 —— Java 侧读的是下面这些名字）
# ---------------------------------------------------------------------
JWT_SECRET=${JWT_SECRET}
INTERNAL_AUTH_TOKEN=${INTERNAL_AUTH_TOKEN}
MODEL_CONFIG_CRYPTO_KEY=${MODEL_CONFIG_CRYPTO_KEY}

# ---------------------------------------------------------------------
# Nacos 开关
#
# 这里用**环境变量**关闭而不仅仅是配置文件, 是有意为之:
# 各服务 jar 内的 application.yml 有 \`spring.config.import: optional:nacos:xxx.yaml\`,
# 该 import 在 config data 处理阶段就要解析。环境变量优先级最高且在该阶段
# 之前就可用, 能确保 import 被跳过; 只靠 additional-location 里的 yml 则
# 存在解析顺序不确定的风险。
# ---------------------------------------------------------------------
SPRING_CLOUD_NACOS_CONFIG_ENABLED=${ENABLE_NACOS}
SPRING_CLOUD_NACOS_DISCOVERY_ENABLED=${ENABLE_NACOS}

# ---------------------------------------------------------------------
# 对外地址
# ---------------------------------------------------------------------
# 浏览器跨域白名单。经 nginx 同源反代时理论上用不到, 但直连 gateway 的场景需要。
CORS_ALLOWED_ORIGINS=${base}
# ★ 云端模型厂商回拉参考图用的地址, **必须公网可达**。
#   生成"图生图 / 图生视频"时, 平台会把参考图 URL 发给云端厂商, 让它们来下载。
#   如果这里是 localhost 或内网地址, 厂商拉不到图, 这类任务会一直失败。
#   注意: 部分厂商要求 https, 未备案阶段只有 http+IP 时可能被拒。
STORAGE_ENGINE_BASE_URL=${base}

# OpenAPI 接口是否需要 API Key。公网部署保持 true。
REQUIRE_API_KEY=true

# ---------------------------------------------------------------------
# 内容审核降级开关 —— 由 ENABLE_SECURITY_SERVICE 推导
#
# security-service 未启用时: 审核服务不可用, 而 TaskService 默认失败关闭
# （见 agent-core TaskService:82）, 会导致**提交生成任务直接报错**。
# 所以关掉 security-service 就必须同时打开降级, 否则整个生成功能不可用。
#
# ⚠️ 打开降级 = 提示词不做敏感词过滤。云端厂商有自己的内容审核兜底,
#    但那是第二道防线, 不等于合规。
# ---------------------------------------------------------------------
PROMPT_AUDIT_DEGRADE_ALLOWED=$([ "${ENABLE_SECURITY_SERVICE}" = "true" ] && echo false || echo true)

# 配额降级开关 —— 保持失败关闭（false）。
#
# 与审核开关的区别: 配额服务在 user-service 里, 三档部署都启用, 不存在"档位关了
# 它"的情况, 所以不能按 ENABLE_* 推导。这里显式写 false 并说明原因, 避免运维
# 看到上面那条推导后困惑"为什么这个没有开关"。
#
# ⚠️ 保持 false 是有意的: 降级放行意味着**不扣配额也不校验余额**, 等于免费无限
#    生成。只有当 user-service 不可用（登录也会一起挂）时才会触发, 那时放行
#    生成反而更危险。真要临时放开, 在服务器上手工改 services.env 后 restart。
QUOTA_DEGRADE_ALLOWED=false

# ---------------------------------------------------------------------
# 联网搜索已整体下线（含自建 SearXNG 容器、search 模块与 model_config 种子行）,
# 这里不再需要任何开关。重启该功能要同时恢复代码、种子数据与部署编排。
# ---------------------------------------------------------------------

# video-engine 下载目录。默认值写死的是 Windows 路径（C:\\Users\\...\\Ai视频）,
# Linux 上必须覆盖, 否则该功能会往一个不存在的目录写。
VIDEO_DOWNLOAD_DIR=${ROOT}/downloads
VIDEO_DOWNLOAD_ENABLED=false

# ---------------------------------------------------------------------
# 提示词自动翻译（中文 → 英文, 生成模型对英文理解更佳）
#
# 默认 true, 但 2C2G 上有两个顾虑:
#   1. 每次提交任务都多一次 LLM 调用, 占 agent-core 的堆和线程
#   2. 若服务器上还没配任何 LLM provider, 这次调用必然失败（虽有降级不报错,
#      但每次白等几秒超时）
# 所以默认关掉; 配好 LLM provider 后想开启, 改成 true 重跑 ./deploy.sh config。
# ---------------------------------------------------------------------
PROMPT_ENHANCE_ENABLED=false

# ---------------------------------------------------------------------
# JVM 参数（按服务区分, run-service.sh 按服务名取对应的那个）
# 2C2G 上必须显式限堆: 不设的话 JVM 按物理内存自动扩张, 多服务叠加必然 OOM。
# ---------------------------------------------------------------------
JVM_OPTS_gateway=${JVM_OPTS_gateway}
JVM_OPTS_user_service=${JVM_OPTS_user_service}
JVM_OPTS_agent_core=${JVM_OPTS_agent_core}
JVM_OPTS_image_engine=${JVM_OPTS_image_engine}
JVM_OPTS_storage_service=${JVM_OPTS_storage_service}
JVM_OPTS_security_service=${JVM_OPTS_security_service}
JVM_OPTS_video_engine=${JVM_OPTS_video_engine}
EOF

  chmod 600 "${f}"
  c_ok "services.env 已生成"
}

# =====================================================================
#  3. 生成 Spring 配置覆盖 config/application.yml
#
#  通过 --spring.config.additional-location 加载, 优先级高于 jar 内配置。
#  内容只放"全局对所有服务都成立"的覆盖, 服务特有的走 services.env 环境变量。
# =====================================================================
gen_spring_config() {
  need_root
  mkdir -p "${ROOT}/config"

  local f="${ROOT}/config/application.yml"
  c_info "生成 ${f}"

  cat > "${f}" <<'HEADER'
# =====================================================================
#  AI Agent Platform —— 运行时配置覆盖
#
#  由 deploy.sh 生成, 不要手工改（下次部署会覆盖）。
#  通过 --spring.config.additional-location 加载, 优先级高于 jar 内
#  各服务的 application.yml。
# =====================================================================

spring:
  cloud:
    nacos:
      # 与 services.env 里的 SPRING_CLOUD_NACOS_* 环境变量双重保险。
      # 环境变量负责让 spring.config.import 跳过, 这里负责让自动配置不注册。
      discovery:
        enabled: ${NACOS_ENABLED}
      config:
        enabled: ${NACOS_ENABLED}
HEADER

  # 静态服务发现: 仅 Nacos 关闭时生成
  if [ "${ENABLE_NACOS}" != "true" ]; then
    cat >> "${f}" <<'BLOCK'

    # -----------------------------------------------------------------
    # 静态服务发现（替代 Nacos）
    #
    # 原理: spring-cloud-commons 自带 SimpleDiscoveryClient, 从下面的静态表
    # 按服务名返回地址。LoadBalancer / Feign / Gateway 的 lb:// 全部照常工作,
    # 所以**不需要改任何 Java 代码或路由配置**。
    #
    # ⚠️ 键名大小写敏感。SimpleDiscoveryClient.getInstances() 内部是
    #    Map.get(serviceId) 精确匹配, 必须与各服务 spring.application.name
    #    完全一致（全小写）。写错的表现是路由 503 / Feign 静默走 fallback,
    #    而且日志不会提示"是键名不匹配"。
    #
    # ⚠️ 只列真正在跑的服务。列了没启动的服务, LoadBalancer 会选中它然后
    #    连接被拒, 比"找不到实例"多等一个连接超时。
    # -----------------------------------------------------------------
    discovery:
      client:
        simple:
          instances:
BLOCK
    emit_instance() {
      printf '            %s:\n              - uri: http://127.0.0.1:%s\n' "$1" "$2" >> "${f}"
    }
    emit_instance "user-service"    "${PORT_USER_SERVICE}"
    emit_instance "agent-core"      "${PORT_AGENT_CORE}"
    [ "${ENABLE_STORAGE_SERVICE}"  = "true" ] && emit_instance "storage-service"  "${PORT_STORAGE_SERVICE}"
    [ "${ENABLE_SECURITY_SERVICE}" = "true" ] && emit_instance "security-service" "${PORT_SECURITY_SERVICE}"
    [ "${ENABLE_IMAGE_ENGINE}"     = "true" ] && emit_instance "image-engine"     "${PORT_IMAGE_ENGINE}"
    [ "${ENABLE_VIDEO_ENGINE}"     = "true" ] && emit_instance "video-engine"     "${PORT_VIDEO_ENGINE}"
  fi

  # 日志级别: 生产环境把 DEBUG 关掉。
  # agent-core 的 application.yml 里写死了 com.platform.agent=DEBUG, 那在开发时
  # 有用（能看到完整 prompt 与适配器请求）, 但线上会: 刷爆磁盘、拖慢 IO、
  # 而且把用户 prompt 明文写进日志（隐私问题）。
  cat >> "${f}" <<'FOOTER'

# ---------------------------------------------------------------------
# 日志级别
# agent-core 打包默认 com.platform.agent=DEBUG, 线上必须关掉:
# 磁盘占用、IO 开销、以及把用户 prompt 明文写进日志的隐私问题。
# 排障时可临时改为 DEBUG, 改完记得改回来。
# ---------------------------------------------------------------------
logging:
  level:
    root: INFO
    com.platform: INFO
FOOTER

  # 把占位符替换成真实值（不用 envsubst: 该命令来自 gettext-base, 不保证已安装）
  sed -i "s/\${NACOS_ENABLED}/${ENABLE_NACOS}/g" "${f}"

  c_ok "Spring 覆盖配置已生成"
}

# =====================================================================
#  4. 数据库迁移
# =====================================================================
apply_sql() {
  need_root
  load_secrets

  local cid
  cid="$(docker compose -f "${ROOT}/docker-compose.infra.yml" ps -q mysql 2>/dev/null || true)"
  [ -n "${cid}" ] || c_die "MySQL 容器未运行, 请先执行: ./deploy.sh infra"

  c_info "等待 MySQL 就绪（端口通了不代表 mysqld 能查询）"
  local ready=false
  for _ in $(seq 1 30); do
    if docker exec -e MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" "${cid}" \
         mysqladmin ping -uroot --silent >/dev/null 2>&1; then
      ready=true; break
    fi
    sleep 3
  done
  [ "${ready}" = "true" ] || c_die "MySQL 90 秒内未就绪, 请查看: docker logs ai-mysql"

  # 顺序不能变: init 建库 → model_config 种子 → rag → chat → prompt → phase1 → memory_space
  # 全部脚本幂等（IF NOT EXISTS / INSERT IGNORE / information_schema 条件 ALTER）,
  # 重复执行无副作用, 所以每次部署都跑一遍是安全的。
  #
  # ⚠️ 新增 sql/*.sql 时必须同步加到这里 —— 否则新表不会建, 对应功能运行时报
  #    "Table doesn't exist"。memory_space.sql（记忆空间）就是补加的一例。
  local files=(init.sql model_config.sql rag.sql chat.sql prompt_template.sql phase1_project_asset.sql memory_space.sql)
  # shellcheck disable=SC2034
  local f
  for f in "${files[@]}"; do
    local p="${ROOT}/sql/${f}"
    if [ ! -f "${p}" ]; then
      c_warn "跳过（文件不存在）: ${f}"
      continue
    fi
    local err
    err="$(docker exec -i -e MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" "${cid}" \
             mysql -uroot --default-character-set=utf8mb4 < "${p}" 2>&1 >/dev/null || true)"
    if printf '%s' "${err}" | grep -q 'ERROR '; then
      if [ "${f}" = "init.sql" ]; then
        c_die "基础建库脚本 init.sql 失败, 中止: ${err}"
      fi
      c_warn "${f}: ${err}"
    else
      c_ok "已应用 ${f}"
    fi
  done

  # ---- 生产环境修正 ----
  #
  # 种子数据里 local-image / local-video 是 enabled=1 且指向 http://localhost:7860
  # （开发机上那个本地推理服务）。服务器上没有这东西, 用户在前端选中"本地 SD"
  # 会得到 ECONNREFUSED, 而且报错信息不会说明"是这台机器根本没有这个服务"。
  #
  # 只关 base_url 仍指向 localhost 的那些 —— 如果哪天把它们改指向一台真的
  # GPU 机器（在管理后台改 base_url）, 这里就不会再误关。
  local n
  n="$(docker exec -e MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" "${cid}" \
        mysql -uroot -N -B -e \
        "UPDATE agent_platform.model_config SET enabled=0
          WHERE provider IN ('local-image','local-video')
            AND (base_url LIKE '%localhost%' OR base_url LIKE '%127.0.0.1%')
            AND enabled=1;
         SELECT ROW_COUNT();" 2>/dev/null | tr -d '\r')"
  if [ "${n:-0}" -gt 0 ] 2>/dev/null; then
    c_ok "已关闭指向本机地址的本地推理条目（${n} 个, 服务器上没有该服务）"
  fi
}

# =====================================================================
#  5. 基础设施容器
# =====================================================================
infra_up() {
  need_root
  load_secrets

  local args=(compose -f "${ROOT}/docker-compose.infra.yml")
  [ "${ENABLE_NACOS}" = "true" ] && args+=(--profile full)

  c_info "启动基础设施容器"
  docker "${args[@]}" up -d

  c_info "等待健康检查通过"
  local svc
  for svc in mysql redis rabbitmq minio; do
    local cid
    cid="$(docker "${args[@]}" ps -q "${svc}" 2>/dev/null || true)"
    [ -n "${cid}" ] || { c_warn "${svc}: 容器未创建"; continue; }

    local ok=false
    for _ in $(seq 1 30); do
      local st
      st="$(docker inspect -f '{{.State.Health.Status}}' "${cid}" 2>/dev/null || echo unknown)"
      [ "${st}" = "healthy" ] && { ok=true; break; }
      [ "${st}" = "unhealthy" ] && break
      sleep 3
    done
    if [ "${ok}" = "true" ]; then c_ok "${svc} 就绪"
    else c_warn "${svc} 健康检查未通过, 查看: docker logs $(docker "${args[@]}" ps -q "${svc}" 2>/dev/null | head -c 12)"; fi
  done
}

# =====================================================================
#  6. 业务服务（systemd 实例）
# =====================================================================
svc_start() {
  need_root
  mkdir -p "${ROOT}/logs" "${ROOT}/downloads"

  local svc
  while read -r svc; do
    c_info "启动 ${svc}"
    # enable --now: 顺带建立开机自启。服务器重启后这套要能自己回来,
    # 单独 systemctl start 的话重启就全没了。
    systemctl enable --now "ai-agent@${svc}" >/dev/null 2>&1 || \
      systemctl start "ai-agent@${svc}"
    # 逐个起来时给 JVM 一点时间, 避免 5 个 JVM 同时启动抢 CPU 和内存
    # （2 核机器上同时起 5 个 Spring Boot 会明显互相拖慢）
    sleep 2
  done < <(enabled_services)

  c_info "等待 Gateway(8080) 就绪"
  local ok=false
  for _ in $(seq 1 60); do
    if (echo > /dev/tcp/127.0.0.1/8080) >/dev/null 2>&1; then ok=true; break; fi
    sleep 2
  done
  if [ "${ok}" = "true" ]; then
    c_ok "部署完成 → $(public_base)"
  else
    c_warn "Gateway 120 秒内未就绪, 排查: ./deploy.sh logs gateway"
  fi
}

svc_stop() {
  need_root
  local svc
  while read -r svc; do
    c_info "停止 ${svc}"
    # 只 stop 不 disable: 关掉的原因通常是"要重启", 下次开机还是要自启的。
    # 真的要永久停用某个服务, 请改 deploy.conf 的档位开关。
    systemctl stop "ai-agent@${svc}" 2>/dev/null || true
  done < <(enabled_services)
  c_ok "已停止"
}

svc_status() {
  echo ""
  echo "===== 基础设施容器 ====="
  local args=(compose -f "${ROOT}/docker-compose.infra.yml")
  [ "${ENABLE_NACOS}" = "true" ] && args+=(--profile full)
  docker "${args[@]}" ps --format "table {{.Name}}\t{{.Status}}\t{{.Ports}}" 2>/dev/null || \
    echo "  (docker compose 不可用)"

  echo ""
  echo "===== 业务服务 ====="
  printf "  %-18s %-10s %s\n" "服务" "状态" "内存"
  local svc
  while read -r svc; do
    local active mem
    active="$(systemctl is-active "ai-agent@${svc}" 2>/dev/null || echo unknown)"
    # 取该服务 systemd cgroup 内的总内存
    mem="$(systemctl show "ai-agent@${svc}" -p MemoryCurrent --value 2>/dev/null || echo 0)"
    if [ "${mem}" -gt 0 ] 2>/dev/null; then
      mem="$((mem / 1024 / 1024)) MB"
    else
      mem="-"
    fi
    printf "  %-18s %-10s %s\n" "${svc}" "${active}" "${mem}"
  done < <(enabled_services)

  echo ""
  echo "===== 内存总览 ====="
  free -h | head -2
  echo ""
  echo "  Swap 使用情况（2C2G 上 swap 是必要缓冲, 持续大量使用说明内存不足）:"
  free -h | tail -1 | awk '{print "    used="$3"  free="$4}'
  echo ""
  echo "  外部访问: $(public_base)"
}

svc_logs() {
  local svc="${1:-}"
  [ -n "${svc}" ] || c_die "用法: ./deploy.sh logs <服务名>，可选: $(enabled_services | tr '\n' ' ')"
  tail -f "${ROOT}/logs/${svc}.log"
}

# =====================================================================
#  7. 审计队列治理（仅 security-service 关闭时需要）
#
#  背景: AuditLogAspect（网关每个请求记一条）和 AuditLogAspect（agent-core）
#  都往 platform.audit.exchange 发消息, common-mq 在每个服务启动时都会声明
#  audit.log.queue 并绑定它。唯一的消费者在 security-service 里 ——
#  极简档不启动它, 于是这个队列**只进不出**。
#
#  单条消息很小, 但 2C2G 的 RabbitMQ 内存水位设的是 0.3（约 600MB）,
#  长期积累会先触发内存告警, 然后内存换页到磁盘, 最后 publish 被阻塞 ——
#  而那会连带影响任务队列。
#
#  解法用 rabbitmq **policy** 而不是改队列参数: 队列已存在时, 用不同的参数
#  重新声明会触发 PRECONDITION_FAILED 并使 channel 关闭; 而 policy 是
#  RabbitMQ 侧的后置规则, 不需要重新声明, 升级到完整档时删掉 policy 即可。
# =====================================================================
audit_queue_guard() {
  need_root
  load_secrets

  local args=(compose -f "${ROOT}/docker-compose.infra.yml")
  [ "${ENABLE_NACOS}" = "true" ] && args+=(--profile full)

  local cid
  cid="$(docker "${args[@]}" ps -q rabbitmq 2>/dev/null || true)"
  if [ -z "${cid}" ]; then
    c_warn "RabbitMQ 未运行, 跳过审计队列治理（执行 ./deploy.sh infra 后重跑本步骤）"
    return 0
  fi

  local policy_name="audit-noconsumer"
  if [ "${ENABLE_SECURITY_SERVICE}" = "true" ]; then
    # 完整档：security-service 会消费, 保留审计数据
    if docker exec "${cid}" rabbitmqctl list_policies 2>/dev/null | grep -q "${policy_name}"; then
      docker exec "${cid}" rabbitmqctl clear_policy "${policy_name}" >/dev/null 2>&1 || true
      c_ok "已清除审计队列丢弃策略（security-service 已启用, 恢复保留审计日志）"
    else
      c_info "security-service 已启用, 审计队列有消费者, 无需治理"
    fi
    return 0
  fi

  # 极简档：1 小时后过期 + 最多留 10000 条, 两条保险
  docker exec "${cid}" rabbitmqctl set_policy "${policy_name}" \
    '^audit\.log\.queue$' \
    '{"message-ttl":3600000,"max-length":10000,"overflow":"reject-publish-dlx"}' \
    --apply-to queues >/dev/null
  c_ok "已设置审计队列过期策略（security-service 未启用, 消息 1h 过期 / 上限 1 万条）"
}

# =====================================================================
#  8. 首个管理员
#
#  ⚠️ 这是全新部署的**必经一步**, 没有它平台会处于"能登录但完全不能用"的状态:
#     * 注册接口只发 TENANT_ADMIN（user-service AuthService）
#     * 而模型配置（API Key / BaseUrl）的写接口要求 SUPER_ADMIN（AdminController）
#     * 且 model_config 里所有云端 provider 种子都是 enabled=0 且无 key
#     于是没人能配 API Key → 对话、图片生成、RAG embedding 全部不可用,
#     而且界面上没有任何自救路径。
#
#  用法: ./deploy.sh admin <用户名>
# =====================================================================
promote_admin() {
  need_root
  load_secrets

  local username="${1:-}"
  [ -n "${username}" ] || c_die "用法: ./deploy.sh admin <已注册的用户名>"

  local cid
  cid="$(docker compose -f "${ROOT}/docker-compose.infra.yml" ps -q mysql 2>/dev/null || true)"
  [ -n "${cid}" ] || c_die "MySQL 容器未运行"

  local uid
  uid="$(docker exec -e MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" "${cid}" \
          mysql -uroot -N -B -e \
          "SELECT id FROM user_service.user_account WHERE username='${username}' LIMIT 1;" 2>/dev/null | tr -d '\r')"

  if [ -z "${uid}" ]; then
    c_die "找不到用户 '${username}'。请先在浏览器里注册, 再执行本命令。（注意：注册发生在部署之后）"
  fi

  docker exec -e MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" "${cid}" mysql -uroot -e \
    "UPDATE user_service.user_tenant SET role_code='SUPER_ADMIN' WHERE user_id='${uid}';" 2>/dev/null

  local n
  n="$(docker exec -e MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" "${cid}" \
        mysql -uroot -N -B -e \
        "SELECT COUNT(*) FROM user_service.user_tenant WHERE user_id='${uid}' AND role_code='SUPER_ADMIN';" 2>/dev/null | tr -d '\r')"
  [ "${n:-0}" -ge 1 ] || c_die "提权失败, 请手工执行 UPDATE 语句"

  c_ok "用户 '${username}' (${uid}) 已提权为 SUPER_ADMIN"
  c_warn "需要重新登录才生效 —— 角色写在 JWT 里, 旧 token 仍然是老角色。"
}

# =====================================================================
#  9. 前置产物检查
#
#  这些文件不齐时，各子命令会以各种晦涩的方式失败（比如 apply_sql 找不到
#  sql 目录只会打印"跳过"）。集中在这里一次说清楚。
# =====================================================================
preflight() {
  local missing=()
  [ -f "${ROOT}/docker-compose.infra.yml" ] || missing+=("docker-compose.infra.yml")
  [ -d "${ROOT}/sql" ]                      || missing+=("sql/")
  [ -f "${ROOT}/bin/run-service.sh" ]       || missing+=("bin/run-service.sh")
  [ -f "${ROOT}/systemd/ai-agent@.service" ] || missing+=("systemd/ai-agent@.service")

  if [ "${#missing[@]}" -gt 0 ]; then
    c_warn "缺少产物: ${missing[*]}"
    c_warn "这些是本地构建脚本上传的文件, 当前看起来只上传了 deploy 目录的一部分。"
  fi

  local ok=0 total=0 svc
  while read -r svc; do
    total=$((total + 1))
    if [ -n "$(find "${ROOT}/apps" -maxdepth 1 -name "${svc}-*.jar" -print -quit 2>/dev/null || true)" ]; then
      ok=$((ok + 1))
    else
      c_warn "缺少 ${svc} 的 jar"
    fi
  done < <(enabled_services)
  c_info "jar 就位: ${ok}/${total}"
}

# =====================================================================
#  入口
# =====================================================================
case "${1:-}" in
  install)
    need_root
    [ -f "${SCRIPT_DIR}/remote-install.sh" ] || c_die "缺少 ${SCRIPT_DIR}/remote-install.sh"
    bash "${SCRIPT_DIR}/remote-install.sh"
    # 系统装好后立刻生成密钥：后续所有子命令都要 load_secrets, 没有 .env 会直接退出
    ensure_secrets
    ;;
  config)
    gen_services_env
    gen_spring_config
    ;;
  infra)
    infra_up
    ;;
  db)
    apply_sql
    ;;
  start)
    gen_services_env
    gen_spring_config
    svc_start
    ;;
  stop)    svc_stop ;;
  restart) svc_stop; svc_start ;;
  status)  svc_status ;;
  logs)    shift; svc_logs "${1:-}" ;;
  admin)   shift; promote_admin "${1:-}" ;;
  doctor)  preflight; audit_queue_guard ;;
  all)
    preflight
    gen_services_env
    gen_spring_config
    infra_up
    audit_queue_guard
    apply_sql
    svc_stop
    svc_start
    ;;
  *)
    sed -n '2,32p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac

#!/usr/bin/env bash
# =====================================================================
#  AI Agent Platform —— 服务器环境初始化
#
#  由 deploy.sh 调用（./deploy.sh install），也可单独执行。
#  只做「操作系统层面」的事：装运行环境、建目录、配 swap、装 systemd/nginx
#  配置。**不碰应用**（jar、前端产物、数据库由 deploy.sh 的其他子命令负责）。
#
#  设计原则:
#    * 幂等 —— 重复执行不报错、不改变已完成的状态（重复部署是常态）
#    * 每步先探测再动手 —— 已装的东西不重装，避免 apt 把线上环境升坏
#    * 面向 2C2G 调优 —— swap、内存上限、日志轮转都是这台机器上的必需项,
#      不是"最佳实践"层面的可选项
#
#  未在真实服务器上验证过：所有步骤都做了存在性探测，但首次部署请逐段
#  观察输出（脚本会在每步打印它做了什么、跳过了什么）。
# =====================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=deploy.conf
source "${SCRIPT_DIR}/deploy.conf"

ROOT="${REMOTE_ROOT}"
SWAP_SIZE_MB="${SWAP_SIZE_MB:-4096}"

c_info() { printf '\033[36m[install]\033[0m %s\n' "$*"; }
c_ok()   { printf '\033[32m[install]\033[0m %s\n' "$*"; }
c_warn() { printf '\033[33m[install]\033[0m %s\n' "$*" >&2; }
c_die()  { printf '\033[31m[install]\033[0m %s\n' "$*" >&2; exit 1; }
c_skip() { printf '\033[90m[install]\033[0m 跳过: %s\n' "$*"; }

# =====================================================================
#  0. 前置检查
# =====================================================================
[ "$(id -u)" -eq 0 ] || c_die "需要 root，请用 sudo 运行"

[ -r /etc/os-release ] || c_die "读不到 /etc/os-release，无法识别发行版"
# shellcheck disable=SC1091
. /etc/os-release
case "${ID:-}" in
  ubuntu|debian) : ;;
  *) c_die "本脚本只支持 Ubuntu/Debian，当前是 ${ID:-未知}。其他发行版请手工装 JDK17 + Docker + Nginx" ;;
esac
c_ok "发行版: ${PRETTY_NAME:-$ID}"

ARCH="$(dpkg --print-architecture)"
c_info "架构: ${ARCH}（x86_64 和 arm64 都支持）"

# 内存检查：低于 1.9G 直接警告，因为 lite 档的估算已经贴边
MEM_MB="$(awk '/MemTotal/ {printf "%d", $2/1024}' /proc/meminfo)"
c_info "物理内存: ${MEM_MB} MB"
if [ "${MEM_MB}" -lt 1900 ]; then
  c_warn "内存不足 1.9G，lite 档预估常驻 ~1.8G，很可能会触发 OOM。" \
         "建议升配或干脆只跑 infrastructure + 少数服务。"
fi

# =====================================================================
#  1. 系统包
#
#  apt 源用系统自带配置，**不替换成国内镜像** —— 云厂商的官方镜像通常已经
#  是内网源，手工替换反而可能变慢或带进不可信的源。
# =====================================================================
c_info "安装基础工具（apt update 可能需要一两分钟）"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq

BASE_PKGS=(ca-certificates curl gnupg lsb-release unzip jq iproute2)
c_info "安装: ${BASE_PKGS[*]}"
apt-get install -y -qq "${BASE_PKGS[@]}" >/dev/null

# =====================================================================
#  2. JDK 17
#
#  只要 JRE —— 构建在本地做（BUILD_ON_SERVER=false），服务器不需要编译器。
#  jre-headless 比 jdk 少 ~150MB 磁盘，也不含无用的 X11 依赖。
# =====================================================================
if command -v java >/dev/null 2>&1 && java -version 2>&1 | grep -qE '"(17|21)'; then
  c_skip "已有 JDK 17+: $(java -version 2>&1 | head -1)"
else
  c_info "安装 openjdk-17-jre-headless"
  apt-get install -y -qq openjdk-17-jre-headless >/dev/null
  # 有些镜像里 17 叫 openjdk-17-jre（带 X11 依赖），装不上 headless 时兜底
  command -v java >/dev/null 2>&1 || apt-get install -y -qq openjdk-17-jre >/dev/null
  command -v java >/dev/null 2>&1 || c_die "JDK 安装失败，请手工装 openjdk-17-jre-headless 后重跑"
  c_ok "$(java -version 2>&1 | head -1)"
fi

# =====================================================================
#  3. Docker + compose v2
#
#  优先用发行版自带的 docker.io + docker-compose-v2（走内网 apt 源，快且稳），
#  拿不到再退回 Docker 官方仓库。
#
#  ⚠️ 必须是 compose **v2**（`docker compose` 子命令）。deploy.sh 用的是 v2 语法，
#     v1 的独立二进制 `docker-compose` 不兼容。
# =====================================================================
if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  c_skip "docker + compose v2 已就绪: $(docker compose version --short 2>/dev/null)"
else
  c_info "安装 docker.io + docker-compose-v2"
  # shellcheck disable=SC2034
  apt-get install -y -qq docker.io >/dev/null 2>&1 || true
  apt-get install -y -qq docker-compose-v2 >/dev/null 2>&1 || \
    apt-get install -y -qq docker-compose-plugin >/dev/null 2>&1 || true

  if ! docker compose version >/dev/null 2>&1; then
    c_warn "apt 源里没有 compose v2，尝试 Docker 官方仓库（可能需要几分钟）"
    install -m 0755 -d /etc/apt/keyrings
    if [ ! -f /etc/apt/keyrings/docker.asc ]; then
      curl -fsSL --connect-timeout 20 https://download.docker.com/linux/"${ID}"/gpg \
        -o /etc/apt/keyrings/docker.asc || c_die "下载 Docker GPG key 失败（网络受限？）"
      chmod a+r /etc/apt/keyrings/docker.asc
    fi
    echo "deb [arch=${ARCH} signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/${ID} $(. /etc/os-release && echo "${VERSION_CODENAME}") stable" \
      > /etc/apt/sources.list.d/docker.list
    apt-get update -qq
    apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-compose-plugin >/dev/null
  fi

  docker compose version >/dev/null 2>&1 || c_die \
    "compose v2 不可用。手工装法: apt install docker-compose-v2，或参照 https://docs.docker.com/compose/install/linux/"
  c_ok "docker $(docker --version | awk '{print $3}' | tr -d ,) / compose $(docker compose version --short)"
fi

systemctl is-enabled docker >/dev/null 2>&1 || systemctl enable docker >/dev/null 2>&1 || true
systemctl is-active  docker >/dev/null 2>&1 || systemctl start  docker

# 验证真的能跑容器 —— 光有二进制不代表 daemon 正常（cgroup 驱动、iptables 都可能出问题）
docker info >/dev/null 2>&1 || c_die "docker daemon 不健康，检查: systemctl status docker"

# =====================================================================
#  4. Nginx
# =====================================================================
if command -v nginx >/dev/null 2>&1; then
  c_skip "nginx 已安装: $(nginx -v 2>&1)"
else
  c_info "安装 nginx"
  apt-get install -y -qq nginx >/dev/null
  c_ok "$(nginx -v 2>&1)"
fi
systemctl enable nginx >/dev/null 2>&1 || true

# =====================================================================
#  5. 目录结构
#
#  上传的产物落点（与 deploy.conf 的 REMOTE_* 一致）:
#    apps/     各服务的 jar
#    web/      前端静态产物（nginx root）
#    config/   Spring 配置覆盖（--spring.config.additional-location）
#    logs/     服务日志（systemd StandardOutput=append 写这里）
#    sql/      数据库迁移脚本
#    bin/ run-service.sh
#    systemd/  ai-agent@.service
#    nginx/    ai-agent.conf
# =====================================================================
c_info "创建目录 ${ROOT}"
mkdir -p "${ROOT}"/{apps,web,config,logs,downloads,sql,bin,systemd,nginx}
chmod 750 "${ROOT}"
chmod 755 "${ROOT}"/{apps,web,sql,bin,systemd,nginx}
# config/ 和 logs/ 只有 root 能看：config 里有配置，logs 里有用户 prompt 明文
chmod 750 "${ROOT}"/{config,logs}
c_ok "目录就绪"

# =====================================================================
#  6. Swap
#
#  ★ 2C2G 上这是**必需项**，不是优化。
#    5 个 JVM + 4 个容器实测常驻约 1.8G，已经贴着 2G 上限；没有 swap 时
#    内存抖动会直接触发 OOM killer 杀掉随机的服务（通常是内存最大的 agent-core），
#    表现为"服务莫名重启"。
#
#  swappiness=20: 默认值 60 会让内核过早把 JVM 热堆换出去，造成持续抖动；
#    设 0 又会让 OOM killer 先行。20 是"平时不换、压力大时才换"的折中。
#    如果部署后发现 si/so 长期不为 0（vmstat 1），说明是真的不够用，该升配了。
# =====================================================================
if swapon --show=NAME --noheadings 2>/dev/null | grep -q .; then
  c_skip "已有 swap: $(swapon --show=SIZE --noheadings | tr -d ' ' | paste -sd+ | bc 2>/dev/null || echo '?')"
else
  if [ -f /swapfile ]; then
    c_info "复用已存在的 /swapfile"
  else
    c_info "创建 ${SWAP_SIZE_MB}MB swap（在 2G 机器上 dd 需要一两分钟）"
    # fallocate 在部分文件系统上会创建出无法 swap 的稀疏文件，dd 慢但可靠
    dd if=/dev/zero of=/swapfile bs=1M count="${SWAP_SIZE_MB}" status=none
    chmod 600 /swapfile
  fi
  mkswap /swapfile >/dev/null
  swapon /swapfile
  # 写 fstab 前先确认没写过（重复执行会累积多行，虽然无害但不干净）
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
  c_ok "swap 已启用: $(free -h | awk '/Swap/ {print $2}')"
fi

cat > /etc/sysctl.d/99-ai-agent.conf <<'EOF'
# AI Agent Platform —— 2C2G 小机器调优（由 remote-install.sh 写入）
#
# 默认 60 会过早换出 JVM 热堆, 造成持续抖动; 0 又会让 OOM killer 先动手。
# 20 = 平时不换、压力大时才换。
vm.swappiness = 20
# 换出时优先丢弃干净的页缓存, 而不是换出匿名页（JVM 堆）。小内存机器的关键选项。
vm.vfs_cache_pressure = 50
# nginx 与 JVM 的连接队列：默认 128 在高并发建立连接时会丢 SYN
net.core.somaxconn = 1024
net.ipv4.tcp_max_syn_backlog = 1024
# 小机器上 TIME_WAIT 堆积会耗尽本地端口
net.ipv4.tcp_fin_timeout = 15
net.ipv4.tcp_tw_reuse = 1
EOF
sysctl -p /etc/sysctl.d/99-ai-agent.conf >/dev/null
c_ok "内核参数已应用"

# =====================================================================
#  7. systemd 单元 + 单服务内存上限
#
#  MemoryMax 的意义: 没有它时，一个跑飞的服务会拖着整机一起 OOM（SSH 都连不上）；
#  有了它，内核杀的是那个 cgroup 里的进程，systemd 按 Restart=on-failure 拉起来。
#  上限 = 该服务的 -Xmx + 堆外开销（Metaspace/线程栈/CodeCache/直接内存）:
#    JVM_SMALL  额外给 260M, JVM_MEDIUM 额外给 320M
#  这是**天花板不是预留**，所以几个服务加起来超过物理内存是可以的 ——
#  正常运行时谁也不会顶到上限。
# =====================================================================
UNIT_SRC="${SCRIPT_DIR}/systemd/ai-agent@.service"
if [ -f "${UNIT_SRC}" ]; then
  # 把仓库里的默认路径 /opt/ai-agent 替换成 deploy.conf 的 REMOTE_ROOT ——
  # 单元文件里 EnvironmentFile / ExecStart / 日志路径全是绝对路径，不替换的话
  # 改了 REMOTE_ROOT 就会去启动一个不存在的目录。
  sed "s|/opt/ai-agent|${ROOT}|g" "${UNIT_SRC}" > /etc/systemd/system/ai-agent@.service
  chmod 0644 /etc/systemd/system/ai-agent@.service
  c_ok "systemd 模板单元已安装（路径 ${ROOT}）"
else
  c_warn "找不到 ${UNIT_SRC}，跳过（systemctl start ai-agent@xxx 会失败）"
fi

# 启动器脚本里也硬编码了 ROOT（它不在 systemd 的环境里，拿不到 services.env 之外
# 的东西），同样替换一次
RUNNER="${ROOT}/bin/run-service.sh"
if [ -f "${RUNNER}" ]; then
  sed -i "s|^ROOT=\"/opt/ai-agent\"|ROOT=\"${ROOT}\"|" "${RUNNER}"
  chmod 0755 "${RUNNER}"
  c_ok "启动器已就位: ${RUNNER}"
fi

if [ -f "${UNIT_SRC}" ]; then
  svc_list="gateway user-service agent-core image-engine storage-service security-service video-engine"
  for svc in ${svc_list}; do
    var="JVM_OPTS_${svc//-/_}"
    opts="${!var:-}"
    [ -n "${opts}" ] || continue
    xmx="$(printf '%s' "${opts}" | sed -n 's/.*-Xmx\([0-9]\+\)m.*/\1/p')"
    [ -n "${xmx}" ] || continue

    # headroom = Metaspace + 线程栈 + CodeCache + 少量堆外。
    # 从 opts 里实际解析 MaxMetaspaceSize，而不是靠匹配变量名 ——
    # 之前写成 case *JVM_MEDIUM* 是错的：opts 是展开后的字面量，
    # 不含 "JVM_MEDIUM" 这个变量名，该分支永远不会命中。
    meta="$(printf '%s' "${opts}" | sed -n 's/.*-XX:MaxMetaspaceSize=\([0-9]\+\)m.*/\1/p')"
    [ -n "${meta}" ] || meta=128
    # 128/160(Metaspace) + 64(线程栈: 约 100 线程 × 512k) + 48(CodeCache) + 18(杂项)
    max=$((xmx + meta + 130))

    dropin_dir="/etc/systemd/system/ai-agent@${svc}.service.d"
    mkdir -p "${dropin_dir}"
    cat > "${dropin_dir}/memory.conf" <<EOF
# 由 remote-install.sh 生成（deploy.conf 的 JVM_OPTS_${svc//-/_} 推导）
# MemoryHigh 软限: 超过后内核优先回收本 cgroup 的页, 变慢但不杀
# MemoryMax  硬限: 超过直接 OOM kill 本服务, systemd 会按 Restart 策略拉起
[Service]
MemoryHigh=$((max * 85 / 100))M
MemoryMax=${max}M
EOF
  done
  systemctl daemon-reload
  c_ok "单服务内存上限已配置（防止一个服务失控拖垮整机）"
fi

# =====================================================================
#  8. nginx 站点
#
#  未备案阶段域名会被云厂商拦在 80/443，所以这里 listen 用 PUBLIC_PORT
#  （默认 80），server_name 留 _ 通配。备案通过后把 deploy.conf 的 DOMAIN
#  填上重跑 deploy.sh，本步骤会重写这份配置。
# =====================================================================
NGINX_SRC="${SCRIPT_DIR}/nginx/ai-agent.conf"
if [ -f "${NGINX_SRC}" ]; then
  c_info "安装 nginx 站点配置"
  # 模板里用 __XXX__ 占位，避免 nginx 配置依赖 shell 变量展开
  # （nginx 的变量语法 $host 和 shell 语法会互相干扰）
  sed -e "s|__PUBLIC_PORT__|${PUBLIC_PORT}|g" \
      -e "s|__SERVER_NAME__|${DOMAIN:-_}|g" \
      -e "s|__WEB_ROOT__|${ROOT}/web|g" \
      -e "s|__GATEWAY_PORT__|${PORT_GATEWAY}|g" \
      "${NGINX_SRC}" > /etc/nginx/sites-available/ai-agent.conf

  ln -sf /etc/nginx/sites-available/ai-agent.conf /etc/nginx/sites-enabled/ai-agent.conf
  # 默认站点占着 80 的 default_server，不删掉会抢走请求
  rm -f /etc/nginx/sites-enabled/default

  if nginx -t >/dev/null 2>&1; then
    systemctl reload nginx 2>/dev/null || systemctl restart nginx
    c_ok "nginx 站点已启用（listen ${PUBLIC_PORT}）"
  else
    c_warn "nginx 配置校验失败，请检查 /etc/nginx/sites-available/ai-agent.conf:"
    nginx -t 2>&1 | sed 's/^/         /' >&2
  fi
else
  c_warn "找不到 ${NGINX_SRC}，跳过（前端将无法通过 80 端口访问）"
fi

# =====================================================================
#  9. 日志轮转
#
#  systemd 的 StandardOutput=append: 是**无限追加**的。即使日志级别调到 INFO,
#  一个跑几周的 agent-core 日志也会涨到几百 MB —— 在 40G 系统盘上这就是事故。
#  这里按天切、留 7 天、总量封顶 200M。
# =====================================================================
cat > /etc/logrotate.d/ai-agent <<EOF
# 由 remote-install.sh 写入
${ROOT}/logs/*.log {
    daily
    rotate 7
    maxsize 32M
    compress
    delaycompress
    missingok
    notifempty
    # 服务以 root 跑（systemd 单元没设 User=），所以不需要 su 切用户
    copytruncate
}
EOF
c_ok "日志轮转已配置（每天切、留 7 天、单文件 32M 封顶）"

# logrotate 靠 cron/timer 触发，最小化安装的镜像里可能没有
if ! systemctl list-timers --all 2>/dev/null | grep -q logrotate; then
  c_warn "logrotate.timer 似乎未启用，日志不会自动轮转。启用: systemctl enable --now logrotate.timer"
fi

# =====================================================================
#  10. 结束语
# =====================================================================
echo ""
c_ok "系统环境初始化完成"
echo ""
echo "  下一步:"
echo "    1. 本地构建并上传产物（开发机上执行 deploy/build-local.ps1）"
echo "    2. 服务器上执行:  cd ${ROOT} && ./deploy.sh config"
echo "    3.                ./deploy.sh infra"
echo "    4.                ./deploy.sh db"
echo "    5.                ./deploy.sh start"
echo ""
echo "  ⚠️ 别忘了在云控制台的安全组里放行 ${PUBLIC_PORT} 端口 —— 服务器本机"
echo "     的 ufw 不拦，但安全组会，表现为浏览器一直转圈直到超时。"
echo ""

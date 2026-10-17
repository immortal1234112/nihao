#!/usr/bin/env bash
# =====================================================================
#  单服务启动器 —— 由 systemd 单元 ai-agent@.service 调用
#
#      ExecStart=/opt/ai-agent/bin/run-service.sh %i
#
#  职责:
#    1. 按服务名找到对应的 fat jar（版本号不写死, 换版本不用改脚本）
#    2. 按服务名取出对应的一档 JVM 参数（2C2G 上必须显式限制堆, 否则 OOM）
#    3. 用 exec 启动 —— exec 让 java 进程直接顶替当前 shell, 这样 systemd
#       的 SIGTERM 直达 JVM, Spring Boot 的优雅停机才会生效。
#       写成 `java -jar ...` 不加 exec 的话, systemd 杀的是 shell, java 会变孤儿。
# =====================================================================
set -euo pipefail

SVC="${1:-}"
if [ -z "$SVC" ]; then
  echo "[run-service] 用法: run-service.sh <服务名>" >&2
  exit 2
fi

ROOT="/opt/ai-agent"

# ---------------------------------------------------------------------
# 1. 取 JVM 参数
#    services.env 里的变量名用下划线（gateway / agent_core / user_service ...）,
#    因为 bash 变量名不允许含 '-'。这里把服务名里的 '-' 换成 '_' 来匹配。
#
#    取不到就给一份保守的默认值 —— 宁可堆小一点（慢但活着）也不要
#    JVM 按宿主机内存自动扩张把 2G 的机器打爆。
# ---------------------------------------------------------------------
VAR_NAME="JVM_OPTS_${SVC//-/_}"
JVM_OPTS="${!VAR_NAME:-}"
if [ -z "$JVM_OPTS" ]; then
  # 兜底：与 deploy.conf 的 JVM_BASE 保持一致（含 2C2G 的 OOM 快退参数）。
  # 故意写成单行 —— 避免续行在不同 shell 下的解析差异。
  JVM_OPTS="-Xss512k -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -XX:ActiveProcessorCount=2 -XX:-UsePerfData -Dfile.encoding=UTF-8 -Djava.security.egd=file:/dev/./urandom -XX:MaxMetaspaceSize=128m -Xmx256m"
  echo "[run-service] 警告: services.env 里没有 ${VAR_NAME}, 使用默认 JVM 参数" >&2
fi

# ---------------------------------------------------------------------
# 2. 找 jar
#    用 find -print -quit 而不是 ls: 目录里若同时存在新旧两个版本,
#    ls | head 在某些 shell 下会有 SIGPIPE 噪音, find 更干净。
# ---------------------------------------------------------------------
JAR="$(find "${ROOT}/apps" -maxdepth 1 -name "${SVC}-*.jar" -print -quit 2>/dev/null || true)"
if [ -z "${JAR}" ]; then
  echo "[run-service] 错误: 找不到 ${SVC} 的 jar" >&2
  echo "[run-service] 期望路径: ${ROOT}/apps/${SVC}-<version>.jar" >&2
  echo "[run-service] 实际内容:" >&2
  ls -1 "${ROOT}/apps" >&2 2>/dev/null || echo "  (目录不存在)" >&2
  exit 1
fi

echo "[run-service] $(date '+%F %T') 启动 ${SVC}"
echo "[run-service] jar     : ${JAR}"
echo "[run-service] JVM     : ${JVM_OPTS}"
echo "[run-service] 配置文件: ${ROOT}/config/application.yml"

# ---------------------------------------------------------------------
# 3. 端口覆盖（只有 gateway 需要）
#
#  gateway 的 jar 内 application.yml 写的是 server.port=8080，但 nginx 要
#  独占对外端口 PUBLIC_PORT。二者若相同，nginx 的 /api 反代就会打回自己，
#  形成请求头不断累积的死循环（表现：400 Request Header Or Cookie Too Large）。
#  所以由 services.env 的 PORT_GATEWAY 显式指定 gateway 的内部端口。
#
#  其他服务的端口不需要覆盖 —— 它们只被 gateway 通过静态服务发现表访问，
#  而那张表写在 config/application.yml 里，与各自的 jar 默认值一致。
# ---------------------------------------------------------------------
APP_ARGS=""
if [ "${SVC}" = "gateway" ] && [ -n "${PORT_GATEWAY:-}" ]; then
  APP_ARGS="--server.port=${PORT_GATEWAY}"
  echo "[run-service] 端口    : gateway 使用内部端口 ${PORT_GATEWAY}（对外由 nginx 承接）"
fi

# ---------------------------------------------------------------------
# 4. 启动
#
#  --spring.config.additional-location 的优先级**高于** jar 内的 application.yml,
#  所以 config/application.yml 里的静态服务发现、Nacos 开关能覆盖打包默认值。
#
#  $JVM_OPTS / ${APP_ARGS} 故意不加引号 —— 需要按空格拆成多个参数。
# ---------------------------------------------------------------------
exec java ${JVM_OPTS} \
  -jar "${JAR}" \
  --spring.config.additional-location="file:${ROOT}/config/" \
  ${APP_ARGS}

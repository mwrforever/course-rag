#!/usr/bin/env bash
# ============================================================================
# commerce-customer 开发环境启动脚本（bash / sh 双兼容，POSIX 子集 + bash 壳）
#
# 功能：
#   all        启动全部（infra + backend + 双前端），默认动作
#   infra      仅启动基础设施（docker compose dev：PG/Redis/etcd/MinIO/Milvus）
#   backend    仅启动后端（mvn spring-boot:run, :8080）
#   c          仅启动 C 端学生端（Next.js, :5000）
#   b          仅启动 B 端管理端（Vue3, :5001）
#   status     查看各组件运行状态
#   stop       停止全部本地进程（可指定组件: backend|c|b）
#   down       停止本地进程并关闭基础设施容器
#   logs       跟踪查看 logs/ 下的运行日志
#   envs       查看 .env 注入结果（敏感键掩码显示）
#   help       显示帮助
#
# .env 注入规则：
#   1. 启动前自动加载 backend/.env（KEY=VALUE 行，支持成对引号，忽略注释/空行/非法键）
#   2. 注入动作 = export，mvn/java/node 等所有子进程全部可见
#   3. 已存在的环境变量优先（不覆盖），符合 dotenv 惯例；如需强制覆盖，
#      请先 export 目标变量再运行本脚本
#
# 用法示例：
#   ./dev.sh            # 等价 ./dev.sh all
#   ./dev.sh backend    # 只启动后端并等待健康
#   ./dev.sh stop c     # 只停 C 端
#   ./dev.sh down       # 全部停止 + 关闭基础设施容器
#
# 环境变量：
#   ENV_FILE      自定义 .env 路径（默认 backend/.env）
#   PROJECT_ROOT  自定义项目根目录（默认本脚本目录的上一级）
# ============================================================================
set -u

# ── 路径定位：脚本放 tools/ 或任意子目录均可，默认项目根 = 脚本目录的上一级 ──
_SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
_PROJECT_ROOT=${PROJECT_ROOT:-"$(dirname "$_SCRIPT_DIR")"}
cd "$_PROJECT_ROOT" || { echo "[dev][错误] 无法进入项目根目录: $_PROJECT_ROOT" >&2; exit 1; }

_ENV_FILE=${ENV_FILE:-"$_PROJECT_ROOT/backend/.env"}
_LOGS_DIR="$_PROJECT_ROOT/logs"
_CMD=${1:-all}

mkdir -p "$_LOGS_DIR"

# ── 基础工具函数 ──
_info() { echo "[dev] $*"; }
_warn() { echo "[dev][警告] $*" >&2; }
_err()  { echo "[dev][错误] $*" >&2; }
_have() { command -v "$1" >/dev/null 2>&1; }
_pid_file() { echo "$_LOGS_DIR/$1.pid"; }

# ── .env 注入：加载 backend/.env 并 export（不覆盖已存在的环境变量） ──
_load_env() {
  [ -f "$1" ] || { _warn "未找到 .env 文件: $1（后端将回退 application.yml 默认值）"; return 0; }
  while IFS= read -r _line || [ -n "$_line" ]; do
    # Windows 下 .env 可能是 CRLF 行尾，先剔除 \r
    _line=$(printf '%s' "$_line" | tr -d '\r')
    case "$_line" in
      ''|'#'*) continue ;;                  # 跳过空行与整行注释
    esac
    _key=${_line%%=*}
    _val=${_line#*=}
    # 仅接受合法环境变量名（字母/数字/下划线），防止畸形键被注入
    case "$_key" in
      *[!A-Za-z0-9_]*|'') continue ;;
    esac
    # 去除成对引号（支持 KEY="value" / KEY='value'）
    case "$_val" in
      \"*\") _val=${_val#\"}; _val=${_val%\"} ;;
      \'*\') _val=${_val#\'}; _val=${_val%\'} ;;
    esac
    # 已存在的环境变量优先，不覆盖
    if ! printenv "$_key" >/dev/null 2>&1; then
      export "$_key=$_val"
    fi
  done < "$1"
}

# ── 通用：已运行检查（PID 文件 + kill -0 双确认） ──
_ensure_not_running() {
  _name=$1
  _pf=$(_pid_file "$_name")
  if [ -f "$_pf" ]; then
    _pid=$(cat "$_pf" 2>/dev/null)
    if [ -n "${_pid:-}" ] && kill -0 "$_pid" 2>/dev/null; then
      _err "$_name 已在运行（PID $_pid），如需重启请先执行: ./dev.sh stop $_name"
      return 1
    fi
  fi
  return 0
}

# ── 通用：等待某端口可访问（curl 不存在时仅提示跳过） ──
_wait_port() {
  _name=$1
  _port=$2
  _seconds=$3
  if ! _have curl; then
    _warn "未找到 curl，跳过 $_name 就绪等待"
    return 1
  fi
  _info "等待 $_name 就绪（http://localhost:$_port，最长 ${_seconds}s）..."
  _i=0
  _max=$((_seconds / 2))
  # 任意 HTTP 状态码（含 404/401）都视为已监听，仅连接被拒才算未就绪
  while [ "$_i" -lt "$_max" ]; do
    if curl -s -o /dev/null "http://localhost:$_port/"; then
      _info "$_name 已就绪"
      return 0
    fi
    sleep 2
    _i=$((_i + 1))
  done
  _warn "$_name 等待超时（$_seconds s），请查看对应日志排查（基础设施容器未就绪或端口冲突是常见原因）"
  return 1
}

# ── 启动基础设施（docker compose dev）；失败时给出排查清单 ──
_start_infra() {
  _have docker || { _err "未找到 docker，请先安装并启动 Docker Desktop"; return 1; }
  if ! docker info >/dev/null 2>&1; then
    _err "Docker daemon 不可达，请先启动 Docker Desktop 并等待托盘鲸鱼图标稳定后重试"
    return 1
  fi
  _info "启动基础设施（docker compose -f docker-compose.dev.yml up -d）..."
  if ! docker compose -f docker-compose.dev.yml up -d; then
    _err "基础设施启动失败，常见原因："
    _err "  1. 端口冲突（5432/6379/19530/9002/2379 被其他项目占用）：netstat -ano | findstr 对应端口 排查"
    _err "  2. 镜像拉取失败：检查网络，或先执行 docker compose -f docker-compose.dev.yml pull"
    _err "  3. Docker Desktop 未就绪：查看托盘鲸鱼图标"
    return 1
  fi
  _info "基础设施已就绪（PG:5432 / Redis:6379 / Milvus:19530 / MinIO:9002）"
}

# ── 启动后端（mvn spring-boot:run, :8080）；$1=fg 时前台运行（日志直显，Ctrl+C 退出） ──
_start_backend() {
  _ensure_not_running backend || return 1
  _have mvn || { _err "未找到 mvn，请确认 Maven 已安装并加入 PATH"; return 1; }
  _have java || { _err "未找到 java，请确认 JDK 17+ 已安装并加入 PATH"; return 1; }
  if [ "${1:-}" = "fg" ]; then
    # 前台运行前记录 PID（exec 不改变进程号），保证 ./dev.sh stop 仍可停止
    echo "$$" > "$(_pid_file backend)"
    _info "前台启动后端（mvn spring-boot:run），Ctrl+C 退出..."
    cd "$_PROJECT_ROOT/backend" || return 1
    exec mvn spring-boot:run
  fi
  _info "启动后端（mvn spring-boot:run，日志: logs/backend.log）..."
  ( cd "$_PROJECT_ROOT/backend" && nohup mvn spring-boot:run >"$_LOGS_DIR/backend.log" 2>&1 &
    echo "$!" > "$(_pid_file backend)" )
  _info "后端已后台启动（PID $(cat "$(_pid_file backend)")）"
}

# ── 解析 pnpm 启动器：优先 pnpm，缺失时回退 corepack（按 packageManager 字段启用对应版本） ──
_resolve_pnpm() {
  if _have pnpm; then
    _PNPM_CMD="pnpm"
  elif _have corepack; then
    _PNPM_CMD="corepack pnpm"
  else
    _err "未找到 pnpm/corepack，请先安装 pnpm（corepack enable 或 npm i -g pnpm）"
    return 1
  fi
  return 0
}

# ── 启动 C 端学生端（Next.js, :5000）；$1=fg 时前台运行 ──
_start_c_frontend() {
  _ensure_not_running c-frontend || return 1
  _resolve_pnpm || return 1
  if [ "${1:-}" = "fg" ]; then
    # 前台运行前记录 PID（exec 不改变进程号），保证 ./dev.sh stop 仍可停止
    echo "$$" > "$(_pid_file c-frontend)"
    _info "前台启动 C 端学生端（$_PNPM_CMD --filter student-frontend dev，:5000），Ctrl+C 退出..."
    exec $_PNPM_CMD --filter student-frontend dev
  fi
  _info "启动 C 端学生端（$_PNPM_CMD --filter student-frontend dev，日志: logs/c-frontend.log）..."
  nohup $_PNPM_CMD --filter student-frontend dev >"$_LOGS_DIR/c-frontend.log" 2>&1 &
  echo "$!" > "$(_pid_file c-frontend)"
  _info "C 端已后台启动（PID $(cat "$(_pid_file c-frontend)")）"
}

# ── 启动 B 端管理端（Vue3, :5001）；$1=fg 时前台运行 ──
_start_b_frontend() {
  _ensure_not_running b-frontend || return 1
  _resolve_pnpm || return 1
  if [ "${1:-}" = "fg" ]; then
    # 前台运行前记录 PID（exec 不改变进程号），保证 ./dev.sh stop 仍可停止
    echo "$$" > "$(_pid_file b-frontend)"
    _info "前台启动 B 端管理端（$_PNPM_CMD --filter frontend dev，:5001），Ctrl+C 退出..."
    exec $_PNPM_CMD --filter frontend dev
  fi
  _info "启动 B 端管理端（$_PNPM_CMD --filter frontend dev，日志: logs/b-frontend.log）..."
  nohup $_PNPM_CMD --filter frontend dev >"$_LOGS_DIR/b-frontend.log" 2>&1 &
  echo "$!" > "$(_pid_file b-frontend)"
  _info "B 端已后台启动（PID $(cat "$(_pid_file b-frontend)")）"
}

# ── 停止单个组件（kill 主进程 + 轮询端口释放 + 残留提示） ──
_stop_one() {
  _name=$1
  _port=$2
  _pf=$(_pid_file "$_name")
  if [ ! -f "$_pf" ]; then
    _info "$_name 未在运行"
    return 0
  fi
  _pid=$(cat "$_pf" 2>/dev/null)
  if [ -z "${_pid:-}" ] || ! kill -0 "$_pid" 2>/dev/null; then
    _info "$_name 进程已退出，清理残留 PID 文件"
    rm -f "$_pf"
    return 0
  fi
  _info "停止 $_name（PID $_pid）..."
  kill "$_pid" 2>/dev/null
  # 主进程停止后，mvn/node 可能残留子进程（fork 的 java/vite），轮询端口等待收敛
  if [ -n "$_port" ] && _have curl; then
    _i=0
    while [ "$_i" -lt 15 ] && curl -s -o /dev/null "http://localhost:$_port/"; do
      sleep 1
      _i=$((_i + 1))
    done
  else
    sleep 2
  fi
  if [ -n "$_port" ] && _have curl && curl -s -o /dev/null "http://localhost:$_port/"; then
    _warn "$_name 端口 $_port 仍被占用：主进程已终止但可能有残留子进程（可用 jps/tasklist 排查后手动结束）"
  fi
  rm -f "$_pf"
}

# ── 停止全部本地进程 ──
_stop_all() {
  _stop_one backend 8080
  _stop_one c-frontend 5000
  _stop_one b-frontend 5001
}

# ── 查看运行状态 ──
_status_one() {
  _name=$1
  _port=$2
  _pf=$(_pid_file "$_name")
  _state="未运行"
  if [ -f "$_pf" ]; then
    _pid=$(cat "$_pf" 2>/dev/null)
    if [ -n "${_pid:-}" ] && kill -0 "$_pid" 2>/dev/null; then
      _state="运行中(PID $_pid)"
    else
      _state="已退出(残留 PID 文件, 可执行 stop 清理)"
    fi
  fi
  _port_state=""
  if [ -n "$_port" ] && _have curl; then
    if curl -s -o /dev/null "http://localhost:$_port/"; then
      _port_state="端口 $_port 已监听"
    else
      _port_state="端口 $_port 未监听"
    fi
  fi
  printf '  %-12s %s %s\n' "$_name" "$_state" "$_port_state"
}

_status() {
  _info "项目根目录: $_PROJECT_ROOT"
  _info "组件状态:"
  _status_one backend 8080
  _status_one c-frontend 5000
  _status_one b-frontend 5001
}

# ── 跟踪日志 ──
_logs() {
  if ! _have tail; then
    _err "未找到 tail 命令"
    return 1
  fi
  if ls "$_LOGS_DIR"/*.log >/dev/null 2>&1; then
    tail -f "$_LOGS_DIR"/*.log
  else
    _warn "logs/ 目录暂无日志文件（先启动组件再执行本命令）"
  fi
}

# ── 查看 .env 注入结果（敏感键掩码显示） ──
_envs() {
  [ -f "$_ENV_FILE" ] || { _warn "未找到 $_ENV_FILE"; return 0; }
  _info "已注入环境变量（来源: $_ENV_FILE，含 KEY/SECRET/PASSWORD/TOKEN 的键掩码显示）:"
  while IFS= read -r _line || [ -n "$_line" ]; do
    _line=$(printf '%s' "$_line" | tr -d '\r')
    case "$_line" in
      ''|'#'*) continue ;;
    esac
    _key=${_line%%=*}
    case "$_key" in
      *[!A-Za-z0-9_]*|'') continue ;;
    esac
    _val=$(printenv "$_key" 2>/dev/null)
    case "$_key" in
      *KEY*|*SECRET*|*PASSWORD*|*TOKEN*) printf '  %s=***\n' "$_key" ;;
      *) printf '  %s=%s\n' "$_key" "$_val" ;;
    esac
  done < "$_ENV_FILE"
}

# ── 帮助 ──
_usage() {
  cat <<'EOF'
commerce-customer 开发环境启动脚本

用法: ./dev.sh [命令]

命令:
  all        启动全部（infra + backend + 双前端），默认动作
  infra      仅启动基础设施（docker compose dev: PG/Redis/etcd/MinIO/Milvus）
  backend    仅启动后端（mvn spring-boot:run, :8080）
  c          仅启动 C 端学生端（Next.js, :5000）
  b          仅启动 B 端管理端（Vue3, :5001）
  status     查看各组件运行状态
  stop       停止全部本地进程（可指定组件: backend|c|b）
  down       停止本地进程并关闭基础设施容器
  logs       跟踪查看 logs/ 下的运行日志
  envs       查看 .env 注入结果（敏感键掩码显示）
  help       显示本帮助

环境变量:
  ENV_FILE      自定义 .env 路径（默认 backend/.env）
  PROJECT_ROOT  自定义项目根目录（默认脚本目录的上一级）

说明:
  - 启动前自动加载 backend/.env 并 export（不覆盖已有环境变量）
  - 各组件后台运行，PID 与日志分别存放于 logs/ 目录
  - Ctrl+C 不会终止后台进程，请用 ./dev.sh stop 或 down 停止
EOF
}

# ═══════════════════════ 主流程 ═══════════════════════
_load_env "$_ENV_FILE"

case "$_CMD" in
  all|start|up)
    _start_infra || { _err "基础设施启动失败，中止启动流程（修复上述原因后重试）"; exit 1; }
    if _start_backend; then
      _wait_port "后端" 8080 120
    else
      _warn "后端启动失败（见上方提示），跳过就绪等待，继续启动前端"
    fi
    _start_c_frontend
    _start_b_frontend
    _status
    _info "正在跟踪运行日志（Ctrl+C 退出跟踪，服务保持后台运行；./dev.sh stop 停止）..."
    _logs
    ;;
  infra)
    _start_infra
    ;;
  backend)
    _start_backend fg
    ;;
  c)
    _start_c_frontend fg
    ;;
  b)
    _start_b_frontend fg
    ;;
  status)
    _status
    ;;
  stop)
    if [ $# -ge 2 ]; then
      case "$2" in
        backend) _stop_one backend 8080 ;;
        c)       _stop_one c-frontend 5000 ;;
        b)       _stop_one b-frontend 5001 ;;
        *)       _err "未知组件: $2（可选 backend / c / b）"; _usage; exit 1 ;;
      esac
    else
      _stop_all
    fi
    ;;
  down)
    _stop_all
    if _have docker; then
      _info "关闭基础设施容器（docker compose down，数据卷保留）..."
      docker compose -f docker-compose.dev.yml down
    fi
    ;;
  logs)
    _logs
    ;;
  envs)
    _envs
    ;;
  help|-h|--help)
    _usage
    ;;
  *)
    _err "未知命令: $_CMD"
    _usage
    exit 1
    ;;
esac

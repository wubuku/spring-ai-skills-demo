#!/usr/bin/env bash
#
# 一键启动本项目开发环境。
#
# 用法：
#   ./dev.sh                 读取 .env，启动后端 8080 和前端 4000
#   ./dev.sh --backend-only  只启动 Spring Boot 后端
#   ./dev.sh --frontend-only 只启动 Next.js 前端
#   ./dev.sh --stop          精确停止指定端口上的本项目服务
#
# 端口可通过环境变量覆盖：
#   BACKEND_PORT=18080 FRONTEND_PORT=4400 ./dev.sh
#
# 日志写入系统临时目录，不写入仓库：
#   ${DEV_RUNTIME_DIR:-$TMPDIR/spring-ai-skills-demo-dev}/

set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_PORT="${BACKEND_PORT:-8080}"
FRONTEND_PORT="${FRONTEND_PORT:-4000}"
DEV_RUNTIME_DIR="${DEV_RUNTIME_DIR:-${TMPDIR:-/tmp}/spring-ai-skills-demo-dev}"
BACKEND_LOG="$DEV_RUNTIME_DIR/backend.log"
FRONTEND_LOG="$DEV_RUNTIME_DIR/frontend.log"
START_BACKEND=true
START_FRONTEND=true

usage() {
  sed -n '1,28p' "$0"
}

die() {
  printf 'dev.sh: %s\n' "$*" >&2
  exit 1
}

listener_pids() {
  lsof -ti:"$1" -sTCP:LISTEN 2>/dev/null || true
}

stop_port() {
  local port="$1"
  local pids
  pids="$(listener_pids "$port")"
  if [[ -z "$pids" ]]; then
    printf 'port %s is already free\n' "$port"
    return 0
  fi

  printf 'stopping listener(s) on port %s: %s\n' "$port" "${pids//$'\n'/ }"
  while read -r pid; do
    [[ -n "$pid" ]] && kill "$pid" 2>/dev/null || true
  done <<< "$pids"
  sleep 1

  pids="$(listener_pids "$port")"
  if [[ -n "$pids" ]]; then
    while read -r pid; do
      [[ -n "$pid" ]] && kill -9 "$pid" 2>/dev/null || true
    done <<< "$pids"
  fi
}

assert_port_free() {
  local port="$1"
  local pids
  pids="$(listener_pids "$port")"
  [[ -z "$pids" ]] || die "port $port is occupied by PID(s): ${pids//$'\n'/ }. Run ./dev.sh --stop or choose another port."
}

wait_for_http() {
  local url="$1"
  local timeout="${2:-180}"
  local elapsed=0
  local status

  while (( elapsed < timeout )); do
    status="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 5 "$url" 2>/dev/null || true)"
    if [[ "$status" =~ ^2[0-9][0-9]$ ]]; then
      return 0
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done

  return 1
}

run_detached() {
  local log_file="$1"
  shift

  if command -v setsid >/dev/null 2>&1; then
    nohup setsid "$@" >"$log_file" 2>&1 < /dev/null &
  elif command -v perl >/dev/null 2>&1; then
    nohup perl -MPOSIX -e '
      my $sid = POSIX::setsid();
      die "setsid failed: $!" unless defined($sid) && $sid > 0;
      exec @ARGV or die "exec failed: $!";
    ' -- "$@" >"$log_file" 2>&1 < /dev/null &
  else
    die "neither setsid nor perl is available to detach development services"
  fi

  DETACHED_PID="$!"
  disown "$DETACHED_PID" 2>/dev/null || true
}

load_env() {
  local env_file="$ROOT_DIR/.env"
  [[ -f "$env_file" ]] || die "missing $env_file; create it from .env.example first"

  set -a
  # shellcheck disable=SC1090
  source "$env_file"
  set +a

  export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-postgresql}"

  # The repository's .env convention uses POSTGRES_* while Spring Boot expects
  # SPRING_DATASOURCE_* when the URL/driver are supplied by the environment.
  if [[ -n "${SPRING_DATASOURCE_URL:-}" ]]; then
    export SPRING_DATASOURCE_USERNAME="${SPRING_DATASOURCE_USERNAME:-${POSTGRES_USER:-postgres}}"
    export SPRING_DATASOURCE_PASSWORD="${SPRING_DATASOURCE_PASSWORD:-${POSTGRES_PASSWORD:-}}"
  fi

  if [[ "${SPRING_PROFILES_ACTIVE:-postgresql}" == *postgresql* ]]; then
    export SPRING_AI_CHAT_MEMORY_REPOSITORY_JDBC_PLATFORM="${SPRING_AI_CHAT_MEMORY_REPOSITORY_JDBC_PLATFORM:-postgresql}"
  fi
}

start_backend() {
  assert_port_free "$BACKEND_PORT"
  mkdir -p "$DEV_RUNTIME_DIR"

  printf 'starting backend with SPRING_PROFILES_ACTIVE=%s on port %s\n' \
    "${SPRING_PROFILES_ACTIVE:-postgresql}" "$BACKEND_PORT"
  printf 'backend log: %s\n' "$BACKEND_LOG"

  (
    cd "$ROOT_DIR"
    run_detached "$BACKEND_LOG" env SERVER_PORT="$BACKEND_PORT" \
      mvn spring-boot:run -DskipTests
    echo "$DETACHED_PID" > "$DEV_RUNTIME_DIR/backend.pid"
  )

  if ! wait_for_http "http://localhost:$BACKEND_PORT/api/products" 180; then
    tail -80 "$BACKEND_LOG" >&2 || true
    die "backend did not become healthy"
  fi
  printf 'backend ready: http://localhost:%s/\n' "$BACKEND_PORT"
}

start_frontend() {
  assert_port_free "$FRONTEND_PORT"
  [[ -x "$ROOT_DIR/frontend/node_modules/.bin/next" ]] \
    || die "frontend dependencies are missing; run (cd frontend && npm ci)"
  mkdir -p "$DEV_RUNTIME_DIR"

  export JAVA_BACKEND_URL="${JAVA_BACKEND_URL:-http://localhost:$BACKEND_PORT}"
  export NEXT_PUBLIC_JAVA_BACKEND_URL="${NEXT_PUBLIC_JAVA_BACKEND_URL:-http://localhost:$BACKEND_PORT}"

  printf 'starting frontend on port %s\n' "$FRONTEND_PORT"
  printf 'frontend log: %s\n' "$FRONTEND_LOG"

  (
    cd "$ROOT_DIR/frontend"
    run_detached "$FRONTEND_LOG" env JAVA_BACKEND_URL="$JAVA_BACKEND_URL" \
      NEXT_PUBLIC_JAVA_BACKEND_URL="$NEXT_PUBLIC_JAVA_BACKEND_URL" \
      "$ROOT_DIR/frontend/node_modules/.bin/next" dev -p "$FRONTEND_PORT"
    echo "$DETACHED_PID" > "$DEV_RUNTIME_DIR/frontend.pid"
  )

  if ! wait_for_http "http://localhost:$FRONTEND_PORT" 60; then
    tail -80 "$FRONTEND_LOG" >&2 || true
    die "frontend did not become healthy"
  fi
  printf 'frontend ready: http://localhost:%s/\n' "$FRONTEND_PORT"
}

main() {
  local command="start"
  for arg in "$@"; do
    case "$arg" in
      --backend-only) START_FRONTEND=false ;;
      --frontend-only) START_BACKEND=false ;;
      --stop) command="stop" ;;
      -h|--help) usage; return 0 ;;
      *) die "unknown argument: $arg" ;;
    esac
  done

  if [[ "$command" == "stop" ]]; then
    stop_port "$FRONTEND_PORT"
    stop_port "$BACKEND_PORT"
    return 0
  fi

  load_env
  $START_BACKEND && start_backend
  $START_FRONTEND && start_frontend

  printf '\ndev environment is ready\n'
  $START_BACKEND && printf '  backend:  http://localhost:%s/\n' "$BACKEND_PORT"
  $START_FRONTEND && printf '  frontend: http://localhost:%s/\n' "$FRONTEND_PORT"
  printf '  stop:     BACKEND_PORT=%s FRONTEND_PORT=%s ./dev.sh --stop\n' "$BACKEND_PORT" "$FRONTEND_PORT"
}

main "$@"

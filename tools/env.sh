#!/usr/bin/env bash
# Общая база для всех скриптов tools/: тулчейн + пути + помощники.
# Подключается через `source`, самостоятельно не запускается.

set -euo pipefail

TOOLS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$TOOLS_DIR/.." && pwd)"
BACKEND="$ROOT/backend"
FRONTEND="$ROOT/frontend"
RUN_DIR="$ROOT/.run"
TOOLCHAIN="${SPRINGBOOTCRM_TOOLCHAIN:-$HOME/.local/toolchain}"

# Порты. Внимание: frontend/src/api/config.ts зашивает бэкенд на :8080,
# поэтому смена BACKEND_PORT требует пересборки фронта либо правки
# window.__API_BASE__ в dist/index.html.
BACKEND_PORT="${BACKEND_PORT:-8080}"
DEV_PORT="${DEV_PORT:-5173}"
PREVIEW_PORT="${PREVIEW_PORT:-4173}"

mkdir -p "$RUN_DIR"

# --- вывод ---------------------------------------------------------------
if [[ -t 1 ]]; then
  C_OK=$'\033[32m'; C_ERR=$'\033[31m'; C_INF=$'\033[36m'; C_OFF=$'\033[0m'
else
  C_OK=''; C_ERR=''; C_INF=''; C_OFF=''
fi
info() { printf '%s==>%s %s\n' "$C_INF" "$C_OFF" "$*"; }
ok()   { printf '%s ok %s %s\n' "$C_OK"  "$C_OFF" "$*"; }
die()  { printf '%sERR%s %s\n' "$C_ERR" "$C_OFF" "$*" >&2; exit 1; }

# --- тулчейн -------------------------------------------------------------
activate_toolchain() {
  [[ -f "$TOOLCHAIN/env.sh" ]] || die "тулчейн не найден: $TOOLCHAIN/env.sh (см. docs/BUILD.md)"
  # shellcheck source=/dev/null
  source "$TOOLCHAIN/env.sh"
  command -v java >/dev/null || die "java недоступна после активации тулчейна"
}

# --- процессы ------------------------------------------------------------
pid_file() { echo "$RUN_DIR/$1.pid"; }
log_file() { echo "$RUN_DIR/$1.log"; }

is_up() {
  local f; f="$(pid_file "$1")"
  [[ -f "$f" ]] && kill -0 "$(cat "$f")" 2>/dev/null
}

port_busy() {
  (ss -ltn 2>/dev/null || netstat -ltn 2>/dev/null) | grep -q ":$1 "
}

# wait_http <url> <секунд> [имя сервиса] — ждёт любой HTTP-ответ (включая 401/403).
# Если передано имя и процесс умер — выходит сразу, не досиживая таймаут.
wait_http() {
  local url="$1" limit="${2:-90}" name="${3:-}" i=0
  while (( i < limit )); do
    curl -sS -o /dev/null -m 3 "$url" 2>/dev/null && return 0
    if [[ -n "$name" ]] && ! is_up "$name"; then
      info "$name завершился, не дождавшись ответа"; return 1
    fi
    sleep 1; i=$((i+1))
  done
  return 1
}

# spawn <имя> <рабочий каталог> <команда...> — фоновый запуск с pid/log.
#
# Сервис уходит в собственную сессию (setsid) со своими fd, поэтому не держит
# stdout вызывающего: `tools/start/all.sh | tee` завершается сразу, а не живёт
# до остановки сервиса. Pid пишет сам дочерний процесс перед exec, так что в
# файле лежит именно pid сервиса, а не промежуточной обёртки.
spawn() {
  local name="$1" cwd="$2"; shift 2
  if is_up "$name"; then
    info "$name уже запущен (pid $(cat "$(pid_file "$name")"))"; return 0
  fi
  local log pidf; log="$(log_file "$name")"; pidf="$(pid_file "$name")"
  : > "$log"; rm -f "$pidf"

  setsid bash -c '
    cd "$1" || exit 127
    echo $$ > "$2"
    shift 2
    exec "$@"
  ' _ "$cwd" "$pidf" "$@" </dev/null >>"$log" 2>&1 &

  local i=0
  while (( i < 50 )) && [[ ! -s "$pidf" ]]; do sleep 0.1; i=$((i+1)); done
  [[ -s "$pidf" ]] || { tail -20 "$log" >&2; die "$name не стартовал, лог: $log"; }
  sleep 0.5
  is_up "$name" || { tail -20 "$log" >&2; die "$name упал сразу после старта, лог: $log"; }
}

halt() {
  local name="$1" f; f="$(pid_file "$name")"
  if ! is_up "$name"; then rm -f "$f"; info "$name не запущен"; return 0; fi
  local pid; pid="$(cat "$f")"
  kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
  local i=0
  while (( i < 20 )) && kill -0 "$pid" 2>/dev/null; do sleep 0.5; i=$((i+1)); done
  kill -0 "$pid" 2>/dev/null && { kill -KILL -- "-$pid" 2>/dev/null || kill -KILL "$pid" 2>/dev/null; }
  rm -f "$f"
  ok "$name остановлен"
}

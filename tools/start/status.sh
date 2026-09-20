#!/usr/bin/env bash
# Стартер: что сейчас поднято.
source "$(dirname "${BASH_SOURCE[0]}")/../env.sh"

row() { # <имя> <порт> <url>
  local name="$1" port="$2" url="$3" state pid="-"
  if is_up "$name"; then
    pid="$(cat "$(pid_file "$name")")"
    state="${C_OK}UP  ${C_OFF}"
  elif port_busy "$port"; then
    state="${C_ERR}ЧУЖОЙ${C_OFF}"
  else
    state="${C_ERR}DOWN${C_OFF}"
  fi
  printf '  %-9s %b  порт %-5s pid %-8s %s\n' "$name" "$state" "$port" "$pid" "$url"
}

echo "SpringBootCRM — состояние:"
row backend  "$BACKEND_PORT" "http://localhost:$BACKEND_PORT"
row frontend "$DEV_PORT"     "http://localhost:$DEV_PORT"
row preview  "$PREVIEW_PORT" "http://localhost:$PREVIEW_PORT"
echo
echo "  логи: $RUN_DIR/*.log"

#!/usr/bin/env bash
# Стартер: backend (собранный jar) на $BACKEND_PORT, по умолчанию 8080.
# usage: backend.sh [--fg]   (--fg = на переднем плане, Ctrl+C для остановки)
source "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
activate_toolchain

JAR="$(ls -1 "$BACKEND"/target/*.jar 2>/dev/null | grep -v original | head -1 || true)"
if [[ -z "$JAR" ]]; then
  info "jar не найден — собираю"
  "$TOOLS_DIR/build/backend.sh"
  JAR="$(ls -1 "$BACKEND"/target/*.jar | grep -v original | head -1)"
fi

if is_up backend; then ok "backend уже поднят на :$BACKEND_PORT"; exit 0; fi
port_busy "$BACKEND_PORT" && die "порт $BACKEND_PORT занят другим процессом"

# БД: H2-файл в backend/data/, каталог должен существовать до старта Flyway.
mkdir -p "$BACKEND/data"

CMD=(java -Xmx2g -XX:+UseG1GC -Dfile.encoding=UTF-8
     "-Dserver.port=$BACKEND_PORT" -jar "$JAR")

if [[ "${1:-}" == "--fg" ]]; then
  info "backend на переднем плане, :$BACKEND_PORT"
  cd "$BACKEND" && exec "${CMD[@]}"
fi

info "запуск backend на :$BACKEND_PORT"
spawn backend "$BACKEND" "${CMD[@]}"
if wait_http "http://localhost:$BACKEND_PORT/actuator/health" 120 backend; then
  ok "backend: http://localhost:$BACKEND_PORT (лог: $(log_file backend))"
else
  tail -30 "$(log_file backend)" >&2
  die "backend не ответил за 120 с"
fi

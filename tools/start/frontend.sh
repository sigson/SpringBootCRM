#!/usr/bin/env bash
# Стартер: vite dev-server на $DEV_PORT (5173), с hot-reload.
# usage: frontend.sh [--fg]
source "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
activate_toolchain

[[ -d "$FRONTEND/node_modules" ]] || { info "node_modules нет — npm ci"; (cd "$FRONTEND" && npm ci); }

if is_up frontend; then ok "frontend уже поднят на :$DEV_PORT"; exit 0; fi
port_busy "$DEV_PORT" && die "порт $DEV_PORT занят другим процессом"

CMD=(npm run dev -- --port "$DEV_PORT" --host)

if [[ "${1:-}" == "--fg" ]]; then
  info "vite dev на переднем плане, :$DEV_PORT"
  cd "$FRONTEND" && exec "${CMD[@]}"
fi

info "запуск vite dev на :$DEV_PORT"
spawn frontend "$FRONTEND" "${CMD[@]}"
if wait_http "http://localhost:$DEV_PORT/" 60 frontend; then
  ok "frontend: http://localhost:$DEV_PORT (лог: $(log_file frontend))"
else
  tail -30 "$(log_file frontend)" >&2
  die "vite не ответил за 60 с"
fi

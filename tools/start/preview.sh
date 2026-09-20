#!/usr/bin/env bash
# Стартер: статический preview собранного dist/ на $PREVIEW_PORT (4173).
# Это прод-режим фронта — то, что реально отдаётся пользователю.
source "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
activate_toolchain

[[ -d "$FRONTEND/dist" ]] || { info "dist нет — собираю"; "$TOOLS_DIR/build/frontend.sh"; }

if is_up preview; then ok "preview уже поднят на :$PREVIEW_PORT"; exit 0; fi
port_busy "$PREVIEW_PORT" && die "порт $PREVIEW_PORT занят другим процессом"

info "запуск vite preview на :$PREVIEW_PORT"
spawn preview "$FRONTEND" npm run preview -- --port "$PREVIEW_PORT" --host
if wait_http "http://localhost:$PREVIEW_PORT/" 60 preview; then
  ok "preview: http://localhost:$PREVIEW_PORT (лог: $(log_file preview))"
else
  tail -30 "$(log_file preview)" >&2
  die "preview не ответил за 60 с"
fi

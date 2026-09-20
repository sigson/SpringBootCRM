#!/usr/bin/env bash
# Билдер: frontend -> frontend/dist/
# usage: frontend.sh [--clean]
source "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
activate_toolchain

cd "$FRONTEND"
if [[ "${1:-}" == "--clean" ]]; then
  info "очистка node_modules и dist"
  rm -rf node_modules dist
fi

if [[ ! -d node_modules ]]; then
  info "npm ci (node $(node -v))"
  npm ci
fi

info "vite build"
npm run build
ok "dist: $FRONTEND/dist ($(du -sh dist | cut -f1))"

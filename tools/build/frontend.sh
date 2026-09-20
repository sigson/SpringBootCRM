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

# Локальная сборка — для локального же бэкенда. Без этого vite подхватил бы
# frontend/.env.production, где зашит URL развёрнутого на Render бэкенда: он
# нужен, чтобы деплой собирался без настройки, но `build.sh up --prod` тогда
# открывал бы preview, ходящий не в тот jar, что рядом.
# Явное значение в окружении имеет приоритет над .env-файлом.
: "${VITE_API_BASE:=http://localhost:$BACKEND_PORT}"
export VITE_API_BASE

info "vite build (VITE_API_BASE=$VITE_API_BASE)"
npm run build
ok "dist: $FRONTEND/dist ($(du -sh dist | cut -f1))"

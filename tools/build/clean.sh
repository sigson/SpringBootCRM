#!/usr/bin/env bash
# Билдер: удалить артефакты сборки (target/, dist/). node_modules и БД не трогает.
source "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
rm -rf "$BACKEND/target" "$FRONTEND/dist"
ok "target/ и dist/ удалены"

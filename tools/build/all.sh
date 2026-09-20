#!/usr/bin/env bash
# Билдер: всё. usage: all.sh [--tests] [--clean]
source "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
D="$(dirname "${BASH_SOURCE[0]}")"
"$D/backend.sh"  "$@"
"$D/frontend.sh" "${@/--tests/}"
ok "сборка завершена"

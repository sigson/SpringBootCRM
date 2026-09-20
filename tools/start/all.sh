#!/usr/bin/env bash
# Стартер: клиент-сервер целиком.
#   all.sh            — backend + vite dev  (режим разработки)
#   all.sh --prod     — backend + preview собранного dist
source "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
D="$(dirname "${BASH_SOURCE[0]}")"

"$D/backend.sh"
if [[ "${1:-}" == "--prod" ]]; then "$D/preview.sh"; else "$D/frontend.sh"; fi

echo
"$D/status.sh"

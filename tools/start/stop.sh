#!/usr/bin/env bash
# Стартер: остановить всё (или выбранное). usage: stop.sh [backend|frontend|preview]
source "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
if [[ $# -gt 0 ]]; then
  for n in "$@"; do halt "$n"; done
else
  for n in frontend preview backend; do halt "$n"; done
fi

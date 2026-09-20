#!/usr/bin/env bash
# Единая точка входа. Полный набор — в tools/build (билдеры) и tools/start (стартеры).
#
#   ./build.sh                 собрать всё          ./build.sh up        поднять backend+frontend
#   ./build.sh backend         собрать backend      ./build.sh up --prod backend+preview dist
#   ./build.sh frontend        собрать frontend     ./build.sh down      остановить всё
#   ./build.sh test [--it]     тесты backend        ./build.sh status    что поднято
#   ./build.sh clean           снести target/dist   ./build.sh logs <имя> [-f]
set -euo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/tools"
cmd="${1:-all}"; shift || true
case "$cmd" in
  all|backend|frontend|test|clean) exec "$D/build/$cmd.sh" "$@" ;;
  up)     exec "$D/start/all.sh"    "$@" ;;
  down)   exec "$D/start/stop.sh"   "$@" ;;
  status) exec "$D/start/status.sh" "$@" ;;
  logs)   exec "$D/start/logs.sh"   "$@" ;;
  *) sed -n '2,10p' "${BASH_SOURCE[0]}" >&2; exit 2 ;;
esac

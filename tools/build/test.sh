#!/usr/bin/env bash
# Билдер: тесты backend.
#   test.sh          — всё: юнит (*Test, surefire) + интеграционные (*IT, failsafe)
#   test.sh --unit   — только юнит-тесты
#   test.sh --it     — только интеграционные
source "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
activate_toolchain
cd "$BACKEND"

case "${1:-}" in
  "")      info "maven verify (юнит + интеграционные)"; mvn -B -ntp verify ;;
  --unit)  info "maven test (только юнит)";             mvn -B -ntp test ;;
  --it)    info "maven verify (только интеграционные)"
           mvn -B -ntp verify -DskipTests=false -Dsurefire.skip=true ;;
  *)       die "неизвестный флаг: $1 (ожидается --unit или --it)" ;;
esac

#!/usr/bin/env bash
# Билдер: backend -> backend/target/springbootcrm-backend-*.jar
# usage: backend.sh [--tests] [--clean]
source "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
activate_toolchain

GOALS=(package); FLAGS=(-B -ntp -DskipTests)
for a in "$@"; do
  case "$a" in
    --tests) FLAGS=(-B -ntp) ;;
    --clean) GOALS=(clean package) ;;
    *) die "неизвестный флаг: $a" ;;
  esac
done

info "maven ${GOALS[*]} (java $(java -version 2>&1 | head -1 | cut -d'"' -f2))"
cd "$BACKEND"
mvn "${FLAGS[@]}" "${GOALS[@]}"
ok "jar: $(ls -1 "$BACKEND"/target/*.jar | grep -v original | head -1)"

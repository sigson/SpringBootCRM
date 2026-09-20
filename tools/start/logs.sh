#!/usr/bin/env bash
# Стартер: хвост логов. usage: logs.sh [backend|frontend|preview] [-f]
source "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
name="${1:-backend}"
f="$(log_file "$name")"
[[ -f "$f" ]] || die "лога нет: $f"
if [[ "${2:-}" == "-f" ]]; then tail -f "$f"; else tail -60 "$f"; fi

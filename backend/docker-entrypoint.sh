#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Translates a PaaS-style DATABASE_URL into the three properties Spring wants,
# then execs the jar.
#
# Render (like Heroku and Fly) hands out one variable:
#
#     postgresql://user:password@host:5432/dbname
#
# JDBC cannot parse that: the driver needs "jdbc:postgresql://host:5432/dbname"
# with the credentials passed separately. Doing the split here rather than in
# Java keeps it visible, and keeps the application free of deploy-target code.
#
# Explicit SPRING_DATASOURCE_URL always wins, so this is invisible to anyone
# who configures the datasource the normal way (including local `docker run`
# against H2).
# ---------------------------------------------------------------------------
set -euo pipefail

if [[ -z "${SPRING_DATASOURCE_URL:-}" && -n "${DATABASE_URL:-}" ]]; then
  url="$DATABASE_URL"

  case "$url" in
    postgres://*|postgresql://*)
      # Strip the scheme, then split on the LAST '@' — a password may contain
      # '@' (Render generates passwords that can), while a host never does.
      rest="${url#*://}"
      creds="${rest%@*}"
      hostpath="${rest##*@}"

      user="${creds%%:*}"
      pass=""
      [[ "$creds" == *:* ]] && pass="${creds#*:}"

      export SPRING_DATASOURCE_URL="jdbc:postgresql://${hostpath}"
      export SPRING_DATASOURCE_DRIVER="org.postgresql.Driver"
      export SPRING_DATASOURCE_USERNAME="$user"
      export SPRING_DATASOURCE_PASSWORD="$pass"

      # Never log the password; the host is useful when a deploy cannot connect.
      echo "entrypoint: datasource -> jdbc:postgresql://${hostpath%%\?*} (user ${user})"
      ;;
    jdbc:*)
      # Already a JDBC URL — pass it through untouched.
      export SPRING_DATASOURCE_URL="$url"
      echo "entrypoint: datasource -> DATABASE_URL used verbatim"
      ;;
    *)
      echo "entrypoint: DATABASE_URL has an unrecognised scheme, ignoring it" >&2
      ;;
  esac
fi

# shellcheck disable=SC2086  # JAVA_OPTS is a list of flags and must word-split.
exec java $JAVA_OPTS -jar /app/app.jar "$@"

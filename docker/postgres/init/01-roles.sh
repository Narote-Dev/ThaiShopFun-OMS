#!/bin/sh
# Local development only. Creates the three OMS roles before Flyway so a developer
# can log in as oms_migrator or oms_app. Passwords come from the container env.
# Defaults live in docker-compose.yml and are not for any shared environment.
# Flyway V1 still creates the roles if this script did not run (existing volume).
# oms_maint stays NOLOGIN.
set -eu

if [ -z "${OMS_MIGRATOR_PASSWORD:-}" ] || [ -z "${OMS_APP_PASSWORD:-}" ]; then
  echo "OMS_MIGRATOR_PASSWORD and OMS_APP_PASSWORD are required" >&2
  exit 1
fi

psql_q() {
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -tA -c "$1"
}

if [ "$(psql_q "SELECT 1 FROM pg_roles WHERE rolname = 'oms_migrator'")" != "1" ]; then
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v migrator_password="$OMS_MIGRATOR_PASSWORD" <<'SQL'
CREATE ROLE oms_migrator LOGIN PASSWORD :'migrator_password'
  NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
SQL
fi

if [ "$(psql_q "SELECT 1 FROM pg_roles WHERE rolname = 'oms_app'")" != "1" ]; then
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v app_password="$OMS_APP_PASSWORD" <<'SQL'
CREATE ROLE oms_app LOGIN PASSWORD :'app_password'
  NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
SQL
fi

if [ "$(psql_q "SELECT 1 FROM pg_roles WHERE rolname = 'oms_maint'")" != "1" ]; then
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
CREATE ROLE oms_maint NOLOGIN NOSUPERUSER BYPASSRLS NOCREATEDB NOCREATEROLE;
SQL
fi

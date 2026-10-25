#!/usr/bin/env bash
# Runs once, on the first start of an empty data volume (docker-entrypoint-initdb.d).
#
# Creates the control database and the two cell databases, with two login roles:
#   sched_owner  owns the "scheduler" schema; migrations and partition maintenance use it (DDL).
#   sched_app    is used by the services; it gets DML on tables created by sched_owner.
#
# Passwords come from the environment and reach SQL as psql variables (:'name'), which psql
# quotes as literals. They are never interpolated into SQL text by the shell.
set -euo pipefail

: "${KAIROS_DB_OWNER_PASSWORD:?KAIROS_DB_OWNER_PASSWORD is required}"
: "${KAIROS_DB_APP_PASSWORD:?KAIROS_DB_APP_PASSWORD is required}"

psql=(psql --no-psqlrc -v ON_ERROR_STOP=1 --username "$POSTGRES_USER")

"${psql[@]}" --dbname postgres \
  -v owner_password="$KAIROS_DB_OWNER_PASSWORD" \
  -v app_password="$KAIROS_DB_APP_PASSWORD" <<'SQL'
CREATE ROLE sched_owner LOGIN PASSWORD :'owner_password';
CREATE ROLE sched_app LOGIN PASSWORD :'app_password';
SQL

for db in kairos_control kairos_cell0 kairos_cell1; do
  "${psql[@]}" --dbname postgres -v db="$db" <<'SQL'
CREATE DATABASE :"db" OWNER sched_owner;
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE :"db" TO sched_app;
SQL

  "${psql[@]}" --dbname "$db" <<'SQL'
REVOKE ALL ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA scheduler AUTHORIZATION sched_owner;
GRANT USAGE ON SCHEMA scheduler TO sched_app;
ALTER DEFAULT PRIVILEGES FOR ROLE sched_owner IN SCHEMA scheduler
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO sched_app;
ALTER DEFAULT PRIVILEGES FOR ROLE sched_owner IN SCHEMA scheduler
  GRANT USAGE, SELECT ON SEQUENCES TO sched_app;
ALTER DEFAULT PRIVILEGES FOR ROLE sched_owner IN SCHEMA scheduler
  GRANT EXECUTE ON FUNCTIONS TO sched_app;
SQL
done

# search_path is per database and per role; set it for sched_app in every database.
for db in kairos_control kairos_cell0 kairos_cell1; do
  "${psql[@]}" --dbname postgres -v db="$db" <<'SQL'
ALTER ROLE sched_app IN DATABASE :"db" SET search_path = scheduler;
ALTER ROLE sched_owner IN DATABASE :"db" SET search_path = scheduler;
SQL
done

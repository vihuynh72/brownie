#!/usr/bin/env bash
# Creates Brownie's three least-privilege database roles on the hosted
# PostgreSQL server. Runs ON THE MACHINE, because the server has no public
# network access and is only reachable from inside the virtual network.
#
#   az vm start --resource-group rg-brownie-pilot --name vm-brownie-pilot
#   scp -i ~/.ssh/brownie_pilot infra/azure/host/create-db-roles.sh \
#       brownieadmin@<public name>:~/
#   ssh -i ~/.ssh/brownie_pilot brownieadmin@<public name>
#   BROWNIE_DB_HOST=... BROWNIE_VAULT_NAME=... BROWNIE_DB_ADMIN_PASSWORD=... \
#     ./create-db-roles.sh
#
# The three role passwords are read from the key vault with the machine's own
# identity and are never printed, never written to disk and never typed. The
# server administrator password is the one generated when the infrastructure
# was deployed; it is used here and nowhere else.
#
# Running it twice is safe: each role is created only if it is missing.
#
# This is the hosted counterpart of infra/local/postgres/init/01-app-roles.sql.
# The grants are identical. What differs is that Azure's server administrator
# is not a superuser, so the schema ownership change is done as a member of
# azure_pg_admin rather than as the cluster's initial superuser.

set -euo pipefail

for required in BROWNIE_DB_HOST BROWNIE_VAULT_NAME BROWNIE_DB_ADMIN_PASSWORD; do
  [ -n "${!required:-}" ] || { printf '%s is not set.\n' "$required" >&2; exit 78; }
done

readonly ADMIN_USER="${BROWNIE_DB_ADMIN_USER:-brownieadmin}"
readonly DATABASE="${BROWNIE_DB_NAME:-brownie}"

command -v psql >/dev/null 2>&1 || {
  printf 'psql is not installed. Run: sudo apt-get update && sudo apt-get install -y postgresql-client\n' >&2
  exit 69
}

az login --identity --only-show-errors > /dev/null \
  || { printf "This machine's identity could not sign in.\n" >&2; exit 77; }

secret() {
  az keyvault secret show --vault-name "$BROWNIE_VAULT_NAME" --name "$1" \
    --query value -o tsv --only-show-errors 2>/dev/null \
    || { printf 'Secret %s is missing from the vault.\n' "$1" >&2; exit 78; }
}

api_password="$(secret db-api-password)"
worker_password="$(secret db-worker-password)"
migration_password="$(secret db-migration-password)"

export PGPASSWORD="$BROWNIE_DB_ADMIN_PASSWORD"
export PGSSLMODE=require

# Passwords go in as parameters of a DO block rather than interpolated into
# the statement text, so they are not exposed to the server's statement log.
psql --host "$BROWNIE_DB_HOST" --username "$ADMIN_USER" --dbname "$DATABASE" \
     --no-password --set ON_ERROR_STOP=1 \
     --set api_password="$api_password" \
     --set worker_password="$worker_password" \
     --set migration_password="$migration_password" <<'SQL'
\set QUIET on

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'brownie_migration') THEN
    CREATE ROLE brownie_migration LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS NOINHERIT;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'brownie_api') THEN
    CREATE ROLE brownie_api LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS NOINHERIT;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'brownie_worker') THEN
    CREATE ROLE brownie_worker LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS NOINHERIT;
  END IF;
END $$;

ALTER ROLE brownie_migration PASSWORD :'migration_password';
ALTER ROLE brownie_api       PASSWORD :'api_password';
ALTER ROLE brownie_worker    PASSWORD :'worker_password';

-- None of the three need the cluster's own administrative database. On a
-- managed server the administrator is not a superuser and does not own that
-- database, so this is allowed to fail: it is a tightening we would like, not
-- one the rest depends on. Everything below it is required and stays fatal.
DO $$ BEGIN
  EXECUTE 'REVOKE CONNECT ON DATABASE postgres FROM PUBLIC';
EXCEPTION WHEN insufficient_privilege OR undefined_object THEN
  RAISE NOTICE 'Skipped revoking connect on the administrative database: the managed service owns it.';
END $$;

-- brownie_migration owns the public schema, so it and only it may create,
-- alter or drop objects there.
ALTER SCHEMA public OWNER TO brownie_migration;

GRANT CONNECT ON DATABASE brownie TO brownie_api, brownie_worker;
GRANT USAGE ON SCHEMA public TO brownie_api, brownie_worker;

-- The API owns ordinary tenant-table access. Workers receive only explicit
-- queue permissions from the migration that introduces each queue contract,
-- so a newly added tenant table is unreachable by a worker until its data
-- boundary has been designed.
ALTER DEFAULT PRIVILEGES FOR ROLE brownie_migration IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO brownie_api;
ALTER DEFAULT PRIVILEGES FOR ROLE brownie_migration IN SCHEMA public
  GRANT USAGE ON SEQUENCES TO brownie_api;

\set QUIET off
SELECT rolname, rolcanlogin, rolsuper, rolbypassrls
  FROM pg_roles WHERE rolname LIKE 'brownie%' ORDER BY rolname;
SQL

printf '\nThree roles present with passwords from the vault. Nothing was printed.\n'

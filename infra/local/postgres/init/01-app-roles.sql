-- Bootstrap script for a fresh local/test Postgres instance. It runs exactly
-- once, automatically, the first time the container starts (standard
-- postgres-image convention for anything under docker-entrypoint-initdb.d),
-- executed as the cluster's initial superuser.
--
-- No application process ever connects using that initial superuser. Its
-- only job is to create Brownie's own three least-privilege roles below and
-- then get out of the way.
--
-- Passwords here are fixed, local-only placeholders, not secrets: this
-- script only ever runs inside a disposable local Postgres container
-- (Testcontainers in tests, Compose for local development). Hosted
-- production credentials are a separate, later decision, not made here.

CREATE ROLE brownie_migration LOGIN PASSWORD 'brownie_migration_local_only'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS NOINHERIT;

CREATE ROLE brownie_api LOGIN PASSWORD 'brownie_api_local_only'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS NOINHERIT;

CREATE ROLE brownie_worker LOGIN PASSWORD 'brownie_worker_local_only'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS NOINHERIT;

-- Every role is implicitly a member of PUBLIC, which Postgres grants
-- CONNECT on every database to by default -- including the cluster's own
-- administrative "postgres" database, which has nothing to do with this
-- application. None of the three roles above need it, so it is revoked
-- explicitly rather than left as an unused, unexamined default.
REVOKE CONNECT ON DATABASE postgres FROM PUBLIC;

-- brownie_migration owns the public schema, so it -- and only it -- may
-- create, alter, or drop objects there. It is not a superuser: it cannot
-- bypass row-level security or change cluster-wide settings.
ALTER SCHEMA public OWNER TO brownie_migration;

GRANT CONNECT ON DATABASE brownie TO brownie_api, brownie_worker;
GRANT USAGE ON SCHEMA public TO brownie_api, brownie_worker;

-- Any table or sequence brownie_migration creates from this point on
-- automatically grants ordinary read/write to both application roles.
-- Every later migration relies on this rule already being in place; none of
-- them need to repeat it.
ALTER DEFAULT PRIVILEGES FOR ROLE brownie_migration IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO brownie_api, brownie_worker;
ALTER DEFAULT PRIVILEGES FOR ROLE brownie_migration IN SCHEMA public
  GRANT USAGE ON SEQUENCES TO brownie_api, brownie_worker;

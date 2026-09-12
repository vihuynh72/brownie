# Brownie

Brownie is a personal project for building and reviewing source-backed
documents.

## Run

Check the pinned local toolchain with:

```sh
./scripts/verify-toolchain.sh
```

Start local Postgres (loopback-only, with the app's own least-privilege
roles already created):

```sh
docker compose -f infra/local/compose.yaml up -d --wait
```

The first time only, copy `.env.example` to `.env` and put any real
values there, never in `.env.example` itself. Then, from the repository
root:

```sh
cp .env.example .env
set -o allexport
source .env
set +o allexport
cd backend && ./mvnw -pl brownie-api spring-boot:run
```

The API listens on 8081, matching `BROWNIE_PUBLIC_ORIGIN` and the callback
registered with the identity provider; health checks answer separately on
8090 (`curl http://localhost:8090/actuator/health`).

Login (`BROWNIE_OIDC_ISSUER`/`CLIENT_ID`/`CLIENT_SECRET`) requires an Entra
External ID tenant and app registration that only the project owner can
create. Until those three are set in `.env`,
`brownie-api` will fail to start on the `local` profile; the `test` profile
used by `mvnw test`/`verify` does not need them. Once configured:

- Start login: open `http://localhost:8081/oauth2/authorization/entra`.
- Current identity and workspace memberships: `GET /api/v1/me` (401 until
  logged in). A personal workspace is created automatically the first time
  each identity logs in.
- Log out: `POST /logout` (needs the `XSRF-TOKEN` cookie echoed back as an
  `X-XSRF-TOKEN` header, like any other mutation -- see CSRF below).

## Identity

Brownie authenticates one kind of principal: customers signing in through
the Entra External ID tenant configured above. That is the only identity
provider integration in this codebase --
there is no separate staff or admin login path today, and every row in
`user_identity` belongs to a customer.

The Microsoft/Azure account used to administer that tenant (create the
app registration, manage billing, deploy resources) is a different,
workforce identity, used only in the Azure and Entra admin portals,
outside the running application. Brownie's code has no route, no
configuration value, and no database table that references it, and it
must stay that way: a staff-facing feature, if one is ever built, needs
its own separate sign-in path rather than a shortcut through the
customer registration or an elevated `WorkspaceRole`.

Which Spring profile is active can't be influenced from outside
`BROWNIE_ENVIRONMENT` itself: `BrownieEnvironmentListener` sets it as the
*only* active profile before any configuration file is even read,
overwriting whatever else -- a stray `SPRING_PROFILES_ACTIVE` left in the
environment, for instance -- might already be set. A production run
can't end up with the `test` profile's placeholder OIDC credentials
active alongside it, since only one profile is ever active, and
`production`, `pilot`, and `local` all require `BROWNIE_OIDC_ISSUER` with
no fallback value, so a missing real tenant fails startup outright
instead of silently running against nothing.

Stop Postgres, keeping its data for next time:

```sh
docker compose -f infra/local/compose.yaml down
```

Stop it and deliberately discard all local data (also drops the roles
above, since they are only recreated on a truly empty volume):

```sh
docker compose -f infra/local/compose.yaml down -v
```

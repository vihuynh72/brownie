# Brownie

Brownie is a personal project for building and reviewing source-backed meeting
minutes.

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
create -- see the master plan §18.6. Until those three are set in `.env`,
`brownie-api` will fail to start on the `local` profile; the `test` profile
used by `mvnw test`/`verify` does not need them. Once configured:

- Start login: open `http://localhost:8081/oauth2/authorization/entra`.
- Current identity: `GET /api/v1/me` (401 until logged in).
- Log out: `POST /logout` (needs the `XSRF-TOKEN` cookie echoed back as an
  `X-XSRF-TOKEN` header, like any other mutation -- see CSRF below).

Stop Postgres, keeping its data for next time:

```sh
docker compose -f infra/local/compose.yaml down
```

Stop it and deliberately discard all local data (also drops the roles
above, since they are only recreated on a truly empty volume):

```sh
docker compose -f infra/local/compose.yaml down -v
```

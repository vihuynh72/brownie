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

Then, from `backend/`, with `.env.example` copied to `.env` and loaded
into your shell:

```sh
cp ../.env.example ../.env   # first time only; edit values there, not here
set -o allexport && source ../.env && set +o allexport
./mvnw -pl brownie-api spring-boot:run
```

The API listens on Spring Boot's default port, 8080; health checks answer
separately on 8090 (`curl http://localhost:8090/actuator/health`).

Stop Postgres, keeping its data for next time:

```sh
docker compose -f infra/local/compose.yaml down
```

Stop it and deliberately discard all local data (also drops the roles
above, since they are only recreated on a truly empty volume):

```sh
docker compose -f infra/local/compose.yaml down -v
```

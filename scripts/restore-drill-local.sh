#!/usr/bin/env bash
# Rehearses a restore without touching the development stack. It brings a
# backup's database up in a throwaway Postgres of its own, ends every
# session in it, applies the deletions recorded outside the database, and
# reports what that changed. Nothing is ever served from the restored
# database. The order practised here is the order a real restore must
# follow: restore, end sessions, apply the deletion record, check, and only
# then let anyone in.
#
#   ./scripts/restore-drill-local.sh <backup-directory> [--keep]
#
# Needs the blob store that holds the deletion record to be running. That
# is the development stack's unless BROWNIE_LOCAL_STORAGE_CONNECTION says
# otherwise, and it must be the store of the stack the backup came from: a
# record from another stack names other people's ids. Also needs
# brownie-worker to be built, or BROWNIE_WORKER_JAR to name a built jar:
#   (cd backend && ./mvnw -q -pl brownie-worker -am package -DskipTests)
#
# The replay only queues stored files for removal in the throwaway
# database; it never removes a file from the blob store it reads.

set -euo pipefail

script_dir="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
repository_root="$(CDPATH='' cd -- "$script_dir/.." && pwd)"

if [ $# -lt 1 ] || [ $# -gt 2 ] || { [ $# -eq 2 ] && [ "$2" != "--keep" ]; }; then
  printf 'usage: %s <backup-directory> [--keep]\n' "$0" >&2
  exit 64
fi
backup="$1"
keep="${2:-}"
project="brownie-restore-drill"
port="${BROWNIE_DRILL_DB_PORT:-25432}"

if [ ! -f "$backup/brownie.dump" ]; then
  printf '%s/brownie.dump not found; that directory is not a backup made by backup-local.sh.\n' "$backup" >&2
  exit 66
fi
backup="$(CDPATH='' cd -- "$backup" && pwd)"

build_hint='(cd backend && ./mvnw -q -pl brownie-worker -am package -DskipTests)'
worker_jar="${BROWNIE_WORKER_JAR:-}"
if [ -z "$worker_jar" ]; then
  target_dir="$repository_root/backend/brownie-worker/target"
  jar_count=0
  if [ -d "$target_dir" ]; then
    for candidate in "$target_dir"/brownie-worker-*.jar; do
      [ -f "$candidate" ] || continue
      case "$candidate" in *original*) continue ;; esac
      worker_jar="$candidate"
      jar_count=$((jar_count + 1))
    done
  fi
  if [ "$jar_count" -gt 1 ]; then
    printf 'More than one brownie-worker jar is in %s; name the one to use with BROWNIE_WORKER_JAR.\n' "$target_dir" >&2
    exit 69
  fi
fi
if [ -z "$worker_jar" ] || [ ! -f "$worker_jar" ]; then
  printf 'brownie-worker is not built. Run: %s\n' "$build_hint" >&2
  exit 69
fi
# A worker built before it could replay ignores the mode it is given and starts serving: it would claim jobs from
# the restored database and work on the blob store it was only meant to read. It is never started.
# Listed into a variable, not piped into a search: a search that stops at its first match ends the pipe early, and
# with pipefail that reads as a failure, which would refuse every good jar.
jar_listing="$(unzip -Z1 "$worker_jar" 2>/dev/null || true)"
case "$jar_listing" in
  *worker/retention/DeletionReplayRunner.class*) ;;
  *)
    printf '%s was built before the worker could replay deletions and would start serving instead. Rebuild it: %s\n' "$worker_jar" "$build_hint" >&2
    exit 69
    ;;
esac

java_bin="java"
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  java_bin="$JAVA_HOME/bin/java"
fi

if [ -n "$(docker compose -p "$project" ps -aq 2>/dev/null)" ]; then
  printf 'An earlier drill was kept and is still there. Look at it or remove it first: docker compose -p %s down -v\n' "$project" >&2
  exit 75
fi

work_dir="$(mktemp -d)"
cat > "$work_dir/compose.yaml" <<YAML
services:
  postgres:
    image: postgres:17
    environment:
      POSTGRES_DB: brownie
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres_bootstrap_only
    ports:
      - "127.0.0.1:${port}:5432"
    volumes:
      - ${repository_root}/infra/local/postgres/init:/docker-entrypoint-initdb.d:ro
    healthcheck:
      # Over TCP on purpose: while the image sets itself up it runs a temporary server that answers on its socket
      # only, before the roles this restore needs exist.
      test: ["CMD-SHELL", "pg_isready -h 127.0.0.1 -U postgres -d brownie"]
      interval: 2s
      timeout: 5s
      retries: 30
YAML
drill=(docker compose -p "$project" -f "$work_dir/compose.yaml")
sql() {
  "${drill[@]}" exec -T postgres psql -U postgres -d brownie -v ON_ERROR_STOP=1 -At -c "$1"
}
finish() {
  if [ "$keep" = "--keep" ]; then
    printf 'Kept: %s (remove with: docker compose -p %s -f %s down -v)\n' "$project" "$project" "$work_dir/compose.yaml"
  else
    "${drill[@]}" down -v > /dev/null 2>&1 || true
    rm -rf "$work_dir"
  fi
}
trap finish EXIT

printf '1/5 Starting a throwaway database on 127.0.0.1:%s...\n' "$port"
if ! "${drill[@]}" up -d --wait > "$work_dir/compose-up.log" 2>&1; then
  printf 'The throwaway database did not start:\n' >&2
  tail -n 15 "$work_dir/compose-up.log" >&2
  exit 1
fi

printf '2/5 Restoring %s (taken %s)...\n' "$backup/brownie.dump" "$(cat "$backup/taken-at" 2>/dev/null || printf 'at an unrecorded time')"
"${drill[@]}" exec -T postgres pg_restore -U postgres -d brownie --exit-on-error < "$backup/brownie.dump"

printf '3/5 Ending every session the backup held...\n'
sql "SET client_min_messages = warning; TRUNCATE spring_session CASCADE" > /dev/null

documents_before="$(sql "SELECT count(*) FROM document")"
workspaces_before="$(sql "SELECT count(*) FROM workspace")"

storage_connection="${BROWNIE_LOCAL_STORAGE_CONNECTION:-DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;BlobEndpoint=http://127.0.0.1:10000/devstoreaccount1;}"
record_endpoint="$(printf '%s' "$storage_connection" | tr ';' '\n' | sed -n 's/^BlobEndpoint=//p')"
printf '4/5 Applying the deletions recorded at %s...\n' "${record_endpoint:-the configured blob store}"
replay_log="$work_dir/replay.log"
set +e
BROWNIE_ENVIRONMENT=local \
BROWNIE_WORKER_MODE=replay-deletions \
BROWNIE_DB_URL="jdbc:postgresql://127.0.0.1:${port}/brownie" \
BROWNIE_DB_USERNAME=brownie_worker \
BROWNIE_DB_PASSWORD=brownie_worker_local_only \
BROWNIE_LOCAL_STORAGE_CONNECTION="$storage_connection" \
BROWNIE_OPENAI_API_KEY="${BROWNIE_OPENAI_API_KEY:-not-used-when-replaying}" \
  "$java_bin" -jar "$worker_jar" > "$replay_log" 2>&1
replay_status=$?
set -e
summary="$(grep -a '^DELETION_REPLAY ' "$replay_log" | tail -n 1 || true)"
if [ "$replay_status" -ne 0 ] || [ -z "$summary" ]; then
  printf 'The replay did not finish (status %s). A database in this state must not be served.\n' "$replay_status" >&2
  grep -a -E '"level":"(ERROR|WARN)"' "$replay_log" | tail -n 5 >&2 || true
  printf 'Full log: %s\n' "$replay_log" >&2
  keep="--keep"
  exit 1
fi
printf '    %s\n' "$summary"
case "$summary" in
  *" entries=0 "*)
    printf '    No recorded deletions were found there. If the stack this backup came from keeps its files somewhere\n'
    printf '    else, that is the wrong place to look: name the right one with BROWNIE_LOCAL_STORAGE_CONNECTION.\n'
    ;;
esac

printf '5/5 What the restored database holds now:\n'
printf '    documents:  %s before the replay, %s after\n' "$documents_before" "$(sql "SELECT count(*) FROM document")"
printf '    workspaces: %s before the replay, %s after\n' "$workspaces_before" "$(sql "SELECT count(*) FROM workspace")"
printf '    sessions:   %s\n' "$(sql "SELECT count(*) FROM spring_session")"
printf '    deletions waiting for the worker to finish them: %s\n' "$(sql "SELECT count(*) FROM deletion_request WHERE state = 'PURGED'")"
printf 'Drill complete. In a real restore this is the point at which the API may be started.\n'

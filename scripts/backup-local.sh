#!/usr/bin/env bash
# Takes a backup of the local development stack: the whole database, and
# the stored files.
#
#   ./scripts/backup-local.sh <new-or-empty-directory>
#
# The database is dumped while it runs (pg_dump reads one consistent
# snapshot). The blob store is stopped for the few seconds its files are
# copied, because its index is only complete on disk when it is not
# running; uploads and downloads fail for that long.
#
# A backup holds everything as of now, including whatever is deleted for
# good afterwards. Restoring one is therefore never the last step: the
# deletions recorded outside the database have to be applied to it before
# anyone is let in. restore-drill-local.sh rehearses exactly that.
#
# BROWNIE_COMPOSE_FILE and BROWNIE_COMPOSE_PROJECT point this at a stack
# other than infra/local/compose.yaml under its default project name.

set -euo pipefail

script_dir="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
repository_root="$(CDPATH='' cd -- "$script_dir/.." && pwd)"

if [ $# -ne 1 ]; then
  printf 'usage: %s <backup-directory>\n' "$0" >&2
  exit 64
fi

compose_file="${BROWNIE_COMPOSE_FILE:-$repository_root/infra/local/compose.yaml}"
compose=(docker compose -f "$compose_file")
if [ -n "${BROWNIE_COMPOSE_PROJECT:-}" ]; then
  compose=(docker compose -p "$BROWNIE_COMPOSE_PROJECT" -f "$compose_file")
fi

target="$1"
if [ -e "$target" ] && [ -n "$(ls -A "$target" 2>/dev/null)" ]; then
  printf '%s already has something in it; a backup is never written over another.\n' "$target" >&2
  exit 73
fi
mkdir -p "$target"
target="$(CDPATH='' cd -- "$target" && pwd)"

azurite_container="$("${compose[@]}" ps -q azurite)"
if [ -z "$azurite_container" ] || [ -z "$("${compose[@]}" ps -q postgres)" ]; then
  printf 'The stack is not running; start it first: %s up -d\n' "${compose[*]}" >&2
  exit 69
fi

printf 'Dumping the database...\n'
"${compose[@]}" exec -T postgres pg_dump -U postgres -d brownie --format=custom > "$target/brownie.dump"

printf 'Copying stored files (the blob store stops for a moment)...\n'
restart_blob_store() {
  if ! "${compose[@]}" start azurite > "$target/.blob-store-restart.log" 2>&1; then
    printf 'The blob store did not come back. Start it by hand: %s start azurite\n' "${compose[*]}" >&2
    cat "$target/.blob-store-restart.log" >&2
    return 1
  fi
  rm -f "$target/.blob-store-restart.log"
}
trap restart_blob_store EXIT
if ! "${compose[@]}" stop azurite > "$target/.blob-store-stop.log" 2>&1; then
  printf 'The blob store could not be stopped, so its files were not copied:\n' >&2
  cat "$target/.blob-store-stop.log" >&2
  exit 1
fi
rm -f "$target/.blob-store-stop.log"
docker cp "$azurite_container:/data" "$target/azurite-data" > /dev/null
trap - EXIT
restart_blob_store

date -u +"%Y-%m-%dT%H:%M:%SZ" > "$target/taken-at"
printf 'Backup written to %s\n' "$target"

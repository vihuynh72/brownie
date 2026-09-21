#!/usr/bin/env bash
# Checks the things that can only be seen from the machine itself: that every
# container is healthy, that both programs say they are ready and mean it,
# that the renderer really is confined, and that the database refuses an
# unencrypted connection.
#
#   ./scripts/verify-deployment-on-host.sh
#
# Run it on the deployed machine, next to /etc/brownie. It reads configuration
# and never prints a secret. BROWNIE_CONFIG_DIR points it at a rehearsal's
# configuration instead of the deployed one.

set -uo pipefail

config_dir="${BROWNIE_CONFIG_DIR:-/etc/brownie}"
compose_file="${BROWNIE_COMPOSE_FILE:-$config_dir/compose.yaml}"
project="${BROWNIE_COMPOSE_PROJECT:-brownie}"

passed=0
failed=0
pass() { printf '  ok    %s\n' "$1"; passed=$((passed + 1)); }
fail() { printf '  FAIL  %s\n' "$1"; [ $# -gt 1 ] && printf '        %s\n' "$2"; failed=$((failed + 1)); }
section() { printf '\n%s\n' "$1"; }

setting() { grep -m1 "^$1=" "$config_dir/api.env" 2>/dev/null | cut -d= -f2-; }

# The compose file names its images and its host through variables, and the
# deployment wrote them next to it. Without them every "docker compose ps"
# below quietly finds nothing and this script reports a healthy deployment as
# broken -- which is worse than reporting nothing at all.
if [ -f "$config_dir/images.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$config_dir/images.env"
  set +a
elif [ -z "${BROWNIE_API_IMAGE:-}" ]; then
  printf 'No %s and no BROWNIE_API_IMAGE in the environment: the compose file cannot be read, so nothing below would mean anything.\n' \
    "$config_dir/images.env" >&2
  exit 78
fi

compose=(docker compose -p "$project" -f "$compose_file")

section 'Containers'
for service in web api worker clamav; do
  id="$("${compose[@]}" ps -q "$service" 2>/dev/null | head -1)"
  if [ -z "$id" ]; then
    fail "$service is running" "no container"
    continue
  fi
  state="$(docker inspect "$id" --format '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}')"
  case "$state" in
    "running healthy"|"running no-healthcheck") pass "$service: $state" ;;
    *) fail "$service is healthy" "$state" ;;
  esac
done

section 'Both programs say they are ready'
api_id="$("${compose[@]}" ps -q api 2>/dev/null | head -1)"
worker_id="$("${compose[@]}" ps -q worker 2>/dev/null | head -1)"
probe() {
  local id="$1" port="$2" what="$3"
  [ -n "$id" ] || { fail "$what answers its readiness probe" "no container"; return; }
  local code
  code="$(docker exec "$id" curl -s -o /dev/null -w '%{http_code}' -m 20 "http://127.0.0.1:$port/actuator/health/readiness" 2>/dev/null)"
  if [ "$code" = "200" ]; then
    pass "$what is ready (its readiness includes the database and the blob store)"
  else
    fail "$what is ready" "the probe answered ${code:-nothing}"
  fi
}
probe "$api_id" 8090 "the API"
probe "$worker_id" 8091 "the worker"

section 'The renderer is confined'
render_image="$(setting BROWNIE_RENDER_IMAGE)"
expected_id="$(setting BROWNIE_RENDER_EXPECTED_IMAGE_ID)"
if [ -z "$render_image" ]; then
  fail "the renderer image is configured" "BROWNIE_RENDER_IMAGE is not in $config_dir/api.env"
else
  actual_id="$(docker image inspect --format '{{.Id}}' "$render_image" 2>/dev/null)"
  if [ -z "$actual_id" ]; then
    fail "the renderer image is on this machine" "$render_image is not present"
  elif [ -n "$expected_id" ] && [ "$actual_id" != "$expected_id" ]; then
    fail "the image present is the approved one" "present $actual_id, approved $expected_id"
  else
    pass "the renderer image is present and is the approved one"

    # The same flags a render is started with. If any of these three stopped
    # being true, a document parser would be running with more than it needs.
    if docker run --rm --network none --entrypoint sh "$render_image" \
        -c 'getent hosts registry-1.docker.io >/dev/null 2>&1 && exit 1; exit 0' >/dev/null 2>&1; then
      pass "a renderer container has no network"
    else
      fail "a renderer container has no network" "it resolved a name on the internet"
    fi

    if docker run --rm --network none --read-only --tmpfs /tmp:rw,size=8m --entrypoint sh "$render_image" \
        -c 'touch /should-not-be-writable 2>/dev/null && exit 1; exit 0' >/dev/null 2>&1; then
      pass "a renderer container cannot write outside its own temporary space"
    else
      fail "a renderer container cannot write outside its own temporary space"
    fi

    who="$(docker run --rm --network none --entrypoint sh "$render_image" -c 'id -u' 2>/dev/null)"
    if [ -n "$who" ] && [ "$who" != "0" ]; then
      pass "a renderer container does not run as root (uid $who)"
    else
      fail "a renderer container does not run as root" "uid ${who:-unknown}"
    fi

    if docker run --rm --network none --entrypoint sh "$render_image" \
        -c 'env | grep -q "^BROWNIE_" && exit 1; test -S /var/run/docker.sock && exit 1; exit 0' >/dev/null 2>&1; then
      pass "a renderer container is given no configuration of ours and no container runtime"
    else
      fail "a renderer container is given no configuration of ours and no container runtime"
    fi
  fi
fi

section 'The database'
db_url="$(setting BROWNIE_DB_URL)"
case "$db_url" in
  *sslmode=require*|*sslmode=verify*) pass "the API is configured to require an encrypted connection" ;;
  "") fail "the database URL is configured" ;;
  *) fail "the API requires an encrypted database connection" "the URL does not ask for one" ;;
esac

section 'The two stores are two'
files="$(setting BROWNIE_STORAGE_ENDPOINT)"
record="$(setting BROWNIE_DELETION_RECORD_STORAGE_ENDPOINT)"
if [ -z "$files" ] && [ -z "$record" ]; then
  printf '  note  this deployment uses local storage; there is nothing to separate\n'
elif [ -n "$files" ] && [ -n "$record" ] && [ "$files" != "$record" ]; then
  pass "the record of deletions is in a different account from the files"
else
  fail "the record of deletions is in a different account from the files" "files='$files' record='$record'"
fi

section 'Only the proxy is published'
published="$(docker ps --filter "label=com.docker.compose.project=$project" --format '{{.Names}} {{.Ports}}' | grep -E '0\.0\.0\.0|:::' || true)"
if [ -z "$published" ]; then
  printf '  note  nothing is published to every interface; if this machine serves the public, the proxy should be\n'
else
  unexpected="$(printf '%s\n' "$published" | grep -v -- '-web-' || true)"
  if [ -z "$unexpected" ]; then
    pass "only the proxy is reachable from outside this machine"
  else
    fail "only the proxy is reachable from outside this machine" "$(printf '%s' "$unexpected" | tr '\n' ' ')"
  fi
fi

printf '\n%s passed, %s failed\n' "$passed" "$failed"
[ "$failed" -eq 0 ] || exit 1

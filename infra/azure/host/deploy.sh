#!/usr/bin/env bash
# Brings the deployed machine to a named set of images. Runs on the host
# itself, sent there by the deployment workflow, which prefixes it with the
# values below. Nothing here is specific to a particular release: the images
# are content addresses handed in, so running this twice with the same ones
# changes nothing.
#
# It expects, in the environment:
#   BROWNIE_REGISTRY                 registry login server
#   BROWNIE_API_IMAGE                full reference with @sha256:...
#   BROWNIE_WORKER_IMAGE             full reference with @sha256:...
#   BROWNIE_WEB_IMAGE                full reference with @sha256:...
#   BROWNIE_RENDER_IMAGE             full reference with @sha256:...
#   BROWNIE_VAULT_NAME               key vault holding the five secrets
#   BROWNIE_SERVER_NAME              the public name, also the certificate's
#   BROWNIE_DB_HOST                  private name of the database server
#   BROWNIE_STORAGE_ENDPOINT         blob endpoint for people's files
#   BROWNIE_DELETION_RECORD_ENDPOINT blob endpoint for the deletion record
#   BROWNIE_OIDC_ISSUER              the tenant's own issuer
#   BROWNIE_OIDC_CLIENT_ID           the registered application
#   BROWNIE_COMPOSE_B64              the compose file, base64
#   BROWNIE_INVITED_ADDRESSES        who may sign in at all, comma separated
#   BROWNIE_SUPPORT_CONTACT          optional; shown to people on their data page
#   BROWNIE_CERTIFICATE_CONTACT      optional; expiry warnings from the authority
#   BROWNIE_GOOGLE_CLIENT_ID         optional; lets people connect a Google
#                                    account, and then two more secrets are
#                                    read from the vault
#   BROWNIE_GOOGLE_DRIVE_OFFERED     optional, true or false (the default);
#                                    lets people pick Google Drive files, once
#                                    the API can read them, and only with a
#                                    Google client id
#
# The order matters and is the point of the script: migrate first with the new
# image and stop if that fails, so a failed migration leaves the running
# release untouched; then start the new containers; then check them; and if
# the check fails, put the previous images back.

set -euo pipefail

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }

# How long the containers get to become healthy. Generous on purpose: on a
# machine that has never run this before, the virus scanner downloads its
# signature databases before it answers anything, which takes minutes. A
# tighter limit would make the very first deployment time out and try to roll
# back to a release that does not exist yet.
readonly HEALTHY_TIMEOUT_SECONDS="${BROWNIE_HEALTHY_TIMEOUT_SECONDS:-900}"
fail() { log "FAILED: $*"; exit 1; }

for required in BROWNIE_REGISTRY BROWNIE_API_IMAGE BROWNIE_WORKER_IMAGE BROWNIE_WEB_IMAGE \
                BROWNIE_RENDER_IMAGE BROWNIE_VAULT_NAME BROWNIE_SERVER_NAME BROWNIE_DB_HOST \
                BROWNIE_STORAGE_ENDPOINT BROWNIE_DELETION_RECORD_ENDPOINT BROWNIE_OIDC_ISSUER \
                BROWNIE_OIDC_CLIENT_ID BROWNIE_COMPOSE_B64; do
  [ -n "${!required:-}" ] || fail "$required is not set."
done

# Not fatal, but worth saying out loud: with the gate on and nobody invited,
# which is what a hosted deployment defaults to, no one can sign in -- not
# even whoever is deploying it.
if [ -z "${BROWNIE_INVITED_ADDRESSES:-}" ]; then
  log "WARNING: BROWNIE_INVITED_ADDRESSES is empty, so nobody will be able to sign in to this deployment."
fi

install -d -m 0750 /etc/brownie
printf '%s' "$BROWNIE_SERVER_NAME" > /etc/brownie/server-name
printf '%s' "${BROWNIE_CERTIFICATE_CONTACT:-}" > /etc/brownie/certificate-contact
printf '%s' "$BROWNIE_COMPOSE_B64" | base64 -d > /etc/brownie/compose.yaml

log "Signing in with this machine's own identity."
az login --identity --only-show-errors > /dev/null || fail "This machine's identity could not sign in."
az acr login --name "${BROWNIE_REGISTRY%%.*}" --only-show-errors > /dev/null || fail "Could not sign in to the registry."

secret() {
  az keyvault secret show --vault-name "$BROWNIE_VAULT_NAME" --name "$1" \
    --query value -o tsv --only-show-errors 2>/dev/null || fail "Secret $1 is missing from the vault."
}

log "Reading the five secrets."
db_api_password="$(secret db-api-password)"
db_worker_password="$(secret db-worker-password)"
db_migration_password="$(secret db-migration-password)"
oidc_client_secret="$(secret oidc-client-secret)"
openai_api_key="$(secret openai-api-key)"

# Connecting Google accounts is optional. With a client id it needs its secret
# and the key that encrypts the tokens Google hands back, and a deployment that
# names the one without the others stops here, before anything changes, rather
# than starting an API that would refuse to start.
google_settings=()
# Anything but true or false would stop the API from starting after the
# database had already been migrated, so it is refused here, before that.
case "${BROWNIE_GOOGLE_DRIVE_OFFERED:-}" in
  ''|true|false) ;;
  *) fail "BROWNIE_GOOGLE_DRIVE_OFFERED must be true or false." ;;
esac
if [ -n "${BROWNIE_GOOGLE_CLIENT_ID:-}" ]; then
  log "Reading the two secrets Google connections need."
  google_client_secret="$(secret google-client-secret)"
  connector_token_key="$(secret connector-token-key)"
  google_settings=(
    "BROWNIE_GOOGLE_CLIENT_ID=${BROWNIE_GOOGLE_CLIENT_ID}"
    "BROWNIE_GOOGLE_CLIENT_SECRET=${google_client_secret}"
    "BROWNIE_CONNECTOR_TOKEN_KEY=${connector_token_key}")
  if [ -n "${BROWNIE_GOOGLE_DRIVE_OFFERED:-}" ]; then
    google_settings+=("BROWNIE_GOOGLE_DRIVE_OFFERED=${BROWNIE_GOOGLE_DRIVE_OFFERED}")
  fi
elif [ "${BROWNIE_GOOGLE_DRIVE_OFFERED:-}" = true ]; then
  log "BROWNIE_GOOGLE_DRIVE_OFFERED is set but Google is not; Drive stays off."
fi

log "Pulling images."
for image in "$BROWNIE_API_IMAGE" "$BROWNIE_WORKER_IMAGE" "$BROWNIE_WEB_IMAGE" "$BROWNIE_RENDER_IMAGE"; do
  docker pull --quiet "$image" > /dev/null || fail "Could not pull $image."
done

# What the API will refuse to run anything but. It is the image's own content
# address as this machine knows it, which is not the registry's manifest
# digest, so it is read here rather than passed in: a value computed anywhere
# else would never match and every render would be refused.
render_image_id="$(docker image inspect --format '{{.Id}}' "$BROWNIE_RENDER_IMAGE")"
[ -n "$render_image_id" ] || fail "Could not read the renderer image's content address."

database_url="jdbc:postgresql://${BROWNIE_DB_HOST}:5432/brownie?sslmode=require"

write_env() {
  local path="$1"
  shift
  install -m 0600 /dev/null "$path"
  printf '%s\n' "$@" > "$path"
}

log "Writing the configuration."
write_env /etc/brownie/api.env \
  "BROWNIE_ENVIRONMENT=pilot" \
  "BROWNIE_PUBLIC_ORIGIN=https://${BROWNIE_SERVER_NAME}" \
  "BROWNIE_WEB_ORIGIN=https://${BROWNIE_SERVER_NAME}" \
  "BROWNIE_DB_URL=${database_url}" \
  "BROWNIE_DB_USERNAME=brownie_api" \
  "BROWNIE_DB_PASSWORD=${db_api_password}" \
  "BROWNIE_DB_MIGRATION_USERNAME=brownie_migration" \
  "BROWNIE_DB_MIGRATION_PASSWORD=${db_migration_password}" \
  "BROWNIE_STORAGE_ENDPOINT=${BROWNIE_STORAGE_ENDPOINT}" \
  "BROWNIE_DELETION_RECORD_STORAGE_ENDPOINT=${BROWNIE_DELETION_RECORD_ENDPOINT}" \
  "BROWNIE_OIDC_ISSUER=${BROWNIE_OIDC_ISSUER}" \
  "BROWNIE_OIDC_CLIENT_ID=${BROWNIE_OIDC_CLIENT_ID}" \
  "BROWNIE_OIDC_CLIENT_SECRET=${oidc_client_secret}" \
  "BROWNIE_OPENAI_API_KEY=${openai_api_key}" \
  "BROWNIE_CLAMAV_HOST=clamav" \
  "BROWNIE_RENDER_IMAGE=${BROWNIE_RENDER_IMAGE}" \
  "BROWNIE_RENDER_EXPECTED_IMAGE_ID=${render_image_id}" \
  "BROWNIE_SUPPORT_CONTACT=${BROWNIE_SUPPORT_CONTACT:-}" \
  "BROWNIE_INVITED_ADDRESSES=${BROWNIE_INVITED_ADDRESSES:-}" \
  ${google_settings[@]+"${google_settings[@]}"}

write_env /etc/brownie/worker.env \
  "BROWNIE_ENVIRONMENT=pilot" \
  "BROWNIE_DB_URL=${database_url}" \
  "BROWNIE_DB_USERNAME=brownie_worker" \
  "BROWNIE_DB_PASSWORD=${db_worker_password}" \
  "BROWNIE_STORAGE_ENDPOINT=${BROWNIE_STORAGE_ENDPOINT}" \
  "BROWNIE_DELETION_RECORD_STORAGE_ENDPOINT=${BROWNIE_DELETION_RECORD_ENDPOINT}" \
  "BROWNIE_OPENAI_API_KEY=${openai_api_key}"

# The images this deployment is moving to, and the ones it is moving from. The
# previous set is what a rollback puts back, so it is written before the new
# one replaces it.
if [ -f /etc/brownie/images.env ]; then
  cp /etc/brownie/images.env /etc/brownie/images.previous.env
fi
docker_group_id="$(getent group docker | cut -d: -f3)"
[ -n "$docker_group_id" ] || fail "There is no docker group on this machine, so the API could not be given access to the container runtime it starts renderers with."

write_env /etc/brownie/images.env \
  "BROWNIE_API_IMAGE=${BROWNIE_API_IMAGE}" \
  "BROWNIE_WORKER_IMAGE=${BROWNIE_WORKER_IMAGE}" \
  "BROWNIE_WEB_IMAGE=${BROWNIE_WEB_IMAGE}" \
  "BROWNIE_SERVER_NAME=${BROWNIE_SERVER_NAME}" \
  "BROWNIE_DOCKER_GID=${docker_group_id}"

log "Applying database migrations with the new image, before anything serves it."
# The same image that is about to run, started with its web side switched off:
# it applies the migrations it carries and exits. If this fails, the release
# that is already running is still running, against the schema it expects.
docker run --rm --env-file /etc/brownie/api.env "$BROWNIE_API_IMAGE" \
  --spring.main.web-application-type=none \
  || fail "Migrations did not apply; nothing was changed."

log "Starting the new containers."
cd /etc/brownie
set -a
# shellcheck disable=SC1091
. /etc/brownie/images.env
set +a
if ! docker compose -f /etc/brownie/compose.yaml up -d --wait --wait-timeout "$HEALTHY_TIMEOUT_SECONDS"; then
  log "The new containers did not become healthy."
  if [ -f /etc/brownie/images.previous.env ]; then
    log "Putting the previous images back."
    cp /etc/brownie/images.previous.env /etc/brownie/images.env
    set -a
    # shellcheck disable=SC1091
    . /etc/brownie/images.env
    set +a
    docker compose -f /etc/brownie/compose.yaml up -d --wait --wait-timeout "$HEALTHY_TIMEOUT_SECONDS" \
      || log "The previous images did not come back either; this needs a person."
  fi
  fail "Deployment rolled back."
fi

log "Issuing or renewing the certificate."
/usr/local/sbin/brownie-certificate.sh "$BROWNIE_SERVER_NAME" "${BROWNIE_CERTIFICATE_CONTACT:-}" || true

log "Checking what is now running."
docker compose -f /etc/brownie/compose.yaml ps --format '{{.Service}} {{.Status}} {{.Image}}'
# Every route but sign-in needs a session, so the answer that proves the
# application is up is 401, not 200. Asking curl to treat any 4xx as a failure
# would fail a deployment that worked perfectly -- which is exactly what
# happened the first time this ran. What is being checked is that Brownie
# itself answered over HTTPS: a 200 or a 401 both prove that, while a 502, a
# 000 or anything else does not.
answer="$(curl -sS -m 15 -o /dev/null -w '%{http_code}' "https://${BROWNIE_SERVER_NAME}/api/v1/capabilities" || true)"
case "$answer" in
  200|401) log "The deployed application answered over HTTPS ($answer)." ;;
  *) fail "The deployed application did not answer its own capabilities route over HTTPS (got ${answer:-no response})." ;;
esac

log "Removing images nothing is using any more."
docker image prune --force --filter "until=168h" > /dev/null || true
log "Done."

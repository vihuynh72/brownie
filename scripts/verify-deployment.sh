#!/usr/bin/env bash
# Checks a running Brownie from outside it, over the public address, the way
# anyone else would reach it. Nothing here needs an account, a key, or access
# to the machine: every check is something a stranger could observe, which is
# exactly why they are the checks worth making automatically.
#
#   BROWNIE_BASE_URL=https://brownie-pilot.westus2.cloudapp.azure.com ./scripts/verify-deployment.sh
#
# BROWNIE_INSECURE_TLS=1 accepts a certificate that is not trusted, for a
# rehearsal against a self-signed one. It never skips the checks themselves.
# BROWNIE_PLAIN_BASE_URL says where plain HTTP is, when it is not port 80 of
# the same name -- again only a rehearsal needs it.
#
# What it cannot check, and what does: signing in, generating, exporting and
# deleting need a real account and a browser, and are the checklist a person
# runs once after a deployment; the renderer's isolation and the worker's
# health are visible only on the machine, and are verify-deployment-on-host.sh.

set -uo pipefail

base="${BROWNIE_BASE_URL:-}"
if [ -z "$base" ]; then
  printf 'BROWNIE_BASE_URL is not set. Example: BROWNIE_BASE_URL=https://brownie-pilot.westus2.cloudapp.azure.com %s\n' "$0" >&2
  exit 64
fi
base="${base%/}"
host="${base#https://}"
host="${host#http://}"
name="${host%%:*}"

curl_options=(--silent --show-error --max-time 20)
if [ "${BROWNIE_INSECURE_TLS:-0}" = "1" ]; then
  curl_options+=(--insecure)
fi

passed=0
failed=0
pass() { printf '  ok    %s\n' "$1"; passed=$((passed + 1)); }
fail() { printf '  FAIL  %s\n' "$1"; [ $# -gt 1 ] && printf '        %s\n' "$2"; failed=$((failed + 1)); }
section() { printf '\n%s\n' "$1"; }

status_of() { curl "${curl_options[@]}" -o /dev/null -w '%{http_code}' "$@"; }
headers_of() { curl "${curl_options[@]}" -o /dev/null -D - "$@"; }
header_value() { printf '%s' "$1" | tr -d '\r' | awk -v key="$(printf '%s' "$2" | tr '[:upper:]' '[:lower:]')" 'BEGIN{IGNORECASE=1} tolower($0) ~ "^" key ":" {sub(/^[^:]*: */, ""); print; exit}'; }

printf 'Checking %s\n' "$base"

section 'Transport'
page_headers="$(headers_of "$base/")"
if [ -z "$page_headers" ]; then
  fail "the address answers at all" "no response from $base/"
  printf '\nNothing else can be checked.\n'
  exit 1
fi
pass "the address answers over HTTPS"

if [ "${BROWNIE_INSECURE_TLS:-0}" = "1" ]; then
  printf '  note  certificate trust not checked (BROWNIE_INSECURE_TLS=1)\n'
else
  if curl --silent --show-error --max-time 20 -o /dev/null "$base/" 2>/dev/null; then
    pass "the certificate is trusted for this name"
  else
    fail "the certificate is trusted for this name"
  fi
fi

# Port 80 of the same name, not the port the HTTPS address happens to use:
# sending plain HTTP at the TLS port is answered by the TLS handshake failing,
# which says nothing about whether a redirect exists.
plain_base="${BROWNIE_PLAIN_BASE_URL:-http://${name}}"
plain="$(curl "${curl_options[@]}" -o /dev/null -D - "${plain_base%/}/" 2>/dev/null | tr -d '\r')"
plain_status="$(printf '%s' "$plain" | awk 'NR==1{print $2}')"
plain_location="$(header_value "$plain" location)"
case "$plain_status" in
  30[178])
    case "$plain_location" in
      https://*) pass "plain HTTP redirects to HTTPS ($plain_status)" ;;
      *) fail "plain HTTP redirects to HTTPS" "redirected to ${plain_location:-nowhere}" ;;
    esac
    ;;
  "") printf '  note  plain HTTP is not reachable from here; nothing to check\n' ;;
  *) fail "plain HTTP redirects to HTTPS" "answered $plain_status" ;;
esac

section 'Headers a browser is given'
check_header() {
  local headers="$1" key="$2" expectation="$3" value
  value="$(header_value "$headers" "$key")"
  if [ -z "$value" ]; then
    fail "$key is set" "absent"
  elif printf '%s' "$value" | grep -qiE "$expectation"; then
    pass "$key: $value"
  else
    fail "$key looks right" "$value"
  fi
}
check_header "$page_headers" strict-transport-security 'max-age=[0-9]{5,}'
check_header "$page_headers" content-security-policy "default-src 'self'"
check_header "$page_headers" x-content-type-options 'nosniff'
check_header "$page_headers" referrer-policy '.'
policy="$(header_value "$page_headers" content-security-policy)"
if printf '%s' "$policy" | grep -q "unsafe-inline"; then
  fail "the content policy allows no inline script or style" "$policy"
else
  pass "the content policy allows no inline script or style"
fi
if printf '%s' "$policy" | grep -q "frame-ancestors 'none'"; then
  pass "the page refuses to be framed"
else
  fail "the page refuses to be framed" "${policy:-no policy}"
fi

section 'One origin'
# Every route but sign-in needs a session, so 401 is the right answer here and
# is what proves the point: the request reached Brownie rather than the page's
# own catch-all. A 404 would mean the proxy is not routing the API at all, and
# a 502 that the API is not answering the proxy.
capabilities="$(status_of "$base/api/v1/capabilities")"
case "$capabilities" in
  200|401) pass "the API is reached under the same address (/api/v1/capabilities: $capabilities)" ;;
  *) fail "the API is reached under the same address" "/api/v1/capabilities answered $capabilities" ;;
esac
api_headers="$(headers_of "$base/api/v1/capabilities")"
if [ -n "$(header_value "$api_headers" strict-transport-security)" ]; then
  pass "API answers carry the transport policy too"
else
  fail "API answers carry the transport policy too"
fi

section 'Nothing is open that should not be'
for route in /api/v1/me /api/v1/workspaces/1/documents; do
  code="$(status_of "$base$route")"
  if [ "$code" = "401" ]; then
    pass "$route without a session: 401"
  else
    fail "$route without a session is refused" "answered $code"
  fi
done
actuator="$(curl "${curl_options[@]}" "$base/actuator/health" 2>/dev/null)"
if printf '%s' "$actuator" | grep -q '"status"'; then
  fail "the management endpoint is not public" "it answered with a health document"
else
  pass "the management endpoint is not reachable from outside"
fi

section 'Sign-in is wired to this address'
signin="$(headers_of "$base/oauth2/authorization/entra")"
location="$(header_value "$signin" location)"
if [ -z "$location" ]; then
  fail "starting a sign-in redirects to the identity provider" "no redirect"
else
  if printf '%s' "$location" | grep -q "ciamlogin.com\|login.microsoftonline.com"; then
    pass "starting a sign-in goes to the identity provider"
  else
    fail "starting a sign-in goes to the identity provider" "$location"
  fi
  # The one check that proves the application knows the address a browser
  # actually used, rather than the container's own name: what it asks the
  # provider to send the person back to.
  # The provider's address may carry it percent-encoded or not, depending on
  # how the query was built; both mean the same thing.
  expected_plain="https://${name}/login/oauth2/code/entra"
  expected_encoded="https%3A%2F%2F${name}%2Flogin%2Foauth2%2Fcode%2Fentra"
  if printf '%s' "$location" | grep -qF "redirect_uri=$expected_plain" \
     || printf '%s' "$location" | grep -qF "redirect_uri=$expected_encoded"; then
    pass "the sign-in callback it asks for is https://$name/login/oauth2/code/entra"
  else
    got="$(printf '%s' "$location" | tr '&' '\n' | grep '^redirect_uri=' || true)"
    fail "the sign-in callback is this address over HTTPS" "${got:-no redirect_uri}"
  fi
fi

section 'One caller cannot take the host'
# This spends most of one minute's allowance for callers who are not signed
# in, so for about a minute after it runs a stranger arriving at the address
# may be told to wait. That is the right trade for an invited pilot checked
# right after a deliberate deployment, and it is why the check is here rather
# than on a timer. BROWNIE_SKIP_RATE_LIMIT_CHECK=1 leaves it out.
limit_hit=""
if [ "${BROWNIE_SKIP_RATE_LIMIT_CHECK:-0}" = "1" ]; then
  printf '  note  skipped by request (BROWNIE_SKIP_RATE_LIMIT_CHECK=1)\n'
  limit_hit="skipped"
else
  # Sent all at once rather than one after another. The allowance refills
  # while a sequential run is in flight, and over a real network -- a hundred
  # milliseconds a request -- the refill outruns the sender and the limit is
  # never reached, which reads as "no limit" when there is one. Twenty at a
  # time outruns the refill on any link.
  if printf '%s\n' $(seq 1 200) \
    | xargs -P 20 -I{} curl "${curl_options[@]}" -o /dev/null -w '%{http_code}\n' "$base/api/v1/me" 2>/dev/null \
    | grep -q '^429$'; then
    limit_hit="yes"
  fi
fi
if [ "$limit_hit" = "skipped" ]; then
  :
elif [ -n "$limit_hit" ]; then
  retry_after="$(header_value "$(headers_of "$base/api/v1/me")" retry-after)"
  pass "requests without a session are limited (429${retry_after:+, retry after ${retry_after}s})"
else
  fail "requests without a session are limited" "140 requests in a row were all accepted"
fi

printf '\n%s passed, %s failed\n' "$passed" "$failed"
[ "$failed" -eq 0 ] || exit 1

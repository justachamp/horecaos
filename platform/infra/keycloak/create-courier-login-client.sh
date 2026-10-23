#!/usr/bin/env bash
#
# ADR 0179: creates or reconciles horecaos-courier, the public Authorization Code + PKCE client
# the courier app (frontend/courier) signs in with.
#
# A courier signs in to Keycloak directly, in the browser, and the platform validates the
# resulting access token like any other (issuer, signature, expiry, audience horecaos-api). That
# is the one place the courier differs from staff, whose password is exchanged by the platform
# itself (ADR 0062, create-staff-login-client.sh): a courier has no operations console to render a
# password form, the app is installed on a personal phone, and "sign in once for the shift" is the
# whole of the experience, which is what a Keycloak SSO session gives.
#
# A public client has no secret, so what stands between a stolen authorization code and a session
# is its redirect URI list and PKCE. This script therefore registers EXACT redirect URIs --
# `<origin>/auth/callback` -- never a wildcard, and exact post-logout and web origins. AGENTS.md:
# "Use strict redirect URI and web-origin configuration."
#
# Where it runs, and the rules it enforces. Like its siblings it talks to the Admin API, which
# nothing publishes (the public auth origin refuses /admin/*): on a laptop that is the compose
# Keycloak on a loopback address; on the pre-production and production hosts it is the `ops`
# container on the compose network, `http://keycloak:8080`, exactly as the bootstrap runbook runs
# assign-service-account-roles.sh. So the Keycloak address says nothing about WHICH realm this is.
# The origins say it, and two rules keep them honest:
#
#   * localhost and real origins are never mixed in one run. A localhost redirect on a realm that
#     serves real couriers is one an attacker can satisfy on their own machine.
#   * the localhost default is for the compose Keycloak on :8081 and nothing else. Against any
#     other address the origins must be named, so a run that was meant for a real realm and
#     forgot to say so stops instead of registering http://localhost:5200 there.
#
#   HORECAOS_COURIER_ORIGINS   origins the app is served from, space or comma separated.
#                              Default: http://localhost:5200 (the dev server's).
#                              http is accepted only for localhost, 127.0.0.1 and [::1];
#                              everything else must be https, with no path and no wildcard.
#
# Session lifetimes are the REALM's, and this script does not change them. Keycloak lets a client
# shorten the realm's SSO session idle and maximum lifespans and refuses a client attribute that
# lengthens them ("cannot exceed realm SSO session idle timeout" -- found the first time this ran
# against a real Keycloak 26.7), so a courier cannot be given a longer session than staff without
# raising the realm's, which is every account's and the owner's call (ADR 0179, open inputs). At
# the realm defaults (30 minutes idle, 10 hours maximum) a courier whose app is in the foreground
# on duty never idles out, because the app's own token refresh and the 10-second telemetry batches
# keep the session alive, and a phone left locked for more than half an hour on a break signs in
# again. To make a courier's session SHORTER than the realm's, set
#
#   HORECAOS_COURIER_SESSION_IDLE_SECONDS   idle timeout for this client only (optional)
#   HORECAOS_COURIER_SESSION_MAX_SECONDS    maximum lifespan for this client only (optional)
#
# Both are refused here if they are not whole seconds, and by Keycloak if they exceed the realm's.
#
# Idempotent: safe to run again, like its siblings. `--print` shows the client representation it
# would send and talks to nothing, which is how a reviewer reads what a run will do.

set -euo pipefail

KEYCLOAK_URL="${HORECAOS_KEYCLOAK_URL:-http://localhost:8081}"
REALM="${HORECAOS_KEYCLOAK_REALM:-horecaos}"
ADMIN_USER="${HORECAOS_KEYCLOAK_ADMIN:-admin}"
ADMIN_PASSWORD="${HORECAOS_KEYCLOAK_ADMIN_PASSWORD:-admin}"

CLIENT_ID="horecaos-courier"
ORIGINS="${HORECAOS_COURIER_ORIGINS:-http://localhost:5200}"
SESSION_IDLE_SECONDS="${HORECAOS_COURIER_SESSION_IDLE_SECONDS:-}"
SESSION_MAX_SECONDS="${HORECAOS_COURIER_SESSION_MAX_SECONDS:-}"

PRINT_ONLY=0
case "${1:-}" in
  --print) PRINT_ONLY=1 ;;
  "") ;;
  *) echo "usage: $0 [--print]" >&2; exit 2 ;;
esac

# The client representation and the origin checks live in one place so --print and a real run can
# never disagree about what they send. Reads the origins and lifetimes from the environment.
payload="$(ORIGINS="${ORIGINS}" CLIENT_ID="${CLIENT_ID}" \
  SESSION_IDLE_SECONDS="${SESSION_IDLE_SECONDS}" SESSION_MAX_SECONDS="${SESSION_MAX_SECONDS}" \
  python3 - <<'PY'
import json, os, re, sys

LOOPBACK = {"localhost", "127.0.0.1", "[::1]"}
origins = [o for o in re.split(r"[\s,]+", os.environ["ORIGINS"].strip()) if o]
if not origins:
    sys.exit("!! HORECAOS_COURIER_ORIGINS names no origin.")

seen, has_loopback, has_real = set(), False, False
for origin in origins:
    m = re.fullmatch(r"(https?)://([^/?#:@\s*]+|\[::1\])(:\d{1,5})?", origin)
    if not m:
        sys.exit(f"!! '{origin}' is not an origin: scheme://host[:port], no path, no wildcard, no trailing slash.")
    scheme, host = m.group(1), m.group(2)
    loopback = host in LOOPBACK
    if scheme == "http" and not loopback:
        sys.exit(f"!! '{origin}' is http. A courier's tokens travel to this origin; only https, or a localhost development server, is allowed.")
    if scheme == "https" and loopback:
        sys.exit(f"!! '{origin}' is https on a loopback host; use the http dev origin instead.")
    has_loopback |= loopback
    has_real |= not loopback
    if origin in seen:
        sys.exit(f"!! '{origin}' is listed twice.")
    seen.add(origin)

if has_loopback and has_real:
    sys.exit("!! localhost origins and real origins in one run. A localhost redirect on a realm that serves real couriers is one an attacker can satisfy on their own machine; run this twice, once per realm.")

def seconds(name):
    value = os.environ[name]
    if value == "":
        return None
    if not re.fullmatch(r"[1-9]\d{2,6}", value):
        sys.exit(f"!! {name} must be a whole number of seconds, 100 or more.")
    return value

idle, maximum = seconds("SESSION_IDLE_SECONDS"), seconds("SESSION_MAX_SECONDS")
if idle and maximum and int(maximum) < int(idle):
    sys.exit("!! The maximum session lifetime is shorter than the idle one.")

attributes = {
    "pkce.code.challenge.method": "S256",
    "post.logout.redirect.uris": "##".join(f"{o}/auth/signed-out" for o in origins),
}
# Always sent, empty when unset: a full-representation PUT leaves an attribute it does not mention
# exactly as it was, so a run that drops the override must say "none" to take a previous one away.
attributes["client.session.idle.timeout"] = idle or ""
attributes["client.session.max.lifespan"] = maximum or ""

print(json.dumps({
    "clientId": os.environ["CLIENT_ID"],
    "name": "HorecaOS Courier",
    "description": "ADR 0179: public Authorization Code + PKCE client of the courier app (frontend/courier). Created by infra/keycloak/create-courier-login-client.sh.",
    "enabled": True,
    "protocol": "openid-connect",
    "publicClient": True,
    "standardFlowEnabled": True,
    "implicitFlowEnabled": False,
    "directAccessGrantsEnabled": False,
    "serviceAccountsEnabled": False,
    # The token carries no realm or client roles: a courier is authorized by owning a courier row
    # (ADR 0049), never by a role, so there is nothing for a role claim to add but exposure.
    "fullScopeAllowed": False,
    "redirectUris": [f"{o}/auth/callback" for o in origins],
    "webOrigins": origins,
    "attributes": attributes,
    # `email` is deliberately absent: the app shows a name and a login, never an address.
    "defaultClientScopes": ["basic", "profile", "roles", "web-origins", "acr"],
    "optionalClientScopes": ["organization"],
    "protocolMappers": [{
        "name": "horecaos-api-audience",
        "protocol": "openid-connect",
        "protocolMapper": "oidc-audience-mapper",
        "consentRequired": False,
        "config": {
            "included.client.audience": "horecaos-api",
            "id.token.claim": "false",
            "access.token.claim": "true",
        },
    }],
}, indent=2))
PY
)"

if [ "${PRINT_ONLY}" = 1 ]; then
  echo "${payload}"
  exit 0
fi

case "${KEYCLOAK_URL}" in
  http://localhost:*|http://127.0.0.1:*|http://[::1]:*|http://keycloak:8080) ;;
  *) echo "!! ${KEYCLOAK_URL} is not an address the Admin API is reachable at. Use a loopback address, or http://keycloak:8080 from the ops container on the host (docs/runbooks/external-connections/w4-courier-client.md)." >&2
     exit 1 ;;
esac
if [ -z "${HORECAOS_COURIER_ORIGINS:-}" ] && [ "${KEYCLOAK_URL}" != "http://localhost:8081" ]; then
  echo "!! ${KEYCLOAK_URL} is not the compose Keycloak, so HORECAOS_COURIER_ORIGINS must be set: the localhost default is only for a laptop." >&2
  exit 1
fi

token() {
  curl -sf -X POST "${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token" \
    -d "client_id=admin-cli" -d "username=${ADMIN_USER}" \
    -d "password=${ADMIN_PASSWORD}" -d "grant_type=password" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])"
}

TOKEN="$(token)"
api() { curl -sf -H "Authorization: Bearer ${TOKEN}" "$@"; }

existing="$(api "${KEYCLOAK_URL}/admin/realms/${REALM}/clients?clientId=${CLIENT_ID}" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print(d[0]['id'] if d else '')")"

if [ -n "${existing}" ]; then
  curl -sf -o /dev/null -X PUT \
    "${KEYCLOAK_URL}/admin/realms/${REALM}/clients/${existing}" \
    -H "Authorization: Bearer ${TOKEN}" -H "Content-Type: application/json" \
    -d "${payload}"
  client_uuid="${existing}"
  echo "==> Updated ${CLIENT_ID} in realm ${REALM}" >&2
else
  curl -sf -o /dev/null -X POST \
    "${KEYCLOAK_URL}/admin/realms/${REALM}/clients" \
    -H "Authorization: Bearer ${TOKEN}" -H "Content-Type: application/json" \
    -d "${payload}"
  client_uuid="$(api "${KEYCLOAK_URL}/admin/realms/${REALM}/clients?clientId=${CLIENT_ID}" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)[0]['id'])")"
  echo "==> Created ${CLIENT_ID} in realm ${REALM}" >&2
fi

# Keycloak honours defaultClientScopes/optionalClientScopes on CREATE and silently ignores them on
# a full-representation PUT to an existing client, which would leave every run after the first
# with whatever scopes the client had. So the sets are converged explicitly here: the wanted ones
# are assigned and any other default scope is removed (a courier token must not quietly regain
# `email`). The loop is the same one create-staff-login-client.sh uses, plus the removal.
scope_id() {
  api "${KEYCLOAK_URL}/admin/realms/${REALM}/client-scopes" \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print(next((s['id'] for s in d if s['name']=='$1'), ''))"
}
assign_scope() {
  local endpoint="$1" scope_name="$2" id
  id="$(scope_id "${scope_name}")"
  [ -n "${id}" ] || { echo "!! client scope '${scope_name}' does not exist in realm ${REALM}" >&2; exit 1; }
  curl -sf -o /dev/null -X PUT \
    "${KEYCLOAK_URL}/admin/realms/${REALM}/clients/${client_uuid}/${endpoint}/${id}" \
    -H "Authorization: Bearer ${TOKEN}"
}
WANTED_DEFAULT="basic profile roles web-origins acr"
for s in ${WANTED_DEFAULT}; do assign_scope default-client-scopes "${s}"; done
assign_scope optional-client-scopes organization

api "${KEYCLOAK_URL}/admin/realms/${REALM}/clients/${client_uuid}/default-client-scopes" \
  | python3 -c "
import sys, json
wanted = set('${WANTED_DEFAULT}'.split())
for scope in json.load(sys.stdin):
    if scope['name'] not in wanted:
        print(scope['id'], scope['name'])
" | while read -r id name; do
      curl -sf -o /dev/null -X DELETE \
        "${KEYCLOAK_URL}/admin/realms/${REALM}/clients/${client_uuid}/default-client-scopes/${id}" \
        -H "Authorization: Bearer ${TOKEN}"
      echo "==> Removed default scope ${name} from ${CLIENT_ID}" >&2
    done

# Read it back and check what matters. A public client with a leftover wildcard redirect, or one
# that quietly lost PKCE, would still "work" and be wrong; this is the loud place to find out.
api "${KEYCLOAK_URL}/admin/realms/${REALM}/clients/${client_uuid}" \
  | EXPECTED="${payload}" python3 -c "
import json, os, sys
actual = json.load(sys.stdin)
expected = json.loads(os.environ['EXPECTED'])
problems = []
if actual.get('publicClient') is not True: problems.append('is not a public client')
if actual.get('secret'): problems.append('carries a secret')
if actual.get('implicitFlowEnabled') or actual.get('directAccessGrantsEnabled') or actual.get('serviceAccountsEnabled'):
    problems.append('has a flow enabled besides the standard one')
if sorted(actual.get('redirectUris', [])) != sorted(expected['redirectUris']): problems.append('redirect URIs differ: %s' % actual.get('redirectUris'))
if sorted(actual.get('webOrigins', [])) != sorted(expected['webOrigins']): problems.append('web origins differ: %s' % actual.get('webOrigins'))
if any('*' in u for u in actual.get('redirectUris', [])): problems.append('has a wildcard redirect URI')
if actual.get('attributes', {}).get('pkce.code.challenge.method') != 'S256': problems.append('does not require PKCE S256')
for key, wanted in expected['attributes'].items():
    if (actual.get('attributes', {}).get(key) or '') != wanted: problems.append('%s differs' % key)
if not any(m.get('name') == 'horecaos-api-audience' for m in actual.get('protocolMappers', [])): problems.append('lacks the horecaos-api audience mapper')
if problems:
    print('!! %s in Keycloak %s.' % (actual.get('clientId'), '; '.join(problems)), file=sys.stderr)
    sys.exit(1)
print('==> Verified: public, PKCE S256, exact redirects:', ', '.join(actual['redirectUris']), file=sys.stderr)
"

echo "==> Done" >&2

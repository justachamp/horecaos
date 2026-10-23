# Keycloak realm

## Service accounts (ADR 0009, ADR 0079)

Three confidential clients, deliberately separated. The drift report runs
unattended on a timer, and that is exactly when write capability should not be
sitting idle in a scheduled job; the device-provisioning credential holds
`manage-clients` alone, so it is the only one of the three that could mint a
new confidential client, and neither of the other two may.

| Client | Purpose | realm-management roles |
|---|---|---|
| `horecaos-provisioning` | Create organizations, create and link owners, assign organization-scoped roles | `manage-organizations`, `manage-users`, `view-users`, `query-users` |
| `horecaos-identity-reader` | Scheduled drift report | `view-organizations`, `query-organizations`, `view-users`, `query-users` |
| `horecaos-device-provisioning` | ADR 0079: create, regenerate the secret of, and disable a kitchen display device's own confidential client | `manage-clients` |

None of the three holds `manage-realm`, `realm-admin`, or `impersonation`, and
only the device-provisioning credential holds `manage-clients` — deliberately
not added to `horecaos-provisioning`, which already holds `manage-users`. A
single credential holding both could mint a service account client and grant
it any role this script assigns, which is realm compromise from one leaked
secret rather than two.

## Staff sign-in (ADR 0062)

`horecaos-staff-login` is a third confidential client, and deliberately not a
service account: `serviceAccountsEnabled` is `false`, because it never acts as
itself. It exists so the platform backend — and only the backend — can run the
OAuth2 direct grant on behalf of a real signed-in staff member:
`frontend/control-plane` and `frontend/operations` each render their own
sign-in page, POST a username and password to the platform, and the platform
exchanges them with Keycloak on this client. Neither app ever holds this
secret or talks to Keycloak directly.

It replaces the two public redirect clients this realm used to carry,
`horecaos-operations` and `horecaos-control-plane` (Authorization Code + PKCE,
ADR 0003/ADR 0035) — both retired from this file along with the redirect flow
they served. A fresh import never creates them at all; a realm that already
imported the old file still carries them until an operator removes them live
(`docs/runbooks/production-setup.md`'s "Production hardening" section still
names them for a redirect-URI step this decision made moot — it needs its own
update to retire that step and add this client's secret to the rotation list
below, not done here).

Same secrets discipline as the two service accounts above: the value in this
file is a development placeholder, `docker compose`'s `openbao-seed` seeds the
matching value at `horecaos/local/identity_admin/keycloak/staff-login-secret`,
and both must stay in sync (see "Secrets" below). Locally, `docker compose up`
only creates this client on a *fresh* realm import; a checkout with an
already-running Keycloak from before this client existed needs
`infra/keycloak/create-staff-login-client.sh` once to catch up — the same
situation `create-local-dev-client.sh` and `create-local-web-client.sh` solve
for their own additions.

## Courier sign-in (ADR 0179)

`horecaos-courier` is the one redirect client this realm has for a person, and it is a public
Authorization Code + PKCE client, not a second staff login. A courier's phone talks to Keycloak
directly (there is no courier console to render a password form and the platform does not
exchange a courier's password), the app keeps its tokens in memory, and the platform validates
the access token like any other: issuer, signature, expiry and audience `horecaos-api`. A
courier is authorized by owning a `fulfillment.couriers` row whose `principal_subject` is the
token's subject (ADR 0049), so the token carries no roles and the client has
`fullScopeAllowed = false`.

It is created by `create-courier-login-client.sh`, never by the realm import: a public client's
only defence is its redirect list, and the origins differ per environment.

```bash
# on a laptop, against the compose Keycloak: http://localhost:5200 (the dev server)
./infra/keycloak/create-courier-login-client.sh

# on the pre-production or production host: from the ops container on the compose network,
# the way the bootstrap runbook runs assign-service-account-roles.sh (the public auth origin
# refuses /admin/*). The origins are the real ones, https only, and must be named.
read -rsp 'Keycloak admin password: ' HORECAOS_KEYCLOAK_ADMIN_PASSWORD; echo
docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env \
  run --rm --no-TTY \
  --volume "$(pwd)/platform/infra/keycloak:/keycloak:ro" \
  -e HORECAOS_KEYCLOAK_URL=http://keycloak:8080 \
  -e HORECAOS_KEYCLOAK_ADMIN_PASSWORD \
  -e HORECAOS_COURIER_ORIGINS=https://courier.staging.horecaos.uz \
  ops bash /keycloak/create-courier-login-client.sh
unset HORECAOS_KEYCLOAK_ADMIN_PASSWORD

./infra/keycloak/create-courier-login-client.sh --print   # the client it would send, talking to nothing
```

It registers exact redirect URIs (`<origin>/auth/callback`), exact post-logout redirect URIs and
exact web origins, refuses a wildcard, a path, a non-https origin that is not a localhost
development server, a run that names localhost and real origins together, and the localhost
default against any Keycloak but the compose one on `:8081`, and reads the
client back and fails if it is not public, carries a secret, enables another flow, has a
wildcard or lost PKCE. It is idempotent and converges: an override dropped from the environment
is removed from the client, and the default scopes are the ones listed in the script (no
`email`).

**Session lifetimes are the realm's.** Keycloak refuses a client attribute that lengthens the
realm's SSO session idle or maximum lifespan ("Client session idle timeout cannot exceed realm SSO
session idle timeout", found on the script's first run), so at the realm's defaults a courier
stays signed in for 30 minutes idle and 10 hours at most; an app in the foreground on duty never
idles out. `HORECAOS_COURIER_SESSION_IDLE_SECONDS` and `HORECAOS_COURIER_SESSION_MAX_SECONDS`
make this client's sessions shorter than the realm's; making them longer is a realm setting that
changes every account's, and is the owner's decision (ADR 0179, open inputs).

Verified against Keycloak 26.7.0 on 2026-10-14, with a real user and a hand-driven browser
round trip: the callback carries `code`, `state`, `session_state` and `iss`; a wrong
`code_verifier` is `invalid_grant`; the access token is `aud = horecaos-api`, `azp =
horecaos-courier`, scope `openid profile`, no `realm_access` or `resource_access`; the identity
token carries the nonce, a name and a login and no email; refresh works; an unregistered
`redirect_uri` or post-logout redirect is a 400; a request with no code challenge is answered
`error=invalid_request`; and CORS allows the registered origin on the token endpoint.

## Verified, not assumed

Checked against Keycloak 26.7 on 2026-08-21:

```text
provisioning  create organization      201
provisioning  create user              201
provisioning  add organization member  201
provisioning  update realm             403   <- least privilege holds
reader        list organizations       200
reader        create organization      403   <- read-only holds
```

The two 403s are the point. A provisioning credential that could change the
realm could create administrators and rewrite authentication flows, and a drift
credential that could write could quietly alter the memberships it is meant to
be reporting on.

## Assigning the roles

The realm import creates the clients; the role assignments are applied by
`assign-service-account-roles.sh`, because realm import files express client
scope mappings awkwardly and a script that fails loudly is easier to trust than
JSON that silently grants nothing.

```bash
docker compose up -d keycloak
./infra/keycloak/assign-service-account-roles.sh
```

Locally it will also say that both clients are still on the secret from the
import file. That is correct locally and is meant to be read, not silenced —
see below.

## Secrets

**The three secrets in `realm/horecaos-realm.json` are not secrets.** They are
`${HORECAOS_KEYCLOAK_PROVISIONING_SECRET:development-only-not-a-secret-provisioning}`,
the reader equivalent, and (ADR 0079)
`${HORECAOS_KEYCLOAK_DEVICE_PROVISIONING_SECRET:development-only-not-a-secret-device-provisioning}`:
an environment placeholder with a fallback that is spelled so that it cannot be
mistaken for a credential in an admin console, a log line, or a diff. Keycloak
substitutes the environment variable at import when one is set, and falls back
to the visible placeholder when one is not — which is what makes
`docker compose up` work on a laptop with nothing configured.

The fallback must never survive anywhere else, and `horecaos-provisioning` is why:
it holds `manage-users`, so that value is realm-wide user administration for
anybody with a checkout of this repository. `horecaos-device-provisioning` is
the same argument over `manage-clients`: the fallback value in a checkout
could mint an arbitrary confidential client and grant it any role.

Two things enforce that rather than asking for it:

- `docs/runbooks/deploy.md`, **"Rotate the service-account secrets"**, is a
  mandatory bootstrap step. Keycloak generates the replacements itself and they
  go straight into OpenBao under `identity_admin` (ADR 0028); no human chooses,
  types, or sees a value.
- `assign-service-account-roles.sh` reads each client's current secret afterwards
  and, with `HORECAOS_KEYCLOAK_REQUIRE_ROTATED_SECRETS=1`, exits non-zero while
  any of the three is still the value from the import file. The bootstrap runs
  it that way.

Rotation changes the value behind the reference and never the reference, so
nothing in this repository changes when it happens again.

## The local browser client

`horecaos-local-web` — public client, `http://localhost:5173/*` — is created by
`create-local-web-client.sh` rather than by the realm import:

```bash
./infra/keycloak/create-local-web-client.sh
```

It used to be a fixture in the import file, which meant the production bootstrap
created it too. A public client has no secret, so its redirect URI list is its
only defence, and a `localhost` entry is one an attacker can satisfy on their own
machine. The script refuses to run against anything but a loopback Keycloak and a
localhost redirect, so no tunnel, paste, or copy of the realm directory can put
it on a real realm.

## Realm security settings

Set on the realm itself, so they apply to every staff login rather than to the
one form somebody remembered:

| Setting | Value | Why |
|---|---|---|
| `bruteForceProtected` | true | The edge does no rate limiting; without this an online guessing run against a known cashier's email is unlimited |
| `permanentLockout` | false | A permanent lockout is a denial of service any stranger can trigger against a named courier at the start of a shift |
| `failureFactor` | 8 | Room for a mistyped password on a POS keyboard, far below what guessing needs |
| `waitIncrementSeconds` / `maxFailureWaitSeconds` | 60 / 900 | The wait doubles up to fifteen minutes: expensive for a script, survivable for a person |
| `quickLoginCheckMilliSeconds` / `minimumQuickLoginWaitSeconds` | 1000 / 60 | Catches the burst shape specifically — attempts arriving faster than a human types |
| `maxDeltaTimeSeconds` | 43200 | Failures decay after twelve hours, so yesterday's typos do not lock out this morning's shift |
| `passwordPolicy` | `length(12) and notUsername and notEmail and passwordHistory(3)` | Length is the only composition rule that measurably helps; NIST 800-63B advises against character-class rules and forced expiry, and both mostly produce `Password1!` and a sticky note |

`notUsername` and `notEmail` are there because these are staff accounts created
by an administrator during onboarding, and the address is the obvious thing to
type when inventing a first password for somebody else.

## Declarative User Profile: firstName/lastName are not required

Keycloak 26's declarative User Profile defaults every attribute group's
`firstName` and `lastName` to required for the `user` role. `ensureMembership`
(`KeycloakOrganizationProvisioner`) now always sets sensible name attributes on
a user it creates, so this normally never matters — but a password-grant login
for **any** account still missing one (an operator-created user, a fixture, a
partially-provisioned retry) is refused with the misleading `invalid_grant` /
"Account is not fully set up", no exception anywhere in the logs, and no realm
setting names it. This is
[keycloak/keycloak#36108](https://github.com/keycloak/keycloak/issues/36108), a
known Keycloak 26.0.7+ regression, still present in the pinned 26.7.0.

`realm/horecaos-realm.json`'s `components` block installs a
`declarative-user-profile` config identical to Keycloak's own default except
that `firstName` and `lastName` carry no `required` block, so a local
deployment authenticates a name-incomplete account instead of refusing it —
belt and braces alongside the application-side fix, and the only lever
available for a fixture or an account this codebase did not create. Verified
live against Keycloak 26.7.0: a nameless account gets the exact
`invalid_grant` / "Account is not fully set up" response under the stock
profile, and a normal token under this one. Confirm the same after changing
either the realm import or `ensureMembership`:

```bash
docker compose down -v && make up
infra/keycloak/create-local-dev-client.sh   # a direct-grant client to test with
```

then create a user via the Admin API with only `username`/`email` (no name),
set a policy-compliant password, and confirm a password-grant token comes back
rather than `invalid_grant`.

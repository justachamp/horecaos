# Keycloak realm

## Service accounts (ADR 0009)

Two confidential clients, deliberately separated. The drift report runs
unattended on a timer, and that is exactly when write capability should not be
sitting idle in a scheduled job.

| Client | Purpose | realm-management roles |
|---|---|---|
| `horecaos-provisioning` | Create organizations, create and link owners, assign organization-scoped roles | `manage-organizations`, `manage-users`, `view-users`, `query-users` |
| `horecaos-identity-reader` | Scheduled drift report | `view-organizations`, `query-organizations`, `view-users`, `query-users` |

Neither holds `manage-realm`, `realm-admin`, `manage-clients`, or
`impersonation`.

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

**The two secrets in `realm/horecaos-realm.json` are not secrets.** They are
`${HORECAOS_KEYCLOAK_PROVISIONING_SECRET:development-only-not-a-secret-provisioning}`
and the reader equivalent: an environment placeholder with a fallback that is
spelled so that it cannot be mistaken for a credential in an admin console, a
log line, or a diff. Keycloak substitutes the environment variable at import when
one is set, and falls back to the visible placeholder when one is not — which is
what makes `docker compose up` work on a laptop with nothing configured.

The fallback must never survive anywhere else, and `horecaos-provisioning` is why:
it holds `manage-users`, so that value is realm-wide user administration for
anybody with a checkout of this repository.

Two things enforce that rather than asking for it:

- `docs/runbooks/deploy.md`, **"Rotate the service-account secrets"**, is a
  mandatory bootstrap step. Keycloak generates the replacements itself and they
  go straight into OpenBao under `identity_admin` (ADR 0028); no human chooses,
  types, or sees a value.
- `assign-service-account-roles.sh` reads each client's current secret afterwards
  and, with `HORECAOS_KEYCLOAK_REQUIRE_ROTATED_SECRETS=1`, exits non-zero while
  either is still the value from the import file. The bootstrap runs it that way.

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

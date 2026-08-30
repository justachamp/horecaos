# Keycloak realm

## Service accounts (ADR 0009)

Two confidential clients, deliberately separated. The drift report runs
unattended on a timer, and that is exactly when write capability should not be
sitting idle in a scheduled job.

| Client | Purpose | realm-management roles |
|---|---|---|
| `qoida-provisioning` | Create organizations, create and link owners, assign organization-scoped roles | `manage-organizations`, `manage-users`, `view-users`, `query-users` |
| `qoida-identity-reader` | Scheduled drift report | `view-organizations`, `query-organizations`, `view-users`, `query-users` |

Neither holds `manage-realm`, `realm-admin`, `manage-clients`, or
`impersonation`.

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

**The two secrets in `realm/qoida-realm.json` are not secrets.** They are
`${QOIDA_KEYCLOAK_PROVISIONING_SECRET:development-only-not-a-secret-provisioning}`
and the reader equivalent: an environment placeholder with a fallback that is
spelled so that it cannot be mistaken for a credential in an admin console, a
log line, or a diff. Keycloak substitutes the environment variable at import when
one is set, and falls back to the visible placeholder when one is not — which is
what makes `docker compose up` work on a laptop with nothing configured.

The fallback must never survive anywhere else, and `qoida-provisioning` is why:
it holds `manage-users`, so that value is realm-wide user administration for
anybody with a checkout of this repository.

Two things enforce that rather than asking for it:

- `docs/runbooks/deploy.md`, **"Rotate the service-account secrets"**, is a
  mandatory bootstrap step. Keycloak generates the replacements itself and they
  go straight into OpenBao under `identity_admin` (ADR 0028); no human chooses,
  types, or sees a value.
- `assign-service-account-roles.sh` reads each client's current secret afterwards
  and, with `QOIDA_KEYCLOAK_REQUIRE_ROTATED_SECRETS=1`, exits non-zero while
  either is still the value from the import file. The bootstrap runs it that way.

Rotation changes the value behind the reference and never the reference, so
nothing in this repository changes when it happens again.

## The local browser client

`qoida-local-web` — public client, `http://localhost:5173/*` — is created by
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

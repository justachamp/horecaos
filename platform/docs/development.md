# Local development

## Requirements

- JDK 25
- Docker with Docker Compose
- Git

Maven does not need to be installed globally. The repository uses the Maven
Wrapper and pins Maven 3.9.16.

## Start dependencies

```bash
make up
```

Equivalent to `docker compose up -d && docker compose ps`, plus one more step:
it also runs `infra/keycloak/assign-service-account-roles.sh`, which grants the
ADR 0009 `horecaos-provisioning` and `horecaos-identity-reader` service accounts
their `realm-management` roles. The realm import file declares the two clients
but cannot express client role mappings, so without this step every Keycloak
Admin API call the application makes returns 403 — the tenant-onboarding
`KEYCLOAK_ORGANIZATION_RECONCILE` step fails on a fresh checkout otherwise. The
script waits and retries briefly for Keycloak to finish starting and importing
the realm (`docker compose up -d` returns before that finishes; the `keycloak`
service declares no healthcheck), then fails loudly, naming what to check,
rather than leaving a checkout silently half-configured. It is idempotent —
safe to run again — so `make up` on an already-running stack is also safe.

The local stack provides:

| Dependency | Address | Local credentials |
|---|---|---|
| HorecaOS PostgreSQL | `localhost:5432/horecaos` | `horecaos` / `horecaos` |
| Keycloak PostgreSQL | `localhost:5433/keycloak` | `keycloak` / `keycloak` |
| Kafka | `localhost:9092` | none |
| Keycloak | `http://localhost:8081` | `admin` / `admin` |
| OpenBao | `http://localhost:8200` | root token `horecaos-local-root` |

These credentials are development-only and must never be reused outside a
developer machine. Keycloak imports the `horecaos` realm and a PKCE-only public
client for a future frontend at `http://localhost:5173`.

### Keycloak service-account secrets (ADR 0009)

`horecaos-provisioning` and `horecaos-identity-reader` each fall back to a
development placeholder secret when the realm import runs with no
`HORECAOS_KEYCLOAK_PROVISIONING_SECRET` / `_READER_SECRET` set — see
`infra/keycloak/README.md`, "Secrets". The `openbao-seed` compose service seeds
the same two values into OpenBao under `horecaos/local/identity_admin/keycloak/`
so the ADR 0028 `SecretResolver` reads back exactly what Keycloak actually
issued. **Both places must carry the same value**; they were briefly out of
sync (the seed wrote `local-provisioning-secret` while the realm fell back to
`development-only-not-a-secret-provisioning`), which made every service-account
login fail on a stock `docker compose up`. If you ever change one, change the
other in the same commit.

To run the application itself against this stack with secrets resolved from
OpenBao rather than the `environment` provider default, copy `.env.example` to
`.env`, export it (`set -a && source .env && set +a`), and run `make run`. Only
`HORECAOS_SECRETS_PROVIDER=openbao` plus the identity_admin path above makes
Keycloak organization provisioning resolvable locally without also exporting
`HORECAOS_KEYCLOAK_PROVISIONING_SECRET` / `_READER_SECRET` by hand.

### Phone/OTP sign-in locally

The `local` profile (`src/main/resources/application-local.yml`) presets a fixed
verification code for one number: `+998000000000` codes as `000000` (override with
`HORECAOS_VERIFICATION_PRESET_PHONE` / `_PRESET_CODE`), so the customer session
journey (ADR 0051) is exercisable end to end without a bound SMS gateway or a
message ever leaving the laptop. `+998 00 000 00 00` is deliberately not a number
anyone has — `00` is unallocated in the Uzbek numbering plan — so it passes
`PhoneNumber` validation without naming a real subscriber. `PresetVerificationCodeSource`
refuses to be created outside a `local` profile and `PresetVerificationCodeGuard`
refuses to let a non-local profile start with either override set: a fixed one-time
code reaching a deployment would be a full authentication bypass, not a weakened
control. Every other number on this profile still draws a random code and still
needs a real transport, so the preset cannot hide a broken SMS path.

### Seeding a local payment setup

```bash
make run           # applies the local-fixtures demo tenant at least once
make seed-payments
```

Gives the [local-fixtures](local-fixtures.md) demo tenant a working CLICK
payment setup, so `PAYMENT_CONFIGURATION_VALIDATE` (ADR 0008, ADR 0013) has
something real to check and a checkout carrying a non-cash method has a
merchant binding to resolve. `tools/seed-payments` is dev-only tooling and
structurally so: every write targets the fixed local addresses `make up`
starts (OpenBao at `http://localhost:8200`, Postgres through the `platform-db`
compose service) with no environment variable that could repoint it anywhere
else. It is not, and must never become, the ADR 0028 production secret-write
path — that is the deploy bootstrap in `docs/runbooks/deploy.md`.

It does four things:

1. Writes a fake CLICK secret into OpenBao at the exact ADR 0028 reference
   `payments` resolves at call time.
2. Seeds an `integration.provider_environments` row for a CLICK sandbox — ADR
   0026 platform-owned reference data with no tenant-facing endpoint by
   design (`horecaos_app` is granted no write on it), so the script runs this
   one statement as `horecaos_migrator`, standing in for the deployment
   migration that would seed a provider actually in use.
3. Prints, rather than sends, the two real `ProviderInstallationController`
   requests an operator would use instead of step 4's installation and
   binding rows: both need a bearer token carrying `INTEGRATION_INSTALLATION_MANAGE`,
   which only a signed-in operator has (get one through Swagger UI's
   **Authorize**, as above) — a script minting its own capability token is
   exactly what ADR 0025 exists to prevent.
4. Seeds the installation, the binding, and the `payments.merchant_bindings`
   row directly in Postgres, reusing the fixture's own legal entity when one
   is already assigned to the fixture location. HTTP endpoints for both DO
   exist — `LegalEntityController` (`legal-entity.manage`) and
   `MerchantBindingController` (`payment.merchant-binding.manage`) — and are
   what an operator uses; the script stays on direct SQL for the same reason
   as step 3: both capabilities belong to a signed-in owner, and a script
   minting its own capability token is exactly what ADR 0025 exists to
   prevent. Same shape the test suite uses (e.g.
   `PaymentCheckoutSurfaceTests`), not a new write path.

Idempotent — safe to run again.

## Build and test

```bash
./mvnw verify
```

The build enforces JDK 25. The first invocation downloads the pinned Maven
distribution and project dependencies.

## Quality gates

ADR 0054 puts four gates inside `./mvnw verify` itself, alongside the
Python-based repository rules `make lint` already runs:

- **Coverage.** JaCoCo measures line coverage on every `verify` and
  `jacoco:check` fails the build below `horecaos.coverage.floor` in
  `pom.xml`. The floor may only rise — raise it when coverage rises, never
  lower it to admit a change.
- **Formatting.** Spotless (`palantir-java-format`) formats
  `src/main/java` and `src/test/java`; `spotless:check` fails `verify` on
  any unformatted file. Run `make format` (`spotless:apply`) before
  committing rather than hand-formatting.
- **Static analysis.** Error Prone and NullAway run on every compile and
  fail the build directly — `-XepAllErrorsAsWarnings` was removed on
  2026-08-31 once the full-tree inventory (ADR 0054) reached zero across
  every check, including NullAway, which is pinned to `-Xep:NullAway:ERROR`
  explicitly. A `@SuppressWarnings("NullAway")` is only ever added with a
  one-line justification comment; it is never blanket.
- **Dependency scanning.** Maven Enforcer bans duplicate managed dependency
  versions on every build. OWASP `dependency-check` is heavier — it pulls
  live NVD data — so it lives in the `security-audit` Maven profile and
  runs from `.github/workflows/security-audit.yml` on a weekly schedule
  and on demand, not on every commit.

## Run the API

```bash
make run
```

`make run` activates the `local` profile and applies the local-only fixture
migration. It never runs under the test or production profiles. The stable demo
tenant and request examples are in [local fixtures](local-fixtures.md). To run
without fixtures, use `./mvnw spring-boot:run` directly.

Readiness and liveness are available at:

```text
http://localhost:8080/actuator/health/readiness
http://localhost:8080/actuator/health/liveness
```

Swagger UI and generated OpenAPI contracts are available at:

```text
http://localhost:8080/swagger-ui.html
http://localhost:8080/v3/api-docs
http://localhost:8080/v3/api-docs.yaml
```

Swagger UI's group selector (and `/v3/api-docs/<group>`) also serves four
additive, filtered views of the same document — `storefront`, `control-plane`,
`operations`, `providers` — each with its own baseline and generated
TypeScript client. See [api/README.md](../api/README.md#per-surface-groups)
for the group-to-frontend mapping and the one-group-per-path rule.

Health and API-documentation endpoints are public. Business endpoints require
a JWT issued by the local `horecaos` realm for the `horecaos-api` audience. Swagger
UI accepts that token through **Authorize** and deliberately does not retain it
after a page reload.

For tenant APIs, the frontend requests Keycloak's `organization:<alias>` scope
for the selected tenant. `organization:*` is supported for administrative
clients that intentionally need multiple memberships. The API reads immutable
organization IDs from the signed `organization` claim and matches them to the
tenant record; it never accepts tenant context from an arbitrary header.

The realm import adds these `horecaos-api` client roles:

- `platform-admin`: global platform control-plane access
- `tenant-owner`: organization-scoped tenant management
- `tenant-admin`: organization-scoped tenant management
- `tenant-viewer`: organization membership/read access

Tenant roles are evaluated only from their matching nested organization claim.
A tenant role appearing only in the token's global role set does not grant
access to every tenant.

## Configuration

The application accepts these environment variables:

| Variable | Development default |
|---|---|
| `HORECAOS_DB_URL` | `jdbc:postgresql://localhost:5432/horecaos` |
| `HORECAOS_DB_USERNAME` | `horecaos` |
| `HORECAOS_DB_PASSWORD` | `horecaos` |
| `HORECAOS_DB_MAX_POOL_SIZE` | `10` |
| `HORECAOS_DB_MIN_IDLE` | `2` |
| `HORECAOS_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |
| `HORECAOS_KAFKA_TENANCY_EVENTS_TOPIC` | `tenancy.events` |
| `HORECAOS_KAFKA_MAX_BLOCK_MS` | `10000` |
| `HORECAOS_KAFKA_REQUEST_TIMEOUT_MS` | `5000` |
| `HORECAOS_KAFKA_DELIVERY_TIMEOUT_MS` | `10000` |
| `HORECAOS_OUTBOX_RELAY_ENABLED` | `true` |
| `HORECAOS_OUTBOX_POLL_INTERVAL` | `1s` |
| `HORECAOS_OUTBOX_BATCH_SIZE` | `20` |
| `HORECAOS_OUTBOX_LEASE_DURATION` | `5m` |
| `HORECAOS_OUTBOX_PUBLISH_TIMEOUT` | `10s` |
| `HORECAOS_OUTBOX_MAX_ATTEMPTS` | `10` |
| `HORECAOS_OUTBOX_INITIAL_BACKOFF` | `1s` |
| `HORECAOS_OUTBOX_MAX_BACKOFF` | `5m` |
| `HORECAOS_OIDC_ISSUER_URI` | `http://localhost:8081/realms/horecaos` |
| `HORECAOS_OIDC_AUDIENCE` | `horecaos-api` |
| `HORECAOS_OIDC_CLIENT_ID` | `horecaos-api` |
| `HORECAOS_API_VERSION` | `v1` |
| `HORECAOS_API_DOCS_ENABLED` | `true` |
| `HORECAOS_SWAGGER_UI_ENABLED` | `true` |

Do not commit local secret files. Production credentials must come from the
deployment secrets manager. Set both documentation flags to `false` in an
environment where API discovery and interactive Swagger access must be
disabled.

The application uses Flyway and Spring JDBC rather than Hibernate. A tenancy
write and its outbox row share the ordinary PostgreSQL transaction. The relay
publishes afterward and exposes `horecaos.outbox.publications` counters tagged by
`published`, `retry`, or `dead-letter` outcome. Disabling the relay stops Kafka
publication without preventing business transactions from accumulating safely
in the outbox.

## Module layout

Each direct, explicitly annotated package under `uz.horecaos.platform` is a
business module. Build new capability code inside one module using this
internal shape as it becomes necessary:

```text
<module>/
  api/             public commands, queries, results, and events
  application/     use-case orchestration and transactions
  domain/          aggregates, value objects, policies, and ports
  infrastructure/  persistence and external adapters
```

Do not create all four folders as placeholders. Add them when the module gains
real behavior, and keep provider DTOs and Camel types inside integration
adapters.

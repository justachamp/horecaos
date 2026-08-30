# Local development

## Requirements

- JDK 25
- Docker with Docker Compose
- Git

Maven does not need to be installed globally. The repository uses the Maven
Wrapper and pins Maven 3.9.16.

## Start dependencies

```bash
docker compose up -d
docker compose ps
```

The local stack provides:

| Dependency | Address | Local credentials |
|---|---|---|
| HorecaOS PostgreSQL | `localhost:5432/horecaos` | `horecaos` / `horecaos` |
| Keycloak PostgreSQL | `localhost:5433/keycloak` | `keycloak` / `keycloak` |
| Kafka | `localhost:9092` | none |
| Keycloak | `http://localhost:8081` | `admin` / `admin` |

These credentials are development-only and must never be reused outside a
developer machine. Keycloak imports the `horecaos` realm and a PKCE-only public
client for a future frontend at `http://localhost:5173`.

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
- **Static analysis.** Error Prone and NullAway run on every compile.
  Every finding — including NullAway's own — is demoted to a warning
  (`-XepAllErrorsAsWarnings`) and printed in the compile log; nothing fails
  the build at this stage. Promoting a check family to build-failing is a
  follow-up change made once that family's warning count is zero.
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

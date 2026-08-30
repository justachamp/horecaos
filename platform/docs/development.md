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
| Qoida PostgreSQL | `localhost:5432/qoida` | `qoida` / `qoida` |
| Keycloak PostgreSQL | `localhost:5433/keycloak` | `keycloak` / `keycloak` |
| Kafka | `localhost:9092` | none |
| Keycloak | `http://localhost:8081` | `admin` / `admin` |

These credentials are development-only and must never be reused outside a
developer machine. Keycloak imports the `qoida` realm and a PKCE-only public
client for a future frontend at `http://localhost:5173`.

## Build and test

```bash
./mvnw verify
```

The build enforces JDK 25. The first invocation downloads the pinned Maven
distribution and project dependencies.

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
a JWT issued by the local `qoida` realm for the `qoida-api` audience. Swagger
UI accepts that token through **Authorize** and deliberately does not retain it
after a page reload.

For tenant APIs, the frontend requests Keycloak's `organization:<alias>` scope
for the selected tenant. `organization:*` is supported for administrative
clients that intentionally need multiple memberships. The API reads immutable
organization IDs from the signed `organization` claim and matches them to the
tenant record; it never accepts tenant context from an arbitrary header.

The realm import adds these `qoida-api` client roles:

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
| `QOIDA_DB_URL` | `jdbc:postgresql://localhost:5432/qoida` |
| `QOIDA_DB_USERNAME` | `qoida` |
| `QOIDA_DB_PASSWORD` | `qoida` |
| `QOIDA_DB_MAX_POOL_SIZE` | `10` |
| `QOIDA_DB_MIN_IDLE` | `2` |
| `QOIDA_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |
| `QOIDA_KAFKA_TENANCY_EVENTS_TOPIC` | `tenancy.events` |
| `QOIDA_KAFKA_MAX_BLOCK_MS` | `10000` |
| `QOIDA_KAFKA_REQUEST_TIMEOUT_MS` | `5000` |
| `QOIDA_KAFKA_DELIVERY_TIMEOUT_MS` | `10000` |
| `QOIDA_OUTBOX_RELAY_ENABLED` | `true` |
| `QOIDA_OUTBOX_POLL_INTERVAL` | `1s` |
| `QOIDA_OUTBOX_BATCH_SIZE` | `20` |
| `QOIDA_OUTBOX_LEASE_DURATION` | `5m` |
| `QOIDA_OUTBOX_PUBLISH_TIMEOUT` | `10s` |
| `QOIDA_OUTBOX_MAX_ATTEMPTS` | `10` |
| `QOIDA_OUTBOX_INITIAL_BACKOFF` | `1s` |
| `QOIDA_OUTBOX_MAX_BACKOFF` | `5m` |
| `QOIDA_OIDC_ISSUER_URI` | `http://localhost:8081/realms/qoida` |
| `QOIDA_OIDC_AUDIENCE` | `qoida-api` |
| `QOIDA_OIDC_CLIENT_ID` | `qoida-api` |
| `QOIDA_API_VERSION` | `v1` |
| `QOIDA_API_DOCS_ENABLED` | `true` |
| `QOIDA_SWAGGER_UI_ENABLED` | `true` |

Do not commit local secret files. Production credentials must come from the
deployment secrets manager. Set both documentation flags to `false` in an
environment where API discovery and interactive Swagger access must be
disabled.

The application uses Flyway and Spring JDBC rather than Hibernate. A tenancy
write and its outbox row share the ordinary PostgreSQL transaction. The relay
publishes afterward and exposes `qoida.outbox.publications` counters tagged by
`published`, `retry`, or `dead-letter` outcome. Disabling the relay stops Kafka
publication without preventing business transactions from accumulating safely
in the outbox.

## Module layout

Each direct, explicitly annotated package under `uz.qoida.platform` is a
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

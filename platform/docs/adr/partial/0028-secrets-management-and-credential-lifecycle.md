# ADR 0028: Secrets management and credential lifecycle

- Decision status: Accepted
- Implementation status: Partial — the read path is built and in use. `SecretReference`
  is the provider-neutral format, `SecretResolver` has an OpenBao KV v2 adapter speaking
  plain HTTP with a five-minute cache and a `resolveFresh` path, an
  `EnvironmentSecretResolver` for local, and `SecretsProfileGuard` refusing the
  environment resolver outside local. `SecretCategory` names the eight categories.
  OpenBao runs in both `compose.yaml` (dev mode, seeded) and `compose.production.yaml`
  (sealed, raft, agent-delivered AppRole token), with least-privilege policies in
  `infra/openbao/policies/horecaos-platform.hcl` and `horecaos-deploy.hcl`. Not built: creation,
  rotation and revocation APIs — nothing writes a secret, so every rotation is a manual
  `bao kv put`; no audit device is enabled and no raft-snapshot backup job exists on the
  colocated host, only a note in `docs/runbooks/restore.md`; rotation periods and expiry
  alerting are undefined; and there are no per-category rotation or compromise runbooks.
- Date proposed: 2026-08-20
- Date decided: 2026-08-20
- Deciders: Ayubkhon Abbosov (platform architecture), security
- Depends on: ADR 0034
- Supersedes / Superseded by: —
- Open inputs: none
- Closed inputs: OpenBao self-hosted in ADR 0034 phase one; AWS Secrets Manager or KMS in phase two (2026-08-20)

## Context

A secrets manager is required from roadmap step three. ADR 0007 resolves
provider credentials "at call time from the environment's secrets manager", ADR
0009 needs a confidential Keycloak service-account credential, ADR 0011 stores
only a secret reference, and ADR 0013 keeps merchant secrets outside the
database. None of them names a secrets manager, states how a reference is
formed, or defines rotation.

Meanwhile the only concrete secret handling in the repository today is
`.env.example` and compose environment variables, which is correct for local
development and not a production answer.

Choosing this late is expensive: every provider integration would embed
assumptions about how credentials are fetched, and retrofitting rotation into
live provider integrations means touching payment and POS paths.

## Decision

- **Secrets are never stored in Qoida's database, Git, container images, Kafka,
  logs, configuration endpoints, or API responses.** PostgreSQL stores an opaque
  secret reference only.
- **A secret reference is a structured, non-guessable identifier**, not a path a
  caller can construct:
  `horecaos:{environment}:{category}:{ownerScope}:{opaqueId}`. Reference format is
  stable across rotations; the value behind it changes.
- **OpenBao is the default self-hosted secrets manager.** It is MPL-2.0 under
  Linux Foundation governance, is API-compatible with HashiCorp Vault, and
  carries no licensing restriction on a company that resells a SaaS product
  built on it. HashiCorp Vault moved to the Business Source License in 2023,
  which is a licensing risk Qoida does not need to accept for a component this
  central.
- **OpenBao is self-hosted in ADR 0034 phase one.** Colocation offers no managed
  secrets service, so this is settled rather than preferred. It runs locally
  through `compose.yaml` in dev mode, seeded with worthless local values, and
  `horecaos.secrets.provider` selects between it and the environment resolver.
- **AWS Secrets Manager or KMS replaces it in phase two.** The `SecretResolver`
  port exists to make that a deployment change rather than a code change, which
  means it must never leak a provider concept: no ARNs, no provider-specific
  reference formats, no assumptions about lease semantics. The reference format
  defined below is deliberately provider-neutral for exactly this reason.
- **The application accesses secrets through one `SecretResolver` port** with
  bounded caching, explicit TTL, and no ability to enumerate. A module asks for
  the secret behind a reference it already legitimately holds.
- **Rotation is expected, not exceptional.** Every secret has an owner, a
  rotation period, and a documented rotation procedure. Rotation changes the
  value behind a stable reference so no business row is rewritten and no
  installation identifier changes.
- **Dual-secret windows are supported** for providers that cannot rotate
  atomically: a previous value stays valid for a bounded overlap, and
  verification uses whichever succeeds while recording which was used.
- **Workload identity, not a bootstrap password.** Each runtime role from ADR
  0023 authenticates to the secrets manager with its own identity and receives a
  least-privilege policy scoped to the categories it needs. The `api` role
  cannot read migration credentials; the `migration` role cannot read provider
  credentials.
- **Local development uses a separate mechanism deliberately.** `.env` files and
  compose variables remain for local work, with values that are never valid
  anywhere else, and a startup check refuses to run a non-local profile with
  file-based secrets.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| HashiCorp Vault | The mature incumbent with the largest ecosystem, and OpenBao is API-compatible precisely because it began as a fork of it. Rejected as default because the Business Source License is not OSI-approved and constrains a company distributing a commercial platform. Self-hosting for internal use is permitted today, but accepting a licensing dependency this central is an avoidable risk | Qoida requires an Enterprise capability OpenBao lacks, such as advanced replication or HSM integration, and legal accepts the licence |
| Environment variables injected by the orchestrator | Simple and widely used, but secrets end up in process listings, crash dumps, and orchestrator state, rotation requires a restart, and there is no per-secret audit of who read what and when | Never for provider and payment credentials. Acceptable for a bootstrap credential used only to authenticate to the secrets manager |
| Encrypted columns in PostgreSQL with an application key | Moves the problem rather than solving it: the key must live somewhere, database backups now contain secret material, and rotation becomes a data migration | Only as an explicitly approved, time-bounded fallback where no manager is available in a target environment |
| Kubernetes Secrets alone | Base64-encoded, not encrypted at rest by default, readable by anyone with namespace access, and with no rotation or lease semantics | As the delivery mechanism for a workload identity token, not as the store |
| A cloud provider's managed secrets service | The phase-two answer, and unavailable in phase one: ADR 0034 hosts locally first, and local providers offer no managed secrets service. Deferred rather than rejected | ADR 0034 phase two. The port and the neutral reference format are what keep the switch cheap |
| Sealed secrets or SOPS committed to Git | Fine for configuration, wrong for credentials that must rotate independently of a deployment and be revoked immediately on compromise | For non-secret configuration only |

## Consequences

### Positive

- Provider credentials can be rotated and revoked without a deployment and
  without touching business data.
- A database compromise does not yield provider or payment credentials.
- Per-role least privilege means a compromised worker cannot read the
  credentials of an unrelated capability.

### Negative

- A new critical infrastructure dependency: if the secrets manager is
  unavailable, provider calls fail once caches expire. Its availability becomes
  part of the platform's availability budget.
- Cache TTL is a genuine trade-off between rotation latency and blast radius
  during an outage, and getting it wrong is either slow revocation or fragile
  operation.
- Local, CI, staging, and production now differ in how secrets are supplied,
  which is a source of environment-specific failure.

### Accepted trade-offs

- Bootstrap remains a real problem: something must authenticate to the manager.
  Workload identity reduces it to a platform-issued token rather than removing
  it entirely, and that token's lifecycle is an operational obligation.
- Dual-secret windows mean two valid credentials exist briefly, which is
  deliberate and must be bounded and audited.

## Contract

```java
interface SecretResolver {
    SecretValue resolve(SecretReference reference);       // cached, bounded TTL
    SecretValue resolveFresh(SecretReference reference);  // bypass cache after auth failure
}

interface SecretAdministration {
    SecretReference create(CreateSecretCommand command);  // value never returned
    void rotate(SecretReference reference, RotationCommand command);
    void revoke(SecretReference reference, String reason);
    SecretMetadata describe(SecretReference reference);   // metadata only
}
```

`SecretValue` is a wrapper whose `toString` is redacted, which is not cosmetic:
it is the last defence against a credential reaching a log line or an exception
message. It is cleared after use where the runtime allows.

On a provider authentication failure the adapter calls `resolveFresh` exactly
once before classifying the failure, so a rotation that happened mid-cache does
not become a false provider outage.

## Secret categories and owners

```text
PROVIDER_POS            per ADR 0026 installation
PROVIDER_PAYMENT        per ADR 0026 installation, category PAYMENT
PROVIDER_DELIVERY       per ADR 0026 installation, category DELIVERY
PROVIDER_NOTIFICATION   per ADR 0026 installation, category NOTIFICATION
IDENTITY_ADMIN          Keycloak service account per ADR 0009
DATA_ENCRYPTION         envelope key material per ADR 0029
DATABASE                per runtime role per ADR 0023
OBJECT_STORAGE          per role, API and migration separated, per ADR 0010
```

Every category has a named owner, a rotation period, a documented procedure, and
an alert on approaching expiry.

## Security rules

- Access is authenticated by workload identity and authorized by least-privilege
  policy per role and category.
- Every read, create, rotation, and revocation is audited in the manager and
  mirrored as an ADR 0027 `SECURITY` audit fact where Qoida initiates it.
- Secret material is never logged, traced, placed in metrics, included in error
  responses, or written to the outbox.
- Compromise response is a runbook: revoke, rotate, invalidate caches, verify
  provider state, and record an incident.
- A test scans captured logs, traces, and API responses for known test secret
  values and fails the build on any match.

## Testing

- A rotated credential is picked up within the configured TTL without restart.
- An authentication failure triggers exactly one fresh resolution before the
  failure is classified.
- A role cannot read a category outside its policy, asserted against a real
  manager instance in integration tests.
- A dual-secret window accepts both values and records which was used.
- Starting a non-local profile with file-based secrets fails at startup.
- No secret value appears in any captured output.

## Rollout and rollback

Stand up the manager in a non-production environment first and migrate the
Keycloak service-account credential as the first real secret, because it is
required by ADR 0009 and has a clean rotation story. Then move object storage
and database credentials per role, then provider credentials as each ADR 0026
category ships. Rollback returns a specific category to the previously approved
delivery mechanism; it never returns secret values to the database.

## Implementation checklist

- [x] Confirm the phase-one and phase-two secrets managers (OpenBao, then AWS).
- [x] Provision OpenBao locally through compose, seeded with the secrets the platform expects.
- [ ] Provision OpenBao on the colocated server with backup and audit enabled.
- [ ] Define workload identities and least-privilege policies per runtime role.
- [x] Implement `SecretResolver`, caching, redaction, and the fresh-resolve path.
- [x] Implement the provider-neutral reference format and the OpenBao KV v2 adapter. Creation, rotation, and revocation APIs remain.
- [ ] Define categories, owners, rotation periods, and expiry alerts.
- [x] Add the non-local secret startup guard (`SecretsProfileGuard`).
- [ ] Write rotation and compromise runbooks per category.
- [x] Add rotation, redaction, and reference-format tests. Dual-window and per-role isolation arrive with the OpenBao adapter.

## Exit criteria

No credential exists in the database, repository, image, or log; every provider
and platform credential can be rotated without a deployment or an identifier
change; each runtime role can read only its own categories, proven by a denied
cross-category read in an integration test; and a compromise runbook has been
rehearsed for at least one category.

## References

- [ADR 0026: Provider installations, bindings, and secret references](../built/0026-provider-installations-bindings-and-secret-references.md)
- [ADR 0034: Hosting environments, topology, and data residency](../partial/0034-hosting-environments-topology-and-data-residency.md)

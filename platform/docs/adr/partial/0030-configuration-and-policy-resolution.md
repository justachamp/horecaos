# ADR 0030: Configuration and policy resolution

- Decision status: Accepted
- Implementation status: Partial — the read half is built and is the platform's only
  resolution chain. V0005 creates `tenant.configuration_values`, `tenant.policies` and
  `tenant.policy_current` with ancestry constraints; `ConfigurationKeys` plus the module
  key registries (`CommercialConfigurationKeys`, `TelemetryConfigurationKeys`) are
  validated at startup by `ConfigurationKeyStartupValidator`; `JdbcConfigurationResolver`
  and `JdbcPolicyResolver` implement precedence, explicit-null semantics, the resolution
  trace and pinned re-resolution. The two have very different reach:
  `ConfigurationResolver` has two consumers, `EnforcementCeiling` and
  `TelemetryIngestService`, while `PolicyResolver` has twelve across courier, fiscal,
  ordering and — as of V0054 — fulfillment, where `DeliveryPlanningService` and
  `DeliverySourcingService` resolve the sourcing policy. ADR 0050 gives approval actions
  a code-owned typed register and `JdbcApprovalService` uses `ResourceScope.chain()` for
  the canonical precedence rule, but approval-policy rows remain an audit-owned
  snapshotting exception rather than a `PolicyResolver` consumer; their V0082 scope shape
  now matches the shared model exactly. V0012 moved order acceptance off its specialised
  table. Not built: authoring.
  No production code inserts or updates any of the three tables — the only rows ever
  written are V0012's seeded order-acceptance policies — and there is no control-plane
  read or write API, so an operator cannot set a configuration value or activate a policy
  version at all. Caching is registered in `CacheRegistry` as `tenant.configuration` and
  `tenant.policy_current` but no resolver is `@Cacheable` and no outbox-driven eviction
  exists.
- Date proposed: 2026-08-20
- Date decided: 2026-08-20
- Deciders: Ayubkhon Abbosov (platform architecture)
- Depends on: ADR 0002, ADR 0004
- Supersedes / Superseded by: —
- Open inputs: none

## Context

The resolution chain `platform default -> tenant default -> brand override ->
location override` appears in the README, in `AGENTS.md`, and inside at least
eight ADRs: order acceptance in 0002, provider policy in 0011, field authority
in 0012, compensation approval in 0013, sourcing and subsidy in 0014, price book
assignment in 0018, checkout and timeout policy in 0019, and routing policy in
0020. ADR 0027 now adds approval thresholds.

Each one re-describes the same mechanism in its own words, and several add the
same extra requirement independently: the resolved policy must be versioned and
snapshotted onto the business fact so that changing a policy later cannot change
what an already-decided order, refund, or approval was permitted to do.

Nine implementations of one mechanism will differ in precedence, in what a null
override means, in caching, and in whether snapshots are taken. Those
differences produce bugs that are invisible until a policy changes and a
historical record becomes unexplainable.

The one implementation that exists today, `ordering.order_acceptance_policies`
from migration V0003, already encodes the pattern correctly: scoped rows,
version columns, and partial unique indexes for the current version per scope.
This ADR generalizes what is already working rather than inventing something
new.

## Decision

One typed, versioned, snapshot-capable configuration and policy mechanism,
owned by `tenancy` and consumed through a port by every module.

- **Two distinct things, one mechanism.** A *setting* is a scalar value such as
  a timeout or a locale. A *policy* is a versioned document such as an
  acceptance policy or an approval threshold set. Both resolve by scope; only
  policies are snapshotted onto business facts.
- **Keys are code-owned and typed.** A configuration key declares its type,
  scope levels at which it may be set, default value, owner module, and whether
  it is tenant-visible. An unknown key fails at startup, not at read time.
- **Resolution is strictly most-specific-wins**: `LOCATION`, then `BRAND`, then
  `TENANT`, then `PLATFORM`. Resolution stops at the first level that has an
  explicit value.
- **Absence and null are different.** No row means "not set at this level,
  continue". An explicitly null-valued row means "explicitly unset here", which
  also continues but is visible in the resolution trace. A key may declare that
  an explicit null instead terminates resolution with no value.
- **No partial merging of policy documents by default.** A policy set at brand
  level replaces the tenant policy entirely rather than merging field by field,
  because field-level merge produces effective policies that nobody wrote and
  nobody can review. A key may opt into declared field-level merge where the
  product genuinely needs it, and that choice is part of the key definition.
- **Every resolution can explain itself.** The resolver returns the value, the
  level it came from, the policy version, and the full trace, and that trace is
  what control-plane screens and support tooling display.
- **Policies are immutable once referenced.** Editing a policy creates a new
  version. Business facts store `policy_id` and `policy_version`, so
  reconstructing why an order was auto-confirmed in March needs no guesswork.
- **Caching is local with event invalidation**, PostgreSQL remains
  authoritative, and a cache outage degrades to a database read.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Let each module implement its own scoped resolution, as today | Nine implementations that will disagree about precedence, null semantics, caching, and snapshotting. The disagreements surface only when a policy changes and history becomes unexplainable | Never |
| A single untyped key-value table with string values | Every consumer parses and validates independently, typos become silent defaults, and no startup validation is possible | Never |
| Spring `@ConfigurationProperties` and profiles | Correct for deployment-time platform configuration and retained for it. It cannot express per-tenant, per-brand, or per-location values, which is the entire requirement | Never for tenant-scoped values |
| A feature-flag service such as an external SaaS platform | Good ergonomics for rollout flags, but tenant-scoped business policy is not a flag: it must be versioned, snapshotted, audited, and reconstructable years later, and putting it in an external service adds a dependency on a business-critical read path and a residency question under ADR 0034 | Rollout flags multiply enough to want dedicated tooling; keep business policy here regardless |
| Field-level merge across scopes by default | Produces an effective policy that exists in no reviewable record. An operator sees a brand override and cannot tell what the location will actually do | Never as the default; opt-in per key |
| Store policies as JSONB with no version pinning on business facts | A policy edit silently rewrites the meaning of every historical decision that referenced it | Never |
| Resolve entitlements through this mechanism too | Entitlements are commercial grants with their own lifecycle, plan versioning, and enforcement modes, owned by ADR 0021. Overloading configuration with commercial state would blur permission, entitlement, and configuration | Never; they compose instead |

## Consequences

### Positive

- One precedence rule, one null semantic, and one snapshot mechanism across
  every capability that has scoped behavior.
- Support can answer "why did this location behave that way" from a resolution
  trace instead of reading code.
- Historical decisions stay explainable because policy versions are pinned.

### Negative

- A shared mechanism becomes a shared dependency: a resolver defect affects
  every module simultaneously.
- Typed keys mean adding a setting requires a code change, which is slower than
  inserting a row and is the price of startup validation.
- Immutable policy versions accumulate, and old versions can never be deleted
  while any business fact references them.

### Accepted trade-offs

- Replace-not-merge is less flexible than field-level inheritance and is chosen
  because reviewability matters more than expressiveness for policies that
  decide money and order acceptance.
- Caching introduces a bounded staleness window after a policy change, which
  support will occasionally perceive as a change not taking effect.

## Physical model

```text
tenant.configuration_values
  id, key_code, scope_type, scope_id null, tenant_id null
  value_type, boolean_value null, integer_value null, decimal_value null
  string_value null, json_value null, is_explicit_null
  set_by, reason null, version, created_at, updated_at
  unique(key_code, scope_type, scope_id)

tenant.policies
  id, key_code, tenant_id null, scope_type, scope_id null
  version, status, document jsonb, document_hash
  valid_from, valid_until null
  created_by, approved_by null, created_at, retired_at null
  unique(key_code, scope_type, scope_id, version)

tenant.policy_current
  key_code, scope_type, scope_id, policy_id, policy_version
  unique(key_code, scope_type, scope_id)
```

Composite foreign keys carry tenant and brand ancestry so a location-scoped
value cannot reference a location outside its tenant. `tenant.policy_current` is
maintained transactionally with activation, which reproduces the partial-unique
current-version pattern already used by `ordering.order_acceptance_policies`.

## Contract

```java
interface ConfigurationResolver {
    <T> Resolved<T> resolve(ConfigurationKey<T> key, ResourceScope scope);
    ResolutionTrace explain(ConfigurationKey<?> key, ResourceScope scope);
}

interface PolicyResolver {
    <P> ResolvedPolicy<P> resolve(PolicyKey<P> key, ResourceScope scope);
    <P> ResolvedPolicy<P> pinned(PolicyKey<P> key, PolicyId id, int version);
}
```

`ResolvedPolicy` exposes the document, its identifier, and its version. Callers
that make a durable decision persist those two values with the business fact.
`pinned` is how a historical fact is re-read under the policy that actually
applied to it.

## Migration of the existing acceptance policy

`ordering.order_acceptance_policies` already implements this pattern for one
key. It is either migrated into `tenant.policies` under key
`ordering.acceptance` or explicitly retained as a specialized table, and the
choice is recorded before ADR 0019 begins. It must not remain as an
undocumented parallel mechanism.

## Security and audit

- Reading a tenant-visible setting requires the corresponding read capability
  from ADR 0025; changing one requires an explicit write capability.
- Platform-scoped values are platform-admin only.
- Every change records actor, reason, previous value, and new value as an ADR
  0027 audit fact.
- Policy activation above configured risk thresholds requires ADR 0027 approval.
- Configuration never carries secrets; a secret is a reference under ADR 0028,
  and a startup check rejects any key declaring both.

## Testing

- Precedence is exhaustively tested for every combination of levels present.
- Explicit null continues resolution and appears in the trace.
- A pinned policy version resolves identically after a newer version activates.
- Cross-tenant and cross-brand scoped values are rejected at the database.
- Unknown or type-mismatched keys fail at startup.
- Cache invalidation applies a change within the agreed bound, and a cache
  outage falls back to the database rather than to a default.

## Rollout and rollback

Introduce the mechanism with the key registry and resolver, migrate order
acceptance as the first real key, then adopt it per ADR as each capability
ships. Rollback pins consumers to platform defaults while stored values and
audit evidence are retained.

## Implementation notes

Delivered so far: the scope model, the typed key registry with startup
validation, precedence resolution with explicit-null semantics and a trace, the
policy tables, and pinned policy re-resolution.

`ordering.order_acceptance_policies` has been migrated into `tenant.policies`
under key `ordering.acceptance` and the specialised table dropped, so the
platform no longer holds two implementations of scoped resolution. The domain
document lost its own id, scope, and version fields, because `ResolvedPolicy`
carries them — a second copy was precisely how the two mechanisms could have
disagreed about which version applied. `ordering.PolicyScope` is gone too,
superseded by `ResourceScope`.

Deliberately not yet delivered, and tracked in the checklist below: policy
authoring and activation commands, caching (ADR 0033), and control-plane read
and write APIs. Precedence lives in `ScopeResolution` as a
pure function of key, scope, and fetched rows, so it is tested exhaustively
without a database.

## Implementation checklist

- [x] Add configuration value, policy, and current-policy tables with ancestry constraints (`V0005`).
- [x] Implement the typed key registry and startup validation (`ConfigurationKeys`, `ConfigurationKeyStartupValidator`).
- [x] Implement resolution, explicit-null semantics, and the resolution trace (`ScopeResolution`, `JdbcConfigurationResolver`).
- [x] Implement pinned re-resolution and scope precedence (`JdbcPolicyResolver`). Authoring and activation commands remain.
- [x] Migrate `ordering.order_acceptance_policies` into the shared mechanism and drop the specialised table (`V0012`).
- [ ] Implement caching with outbox-driven invalidation.
- [ ] Add control-plane read and write APIs with ADR 0025 capabilities and ADR 0027 audit.
- [x] Add precedence, pinning, and isolation tests (`ScopeResolutionTests`, `ResourceScopeTests`, `JdbcConfigurationResolverTests`, `JdbcPolicyResolverTests`).

## Exit criteria

Every scoped behavior in the platform resolves through one mechanism with one
precedence rule; any resolved value can be explained with its source level and
version; a business fact that referenced a policy resolves to the same policy
after that policy changes; and no module retains a private scoped-configuration
table.

## References

- [ADR 0002: SaaS domain model and order acceptance](../partial/0002-saas-domain-model.md)
- [ADR 0027: Audit evidence and the approval model](../partial/0027-audit-evidence-and-approval-model.md)
- [ADR 0050: Missing approval policy behavior](../built/0050-missing-approval-policy-behavior.md)

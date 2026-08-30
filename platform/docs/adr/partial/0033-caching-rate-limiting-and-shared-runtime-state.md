# ADR 0033: Caching, rate limiting, and shared runtime state

- Decision status: Accepted
- Implementation status: Partial — `CacheRegistry` enumerates all six caches with TTL,
  bound and invalidation source, `CacheConfiguration` builds a fixed Caffeine cache per
  entry so an unregistered name cannot be created implicitly, and `CacheWiringTests` plus
  `CachingAndRateLimitingTests` assert that and forbid cache reads on correctness paths.
  Two caches are actually wired: `iam.grants` on `JdbcAuthorizationService`, evicted by
  `GrantManagementService` on every grant and revocation, and `tenant.hierarchy` on
  `JdbcResourceScopeVerifier`, which caches positive answers only.
  `InProcessRateLimiter` implements the `RateLimiter` port and now has five call sites:
  QR entry, the partner order API, telemetry ingest, the operations stream, and — as of
  V0055 — `CustomerVerificationService`, which carries the per-caller half of the one-time
  code limit (six issues and fifteen attempts a minute) while the per-number half is a
  condition on `customer.verification_challenges`.
  `README.md` names Valkey as deferred. Not built: the registered
  `tenant.configuration`, `tenant.policy_current`, `commercial.entitlements` and
  `integration.environments` caches have no `@Cacheable` behind them, so four of the six
  registry entries describe caches that do not exist; there are no edge rate limits
  (nothing in `infra/` or `compose.production.yaml` configures the reverse proxy); and the
  measured trigger and runbook for introducing Valkey are unwritten.
- Date proposed: 2026-08-20
- Date decided: 2026-08-20
- Deciders: Ayubkhon Abbosov (platform architecture)
- Depends on: ADR 0001, ADR 0023
- Supersedes / Superseded by: —
- Open inputs: none

## Context

The architecture diagram in `README.md` shows `API --> Redis["Redis cache and
rate limits"]`. Redis appears nowhere else: not in an ADR, not in
`compose.yaml`, not in the module boundaries, not in `AGENTS.md`. The only other
mentions describe removing the legacy system's Redis Pub/Sub in favour of Kafka.

So the platform's public architecture claims a component that no decision
covers, whose data model, invalidation strategy, failure behavior, and licensing
have never been examined. Several ADRs also assume caching exists without saying
where it lives: ADR 0016 caches storefront projections with ETags, ADR 0021
caches entitlement snapshots, ADR 0025 caches grants, and ADR 0030 caches
resolved configuration.

Rate limiting is assumed too. ADR 0009 requires rate-limiting onboarding and
invitations, ADR 0023 requires per-tenant and per-provider quotas, and ADR 0031
defines `429` and `Retry-After` responses without saying what enforces them.

## Decision

### Caching is layered, and most of it is in-process

1. **In-process caches (Caffeine) are the default** for authorization grants,
   resolved configuration and policies, entitlement snapshots, capability
   registries, and provider environment reference data. These are small,
   read-dominated, tenant-scoped, and tolerate a bounded staleness window.
2. **A shared cache is used only where in-process is genuinely insufficient**:
   storefront catalog and availability projections, where per-replica duplicate
   computation is measurable and invalidation must be immediate across replicas.
3. **PostgreSQL is always the authority.** Every cache is a disposable
   accelerator. A cache miss, eviction, or total outage degrades to a database
   read. No cache is ever the only copy of anything, and no correctness decision
   — reservation, idempotency, deduplication — is made from cache state. ADRs
   0005 and 0017 already reject cache-based correctness explicitly.
4. **Invalidation is event-driven with TTL as a backstop.** Outbox events
   invalidate by stable scope key; TTL exists so a missed invalidation heals
   rather than persisting indefinitely.

### The shared cache is Valkey, not Redis

When a shared cache is introduced, it is **Valkey**, self-hosted in ADR 0034
phase one and self-hosted or managed in phase two: the BSD-3-Clause,
Linux-Foundation-governed fork of Redis 7.2.4, protocol-compatible with existing
Redis clients. Redis itself moved to SSPL and RSALv2 in 2024 and added AGPLv3 in
2025; none of those is a licence a company distributing a commercial multi-tenant
platform should adopt for an infrastructure dependency when a BSD-licensed,
drop-in, actively maintained alternative exists.

Valkey is **not** used for: durable work queues, event distribution, locks
protecting correctness, session storage, idempotency records, or deduplication.
Kafka owns event distribution and PostgreSQL owns durable state and locks.

### Rate limiting

- **Edge first.** Coarse per-IP and per-route limits belong at the CDN, WAF, or
  gateway from ADR 0023, because a request rejected at the edge costs nothing.
- **Application limits are per tenant, per principal, and per capability** for
  the operations the edge cannot see: onboarding and invitation actions (ADR
  0009), quote creation and checkout (ADRs 0018 and 0019), export and report
  generation (ADR 0023), and outbound provider calls.
- Application limits use a token-bucket algorithm. While a single API replica
  set is small, limits are enforced in-process with a conservative per-replica
  budget. When replica count makes that inaccurate, enforcement moves to Valkey
  with atomic scripted counters behind the same port.
- **Outbound provider rate limits are Camel route policies** under ADR 0007, not
  a shared counter, because they are per provider binding and already need
  circuit breaking beside them.
- Exceeding a limit returns `429` with `Retry-After` and the ADR 0031 error code
  `RATE_LIMIT_EXCEEDED`. Limits are configured through ADR 0030 so they resolve
  per tenant where a plan justifies a different budget.
- Rate limiting **fails open for reads and closed for expensive writes** when
  the limiter backend is unavailable, and that choice is per limit rather than
  global.

### Sessions

There is no server-side session store. Frontends hold tokens in memory under ADR
0022 and APIs are stateless under ADR 0023. This is the single largest reason
the platform does not need Redis on day one.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Adopt Redis as the README implies | Licensing is the deciding factor: SSPL and RSALv2 restrict exactly the kind of commercial platform Qoida is, and AGPLv3 brings its own distribution questions. Valkey is protocol-compatible, BSD-licensed, and backed by the Linux Foundation and major vendors | A Redis-only module such as RediSearch or RedisJSON becomes genuinely necessary and no Valkey equivalent exists. That would be a deliberate licence decision |
| Introduce a shared cache immediately for everything | Adds an always-on infrastructure dependency, a new failure mode, and a serialization boundary before any measurement shows a problem. In-process caching of small tenant-scoped data is faster and simpler | A measured hit-rate or duplicate-computation problem appears across replicas, most likely on storefront projections |
| No caching at all; rely on PostgreSQL | Authorization, configuration, and entitlement lookups occur several times per request. Hitting the database for each would create avoidable load and latency for data that changes rarely | Never |
| Use Valkey for distributed locks | Distributed locking over a cache has well-known correctness edge cases under failover, and the platform already has `FOR UPDATE SKIP LOCKED`, lease tokens, and compare-and-set in PostgreSQL, which are transactional with the work they protect | Never for correctness-bearing locks |
| Use Valkey for idempotency and deduplication | Eviction or failover silently reopens a duplicate window with no evidence. ADRs 0005 and 0031 both place this in PostgreSQL | Never |
| Hazelcast or Infinispan as an embedded distributed cache | Clustering inside the application adds split-brain and rolling-restart complexity to a deployment that otherwise has none | Never while a shared cache remains optional |
| Rate limit only at the edge | The edge cannot see tenant, principal, capability, or plan, so it cannot express "this tenant may create ten onboarding runs per hour" | Never as the only layer; it remains the first layer |
| Remove Redis from the README and add nothing | Caching and rate limiting are genuinely required by several ADRs. Silence would leave the same gap in the other direction | Never |

## Consequences

### Positive

- The architecture diagram becomes true, and the caching claim is backed by a
  decision with licensing, failure behavior, and invalidation defined.
- The platform ships without a shared cache dependency, so there is one fewer
  service to run, secure, back up, and reason about at launch.
- Correctness never depends on cache state, so a cache outage degrades latency
  rather than integrity.

### Negative

- In-process caches multiply per replica, so a cached value can be stale in one
  replica and fresh in another for the duration of the TTL, which produces
  confusing support reports.
- Per-replica rate limiting is approximate: the effective global limit varies
  with replica count, so budgets must be set conservatively.
- Introducing Valkey later means a second invalidation path is added to code
  that already works, which is a deliberate but real migration.

### Accepted trade-offs

- Bounded staleness is accepted for authorization grants and entitlements, so a
  revoked grant may remain effective for the TTL. The TTL is therefore short,
  and revocation-sensitive paths may force a fresh read.
- Deferring the shared cache means the first storefront load test may reveal the
  need for it, and that work is planned rather than pre-built.

## Implementation notes

Delivered: the cache registry with a TTL, size bound, and invalidation source
per cache; a Caffeine cache manager restricted to registered names so an
unregistered cache cannot appear implicitly; and the `RateLimiter` port with an
in-process token-bucket implementation.

Per-replica limiting is approximate by construction: with N replicas the
effective global budget is roughly N times the configured one, so budgets are
set conservatively. That is the accepted cost of not running a shared cache yet,
and the port is what keeps the eventual move to Valkey a configuration change.

The ADR 0025 grant lookup now reads through `iam.grants`, which has the shortest
TTL in the registry: a stale allow is worse than a stale configuration value, so
a revoked grant must stop working faster than a changed setting propagates. A
test pins that relationship rather than leaving it to whoever edits the registry
next.

Two architecture tests make the decision enforceable instead of conventional:
every `@Cacheable` must name a registered cache, and no class on a correctness
path — idempotency, the inbox, approvals, audit — may carry one at all.

Not yet delivered: caching the ADR 0030 resolvers, event-driven invalidation,
and edge rate limits.

## Cache inventory

Every cache is registered with its key shape, TTL, invalidation event, size
bound, and failure behavior. An unregistered cache fails a startup check.

```text
iam.grants                 principal+tenant     short TTL   TenantGrantsChanged
tenant.configuration       key+scope            short TTL   ConfigurationChanged
tenant.policy_current      key+scope            short TTL   PolicyActivated
commercial.entitlements    tenant               short TTL   TenantEntitlementsChanged
integration.environments   provider+environment long TTL    deployment
catalog.publication_view   brand+location+locale ETag+event CatalogPublished
inventory.availability     location+variant     very short  InventoryAvailabilityChanged
```

Metrics expose hit rate, size, eviction, and load latency per cache with bounded
labels and never a tenant identifier, per ADR 0023.

## Contract

```java
interface RateLimiter {
    RateLimitDecision check(RateLimitKey key, RateLimitPolicy policy);
}
```

`RateLimitKey` composes tenant, principal, capability, and operation. The port
hides whether enforcement is in-process or shared, so moving to Valkey changes
configuration rather than call sites.

## Testing

- A cached authorization grant is invalidated within the agreed bound after
  revocation, and a limiter or cache outage follows the declared failure mode.
- No correctness path reads from cache, asserted by an architecture test that
  forbids cache access inside reservation, idempotency, and inbox code.
- Rate limits return `429` with `Retry-After` and the registered error code.
- Cache metrics carry no unbounded labels.
- Every registered cache has a declared invalidation source, and startup fails
  if one is missing.

## Rollout and rollback

Ship in-process caches and in-process rate limiting first, with metrics from the
start. Add Valkey only when a measured cross-replica problem exists, introducing
it behind the existing ports for storefront projections first. Rollback disables
the shared cache and returns to in-process behavior without a code change.

## Implementation checklist

- [x] Correct the `README.md` architecture diagram to name Valkey and state that it is deferred.
- [x] Implement the cache registry and a fixed cache-name manager, so an unregistered cache cannot be created implicitly.
- [x] Implement the in-process cache for grants.
- [ ] Cache configuration, policies, and entitlements through the same registry.
- [x] Implement the `RateLimiter` port with in-process token buckets (`InProcessRateLimiter`).
- [ ] Configure edge rate limits with ADR 0023.
- [x] Add the architecture test forbidding cache reads on correctness paths, and one asserting every `@Cacheable` names a registered cache.
- [ ] Document the measured trigger and runbook for introducing Valkey.

## Exit criteria

Every cache in the platform is registered with a TTL, invalidation source, and
failure behavior; no correctness decision reads cache state, proven by an
architecture test; rate limits are enforced per tenant and capability with
correct `429` semantics; and the documented architecture matches what is
actually deployed.

## References

- [ADR 0023: Production operating model, observability, security, and recovery](../partial/0023-production-operating-model-observability-security-and-recovery.md)
- [ADR 0030: Configuration and policy resolution](../partial/0030-configuration-and-policy-resolution.md)

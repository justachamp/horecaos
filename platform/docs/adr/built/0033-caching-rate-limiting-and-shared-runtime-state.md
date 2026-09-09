# ADR 0033: Caching, rate limiting, and shared runtime state

- Decision status: Accepted
- Implementation status: Built — `CacheRegistry` enumerates every in-process cache with a
  TTL, size bound and invalidation source; `CacheConfiguration` builds a fixed Caffeine
  cache per entry so an unregistered name cannot be created implicitly; and
  `CacheWiringTests` plus `CachingAndRateLimitingTests` assert that and forbid cache reads
  on correctness paths. All five registered caches are wired; two entries that described caches which should not exist are gone. `iam.grants` on
  `JdbcAuthorizationService` is evicted by `GrantManagementService` on every grant and
  revocation. `tenant.hierarchy` on `JdbcResourceScopeVerifier` caches positive answers
  only. `tenant.status` on `JdbcTenantSuspensionLookup` (ADR 0078) is evicted by
  `TenantControlPlaneService` on every suspend or reactivate. As of this record:
  `tenant.configuration` on `JdbcConfigurationResolver` and `tenant.policy_current` on
  `JdbcPolicyResolver` are both keyed by key code and scope, and the tenant-scoped half of an
  installation — `status` and `secret_reference` — is read fresh on every call, because it
  feeds the outbound payment, delivery, POS, notification and SMS gateways. The
  `integration.environments` entry is **removed rather than wired**, and the attempt to
  wire it is why: its declared invalidation source was "deployment", which the application
  cannot perform, so a provider's `base_url` cached for an hour had no way to be dropped
  when it moved. That broke `anOwnerRotatesToAReferenceThatResolvesAndPassesGetMe` — a
  rotated secret resolving against a gateway whose address the cache still remembered,
  answering 422 instead of 200. `JdbcProviderEnvironmentLookup` survives as the seam that
  keeps the platform-owned half visibly separate from the tenant-scoped one; it just does
  not cache. `tenant.policy_current` is evicted
  by `JdbcPolicyAuthor` through the new `PolicyCurrentCache` port the instant a policy
  publishes, so a change an operator makes resolves on the very next read rather than
  waiting out the TTL. `tenant.configuration` has no writer yet — `ConfigurationController`
  is read-only, and nothing outside a migration or a test inserts into
  `tenant.configuration_values` — so its declared `ConfigurationChanged` invalidation has
  nothing to fire from; the sixty-second TTL is the only bound on staleness today, which is
  exactly the healing backstop this ADR's own Decision describes for a missed invalidation,
  not a broken promise. Whoever builds a configuration writer must evict this cache the same
  way `JdbcPolicyAuthor` evicts `tenant.policy_current`. `commercial.entitlements` is no
  longer registered and was never wired: ADR 0021, decided independently and already built,
  reads PostgreSQL on every entitlement resolution by design, and says so in its own "What
  was built" section — caching it would contradict a decision already shipped, so the entry
  naming a cache that must not exist is removed rather than left unfulfilled.
  `InProcessRateLimiter` implements the `RateLimiter` port. It had five call sites when
  this line was last counted; ADR 0062 (staff sign-in) and ADR 0063 (Telegram sign-in)
  each added their own since, and it now has ten: QR entry, the partner order API,
  telemetry ingest, the operations stream, `CustomerVerificationService` (the per-caller
  half of the one-time code limit — six issues and fifteen attempts a minute — while the
  per-number half is a condition on `customer.verification_challenges`),
  `SecretIngressController`, `StorefrontTelegramSignInController`,
  `CustomerBotActionAuthorizer`, `TelegramUpdateHandler` and `StaffAuthService`.
  Edge rate limits are configured: both
  `deploy/infra/caddy/Caddyfile` and `platform/infra/production/caddy/Caddyfile` carry
  `rate_limit` zones for the Payme and Click provider callbacks, the storefront browse
  surface, dine-in guest endpoints, the three identity/OTP steps, Telegram sign-in, and
  staff sign-in — landed under ADR 0023 (commit `58e8467`, 2026-09-05), before this record's
  own text had caught up with it. `README.md` names Valkey as deferred, and it still is: no
  measured cross-replica problem has appeared, so `catalog.publication_view` and
  `inventory.availability` — the shared-cache candidates this ADR's Decision names — are
  correctly unbuilt and are not in `CacheRegistry`, which governs only the in-process layer.
  Not built, and deliberately: the measured trigger and runbook for introducing Valkey,
  which is documentation for a component this ADR's own Rollout section says to add only
  once that trigger fires, not a gap in what is decided to exist today.
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

**The ADR 0030 resolvers are now cached, following the same eviction shape as
`tenant.status`.** `JdbcPolicyResolver#resolve` and `JdbcConfigurationResolver#resolve`
are both `@Cacheable`, keyed by key code and scope (platform/tenant/brand/location),
so a caller resolving the same key at the same scope repeatedly stops repeating the
query. `JdbcPolicyResolver` also implements the new `PolicyCurrentCache` port —
`tenancy.application.port` — exactly as `JdbcTenantSuspensionLookup` implements
`TenantStatusCache`, and `JdbcPolicyAuthor` calls it right after moving the
`tenant.policy_current` pointer. Before this, policy authoring (added for the
2026-08-30 proving run's Gap D) had no writer evicting the cache it was about to
sit behind; without this change, the first policy an operator published or changed
would have resolved to the old version for up to sixty seconds. `pinned()` — the
read of an exact historical policy version, never a "current" answer — is
deliberately not cached: it is diagnostic and re-explains an already-decided fact,
and it has no reason to accept a stale answer for a row that never changes.

**`tenant.configuration` has no writer to evict from yet.** `ConfigurationController`
only reads (see its own class Javadoc); nothing else in application code inserts,
updates, or deletes a `tenant.configuration_values` row — only Flyway migrations and
tests do, directly against the table. The cache is wired regardless, because nothing
in ADR 0033's Decision requires a writer to exist before an accelerator can — the
sixty-second TTL is exactly the "missed invalidation heals" backstop the Decision
already describes, and here it is the *only* mechanism rather than a backstop behind
an eviction call, which is an honest fact about today's code rather than a design
flaw. The day a configuration-authoring endpoint exists, it must evict this cache
the same way `JdbcPolicyAuthor` now evicts `tenant.policy_current`, or that endpoint
will reproduce the exact bug just fixed on the policy side.

**`integration.environments` deliberately caches less than its name might suggest.**
`JdbcProviderInstallationLookup#installation` used to join `integration.installations`
straight to `integration.provider_environments` in one query and return both in one
`InstallationSnapshot`. That snapshot's `status` and `secretReference` are per-tenant,
change at any time an operator or a provider acts, and feed the outbound payment,
delivery, POS, notification, and SMS gateways (`PaymentGateway`, `DeliveryGateway`,
`PosGateway`, `NotificationGateway`, `SmsGateway`) — caching them for the registry's
one-hour, deployment-scoped TTL would be precisely the correctness-on-a-cache mistake
`CacheWiringTests` exists to catch, just on a class the fixed `CORRECTNESS_PATHS` list
does not name. So the query is split: `JdbcProviderInstallationLookup` now reads the
tenant-scoped row fresh on every call, and a new `JdbcProviderEnvironmentLookup`
caches only `base_url` by `environmentCode` — the part ADR 0026 itself calls
"platform-owned reference data, not tenant-writable," which is what the registry's
"reference data that changes on deployment" comment was describing all along.

**`commercial.entitlements` is removed from `CacheRegistry`, not wired.** This ADR's
own Decision text names "entitlement snapshots" among the in-process caches it
expected, but ADR 0021 — an independent decision, already built — reads PostgreSQL on
every entitlement resolution on purpose, and its own "What was built, and where it
departs from the text above" section says why: caching would introduce a window
where a plan change is not yet visible, and "until a measured request path needs it,
[that] is a support ticket bought for nothing." `EntitlementQueryService`'s own class
Javadoc repeats the same reasoning independently. A registry entry describing a cache
that must not exist is exactly as dishonest as an unwired one, so it is deleted here
rather than left for the next reader to discover was never going to be built.

**Edge rate limits were already built, under ADR 0023, before this record caught up.**
Commit `58e8467` ("Harden the edge: rate limits, body caps, and a fail-closed Payme
allowlist") added `rate_limit` zones to both `deploy/infra/caddy/Caddyfile` and
`platform/infra/production/caddy/Caddyfile` — the two parallel production Caddyfiles
ADR 0061 keeps in sync — for the Payme and Click provider callbacks (per-binding,
keyed by path), the storefront browse surface and dine-in guest endpoints (per IP),
the three identity/OTP steps and Telegram sign-in (per IP, separately budgeted per
step), and staff sign-in (per IP). The authenticated API surface deliberately carries
no edge limit: the edge cannot see tenant, principal, capability, or plan, which is
exactly why `RateLimiter`/`InProcessRateLimiter` exists at the application layer for
that traffic instead of a coarse, tenant-blind duplicate at the edge.

## Cache inventory

Every cache is registered with its key shape, TTL, invalidation event, size
bound, and failure behavior. An unregistered cache fails a startup check. This
table is `CacheRegistry` in prose, and covers only the in-process layer —
`catalog.publication_view` and `inventory.availability` are the Decision's named
candidates for a future *shared* cache and are correctly absent: Valkey remains
deferred, so neither is registered, built, or startup-checked.

```text
iam.grants                 principal+tenant       short TTL   TenantGrantsChanged
tenant.configuration       key+scope              short TTL   ConfigurationChanged
tenant.policy_current      key+scope              short TTL   PolicyActivated
integration.environments   environment code       long TTL    deployment
tenant.hierarchy           tenant/brand/location   long TTL    BrandCreated, LocationCreated
tenant.status               tenant                 short TTL   TenantSuspended, TenantReactivated
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
- [x] Cache configuration and policies through the same registry (`JdbcConfigurationResolver`,
      `JdbcPolicyResolver`), with the policy side evicted by its writer
      (`JdbcPolicyAuthor` via the new `PolicyCurrentCache` port). Entitlements
      deliberately excluded: ADR 0021 already built and decided against caching
      them, so `commercial.entitlements` is removed from `CacheRegistry` rather
      than left wired to nothing.
- [x] Implement the `RateLimiter` port with in-process token buckets (`InProcessRateLimiter`).
- [x] Configure edge rate limits with ADR 0023. Landed under that ADR in commit
      `58e8467` (2026-09-05), in both production Caddyfiles; this record's status
      simply had not caught up with it until now.
- [x] Add the architecture test forbidding cache reads on correctness paths, and one asserting every `@Cacheable` names a registered cache.
- [ ] Document the measured trigger and runbook for introducing Valkey. Deliberately
      still open: the Rollout section only calls for this once a measured
      cross-replica problem appears, which has not happened, so it does not gate
      the Exit criteria below.

## Exit criteria

Every cache in the platform is registered with a TTL, invalidation source, and
failure behavior; no correctness decision reads cache state, proven by an
architecture test; rate limits are enforced per tenant and capability with
correct `429` semantics; and the documented architecture matches what is
actually deployed.

## References

- [ADR 0023: Production operating model, observability, security, and recovery](../partial/0023-production-operating-model-observability-security-and-recovery.md)
- [ADR 0030: Configuration and policy resolution](../built/0030-configuration-and-policy-resolution.md)

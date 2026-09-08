# ADR 0077: A suspended tenant stops trading

- Decision status: Accepted
- Implementation status: Partial — the rule is enforced and the transition is
  reachable from production, but nothing exposes it over HTTP yet. Wave 79 adds
  `iam.api.TenantSuspensionLookup`, answered by
  `tenancy.infrastructure.authorization.JdbcTenantSuspensionLookup` over
  `tenant.tenants.status` and cached for thirty seconds (ADR 0033,
  `tenant.status`); `JdbcAuthorizationService.applicableGrants` drops every
  non-`PLATFORM` grant when the scope names a suspended tenant, which covers
  `has`, `require` and `viewFor` and therefore the web interceptor,
  `TenantAccessPolicy`, and the non-web callers — `BotCallbackAuthorizer` above
  all — that reach `AuthorizationService` directly. `TenantControlPlaneService`
  gains platform-admin-only `suspendTenant`/`reactivateTenant`, audited under
  ADR 0027 as `tenant.suspended`/`tenant.reactivated`, writing through the new
  `TenantControlPlaneStore.updateTenantStatus` and evicting the cache so the
  refusal starts at the next request. Not built: no controller calls either
  method, so today a suspension is issued from a service and not from an
  operator's screen; no ADR 0032 event is published; and `ARCHIVED` is
  deliberately not treated as suspended (see Open inputs).
- Date proposed: 2026-09-08
- Date decided: 2026-09-08
- Deciders: platform owner (directed the work), Claude (architecture)
- Depends on: 0003, 0025, 0027, 0030, 0033
- Supersedes / Superseded by: —
- Open inputs:
  - Whether an `ARCHIVED` tenant's people keep working — owner. Archival is
    ending the relationship rather than pausing it, and the retention answer
    (ADR 0029) is not written yet. Until it is, this record enforces only
    `SUSPENDED`, and says so rather than settling a retention question inside a
    grant filter.
  - Whether a suspended tenant's own staff keep any read access — owner. This
    record says no (see Alternatives). If the answer becomes "they may read
    their invoices and export their data", the shape that supports it is a
    read-only capability set, not a hole in this rule.

## Context

`Tenant.suspend()` has existed since the tenancy module was written. It set
`status = SUSPENDED` and nothing else, and on 2026-09-08 a check of the whole
request path found that nothing anywhere read that column for an access
decision:

- `TenantAccessPolicy` contains no reference to `TenantStatus` or `SUSPENDED`.
  It checks organization membership and ADR 0025 capabilities.
- `JdbcAuthorizationService.SELECT_GRANTS` filters on `g.status = 'ACTIVE'`,
  `r.status = 'ACTIVE'` and the grant's validity window. A grant is not
  cancelled by its tenant being suspended, so every capability stayed live.
- ADR 0009's Keycloak organization flag is not an authentication control:
  `KeycloakOrganizationIntegrationTests` proves against a live Keycloak 26.7.0
  realm that a member of a *disabled* organization still completes the ADR 0062
  direct grant and receives a valid token.

So suspending a tenant left its staff able to sign in and to exercise every
capability they held. It was not exploitable, because a fourth fact made the
state unreachable: nothing in production called `suspend()` — only a domain unit
test did — and `TenantControlPlaneStore` had `updateBrandStatus` and
`updateLocationStatus` but no tenant equivalent, so there was no way to persist
the result either. The platform could not suspend a tenant, and would not have
enforced it if it could.

That second gap is why this record changes both halves. Enforcing a state that
production cannot produce would have been a rule provable only by a fixture
doing what production never does, which this repository has been bitten by
before.

## Decision

**A suspended tenant's grants do not apply. Platform-scoped grants do.**

The check lives in `AuthorizationService`, applied to the grants a subject holds
after they are read and before they are matched against the requested scope:
when the requested scope names a tenant and that tenant is suspended, only
grants at `PLATFORM` scope survive.

Three properties follow, and they are the reason for this placement:

1. **Everything that authorizes goes through it.** ADR 0025's enforcement is not
   only the web interceptor. `BotCallbackAuthorizer` calls
   `AuthorizationService.require` directly, by ADR 0060 §4's design, and so does
   `TenantAccessPolicy`. A rule placed in the interceptor would leave the staff
   Telegram bot answering taps for a suspended tenant.
2. **Suspension is not a one-way door.** A platform administrator reaches a
   tenant through the platform-admin realm role (ADR 0003) or a `PLATFORM`
   grant, neither of which this filter touches, so the platform can read, audit
   and reactivate a tenant it just suspended.
3. **It cannot be cached wrong.** The filter sits outside `grantsFor`, which is
   `@Cacheable` on subject and tenant. A cached value must not depend on
   anything absent from its key, or a suspension would wait out a TTL and a
   reactivation would too.

The transition itself is platform-admin only, on the platform's side of the
relationship: a tenant cannot lift its own suspension, and the reasons for
imposing one — non-payment, abuse, a legal instruction — are not the
restaurant's to adjudicate.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Filter inside `SELECT_GRANTS` with a join to `tenant.tenants` | Puts tenancy's status vocabulary inside iam's grant query and makes iam read tenancy's table; and the result would be cached under `iam.grants`, whose key has no room for tenant status, so a suspension would not take effect until the entry expired | iam and tenancy merge, or the grants cache key grows a status component |
| A check in `TenantAccessPolicy` | Covers only callers that go through it. Most modules' controllers reach `AuthorizationService` through the interceptor, and the staff bot reaches it directly — a suspended tenant would keep approving orders from Telegram | `TenantAccessPolicy` becomes the single entry point for every capability check |
| A request-scoped gate in `CapabilityEnforcementInterceptor` | Same coverage hole from the other side: it is a servlet interceptor, and the bot, the POS adapter and scheduled work are not requests | Every non-web caller is rewritten to enter through the web layer, which ADR 0060 §4 explicitly rejected |
| Revoke the grants themselves on suspension | Destroys information the platform needs to restore, turns a reversible business state into a migration, and races with any grant written while suspended | Suspension becomes terminal rather than reversible — at which point it is archival, not suspension |
| Let a suspended tenant's staff keep read capabilities | A tenant suspended for non-payment reading its own dashboards is arguably fine; one suspended for abuse continuing to export customer PII is not, and the capability model cannot currently express "read-only subset" without enumerating it | The owner answers the second Open input, or ADR 0029's export obligation forces a defined read path |

## Consequences

### Positive

- Suspension means something, at every entry point rather than at one.
- The platform keeps full access to a tenant it suspended, so the action is
  reversible by the party that took it.
- `viewFor` stops offering capabilities that would then be refused, so a
  frontend renders a suspended tenant honestly instead of a menu of failures.
- One filter, in one method, with no per-endpoint annotation to forget.

### Negative

- Every capability check now asks a second question. It is cached for thirty
  seconds with an evicting writer, but it is a cache that can miss, and a miss
  is a database read on the hot path.
- The refusal a suspended tenant's staff see is `INSUFFICIENT_CAPABILITY`
  (ADR 0031), which is honest but unhelpful — it does not say "your tenant is
  suspended", and deliberately does not, because the error vocabulary has no
  code for it yet and inventing one here would leak tenant state to an
  unauthenticated probe. Support will field "my permissions disappeared".
- A tenant suspended mid-shift loses the till, the kitchen board and the bot at
  once. That is the intent, but it means suspension is not a gentle instrument
  and the operator issuing one has no preview of what it stops.
- `iam` now depends on an SPI that `tenancy` implements. The arrow is the right
  way round and no cycle exists, but a fifth module wanting to veto
  authorization would be tempted to add a second lookup rather than a policy.

### Accepted trade-offs

- Fail-safe towards allowing: a tenant whose row cannot be found is treated as
  not suspended. A lookup failure locking a trading restaurant out of its own
  tills is worse than a suspended one keeping access for a few more seconds, and
  whether the scope names a real tenant is `ResourceScopeVerifier`'s question,
  which already refuses.
- Thirty seconds of staleness at worst, and only if the evicting write is lost.
- `ARCHIVED` is not enforced, pending the first Open input.

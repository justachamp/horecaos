# ADR 0025: Fine-grained authorization and the capability model

- Decision status: Accepted
- Implementation status: Built — the staff grant model is live and both bootstrap gaps are closed.
  V0008 holds roles, capabilities, grants and the registry with ancestry constraints;
  `Capability` and `PlatformRole` are the code-owned catalogue projected at startup by
  `RoleRegistrySynchronizer`; `JdbcAuthorizationService` resolves grants and scope
  covering, behind the ADR 0033 `iam.grants` cache that `GrantManagementService` evicts
  on every grant and revocation; `@RequiresCapability` refuses with ADR 0031's
  `INSUFFICIENT_CAPABILITY`; `horecaos.authorization.enforce` defaults to true.
  `ResourceScopeVerifier` now proves the path hierarchy is real after the capability
  check, closing the gap where a tenant-scoped grant authorised any brand identifier a
  caller cared to name. A platform admin passes `IAM_GRANT_MANAGE` without a grant row
  and nothing else, which is how a fresh deployment creates its first grant; and the
  storefront is authorised by ownership rather than by staff capabilities —
  `@CustomerOwned` on ordering, payments, notification preferences and registration, with
  `EndpointCapabilityDeclarationTests` failing the build on an endpoint that declares
  neither and on one that declares both. ADR 0049 extends that declaration model to the
  partner client's active bindings and the courier's own relationship, without copying
  either into staff grants. The customer principal this line used to call
  unmintable now exists: V0055, `CustomerVerificationService` and
  `StorefrontCustomerIdentityController` issue a one-time code, verify it, and redeem the
  single-use grant into an account keyed by trusted issuer and token subject. The old
  `NO_STAFF_PRINCIPAL_HOLDS_THESE` gap is closed: its five unused constants were removed,
  and the three live partner/courier operation names are recorded by
  `NON_STAFF_RELATIONSHIP_AUTHORIZED` under ADR 0049.
  The operational bootstrap gap closed 2026-08-31: `PlatformAdminBootstrapReconciler`
  grants PLATFORM scope from configuration (idempotent, never revoking), and
  `PlatformGrantController` grants/revokes it over HTTP under ADR 0027
  maker-checker — the highest-authority action in the system, gated accordingly;
  `create-platform-admin.sh` no longer touches SQL.
- Date proposed: 2026-08-20
- Date decided: 2026-08-20
- Deciders: Ayubkhon Abbosov (platform architecture), security
- Depends on: ADR 0002, ADR 0003
- Supersedes / Superseded by: — / ADR 0049 for non-staff relationship authorization
- Open inputs: none
- Closed inputs: Capability catalogue and role bundles approved 2026-08-20 (granular role set; tenant executes refunds under ADR 0027 maker-checker; tenant owner and admin manage integrations; no tenant-defined roles in v1)

## Context

ADR 0003 deliberately stopped at the tenant boundary. It decided that Keycloak
owns authentication, organization membership, and coarse roles, and that
"fine-grained brand, location, plan, entitlement, and resource grants" stay in
Qoida projections. It never said what those projections are.

Everything after it assumed the missing model. ADR 0016 requires that "location
Operations may manage only explicitly delegated offering fields". ADR 0019
requires "location scope, reason, idempotency key" on every Operations
mutation. ADR 0022 invents a `/api/v1/session/context` endpoint returning
"capabilities" such as `order.approve` and `catalog.publish`. ADR 0021 has to
state that an entitlement "never grants a user permission", which only makes
sense if something else does.

Without this ADR, three things happen. Authorization gets re-invented per
module with different semantics. A restaurant employee at one location can read
the whole tenant, because organization membership is currently sufficient for
tenant reads. And the frontend's capability list becomes the de facto
permission model, enforced only in the browser.

## Decision

Qoida owns a capability-based authorization model in the `iam` module,
evaluated server-side on every request, with Keycloak remaining the source of
authentication and coarse role facts.

- **A capability is a verb on a resource type**, named `resource.action`, for
  example `order.approve`, `catalog.publish`, `refund.execute`,
  `integration.failure.resolve`. Capabilities are a code-owned enum-like
  registry, not database-defined strings, so an unknown capability fails at
  startup rather than silently denying or allowing.
- **A grant binds a principal to a role at a scope.** The scope is one of
  `PLATFORM`, `TENANT`, `BRAND`, or `LOCATION`. Grants live in `iam`, not in
  tokens.
- **A role is a named, versioned bundle of capabilities.** Platform-defined
  roles ship with the product. Tenants may later define custom roles from the
  same capability catalogue; they can never invent a capability.
- **Authorization is the conjunction of four independent checks**, evaluated in
  this order, all of which must pass:
  1. Authentication: valid Keycloak token, issuer, audience, expiry (ADR 0003).
  2. Tenant match: the request's tenant matches a signed `organization` claim
     bound to that tenant's immutable organization ID (ADR 0003).
  3. Capability: an active grant gives the principal the required capability at
     a scope that covers the target resource.
  4. Entitlement: the tenant's plan permits the feature (ADR 0021).
- **Scope covers downward, never upward or sideways.** A `TENANT`-scoped grant
  covers every brand and location in that tenant. A `LOCATION`-scoped grant
  covers only that location. Nothing infers a grant from a sibling.
- **Reads are scoped too.** This ADR narrows ADR 0003: organization membership
  alone is no longer sufficient to read all tenant data. Membership establishes
  tenant context; a capability grant establishes what may be read within it.
  ADR 0003's rule remains correct for the control-plane tenant record itself.
- **Entitlement is not permission and permission is not entitlement.** A
  capability check that passes while the plan forbids the feature returns a
  distinct error from a capability check that fails, and neither substitutes for
  the other.
- **The browser receives a capability view, never the policy.**
  `GET /api/v1/session/context` returns the actor, memberships, active tenant,
  covered scopes, granted capabilities, entitlement summary, and a context
  version. It exists to shape the interface. Every mutation is authorized again
  on the server.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Put fine-grained grants in the Keycloak token | Token size grows without bound as a tenant adds brands, locations, and staff, and every grant change requires re-issuing tokens. ADR 0003 already rejected this for the same reason | Never for relationship grants |
| Keycloak Authorization Services as the policy engine | Capable and standards-based, but it puts a synchronous external call on every authorized request, splits policy across two systems, and makes tenant-scoped resource policies hard to reason about at Qoida's granularity | Qoida needs externalized policy authoring by non-engineers |
| Open Policy Agent or Cedar with policy-as-code | Genuinely attractive for expressiveness and auditability, and the capability registry here could later compile into either. Rejected now because it adds a policy language, a distribution mechanism, and a sidecar or embedded engine to operate, for a model that is currently four checks and a scope tree | Policy complexity outgrows the scope tree, particularly if per-tenant custom policies with conditions appear |
| Role strings checked directly with `@PreAuthorize("hasRole(...)")` | Roles proliferate into per-scope variants such as `LOCATION_42_ORDER_APPROVER`, which is unmanageable and leaks identifiers into role names | Never |
| Relationship-based authorization in the style of Google Zanzibar (SpiceDB, OpenFGA) | The right answer for deep, arbitrary resource graphs with sharing. Qoida's graph is a fixed four-level tree, so a dedicated authorization database would add infrastructure and a consistency problem for a hierarchy that composite SQL keys already enforce | Resource sharing becomes non-hierarchical, for example a courier pool shared across tenants, or per-record ACLs appear |
| Let the frontend capability list be the only check | Trivially bypassed. It is a user-experience optimization and is stated as such | Never |
| Keep ADR 0003's "membership is sufficient for reads" unchanged | A single-location employee could read every location's orders, customers, and revenue in the tenant. This is the largest open authorization gap in the current set | Never |

## Consequences

### Positive

- One authorization model, named consistently across API, frontend, audit, and
  tests, so `order.approve` means the same thing everywhere.
- Location-scoped staff become expressible, which is a hard requirement for
  restaurant operations and is currently impossible.
- Capability names give ADR 0027 audit records and ADR 0031 error responses a
  stable vocabulary.

### Negative

- A grant table is now on the hot path of every request, so it needs caching
  with correct invalidation, and a cache bug becomes an authorization bug.
- Two systems hold identity facts: Keycloak for authentication and membership,
  Qoida for grants. They can drift, and ADR 0009's drift report must cover
  grants as well as memberships.
- Every new capability is a decision that someone must make deliberately, and an
  under-specified capability catalogue will slow feature work.

### Accepted trade-offs

- Narrowing ADR 0003's read rule means the existing control-plane read paths
  must be revisited before more tenant data is exposed. That is deliberate
  rework, taken now because it is far cheaper than after customer data exists.
- Capabilities are code-owned, so adding one requires a release. Tenants get
  flexibility in composing roles, not in inventing permissions.

## Physical model

```text
iam.roles
  id, tenant_id null, code, name, scope_type, status
  is_platform_defined, version, created_at, updated_at
  unique(coalesce(tenant_id, platform sentinel), code)

iam.role_capabilities
  role_id, capability_key, granted
  unique(role_id, capability_key)

iam.grants
  id, tenant_id, principal_id, role_id
  scope_type, scope_id null
  status, granted_by, reason, valid_from, valid_until null
  version, created_at, updated_at
  unique(principal_id, role_id, scope_type, scope_id) where active

iam.capability_registry_snapshot
  capability_key, description, resource_type, action
  introduced_in_version, deprecated_in_version null
```

`scope_id` is null only for `PLATFORM`. Composite foreign keys carry tenant and
brand ancestry so a grant cannot reference a location outside its tenant. The
registry snapshot is written from code at startup so the database can be joined
for reporting without becoming the authority.

## Evaluation contract

```java
interface AuthorizationService {
    boolean has(Capability capability, ResourceScope scope);
    void require(Capability capability, ResourceScope scope);
    CapabilityView viewFor(PrincipalId principal, TenantId tenant);
}
```

`require` throws a typed exception that ADR 0031 maps to a stable
`403` Problem Details response naming the missing capability and scope, never
the policy that produced the decision.

Evaluation is a pure function of the principal's active grants and the target
scope. It performs no I/O beyond a cached grant read, so it can be unit tested
exhaustively and is safe to call several times per request.

## Caching and invalidation

Grants are cached per principal and tenant with a short TTL and are invalidated
by `TenantGrantsChanged` outbox events. PostgreSQL remains authoritative. A
cache miss or a cache outage degrades to a database read, never to an allow.

## Initial capability catalogue

The first slice covers what existing ADRs already assume, grouped by owner:

```text
tenant.read, tenant.write, tenant.onboarding.manage
brand.read, brand.write
location.read, location.write
catalog.read, catalog.author, catalog.publish
offering.manage
inventory.read, inventory.adjust
pricing.read, pricing.author, pricing.activate
order.read, order.approve, order.cancel, order.state.override
refund.request, refund.approve, refund.execute
recovery.case.manage, recovery.remedy.approve
delivery.plan.read, delivery.manual_assign, shipment.cancel
integration.installation.manage, integration.binding.activate
integration.failure.read, integration.failure.retry, integration.failure.resolve
notification.template.author, notification.template.activate
commercial.subscription.manage, commercial.override.approve
audit.read
platform.admin
```

Values are proposals; the accepted catalogue and the default role bundles are an
open input owned by product and operations.

## Security rules

- Deny by default. An unmapped endpoint fails closed and a startup test asserts
  that every mutating endpoint declares a capability.
- `platform.admin` is a Keycloak-issued global role from ADR 0003 and is never
  grantable through tenant administration.
- A tenant administrator can grant only capabilities it holds itself, at scopes
  it covers, and never `platform.admin`.
- Every grant and revocation is an ADR 0027 audit fact with actor, reason, and
  validity window.
- Time-bounded grants expire without human action, and support access uses them.

## Testing

- A principal with a `LOCATION` grant is denied at a sibling location and at the
  tenant scope, proven at both the application and database boundary.
- A multi-tenant principal carrying two organization claims resolves grants
  independently per tenant.
- Capability checks and entitlement checks fail independently and produce
  distinguishable errors.
- Every capability in the registry has at least one positive and one negative
  test, enforced by a coverage test over the registry.
- The session-context response and server enforcement agree for a matrix of
  roles and scopes; a divergence fails the build.
- Cache invalidation removes a revoked grant within the agreed bound, and a
  cache outage denies rather than allows.

## Rollout and rollback

Introduce the tables, registry, and evaluation service with enforcement in
shadow mode: evaluate, log the decision, and continue to apply the existing
ADR 0003 rule. Compare decisions for a full observation period, fix divergences,
then enforce per capability group starting with writes. Rollback returns to
shadow mode while grants and audit evidence are retained.

## Implementation checklist

- [x] Approve the capability catalogue and default role bundles.
- [x] Add role, capability, grant, and registry tables with ancestry constraints (`V0008`).
- [x] Implement the code-owned capability registry and startup projection (`Capability`, `PlatformRole`, `RoleRegistrySynchronizer`).
- [x] Implement grant resolution and scope covering (`JdbcAuthorizationService`). Caching landed with ADR 0033: `grantsFor` is `@Cacheable("iam.grants")` and `GrantManagementService` evicts on grant and revocation, so a revoked grant stops working without waiting out the TTL.
- [x] Implement `require` and `AccessDeniedException`, and map it in `GlobalApiErrorHandler` to ADR 0031's `INSUFFICIENT_CAPABILITY`. The mapping was missing until enforcement was turned on: the exception is Qoida's own rather than Spring Security's, so every refusal would have been a 500. Nothing reached it while the declaration ran in shadow mode.
- [x] Implement `viewFor`, which backs `GET /api/v1/session/context`. The endpoint is live on `GrantController`, and `JdbcAuthorizationServiceTests.theCapabilityViewMatchesServerEnforcement` walks every capability to prove the view and the server agree. ADR 0022, which was to have delivered it, is superseded by ADR 0035.
- [x] Add grant management APIs with ADR 0027 audit facts, and `GET /api/v1/session/context`.
- [x] Implement the narrowing of ADR 0003's read rule behind `horecaos.authorization.enforce`.
- [x] Add shadow-mode comparison and the `horecaos.authorization.shadow` metric. Dashboards and alerts follow deployment.
- [x] Add scope, isolation, expiry, and registry coverage tests.
- [x] `horecaos.authorization.enforce` now defaults to true, and shadow mode is the opt-out. Flipped before the frontends exist rather than after a quiet shadow log, because there was no estate to be quiet: no grant had ever been created, so the log said "would_deny" about everything and could never have said anything else. The evidence a shadow comparison was meant to produce is not available before the first tenant, and waiting for it would have meant a first denial on a restaurant's first trading day.
- [x] Decide how the first grant is created. Decided and built as a bypass inside `JdbcAuthorizationService.has`: a subject holding the Keycloak realm role `platform-admin` passes `IAM_GRANT_MANAGE` with no grant row, and passes nothing else, so it can create the first grant through the ordinary audited `GrantManagementService` and must then grant itself everything further. The role is read from the calling actor and compared against the subject being asked about, so it cannot answer for somebody else, and `viewFor` does not report it — a platform admin holding no grants sees no capabilities, which is what the grant table says.
- [x] Decide what a non-staff principal is in this model. ADR 0049 keeps grants as delegated staff authority and adds three typed relationship declarations: customer ownership, active partner bindings, and courier self-ownership. The five unused constants were removed; the three live partner/courier operation names remain code-owned vocabulary without pretending their principals hold staff roles.

## Exit criteria

Every mutating endpoint declares a capability, a location-scoped user can act at
exactly one location and is denied everywhere else at both application and
database boundaries, the frontend capability view provably matches server
enforcement, and no authorization decision depends on a role name string.

## References

- [ADR 0003: Keycloak tenant authorization](../built/0003-keycloak-tenant-authorization.md)
- [ADR 0021: SaaS plans, entitlements, and usage metering](../partial/0021-saas-plans-entitlements-and-usage-metering.md)
- [ADR 0027: Audit evidence and approval model](../partial/0027-audit-evidence-and-approval-model.md)
- [ADR 0049: Non-staff principal authorization](../built/0049-non-staff-principal-authorization.md)

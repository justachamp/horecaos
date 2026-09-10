# ADR 0081: Support enters a tenant through a time-boxed, reasoned session

- Decision status: Proposed
- Implementation status: Built — `iam.support_sessions` (V0196), the `support-session-view` and `support-session-assist` roles, `SupportSessionService`, `SupportSessionController` and the audit listener, tested against PostgreSQL; the control-plane support-session screen (open, end, history, a link into operations) and the operations app's support banner and explicit-tenant entry
- Date proposed: 2026-09-11
- Date decided: —
- Deciders: proposed by Claude and built on the platform owner's instruction of 2026-09-10 to finish the control plane's remaining waves; Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0025, ADR 0027, ADR 0056, ADR 0062, ADR 0078
- Supersedes / Superseded by: —
- Open inputs: whether the two access levels and the four-hour ceiling are the right ones — owner Ayubkhon Abbosov. Work proceeds without the answer because the owner asked for the wave to be built overnight, and every limit is a constant or a CHECK a later record can move without a data change

## Context

The control-plane information architecture (2.8) says support enters a
tenant's operations app "under a scoped, time-boxed, fully audited support
identity", and deliberately refuses a shadow operations console inside the
platform app. Nothing implemented it: the screen said "not built".

What existed already decided most of the shape. A grant carries `valid_until`,
and the grant query already refuses a grant past it, so an expiring grant is
the ordinary ADR 0025 path rather than a new one. HorecaOS staff sign in to
the operations app with the same account they use for the control plane (ADR
0062: one staff-login client behind both). The session-context endpoint
already takes an explicit `tenantId`. And `platform-support` is a
platform-scoped, read-only role: it can already look at any tenant, but it can
never help one, and nothing records why it looked.

## Decision

A HorecaOS person acts inside a tenant only through a **support session**: a
grant of a support-only role, scoped to that tenant, that ends by itself,
opened with a reason the tenant can read.

1. Two support-only roles, conferred only by a session and never grantable by
   hand. `support-session-view` looks. `support-session-assist` also does the
   order-floor acts a location manager could — move, amend or cancel an order,
   mark an item unavailable, close a branch, settle a POS export, assign a
   courier. Neither moves money, reveals a customer, publishes a catalogue or
   changes who has access.
2. A session lasts 15 minutes to 4 hours, chosen when it is opened, and one
   person has at most one open session of a kind per tenant.
3. The grant is the authority and `valid_until` ends it. `iam.support_sessions`
   is the account: who, which tenant, which access, why, the ticket, when it
   started, when it ended and by whom.
4. The support person may end their own session; a platform administrator may
   end anyone's; and the tenant's own administrators may end any session in
   their account.
5. Opening and ending are security audit facts filed at the tenant's scope, so
   the tenant's own log shows the visit beside everything done during it.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Impersonation: act as a tenant user | Every act would carry the tenant user's name, so the audit trail would say the owner did what support did | Never, for staff support |
| Keycloak token exchange for a scoped token | A second identity mechanism beside grants, and a token that outlives a revocation until it expires | A tenant app that cannot call the session-context endpoint |
| Widen `platform-support` with writes | Standing write access to every tenant, with no reason and no end | Never |
| A support console inside the control plane | Duplicates the operations app and splits its source of truth, which the IA already rejected | Never |
| Require a second person to approve every ASSIST session | Right for sensitive tenants, but blocks the common "the owner is on the phone now" case | A tenant asks for it; ADR 0027 approval policies can gate the start action then |

## Consequences

### Positive

- Support can help, not only look, and every visit has a reason, an end and a
  record the tenant can see.
- No new authorization path: a session is a grant, so every endpoint, cache
  eviction and suspension rule already applies.

### Negative

- A cached grant can outlive its deadline by the grant cache's TTL, the same
  bound every timed grant already has. Ending a session early evicts at once.
- ASSIST is a fixed list. A support task outside it needs the tenant's own
  staff, or a later record that widens the role.
- The grant-changed audit fact names `iam.grant.manage` as its capability,
  because that listener cannot tell a session's grant from a hand grant; the
  session's own fact names the right one.

### Accepted trade-offs

- A lapsed session's grant stays `ACTIVE` with a past `valid_until` until the
  same person opens another, when it is revoked with its own reason. Nothing
  reads it in between, and a sweeper would be one more job for no change in
  who may do what.

## Specification

- Capabilities: `support.session.start` (platform; held by `platform-support`
  and `platform-admin`), `support.session.read` (tenant owners and
  administrators for their own account; both support roles, so the person
  inside can read their own deadline).
- `GrantManagementService.grantForSupportSession` issues the grant: only a
  support role, always with an end, and it retires a lapsed grant first so
  the one-active-grant index keeps meaning one open session. `grant` refuses a
  support role outright.
- `iam.support_sessions`: `CHECK` that the window is at most four hours and the
  reason is not blank; the end columns move together; the grant is referenced
  tenant-scoped.
- Control plane: `POST/GET /control-plane/tenants/{t}/support-sessions`,
  `POST …/{id}/end`, `GET /control-plane/support-sessions/mine`.
- Operations: `GET /operations/tenants/{t}/support-sessions` (the tenant's
  record), `GET …/current` (the caller's own open session, for a banner),
  `POST …/{id}/end` (the tenant's administrators, under `iam.grant.manage`).
- An archived tenant is refused: it keeps no grants (ADR 0078), so a session
  would open onto nothing.

## Rollout and rollback

Additive. Rolling back removes the endpoints and leaves the table and roles
unused; no grant outlives four hours, so nothing needs cleaning up.

## Implementation checklist

- [x] Roles, capabilities, migration, service, controller, audit listener
- [x] Tests: the deadline ends access on the ordinary path, ASSIST's limits,
  one open session, early end by the tenant, no hand grants, bounds
- [x] Control-plane screen: open, list, end, and a link into operations
- [x] Operations: a support banner, and entry with an explicit tenant

## Exit criteria

A support person opens a session from the control plane, lands in the
tenant's operations app with the chosen access, sees their deadline, and loses
access at it without anything else running; the tenant's audit log shows the
visit and its reason.

## References

- `docs/frontend-information-architecture.md` §2.8

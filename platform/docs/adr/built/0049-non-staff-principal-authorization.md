# ADR 0049: Non-staff principals use typed relationship authorization

- Decision status: Accepted
- Implementation status: Built — partner order push and all five courier self-service
  mutations declare typed relationship authorization; partner reach resolves from the
  active credential and live bindings, courier reach resolves from token subject and row
  ownership, replay protection remains explicit, and the five dead capability constants
  are removed.
- Date proposed: 2026-08-26
- Date decided: 2026-08-26
- Deciders: Ayubkhon Abbosov (platform architecture)
- Depends on: ADR 0003, ADR 0025, ADR 0031, ADR 0040, ADR 0042, ADR 0045
- Supersedes / Superseded by: supersedes ADR 0040's `PARTNER_INTEGRATION`
  `iam.grants` requirement only / —
- Open inputs: none

## Context

ADR 0025 correctly models delegated staff authority: a principal receives a role at a
scope and may act down that scope tree. It does not model authority a caller has because
of what it is related to. Customers own accounts, a partner credential belongs to an
installation with live bindings, and a courier is the signed subject of one courier row
and acts on their own shift.

Pretending those relationships are staff grants made six live endpoints unreachable.
The capability interceptor ran before partner authentication or courier ownership checks,
and none of those principals had an `iam.grants` row. Adding them to a staff bundle would
have widened the wrong principal and, for couriers, contradicted ADR 0042's rule that a
manager cannot create a courier's paid time or direct their breaks.

## Decision

**Keep `iam.grants` for delegated staff authority and authorize non-staff principals by a
typed, code-declared relationship strategy.**

- A customer is authorized by ownership of the account or business row.
- A partner machine client is authorized by an active, unexpired credential, tenant match,
  and the installation's currently live bindings.
- A courier is authorized by the token subject's courier row, active engagement where the
  operation requires one, and ownership of the affected shift or handover.
- Every effectful endpoint declares exactly one strategy. Staff use
  `@RequiresCapability`; the other three use `@CustomerOwned`, `@PartnerBound`, or
  `@CourierSelfAuthorized`.
- Capability codes may name partner and courier operations without being held in a role.
  They remain a common vocabulary for audit, API description and reporting, not proof that
  a relationship principal passed through `iam.grants`.
- Unknown or future non-staff relationships require another typed declaration. Absence of a
  declaration never means public access.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Put partner clients and couriers in `iam.grants` | Duplicates binding/ownership reach and drifts after unbinding; makes a courier look like delegated staff | A non-staff principal genuinely receives discretionary authority unrelated to a resource relationship |
| Give shift capabilities to managers | Lets a manager create paid hours and direct breaks, contradicting ADR 0042 | Never while the engagement remains self-employed |
| Disable capability enforcement on partner/courier path prefixes | Makes all future endpoints under those prefixes silently exempt | Never |
| One generic `@RelationshipAuthorized` marker | Erases which service must prove which relationship and makes reviews depend on handler archaeology | Relationship types multiply enough to justify a closed strategy registry |

## Consequences

### Positive

- The partner and courier APIs are reachable by the principals they were built for.
- Revoking a binding or engagement changes reach at the authoritative relationship rather
  than waiting for a copied grant to expire.
- A build test distinguishes deliberate relationship authorization from a forgotten check.

### Negative

- Authorization is no longer expressed through one interceptor; relationship checks live
  in the application service that can resolve the affected row.
- Capability views sent to staff do not describe a partner or courier's relationship reach.

### Accepted trade-offs

The marker annotations do not themselves authorize. This is accepted because a generic
interceptor does not know the binding hidden behind a venue reference or the owner of a
handover UUID. Domain-level comparison is the enforcement point; the marker makes that
choice statically visible and testable.

## Specification

`@PartnerBound(Capability)` requires the handler path to authenticate via
`PartnerAuthenticationService`. That service checks credential status, expiry and tenant,
then derives live binding IDs. The ingestion service resolves the venue to one binding and
requires `principal.covers(bindingId)` before booking an order.

`@CourierSelfAuthorized(Capability)` requires the handler to resolve a courier by
`(tenant_id, token subject)`. Shift operations name no courier ID in the request and verify
the live shift belongs to the resolved courier. Cash declaration additionally compares the
handover's `courier_id` before mutation.

Removing a staff capability annotation must not remove replay protection. Courier mutations
declare `@Idempotent`. Partner order push declares `@NaturallyIdempotent`; the unique
`(binding, external_order_id)` intake key returns the original result on retry and does not
require a header partners do not send.

The unused `order.place`, `payment.initiate`, `notification.preference.manage`,
`marketplace.order.status.push`, and `marketplace.menu.read` constants are removed. The
first three were replaced by customer ownership; the latter two have no endpoint. A code-
owned capability should appear when the operation appears, not years before it.

## Rollout and rollback

Ship declarations, handler checks and tests together. There is no data migration. Rolling
back restores the previous known outage; if an emergency rollback is unavoidable, the
existing authorization enforcement opt-out is the temporary operational escape hatch while
the source is reverted.

## Implementation checklist

- [x] Add partner-bound and courier-self authorization declarations.
- [x] Require exactly one authorization strategy on every effectful endpoint.
- [x] Preserve courier header idempotency and declare partner natural idempotency.
- [x] Enforce handover ownership before courier cash declaration and record the action.
- [x] Remove five dead capability constants.
- [x] Convert the role-test gap list into an intentional relationship-authorized set.

## Exit criteria

The partner push reaches its binding-based authentication without a staff grant; the courier
handlers reach their subject/ownership checks without a staff grant; foreign tenant,
binding, shift and handover attempts are refused before mutation; and a new effectful
endpoint with zero or multiple authorization strategies fails the build.

## References

- [Intent and approved spec](../../../intent/0002-principals-who-are-not-staff/spec.md)
- [ADR 0025: Fine-grained authorization](../built/0025-fine-grained-authorization-and-capability-model.md)
- [ADR 0040: Marketplace channel](../partial/0040-marketplace-channel-and-partner-api.md)
- [ADR 0042: Courier compensation](../partial/0042-courier-compensation-shifts-and-settlement.md)

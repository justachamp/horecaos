# Spec: typed authorization for non-staff principals

- **Intent:** [`intent.md`](intent.md)
- **Author:** Codex, reviewed by Ayubkhon Abbosov in conversation
- **Date:** 2026-08-26
- **Status:** Approved
- **Approver:** Ayubkhon Abbosov (platform owner)

## Summary

Keep ADR 0025 grants as delegated staff authority and add explicit authorization
strategies for principals whose authority comes from a relationship: customer ownership,
an active partner installation binding, or a courier's own active engagement and rows.
Partner and courier endpoints retain code-owned capability names as operation vocabulary,
but do not pretend that those callers hold staff roles.

## Requirements

1. Every effectful endpoint declares exactly one authorization strategy.
2. A partner order push authenticates an active, unexpired confidential client, matches the
   tenant in the path, and affects only a live binding of its installation.
3. A courier shift action resolves the courier from the signed token subject and acts only
   on that courier's shift or cash handover.
4. Partner natural idempotency remains the unique `(binding, external_order_id)` database
   guarantee; courier mutations continue to require `Idempotency-Key`.
5. The five unused customer/partner capability constants are removed; a capability is added
   back only when an endpoint using it ships.
6. Partner authentication and courier actions retain attributable evidence: partner client
   authentication is stamped, inbound pushes are staged, and courier shift/cash actions are
   audit facts.

## Non-goals

- Putting couriers or machine clients into staff role bundles.
- Building partner credential authoring or the unbuilt menu/status partner endpoints.
- Replacing the per-person reveal grants from ADR 0045.
- Generalising relationship authorization into an external policy engine.

## Design

### Domain impact

No aggregate changes. `PartnerPrincipal.bindingIds` remains the complete machine reach.
Courier services continue to enforce active engagement and self-owned shift invariants; cash
declaration adds the same owner comparison before mutation.

### Data

No migration. Existing partner client, installation, binding, courier, shift and handover
foreign keys remain authoritative. Removing dead code-owned capabilities is projected to
`iam.capability_registry_snapshot` by the existing startup synchronizer.

### API

- `POST /api/v1/partner/tenants/{tenantId}/orders` declares
  `@PartnerBound(MARKETPLACE_ORDER_RECEIVE)` and `@NaturallyIdempotent`.
- The five `CourierShiftController` mutations declare `@CourierSelfAuthorized` plus
  `@Idempotent`.
- Relationship failures use `UNAUTHENTICATED` or non-enumerating `RESOURCE_NOT_FOUND`.

### Events

No event changes. ADR 0040's unbuilt partner events remain outside this intent.

### Integration

The partner client is resolved to its installation's currently live bindings on every
authentication. No binding list is copied into a grant or token.

## Policy review

| Area | Verdict | Note |
|---|---|---|
| Tenant isolation | Satisfied | Partner tenant match and courier tenant+subject lookup precede effects. |
| Authorization capability (ADR 0025) | Satisfied | ADR 0049 distinguishes staff grants from typed relationship authorization. |
| Secrets and PII (ADR 0028, 0029) | Satisfied | Existing OAuth client and protected courier data paths are unchanged. |
| HTTP conventions (ADR 0031) | Satisfied | Courier keys and partner durable natural idempotency are explicit. |
| Event contracts (ADR 0032) | Not applicable | No producer or consumer changes. |
| Migration safety | Not applicable | No migration. |
| Observability and audit (ADR 0027) | Satisfied | Existing partner evidence remains; cash declaration now emits an audit fact. |

## ADR impact

[ADR 0049](../../docs/adr/built/0049-non-staff-principal-authorization.md) extends
ADR 0025 and supersedes only ADR 0040's statement that a partner confidential client must
hold an `iam.grants` role.

## Rollout and rollback

The declaration change is atomic with its build tests. Rollback restores the previous
annotations but also restores the known 403 outage, so operational rollback is to disable
capability enforcement only while reverting. Database state is unchanged.

## Acceptance criteria

- `EndpointCapabilityDeclarationTests` proves every effectful endpoint has exactly one
  strategy and replay declaration.
- `PlatformRoleTests` proves the live partner/courier operation names are intentionally
  relationship-authorized and in no staff bundle.
- `MarketplaceChannelTests` proves wrong-tenant, inactive, expired and unbound clients are
  refused and a covered binding succeeds.
- `CourierCompensationTests.aCourierCannotDeclareAnotherCouriersCash` proves a foreign
  handover is not mutated.
- `make verify` and `make lint` pass.

## Open questions

| Question | Owner | Blocking? |
|---|---|---|
| none | — | no |

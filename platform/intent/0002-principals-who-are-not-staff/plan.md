# Plan: typed authorization for non-staff principals

- **Spec:** [`spec.md`](spec.md)
- **Author:** Codex
- **Date:** 2026-08-26

## Files that change

| File | Change |
|---|---|
| `docs/adr/built/0049-non-staff-principal-authorization.md` | Record the principal and endpoint strategy decision. |
| `partner/api/PartnerBound.java`, `courier/api/CourierSelfAuthorized.java` | Add typed relationship declarations. |
| `web/idempotency/NaturallyIdempotent.java` | Make the partner's database natural key an explicit replay decision. |
| Partner and courier controllers/services | Replace unreachable staff checks and enforce self-owned handovers. |
| `Capability.java`, `PlatformRoleTests.java` | Remove unused constants and convert the gap list into a decided relationship set. |
| Authorization, courier and telemetry tests | Prove declaration exclusivity, ownership and staff-only stream reach. |

## Order of work

1. Accept ADR 0049 and add the three marker annotations.
2. Move partner push and courier self-service handlers off staff capability interception,
   keeping operation names and replay guarantees explicit.
3. Add the missing courier-to-handover comparison before cash mutation and audit it.
4. Remove the five dead capability constants and tighten registry tests.
5. Run focused authorization/domain tests, then `make lint` and `make verify`.

## Risks

| Risk | Mitigation |
|---|---|
| A marker is mistaken for enforcement | Javadoc names the enforcing service; endpoint tests require a typed strategy and domain tests prove negative cases. |
| Removing `@RequiresCapability(mutating=true)` drops replay protection | Courier handlers add `@Idempotent`; partner push adds `@NaturallyIdempotent`. |
| A courier guesses another handover UUID | Service compares the resolved courier id and answers non-enumerating 404 before update. |
| Dead registry removal surprises stored roles | None of the five is held by a non-superuser role or declared by an endpoint; startup projection is code-authoritative. |

## Proof

```bash
./mvnw test -Dtest='EndpointCapabilityDeclarationTests,PlatformRoleTests,TelemetryDecisionTests,MarketplaceChannelTests,CourierCompensationTests' -DfailIfNoTests=false
make lint
make verify
```

- [x] New tests, named: `anEndpointDeclaresExactlyOneAuthorizationStrategy`,
  `aCourierCannotDeclareAnotherCouriersCash`
- [x] Tenant isolation negative case: existing partner wrong-tenant and courier subject lookup
- [x] Migration applies on a populated database and is idempotent: not applicable; no migration
- [x] Rollback rehearsed: source-only declaration change; no persisted state to undo

## Divergence log

| Date | Departed from plan | Why |
|---|---|---|
| 2026-08-26 | Added courier cash-handover ownership enforcement and audit. | Inspection found the handler resolved a courier but never compared that courier to the handover row. |

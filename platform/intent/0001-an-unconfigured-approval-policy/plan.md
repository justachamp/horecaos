# Plan: explicit behavior for missing approval policies

- **Spec:** [`spec.md`](spec.md)
- **Author:** Codex
- **Date:** 2026-08-26

## Files that change

| File | Change |
|---|---|
| `audit/api/ApprovalAction.java` | Register every live approval action and its missing-policy mode. |
| Approval command/service/policy service | Reject unknown actions, apply explicit modes, and expose coverage. |
| `V0082__scope_approval_policies_to_actual_resources.sql` | Add exact scope identifiers, ancestry FKs, legacy fallback labels and indexes. |
| Approval policy controller | Accept exact scope IDs and expose coverage and legacy status. |
| Approval callers and tests | Replace string literals with registered codes and prove modes/scope isolation. |
| ADR 0027, ADR 0030 and ADR 0050 | Reconcile the accepted model with the delivered behavior. |

## Order of work

1. Catalogue production approval actions and create the closed `ApprovalAction` register.
2. Apply its modes in `JdbcApprovalService`, retaining observability and distinguishing a
   missing policy from a resolver failure.
3. Repair the approval-policy physical scope model and author/list/resolve exact rows.
4. Add coverage output and migrate every production caller to the register.
5. Add unit, service, migration-backed, endpoint and authorization regression tests.
6. Run focused tests, `make lint`, `make verify`, ADR index validation and diff checks.

## Risks

| Risk | Mitigation |
|---|---|
| Global refusal outages every money operation | Per-action permissive default; only manual penalties begin fail closed. |
| Historical scope rows are falsely narrowed | Mark them legacy-wide and keep their prior behavior until explicitly replaced. |
| An undeclared new action defaults open | Validate the code in `ApprovalRequestCommand`. |
| Tenant IDs cause metric-cardinality exhaustion | Keep them in bounded warning logs, never metric labels. |

## Proof

```bash
./mvnw test -Dtest='ApprovalActionTests,JdbcApprovalServiceTests,ApprovalPolicyServiceTests,ApprovalPolicyEndpointTests' -DfailIfNoTests=false
make lint
make verify
tools/adr-index --check
git diff --check
```

- [x] New tests, named: `anActionRegisteredFailClosedRefusesWhenNoPolicyResolves`,
  `aBrandPolicyGovernsOnlyTheBrandItNames`, `anUnknownActionCannotSilentlyInheritThePermissiveDefault`
- [x] Tenant isolation negative case: a policy for one brand does not govern another brand
- [x] Migration applies on a populated database and is idempotent: `ApprovalPolicyScopeMigrationTests` migrates legacy BRAND and LOCATION rows from V0081 to latest, then proves they are labeled wide fallbacks and new rows require exact resources
- [x] Rollback rehearsed: mode change is source-only; migration is additive and evidence is retained

## Divergence log

| Date | Departed from plan | Why |
|---|---|---|
| 2026-08-26 | Added exact brand/location columns rather than changing only absent-policy behavior. | Inspection showed prior scope labels could govern every resource at the level, invalidating safe per-action rollout. |

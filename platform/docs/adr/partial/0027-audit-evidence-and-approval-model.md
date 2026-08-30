# ADR 0027: Audit evidence and the approval model

- Decision status: Accepted
- Implementation status: Partial — an approval is now single-use and the policy timeline
  can no longer be disarmed by a cancelled change: V0071 adds `CONSUMED` to
  `ck_approval_request_status` with `consumed_at` and `consumed_by`,
  `ApprovalOutcome.Approved` carries an `ApprovalGrant` the caller spends in the
  transaction that performs the action, and `findRequest` ignores a spent row, so the
  maker's next identical submission raises a fresh request instead of riding the old
  signature to its expiry; and `ApprovalPolicyService.endDate` refuses a version that has
  not taken effect yet, because cancelling a scheduled version left the version it
  superseded clamped with nothing to follow it, which turned the control off from the
  scheduled date onwards. The audit half is built and used. V0007 creates the
  partitioned `audit.audit_events`, plus `audit.approval_policies` and
  `audit.approval_requests`;
  `JdbcAuditRecorder` and `ChangeDocuments` record classification-aware facts in the
  caller's transaction; `qoida_application` holds insert and select on `audit` and one
  column-level `UPDATE` (V0059, below) and nothing else;
  `AuditPartitionManager` rolls partitions; and `GET /control-plane/tenants/{id}/audit-events`
  serves reads behind `audit.read`, auditing the read itself. The maker-checker half is now
  wired at both ends. Six services call `ApprovalService.requireApproval` —
  `OrderRemedyService`, `CourierAdjustmentService`, `CourierSettlementService`,
  `OnboardingService`, `FailureOperationsService` and `LoyaltyAdjustmentService` — and the
  two things that were missing behind them exist. `audit.approval_policies` has a writer:
  `ApprovalPolicyService` and `ApprovalPolicyController` author and retire versioned
  policies at `/api/v1/control-plane/tenants/{tenantId}/approval-policies` behind
  `approval.policy.manage`, which among the tenant bundles only `tenant-owner` holds, and
  V0059 grants the privilege that authoring needs — `INSERT`, plus `UPDATE (valid_until)`
  at column level and nothing else, so a threshold is superseded by a new version row
  rather than rewritten under the `policy_version` already snapshotted onto recorded
  evidence. And the decide path exists: `ApprovalRequestController` serves the pending
  queue and `POST /approval-requests/{requestId}/decision` behind `approval.decide`, over
  `ApprovalDecisionService`, which refuses the requester as their own second signature,
  refuses a caller who does not hold the capability the governing policy version named,
  refuses a lapsed or already-decided request, and audits every refusal; and
  `ApprovalExpirySweeper` finally calls `expireOverdue` on a schedule, so a lapsed request
  stops reading `PENDING`. ADR 0050 now settles what an unconfigured action means:
  `ApprovalAction` names every live operation and gives it a deliberate permissive or
  fail-closed absent-policy mode; the coverage surface exposes that state; and V0082
  makes new brand/location policies name their actual resources while preserving old
  ambiguous rows as visibly marked legacy fallbacks. Also not built: archival of closed
  partitions to protected storage, and the retention periods the Open input names.
- Date proposed: 2026-08-20
- Date decided: 2026-08-20
- Deciders: Ayubkhon Abbosov (platform architecture), security
- Depends on: ADR 0004, ADR 0025
- Supersedes / Superseded by: — / ADR 0050 for missing-policy behavior and exact approval-policy scope
- Open inputs: Retention periods per audit class (legal, finance)

## Context

The `audit` schema was created empty in the foundation migration and no ADR ever
filled it. Meanwhile nearly every later ADR requires audit facts as a
precondition: ADR 0006 requires actor, reason, prior and new state for every
replay; ADR 0009 audits organization and membership changes; ADR 0012 audits
review decisions with before, imported, and decision hashes; ADR 0013 requires
maker-checker approval above monetary thresholds; ADR 0021 requires four-eyes
approval on commercial mutations; ADR 0024 requires signed cutover decisions.

Maker-checker appears in four ADRs with no shared definition of what an approval
is, who may give one, or how a threshold is configured. Without this ADR each of
those builds its own approval table, and the audit trail becomes a per-module
convention that cannot be searched, exported, or proven complete.

Audit is also needed early. ADR 0006 is roadmap step two.

## Decision

The `audit` module owns one append-only evidence store and one reusable approval
workflow, both available before any capability that requires them.

- **An audit fact is immutable.** It records what happened, who caused it, in
  which tenant and scope, when, why, and what changed. There is no update or
  delete path in the application; the database role has `INSERT` and `SELECT`
  only.
- **Audit facts are written in the same transaction as the change they
  describe.** An audited action that commits without its fact is a bug that
  tests must catch. Audit is not a log-shipping concern and is not derived from
  application logs.
- **Two audit classes with different retention**: `SECURITY` for authentication,
  authorization, grants, secrets, and identity changes; `BUSINESS` for state
  transitions, approvals, monetary actions, and operator overrides. Both are
  append-only; only retention and access differ.
- **Structured before and after, not free text.** Changes record a stable action
  code, target type and identifier, and a redacted, classification-aware change
  document. A reason string is required for every operator-initiated action and
  is stored alongside the structured facts, never instead of them.
- **One approval model serves every maker-checker requirement.** An
  `ApprovalRequest` names the action, its parameters hash, the requester, the
  policy and threshold that triggered it, and its decision. The requester can
  never be the approver. Approvals expire.
- **Approval thresholds resolve through ADR 0030** from platform, tenant, brand,
  then location, and the resolved policy version is snapshotted onto the request
  so a later policy change cannot alter what was approved.
- **Audit reads are themselves audited**, and access requires the `audit.read`
  capability from ADR 0025.
- **Audit facts are not Kafka events.** Selected facts may be published through
  the outbox for downstream consumers, but the PostgreSQL record is the
  evidence, since topic retention expires and events are lossy by design.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Derive audit from application logs shipped to a log store | Logs are sampled, rotated, mutable in practice, and disconnected from the transaction. An audit trail that cannot prove it is complete is not evidence | Never for business and security audit; logs remain for diagnostics |
| Per-module audit tables | Every module invents its own actor, reason, and change representation, and cross-cutting questions such as "everything this operator did last Tuesday" become impossible | Never |
| PostgreSQL triggers writing row-level change history | Captures rows, not intent. It cannot record why an action was taken, which capability authorized it, or which approval permitted it, and it silently captures migrations and backfills as user actions | As a supplementary integrity check on specific tables, not as the audit model |
| Event sourcing the whole domain so history is implicit | A far larger commitment than the platform needs, and derived history still would not carry actor, reason, approval, and authorization context without additional modelling | Never as a substitute |
| Kafka topic as the audit store | Retention expires, ordering across topics is not guaranteed, and consumers cannot query by tenant and actor. The reasoning matches ADR 0006's rejection of DLT as authoritative | Never |
| A separate approval service or workflow engine | Adds infrastructure for what is a request, a policy snapshot, and a decision. ADR 0008 already rejected a workflow engine for a larger problem | Approval routing gains delegation chains, escalation, and out-of-band notification complexity |
| Defer audit to ADR 0023 production readiness, as originally sequenced | Step two already requires audited replay. Building audit late means retrofitting it into every capability that shipped without it | Never |

## Consequences

### Positive

- One searchable, exportable, provably append-only evidence store across every
  module.
- Maker-checker is implemented once, so ADRs 0006, 0012, 0013, 0021, and 0024
  consume it rather than each inventing one.
- Transactional writing means the audit trail cannot silently miss an action
  that succeeded.

### Negative

- Every audited action pays an extra insert, and high-frequency actions need
  deliberate decisions about what is worth auditing.
- The audit table grows continuously and is never deleted by the application, so
  partitioning and archival to retention-protected storage are required early
  rather than eventually.
- Requiring a reason on operator actions adds friction to every Operations
  workflow, and a poorly designed reason field becomes a dropdown of "other".

### Accepted trade-offs

- Audit is written synchronously in the business transaction, so an audit write
  failure fails the business action. This is the correct direction for evidence
  and does mean audit availability is business availability.
- Structured change documents are more work to produce than a message string,
  and are the reason the trail is queryable years later.

## Implementation notes

Delivered: the partitioned append-only store, the transactional recorder, the
classification-aware change-document serializer, the approval policy and request
tables, and the shared maker-checker service with policy snapshotting.

Two guarantees are proven by test rather than asserted:

- **Evidence cannot be rewritten.** A test connects as the restricted
  `qoida_application` role, reads and inserts successfully, and fails on both
  `UPDATE` and `DELETE`.
- **A committed change always has its fact.** The recorder joins the caller's
  transaction with no annotation of its own, so a business failure after the
  audit write rolls both back, and a commit carries both.

Two bugs surfaced during implementation and are worth remembering: `decide` and
`expireOverdue` originally used wall-clock time while accepting an injected
`Clock`, so they ignored it entirely. The ADR's requirement that approval
behaviour be deterministic under a fixed clock is what caught them. `recorded_at`
is now supplied by the column default, because it means "when the database
recorded this", not "when the application thought it did".

The control-plane service now records facts for tenant creation, brand
creation, location creation, and Keycloak organization linking, in the same
transaction as each change.

Query endpoints now exist behind `audit.read`, and **reading audit is itself
audited**, including how many records were returned: the difference between an
agent reading one record and exporting two hundred is exactly what this control
exists to capture. The change document is deliberately absent from list results,
because redacted structure is still revealing in bulk; retrieving one is a
separate, individually audited read.

`AuditPartitionManager` keeps partitions two years ahead. The default partition
exists so an audited action can never fail for want of a partition, but rows
landing in it are a symptom rather than a design, and a test asserts it stays
empty.

Not yet delivered: archival to retention-protected storage, and retention
periods confirmed by legal and finance.

## Physical model

```text
audit.audit_events
  id, tenant_id null, audit_class, action_code
  actor_type, actor_subject null, actor_display null
  on_behalf_of_subject null
  scope_type, scope_id null
  target_type, target_id null, target_version null
  outcome, reason null
  change_document jsonb null, evidence_reference null
  capability_used null, approval_request_id null
  correlation_id, causation_id null, request_id null
  source_ip_hash null, user_agent_hash null
  occurred_at, recorded_at
  partition by range (recorded_at)

audit.approval_requests
  id, tenant_id, action_code, parameters_hash
  scope_type, scope_id null
  policy_id, policy_version, threshold_description
  status, requested_by, requested_at, reason
  decided_by null, decided_at null, decision_reason null
  expires_at, evidence_reference null
  version
  check (decided_by is null or decided_by <> requested_by)

audit.approval_policies
  id, tenant_id null, action_code, scope_type
  threshold_json, required_approver_capability
  valid_from, valid_until null, version, approved_by
```

`actor_type` distinguishes `USER`, `SERVICE`, `SYSTEM_JOB`, and `MIGRATION`, so
a backfill is never mistaken for a person. Retention is enforced by archival to
retention-protected storage, not by deletion in place.

## Contract

```java
interface AuditRecorder {
    void record(AuditFact fact);              // same transaction as the change
}

interface ApprovalService {
    ApprovalOutcome requireApproval(ApprovalRequestCommand command);
    void decide(ApprovalId id, Decision decision, ActorRef approver, String reason);
}
```

`requireApproval` returns `NOT_REQUIRED`, `PENDING`, or `APPROVED`. A caller
that receives `PENDING` must not perform the side effect; it persists its own
state and resumes when the approval is decided, which composes with the
process-manager pattern in ADR 0019 and the step handlers in ADR 0008.

## Redaction and classification

The change document is produced by a classification-aware serializer. Fields
carrying PII, credentials, or regulated payment data are stored as a hash or an
opaque reference, never as a value. ADR 0029 owns the classification rules; this
ADR enforces them at the boundary. A serializer test asserts that no field
marked sensitive can reach the audit table in plaintext.

## Testing

- An audited action that commits always has its fact, proven by rolling back the
  audit insert and asserting the business change rolls back too.
- The application role cannot update or delete an audit row.
- Requester and approver cannot be the same subject, at the database level.
- An expired approval cannot authorize an action.
- A policy change after approval does not alter the snapshotted threshold.
- Reading audit produces its own audit fact.
- Sensitive fields never appear in the change document.

## Rollout and rollback

Deliver the audit store and recorder before ADR 0006, and the approval model
before the first monetary capability in ADR 0013. Existing control-plane
mutations adopt the recorder as they are touched. Rollback disables new audited
features but never disables audit writing for an action that still executes.

## Implementation checklist

- [ ] Approve retention periods and archival destinations per audit class (legal, finance).
- [x] Add partitioned audit, approval request, and approval policy tables (`V0007`).
- [x] Restrict the application database role to insert and select on `audit` (`qoida_application`), proven by a test that connects as that role and fails to update or delete.
- [x] Implement the transactional recorder and classification-aware serializer (`JdbcAuditRecorder`, `ChangeDocuments`).
- [x] Implement approval policy resolution using the ADR 0030 scope chain, with the policy version and threshold snapshotted onto the request.
- [x] Make missing-policy behavior explicit per registered action and expose configured coverage (ADR 0050).
- [x] Implement the `ApprovalOutcome` pending-side-effect contract (`ApprovalService`). HTTP endpoints remain, and need ADR 0025 capabilities.
- [x] Add audit query APIs behind the `audit.read` capability, with the read itself audited.
- [x] Implement partition management (`AuditPartitionManager`). Archival to protected storage remains.
- [x] Add immutability, self-approval, expiry, and redaction tests.

## Exit criteria

Every operator-initiated mutation in the platform produces an immutable audit
fact in the same transaction, maker-checker is enforced by one shared mechanism
with a snapshotted policy version, no application path can modify or delete
audit evidence, and sensitive values are provably absent from the trail.

## References

- [ADR 0025: Fine-grained authorization and the capability model](../built/0025-fine-grained-authorization-and-capability-model.md)
- [ADR 0029: PII protection, envelope encryption, and key rotation](../partial/0029-pii-protection-envelope-encryption-and-key-rotation.md)
- [ADR 0050: Missing approval policy behavior](../built/0050-missing-approval-policy-behavior.md)

# ADR 0092: The control plane shows how personal data is treated

- Decision status: Accepted
- Implementation status: Built — `DataProtectionController` reading the data classes, every `*_encrypted` column from the database catalog, the retention rules with their enforcing jobs, `customer.erasure_requests` and the audit trail's reveals and exports, tested in `DataProtectionControllerTests`; the control-plane PII and data classification screen. V0209 and the `CartRetentionSweeper` and `CourierApplicantRetentionSweeper` that enforce the two rules decided on 2026-09-11, tested in `CartRetentionSweeperTests` and `CourierApplicantRetentionSweeperTests`
- Date proposed: 2026-09-11
- Date decided: 2026-09-11
- Deciders: proposed by Claude and built on the platform owner's instruction of 2026-09-10 to finish the control plane's remaining waves; accepted by Ayubkhon Abbosov (platform owner) on 2026-09-11, who answered its open inputs the same day
- Depends on: ADR 0027, ADR 0029, ADR 0042, ADR 0044, ADR 0059
- Supersedes / Superseded by: —
- Open inputs: closed 2026-09-11: a cart that never became an order is deleted 90 days after it was last touched, and a courier application nobody verified is erased 12 months after it was last touched. Counsel should confirm both periods

## Context

ADR 0029 classifies and encrypts personal data, several modules delete it on a
schedule, customers can ask to be forgotten, and every reveal and export is
audited. None of it could be seen in one place: the PII screen (IA 6.4) said
the registry, the retention schedule, the egress log and the erasure workflow
did not exist, when most of them did, spread across five modules.

## Decision

The control plane reads one overview from what enforces each part. It adds
no rule of its own; the two rules the first version listed as missing were
decided by the platform owner on 2026-09-11 and are enforced by their modules
(item 6):

1. **Classes.** The data classes and what each requires: encrypted at rest,
   and whether a value may appear in an event or a log.
2. **Encrypted columns.** Every column named `*_encrypted`, read from the
   database's own catalog, so a new one appears without anyone listing it.
3. **Retention.** Each kind of personal data, how long it is kept and the job
   that deletes or archives it. The list lives in one place with the enforcing
   class named, and a test fails if a named job disappears.
4. **Erasure.** Requests to be forgotten by status, and those waiting, with
   their tenant and how long they have waited.
5. **Egress.** Reveals and exports of personal data in the last 30 days, by
   kind, counted from the audit trail.

6. **The two missing rules.** A cart that never became an order is deleted
   90 days after it was last touched and after it expired; a converted cart,
   or one an order names, is an order's history and is never touched. A
   courier who applied and was never verified, never on a shift and never
   paid is erased 12 months after their record was last touched: the name is
   overwritten with a tombstone and the courier archived. The courier module
   has no rejection step, so an application nobody verified in that time is
   treated as a rejected one; a verified courier's record is their settlement
   history and is not an applicant record.

Nothing in it names a customer: counts, identifiers and column names only.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Each retention job registers itself | Five modules and five enforcement styles for a read-only list; the named-class test catches the drift that matters | A sixth rule is added |
| Scan annotated Java fields for the registry | The database is where the data lives; the catalog cannot drift from it | A classified field is stored without the naming convention |
| Show the audit entries themselves | Each carries a person's reason about a named customer; the audit log already shows them behind its own capability | Never here |

## Consequences

### Positive

- One place answers what is protected, for how long, and who looked.
- A late erasure request is visible without a query.

### Negative

- The retention list states each period in words and can drift from a job's
  real period; only the enforcing class's existence is checked.

### Accepted trade-offs

- The egress figure counts by action code suffix, so a new reveal named
  differently would be missed until it follows the convention.

## Specification

- `GET /control-plane/data-protection` (`audit.read`, platform scope).

## Rollout and rollback

Read-only; nothing to roll back.

## Implementation checklist

- [x] Overview read and its test
- [x] PII and data classification screen

## Exit criteria

A reveal of a customer's contact appears in the last-30-days count, and every
retention rule names a class that exists.

## References

- `docs/frontend-information-architecture.md` §6.4

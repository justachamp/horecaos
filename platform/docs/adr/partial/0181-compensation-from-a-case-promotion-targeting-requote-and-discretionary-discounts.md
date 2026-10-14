# ADR 0181: Compensation from a case, promotion targeting, the re-quote and discretionary discounts

- Decision status: Accepted — proposed by Claude (batch 21, wave `w6-promotions-reviews-approvals`); accepted by the platform owner 2026-10-14
- Implementation status: Partial — the wave is being built; this line is advanced to Built when it ends.
- Date proposed: 2026-10-14
- Date decided: 2026-10-14
- Deciders: proposed by Claude (batch 21); Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0018, ADR 0019, ADR 0025, ADR 0027, ADR 0044, ADR 0048, ADR 0050, ADR 0072, ADR 0112, ADR 0140, ADR 0159
- Supersedes / Superseded by: — (edits no record's decision; it adds a dated status addition to ADR 0019, ADR 0140 and ADR 0159 where a decision below closes one of their open inputs)
- Open inputs:
  - Finance, for the three `pricing.promotion.approval.*` limits, the new gift-value key and the customer export limit: the defaults stay provisional (finance).
  - Finance and counsel, for the fiscal and tax treatment of a service line and the lawfulness of a payment-method-dependent surcharge: the order-level markup is built and stays off until they answer (finance, counsel). See `docs/runbooks/external-connections/w6-promotions-reviews-approvals.md`.
  - Counsel, for the class of the loyalty-expiry message and the expiry of a promotional balance, and the SMS gateway, for each tenant's wording (counsel, platform owner). Same runbook.

**Decision record, 2026-10-14.** Accepted by Ayubkhon Abbosov (platform owner) with the instruction "accept all defaults, start batch 21" (batch 21); each decision below is taken at the value the owner chose or, where the owner said nothing else, at the smallest default the batch-21 plan proposed.

## Context

Four rows of the operations gap map wait on a choice that no Accepted record makes, and
each is cheap to take once somebody takes it.

**Who may issue compensation from a service-recovery case (row 5.4).** ADR 0159 Decision 9
says a case links to compensation and never pays it, and its exit criteria say "a support
agent ... mints an apology code from the case". The mint is `PRICING_PROMOTION_MANAGE`,
which ADR 0072 gives to the tenant owner alone ("a decision to give the tenant's own money
away"); the remedy endpoints demand tenant-scope `REFUND_REQUEST` and `PAYMENT_READ`
(ADR 0048), which only the owner and finance hold; and ADR 0159's role-bundle changes give
case workers neither. The exit criterion and the capability model disagree.

**Named-customer targeting (row 6.1).** ADR 0140 ships no named-customer condition until a
static audience exists; ADR 0044 and ADR 0112 offer only computed attributes, and
`PredicateType` has no list kind. ADR 0140 says "the owner confirms that is acceptable for
the pilot".

**What a changed scheduled-order re-quote leads to (row 6.1).** `ScheduledOrderRequoteService`
prices a scheduled order's live basket again an hour before the promised time
(`horecaos.ordering.scheduled-requote.lead`, PT60M) and records the promotions gained,
dropped or changed. The order is untouched. ADR 0019 says "any customer-visible
price/substitution change requires explicit acceptance or safe cancellation" and leaves the
scheduled-order checkpoints open.

**A discretionary order-line discount (row 9.4).** ADR 0140 says "operator-applied
discretionary discounts have no ADR and are not decided here". The IA's row 9.4 lists the
producer among the things a second signature guards.

## Decision

**D10. No delegation of compensation.** A case worker links and hands off; the tenant's
money is spent only by those who already hold the capability to spend it.

- The case drawer lists the remedies of the case's order and the grants of the case's
  customer through one case-scoped read under `recovery.case.manage` (the read is the
  `CompensationDirectory` SPI the case already uses to verify a link; the caller is a
  capability-gated case endpoint that has already decided who may ask), so the worker
  picks what exists instead of pasting an identifier.
- The drawer deep-links into the order-remedy console with the order prefilled.
- The apology-code mint is reachable from the drawer for a person who holds
  `PRICING_PROMOTION_MANAGE` (today the tenant owner): the drawer shows the mint form to
  them and nobody else, calls the existing mint endpoint with source `RECOVERY_CASE`, the
  case id and the customer prefilled, shows the plaintext once, and attaches the grant.
- Tenant-owner, tenant-admin and tenant-finance issue compensation under their existing
  capabilities. ADR 0159's exit criterion is read as "an owner mints"; no role bundle
  changes and no new capability exists.

**D11. Named-customer promotion targeting is declined for v1.** No STATIC audience kind is
built, no account-id list is added to a rule. The revisit trigger is the one ADR 0140
already names: a static audience is built (with its consent, suppression and erasure
design).

**D12. The re-quote stays evidence.** A changed scheduled-order re-quote is a finding the
operator acts on (phones the customer, or cancels with the existing cancel path); the order
is untouched and there is no customer-facing acceptance flow in this wave. The checkpoints
are the confirmation of the order and the kitchen start; the sweep's instant is the
promised time less `horecaos.ordering.scheduled-requote.lead`. This closes ADR 0019's open
input on the checkpoint instants for pricing and is recorded there as a dated status
addition. The console screen for the findings and the re-check button are built in this
wave.

**D23. A discretionary order-line discount is declined for v1.** The intent is served by the
approval-gated producers that exist: `payments.remedy.record`,
`payments.remedy.future-discount` and `ordering.amendment.decrease`. IA 9.4 is read
without "discretionary discounts"; the worklist labels the existing actions so an approver
can tell them apart. A new ADR 0039 command with reason codes, a cap and a new
`ApprovalAction` would be the way to build it.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Mint under `recovery.case.manage` with value ceilings in the `reviews.recovery` policy (D10) | The tenant's own money would be given away by a role that was not chosen for it, ADR 0072 reserves that decision to the owner, and the ceilings are numbers only the owner can set; an L change in `reviews`, `pricing` and `PlatformRole` | The owner names a role that may give away a bounded amount, and the ceilings |
| Add `PRICING_PROMOTION_MANAGE` or `REFUND_REQUEST` to the case-worker bundle (D10) | Capability inflation: every case worker could mint any amount | Never without a ceiling |
| A STATIC audience kind with an uploaded list (D11) | An L change with its own consent, suppression and erasure design; the pilot has no named-customer promotion to run | A tenant needs a promotion for a named list and a static audience is built |
| An account-id list inside a rule (D11) | ADR 0140's own rejected row: no consent or erasure hook for a per-customer entitlement list | Static audiences are never built and the need is pressing, with an erasure hook |
| A customer-facing acceptance flow with a payment delta for a changed re-quote (D12) | An XL change across ordering, payments, notifications and both storefronts for a case the pilot has not met; nothing says how often a promotion changes between order and kitchen start | The first scheduled order is repriced and the customer disputes it |
| Re-quote only at the kitchen start (D12) | The operator would learn of a changed price when there is no time to phone the customer | A single checkpoint proves enough |
| A new ADR 0039 command for an operator discount, with reason codes and a cap (D23) | About L to XL, a new `ApprovalAction`, and the three remedy producers already give a customer a lower price with a reason and a second signature | A tenant needs a goodwill discount that is not a refund, a future discount or an amendment decrease |

## Consequences

### Positive

- The case drawer stops asking an operator to paste an identifier, and the exit
  criterion of ADR 0159 is true to the capability model.
- No capability, role bundle or ceiling is invented, so nothing here widens who can spend
  the tenant's money.
- The re-quote findings become something an operator can read.

### Negative

- **A support agent who is not the owner cannot give an apology code.** She links an
  existing remedy or grant, or asks the owner. A busy owner is the bottleneck the first
  tenants will report.
- **A customer whose scheduled order is repriced is not asked.** An operator has to phone
  them; if nobody reads the finding, the order is made and charged at the original price.
- **Named customers cannot be targeted by a promotion.** A tenant who wants "these fifty
  regulars" builds a segment by attribute or sends a coded benefit grant to each.
- **No operator can type a discount into an order line.** An owner who wants one uses a
  remedy and takes its second signature.

### Accepted trade-offs

- Each decision takes the smallest default; each names the event that reopens it.

## Specification

The wave implements the decisions as follows (see the gap-map rows `5.4`, `6.1`, `6.3` and
`9.4`):

- D10: `GET .../recovery-cases/{caseId}/compensation-candidates` (`recovery.case.manage`),
  the picker and the mint form in `recovery-case-drawer`.
- D11: IA row 6.1 and ADR 0140's status line record the decline.
- D12: `ScheduledOrderRequoteService` is unchanged; the console shows its findings
  (`GET`/`POST .../orders/{orderId}/promotion-requotes`).
- D23: IA row 9.4 and `operations-spec/staff-and-access.md` record the decline;
  `approval-action-labels.ts` labels every tenant-worklist action.

## Rollout and rollback

Documentation and console changes; nothing to roll back in the database for the four
decisions themselves.

## Implementation checklist

- [x] ADR 0181 written
- [ ] D10 picker, hand-off and mint in the drawer
- [ ] D11 recorded in the IA, the gap map's source rows and ADR 0140
- [ ] D12 console panel and the dated addition to ADR 0019
- [ ] D23 recorded in the IA and the staff spec; labels

## Exit criteria

An operator holding `recovery.case.manage` opens a case, sees the order's remedies and the
customer's grants, attaches one by choosing it, and follows a link to the order-remedy
console; the owner mints an apology code from the same drawer and the code can be spent at
checkout; an operator opens a scheduled order and sees its re-quote findings; the worklist
shows a label for every tenant action.

## References

- ADR 0019, ADR 0048, ADR 0072, ADR 0112, ADR 0140, ADR 0159

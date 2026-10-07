# ADR 0171: Who owns a loyalty balance when the till also keeps one

- Decision status: Proposed — proposed by Claude (batch 19); the platform owner decides
- Implementation status: Not started — the ledger is complete and the till is not
  connected to it. The platform side is built: `loyalty.accounts` is keyed
  `(tenant_id, brand_id, customer_account_id)` and carries `balance_minor` (spendable;
  points held by a reservation are already debited), `reserved_minor` and `version`;
  `loyalty.entries` is append-only with a closed `entry_type`; a credited
  `ADJUSTMENT` grants a lot that expires like any other (`LoyaltyAdjustmentService`);
  `ReferralGrantPort` and the accrual clawback are the two precedents for a *system*
  actor writing an `ADJUSTMENT` with no human approval; `loyalty.clawbacks` (V0079)
  holds what a refunded order cost the brand. `LoyaltyBalanceChanged` is published only
  by `LoyaltyAccrualService.accrue` and `PointsRedemptionService.reserve`, so it is not a
  complete trigger for anything that must follow every movement. The till side has no
  loyalty at all. `PosAdapter` has no balance or movement method; `PosCapability` has
  ten constants and none is a loyalty one; `integration.pos_provider_capabilities`
  holds rows for `clopos` only, and `integration.pos_capability_is_supported()` refuses
  to enable a capability nobody has assessed; exactly one `PosAdapter` bean is wired
  (Clopos) and `docs/providers/` holds notes for Click, Payme, Clopos and an SMS gateway,
  none for iiko or R-Keeper, so nothing in the repository says what either exposes for a
  customer wallet. The identity machinery a sync would reuse exists:
  `integration.provider_entity_mappings` (ADR 0026) with `MappingEntityType` (nine
  types today; `OPERATOR` was the latest, added by ADR 0139 on the same footing this
  record needs), and
  `CustomerPhoneLookup.findByPhone`, which takes a plaintext number and hashes it inside
  the customers module. The console says what is missing in one place: the «POS balance
  sync» card on the loyalty page (`marketing.loyalty.posSync.*`: "Not built. No ADR owns a
  point-of-sale terminal reading or writing a loyalty balance, and no provider capability
  declares it"). A tenant whose till keeps bonus balances today runs two ledgers and
  nothing says so to anyone.
- Date proposed: 2026-10-07
- Date decided: —
- Deciders: proposed by Claude (batch 19); Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0007, ADR 0011, ADR 0012, ADR 0015, ADR 0025, ADR 0026, ADR 0027,
  ADR 0029, ADR 0030, ADR 0031, ADR 0032, ADR 0033, ADR 0046, ADR 0139, ADR 0165
- Supersedes / Superseded by: — (amends ADR 0011 without editing it: it adds an eleventh
  capability, `LOYALTY_BALANCE_SYNC`, to that record's capability model, and it reopens
  exactly one entry of it, the `CUSTOMER_UPSERT` row ("Partial, not enabled — needs an
  ADR 0029 consent basis, not an endpoint"), only to say that this capability does not use
  it, because no customer data leaves HorecaOS. It adds to ADR 0046 without editing it,
  and keeps one of its rejected rows rejected: «Points as an abstract currency with a
  tenant-set conversion rate» stays rejected, so only a one-to-one programme can be
  synced)
- Open inputs: each is closed on its proposed default if the owner accepts the
  record as written; the ones that name a person other than the owner stay with
  that person and the work they block is marked.
  - **What iiko's and R-Keeper's APIs expose for a customer wallet**: reading a
    balance, listing movements since a cursor, crediting and debiting with an
    idempotency key, setting a balance, placing a hold, and whether the programme's unit
    is one-to-one with a som (integration discovery, owned by Ayubkhon Abbosov through the
    vendors and their dealers, as ADR 0011 did for Clopos). Proposed default: nothing is
    assumed. Every cell is "unassessed", so `integration.pos_capability_is_supported()`
    refuses `LOYALTY_BALANCE_SYNC` for both vendors; no adapter code is written for either
    before a provider note exists in `docs/providers/` and a ceiling row exists with
    evidence. Blocks: each vendor's adapter, and only that.
  - **Whether the pilot's POS, Clopos, keeps a customer balance and exposes it**
    (Clopos, asked through the owner; the customer endpoints are in
    `docs/providers/clopos-api.md` and the notes record no balance field). Proposed
    default: unassessed, so the capability is not available on a Clopos binding. Blocks:
    nothing.
  - **How much a till may credit without a person looking** (finance, with Ayubkhon
    Abbosov). Proposed default: a till-originated credit above 100 000 points for one
    customer in one business day, or 2 000 000 for one binding in one business day, is held
    for review and not applied, as ADR 0030 policy
    (`loyalty.pos_sync.max_auto_credit_minor`, `loyalty.pos_sync.max_binding_credit_minor_per_day`).
    The figures are an order of magnitude above a normal day's earning on an 84 000 som
    order and are chosen to hold too much rather than too little. Blocks: nothing.
  - **How a till customer is matched to a platform account** (Ayubkhon Abbosov).
    Proposed default: by the normalised phone the till returns, through
    `CustomerPhoneLookup`, and only when it resolves to exactly one account that has a
    points account at the binding's brand. No match, or more than one, is reported and
    never guessed (ADR 0015 forbids the auto-merge a guess would be). Blocks: nothing.
  - **Whether syncing a customer's balance with the tenant's own till needs a consent
    purpose** (legal counsel, engaged by the platform owner). Proposed default: no. The
    till is the tenant's own system, no customer data is sent to it (the platform writes a
    number against the till's own customer id), and the phone the till returns is used
    once to find an account and discarded. If counsel disagrees, matching is gated on a
    consent purpose read through `ConsentDirectory`, absence counts as withheld, and an
    unconsented customer appears in the report as `NOT_CONSENTED`. Blocks: nothing.
  - **The convergence window the product promises** (Ayubkhon Abbosov, as product
    owner). Proposed default: a platform movement reaches the till within 60 seconds, a
    till movement reaches the platform within the five-minute pull interval, and a nightly
    reconciliation after day close compares every matched account. The screens say "may
    differ for up to five minutes". Blocks: nothing.
  - **Who resolves a discrepancy** (the tenant's finance role, named by the tenant).
    Proposed default: a holder of the new capability `loyalty.sync.manage`, with an ADR
    0027 approval by a second person when the resolution credits points above the same
    threshold as a manual adjustment. Blocks: nothing.

**To accept as written:** say "accept 0171". Every open input above is then closed on
its proposed default.

## Context

Gap-map row `6.3b` (IA 6.3, tier 3) is `NOT BUILT`: "Loyalty — POS balance sync". Its "What
is missing": "A tenant whose till also holds bonus balances runs two ledgers: points earned
or spent at the POS terminal never reach the platform, and a platform redemption is invisible
to the till, so a cashier and the app disagree about what a customer has." Its "Blocked by":
"No ADR covers which side owns the balance; each POS vendor (iiko, R-Keeper) needs its own
capability adapter." Part B adds "each POS needs its own capability adapter and a
`pos_provider_capabilities` ceiling row", and the owner it names is "An ADR plus a vendor".
IA 6.3 lists "POS balance sync" among the loyalty screen's owned things, beside accrual,
redemption cap, expiry and deposit accounts (the last now declined, ADR 0165).

**The question has two halves and only one is a decision.** Which side owns the balance is
a decision, and the repository has already decided it implicitly three times: ADR 0046 made
the platform's append-only ledger the authority and the balance a projection of it; ADR 0012
made the platform authoritative for the customer-facing price, and ADR 0011 treats a till
as a system it exports to and reads from through capabilities; and `CLAUDE.md` records, from the
2026-08-26 audit, what happens when a stored balance and its entries are two sources of
truth — "`balance == SUM(lots.remaining)` held through every loyalty money bug while
`balance - SUM(entries)` was the one that broke". A till's balance is a second stored
balance in a system the platform does not run. The other half, what an iiko or an R-Keeper
can read and write, is a fact about two vendors this repository has never read. This record
decides the first and refuses to guess the second.

**What the till's balance is, and what it can be allowed to be.** A till's bonus balance
is either a number the platform pushed there or a number the till computed from earn and
spend events at the terminal. The first is a mirror and can be reset to the platform's
number at any time. The second contains facts: a customer already walked out having spent
5 000 at the till. A reconciliation that "fixes" the till back to the platform's number would
undo a discount the restaurant has already given, and one that overwrites the platform with
the till's number would discard a redemption the platform has already settled on an order.
Neither is an answer. Movements that already happened are facts and must be imported;
the balance is a derived quantity and must be compared, not copied.

**What the ledger allows an import to be.** An `ACCRUAL` entry must name a rule
(`ck_loyalty_entry_accrual_snapshots_its_rule`), and a `REDEMPTION`, `RELEASE` or `REVERSAL`
must name an order, a tender and a lot (`ck_loyalty_entry_tender_movement_names_an_order`).
A till earn or spend has none of those, so the only order-free movement the ledger admits is
`ADJUSTMENT`, which is what the referral grant and the accrual clawback already use, with a
system actor and a reason code. That is not a loophole and not a new entry type: a till movement
is an adjustment in exactly the sense ADR 0046 gave the word, a movement an operator or a
system authors outside an order, with the same lot treatment (a credit grants a lot that
expires) and the same audit. What it must not inherit is the unlimited credit: ADR 0046's
"an unbounded manual credit is a cash drawer any console login can open" applies with more force
to a system that credits without a person, which is the reason for the caps below.

**What the vendors owe the design, and what they do not.** The gap map names two
vendors, and the parity matrix documents Delever's per-branch tabs for iiko (credentials,
branches, products, terminal, couriers, discounts) and the connection steps for R-Keeper;
neither documents a customer balance. The capability
model from ADR 0011 is built for exactly this ignorance: support is three-valued, a ceiling row
needs evidence, and an unassessed capability is unconfigurable "in the database, not by a screen
that is supposed to prevent it". So the honest position for a vendor nobody has read is already
implemented by the trigger, and "report vendors without the capability honestly" means making
the unassessed state visible to the operator, not making it work.

## Decision

**The platform's loyalty ledger is the single authority for every balance the platform
issues. A till's balance is a mirror kept equal by an adapter capability,
`LOYALTY_BALANCE_SYNC`, over the ADR 0026 binding; movements that happened at the till are
imported as facts, bounded by caps; a reconciliation report compares the two and names every
difference; and a vendor that cannot do this is reported as unable, never as working.**

1. **One ledger, no second stored balance.** The platform writes no balance that is not the
   sum of its entries and holds no copy of the till's number except as an observation
   (`last_till_balance_minor`) beside the account it was observed against. A disagreement is
   resolved by the rules below, never by assigning a number to the account.
2. **A new capability, `LOYALTY_BALANCE_SYNC`, with stated halves.** It is the eleventh
   `PosCapability`, stored under that name in `integration.binding_capabilities` and
   `integration.pos_provider_capabilities`. Its sub-properties — reads a balance, lists
   movements since a cursor, credits, debits, sets a balance, holds, one-to-one programme,
   idempotency (`KEYED`, `NATURALLY_IDEMPOTENT` or `NONE`) — are recorded as an additive
   `limits` column on the ceiling row. `PARTIAL` is the honest value for a vendor that can
   write a balance and not read movements, and the rationale names the missing half.
   Every vendor starts unassessed; the trigger refuses it.
3. **Push: the platform's number goes to the till.** After any committed movement on a matched
   account (not only the two that publish `LoyaltyBalanceChanged`: accrual, reservation,
   release, expiry, forfeiture, adjustment, reversal), a command carrying the binding, the
   account and the account's `version` is written through the outbox. The handler reads the
   *current* balance, sends it to the till's own customer id with an idempotency key built
   from the version, and verifies by read-back where the vendor lets it. Only the latest
   version matters, so commands for one account coalesce. An answer that is not known (the
   request was sent and the reply was lost) is resolved by a read, never by a second send, as
   the order export is (ADR 0011).
4. **Pull: what happened at the till is imported as a fact.** The adapter lists movements since
   a stored cursor. A till earn becomes a credit `ADJUSTMENT` with reason `POS_SYNC_EARN`, which
   grants a lot with the lifetime every credited adjustment gets today (180 days,
   `LoyaltyAdjustmentService`) and so expires like any other point; a till spend becomes a
   debit `ADJUSTMENT` with reason `POS_SYNC_SPEND`, consuming lots oldest-expiry-first as
   `consumeFromOldestLots` does for every debit. A tenant whose till expires bonus on a different schedule gets the
   platform's schedule here; matching the till's is a later decision with its own evidence. The actor is `system:pos-sync:<bindingId>`, the idempotency key is
   `POSSYNC:<bindingId>:<till movement id>`, so a replayed or overlapping page imports once.
   A till spend larger than the balance applies what the balance covers and records the rest
   as `SPEND_EXCEEDS_BALANCE`, absorbed by the brand's legal entity and visible on the liability
   report; the balance never goes negative (ADR 0046).
5. **Caps hold what a person has not seen.** A till-originated credit above the policy caps
   is stored `HELD`, opens a discrepancy and credits nothing until a person with
   `loyalty.sync.manage` resolves it, with a second approver where the resolution credits
   points above the manual-adjustment threshold. A suspended binding imports nothing. The
   reason codes `POS_SYNC_EARN`, `POS_SYNC_SPEND` and `POS_SYNC_CORRECTION` are written by
   the sync only and never pass through the operator reason-code list of ADR 0165.
6. **A reconciliation report names every difference.** After day close, and on demand, each
   matched account's balance is compared with a fresh read of the till's. The report counts
   compared, matched, till higher, till lower, till-only customers, platform-only customers,
   ambiguous matches, imported movements, held movements and unverifiable accounts, and lists the
   discrepancies with the two balances and the difference. A difference is never silently
   corrected. The resolutions are: accept the till (apply the difference as an adjustment, under
   approval), accept the platform (push it again), absorb it (the brand carries it), or
   suspend that customer's link.
7. **Matching reads the till; it sends nothing about the customer.** The adapter lists the
   till's customers, the platform finds each by `CustomerPhoneLookup.findByPhone`, and a unique
   match at the binding's brand is recorded as an ADR 0026 mapping of a new
   `MappingEntityType.LOYALTY_CUSTOMER` (the horecaos id is the points account's id), source
   `DISCOVERED`. Till customers with no platform account are not imported and no platform
   account is created from a till record (an account made from a till row would be a person
   nobody consented to be contacted); platform customers with no till record are not pushed to
   a till that does not know them, and `CUSTOMER_UPSERT` is not used.
8. **A vendor without the capability is said to be without it.** The control-plane POS
   capability matrix shows `LOYALTY_BALANCE_SYNC` per vendor as `SUPPORTED`, `PARTIAL`,
   `UNSUPPORTED` or `UNASSESSED`. A tenant that tells the platform its till keeps its own
   balance (`loyalty.pos.till_keeps_balance`) and has no binding that carries the capability
   sees a standing notice on the loyalty page and on a customer's loyalty card: "This brand's
   till keeps its own bonus balance and HorecaOS cannot see or change it. A customer's balance
   here and at the till can differ." Redemption is not blocked; the notice is the honest
   statement, and the tenant's alternative, retiring the till's balance and importing it once as
   `LEGACY_OPENING_BALANCE` adjustments, is documented as a tenant decision, not offered as a feature.
9. **One-to-one only.** A programme whose unit is not one point to one som cannot be bound, and
   the refusal says why. ADR 0046's rejected row about a tenant-set conversion rate stays rejected.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| The till is the ledger and the platform is a client of it for every accrual and redemption | The platform's checkout needs reserve, release and expiry with lots (ADR 0046); a vendor API with those and an idempotency key is unknown for both vendors; and a till outage would stop online redemption. It also makes a second, unread authority the source of a liability the brand's legal entity owes | A tenant's contract makes the till the loyalty system of record *and* a named vendor's API supports hold, capture and release with idempotency; then it is a per-tenant mode with its own ADR |
| Leave the two ledgers independent and report the difference | It is today's state with a report added. It does not stop the cashier and the app disagreeing, and without an import a till spend is invisible to the platform's liability figure | No assessed vendor supports any half of the capability; then it is exactly the fallback Decision 8 describes |
| Last-writer-wins on the balance number, in either direction | Loses the other side's spend. The two-writers-one-number shape is the "stored balance beside its entries" defect `CLAUDE.md` records, with a network between the writers | Never |
| Exchange movements only, no balance comparison | Drift from a lost movement is invisible until a customer is refused, and an at-least-once feed needs the balance check to be trusted at all | Never as the whole; Decision 4 is the movement half and Decision 6 the check |
| Create till wallets for platform customers (`CUSTOMER_UPSERT`) | Sends a customer's phone and name to a third-party system, which ADR 0011 says needs a consent basis and not an endpoint | Counsel confirms a basis and a tenant asks to have its platform customers appear at the till |
| Import till customers as platform accounts | Creates a platform account for a person who never registered, contactable by nobody's consent (ADR 0039's "absence of a decision is not consent") | Never without a consent basis; `IMPORT`-origin accounts exist for a tenant's own CSV, not for this |
| Apply till movements without caps, because the till is the tenant's own system | A compromised credential or a staff member at the terminal then mints points at the speed of the till's API, and the cost is the brand's liability. The manual path has caps and approvals for the same reason | A vendor's feed carries a signed, tamper-evident movement and the tenant asks to raise the caps |
| Build the iiko adapter now on what Delever's documentation implies | The parity matrix describes tabs, not a wallet API; an adapter written on a guess is how an export with no idempotency key was found only by reading Clopos | A provider note exists in `docs/providers/` and a ceiling row has evidence |
| One tenant-wide pool synced to every till | Brand B's till would show points brand A owes, a transfer between legal entities ADR 0046 refuses | Participating brands resolve to one legal entity (ADR 0046's own condition) |

## Consequences

### Positive

- A cashier and the app stop disagreeing, or disagree for a stated window and a stated reason.
- The liability report counts what the till gave away, because a till spend becomes a ledger entry.
- No second stored balance appears in the platform, so the defect `CLAUDE.md` warns about has no
  new place to live.
- A vendor nobody has read cannot be enabled, and an operator is told the till's balance is out of
  reach instead of being left to assume it is mirrored.
- No customer data is sent to any till, and no account is created from a till record.

### Negative

- It is eventually consistent. For up to five minutes a till can show a number the platform has
  moved, and a customer can spend the same points at both in that window.
- The overlap is absorbed by the brand: a till spend that exceeds the platform balance is a
  write-off the tenant carries. It should be rare and it is visible, and it is still a cost.
- Someone has to resolve discrepancies, and a tenant that never looks accumulates them.
- Every vendor needs its own adapter, a ceiling row and a provider note before it works, and a vendor
  that cannot read movements cannot be synced in the pull direction at all.
- A system actor credits points without a person. The caps bound it; they do not remove it.
- The state tables and the push command are real surface area for a feature whose first vendor is
  unassessed.

### Accepted trade-offs

- The platform is the authority even where the till's balance is the older and larger one. A tenant
  that wanted the till to win has the alternative in Alternatives, and this record does not build it.
- Imports are adjustments and not a new entry type, so a report of "points earned at the till" is a
  query on reason code and not a column. That keeps the ledger's closed set closed.
- Nothing here is built until a vendor is assessed. The record decides who owns the balance now so
  that the first adapter has somewhere to go.

## Specification

### Physical model

All rows carry `tenant_id`; unique and foreign keys include it. The application role holds `SELECT`,
`INSERT` and `UPDATE` on each and no `DELETE`: they are evidence of what moved, and retention follows
the ledger's.

```text
integration.pos_provider_capabilities   (existing; V0036)
  + limits jsonb null    -- reads_balance, reads_movements, credits, debits, sets_balance, holds,
                         -- one_to_one, idempotency (KEYED|NATURALLY_IDEMPOTENT|NONE)

loyalty.pos_balance_sync_state          one row per matched (binding, points account)
  id, tenant_id, binding_id, account_id, mapping_id          -- mapping = ADR 0026 LOYALTY_CUSTOMER
  status (MATCHED|SUSPENDED|RETIRED)
  last_pushed_version int, last_pushed_balance_minor bigint, last_pushed_at
  last_pull_cursor varchar(255), last_pulled_at
  last_till_balance_minor bigint null, last_till_observed_at null
  version, timestamps
  unique (tenant_id, binding_id, account_id)

loyalty.pos_sync_movements              a till movement, whatever became of it
  id, tenant_id, binding_id, account_id
  external_movement_id varchar(128), kind (EARN|SPEND), amount_minor > 0
  occurred_at_till, received_at
  status (APPLIED|HELD|PARTIALLY_APPLIED|REJECTED), applied_minor, absorbed_minor
  entry_id null, discrepancy_id null
  unique (tenant_id, binding_id, external_movement_id)

loyalty.pos_sync_discrepancies
  id, tenant_id, binding_id, account_id null
  kind (TILL_HIGHER|TILL_LOWER|TILL_ONLY|PLATFORM_ONLY|AMBIGUOUS_MATCH|SPEND_EXCEEDS_BALANCE
        |CREDIT_HELD|RATIO_MISMATCH|NOT_CONSENTED|UNVERIFIABLE)
  platform_balance_minor null, till_balance_minor null, delta_minor null
  status (OPEN|RESOLVED), resolution_code null (ACCEPT_TILL|ACCEPT_PLATFORM|ABSORB|SUSPEND)
  resolved_by, resolved_at, approval_id null, observed_at, version

loyalty.pos_sync_runs                   the reconciliation report's header
  id, tenant_id, binding_id, kind (PUSH|PULL|RECONCILE), started_at, finished_at
  compared, matched, till_higher, till_lower, till_only, platform_only, ambiguous,
  imported, held, unverifiable
  status (RUNNING|SUCCEEDED|FAILED), failure_code null
```

Money is integer minor units in the account's currency; a till balance read in another currency
is refused. `ck_pos_sync_movement_amount CHECK (amount_minor > 0)`,
`ck_pos_sync_movement_applied CHECK (applied_minor + absorbed_minor <= amount_minor)`. Both
`UNIQUE` keys exist for idempotence and are asserted by tests, not trusted.

### The port

Declared in `loyalty.api` and implemented in `pos.application`, the shape of
`fiscal.api.PartnerFiscalizationPort`, so `loyalty` never imports a POS type and `pos` imports only
`loyalty.api`:

```text
PosLoyaltyBalancePort
  listTillCustomers(binding, cursor)      -> [TillCustomer{externalId, phone}]      phone never stored
  readBalance(binding, externalId)        -> TillBalance{minor, observedAt}
  readMovements(binding, externalId?, cursor) -> [TillMovement{externalId, kind, minor, at}], nextCursor
  writeBalance(binding, externalId, minor, ledgerVersion, idempotencyKey) -> ProviderOutcome
```

`PosAdapter` gains four methods with a default that answers "not supported", so the
Clopos adapter compiles and behaves unchanged. `FakePosAdapter` gains an in-memory till wallet whose failure modes are injectable:
a reply lost after a write is applied, a duplicate page, a till spend during a push, a currency
mismatch, an unreadable movement feed.

### Commands and events (ADR 0032)

`PosLoyaltyBalancePushRequested.v1` on `pos.commands` (bindingId, accountId, version; ids only;
retention as the topic's), written through the ADR 0004 outbox in the transaction of the ledger
movement that caused it and consumed through the inbox (ADR 0005). `LoyaltySyncDiscrepancyOpened`
is an in-process signal for the operations alert (`OperationsAlertPort`), never a catalogued
event. No event or command carries a phone, a name or a balance of a person beyond ids; the
version is the only quantity.

### APIs (ADR 0031) and capabilities (ADR 0025)

```text
GET  /api/v1/operations/tenants/{tenantId}/brands/{brandId}/loyalty/pos-sync
       loyalty.read (TENANT)        per binding: capability state, last runs, open discrepancy counts
GET  .../loyalty/pos-sync/runs?from&to           loyalty.read     reconciliation reports
GET  .../loyalty/pos-sync/runs/{runId}           loyalty.read
GET  .../loyalty/pos-sync/discrepancies?status=  loyalty.read
POST .../loyalty/pos-sync/discrepancies/{id}/resolutions
       loyalty.sync.manage (BRAND), mutating; Idempotency-Key; If-Match; ADR 0027 approval above the threshold
POST .../loyalty/pos-sync/runs                   loyalty.sync.manage (BRAND), mutating  (reconcile now)
```

One new capability, `loyalty.sync.manage`, brand scope, granted to the tenant owner and finance and
not to a location manager, because a resolution can credit points and a brand's outstanding points
are its legal entity's liability (the argument ADR 0046 makes for `loyalty.policy.manage`). The
capability matrix endpoint (`/api/v1/control-plane/pos-capability-matrix`) gains the four-valued
state per vendor.

### Policy (ADR 0030)

| Key | Scopes | Default |
|---|---|---|
| `loyalty.pos_sync.max_auto_credit_minor` | `BRAND` and above | 100 000 per customer per business day |
| `loyalty.pos_sync.max_binding_credit_minor_per_day` | `BRAND` and above | 2 000 000 |
| `loyalty.pos.till_keeps_balance` | `BRAND` | false |
| `loyalty.pos_sync.pull_interval_seconds` | `PLATFORM` | 300 |

The caps are versioned policy documents so that a held credit records which version held it.

### PII, audit

- ADR 0029: the till's phone is read, hashed through `CustomerPhoneLookup` and discarded; it is
  never stored, logged, put in an event or shown in the report. The till's own customer id is stored
  in the mapping, classified `INTERNAL` as every other external id in `provider_entity_mappings` is.
  Nothing about the customer is sent to the till.
- ADR 0027: `loyalty.pos_sync.matched`, `.imported`, `.held`, `.resolved` (`BUSINESS`, with counts and ids and
  never a name), and the adjustments themselves leave the existing `loyalty.balance.adjust` fact with
  actor `system:pos-sync:<bindingId>`.

### Testing

- After any sequence of pushes, pulls, replays and a till spend during a push, `balance_minor` equals
  `SUM(entries.amount_minor)` for the account *and* `SUM(lots.remaining_minor)`: both, because the
  audit found the second holds while the first breaks.
- A page of movements replayed three times imports each once; a till spend over the balance applies
  the covered part and records the rest as absorbed; a credit above the cap is `HELD` and credits
  nothing; resolving it credits once and only with the approval.
- The fake till loses a reply after applying a write: the handler reads and does not resend.
- Matching: a phone with two platform accounts is `AMBIGUOUS_MATCH`, none is `TILL_ONLY`, a till
  record never creates an account; the phone is absent from every log appender (the ADR 0029 canary
  pattern).
- `integration.pos_capability_is_supported()` refuses `LOYALTY_BALANCE_SYNC` on an unassessed vendor
  and on a Clopos binding; the matrix endpoint shows `UNASSESSED`.
- The lot clock is advanced past the 180 days so an imported credit's expiry is a duration, not an instant.
- Tenant isolation on every read and resolution; a location-scoped principal cannot resolve.
- Front-end: the standing notice appears for `till_keeps_balance` with no capable binding and not
  otherwise; the report renders every discrepancy kind with its two balances.

## Rollout and rollback

Nothing ships until a vendor is assessed. Then, per vendor: the provider note and the ceiling row
with evidence, the adapter against the fake and the contract tests, then a binding in **report-only**
mode on one branch (pull and reconcile, apply nothing, push nothing) for a stated number of days, read
by the tenant's finance. Then pull applies, with the caps. Then push. The standing-notice half of
Decision 8 ships first and alone: it needs the policy key and the capability matrix state and no
adapter, and it is the part that tells a tenant the truth today. Rollback is suspending the binding's
capability, which stops all three directions; entries already imported stay, because the ledger is
append-only and an import is reversed, if it must be, by an adjustment under approval.

## Implementation checklist

- [ ] Owner accepts the record, or answers the open inputs; vendor discovery for iiko and R-Keeper
      starts in parallel and gates only those adapters.
- [ ] `loyalty.pos_*` tables, `limits` on the ceiling row, grants; check every active worktree's
      `db/migration/` for the next free number.
- [ ] `LOYALTY_BALANCE_SYNC` in `PosCapability`; the four-valued matrix state; the policy keys.
- [ ] The standing notice and the `till_keeps_balance` policy, with strings in ru, uz-Latn and en
      (replaces the «POS balance sync» "not built" card's wording).
- [ ] `PosLoyaltyBalancePort`, the optional `PosAdapter` methods, the `FakePosAdapter` wallet and the
      contract tests.
- [ ] `MappingEntityType.LOYALTY_CUSTOMER` with its entity-exists check.
- [ ] `PosBalanceSyncService`: matching, push command and handler with coalescing, pull and import
      with caps, reconciliation and the report.
- [ ] `loyalty.sync.manage` capability and the resolution endpoints; the console report and
      discrepancy list.
- [ ] Provider note and ceiling row per vendor before its adapter.
- [ ] ADR 0011's and ADR 0046's status lines updated; gap-map row `6.3b` re-audited (this record
      edits none of them).

## Exit criteria

Against a fake till, and later one real vendor's sandbox: a customer earns at the till and the
platform balance shows it within the pull interval as an adjustment with reason `POS_SYNC_EARN`; the
same customer redeems on an order and the till shows the lower balance within 60 seconds; a spend at
the till larger than the platform's balance leaves the balance at zero, an absorbed amount on the
discrepancy list and a line on the liability report; a deliberately corrupted till balance appears in
the nightly report as `TILL_HIGHER` or `TILL_LOWER` with both numbers and is not changed until a person
resolves it. A vendor nobody has assessed shows `UNASSESSED` in the matrix and cannot be given the
capability; a tenant that says its till keeps a balance, with no capable binding, is told on the
loyalty page that the two can differ.

## References

- ADR 0007, ADR 0011 (capability model, the ceiling and probe stores, `CUSTOMER_UPSERT`), ADR 0012
  (reconciliation), ADR 0015 (no auto-merge), ADR 0025, ADR 0026 (bindings and mappings), ADR 0027,
  ADR 0029, ADR 0030, ADR 0031, ADR 0032, ADR 0033, ADR 0039 (absence of a decision is not consent),
  ADR 0046 (the ledger, "an unbounded manual credit is a cash drawer", the rejected conversion-rate
  row, cross-brand scope), ADR 0067 (the system-actor credit precedent), ADR 0139 (`OPERATOR`
  mapping), ADR 0165 (points only; the operator reason-code list)
- `platform/docs/operations-gap-map.md` row `6.3b` and Part B's blocked table
- `platform/docs/frontend-information-architecture.md` row 6.3
- `platform/docs/providers/clopos-api.md`; no note exists for iiko or R-Keeper
- `platform/CLAUDE.md` ("A green test is evidence about the test, not about the code")
- `V0013`, `V0036`, `V0042`, `V0079`; `PosCapability`, `PosAdapter`, `PosProviderCapabilityCatalog`,
  `PosCapabilityMatrixController`, `MappingEntityType`, `PosMappingService`, `CustomerPhoneLookup`,
  `LoyaltyAdjustmentService`, `ReferralGrantPort`, `LoyaltyBalanceChanged`, `PartnerFiscalizationPort`
- `frontend/operations/src/app/features/marketing/loyalty/loyalty-page.html`,
  `frontend/operations/src/app/core/i18n/messages/marketing.en.ts` (`marketing.loyalty.posSync.*`)

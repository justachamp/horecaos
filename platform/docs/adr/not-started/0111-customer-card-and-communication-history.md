# ADR 0111: A guest has one card, and every contact with them is history

- Decision status: Proposed
- Implementation status: Not started — the `customers` module already builds most of the
  foundation this record organizes (identity resolution, contact points, consent,
  blacklist, erasure, favourites — see Context), and the `notifications` module already
  keeps a per-attempt delivery journal for asynchronous channels. Nothing this record
  itself decides exists yet: there is no `customer.leads` table, no `customer.contact_attempts`
  journal for call-centre voice contact, no card-view audit fact distinct from a PII
  reveal, and no read-model that assembles one guest's history across `notifications`,
  `marketing` and `reviews` for the customer card. `grep -a -ril lead` over
  `platform/src/main/java` finds no domain type named `Lead` anywhere in the tree.
- Date proposed: 2026-09-13
- Date decided: —
- Deciders: proposed by Claude and built on the platform owner's instruction of
  2026-09-13 ("write a new ADR for campaign management and CRM (customer card); do a
  similar architecture for these"); Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0004, ADR 0005, ADR 0015, ADR 0020, ADR 0025, ADR 0027, ADR 0029,
  ADR 0030, ADR 0031, ADR 0032, ADR 0043, ADR 0044, ADR 0055, ADR 0064, ADR 0067,
  ADR 0092, ADR 0094
- Supersedes / Superseded by: —
- Open inputs:
  - Whether "card view" audit fires on every staff read of a customer record at all,
    given a compliance record may need to exist unconditionally — see Decision §7;
    until this resolves, this record builds no tenant-disable lever for it (counsel).
  - The exact consent-purpose vocabulary and per-purpose retention periods this
    record's lead and contact-attempt writes must key against — reserved as W03
    (`V0289`–`V0291`, gap map §5 row 10.11) and not yet built (platform owner, counsel).
  - Call-centre queue assignment policy once a real operator roster and per-operator
    load figure exist; this record assigns by branch only because that figure does not
    (platform owner, operations).
  - Whether a B2B catering enquiry is owned by the call centre (this record's default)
    or by a separate sales function once catering volume justifies one (product).
  - The geocoder/map provider ADR 0015 and ADR 0037 still owe, which this record
    inherits for any address an operator captures during lead intake (platform owner).
  - Sequencing against ADR 0055's greenfield launch order (storefront → operations →
    payments → onboarding): a lead pipeline and call-centre queue are mature-CRM
    features this record does not itself rank against the pilot's build order
    (platform owner).

## Context

**There is no unified customer/lead card in HorecaOS; there is a well-built
`customers` module and a scattering of adjacent facts.** `customer.customer_accounts`,
`principal_links`, `brand_profiles`, `contact_points`, `addresses`, `consent_decisions`,
`blacklist_entries`, `erasure_requests` and `favourites` all live in Postgres schema
`customer` (`platform/src/main/resources/db/migration/V0002__add_customer_schema.sql:1-4`,
`V0017__create_customers.sql:14-244`), resolved by `CustomerIdentityService` on
`(issuer, subject)` — never on phone or email, which ADR 0015 forbids as an automatic
match key (`CustomerIdentityService.java:26-38,100-125`). Consent is append-only,
INSERT/SELECT-only at the grant level, and absence is never treated as consent
(`ConsentService.java:16-27,162-173`). None of this is redecided here.

**What the codebase does not have is a lead.** A guest who has phoned in, filled a
storefront form, messaged the Telegram bot, booked a reservation, asked for a callback,
or placed a first order through an aggregator, and who has not yet become a
`customer.customer_accounts` row with an order behind it, has no representation at all.
The nearest built analogue is "Drafts & abandoned carts" (IA §1.4, Tier 2, an
implicit funnel-diagnosis screen, not a lead entity) and the call-centre screen's
operator presence states — `ONLINE`/`PAUSED`/`WRAP_UP`/`OFFLINE` — which manage an
operator's own availability for an incoming call's screen-pop, not a queue of
outstanding leads (`frontend/operations/src/app/features/orders/call-centre-page.ts:15,20-33`,
ADR 0064). ADR 0064 also refused a softphone/telephony integration, so there is no
provider webhook this record could consume even if a lead queue existed.

**Consent purpose is free text today, and it is already inconsistent.** There is no
consent-type registry: `purpose` on `customer.consent_decisions` is a bare
`varchar(64)`, the operations console lets an operator type anything, and three
different literal strings are already in live use — import writes `MARKETING`,
campaigns write `MARKETING_PROMOTIONS`, and the customer-detail screen sends a
snapshot-purpose string — which is precisely why `MarketingEligibility` refuses
consent it should grant (`platform/docs/operations-gap-map.md` rows 5.2b, 5.3). A
registry is planned as wave W03 with Flyway numbers `V0289`–`V0291` reserved and not
yet created; the migration directory's actual head is `V0222`
(`platform/docs/operations-gap-map.md:688`, `2037-2046`). This record depends on W03
rather than re-deciding it.

**The communication history that exists is real but split across two modules with no
seam between them.** `notifications.NotificationDispatch` carries an `attemptId`
exactly as a contact-attempt journal would
(`platform/src/main/java/uz/horecaos/platform/notifications/api/NotificationDispatch.java:35-46`),
and `DispatchOutcome.Status.UNCERTAIN` is the module's answer to out-of-order and
ambiguous provider callbacks — an uncertain send is reconciled under its idempotency
key, never silently retried
(`platform/src/main/java/uz/horecaos/platform/notifications/api/DispatchOutcome.java:9-16,20-33`).
That journal covers SMS, push and Telegram. It has no counterpart for a phone call an
operator makes or receives, and the customer-detail screen has no single place that
reads across `notifications`, `marketing` campaign receipts and `reviews` for one
guest (IA §5.2 lists order history, cashback ledger, promo redemptions and reviews
left as separate reads, several of them gap-map rows still `NOT BUILT`:
5.2g promo redemptions, 5.2h reviews left — `platform/docs/operations-gap-map.md`
rows 5.2g, 5.2h).

**Module ownership today**: `customers` owns identity, contact, consent, blacklist,
erasure and favourites; `notifications` owns dispatch and delivery-attempt evidence
(ADR 0020); `marketing` owns audiences, campaigns and their recipient receipts
(ADR 0044); `reviews` owns `order_reviews` behind a narrow `CustomerReviewPort` SPI
that lets `integration`'s Telegram handler submit a rating without crossing into
`reviews.application`
(`platform/src/main/java/uz/horecaos/platform/reviews/api/CustomerReviewPort.java`);
`reporting` owns the ADR 0043 fact tables that are the platform's only analytical
mart — there is no ClickHouse and none is planned
(`platform/docs/frontend-information-architecture.md:391`).

## Decision

**One customer card, assembled by read-through, never re-mastered.** The `customers`
module remains the identity and consent authority. It gains two new tables of its
own — a lead and a voice-channel contact-attempt journal — and the customer-detail
read model composes the rest live from `notifications`, `marketing` and `reviews`
through narrow ports, the same shape `CustomerReviewPort` already establishes. No
module keeps a private copy of another module's data.

**1. Identification and search stays exactly as ADR 0015 decided it, extended with
two more identifiers.** Phone (via `contact_points.normalized_hash`), Telegram id
(via the widened `integration.telegram_bindings`, ADR 0058/0063), loyalty reference
(`brand_profiles.loyalty_reference`) and a storefront account (`principal_links`) are
all lookup paths onto the same `customer_account_id`; none of them is an identity key
on its own. A phone match surfaced by search is a hint an operator confirms through
the existing merge endpoint (`CustomerController` `POST /{accountId}/merge`); this
record adds no new merge path and no automatic merge, per ADR 0015's permanent
prohibition.

**2. The customer card is a read-through composition, not a second master.** It reads
`customers` for identity/contact/consent/blacklist, `ordering`/`payments` for order
and payment history, `loyalty` for balance and ledger, `marketing` for promo
redemptions and campaign receipts (through a new narrow port,
`marketing.api.CustomerEngagementHistoryPort`, mirroring `CustomerReviewPort`), and
`reviews` for reviews left (through the existing `CustomerReviewPort`, extended with
a `history(customerAccountId)` read method). None of these facts are copied into
`customers` tables. `CustomerEngagementHistoryPort`'s contract is defined here at the
narrowest shape this record needs (promo redemptions, campaign receipts); ADR 0112
grows the same port's contract to add scenario step decisions and shown offers once
scenarios exist, rather than opening a second port.

**3. Consents, contacts and restrictions reuse ADR 0015's model and wait on W03's
registry for the purpose vocabulary.** This record adds no new purpose literal. Every
new write path it introduces (lead intake, call-centre contact attempts) either needs
no consent decision at all — because a guest-initiated callback is a
`TRANSACTIONAL_REQUIRED`-classed reply under ADR 0020, the same way an order
confirmation needs none — or defers to whichever purpose key W03 designates once it
ships. The blacklist stays `customers.blacklist_entries`, unchanged
(`CustomerBlacklistService.java:24-45`). The bank reference's per-product ban has no
restaurant analogue and is not adapted: HorecaOS has no concept of banning one
customer from one menu item, and the blacklist's existing scope (tenant-wide,
enforced at sign-in and at checkout) is the restriction this platform has chosen to
support.

**4. Lead intake becomes a first-class `customers.leads` row — the storefront and the
Telegram bot ARE the intake, there is no separate Lead Service.** The bank's channel
fan-in (site, bot, mobile bank) each hitting a Lead Service before a CRM ever sees the
contact has no counterpart here: HorecaOS's storefront and Telegram bot already create
the customer account directly on first sign-in
(`CustomerTelegramSignInService.java:14-16`), and a lead is only needed for contacts
that are **not yet** an order, a reservation or a signed-in account. A lead is created
for: a storefront/site inquiry form, a Telegram bot message from an unlinked chat, a
reservation request that named no existing customer, a callback request, an
aggregator's first order arriving with only a masked identity, a B2B catering
enquiry, and — a forward-compatible seventh source — a campaign scenario's
`CALL_CENTRE` step (ADR 0112): `EnqueueCallTaskCommand` is consumed here under the
ADR 0005 inbox and produces exactly one `customer.leads` row, source
`CAMPAIGN_SCENARIO`, carrying a bare `origin_campaign_id`/`origin_step_sequence`
reference and nothing else different — the same queue, the same capabilities, the
same audit, so `marketing` never keeps a parallel call queue of its own. Status
vocabulary, adapted from the bank's six states down to what a restaurant's call
centre actually does with a lead:

```text
NEW -> CONTACTED -> CALLBACK_SCHEDULED -> CONVERTED
NEW | CONTACTED | CALLBACK_SCHEDULED -> DECLINED | LOST
```

The bank's `Application` and `Sale` states, and its lead→application→contract chain,
have no restaurant equivalent — there is no application and no contract. `CONVERTED`
points at whichever terminal fact actually happened: `converted_order_id` or
`converted_reservation_id`, exactly one set. A catering enquiry converts the same way,
against an order id once quoted and accepted; this record does not build a quoting
workflow for catering, only the lead shape that would hold one.

**5. The call centre's callback queue replaces the bank's lead queue, and assignment
is by branch, not by operator load.** ADR 0064 built operator presence
(`ONLINE`/`PAUSED`/`WRAP_UP`/`OFFLINE`) and a screen-pop for an *inbound* call already
in progress; it did not build a queue of outstanding leads waiting for an *outbound*
callback, and there is no per-operator load figure to round-robin against. This
record's queue is the plain list of `NEW` leads for a brand, and assignment sets
`assigned_location_id` — a branch, not an operator — because a branch can staff its
own outbound callbacks with whoever is on shift, and inventing a synthetic
per-operator load metric on top of a presence system built for inbound triage would
be a number nobody could defend. **This is the explicit adaptation the bank's
round-robin/by-load assignment does not survive**: revisit it once a real roster
system gives an operator a load figure worth routing against (see Open inputs).

**6. Hand-off to a branch is a field, not a workflow.** The bank's lead→application
hand-off requires explicit acceptance of responsibility and an application number.
HorecaOS's hand-off is `assigned_location_id` plus, on conversion, the order or
reservation id the branch actually fulfilled — there is no separate acceptance step
because a branch does not decline an assigned lead the way a sales unit might decline
an unqualified one; if a branch cannot serve the lead (out of catering capacity, wrong
cuisine), the lead moves to `DECLINED` with a reason, not to a different branch.

**7. Access and audit reuse ADR 0025's capability model and add a card-view audit
fact.** Two new capabilities, `customer.lead.read` and `customer.lead.manage`, gate
the lead queue and its transitions the same way `customer.read`/`customer.manage`
already gate the account. **New**: every full customer-card open by staff — not only
a PII reveal — writes one `AuditClass.BUSINESS` fact (`customer.card.viewed`, actor,
customer_account_id, purpose) in the same transaction as the read. Today only the
decrypt paths are audited (`CustomerProfileService` reveal doc,
`CustomerListQueryService.exportFiltered`); a plain card open leaves no trace at all,
which is a real gap relative to the bank reference's explicit "separate rights for
viewing a card; audit of card views." This audit fact carries no personal data itself
— an id and a purpose, per ADR 0029's rule that nothing protected reaches a log or an
event. **This record builds no tenant-disable toggle for it.** Whether a
compliance-relevant audit record should ever be switchable off by a tenant is exactly
the question Open Input #1 puts to counsel; presupposing the answer by shipping an
escape hatch now would risk building around a record that turns out to be legally
mandatory. Card-view auditing is therefore unconditional in this rollout — every
staff card open writes the fact, with no configuration key to suppress it — until
that input resolves one way or the other.

**8. Communication history splits at the channel boundary that already exists.**
Asynchronous channel history (SMS/push/Telegram) stays exactly where it is:
`notifications.NotificationDispatch`/`DispatchOutcome`, unchanged, read by the card
through a new narrow port (`notifications.api.CustomerCommunicationHistoryPort`)
rather than a second table. Voice contact — an operator's outbound or inbound call —
has no notification-module counterpart because ADR 0064 refused telephony
integration, so it gets a new, `customers`-owned, append-only journal:
`customer.contact_attempts`. Both are attempts; both carry a blocking reason when
refused; neither is mastered twice.

**9. Marketing touches, business-event intake and history export to reporting are
explicitly not decided here.** A standalone "touch" entity (touch id, UTM, external
ad id) does not exist and is not built by this record — it is ADR 0044/ADR 0112's
`attribution_links`, gap row `6.6a`, scheduled under wave T18. **Business event
intake** — the bank reference's CRM triggers firing off applications, product
changes and transactions — is declined for the same reason: HorecaOS's restaurant
equivalent is automations (birthday, cashback-balance change, late-order apology,
inactivity), gap-map row `6.5`, entirely `NOT BUILT` and out of scope for both this
record and ADR 0112. A future automations record decides how a business event
starts a trigger; this record only stores what already happened, never what should
happen next. Export to an analytical mart reuses the existing `reporting` fact
tables; this record adds no new fact table. If a `reporting.fact_contact_attempt` is
wanted later, that is a reporting-module decision citing ADR 0043, not this one.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| A separate lead-service module, mirroring the bank's Lead Service | The storefront and Telegram bot already create the customer account directly (`CustomerTelegramSignInService`); a separate service would duplicate identity resolution for the minority of contacts that are not yet an account, and give `customers` a second identity path to keep consistent with the first | A tenant asks for lead intake from a channel HorecaOS does not operate directly (a third-party ad platform's own lead-gen form), which would need its own ingestion adapter, not a new module |
| Round-robin or by-load lead assignment, as the bank reference specifies | There is no per-operator load figure anywhere in the codebase — ADR 0064's presence states track availability for inbound screen-pop, not outstanding work — and a synthetic load number invented for this record would be unverifiable and untrusted from day one | A real operator roster and shift system exists and can report a defensible load figure per operator |
| Fold `customer.leads` into `marketing` instead of `customers` | A lead is an identity-adjacent fact (a not-yet-customer), not a marketing fact; putting it in `marketing` would mean the module that ADR 0044 explicitly forbids from holding "a private copy of a customer" now holds the only copy of a pre-customer | Never, unless leads become purely a marketing-generated artifact (e.g. only ad-campaign-sourced), which contradicts this record's own sources (reservation, callback, aggregator) |
| A single `communication_history` table joining notifications, marketing and voice contacts | Cheaper to query, but it duplicates `NotificationDispatch`/`DispatchOutcome` and `marketing.campaign_recipients`, both already the source of truth for their channels, and a duplicated row set drifts the first time either module's schema changes | A read-heavy customer-360 reporting need proves the read-through joins too slow in production, at which point a reporting-owned denormalised fact (not a `customers`-owned copy) is the right answer |
| Card-view audit only on PII-reveal fields, as built today | Matches the bank reference's weaker reading and costs nothing new, but leaves "who looked at this customer's record" unanswerable for a card view that reveals no decrypted field — exactly the gap ADR 0092's control-plane PII overview cannot currently close from the tenant side | Counsel confirms plain-view auditing is not required (see Open inputs); if so, this decision narrows rather than reverses |
| A tenant-disable toggle for card-view auditing, shipped now with a safe default | Presupposes counsel's answer to Open Input #1 before it exists; a compliance record that turns out to be legally mandatory should never have shipped with an off switch, even a defaulted-on one | Counsel confirms plain-view auditing is not legally required in any jurisdiction this platform operates in |
| Let a lead be created without any brand scope (tenant-wide) | A lead always arrives through one brand's storefront, bot or phone line, and a tenant-wide lead would need its own resolution rule for `TENANT_SHARED` vs `BRAND_ISOLATED` tenants that ADR 0015 does not define for leads | A tenant asks for a shared lead pool across its brands, which is a new identity-policy question for ADR 0015, not this record |

## Consequences

### Positive

- A guest who has not ordered yet is finally representable — a phoned-in catering
  enquiry, a Telegram message from an unlinked chat, or an aggregator's first order
  all land in one place instead of nowhere.
- The customer card gains a real audit trail for plain views, closing a gap the
  bank reference's "separate rights for viewing a card; audit of card views"
  principle names explicitly and ADR 0092's control-plane overview cannot answer
  from inside a tenant.
- No new master is created: order history, loyalty balance, promo redemptions and
  reviews all stay owned exactly where they already are, read through narrow ports
  that mirror `CustomerReviewPort`'s already-proven shape.
- The call-centre's callback queue is buildable today, on top of ADR 0064's existing
  presence states, without waiting for a roster/load system that does not exist.

### Negative

- Every full customer-card open by staff now writes an audit fact. On a busy call
  centre this is a meaningful new write volume against `AuditFact`, and its
  retention (ADR 0027 `BUSINESS` class) is an operational cost, not a one-time one.
- `customer.contact_attempts` is a second attempt journal beside
  `NotificationDispatch`, and a card assembling "everything that happened with this
  guest" must query two tables under two different modules and merge them by time —
  a query the bank reference's single `История коммуникаций` module does not need to
  perform, because it never split the journal at the channel boundary in the first
  place.
- Branch-based lead assignment is a real reduction in reach relative to
  operator-load routing: a branch with one busy operator and three idle ones gets
  callbacks queued behind the busy one until someone manually reassigns, because
  there is no load-aware router yet.
- A lead created for a B2B catering enquiry has no quoting or proposal workflow
  behind it — this record gives it a shape to sit in, not a sales process, so an
  operator still tracks the actual negotiation outside the platform.
- Two more capabilities (`customer.lead.read`, `customer.lead.manage`) is two more
  things a tenant's role designer must understand and grant correctly; a
  misconfigured grant either locks operators out of the callback queue or hands the
  queue to a role that should never have seen a guest's phone number.
- This record's consent-write paths are deliberately inert on the purpose question
  until W03 ships — meaning a lead-intake feature that does need marketing consent
  (a catering enquiry that also opts into promotions) has nowhere correct to record
  that decision today, and will keep using whichever ad-hoc purpose string is
  already wrong.

### Accepted trade-offs

- The card-view audit fact carries no field-level detail (which section of the card
  was viewed) — only that the card was opened, by whom, for which account. A
  narrower "viewed the cashback ledger specifically" audit is not built, and is not
  needed to answer "who looked at this customer."
- `customer.contact_attempts` is voice-only. It does not attempt to also journal
  in-person conversations at a branch, because there is no digital trace of one to
  journal truthfully — a fabricated row would be worse than no row.
- Lead deduplication is a phone-hash hint surfaced to an operator, never an
  automatic merge — exactly ADR 0015's existing trade-off, inherited rather than
  reopened, which means duplicate leads will occasionally exist and be merged by a
  human.

## Specification

### Module and table ownership (Spring Modulith)

| Table / port | Owning module | Package |
|---|---|---|
| `customer.leads` (new) | `customers` | `customers.domain` / `customers.application` |
| `customer.contact_attempts` (new) | `customers` | `customers.domain` / `customers.application` |
| `customer.customer_accounts`, `contact_points`, `addresses`, `consent_decisions`, `blacklist_entries`, `erasure_requests` (existing, unchanged) | `customers` | `customers.*` |
| `notifications.dispatch_*` (existing, unchanged) | `notifications` | `notifications.*` |
| `notifications.api.CustomerCommunicationHistoryPort` (new SPI) | `notifications` | `notifications.api` |
| `marketing.campaign_recipients` (existing, unchanged) | `marketing` | `marketing.*` |
| `marketing.api.CustomerEngagementHistoryPort` (new SPI, contract grown by ADR 0112) | `marketing` | `marketing.api` |
| `reviews.order_reviews` (existing, unchanged) | `reviews` | `reviews.*` |
| `reviews.api.CustomerReviewPort` (existing, extended with `history(customerAccountId)`) | `reviews` | `reviews.api` |
| `customers.web.CustomerCardAssemblyService` (new, composes the above via ports) | `customers` | `customers.application` |

No table changes hands. `customers` never queries `notifications`, `marketing` or
`reviews` tables directly — only their published ports, the same boundary
`CustomerReviewPort` already enforces and `make arch` checks structurally.

### Data model

```text
customer.leads
  id, tenant_id, brand_id                                -- tenant + brand scoped
  status (NEW | CONTACTED | CALLBACK_SCHEDULED | CONVERTED | DECLINED | LOST)
  source (STOREFRONT | TELEGRAM_BOT | SITE | RESERVATION | CALLBACK_REQUEST
          | AGGREGATOR_FIRST_ORDER | B2B_CATERING_ENQUIRY | CAMPAIGN_SCENARIO)
  phone_normalized_hash                                  -- ADR 0029 keyed hash, dedup hint only
  phone_encrypted, display_name null                      -- ADR 0029 envelope encryption
  customer_account_id null                                -- set once identified/merged
  notes_encrypted null                                     -- PERSONAL, free text an operator wrote
  assigned_location_id null                                -- the branch hand-off
  origin_campaign_id null, origin_step_sequence null       -- set only when source =
                                                            -- CAMPAIGN_SCENARIO; bare id
                                                            -- reference, no FK across the
                                                            -- module boundary, no marketing
                                                            -- table duplicates this row
  assigned_at null, converted_order_id null, converted_reservation_id null
  closed_reason null, created_by, version, created_at, updated_at
  check (status <> 'CONVERTED' or converted_order_id is not null
         or converted_reservation_id is not null)

customer.contact_attempts                                 -- append-only, INSERT/SELECT-only grant
  id, tenant_id, brand_id
  lead_id null, customer_account_id null                  -- at least one is set
  direction (INBOUND | OUTBOUND), channel (PHONE)
  attempt_id                                               -- same shape as notifications.attemptId,
                                                            -- cross-referenced when one logical
                                                            -- contact also produced a notification
  outcome (CONNECTED | NO_ANSWER | DECLINED | VOICEMAIL | BLOCKED)
  blocking_reason null (BLACKLISTED | OUTSIDE_QUIET_HOURS | NO_CONSENT | WRONG_NUMBER)
  operator_actor_id, occurred_at
  next_action null, next_action_at null
```

Both tables carry a non-null `tenant_id`; both carry `brand_id` because a lead or a
call is always tied to one brand's storefront, bot or phone line, matching the
existing `brand_profiles` scoping rather than inventing a tenant-wide shape ADR 0015
does not define for pre-customer contacts. `customer.contact_attempts` is append-only
by grant, exactly like `customer.consent_decisions` — the application role gets
`INSERT`/`SELECT` and nothing else, so a call outcome cannot be quietly rewritten
after the fact.

### Events and audit (ADR 0004, ADR 0005, ADR 0027, ADR 0029, ADR 0032)

New outbox events, catalogued per ADR 0032, one producing module each, no personal
data in any payload:

```text
customers.events / LeadRegistered        { leadId, tenantId, brandId, source }
customers.events / LeadStatusChanged     { leadId, fromStatus, toStatus }
customers.events / LeadAssignedToLocation{ leadId, locationId }
customers.events / LeadConverted         { leadId, orderId null, reservationId null }
```

Written in the same transaction as the row (ADR 0004 outbox), no consumer required
by this record — these exist so a future marketing trigger or reporting fact can
subscribe through ADR 0005's inbox without `customers` knowing who is listening.
One inbound command is consumed here, not produced: ADR 0112's
`marketing.commands / EnqueueCallTaskCommand` arrives under a stable `consumer_name`
through the ADR 0005 inbox and, inside that same idempotent transaction, inserts one
`customer.leads` row (source `CAMPAIGN_SCENARIO`) — `customers` never calls back into
`marketing` to acknowledge it beyond the inbox's own delivery guarantee.
Card-view and lead-transition audit facts follow ADR 0027 exactly: `AuditClass.BUSINESS`,
written in the same transaction as the read or write, `changed` carrying ids and
status codes only. `customer.card.viewed` is new; `customer.lead.status_changed` is
new; both reuse the existing `AuditFact`/`AuditRecorder` infrastructure — no new
audit table.

### Capabilities (ADR 0025)

| Capability | Gates | Scope |
|---|---|---|
| `customer.lead.read` | The callback queue and a single lead's detail | `TENANT` \| `BRAND` \| `LOCATION` (a `LOCATION` grant sees only leads whose `assigned_location_id` matches) |
| `customer.lead.manage` | Creating a lead, transitioning its status, assigning a branch | `TENANT` \| `BRAND` \| `LOCATION`, same downward coverage as ADR 0025 already defines |
| `customer.read` (existing) | The customer card, unchanged | Unchanged — see ADR 0025 |
| `customer.manage` (existing) | Card mutations, unchanged | Unchanged — see ADR 0025 |
| `customer.pii.reveal` (existing) | Any decrypted contact value on the card or a lead, unchanged | Unchanged — see ADR 0025 |

No four-eyes approval is introduced here — a lead transition and a card view are
neither irreversible nor cost-bearing the way a campaign send is, which is where
ADR 0112 reaches for ADR 0027's approval model instead of its audit half.

### Configuration keys (ADR 0030)

- `customer.lead.callback_reminder_minutes` (`TENANT`, default `60`) — how soon a
  `CALLBACK_SCHEDULED` lead surfaces on the queue again if not converted or declined.
- **No `customer.card.view_audit_enabled` key in this rollout.** Per Decision §7,
  card-view auditing ships unconditional; a tenant-tunable disable key is deferred
  until Open Input #1 tells counsel's answer, at which point a follow-on change adds
  it if — and only if — the audit is confirmed not to be legally required.

### What is explicitly not decided or built here

- No external CDP — audiences and history stay inside the platform, per ADR 0044's
  existing prohibition on exporting a segment or a customer list to a third party.
- No ClickHouse or separate analytical mart — `reporting`'s existing fact tables are
  the mart; this record adds none.
- No SAP or third-party marketing cloud integration of any kind.
- No separate lead service — the storefront and the Telegram bot are the intake,
  unchanged from how they already create customer accounts.
- No B2B catering quoting/proposal workflow — only the lead shape to hold one.
- No consent-type registry — that is W03's `V0289`–`V0291`, cited and depended on,
  not rebuilt here.

## Rollout and rollback

This record governs two gap-map waves and is explicitly adjacent to, but does not
govern, three more that the campaign/offer record (ADR 0112) and a future reporting
record own instead.

**Governed by this record:**
- **`P40` — Customer record depth: ledger, promos, reviews, blacklist, erasure**
  (gap map line 1326; rows `5.2d`, `5.2e`, `5.2g`, `5.2h`, `5.2i`, `5/X.1`, gap-map
  lines 274-284). This record's read-through ports (`CustomerEngagementHistoryPort`
  for promo redemptions, the extended `CustomerReviewPort` for reviews left) are the
  mechanism `P40` wires into the customer-detail pane; `P40` itself does not change
  table ownership.
- **`P26` — ImportWizard, customer CSV and the header counters** (gap map line 1347;
  rows `X.13`, `5.1b`, `5.1a`, gap-map lines 267-268, 460). A CSV-imported customer is
  exactly the kind of guest this record's `source` enum does not cover — bulk import
  stays a `customers`-owned operation via the existing `CustomerImportDirectory` and
  is unaffected by the lead/contact-attempt tables this record adds.

**Adjacent, not governed by this record:**
- **`T05`, `T18`** (segments, promo codes, campaigns, loyalty, attribution links) —
  owned by ADR 0112.
- **`P27`** (order and business reports) — an order-report wave with no customer-card
  or lead surface; a future reporting-focused record owns any communication-history
  fact table, per this record's own "not decided" list above.

**Sequence**: add `customer.leads` and `customer.contact_attempts` with their grants
(one migration, additive, no backfill — there is nothing to backfill into a table
that never existed); build the two narrow ports and the card-assembly service before
wiring `P40`'s screen sections to them, so the console change and the module boundary
land together rather than the screen reading tables directly first and the port
being retrofitted after. Card-view auditing ships unconditional from the first
migration (Decision §7); there is no follow-up release gating it. Rollback is
additive-safe: dropping the two new tables loses only leads and voice contact
history created since rollout, and no existing table's shape changes.

**Against the pilot's launch order.** ADR 0055 sequences the greenfield build as
storefront → operations → payments → onboarding. A lead pipeline and a call-centre
callback queue are operations-tier features this record does not itself rank inside
that order — the single pilot tenant's actual guest volume may not justify `P40`/`P26`
ahead of payments-tier work. This record specifies the shape; it defers the "build it
now or later" call to the platform owner as an open input, not a decision made here.

## Implementation checklist

- [ ] `customer.leads` and `customer.contact_attempts` migrations, with grants, under
      the next free Flyway numbers after `V0222`.
- [ ] `LeadService`, `JdbcLeadStore`, lead status transitions enforced as a state
      machine, each transition an ADR 0027 audit fact and an outbox event.
- [ ] `ContactAttemptService` for call-centre-entered voice contact rows, append-only.
- [ ] `notifications.api.CustomerCommunicationHistoryPort` and
      `marketing.api.CustomerEngagementHistoryPort`, each a narrow read SPI.
- [ ] `reviews.api.CustomerReviewPort.history(customerAccountId)`.
- [ ] `customers.application.CustomerCardAssemblyService` composing all of the above.
- [ ] `customer.card.viewed` audit fact written unconditionally on every staff card
      open — no tenant-disable configuration key in this rollout (Open Input #1).
- [ ] `EnqueueCallTaskCommand` ADR 0005 inbox consumer producing a `customer.leads`
      row (source `CAMPAIGN_SCENARIO`) for ADR 0112's scenario call-centre step.
- [ ] Capabilities `customer.lead.read`, `customer.lead.manage` registered in
      `Capability` with `TENANT`/`BRAND`/`LOCATION` scope and granted to the roles
      the operations spec already names for the call centre.
- [ ] Callback queue screen: `NEW` leads for a brand, assignable to a location.
- [ ] Tests: a lead cannot reach `CONVERTED` without an order or reservation id; a
      contact attempt row is never updated after insert; every card view writes
      exactly one audit fact regardless of configuration; cross-tenant and
      cross-brand reads of leads and contact attempts fail.

## Exit criteria

A phoned-in catering enquiry, a Telegram message from an unlinked chat, and a
callback request all produce a `customer.leads` row an operator can find, assign to
a branch, and convert into the order or reservation that resulted — or decline with
a reason. A customer card opened by staff leaves a `customer.card.viewed` audit fact
naming the actor and the account. A voice call an operator made or received is
recorded in `customer.contact_attempts` with an outcome and, when refused, a blocking
reason — and it is never edited after the fact. The customer card shows order
history, loyalty balance, promo redemptions and reviews left without any of those
facts being copied into a `customers`-owned table.

## References

- [ADR 0004](../built/0004-sql-outbox-and-kafka-delivery.md) — the outbox this record's new lead events use
- [ADR 0005](../built/0005-kafka-inbox-and-idempotent-consumers.md) — the inbox that consumes ADR 0112's `EnqueueCallTaskCommand`
- [ADR 0015](../partial/0015-customer-accounts-cross-brand-identity-and-consent.md) — customer identity and consent, unchanged by this record
- [ADR 0020](../partial/0020-notification-preferences-templates-and-delivery.md) — notification classes and the delivery attempt journal this record reads through a new port
- [ADR 0025](../built/0025-fine-grained-authorization-and-capability-model.md) — the capability model two new capabilities join
- [ADR 0027](../built/0027-audit-evidence-and-approval-model.md) — the audit-fact mechanism the new card-view fact reuses
- [ADR 0029](../partial/0029-pii-protection-envelope-encryption-and-key-rotation.md) — envelope encryption for lead phone/name/notes
- [ADR 0030](../built/0030-configuration-and-policy-resolution.md) — resolution for the callback-reminder config key
- [ADR 0031](../built/0031-http-api-conventions.md) — the HTTP conventions the callback-queue and lead endpoints follow
- [ADR 0032](../built/0032-event-contract-governance-and-topic-policy.md) — the event catalogue the new lead events join
- [ADR 0043](../partial/0043-reporting-analytics-and-the-metric-layer.md) — the fact-table mart this record's history export defers to
- [ADR 0044](../partial/0044-marketing-campaigns-audiences-and-engagement.md) — the marketing module boundary this record's card read never crosses
- [ADR 0055](../meta/0055-greenfield-launch-scope.md) — the pilot's build order this record's rollout is sequenced against
- [ADR 0064](../partial/0064-voice-channels-and-the-operator-presence-model.md) — operator presence, extended (not replaced) by the callback queue
- [ADR 0067](../partial/0067-referral-program-rewards-through-the-loyalty-ledger.md) — referral edges, adjacent to but not part of this record's lead model
- [ADR 0092](../built/0092-the-control-plane-shows-how-personal-data-is-treated.md) — the control-plane PII overview this record's card-view audit feeds
- [ADR 0094](../built/0094-support-finds-a-customer-by-phone-and-sees-credentials-due.md) — cross-tenant lookup and its audit pattern, mirrored by the card-view fact
- [ADR 0112](../not-started/0112-campaigns-offers-and-contact-policy.md) — the scenario `CALL_CENTRE` step whose call task lands in this record's lead queue
- `platform/docs/operations-gap-map.md` lines 267-268, 274-284, 460 (§5 rows `5.1a`,
  `5.1b`, `5.2d`, `5.2e`, `5.2g`, `5.2h`, `5.2i`, `5/X.1`, `X.13`); lines 740-741, 1326,
  1347 (waves `P40`, `P26`)
- `/private/tmp/claude-501/-Users-admin-Developer-HorecaOS/06b46f36-9f32-4bff-8658-edc24c462f82/scratchpad/crm-reference-architecture.md` — the bank reference this record adapts

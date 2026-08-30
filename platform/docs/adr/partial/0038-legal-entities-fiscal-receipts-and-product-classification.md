# ADR 0038: Legal entities, fiscal receipts, and fiscal product classification

- Decision status: Accepted
- Implementation status: Partial — built: `catalog.fiscal_classifications`, the
  ИКПУ reference `catalog.mxik_reference`, the delivery-fee node, the
  whole-percent `ck_tax_rate_whole_percent` constraint on
  `pricing.tax_profiles.rate_basis_points`, and the validator's coverage
  reporting (V0028), with per-node classification endpoints on
  `CatalogAuthoringController`; the Click and Payme fiscal adapters, the
  per-legal-entity merchant binding and the cash `NOT_APPLICABLE` record (V0027);
  the document lifecycle in a `fiscal` module of its own — the `BLOCKED`
  state, `FiscalReportingSweeper` on a timer, the business-date backstop, the
  one-sale-per-settled-tender constraint, the blocked worklist, the audited retry
  and unblock commands and the coverage report (V0039); and the legal-entity
  registry itself, new in V0053 — `tenant.legal_entities` and
  `tenant.location_fiscal_assignments` with the gist exclusion that forbids two
  taxpayers on one branch-day, plus the two foreign keys from
  `payments.merchant_bindings` and `fiscal.fiscal_documents` this checklist called
  a one-line follow-up; `LegalEntityService` registers, activates, suspends and
  archives an entity and assigns a location close-then-open in one transaction;
  `JdbcLegalEntityStore` implements `tenancy.api.LegalEntityDirectory`; and
  `FiscalObligationService` with `FiscalObligationSweeper`'s two `@Scheduled`
  passes opens a `SALE` obligation for every completed order, stamps the resolved
  seller on it, and blocks with a reason where no entity covers that branch on
  that date (`LegalEntityAssignmentTests`, `FiscalObligationTests`). Also built now: an HTTP
  surface over the registry — `LegalEntityController` at
  `/api/v1/control-plane/tenants/{tenantId}/legal-entities` registers, lists,
  gets and activates an entity and reads/writes a location's fiscal assignment,
  gated by new `legal-entity.read`/`legal-entity.manage` capabilities
  (`.manage` held by `tenant-owner` alone); and `payments`' own
  `PaymentLegalEntityResolver` — `TenancyLegalEntityResolver`, delegating to
  `tenancy.api.LegalEntityDirectory` — so `canAcceptPayment` resolves a real
  seller and CLICK/PAYME can be offered wherever a location has an assignment.
  Not built: ADR 0018 tax-profile
  resolution and the quote context hash, which still resolve no entity;
  `payments.payment_method_entity_bindings`, the fiscal-responsibility validation
  at method activation, and the foreign key from ADR 0036's
  `channel_payment_methods` — note that `payments.payment_methods` itself now
  exists, created by ADR 0046 in V0042, but `JdbcSettlementStore.registerMethod`
  has no production caller, so no tenant has a registry row; bulk classification
  assignment, and the three `CatalogValidator` rules are still warnings rather
  than publication errors; the payments half of the `PARTNER` path — nothing
  implements `fiscal.api.PartnerFiscalizationPort`, so `FiscalPortConfiguration`'s
  stand-in answers `NOT_WIRED`, every claimed submission is released unsent, and
  no receipt reaches Click or Payme; the `business_date` and `tender_id` snapshot
  at acceptance — `JdbcFiscalLifecycleStore`'s insert writes neither and the
  sweeper still derives the date from the branch timezone; `TERMINAL` issuance,
  marked goods and age gating; the correction and void commands; the
  `FiscalDocument*` ADR 0032 event contracts; and the `payments.fiscal_documents`
  compatibility view, which `JdbcFiscalDocumentStore` and `PaymeMerchantApi` still
  read through. See [What is built, and what a reader should not
  assume](#what-is-built-and-what-a-reader-should-not-assume).
- Date proposed: 2026-08-21
- Date decided: 2026-08-22
- Deciders: Ayubkhon Abbosov (platform architecture; cash fiscalization, 2026-08-22), finance (VAT registration, tax treatment, evidence retention), legal (fiscal agency per settlement path)
- Depends on: ADR 0002, ADR 0011, ADR 0013, ADR 0016, ADR 0018, ADR 0026, ADR 0029, ADR 0030, ADR 0034, ADR 0036, ADR 0046
- Supersedes / Superseded by: Supersedes the "Fiscal receipts and settlement" section of ADR 0013 and its closed input; ADR 0013 records the pointer back to this ADR
- Closed inputs: **The restaurant's legal entity is the seller and the legal principal; Qoida is an agent and never the issuer** (business/legal, 2026-08-22). **Provider merchant accounts are the restaurant's own, one per legal entity** (forced by both provider contracts, 2026-08-22 — neither provider accepts a fiscal identity as a per-request field). **A cash order gets no provider fiscal receipt, and that fact is recorded as an explicit state rather than as an absence** (Ayubkhon Abbosov, 2026-08-22).
- Open inputs: Whose TIN or PINFL Click's per-line `CommissionInfo` carries, and whether one Click service may name a different TIN per line — a written question to Click, paired with a legal determination of whether the Qoida–restaurant agreement is a договор комиссии in the sense the tax rules use (Click, legal); source and refresh cadence of the ИКПУ/MXIK reference list and who signs off a tenant's assignments (finance); which product classes are age-restricted, at what age, and whether any of them may be delivered rather than collected (legal, product); the retention period for fiscal evidence per legal entity, as an ADR 0030 policy value (finance, legal); four provider-discovery answers that set constants and not structure — Click `submit_items` idempotency, the Payme `SetFiscalData` `status_code` enumeration and whether a delivery deadline exists, and how a fractional quantity is expressed in Click's `uint64` `Amount` (Click, Payme)

**None of the open inputs is structural**, which is why this ADR is `Accepted`
rather than `Proposed`. The `CommissionInfo` question is the largest of them and
is examined in [Whose merchant account issues the
receipt](#whose-merchant-account-issues-the-receipt): per-legal-entity merchant
accounts are correct under either answer, because Payme has no per-line fiscal
identity field of any kind and its cashbox *is* the taxpayer. A favourable Click
answer would permit a later commercial consolidation onto fewer Click services.
It would not remove the legal-entity dimension from the binding.

## Context

ADR 0013 closed an open input with one sentence: fiscalization is performed by the
payment partners, Click and Payme, so Qoida retains fiscal evidence and never
issues a fiscal document. That is correct for an online card payment and wrong for
everything else. Cash on delivery, a courier's card terminal, a self-service
kiosk, and a dine-in bill settled at the POS each create a receipt obligation with
no payment partner in the transaction. Those paths are most of this market's
volume, so the assumption that made ADR 0013 small also made it inapplicable to
most orders. This ADR therefore supersedes ADR 0013's "Fiscal receipts and
settlement" section and the closed input beneath it: `payments.fiscal_receipts`
does not survive as a partner-populated side table, its content becoming
`fiscal.fiscal_documents` below, and ADR 0013 now carries a pointer here so that
a reader of that file alone is not left holding the withdrawn assumption.

Two structural facts arrive with the correction, and both are cheaper now than
after the pilot.

**The fiscal taxpayer is per branch.** Delever holds the fiscalization INN at
branch granularity, lets a branch INN override the company INN, and describes
per-branch merchant profiles for operators splitting payments across legal
entities. One tenant can therefore contain several taxpayers. ADR 0002 models
`Tenant -> Brand -> Location` with no legal entity anywhere: `tenant.tenants`
carries a `legal_name` column and nothing else, `tenant.locations` carries no tax
identity at all. Brand cannot carry it either — a company and a trade name are
orthogonal, one company routinely runs three brands, and one brand is routinely
split across two companies for tax or franchise reasons.

**Fiscal classification is not a label.** ИКПУ/MXIK and the package code are
required on every priceable node — products, variants, modifier options, and the
delivery fee line itself — and aggregators reject menus that lack them. ADR 0016
records this as an open input ("SPIC/unit/VAT classification semantics") and
deliberately does not copy the legacy fields; that is a blocker dressed as a
deferral. Unit-marked goods go further: one identifier per physical unit must
reach the receipt, which changes how an order line is constructed rather than how
it is displayed.

The built code confirms the gap. `catalog.products` has a nullable
`tax_category_code` that nothing reads, and `CatalogValidator` runs no fiscal rule.
The `payments` module is an empty package, so ADR 0013's `payments.fiscal_receipts`
has never been built and can be relocated at no migration cost.

### What the provider contracts settled, and what they broke

Between the first draft of this ADR and this revision, both provider contracts
were read in full and written up as
[`docs/providers/click-merchant-api.md`](../../providers/click-merchant-api.md),
[`docs/providers/payme-merchant-api.md`](../../providers/payme-merchant-api.md) and
[`docs/providers/fiscalization-via-payment-providers.md`](../../providers/fiscalization-via-payment-providers.md).
Those notes own the wire protocols; this ADR cites them and does not restate them.
Four things they establish change the shape of this decision rather than confirm
it.

1. **The seller's identity is not a request field on either provider.** Payme
   derives it from the cashbox — `receipts.create` returns a `merchant` object
   with `organization` populated from the cashbox and nothing in the request
   naming a taxpayer. Click derives it from `service_id` plus
   `merchant_user_id`/`secret_key`, and no seller TIN appears in any fiscal
   request body. A shared Qoida account would therefore issue every receipt in
   Qoida's name, which is the opposite of the decision that the restaurant is the
   principal. The legacy corroborates: `fin_agents` is
   `UNIQUE (payment_method_id, vendor_id)` — one provider agent per vendor, by
   construction. A tenant-scoped, singular provider binding on a payment method
   is consequently wrong, and the version of `payments.payment_methods` in the
   first draft of this ADR carried exactly that error.
2. **A cash order cannot be fiscalized by either provider, at all.** Click's
   `submit_items` requires a CLICK `payment_id` that a cash order does not have;
   Payme's fiscal data attaches to a Payme receipt that a cash order does not
   have. Click's `received_cash` field is a tender split *inside a Click payment*
   and is not a cash path; reading it as one produces a system that appears to
   fiscalize cash and does not.
3. **"Exactly one fiscal document per order" is true as an obligation and false
   as a row count.** Payme's `SetFiscalData` arrives as `type: "PERFORM"` and
   later as `type: "CANCEL"`, and the docs state that the tax authority forms two
   separate fiscal receipts. ADR 0046 split tender produces more still.
4. **V0021's two interim columns are roughly a third of the required per-line
   field list**, and Payme has no field for a marking code at all.

## Decision

- **The restaurant's legal entity is the seller and the legal principal. Qoida is
  an agent and never the issuer.** Each brand's legal entity issues its own fiscal
  receipt under its own INN.
- **A legal entity is a first-class tenant-owned object, and every location is
  assigned to exactly one with effective dating.** The assignment carries the tax
  identity, not the location, so a receipt issued before a re-registration still
  resolves to the INN in force on its business date.
- **Fiscalization is an obligation of the order, not of the payment**, owned by a
  new `fiscal` Spring Modulith module rather than by `payments`. Every accepted
  order's obligation resolves exactly once — to issued, evidenced as not required,
  evidenced as not applicable, or visibly blocked. There is no path where an order
  is delivered and nobody knows whether a receipt exists. **This is a statement
  about the obligation and not about row count**; see [One obligation, several
  documents](#one-obligation-several-documents).
- **Each payment method declares who discharges that obligation** — `PARTNER`,
  `TERMINAL`, `MARKETPLACE`, or `OPERATOR` — validated against the ADR 0026
  installation behind it at activation, not at receipt time. The method itself is
  a row in a tenant-scoped registry, `payments.payment_methods`, **owned by this
  ADR**: there is one registry, and everything that names a payment method points
  at it.
- **A `PARTNER` method binds to a provider account per legal entity, never per
  tenant.** Each legal entity holds its own Click service and its own Payme
  cashbox, registered to its own INN, with credentials as ADR 0026 secret
  references. Where an entity has no active binding for a method, that method is
  not offered on any channel serving that entity's locations.
- **Click and Payme are both in this build, together.** One provider first would
  shape the port like that provider; the two disagree on units, on the meaning of
  `price`, on marking support and on the direction fiscal data travels, and a port
  that satisfies both is a port.
- **Telegram is designed for and not built.** The payment port and ADR 0036's
  channel model must accept a Telegram provider token and an invoice payload
  without redesign. No bot is built here.
- **Cash and courier-terminal payments require a fiscal-capable terminal bound to
  the location.** Where none is active those methods are not offered on any
  channel serving that location. A serviceability precondition, not a warning.
- **A cash tender records `NOT_APPLICABLE` for provider fiscalization, with a
  reason, and never a null.** Decided by Ayubkhon Abbosov on 2026-08-22. See
  [Cash](#cash-an-explicit-state-not-an-absence).
- **Qoida does not become a fiscal issuer in the pilot.** The `OPERATOR`
  responsibility and the ADR 0026 `FISCAL` provider category are specified here and
  left unimplemented, so adding them later is an adapter plus an enum value.
  Confirmed as the sequencing by Ayubkhon Abbosov on 2026-08-23, against evidence
  that the competitor does implement it: Delever carries a tenant-wide
  fiscalization INN, a per-tenant setting for which payment types it fiscalizes,
  and an operator action that pushes a paid order to the tax service directly when
  the automatic attempt fails. Its orders carry a fiscalization URL whatever the
  channel. So `OPERATOR` is not a hypothetical route kept for symmetry — it is the
  route a comparable product runs in this market, and the pilot ships without it
  knowingly rather than for want of a design.

  This also corrects the record on the cash decision of 2026-08-22. That decision
  was taken on the stated premise that no payment partner can fiscalize a cash
  order and that therefore no route existed. The first half is true and the second
  is not: a direct call to a fiscal operator does not involve a payment partner at
  all. `docs/delever-parity-matrix.md` carried the finding — "Fiscalization is not
  solved by the payment partners… fails for cash on delivery, courier terminals,
  kiosk, and POS-settled dine-in, which is most of this market's volume" — and it
  was not surfaced when the decision was being made. The decision stands; the
  reasoning behind it did not.
- **Fiscal classification attaches to a priceable node using ADR 0018's exact
  vocabulary** — `VARIANT`, `MODIFIER_OPTION`, `FEE` — so the delivery fee is
  classified by the same mechanism as a dish. Missing classification blocks
  publication. **The delivery fee reaches a receipt as an ordinary item line**,
  never through Payme's `shipping` block, which carries no code, no package code
  and no VAT percent.
- **A marked good is fiscalized per unit, and marking constrains the payment
  method as well as the channel.** The order line stays one line but carries an
  ordered set of unit identifiers, and cannot be fiscalized until the captured
  count equals the line quantity. Because Payme's `detail` object has no
  marking-code field, a cart containing a marked node removes Payme from the
  offered payment methods.
- **An unreported fiscal document ages into `BLOCKED` on a deadline this ADR
  owns.** Payme's `SetFiscalData` is inbound, optional to implement, and has no
  merchant-initiated retry, so silence is otherwise indistinguishable from a
  missing receipt. See [The reporting
  sweeper](#the-reporting-sweeper-silence-is-not-evidence).

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Keep ADR 0013's position — partners fiscalize, Qoida only retains evidence, and the table lives in `payments` | Leaves cash on delivery, courier terminals, kiosk, and POS-settled dine-in with no receipt path, and cash is most of this market's volume. The module placement fails the same way: those orders have a receipt obligation and no Qoida payment transaction to hang it on, so `payment_transaction_id` would be null exactly where the obligation is hardest | Never as a complete answer; it survives as the `PARTNER` responsibility for Click and Payme |
| Qoida is the principal, with one Qoida Click service and one Qoida Payme cashbox serving every restaurant | Rejected on the contracts, not on preference. Neither provider takes a seller identity per request, so every receipt would name Qoida as the seller of a meal Qoida did not cook, under Qoida's INN, with Qoida owing the output VAT. It also makes Qoida the merchant of record for chargebacks and the fiscal correspondent for every tenant's audit | Only if Click confirms `CommissionInfo` names the committent per line **and** legal confirms a договор комиссии **and** an equivalent exists on Payme. Two of the three are currently absent, and the third does not exist at all |
| Refuse cash on the new platform until a fiscal path exists | Honest and cheap. Rejected because it removes the payment method the pilot tenant's customers use most, turning a compliance question into a revenue question the business answers by staying on the legacy system | If the pilot tenant proves card-dominant and cash is a few percent of orders, refusing it is cheaper than the terminal precondition |
| Record a cash order's fiscal status as null / no row, and infer "cash" at read time | A null is the same shape as "not yet attempted", which is the exact confusion this ADR exists to end. If the cash decision reverses, the affected orders must be findable by a query rather than by an accountant reading order histories | Never. If provider cash fiscalization ever becomes possible, the state gains a transition, not a meaning |
| Ship Click first and add Payme after the pilot | Cheaper by one adapter and wrong by one port. Click's `Price` is a line total, Payme's is a unit price; Click is som on the payment call and tiyin on the fiscal call, Payme is tiyin throughout; Click pushes fiscal data after capture, Payme receives it before and reports back after. A port designed against Click alone encodes all four as universals | Never. A third provider is a smaller change than the second, precisely because the second was built alongside the first |
| Emit the delivery fee through Payme's `shipping` block | `shipping` accepts a title and a price and nothing else — no ИКПУ, no package code, no VAT percent. The `FEE` classification this ADR spends a validator rule enforcing would never reach a receipt, and the failure is invisible because the payment still succeeds | Never, unless Payme adds the three fields |
| Enforce "one fiscal document" as a unique index on `order_id` | It would overwrite a sale's fiscal evidence with a cancellation payload the first time a Payme order is refunded, destroying the only record that the sale was ever fiscalized. Split tender under ADR 0046 breaks it a second way | Never |
| Integrate a fiscal operator directly, creating the `FISCAL` category ADR 0026 declined | Makes Qoida the issuer of a legal document: operator contracts per legal entity, correction and void obligations, and an outage class in which orders cannot lawfully complete. That is a business Qoida has not decided to enter, and the pilot tenant already owns fiscal-capable equipment | The legal open input returns "Qoida is the fiscal agent", or a tenant needs cash where no fiscal-capable POS exists. The seam is cut: one category value and one adapter |
| Put the tax identity on the brand, or on the tenant only | A tenant-level INN cannot express a branch registered to a different company; a brand-level INN breaks the moment two brands share one company, which is the normal case | Never |
| Widen ADR 0026's `integration.bindings` with a `legal_entity_id` scope instead of adding a payments-side binding table | Legal entity is not a scope in ADR 0026's `brand → location` hierarchy, and adding a third nullable scope column changes the primary-binding uniqueness rule for POS, delivery and notification providers, none of which need it | If a second provider category ever binds per legal entity — a direct `FISCAL` operator would — at which point the scope belongs in ADR 0026 and this table folds into it |
| Store ИКПУ/MXIK as a free-text column on the product, as the legacy schema did, and generate missing codes as Delever does | A wrong or absent code is a tax classification error on a legal document. Free text cannot be validated, bulk-corrected, or reported on for coverage, and it cannot reach a modifier or the delivery fee, both of which appear on receipts. Generating the code moves that risk from the tenant to Qoida and makes it invisible | Never for the code itself. Assistive search over the official list, with the operator selecting and the selection audited, is acceptable |

## Legal entities and fiscal assignment

```text
tenant.legal_entities
  id, tenant_id, code, legal_name, short_name
  tin, vat_registered boolean, vat_certificate_reference null
  tax_profile_id null            -- ADR 0018 profile used when this entity sells
  registered_address, contact_phone, status, version, timestamps
  unique (tenant_id, code), unique (tenant_id, tin)

tenant.location_fiscal_assignments
  id, tenant_id, brand_id, location_id, legal_entity_id
  effective_from, effective_until null
  approved_by, approval_reference          -- ADR 0027 evidence
  version, timestamps
```

An exclusion constraint forbids overlapping ranges for one location: two
overlapping assignments mean two INNs are simultaneously correct and the resolver
picks by row order, which is how one branch issues receipts under two taxpayers in
one evening. A location with no active assignment cannot be activated for a
channel that can produce a receipt obligation. Resolution is by location and
business date, snapshotted onto the order at acceptance with the assignment
version; nothing downstream re-resolves it, because a re-registration must not
rewrite what a delivered order's receipt said.

The business date is snapshotted at acceptance for a second reason the contracts
supply: Click's recovery lookup is
`GET payment/status_by_mti/:service_id/:merchant_trans_id/YYYY-MM-DD`, so
recovering a lost `payment_id` needs the payment's business date. A date inferred
later is a date that can be wrong across midnight, and a wrong date reads as "no
payment found" — the exact condition under which a blind retry double-charges.

**This extends ADR 0018 in three places.** Tax profile resolution gains a
legal-entity step ahead of brand and tenant, because VAT registration belongs to
the company and a tenant with one registered and one unregistered company cannot
be expressed by brand scope. The quote context hash gains `legal_entity_id` and
the assignment version, because without them the same cart at the same location
under a re-registered entity hashes identically and prices differently — the one
thing ADR 0018 promises cannot happen. And **`tax_profiles.rate_basis_points`
must be constrained to multiples of 100**: both providers type the VAT rate as an
integer percent (`VATPercent`, `vat_percent`), so a rate of 12.5 percent is not
expressible on either wire. The adapter rejects such a profile rather than
rounding it; a rounded rate on a fiscal document is a misstatement of tax.
`rate_basis_points` stays the only source of a rate in a quote; a zero-rated or
exempt node names a different ADR 0018 profile rather than carrying a loose
percentage. Two rates in two tables is how a receipt shows 12 percent on a line
the customer paid no tax on.

## Whose merchant account issues the receipt

Neither provider accepts the seller's identity as a per-request field. Payme's
receipt carries a `merchant` object whose `organization` comes from the cashbox
the `X-Auth` credential belongs to — **the cashbox is the taxpayer**. Click's
fiscal calls carry `service_id` and no seller TIN anywhere in the body. So the
merchant account *is* the fiscal identity, and one account cannot serve several
sellers.

```text
payments.payment_methods
  id, tenant_id, code, display_name
  responsibility (PARTNER|TERMINAL|MARKETPLACE|OPERATOR)
  provider_type null              -- CLICK | PAYME | ... ; required when PARTNER
  contract_reference null         -- required when MARKETPLACE
  settles_from_balance boolean default false   -- ADR 0046 adds this flag
  supports_marking boolean        -- false for PAYME: the detail object has no marking field
  active boolean, version, timestamps
  unique (tenant_id, code)

payments.payment_method_entity_bindings
  id, tenant_id, payment_method_id, legal_entity_id
  provider_installation_id                 -- ADR 0026; this entity's own service or cashbox
  external_account_reference               -- Click service_id, or Payme cashbox id; non-secret
  status, effective_from, effective_until null
  version, timestamps
  unique (tenant_id, payment_method_id, legal_entity_id) where status = 'ACTIVE'
```

**The first draft of this ADR carried `provider_installation_id` directly on
`payments.payment_methods`, and that was wrong.** One tenant-scoped `CLICK` row
cannot be correct for a tenant holding two legal entities, because the two entities
have two different Click services and therefore two different taxpayers. The
binding above is the correction, and it resolves the same way the entity itself
resolves: order → location → active fiscal assignment → legal entity → binding →
installation → ADR 0026 secret reference. An entity with no active binding for a
method does not offer that method, exactly as a location with no fiscal terminal
does not offer cash.

`payment_methods` still lives in the `payments` schema, because tenders and
payment intents read it on every order, but it is owned here: `responsibility` is
a fiscal property — it decides who issues the receipt — and the activation checks
below run against these rows. `code` is stable and a method is deactivated rather
than deleted, because a delivered order's tender and its fiscal document both
point at the row that governed them and must still resolve a year later.

**ADR 0036 left this registry open and now references it.** Its
`channel_payment_methods.payment_method_code` becomes a foreign key to
`payments.payment_methods`, and its provisional code-owned constants `CASH`,
`CLICK`, `PAYME` become seeded rows. A channel therefore enables a method the
tenant actually holds, instead of a string that may name nothing.

**ADR 0046 extends this table rather than running a parallel enum.** A tender
references a payment method row; the `tender_type` enum ADR 0046 drafted as
code-owned is withdrawn as a second registry, and ADR 0046 contributes the one
row loyalty needs — `LOYALTY_POINTS` — carrying `settles_from_balance`, which
marks a method that draws on a platform-held balance instead of an external
settlement. It contributed two until 2026-08-23; `CUSTOMER_DEPOSIT` was
withdrawn with the rest of customer-funded stored value, because nobody holds
customer funds and a registry row for a mechanism that does not exist is a row
somebody will one day activate. Two registries would let a channel offer a
tender that no method row governs, which is precisely how an order settles under
a method that names no fiscal responsibility and nobody notices until the receipt
is missing.

### The question this ADR deliberately leaves open

Click requires a `CommissionInfo` object on **every** item line, carrying either a
`TIN` (9 characters) or a `PINFL` (14), labelled «Данные комиссионного чека» —
commission-receipt data. **The documentation does not say whose identifier it
is.** That matters because commission trade (комиссионная торговля) is the Uzbek
fiscal construct for precisely the arrangement chosen here: goods sold by one
party on behalf of another, with the receipt naming the committent.

Two questions, one subject, neither guessable:

- **To Click, in writing:** whose TIN or PINFL does `CommissionInfo` carry, and
  may a receipt issued under one `service_id` name a different TIN per line? The
  answer is recorded in `docs/providers/fiscalization-via-payment-providers.md`.
- **To legal:** is the Qoida–restaurant agreement a договор комиссии in the sense
  the tax rules use, or an agency arrangement that does not qualify? This is the
  same question as this ADR's original open input on fiscal agency per settlement
  path, and closes with it.

Until both are answered, `fiscal_document_lines.commission_tin` is populated with
the selling legal entity's own TIN — the answer that is correct if
`CommissionInfo` names the committent and harmless if it names the seller, since
under per-entity accounts those are the same party. Nothing is blocked on the
answer, because per-entity merchant accounts are correct either way: Payme has no
per-line fiscal identity field at all, so no Click answer can collapse the
binding's legal-entity dimension.

## Fiscal classification on priceable nodes

```text
catalog.fiscal_classifications
  id, tenant_id, brand_id
  priceable_type (VARIANT|MODIFIER_OPTION|FEE), priceable_id
  mxik_code not null, package_code not null   -- both required by both providers
  unit_code integer not null                  -- Click Units / Payme units
  fiscal_name varchar(63) not null            -- Click Name; unit of measure included
  barcode null                                -- Click Barcode, string(13)
  tax_profile_id null                         -- override; same-brand, ADR 0018
  marking_required boolean, marking_scheme (NONE|DATA_MATRIX)
  excisable boolean, alcohol_by_volume_bp null, age_restriction_years null
  source (MANUAL|IMPORT|POS_SYNC), classified_by, classified_at
  version, timestamps, unique (priceable_type, priceable_id)

catalog.mxik_reference
  code, parent_code null, label_ru, label_uz, label_en
  default_package_codes text[], default_unit_codes integer[]
  valid_from, valid_until null, imported_at
```

**Four columns beyond the first draft, each forced by a provider contract.**
`package_code` becomes NOT NULL because both providers mark it required.
`unit_code` returns a field the legacy schema already had as
`variants.unit_code NOT NULL` and the V0021 interim dropped; Click compensates for
its optionality by demanding the unit of measure *inside* `Name`, so a platform
without a unit code cannot build a conformant Click line at all. `fiscal_name` is
a different string from the display name with a different audience and a hard
63-character cap — a Cyrillic dish name plus its modifiers plus a unit exceeds it
routinely, and truncating a customer-facing name at fiscalization time produces a
receipt line nobody can reconcile against a menu. `barcode` is optional on the
wire and cheap to carry.

**VAT does not live here.** The rate is resolved through ADR 0018 from the
classification's `tax_profile_id` or the entity/brand/tenant chain, and the
per-line VAT *amount* comes from the accepted quote's recorded tax share and is
written onto the document line. Neither is recomputed at fiscalization time. A
second rate stored on the catalog node is how a receipt disagrees with the price
the customer was shown.

`mxik_code` is validated against `catalog.mxik_reference`, not a hard-coded
format: the code's shape belongs to the official list, and asserting a format the
tax authority later changes is a migration nobody planned.
`catalog.products.tax_category_code` is dropped in the same migration, because a
second unenforced classification column would be picked up by exactly one adapter
and disagree with this table forever.

### The V0021 interim columns, and how they end

V0021 added nullable `mxik_code` and `package_code` to `catalog.products`,
`catalog.variants` and `catalog.modifier_options`, with `CatalogValidator`
reporting the gap as a WARNING. Its own comment names them "the smaller interim",
correctly: the columns exist so an operator can begin entering codes before this
table lands, and they were made nullable and non-blocking because this ADR was
still `Proposed`. Both conditions have now ended.

The columns migrate into `catalog.fiscal_classifications` — variant and modifier
codes move to rows of matching `priceable_type`, and the product-level code is
applied as the default for variants that carry none, which is the inheritance
V0021's comment already describes — and are then dropped. The two partial indexes
`ix_variants_unclassified` and `ix_modifier_options_unclassified` served the
coverage question and are dropped with them.

**One naming trap during the migration.** Legacy `variants.package_id` and
`variants.is_package` are a *bundle* concept and have nothing to do with the
fiscal `package_code`. A migration that maps one onto the other produces a menu
that looks fully classified and is not, which is worse than an unclassified menu
because the coverage report reads clean.

`CatalogValidator` gains three rules, all blockers:

| Finding | Raised when |
|---|---|
| `FISCAL_CLASSIFICATION_MISSING` | An active variant or modifier option offered by any location has no classification, or has one missing any of `mxik_code`, `package_code`, `unit_code`, `fiscal_name` |
| `FISCAL_DELIVERY_FEE_UNCLASSIFIED` | A brand publishes to a channel that can charge a delivery fee and the `FEE` node is unclassified |
| `FISCAL_RESTRICTED_NODE_ON_UNVERIFIED_CHANNEL` | A node with `age_restriction_years` is offered on a channel with no age verification, or on a fulfilment mode the entity's policy forbids |

Blocking is the point: the alternative is four hundred live dishes fiscalized
under one catch-all code, found by an inspector and unwound by an accountant one
dish at a time. It is also the only checkpoint that exists on the Payme path —
Payme's `detail` object is fixed *before* the customer pays, so an unclassified
line reaches Payme as an unclassified line and the payment still succeeds. There
is no later gate to catch it. Because it is a wall, the tools to pass it belong to
this decision — bulk assignment across a filtered selection, a per-brand coverage
report, and classification carried as a first-class field through ADR 0012 POS
import and ADR 0024 migration rather than as a later chore.

## Terminals and who discharges the obligation

```text
fiscal.fiscal_terminals
  id, tenant_id, brand_id, location_id, legal_entity_id
  terminal_kind (POS|COURIER_TERMINAL|KIOSK|VIRTUAL)
  provider_binding_id                 -- ADR 0026
  terminal_reference, capability_snapshot jsonb
  status, last_health_check_at, last_health_status, version, timestamps
```

Endpoints come from the ADR 0026 approved provider environment catalogue and never
from tenant configuration, which closes the request-forgery path here for the same
reason it is closed there. A kiosk is a terminal of kind `KIOSK` bound to a
location and resolving to that location's legal entity — the whole of kiosk fiscal
identity, at the cost of one row. The hardware integration stays declined.

| Responsibility | Who issues | Qoida's role | Methods |
|---|---|---|---|
| `PARTNER` | Click or Payme, under the selling entity's own merchant account | Supply lines, retain evidence, chase silence; corrections requested through the partner | Online card, wallet |
| `TERMINAL` | A fiscal-capable POS, courier terminal, or kiosk belonging to the entity | Request issuance, record the returned reference, block on failure | Cash, courier terminal, kiosk, dine-in POS settlement |
| `MARKETPLACE` | The aggregator, where contracted as fiscal agent | Record not-required with the contract reference as evidence | Aggregator-settled orders |
| `OPERATOR` | A fiscal operator called directly by Qoida | Specified, not implemented | None in the pilot |

Three checks run at activation rather than at receipt time. A method declaring
`PARTNER` must have an active `payment_method_entity_bindings` row for every legal
entity whose locations the method's channels serve, and each bound installation's
capability snapshot must include `IssueFiscalReceipt`; a method declaring
`MARKETPLACE` must carry a recorded contract reference. Without them a tenant ticks
"Click fiscalizes", nothing fiscalizes, and the gap surfaces at a tax audit
instead of at configuration.

**The two providers meet under `TERMINAL` as well as under `PARTNER`.** Click's
`POST payment/ofd_data/submit_qrcode` and Payme's Subscribe-API
`receipts.set_fiscal_data` both attach a receipt issued on the restaurant's own
equipment to a provider payment. That is the only remedy for a captured payment
that cannot be fiscalized which does not depend on a calendar boundary — Click's
reversal is restricted to the current reporting month, so it is unavailable for an
unfiscalized sale discovered a week later.

## Fiscal document lifecycle

```text
PENDING ──► REQUESTED ──► ISSUED ──► CORRECTION_REQUESTED ──► CORRECTED
   │            │            └─────► VOID_REQUESTED ────────► VOIDED
   │            ├──────► FAILED ──► REQUESTED     (retry, same document)
   │            └──────► BLOCKED                  (reporting deadline passed)
   ├──► BLOCKED ──► PENDING        (blocking condition cleared)
   ├──► NOT_REQUIRED               (evidenced, terminal)
   └──► NOT_APPLICABLE             (evidenced, terminal — no provider path exists)
```

**`REQUESTED` is called `SUBMITTED` in the built schema.** V0027 named the column
value that way before this ADR was accepted and both modules read the same
column, so the value is what it is; renaming it would be a migration whose only
effect is to make two files agree with a diagram. Every "`REQUESTED`" below means
that state. `NOT_REQUIRED` is specified and unreachable, because the
`MARKETPLACE` responsibility that produces it is not built.

```text
fiscal.fiscal_documents
  id, tenant_id, brand_id, location_id, order_id
  tender_id null                            -- ADR 0046; null for a single-tender order
  legal_entity_id, fiscal_assignment_version
  terminal_id null, payment_method_entity_binding_id null, payment_transaction_id null
  responsibility, document_type (SALE|REFUND|CORRECTION)
  provider_receipt_type null                -- Payme PERFORM | CANCEL
  status, blocked_reason_code null, not_applicable_reason_code null
  failure_code null, provider_status_code null, provider_message null
  attempt_count, reporting_deadline_at null
  total_minor, currency, business_date
  external_receipt_id null, fiscal_sign null, receipt_reference null
  terminal_reference null                   -- the VFM / t= component
  corrects_document_id null, idempotency_key, requested_at null, issued_at null
  protected_request_reference, protected_response_reference, version, timestamps

fiscal.fiscal_document_lines
  document_id, sequence, source_quote_line_id, priceable_type, priceable_id
  fiscal_name, mxik_code, package_code, unit_code, quantity
  unit_price_minor, line_total_minor, vat_rate_bp, vat_amount_minor
  discount_minor, commission_tin null
  marking_scheme, marking_unit_count null

fiscal.fiscal_unit_marks
  id, tenant_id, order_line_id, document_line_id null, sequence
  marking_scheme, code_reference       -- ADR 0029 protected; never logged
  captured_by, captured_at, capture_stage (PICK|HANDOVER)
```

**Lines are derived from the accepted ADR 0018 quote snapshot, never recomputed**,
and the receipt total must equal the order total to the som. ADR 0018's rounding
remainder is allocated deterministically to the highest-value line and recorded,
because a receipt has no line type meaning "rounding" and one whose lines do not
sum to its total is rejected outright.

**Evidence is stored as fields, not as a link.** Click packs the fiscal sign,
receipt number, terminal id and registration timestamp into a single
`https://ofd.soliq.uz/epi?t=…&r=…&c=…&s=…` URL; Payme returns them as named
fields plus a URL. The URL points at a service Qoida does not run and whose
lifetime is not Qoida's to guarantee, so the Click URL is parsed into its four
components and both the components and the URL are stored. Alongside them the
exact request — the `items` array or `detail` object as sent, with the binding and
legal entity in force — goes to `protected_request_reference`. The legacy
`tax_receipts` table understood this, holding `payload`, `request`, `response` and
`error` as four columns, and it is the only thing that makes an incorrect receipt
explicable a year later.

**`BLOCKED` is a state, not an error.** It carries a reason —
`CLASSIFICATION_MISSING`, `MARKS_INCOMPLETE`, `NO_FISCAL_PATH`,
`TERMINAL_OFFLINE`, `PROVIDER_REPORT_OVERDUE` — and appears in the operator
console as work. An order sitting unreceipted behind a generic failure is how a
tenant finds out at month end.

**Manual retry reuses the document.** Delever's «Фискализировать» becomes
`POST /api/v1/operations/orders/{orderId}/fiscal-document/retry`, an ADR 0031
intent command with `Idempotency-Key` and expected-version, audited under ADR 0027
with a required reason. It never creates a second document for the same leg: two
sale receipts for one payment is a discrepancy with the tax authority that cannot
be deleted, only corrected, and costs an accountant a day. On the Click path the
retry **reads before it writes** — `GET payment/ofd_data/:service_id/:payment_id`
returns a populated `qrCodeURL` when a receipt already exists, and the retry is
then suppressed. Click does not document `submit_items` as idempotent, so that
read is what makes the retry safe; it stays mandatory until Click answers
otherwise in writing.

### What is built, and what a reader should not assume

The lifecycle above exists. It lives in `fiscal.fiscal_documents`, which V0039
moved out of `payments` — V0027 created the table in the `payments` schema and
said in its own comment that these rows would move when this ADR's schema landed,
and the column shape it chose made the move a copy rather than a redesign.
`payments.fiscal_documents` survives as an auto-updatable view over the moved
table, restricted to V0027's exact column list. That shim is not a permanent
arrangement: it exists so the move was a schema change rather than a cross-module
refactor of a built module, and retiring it is a rename in six SQL strings.

Six of the seven states are reachable today. `PENDING`, `SUBMITTED`, `ISSUED`,
`FAILED` and `NOT_APPLICABLE` are written by the payments seam as providers
answer; `BLOCKED` is written only by the sweeper below, and only ever with
`PROVIDER_REPORT_OVERDUE`. `CORRECTION_REQUESTED`, `VOID_REQUESTED`, `CORRECTED`
and `VOIDED` are **not built** — a correction is requested through the partner
and no Qoida-side command exists for one.

Three things a reader should not assume from the sketches above:

- **`fiscal_document_lines`, `fiscal_unit_marks` and `fiscal_terminals` do not
  exist.** Lines are deliberately not stored as rows; what is retained is the
  exact `items` array or `detail` object that was sent, behind an ADR 0029
  protected reference, because a reconstruction is not evidence. The other two
  are rollout stages 5 and 6 and have no writer. An empty table for each would
  read to the next author as though the projection were broken.
- **`responsibility` is derived, not stored.** A document with no provider is a
  cash or terminal leg. A stored copy would have no maintainer on the rows the
  payments seam inserts, and two columns for one fact have no defined winner when
  they disagree.
- **Nothing currently opens a `PARTNER` obligation or submits one.** The Click
  and Payme adapters exist and are correct — including the mandatory read-back
  through `GET payment/ofd_data` before any Click resubmission — but the caller
  that drives them at capture does not. That is rollout stage 4's remaining half,
  and it is the reason `fiscal.api.PartnerFiscalizationPort` is declared with an
  unwired stand-in rather than left out: the operator retry command exists, and
  while nothing implements the port it answers `NOT_WIRED` on the response and on
  every worklist read, instead of reporting a silent success.

### One obligation, several documents

The obligation is per order. The **evidence** is per settlement leg, and the two
are not the same cardinality:

- Payme's `SetFiscalData` arrives once as `PERFORM` and, after a refund, again as
  `CANCEL`. The tax authority forms two separate fiscal receipts and Payme's own
  documentation says to store them separately, side by side.
- An ADR 0046 split-tender order settled partly on Click and partly in cash
  produces evidence on two different paths for one order.

So `fiscal.fiscal_documents` is keyed by `order_id` with a nullable `tender_id`,
and **there is no unique index on `order_id` alone**. What the database enforces
is one `SALE` document per settled tender — a partial unique index on
`(tenant_id, order_id, tender_id)` where `document_type = 'SALE'` and the document
is not voided. A `REFUND` or `CORRECTION`
is an additional row linked by `corrects_document_id`, and Payme's `CANCEL`
payload lands on that row rather than over the `PERFORM` one. An implementer who
reads "exactly one fiscal document per order" as a constraint writes the cancel
data over the perform data and destroys the only record that the sale was ever
fiscalized.

**As built, and the trap inside the trap.** V0039 creates
`uq_fiscal_document_sale_per_tender` with **`NULLS NOT DISTINCT`**, and without
that clause the index is worse than useless. A single-tender order carries
`tender_id IS NULL`, and under PostgreSQL's default every such row is unique
against every other — so the rule would hold for split-tender orders, which are
the rare case, and silently permit unlimited duplicate sale documents on the
common one. That is the wrong way round, and it is the sort of defect that only
appears in a coverage report months later as an issued-share above one hundred
percent.

The uniqueness on `idempotency_key` this ADR also asked for is **not built**, for
a reason worth recording: nothing yet generates one. The column exists in the
sketch above and not in the table, and adding a unique index over a column no
writer populates would enforce nothing while reading as though it did. It belongs
with the stage 4 caller that will produce the key.

### The reporting sweeper: silence is not evidence

Payme's `SetFiscalData` is **inbound and optional to implement**, and there is no
merchant-initiated retry — `receipts.set_fiscal_data` runs the other way and is
for a merchant who fiscalized on their own equipment. So a callback that never
arrives leaves "not yet reported" and "no receipt" as the same state, and the
tenant learns the difference at an audit. Nobody else owns this, so this ADR does.

- **Every document entering `REQUESTED` carries `reporting_deadline_at`**, set
  from an ADR 0030 policy value resolved per provider and legal entity —
  `fiscal.reporting_deadline_minutes`, default 60 — with an absolute backstop at
  the end of the document's `business_date`. The backstop is not a duplicate of
  the interval: a tax obligation is per business date, and asking a provider today
  what happened today is a different conversation from asking next week.
- **A scheduled sweep in the `fiscal` module moves every overdue `REQUESTED`
  document to `BLOCKED` with `PROVIDER_REPORT_OVERDUE`**, emitting
  `FiscalDocumentBlocked`. The sweep is idempotent and re-entrant; it changes
  status and nothing else.
- **A late callback is still accepted.** `SetFiscalData` is idempotent on
  (`params.id`, `type`); an arrival after blocking clears the block and resolves
  the document normally. The sweeper marks a document as needing a human, not as
  finished.
- **Arrival is not proof of a receipt.** `status_code` is a status and `message`
  describes an OFD registration failure. A `SetFiscalData` with a non-zero
  `status_code` moves the document to `FAILED` with the provider's code and
  message stored — never to `ISSUED`. Storing `fiscal_data` and marking `ISSUED`
  on arrival is a defect that passes every test written against the happy-path
  example in the docs.
- The full `status_code` enumeration is undocumented and is an open input. Until
  Payme supplies it, zero is success and every other value is a failure requiring
  an operator, which is the safe direction to be wrong in.

#### As built

`fiscal.application.FiscalReportingSweeper` polls PostgreSQL every minute and
`FiscalDocumentService.sweepOverdueReports` does the work. Six properties of it
are decisions rather than implementation detail, and each one is a way the sweeper
could have been written so that it appeared to work.

- **It polls, and it is not a consumer.** The thing being detected is the
  *absence* of a message, so nothing will arrive to trigger it. An in-memory
  timer would be lost on every deployment, and the documents it was watching would
  then sit `SUBMITTED` for ever — the exact failure this exists to remove,
  reintroduced by the mechanism meant to remove it. This is the fourth durable
  timer in the codebase after ADR 0019's approval deadlines, ADR 0017's expiry and
  ADR 0041's release buffer, and it is written a fourth time for ADR 0041's stated
  reason.
- **The sweep interval is a minute and the deadline is an hour.** Sweeping rarely
  would add a second, invisible deadline on top of the configured one: a
  sixty-minute policy swept hourly blocks somewhere between sixty and a hundred
  and twenty minutes, and nobody would be able to say which.
- **Only `SUBMITTED` documents are swept, never `PENDING`.** A `PENDING` document
  has not kept a provider waiting for anything — it is waiting on a capture that
  may never come, because the customer abandoned the checkout. Ageing those into
  `BLOCKED` would fill the worklist with abandoned carts, and a worklist that is
  mostly noise is a worklist nobody reads, which returns the system to exactly the
  state before the sweeper existed. The gap this leaves is real and is named in
  the checklist: a captured payment whose submission never ran stays `PENDING`,
  and closing that needs the stage 4 caller rather than a looser sweep.
- **The deadline is `LEAST(interval, end of business date)`.** The backstop is a
  ceiling on how long a policy may postpone the question, not an extension of it.
  The business date is the *branch's* calendar day — a UTC day rolls over at 05:00
  in Tashkent, in the middle of a night service — resolved through the payment
  intent's location and falling back to a configured platform timezone rather than
  dropping the document on a failed join. ADR 0038 asks for the business date to
  be snapshotted onto the document at acceptance; it is not, because nothing
  snapshots one yet, and deriving it is the honest interim rather than a column no
  writer fills.
- **The deadline actually applied is written onto the row as it blocks.** Without
  it an operator can see that a document is overdue and not by how much or against
  what, and the false positives this ADR accepts in writing would be unarguable
  rather than merely tolerable.
- **Every write is conditional on the document still being `SUBMITTED`.** A
  callback arriving between the claim and the update therefore wins: the update
  matches nothing and the receipt is the outcome that survives. The alternative
  overwrites a fiscal sign that is on file with the tax authority with the word
  "blocked".

**Blocked is visible in three places, and none of them is a log line alone.** The
worklist is `GET /api/v1/tenants/{tenantId}/fiscal/documents/blocked`, ordered by
how long each document has been waiting rather than by when its order was placed.
The coverage report below counts blocked documents separately from cash. And each
block is logged at WARN with the document, the order and the deadline on it — and
with no fiscal sign, no receipt URL and no marking code, because ADR 0029 keeps
evidence out of logs and a status is not evidence.

**What is not built here is the ADR 0032 event.** `FiscalDocumentBlocked` and its
siblings have no contract in `EventCatalog`, no schema in `events/`, and no
outbox listener. That is a governed change in the `integration` module rather than
in this one, and shipping a Spring application event that nothing listens to would
have been the appearance of an event stream without one.

### Cash: an explicit state, not an absence

**A cash order gets no fiscal receipt from either payment provider, and cannot.**
Click's `submit_items` requires a CLICK `payment_id` that does not exist for a
cash order; Payme's fiscal data attaches to a Payme receipt that does not exist.
Click's `received_cash` is a tender split *inside* a Click payment and is not a
cash path — reading it as one builds a system that appears to fiscalize cash and
does not, which is the worst available outcome because the gap stays invisible
until an inspection.

Decided by Ayubkhon Abbosov on **2026-08-22**: a cash tender records a fiscal
document with status **`NOT_APPLICABLE`** and
`not_applicable_reason_code = 'CASH_TENDER_NO_PROVIDER_FISCALIZATION'`. Never a
null, never a missing row.

The reason is queryability under reversal. If a provider, a fiscal operator or a
regulation later makes cash fiscalization possible, or if the decision is simply
revisited, the affected orders must be findable by
`where status = 'NOT_APPLICABLE' and not_applicable_reason_code = …` rather than
by an accountant reading order histories. A null carries no such handle, and it is
also the same shape as "not attempted yet", which is precisely the confusion the
`BLOCKED`-with-a-reason design exists to end.

`NOT_APPLICABLE` is distinct from `NOT_REQUIRED`. `NOT_REQUIRED` says another
party is contractually the fiscal agent and names the contract as evidence.
`NOT_APPLICABLE` says this settlement leg has no provider path at all. Neither
extinguishes the order's obligation: cash is a `TERMINAL` method, so the receipt
comes from the entity's own fiscal-capable equipment, and where no such terminal
is bound the method is not offered at all. A cash order that somehow exists with
no terminal path is `BLOCKED` with `NO_FISCAL_PATH` — not `NOT_APPLICABLE`.

**This is the majority of orders, not an edge case.** The legacy
`payment_methods` table seeds three rows — `cash` enabled, `click` disabled,
`payme` enabled — and cash is the tender this market's customers use most. Until
ADR 0011's POS work lands and stage 5 of the rollout below is complete, provider
fiscalization therefore covers the minority of orders. That is stated again under
Consequences because it is the single most important thing a reader of this ADR
should not be able to miss.

**So the report refuses to state a single coverage figure.**
`GET /api/v1/tenants/{tenantId}/fiscal/coverage` returns counts and *three*
shares — issued, not-applicable, and unreceipted — and never one number, because
any single figure has to decide whether a cash order counts as covered and both
answers are wrong. Counted, it reports an unreceipted majority as healthy;
excluded, it reports a compliant restaurant as failing. `unreceipted` is blocked
plus failed plus still-waiting, and `NOT_APPLICABLE` is deliberately outside it: a
cash order owes a receipt from the restaurant's own equipment, which is a
different problem with a different owner, and folding the two together produces a
number no action can move. The response also carries
`providerPathIsMinority` as a flag, because this ADR predicts that will stay true
for the whole pilot and a prediction nobody checks is a prediction nobody notices
coming true.

`NOT_APPLICABLE` is also never blocked, and the database says so
(`ck_fiscal_document_not_applicable_is_not_blocked`) rather than the sweeper
merely happening not to select it. A cash document is not a document the sweeper
forgot; it is one there was never a provider to chase.

## What the provider contracts fix in the port

These are invariants of the adapters, not restatements of the wire protocol. Each
has a test in the list below. The full contracts are in `docs/providers/`.

1. **The som↔tiyin conversion happens exactly once, at the adapter boundary, and
   is visible in the type.** ADR 0018 stores `amount_minor` as **whole som**.
   Click's SHOP API `amount` is **som**; Click's fiscalization `Price` and `VAT`
   and every Payme amount are **tiyin**. So one Click adapter carries both units,
   and which one a value is in must be readable from its type or its method name.
   A bare `* 100` may not appear in any payment or fiscal adapter — a
   factor-of-100 error here charges a customer a hundred times the price.
2. **Click's `Price` is the line total; Payme's `price` is the unit price.** The
   same word, a factor of quantity apart. There is no shared `toReceiptLine()`
   helper across the two adapters; that helper is how an order is fiscalized at
   `quantity²` times its value.
3. **Signatures are computed over the raw received strings.** Click's
   `sign_string` is an MD5 over concatenated form values as received; reformatting
   `1000.00` to `1000` before hashing is the commonest cause of a spurious
   `-1 SIGN CHECK FAILED!`. Prepare and Complete sign *different field lists*, and
   `click_paydoc_id` is in neither. Parse only after verifying, and compare in
   constant time.
4. **A business failure is never reported through Click's Complete.** After a
   successful charge, Complete may return only `-4` (already paid) or `-9`
   (already cancelled). If the order cannot be fulfilled, answer `error: 0` and
   then call the reversal endpoint. Returning an error instead leaves the customer
   charged and uncredited while CLICK retries and eventually escalates to manual
   investigation.
5. **Payme's auth failure is `-32504` returned with HTTP 200.** Every Payme
   response is HTTP 200, errors included; any non-200 is read as `-32400`.
   `message` is a localised object `{ru, uz, en}`, not a string, and is mandatory
   in that shape for the account-error range. Spring's stock `httpBasic()` returns
   a bodyless 401 and fails Payme's very first sandbox test.
6. **Payme's 12-hour timeout is measured from `params.time`** — Payme's creation
   time, not the merchant's. On expiry the transaction goes to state `-1` with
   reason `4`, and `PerformTransaction` must never perform an expired one.

**Do not copy either official reference implementation.** Click's Django sample
lets underpayment pass its amount check through a misplaced parenthesis, and its
`-8` missing-field check fires only for a completely empty body. Payme's own Java
template returns a bodyless 401, filters `GetStatement` to completed transactions
only — silently breaking reconciliation — and re-cancels an already-cancelled
transaction, rewriting `-2` back to `-1`. The provider notes record, per
disagreement, which source to believe and why.

## Marked goods, age gating, and the payment-method constraint

A marked good needs one identifier per physical unit, so quantity stops being a
number the customer chose and becomes a set of specific objects somebody scans.
`marking_required` forces integer quantity and forbids splittable or catch-weight
semantics. Marks are captured at `PICK` or `HANDOVER`, never at cart time — the
customer cannot know which physical bottle they will receive, and a code captured
optimistically at checkout belongs to an item that went to someone else. The
document leaves `PENDING` only when the captured count equals the line quantity;
otherwise it is `BLOCKED` with `MARKS_INCOMPLETE`. Codes are ADR 0029 protected
data: stored by reference, never logged, never carried in an ADR 0032 event.

**Marking constrains the payment method, and this is new.** Click's `Items[]`
carries `Labels`, an array of marking codes. **Payme's `detail.items[]` has no
marking field of any kind.** A marked good therefore cannot be lawfully
fiscalized through Payme. `payments.payment_methods.supports_marking` is `false`
for Payme, and a cart containing any node with `marking_required` removes every
method whose `supports_marking` is false from the offered set — the same mechanism
by which a location with no fiscal terminal does not offer cash. Stated plainly:
marking is a constraint on *how the customer may pay*, not only on which channel
may sell.

Age gating is a property of the classification and a constraint on the channel. A
node with `age_restriction_years` is unsellable on any channel that cannot verify
age — an unattended kiosk cannot; an operator-assisted order can only if the
handover records a verification. **Whether a restricted class may be delivered at
all defaults to deny**, per legal entity, as an ADR 0030 policy: that is a legal
determination Qoida is not entitled to make on a tenant's behalf, and a default of
allow would silently make the platform the instrument of a breach.
`alcohol_by_volume_bp` and `excisable` are carried because receipts and aggregator
feeds ask for them, not because Qoida computes anything from them.

## Telegram: designed for, not built

ADR 0036 already owns `TELEGRAM` as a channel `system_type`. Three properties of
the two providers' Telegram surfaces are accommodated here so that adding a bot
later is an adapter and a row, not a redesign.

- **The provider token is a per-legal-entity credential**, held as an ADR 0026
  secret reference on that entity's binding — exactly the dimension this ADR
  already added. Payme is explicit that a Telegram bot needs **its own bot
  cashbox** and will not work with an existing one, so a bot is a second
  installation for the same legal entity, not a flag on the first.
- **The paid signal is not the Merchant API.** On Telegram, settlement is
  confirmed by Telegram's `SuccessfulPayment` update, with a 10-second deadline on
  `answerPreCheckoutQuery`, and a Payme bot-cashbox payment appears not to call the
  JSON-RPC endpoint at all. The payment port therefore may not assume that a
  capture is learned through a provider callback into Qoida's own JSON-RPC or
  SHOP API surface.
- **The fiscal path for a Telegram order is undocumented on both providers.**
  Neither states whether item-level ИКПУ must be passed through Telegram's
  `provider_data`, nor which currency exponent applies. A Telegram order's fiscal
  document is consequently created like any other, and the reporting sweeper is
  what surfaces the case where nothing ever comes back. That is the argument for
  building the sweeper before the bot rather than with it.

## Events and testing

```text
LegalEntityRegistered / LocationFiscalAssignmentChanged
PaymentMethodEntityBindingActivated / Suspended
FiscalDocumentRequested / Issued / Failed / Blocked / Corrected / Voided
FiscalDocumentNotApplicable
FiscalClassificationAssigned / FiscalClassificationCoverageBreached
```

Under ADR 0032 these carry identifiers and status only. An INN is a business
identifier and may appear; a marking code, a fiscal sign, and a receipt URL are
evidence and stay behind the protected reference. **None of them is published
yet** — see the sweeper section — so a consumer waiting for `FiscalDocumentBlocked`
would wait for ever, and the worklist and the coverage report are what carry the
information today. Tests that must exist:

- An order at a location whose assignment changed after acceptance still receipts
  under the INN in force on its business date, and overlapping assignments are
  rejected by the database rather than by a service.
- A tenant with two legal entities resolves two different Click services and two
  different Payme cashboxes for the same payment method code, and a method with no
  active binding for an entity is absent from that entity's channel matrix.
- A cash method cannot be activated where no fiscal-capable terminal is active,
  and leaves the channel matrix when that terminal is suspended.
- Every cash-tendered order has a fiscal document with status `NOT_APPLICABLE`
  and the cash reason code; a query over that pair returns exactly the cash orders
  and no nulls appear in the status column for any accepted order.
- Publication is blocked when any offered variant, modifier option, or the
  delivery fee lacks a classification or lacks any of `mxik_code`,
  `package_code`, `unit_code`, `fiscal_name`.
- The delivery fee appears in the Payme `detail.items` array and never in
  `shipping`, and its line carries an ИКПУ, a package code and a VAT percent.
- A cart containing a `marking_required` node offers Click and not Payme.
- A tax profile whose `rate_basis_points` is not a multiple of 100 is rejected by
  the adapter rather than rounded onto a receipt.
- Golden-value tests on the unit boundary: one order produces a Click SHOP API
  `amount` in som and a Click fiscalization `Price` in tiyin differing by exactly
  ×100, and Payme's `price` equals the unit price while Click's `Price` equals the
  line total for the same line at quantity 3.
- Document lines sum exactly to the order total, rounding allocation included,
  across ADR 0018's golden carts, and concurrent manual retries of one order
  produce one document and one issuance.
- A Payme order receiving `PERFORM` then `CANCEL` holds two document rows linked
  by `corrects_document_id`, with the sale's fiscal sign still readable after the
  cancellation is stored.
- A `SetFiscalData` with a non-zero `status_code` leaves the document `FAILED`
  with the provider message stored, not `ISSUED`.
- A document that reaches its `reporting_deadline_at` in `REQUESTED` is `BLOCKED`
  with `PROVIDER_REPORT_OVERDUE`, and a callback arriving afterwards clears it.
- A marked line with fewer captured codes than its quantity stays `BLOCKED`, and
  the same cart under two legal entities with different tax profiles produces two
  different context hashes.

## Rollout

1. Legal entities and assignments, migrating every location to one tenant-default
   entity carrying the tenant's INN — the implicit state made explicit.
2. Classification tables, MXIK reference import, migration of V0021's interim
   columns, bulk assignment and coverage tooling, before any validator rule is
   switched on.
3. Validator rules enabled per brand once its coverage report is clean. A brand is
   not published against a wall it has not been given tools to pass.
4. The `fiscal` module with `PARTNER` responsibility only — ADR 0013's original
   scope, relocated — with **both** Click and Payme adapters, per-legal-entity
   bindings, the reporting sweeper, and `NOT_APPLICABLE` for cash tenders from the
   first commit. The sweeper is not a later hardening step: without it the Payme
   adapter cannot distinguish success from silence. *Partly done: the adapters,
   the bindings, the cash record, the module, the sweeper and the blocked worklist
   are built; what remains is the caller that opens a partner obligation at
   capture and submits it, which is also what makes the operator retry reach a
   provider.*
5. `TERMINAL` responsibility against the ADR 0011 POS capability; cash enabled per
   location as terminals are bound and health-checked. Until this stage completes,
   provider fiscalization covers the minority of orders.
6. Marked goods and age gating last, against a real marked SKU rather than an
   assumption — and with the Payme exclusion in place before the first marked SKU
   is published.

Stages 1 to 3 are additive. Stage 4 rolls back by deactivating the `PARTNER`
methods, which withdraws them from the channel matrix. Stage 5 rolls back by
suspending terminal bindings, withdrawing cash rather than issuing unreceipted
orders.

## Consequences

### Positive

- A tenant can contain several taxpayers, which is what this market looks like,
  and every receipt names the right one for its date and the right merchant
  account for that taxpayer.
- The restaurant is the seller on its own receipt, under its own INN, which is
  what the arrangement actually is and what both providers force anyway.
- Cash, kiosk, and dine-in orders have a receipt path instead of an assumption, so
  the pilot can legally take the payment method most customers use.
- Classification is validated once, at publication, on the same priceable
  vocabulary pricing already uses, so the delivery fee cannot be the line that
  quietly has no code — and publication is the only checkpoint the Payme path
  offers.
- An unreceipted order is visible as blocked work with a reason rather than
  discovered at month end, including the case where a provider simply never
  answers, and a direct fiscal operator later costs one ADR 0026 category value
  and one adapter.

### Negative

- **Provider fiscalization covers the minority of orders until stage 5 lands.**
  Cash is enabled in the legacy and is this market's dominant tender; every cash
  order carries `NOT_APPLICABLE` for the provider path and depends on the
  restaurant's own equipment for its receipt. This is honest, queryable, and still
  a gap.
- Onboarding a restaurant now includes obtaining a Click service and a Payme
  cashbox in that restaurant's own name, each requiring an acquiring contract with
  a connected bank. That is a commercial dependency on a third party's timeline
  that Qoida cannot compress, and it is on the critical path for every new legal
  entity rather than once per tenant.
- Cash becomes conditional on POS work ADR 0011 accepted and has not built. A
  location without a compliant terminal cannot take cash, and the first tenant
  will experience that as Qoida refusing money.
- Two provider adapters must be built and kept correct at once, against contracts
  that disagree on units, on the meaning of `price`, and on the direction fiscal
  data travels. Both official reference implementations contain defects, so
  neither can be used as a shortcut.
- A tenant selling a marked SKU loses Payme as a payment method for any cart
  containing it. That is a revenue consequence of a legal constraint, and the
  customer sees it as a missing button.
- ADR 0013 must now be built against a moved boundary, and payments depends on a
  module that does not exist yet.
- The classification wall will block onboarding, and it is now four required
  fields per node rather than two. A brand migrating four hundred products cannot
  publish until every one is classified; bulk tooling reduces that cost without
  removing it.
- Marked goods add a scan at pick or handover, and a courier whose device cannot
  read a damaged Data Matrix has a blocked order and a customer in front of them.
- Every financial report must group by legal entity rather than by tenant, so
  reports written against tenant totals are quietly wrong for multi-entity tenants.
- Tax profile resolution has three scopes instead of two, and a misconfigured
  entity-level profile silently overrides a correct brand-level one.

### Accepted trade-offs

- Qoida stays a requester and retainer of fiscal documents rather than an issuer.
  That accepts a dependency on the tenant's own equipment and contracts in
  exchange for not owning a legal-document obligation, an operator contract per
  legal entity, and an outage class in which orders cannot lawfully complete.
- Classification is a hard blocker rather than a warning with a grace period. A
  grace period would be used, and the debt would be found by an inspector.
- The reporting deadline will produce false positives — a document blocked at 60
  minutes whose callback arrives at 70. A cleared block costs an operator a
  glance; an uncleared silence costs a tenant an audit finding, and the asymmetry
  decides the default.
- `CommissionInfo` is built against the conservative reading — the selling
  entity's own TIN, per-entity accounts — which may turn out to be more accounts
  than strictly required. Consolidating later is a commercial change; splitting
  later would mean re-pointing live payment configuration for every entity.

## Implementation checklist

- [x] Add `tenant.legal_entities` and `tenant.location_fiscal_assignments` with the overlap exclusion constraint. (V0053, which also adds the two foreign keys from `payments.merchant_bindings.legal_entity_id` and `fiscal.fiscal_documents.legal_entity_id`, so neither is an unconstrained uuid any longer. No control-plane surface writes either table: `LegalEntityService` has no controller, so an entity and its assignment are hand-written SQL today.)
- [ ] Extend ADR 0018 tax profile resolution and the quote context hash with the legal entity, and constrain `rate_basis_points` to multiples of 100.
- [x] Add `catalog.fiscal_classifications` with `unit_code` and `fiscal_name`, and `catalog.mxik_reference`; migrate V0021's interim columns into it and drop them, along with `catalog.products.tax_category_code` and the two unclassified indexes. (V0028; the four completeness fields are nullable and asserted by the validator, so stage 3 can be enabled per brand.)
- [ ] Add the three `CatalogValidator` rules, bulk assignment, and the coverage report. (V0028 reports coverage; the rules are still warnings.)
- [x] Create the `fiscal` module and move `payments.fiscal_documents` into `fiscal.fiscal_documents` behind a compatibility view: the `BLOCKED` state, the one-sale-per-settled-tender index with `NULLS NOT DISTINCT`, the blocked worklist, the coverage report, the audited retry and unblock commands, and the `NOT_APPLICABLE` cash path. (V0039. Lines, unit marks and terminals are deliberately absent — see [What is built](#what-is-built-and-what-a-reader-should-not-assume).)
- [ ] Add `payments.payment_methods` and `payments.payment_method_entity_bindings`, seed each tenant's methods, and repoint ADR 0036's `channel_payment_methods.payment_method_code` at the registry as a foreign key.
- [ ] Validate fiscal responsibility and per-entity bindings at payment-method activation; add the cash serviceability precondition and the marking-versus-`supports_marking` exclusion.
- [x] Build the Click and Payme fiscal adapters together, with the single named som↔tiyin conversion, read-before-retry on Click, and `PERFORM`/`CANCEL` as separate documents on Payme. (V0027's module; a `SetFiscalData` with a non-zero `status_code` lands `FAILED` and never `ISSUED`.)
- [ ] Wire the adapters to the lifecycle. Half done: `FiscalObligationService` and `FiscalObligationSweeper`, which landed with V0053, open the obligation and claim it for submission on two timers — at completion rather than at capture, because ordering publishes no capture signal. Still missing, and the whole of what remains in rollout stage 4: an implementation of `fiscal.api.PartnerFiscalizationPort` in payments, without which every claimed submission settles `NOT_WIRED` and is released unsent, and an operator retry reaches neither Click nor Payme.
- [x] Implement the reporting sweeper, the `fiscal.reporting_deadline` ADR 0030 policy, and the business-date backstop. (V0039. The policy is settable at `PLATFORM` and `TENANT`, defaults to sixty minutes, and takes per-provider overrides; legal-entity granularity belongs inside the document rather than as a fourth ADR 0025 scope.)
- [ ] Snapshot the order's `business_date` and `tender_id` onto the fiscal document at acceptance. The sweeper derives the business date from the branch's timezone today, and `tender_id` is null on every row, which is correct for one intent per order and wrong the moment ADR 0046 split tender lands.
- [ ] Publish the `FiscalDocument*` ADR 0032 event contracts — catalogue entries, schemas, topic policy and an outbox listener — so `FiscalDocumentBlocked` reaches a consumer rather than only a worklist.
- [ ] Retire the `payments.fiscal_documents` compatibility view by renaming the six SQL strings in `JdbcFiscalDocumentStore` and `PaymeMerchantApi` to the `fiscal` schema.
- [ ] Wire `TERMINAL` issuance through the ADR 0011 POS capability and ADR 0026 bindings, including Click `submit_qrcode` and Payme `receipts.set_fiscal_data` as the recovery remedy.
- [ ] Implement mark capture at pick and handover, and the age-gate channel constraint.
- [ ] Build the correction and void commands: `CORRECTION_REQUESTED`, `VOID_REQUESTED`, `CORRECTED` and `VOIDED` are in the state diagram and unreachable.
- [ ] Ask Click the `CommissionInfo` question in writing and legal the договор комиссии question; record both answers in `docs/providers/fiscalization-via-payment-providers.md` and revisit only the consolidation option here.

## Exit criteria

Every accepted order carries a resolved legal entity and a fiscal obligation
resolved exactly once — issued, evidenced as not required, evidenced as not
applicable with a reason, or visibly blocked with a reason an operator can act on
— with no null fiscal status anywhere; a `PARTNER` receipt is issued under the
selling entity's own merchant account and names that entity's INN; no brand can
publish a menu in which a priceable node reaching a receipt lacks a
classification, a unit code or a fiscal name; a cash payment method cannot be
offered where no fiscal-capable terminal is bound; a cart containing a marked node
cannot be paid through Payme; a document that is never reported becomes blocked
rather than staying pending; and a receipt's lines sum exactly to the order total
ADR 0018 produced, in the unit each provider expects.

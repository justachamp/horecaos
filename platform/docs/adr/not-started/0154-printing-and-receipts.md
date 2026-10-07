# ADR 0154: Printing and receipts

- Decision status: Accepted — proposed by Claude (batch 19); accepted by the platform owner 2026-10-07
- Implementation status: Not started — nothing prints from HorecaOS. No module,
  table, endpoint or screen exists for a printer, a print job, a route or a receipt
  layout. A word search of `platform/src/main/java` finds «print» as a verb or noun only
  in comments (a record's `toString` that would print a secret, a printed table card,
  "the short number a receipt prints") and in the Apache CSV printers of
  `ReportExportService` and `CatalogImportParser`, never as code that prints, and
  `PosCapability` has no print constant (its ten are `CATALOG_READ`,
  `AVAILABILITY_READ`, `ORDER_APPROVAL`, `ORDER_EXPORT`, `ORDER_CANCELLATION`,
  `PREPARATION_STATUS`, `RECEIPT_READ`, `FISCAL_IDENTIFIER_WRITE_BACK`,
  `FULFILLMENT_STATUS_WRITE` and `CUSTOMER_UPSERT`). What prints today is whatever a
  till does. `PosOrderExportService` exports a confirmed order and the POS produces its
  own kitchen ticket as a side effect the platform cannot see: V0036's comments call a
  repeated post "a second kitchen ticket". orders.md §4.8 specifies «Печать в POS» as
  that same push (`OrderPosExportController#push`, `POS_EXPORT_RESOLVE`, offered only
  where the binding declares `ORDER_EXPORT`), and the order detail's own label for it is
  «Экспорт в кассу» (`orders.detail.section.posExport`). The only paper the console
  itself can produce is the table QR card
  (`frontend/operations/src/app/shared/ui/table-print-card`, which has an
  `@media print` rule and no code that calls `print()`; the operator uses the browser's
  own menu). A customer's fiscal receipt is a link:
  `payments.notifications.FiscalCustomerReceiptTrigger` sends the OFD URL by message
  when `FiscalDocumentIssued` fires, and nothing puts it on paper. Every fact a paper
  receipt would carry already exists: `fiscal.fiscal_documents` (V0027, moved to the
  `fiscal` schema by V0039) holds `fiscal_sign`, `receipt_reference`, `terminal_id`,
  `registered_at` and `receipt_url`, and `fiscal_document_lines` the lines;
  `LegalEntityDirectory#sellerFor` (ADR 0038, V0053) answers the legal name and TIN
  for a location and a business date, and `tenant.legal_entities` holds the registered
  address and phone that `FiscalSeller` does not carry; `ordering.orders.public_order_number`
  (V0022) is "the short number a receipt and a kitchen ticket both print" (V0326's own
  words); `kitchen.tickets.sequence_label` copies it (V0030); `tenant.brand_media`
  (V0243) names a brand's logo. Settings §10.14 and gap-map row `10.14` exist as three
  cards whose own text says what is not built.
- Date proposed: 2026-10-07
- Date decided: 2026-10-07
- Deciders: proposed by Claude (batch 19); Ayubkhon Abbosov (platform owner) decides
- Depends on: [ADR 0007](../partial/0007-camel-route-foundation-and-provider-contract-testing.md),
  [ADR 0010](../partial/0010-s3-media-lifecycle-and-filesystem-migration.md),
  [ADR 0011](../partial/0011-pos-installations-bindings-and-capability-adapters.md),
  [ADR 0025](../built/0025-fine-grained-authorization-and-capability-model.md),
  [ADR 0026](../built/0026-provider-installations-bindings-and-secret-references.md),
  [ADR 0027](../built/0027-audit-evidence-and-approval-model.md),
  [ADR 0029](../partial/0029-pii-protection-envelope-encryption-and-key-rotation.md),
  [ADR 0030](../built/0030-configuration-and-policy-resolution.md),
  [ADR 0031](../built/0031-http-api-conventions.md),
  [ADR 0032](../built/0032-event-contract-governance-and-topic-policy.md),
  [ADR 0033](../built/0033-caching-rate-limiting-and-shared-runtime-state.md),
  [ADR 0038](../partial/0038-legal-entities-fiscal-receipts-and-product-classification.md),
  [ADR 0041](../partial/0041-kitchen-execution-and-production-routing.md),
  [ADR 0056](../partial/0056-tenant-isolation-enforcement-and-rls.md),
  [ADR 0058](../partial/0058-telegram-notification-channels.md),
  [ADR 0079](../partial/0079-kitchen-display-device-principal-and-enrolment.md),
  [ADR 0082](../built/0082-a-feature-flag-is-a-boolean-configuration-key.md),
  [ADR 0136](../partial/0136-composite-products-combo-groups-and-modifier-depth.md),
  [ADR 0151](../not-started/0151-a-wall-display-device-class.md)
- Supersedes / Superseded by: Supersedes the exit-criterion sentence of ADR 0041, «a branch runs
  a full service from screens with no paper tickets», only for a branch that has a printing
  route; nothing else in ADR 0041 changes. The building wave sets `Superseded by ADR 0154` on ADR
  0041 for that sentence and edits neither its argument nor its other exit criteria. (ADR 0011's
  closed port list stays closed: Decision 11 explains why no print capability is added. ADR 0038
  is extended, not changed: it owns the fiscal document and says nothing of the paper. What this
  record reopens is a declination in two documents that are not ADRs and that name it:
  `operations-spec/settings.md` §10.14 Card 3, which renders tape width and logo as a
  `LockedState` "rather than shipping fields nothing reads" until an ADR owns them, and
  the parity matrix's decline of «Self-service kiosk hardware integration (… 58/80 mm
  receipt printers …)». It reopens only the first, and only for a printer that belongs
  to a location. It does **not** reopen the kiosk-hardware decline: a printer in a kiosk
  cabinet, its certification and its field support remain declined, and ADR 0162 says so
  for the kiosk.)
- Open inputs: each is closed on its proposed default if the owner accepts the record
  as written; the ones that name a person other than the owner, or an external fact,
  stay with that person and the work they block is marked.
  - **Which printers the pilot has** (operations, the pilot tenant). Proposed default:
    ESC/POS-compatible thermal printers, 80 mm at a kitchen pass and a counter, 58 mm
    accepted, reached over the branch LAN (raw TCP 9100) or USB by the agent. Nothing
    in this record needs the answer to build: the first build proves the path against a
    fake printer, and the first real printer is a bake-off against Decision 3's
    acceptance test (a page of every glyph the three locale catalogues use). Blocks:
    nothing but the pilot's own go-live.
  - **How the agent is run and shipped** (platform owner, operations). Proposed default:
    the reference agent is run by HorecaOS support on a small always-on machine at the
    pilot branch and enrolled by the manager like any device (Decision 9); tenants do not
    install it themselves in v1; a packaged installer is a later decision. Blocks: only
    the second tenant.
  - **Which fiscal fields the paper must carry, and the legal wording around them**
    (legal, finance, the tenant's own accountant). This is an external fact and stays
    with them. Proposed default: print every evidence field the fiscal operator returned
    for the document (receipt number, fiscal sign, terminal reference, registration
    time, the seller's name and TIN) verbatim, and the QR of the stored receipt URL;
    label an order summary «not a fiscal document»; mark every copy after the first as a
    copy. Wording lives in the locale catalogue, so a legal correction is a string
    change. Blocks: step 3 of Rollout for `FISCAL_RECEIPT` `auto_print`, not the build; a
    correction after that is a release of catalogue text.
  - **The language a document prints in** (operations). Proposed default: one language
    per document, the location's own, resolved through the ADR 0030 key
    `printing.document_locale` and defaulting to `ru`; never the customer's.
  - **Whether the QR encoder is a new dependency** (engineering, platform owner).
    Proposed default: yes, `com.google.zxing:core` (Apache-2.0, pure Java), because
    `frontend/operations/src/app/shared/ui/qr-code.ts` and `qr-encode.ts` are a TypeScript
    component capped at 106 bytes (ADR 0119) and cannot run in a Java renderer.
  - **The bundled font** (engineering). Proposed default: a monospaced face with
    Cyrillic and the Latin Extended letters Uzbek uses (including U+02BB) under the SIL
    Open Font License, vendored under `src/main/resources/printing/`; the glyph test in
    the acceptance suite is what proves it.
  - **How long a stored document is kept** (platform owner, legal). A document may carry
    a customer's free-text note. Proposed default: the document is envelope-encrypted
    (ADR 0029), erased 7 days after the job reaches a terminal state and 30 days after a
    dead job, and the job row stays without it.
  - **Whether printing is sold as a module or limited by a plan** (finance). Proposed
    default: neither; `feature.printing` (ADR 0082) ships off and is switched on per
    tenant by hand; a module is a later price-list entry (ADR 0087).
  - **A courier slip, a table bill, a refund or correction receipt** (product, finance).
    Proposed default: none in this record. Each needs a decision this record cannot take
    for it: a printed delivery address is personal data (ADR 0029); a table bill needs
    ADR 0047's session bill to be final; a refund receipt needs ADR 0048.

**To accept as written:** say "accept 0154". Every open input above is then closed on
its proposed default.

**Decision record, 2026-10-07.** Accepted by Ayubkhon Abbosov (platform owner) under the standing instruction "lets finish all" given the same day, which accepts every record proposed in batch 19 (ADRs 0154–0176) on the default each open input proposes. An input that names a person other than the owner, or an external fact (a device model, a legal wording, a provider capability, a dataset publication), stays with that owner as written and implementation proceeds without it, marking what waits. Implementation starts in operations batch 20 (2026-10-07).

## Context

Gap-map row `10.14` is `NOT BUILT` with the note that "an operator cannot choose what
prints where, on what tape width, or what the customer's receipt looks like; every print
today is whatever the POS adapter happens to do", and it is blocked by "no ADR owns
printing or receipt presentation — one has to be written before any screen can be
specified". Settings §4 repeats it as the last row of «Blocks a good version of the
section»: "a print capability port on ADR 0011, and a decision that owns printing and
receipt presentation at all. Nothing owns it today." The IA keeps «print» in its list
of what the capability matrix lets operations hide (row 3.2) and in the order detail's
«Печать» action (row 1.2, suppressed when the POS lacks the capability).

**What "print" means in the code today, which is not what the three cards assume.**
Settings §10.14 Card 1 imagines «Печать через POS» as a capability a POS declares and
HorecaOS asks for. The built system has no such call. The action orders.md §4.8 names
«Печать в POS» is `OrderPosExportController#push`, shown in the console as «Экспорт в
кассу»: it re-sends an order to the till, and the till prints because it receives an
order. That has three consequences this record cannot design around.

- **A POS-bound branch already prints, and prints only what the till decides.** Clopos
  is documented in V0036 as having no idempotency key, so a repeated post is a second
  kitchen ticket. Any HorecaOS printing at such a branch is a second path to paper for
  the same order, and the platform has no way to read whether the till printed.
- **A branch with no POS prints nothing.** ADR 0041's exit criterion is "a branch runs a
  full service from screens with no paper tickets", and its rollout step 1 says a branch
  "runs the screen beside paper". The paper in that sentence is not HorecaOS's.
- **No customer ever holds a HorecaOS receipt on paper.** The fiscal receipt reaches the
  customer as a link (`FiscalCustomerReceiptTrigger`). The parity matrix names print-to-POS
  one of two capabilities "genuinely absent from the port list", and ADR 0038 "owns the
  fiscal document but not the paper" (settings §10.14's words).

**The evidence is there; the paper is not.** ADR 0038 stores the fiscal sign, receipt
reference, terminal reference, registration time and the URL as fields precisely because
the URL "points at a service Qoida does not run". It derives the document's lines from
the accepted quote snapshot so that "the receipt total must equal the order total to
the som". So a printed receipt has nothing to compute and nothing to invent, and the
only way for this record to do harm is to compute or invent something. That is the
design pressure: **a presentation layer that can reorder or hide evidence is a
compliance defect, and a layout designer is exactly that**. Settings §10.14's own
judgement ("do not build a receipt-template designer") is therefore carried into the
decision as a constraint, not a deferral.

**Why a branch cannot be left to a browser.** The table card proves the console can
make a paper artifact through the operator's own print dialog, and nothing more: a
kitchen printer has no user at its screen, a ticket that fails to print must be
retried and seen, and an order confirmed at 19:02 must not wait for someone to press a
button. A branch printer is reached from a machine inside the branch, and the platform
runs in a rented VM in the country (ADR 0073, ADR 0061), so the connection has to be
**outbound from the branch**: nothing on a restaurant's LAN is reachable from the
platform, and no tenant should be asked to open a port.

**What the platform already decided that carries most of the answer.** ADR 0079: a
non-human principal is a Keycloak service-account client with a location-scoped role,
enrolled by a pairing code, listed and revocable, and "adding a class is an enum
value and a role, never a redesign". A print agent is that principal. ADR 0041:
kitchen tickets are per station with a `sequence_label`, release modes and
amendments. ADR 0136: a combo "prints as one header line with each component as a
normal, independently routable ticket item". ADR 0027: an operator action names a
person. ADR 0029: personal data is classified and encrypted and never reaches an event,
log or metric. ADR 0030: a durable decision persists the policy version it used. ADR
0032: an in-process signal needs no catalogue entry (`FiscalDocumentIssued` is the
precedent, "no ADR 0032 catalogue entry, never appended to the outbox").

## Decision

**Add one module, `printing`, that turns facts other modules own into a single 1-bit
bitmap per document and delivers it, at least once, to a printer a branch's own agent
can reach; ship it dark, print nothing until a manager creates a route, and add no print
capability to ADR 0011.**

1. **A module that renders and delivers, and decides nothing commercial.** `printing`
   owns printers, routes, a brand's receipt profile, print jobs and the renderer. It
   computes no money, no tax and no fiscal identifier; every figure on paper is read
   from the owning module's `api` port (ordering, kitchen, payments, tenancy, media) and
   printed as stored. No other module depends on `printing`: events flow into it, never
   out of it, and `ModularArchitectureTests` gains the module with that constraint.

2. **Three documents and a test page, a closed code-owned set.** `KITCHEN_TICKET` (one
   station's items of one ADR 0041 ticket), `ORDER_SLIP` (an order's summary, never a
   fiscal document) and `FISCAL_RECEIPT` (one `ISSUED` `SALE` fiscal document, printed
   as stored), plus `TEST_PAGE`. Courier slip, table bill and refund or correction
   receipts are out (Open inputs). A tenant cannot add a type, for the reason ADR 0036
   closes its channel types: behaviour keys on the type.

3. **One renderer, one bitmap, in v1.** Every document is built as a structured
   `PrintDocument` (blocks: text, key-value, line item with children, rule, QR, an
   indivisible fiscal block, feed, cut), laid out on a fixed character-cell grid (12
   dots a cell: 32 columns on a 58 mm roll's 384 dots, 48 on an 80 mm roll's 576) in a
   bundled font, and drawn to a 1-bit bitmap. The same bitmap is the console's preview,
   the image a browser prints, and the raster band of the ESC/POS bytes the platform
   hands an agent. There is no native-text mode and no printer code page: raster
   removes the question of which glyphs a printer model can draw, and a wrong glyph on a
   fiscal evidence line is silent.

4. **Tape width is the printer's; presentation is a closed set of fields on the brand.**
   A printer row carries 58 or 80. A brand's receipt profile carries `print_logo`
   (default off) and a footer of up to three lines per locale, and nothing else. The
   seller's name and TIN, the order number, the totals, the QR and the fiscal block are
   not fields: a tenant cannot move, resize or remove them. There is no template
   designer.

5. **The fiscal block is evidence and cannot be edited by presentation.** It is one
   indivisible block below the totals and above the footer, never split across a cut,
   printing the stored receipt reference, fiscal sign, terminal reference, registration
   time (in the location's timezone) and the seller's name and TIN, followed by the QR of
   the stored `receipt_url`. A `FISCAL_RECEIPT` is created only for a document that is
   `ISSUED` and of type `SALE`; a manual request for any other state is refused naming
   the state. The platform never prints a facsimile. Every print after the first of one
   document carries a copy marker. An `ORDER_SLIP` is never titled or headed as a
   receipt: its header says it is not a fiscal document, and one line states the fiscal
   status at render time, which is the only place the word «чек» / «receipt» appears on it.

6. **What prints where is explicit, and nothing prints by default.** A printer belongs to
   a location. A route maps (location, document type, optional station) to a printer,
   with a number of copies and an `auto_print` flag. A new location has no routes and a
   document with no route prints nowhere: the order action says so. Where a location has
   an active POS binding with `ORDER_EXPORT` enabled, the routes screen says "your point
   of sale may already print this" beside the kitchen-ticket route, because the platform
   cannot read whether it does; it does not create the route for the manager.

7. **A job is a durable, leased, at-least-once row.** States `QUEUED`, `CLAIMED`,
   `PRINTED`, `HANDED_OFF` (browser path), `DEAD`, `CANCELLED`, `EXPIRED`. An agent
   claims with a lease; a lapsed lease returns the job to `QUEUED` with the attempt
   counted; six attempts over about eight minutes end in `DEAD`; a `KITCHEN_TICKET`
   expires 30 minutes after it was queued rather than printing stale; a job printed on
   a retry says so on the paper, and a job whose paper comes out more than
   `printing.late_marker_minutes` after it was queued says «delayed» with the time it was queued,
   retried or not (an offline printer stops being claimed from, so a job that only waited prints
   as attempt 1 and needs this line to say so). Both are lines drawn when the artifact is
   rendered, above the fiscal block and never inside it. A manual reprint is a new job, linked to
   the first, with a reason and the requesting person, and writes a `BUSINESS` audit fact.

8. **Jobs are created after commit and reconciled, never inside the business
   transaction.** Listeners on `OrderConfirmed`, a new ticket-released signal (kitchen has
   no `api` package today; the build adds one, with the signal and the ticket read a
   printer needs) and `FiscalDocumentIssued` create jobs `AFTER_COMMIT`, idempotently by a
   unique key; a sweeper re-derives what should have been queued and creates the missing
   job. A printing fault must never roll back an order confirmation or a fiscal issuance,
   which is why this does not copy `KitchenTicketOpener`'s `BEFORE_COMMIT`.

9. **A print agent is a device principal, and it is a byte pipe.** `DevicePrincipalClass`
   gains `PRINT_AGENT`; a new role `print-agent` (`LOCATION`, excluded from
   `TenantRoleCatalog` and `StaffMembers`) holds two capabilities, `print.job.claim` and
   `print.job.report`. The agent advertises the printers it can reach by an alias it
   chooses locally; the console binds a printer row to an advertised alias; the platform
   never sends the agent an address to dial. The agent receives bytes and a printer alias
   and reports an outcome. It holds no template, no language and no business rule.

10. **The operator can also print from the console.** A workstation prints the preview
    bitmap through the browser at the roll's physical width. It is recorded as a job in
    state `HANDED_OFF`, which is all the platform can know, with the requesting person.

11. **No print capability on ADR 0011.** «Печать в POS» keeps meaning "send to the till":
    the push stays `POS_EXPORT_RESOLVE`, gated by `ORDER_EXPORT`, and the console keeps
    calling it «Экспорт в кассу», so that nobody presses it believing a printer is
    attached. No POS vendor read so far documents a call that prints without creating an
    order, and a capability nobody can declare `SUPPORTED` is a screen that invents its
    own contents.

12. **The operator screen is Settings → Money and tax → Printing and receipts (row
    `10.14`),** whose three cards become: *Printers and routes* (replacing «Печать через
    POS», with the POS line kept as a read-only statement of what ADR 0011 says),
    *Fiscal receipt* (the read-only projection settings §10.14 already specifies, plus
    the print policy), and *Tape width and presentation* (the receipt profile). A list
    beside the cards, *Print queue*, shows failed, dead and expired jobs with reprint and
    cancel.
    Three capabilities, `print.read`, `print.manage` and `print.reprint`, and a flag,
    `feature.printing`, ship with it.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Leave printing to the POS, as today | Correct for a branch whose till prints; it leaves a branch with no POS without paper tickets and every customer without a paper fiscal receipt. This record keeps it as the default for POS-bound branches (Decision 6) | Never as the answer for a branch with no POS |
| Add `ORDER_PRINT` to ADR 0011's port list and let each POS print | The only "print" any adapter reaches is the side effect of exporting an order; adding a capability nobody can honestly declare `SUPPORTED` repeats what ADR 0011's `PREPARATION_STATUS` row warns of, a screen that invents its own contents | A POS vendor documents a print or reprint call that does not create an order, with a cited endpoint |
| A receipt-template designer | Settings §10.14: a restaurant that needs one has a POS that has one. A tenant-authored layout can hide or reorder the fiscal block, and every layout is a new thing to test in three languages on two tape widths | Three tenants ask for the same layout change in a quarter and it is not a field on the profile, or a tenant with many locations needs per-location layouts |
| Native ESC/POS text with printer code pages | Faster and smaller, and the answer for a printer with no raster mode. The code-page coverage of the pilot's printers for Russian and Uzbek Latin (U+02BB) is unknown, and a missing glyph prints as a wrong character with no error | A printer in the field cannot raster, or the pilot measures a ticket taking longer than a cook will wait |
| Print only from the browser (`window.print`) | Needs a person at a screen; no queue, no retry, no kitchen printer. Kept as the secondary path (Decision 10) | Never as the only path |
| A cloud print provider through ADR 0026 and ADR 0007 (PrintNode, Star CloudPRNT, Epson Server Direct Print) | A foreign processor of order data (ADR 0034), paid for across the border ADR 0073 calls "a real obstacle", and its agent still runs on branch hardware. CloudPRNT and Server Direct Print are printer-side polling protocols that need no agent, so the claim service is shaped to be put behind them later | A tenant buys printers that speak one of them and will not run an agent: add a transport adapter over the same claim service under ADR 0026/0007, with a fake |
| Render on the agent (templates and fonts on every branch machine) | Every layout, wording or font change becomes a release to every branch; three languages and two widths at each | Branches must print through an internet outage, which needs the agent to hold a document and a renderer |
| Push jobs to the agent over a socket | Needs a channel in ADR 0045's closed catalogue and a held connection through branch NAT; pull over outbound HTTPS needs neither | The claim latency measured at the pilot exceeds two seconds at the 95th percentile |
| Keep printers in ADR 0026 installations and bindings | A printer has no vendor account, credential or approved environment; the agent's credential is already a device principal. Putting a printer in `integration.provider_installations` makes ADR 0026's rows mean two things | Printers are reached through a provider's cloud rather than a branch agent |
| Create jobs `BEFORE_COMMIT` like `KitchenTicketOpener` | Atomic with the confirmation, which is exactly the property to avoid: a defect in printing would stop a restaurant confirming orders | Never; the sweeper (Decision 8) gives the durability |

## Consequences

### Positive

- A branch with no POS can run paper tickets, and a customer can hold the fiscal
  receipt the platform already holds, without any figure being computed twice.
- The paper cannot disagree with the evidence: lines and totals are the fiscal
  document's, the QR is the stored URL, and a document that is not `ISSUED` cannot
  print as a receipt.
- One bitmap serves the preview, the browser and the printer, so what a manager sees
  in Settings is what prints, in all three languages.
- A lost agent loses no job; a revoked one stops within a request (ADR 0079).
- No personal identity prints: none of the three documents carries a customer's name,
  phone or address.
- The next device is cheaper: a class, a role, two capabilities and an endpoint pair,
  the pattern ADR 0151 also follows.

### Negative

- A new module with four tables, a renderer, a bundled font, a new dependency (the QR
  encoder) and a headless-AWT requirement: drawing text with `java.awt` needs
  `libfreetype` and `fontconfig` in the production image, which no image in the repository
  is yet known to carry.
- HorecaOS takes on a slice of the field support the parity matrix gave as the reason to
  decline kiosk hardware: printers jam and agents die. The agent is software somebody
  must ship, update and be paged for.
- Printing depends on the branch's internet. A kitchen that loses its connection stops
  receiving paper just as it stops receiving screens; a POS-bound branch keeps printing
  through its till, which is why the POS stays the default there.
- At-least-once means occasional duplicate paper. The retry marker makes it legible and
  does not prevent it.
- A manager can turn on auto-print for a kitchen ticket at a branch whose till also
  prints and get two tickets per order. The platform can warn and cannot know.
- Raster is slower and heavier than text on a slow link (a Bluetooth printer).
- Another worklist for operators, and a manual reprint of a fiscal receipt that reads
  as a copy of an original the customer may already hold.

### Accepted trade-offs

- No designer, no per-location layout, no logo placement, no second language on one
  slip. A tenant that needs more keeps its POS's printing.
- A reprint is regenerated from the current facts and marked, not replayed from the
  stored bytes, so a reprinted kitchen ticket after an amendment shows the order as it
  is now.
- v1 prints `SALE` receipts only. A refund receipt is not printed and is not refused
  silently: the order action names it.
- The pilot's agent is run by HorecaOS support, which does not scale to a second tenant
  and is not meant to.
- The agent's reference implementation is the only transport in v1.

## Specification

### Physical model (schema `printing`; every row has `tenant_id`; new migrations take the next free number in the worktree that builds them, checked against every sibling worktree)

```text
printing.printers
  id, tenant_id, brand_id, location_id
      (tenant_id, brand_id, location_id) -> tenant.locations (tenant_id, brand_id, id)
  display_name varchar(120)                       unique (tenant_id, location_id, display_name)
  tape_width_mm smallint                          58 | 80
  agent_device_id uuid null                       (tenant_id, location_id, agent_device_id) -> iam.device_principals
                                                  (tenant_id, location_id, id) [uq_device_principal_location]:
                                                  an agent serves printers at its own location only
  agent_printer_key varchar(64) null              the alias the agent advertises; null = BROWSER
  connection_kind varchar(16)                     AGENT | BROWSER       (a CHECK ties the pair: AGENT needs both ids)
  status varchar(16)                              ACTIVE | RETIRED
  last_status varchar(16) null                    ONLINE | OFFLINE | OUT_OF_PAPER | COVER_OPEN | ERROR | UNKNOWN
  last_status_at timestamptz null
  version, created_at, updated_at                 unique (tenant_id, id); uq_printer_location (tenant_id, location_id, id)

printing.print_routes
  id, tenant_id, brand_id, location_id
      (tenant_id, brand_id, location_id) -> tenant.locations (tenant_id, brand_id, id)
  document_type varchar(20)                       KITCHEN_TICKET | ORDER_SLIP | FISCAL_RECEIPT
  station_id uuid null                            (station_id, tenant_id, location_id) -> kitchen.stations (id, tenant_id, location_id);
                                                  KITCHEN_TICKET only
  printer_id uuid                                 (tenant_id, location_id, printer_id) -> printing.printers
                                                  (tenant_id, location_id, id) [uq_printer_location]:
                                                  a route names a printer at its own location only
  copies smallint                                 1..3
  auto_print boolean                              default false
  status varchar(16)                              ACTIVE | INACTIVE
  version, created_at, updated_at
  uq_print_route_location (tenant_id, location_id, id)
  unique (tenant_id, location_id, document_type, coalesce(station_id, nil uuid), printer_id)

printing.receipt_profiles
  tenant_id, brand_id                             primary key (tenant_id, brand_id)
  print_logo boolean                              default false
  footer_texts jsonb                              {locale: [up to 3 lines]}, shape checked
  version, updated_by, updated_at

printing.print_jobs
  id (Ids.newId, ADR 0076), tenant_id, brand_id, location_id, printer_id
      (tenant_id, brand_id, location_id) -> tenant.locations (tenant_id, brand_id, id)
      (tenant_id, location_id, printer_id) -> printing.printers (tenant_id, location_id, id) [uq_printer_location]
  route_id uuid null                              null for a manual job; (tenant_id, location_id, route_id) ->
                                                  printing.print_routes (tenant_id, location_id, id) [uq_print_route_location]
  document_type varchar(20)                       adds TEST_PAGE
  source_type varchar(20)                         KITCHEN_TICKET | ORDER | FISCAL_DOCUMENT | NONE
  source_id uuid null, source_revision integer    ticket release or amendment sequence; order version; 0 otherwise
                                                  source_id names a row in another module's table and has no foreign key, by design;
                                                  the unique key below and the sweeper guard it
  copy_no smallint                                1..copies
  origin varchar(8)                               AUTO | MANUAL
  requested_by varchar(255) null, reason_code varchar(24) null
  reprint_of uuid null                            (tenant_id, reprint_of) -> printing.print_jobs (tenant_id, id) [uq_print_job_tenant_id]
  status varchar(12)
  attempt integer, next_attempt_at timestamptz, expires_at timestamptz
  lease_token uuid null, lease_expires_at timestamptz null
  claimed_by_device_id uuid null                  (tenant_id, location_id, claimed_by_device_id) -> iam.device_principals
                                                  (tenant_id, location_id, id) [uq_device_principal_location]
  failure_code varchar(24) null                   PRINTER_OFFLINE | OUT_OF_PAPER | COVER_OPEN | TRANSPORT | RENDER | LEASE_LAPSED
  locale varchar(8), tape_width_mm smallint, profile_version integer null
  protected_document text null                    FieldProtection envelope (ADR 0029); null once purged
  document_digest varchar(64)                     SHA-256 of the canonical document, kept after the purge
  payload_purge_after timestamptz
  queued_at, printed_at, version, created_at, updated_at
  uq_print_job_tenant_id (tenant_id, id)
  unique (tenant_id, location_id, printer_id, document_type, source_type, source_id,
          source_revision, copy_no)  WHERE origin = 'AUTO'
  index (printer_id, queued_at) WHERE status IN ('QUEUED', 'CLAIMED')
```

Every table has `tenant_id` in its unique and foreign keys, and every foreign key that reaches
a row bound to a location carries `location_id` too, so the database and not only the endpoint
refuses a route naming a printer at another location, a printer bound to an agent enrolled at
another location, a job whose printer, route or claiming agent is at another location, and a
reprint of another tenant's job. The constraints are named `uq_printer_location`,
`uq_print_route_location`, `uq_print_job_tenant_id`, `fk_printer_agent_device`,
`fk_print_route_printer`, `fk_print_job_printer`, `fk_print_job_route`, `fk_print_job_reprint_of`
and `fk_print_job_claimed_by` (with `fk_*_location` for each table's own location), and
`TenantScopedReferenceCatalogTests` stays green with `known_tenant_blind_references.tsv` empty.
`uq_device_principal_location (tenant_id, location_id, id)` on `iam.device_principals` is shared
with ADR 0155 and ADR 0162: the first of the three migrations to merge adds it and the others rely
on it (an earlier migration cannot rely on a constraint a later one adds, which is why this one
is carried by the first and not by the last as the class CHECKs below are). Each table also has a
`GRANT SELECT, INSERT, UPDATE` to `horecaos_application` (`printing.print_routes` also `DELETE`; a
job is never deleted, its payload is nulled), and row-level security from the first migration through
`platform.enable_tenant_row_level_security` (ADR 0056): a new schema has no cross-tenant
reader to retrofit, and the reconciler and purge job bind platform scope explicitly. The
migration also seeds nothing; `DatabasePrivilegeTests` and `RowLevelSecurityBackstopTests`
name the schema. The CHECK that `connection_kind = 'AGENT'` implies both agent columns, and
that a `FISCAL_RECEIPT` job has `source_type = 'FISCAL_DOCUMENT'`, are database facts, not
service conventions. Device classes are restated in `ck_device_principal_class` and
`ck_device_enrolment_class` with every value in force on `main` when the migration is
written (ADR 0151 adds `KITCHEN_VDU`, ADR 0162 adds `KIOSK`): the last of the three to merge
carries all of them, and the migration test asserts the whole list.

### The document and the grid

`PrintDocument` is versioned JSON: `{type, locale, tapeWidthMm, copyMarker, blocks[]}` with
money as `{amountMinor, currency}` and every instant as UTC formatted in the location's
IANA timezone by the renderer. The retry and delayed lines are render inputs (the job's attempt,
its `queued_at` and the clock when the artifact is requested), never part of the stored document or
of its digest. Text is placed on the cell grid (12 dots a cell, 24-dot glyphs), wrapped on cells, never on pixels, so a 58 mm and an 80 mm roll differ only in
column count. A combo is a header line with its components indented beneath it (ADR 0136);
on a `KITCHEN_TICKET` only the components routed to that station appear under the header.
Totals and VAT are printed from the fiscal or order facts and a render-time check that they
sum is logged, never corrected. Fonts and bitmaps are drawn with `java.awt` in headless
mode from a vendored font; the QR is encoded with ZXing core. The logo, when the profile
asks, is the brand's `LOGO` asset (`tenant.brand_media`) thresholded to 1 bit at the roll's
width and cached by asset id and width as an ADR 0033 registered accelerator.

| Document | Source and content (nothing else) |
|---|---|
| `KITCHEN_TICKET` | The station's `kitchen.ticket_items` of one ticket: `sequence_label` large, fulfilment mode, channel code, table label where ADR 0047 has a session, station name, release time, target-ready time when promised, quantity and name per line with modifiers and the line's coded presets and free-text note, amendment marker. No customer name, phone or address |
| `ORDER_SLIP` | Location name, address and phone; the seller's legal name and TIN for the location and business date; order number; lines and totals; the tenders and, for cash, tendered and change due; one line of fiscal status. Header: «not a fiscal document». No customer identity |
| `FISCAL_RECEIPT` | One `ISSUED` `SALE` document: seller, lines from `fiscal_document_lines` as stored, VAT, total, the fiscal block, the QR of `receipt_url`, the footer |
| `TEST_PAGE` | Roll width, a ruler, every glyph of the locale catalogues, a QR, the printer's name and the agent's build |

### Routing and triggers

| Signal | Source | Creates |
|---|---|---|
| `OrderConfirmed` | `ordering.api` | `ORDER_SLIP` per route with `auto_print`. The trigger is fixed by the document type, not a column on the route: a slip prints when the order is confirmed, a ticket when it is released, a receipt when its fiscal document is issued |
| ticket released (a new in-process signal in a new `kitchen.api` package, no ADR 0032 entry) | kitchen, from `KitchenTicketService` and `KitchenReleaseWorker` | `KITCHEN_TICKET` per station route; a station with no route falls back to a route with a null station; a preorder prints at its own release instant, never at confirmation (the ADR 0041 rule that stopped a 20:00 order printing at 11:00) |
| kitchen amendment | `KitchenAmendmentListener`'s ticket event (V0477) | an amendment `KITCHEN_TICKET` with `source_revision` raised, carrying the kitchen's own amendment fact |
| `FiscalDocumentIssued` | `payments.api` | `FISCAL_RECEIPT` per route with `auto_print`; the evidence is read through the port `FiscalCustomerReceiptTrigger` uses, never the table |

The sweeper runs every minute, scans a bounded window (kitchen tickets released and fiscal
documents issued in the last two hours), and for each (route, source) lacking a job
inserts one under the unique key. `expires_at` for `KITCHEN_TICKET` is queue time plus
`printing.kitchen_ticket.expire_minutes`; documents of the other types do not expire for 24
hours.

### Job lifecycle and the agent contract

```text
QUEUED --claim--> CLAIMED --result PRINTED--> PRINTED
   ^                 |  \--result FAILED, attempt < 6--> QUEUED (next_attempt_at: +5 s, +15 s, +45 s, +2 min, +5 min)
   |                 |  \--result FAILED, attempt = 6--> DEAD
   +--lease lapses---+      QUEUED past expires_at --> EXPIRED      QUEUED --cancel--> CANCELLED
```

Per printer, jobs are claimed one at a time in `queued_at` order, so a station's tickets keep
their order. The agent calls, all under the agent's own `LOCATION` grant and all subject to
`ResourceScopeVerifier`:

```text
POST /api/v1/tenants/{t}/brands/{b}/locations/{l}/print/claims                print.job.claim   Idempotency-Key
     body  { printers: [{ key, status }], maxJobs }       long-poll up to 20 s
     reply { jobs: [{ jobId, printerKey, documentType, leaseToken, leaseExpiresAt }] }
     the reply first repeats this agent's own unexpired leases, so a retry after a lost reply returns what was claimed;
     the stored idempotent reply holds identifiers and 60-second lease tokens, never an artifact
GET  .../print/jobs/{jobId}/artifact?lease=...                                print.job.claim
     reply the ESC/POS bytes for the bound printer's width, rendered now from the stored document
POST .../print/jobs/{jobId}/results                                           print.job.report  Idempotency-Key
     body  { leaseToken, outcome: PRINTED | FAILED, failureCode }
     a stale or foreign lease token is refused 409 and changes nothing
```

The lease is 60 seconds. `claims` upserts the advertised printers' statuses, so a printer
that reports `OFFLINE` stops being claimed from and is raised as an operations alert
(`PRINTER_OFFLINE`, through ADR 0058's `OperationsAlertPort`) when a `KITCHEN_TICKET` is waiting on
it for two minutes; a job that ends `DEAD` raises `PRINT_JOB_DEAD` the same way.
The agent's configuration (which printers it may dial, by local alias) lives with the agent,
never in a platform response: the platform cannot make a branch machine open a connection to
an address a manager typed. The reference agent (`tools/print-agent`, TypeScript) speaks raw
TCP and a local spool, reads status with `DLE EOT`, and has no rendering code. A
`FakePrintAgent` (in-process, implements the protocol) and a `FakePrinter` (a TCP listener
that records the bytes and decodes the raster) are test fixtures and a `make up` service, so
every test above the wire runs without hardware.

### Console endpoints (ADR 0031: Problem Details with stable codes, expected version on aggregate writes, cursor pagination, `Idempotency-Key` on effectful mutations)

```text
GET    .../locations/{l}/print/printers                          print.read     LOCATION
POST   .../locations/{l}/print/printers                          print.manage   LOCATION   { name, tapeWidthMm, agentDeviceId, agentPrinterKey | browser }
PUT    .../locations/{l}/print/printers/{id}                      print.manage   If-Match
POST   .../locations/{l}/print/printers/{id}/test-page           print.reprint
GET    .../locations/{l}/print/routes     POST/PUT/DELETE        print.read / print.manage
GET    .../brands/{b}/print/receipt-profile   PUT                print.read / print.manage   BRAND scope, If-Match
GET    .../locations/{l}/print/jobs?status=&cursor=              print.read
POST   .../locations/{l}/orders/{orderId}/print-jobs                print.reprint  LOCATION   { documentType, printerId?, reasonCode }
POST   .../locations/{l}/print/jobs/{id}/cancel                  print.manage
POST   .../print/documents/preview                               print.read     returns the PNG of the document the job would print
GET    .../locations/{l}/print/agents                            print.read     device principals of class PRINT_AGENT and the printers each advertises
```

The agent is enrolled through ADR 0079's pairing handshake with `deviceClass = PRINT_AGENT`
and approved from the Printing screen by a holder of `print.manage` at the location, the
role `print-agent` granted exactly as `KitchenDeviceService` grants `kitchen-device`.
Capabilities are new: `print.read` (location, brand and tenant managers), `print.manage`
(`LOCATION_MANAGER`, `TENANT_ADMIN`, `TENANT_OWNER`; the profile endpoints need it at `BRAND`
scope, which a location manager does not hold), `print.reprint` (also `LOCATION_STAFF` and
`BRAND_MANAGER`: a cashier reprints a receipt), and the machine pair `print.job.claim` and
`print.job.report`, held by `print-agent` only. The order detail gains one action, «Печать»
(this record), beside the existing «Экспорт в кассу» section, which is unchanged.

### Policy keys (ADR 0030) and the flag (ADR 0082)

`feature.printing` (boolean, off). `printing.document_locale` (string, location scope,
default `ru`). `printing.kitchen_ticket.expire_minutes` (integer, default 30, range 5–240).
`printing.late_marker_minutes` (integer, location scope, default 5, range 1–1440).
A job persists the resolved locale, the receipt profile's version, the roll width and the
render protocol it used, so a reprint explains itself.

### Events, alerts and metrics

No Kafka event is published. The signals above are in-process (ADR 0032's
`FiscalDocumentIssued` precedent), and the two things an operator watches, a printer
offline and a dead job, are ADR 0058 operations alerts through `OperationsAlertPort`, under the
classes `PRINTER_OFFLINE` and `PRINT_JOB_DEAD`. If a consumer appears, the catalogue entry and
schema precede the producer. Metrics carry only outcome
and document type (never a tenant's order, a name or a figure): `horecaos.printing.jobs`
by terminal state, `horecaos.printing.claim.latency`, `horecaos.printing.render.seconds`,
`horecaos.printing.enqueue.failures`.

`fanOut` takes an `eventClass`, and it reaches only chats subscribed to a class in the closed
`TelegramEventClass` set, backed by `integration.telegram_binding_events`'s
`ck_telegram_binding_event_class` (last widened by V0488). Neither «printer offline» nor «dead
job» is in it today, so an alert raised under either would reach no one («silent on no
subscriber»). The build therefore adds `PRINTER_OFFLINE` and `PRINT_JOB_DEAD` to
`TelegramEventClass` (with their labels) and restates the full `ck_telegram_binding_event_class`
list in a migration, carrying every value in force forward as V0488 does; each class is its own
semantic template key, whose wording a tenant authors as for `MARKETPLACE_CHANNEL_STALE`. ADR 0155
widens the same list (`TERMINAL_CREDENTIAL_LOCKED`, `TERMINAL_DEVICE_UNSEEN`): the last of the two
migrations to merge carries every value in force. The idempotency key base names the printer or
job and the episode (the status change's `last_status_at`, the job id), so a replayed trigger
reaches a chat once and a later outage alerts again.

### Personal data, audit and security

No document carries a customer's name, phone or address, and the document is stored
envelope-encrypted anyway because a line's free-text note can hold anything a customer
typed (ADR 0029); it never reaches a log, event or metric, and is erased on the schedule in
Open inputs. Audit (ADR 0027): `printing.printer.registered|changed|retired`,
`printing.route.changed`, `printing.profile.changed` (through `ChangeDocuments.diff`, the
footer text redacted to its length) and `printing.job.reprinted` (`BUSINESS`, the person, the
order, the reason code), the person resolved through `StaffDirectory`. An automatic job has
no audit fact per job; its row names the route and the triggering fact. A job's artifact is
readable only with its live lease token and by the agent that holds it.

### Testing (each seen failing first)

- A `FISCAL_RECEIPT` for a document in any state but `ISSUED`, or of type `REFUND`, is
  refused; the auto path creates nothing.
- The bitmap of a `FISCAL_RECEIPT` decodes (test-only ZXing reader) to exactly the stored
  `receipt_url`, and the receipt reference and fiscal sign render as stored.
- An `ORDER_SLIP`'s header never says receipt or «чек» in any locale and says «not a fiscal
  document»; its fiscal-status line is the only place either word appears.
- A station's ticket carries none of another station's lines; a combo prints one header and
  only this station's components.
- Glyph test: every string the three catalogues use on a document, and U+02BB, renders with no
  missing-glyph box.
- The same `OrderConfirmed` delivered twice, and the sweeper after a lost listener, each
  produce one job per route.
- A lapsed lease returns the job to `QUEUED` and the next print is marked as a retry; a
  result with a stale token is refused and changes nothing; six failures end `DEAD`; a
  kitchen ticket past its expiry becomes `EXPIRED` and is never claimed.
- A job that waited behind an offline printer for longer than `printing.late_marker_minutes` and
  printed on its first attempt carries the «delayed» line with its queue time and no retry line
  (the clock advanced, not an instant asserted); one printed inside the bound carries neither; a
  retried and delayed job carries both; neither line is inside the fiscal block, and the stored
  document's digest is the same with and without them.
- An agent at location A is refused at location B and at another tenant, at the endpoint
  (`ResourceScopeVerifier`).
- The database, in a migration test, refuses: a route at location A naming a printer at B; a printer
  at A bound to an agent enrolled at B; a job at A naming a printer, a route or a claiming agent at
  B; a job whose `reprint_of` is another tenant's job. Each is seen failing first against keys that
  carry `tenant_id` only. `TenantScopedReferenceCatalogTests` stays green with
  `known_tenant_blind_references.tsv` empty.
- A chat subscribed to `PRINTER_OFFLINE` and one subscribed to `PRINT_JOB_DEAD` each receive that
  alert once; a chat subscribed to neither receives nothing; every class in `TelegramEventClass` is
  admitted by the restated CHECK.
- No stored document contains the order's customer name, phone or address (the order is
  seeded with all three).
- End to end against `FakePrinter`: claim, artifact, bytes received, raster decoded equals
  the preview bitmap.
- A revoked agent's next claim is refused; `EndpointCapabilityDeclarationTests`,
  `OpenApiContractTests`, `DatabasePrivilegeTests`, `ModularArchitectureTests` and
  `EventCatalogCompletenessTests` pass with the module present.

## Rollout and rollback

Ship dark: `feature.printing` is off, no route exists, nothing prints. Step 1 is the
console path alone (test page and reprint of a slip through the browser at a workstation, no
agent), which proves the renderer on a real roll. Step 2 is one agent and one LAN printer
at the pilot branch with a manually created `KITCHEN_TICKET` route and `auto_print` off,
used by hand beside the screens. Step 3 turns `auto_print` on for one route at a time,
kitchen first, and `FISCAL_RECEIPT` only after the legal wording (Open input 3) is confirmed.
Rollback is, in order of bluntness: set a route inactive, turn the flag off for the tenant, revoke the agent. The
tables are inert without routes, and no other module depends on this one.

## Implementation checklist

- [ ] Owner accepts the record; the pilot's printer model is asked for and tracked, not waited
      on; the legal wording is asked for and gates only step 3 of Rollout for `FISCAL_RECEIPT`
      `auto_print`, not the build.
- [ ] Migrations: `printing` schema, four tables, grants, RLS, indexes and CHECKs, and the
      location-carrying keys (`uq_printer_location`, `uq_print_route_location`,
      `uq_print_job_tenant_id`, the foreign keys named in the Model); `uq_device_principal_location`
      unless ADR 0155 or ADR 0162 already carries it; the two device-class CHECKs restated with
      every class in force; `ck_telegram_binding_event_class` restated with `PRINTER_OFFLINE` and
      `PRINT_JOB_DEAD`; the next free number checked in every worktree.
- [ ] `DevicePrincipalClass.PRINT_AGENT`, `PlatformRole.PRINT_AGENT` (excluded in
      `TenantRoleCatalog` and `StaffMembers`), five capabilities in `Capability`, bundle
      changes and the invariant tests.
- [ ] `printing` module: `PrintDocument`, the layout grid, the renderer, ESC/POS framing,
      ZXing, the vendored font, the logo thresholder and its registered cache.
- [ ] Document builders over ordering, kitchen, payments, tenancy and media ports; a new
      `kitchen.api` package with the ticket-released signal and the ticket read; the seller's address and phone added to the
      seller read (not read from the table).
- [ ] Listeners, sweeper, purge job, state machine, claim, artifact and result endpoints;
      the idempotent unique key; the retry and delayed lines; the two `TelegramEventClass` values
      and their alert callers.
- [ ] Console endpoints, audit facts, flag and policy keys, the five OpenAPI baselines, the
      generated client.
- [ ] Settings → Printing and receipts (three cards and the queue), the order «Печать»
      action beside «Экспорт в кассу», ru / uz-Latn / en strings and the key-parity spec; the
      IA gets a row for the screen; settings.md §10.14 and the gap-map row are updated by
      the wave that builds, not by this record.
- [ ] `tools/print-agent`, `FakePrintAgent`, `FakePrinter` and the `make up` service; the
      production image carries `libfreetype` and `fontconfig`, proved by a render in CI.
- [ ] A runbook: enrol an agent, replace a printer, read a dead job.
- [ ] In the building wave, set `Superseded by ADR 0154` on ADR 0041 for its «no paper tickets» exit
      sentence and advance ADR 0038's Implementation status line (the paper half exists); do not edit
      either record's argument. This record edits neither.
- [ ] Tests listed above, each seen failing first.

## Exit criteria

At a pilot branch with an agent and one printer, a manager creates a kitchen-ticket route
for the grill and an `ORDER_SLIP`/`FISCAL_RECEIPT` route for the counter; a confirmed order
prints its grill ticket once, in order, within seconds; the agent is unplugged for forty
minutes, the tickets queued in its first ten are expired rather than printed late, and a receipt
queued in the outage prints on reconnection marked «delayed» with the time it was queued; a cash
sale at the counter prints a receipt whose fiscal sign, receipt number and QR are those the
fiscal operator returned and whose QR a phone opens to that receipt; a reprint says it is a
copy and names, in the activity log, the person who asked and why; no printed page names a
customer; and revoking the agent from the screen stops it within a request without signing
anyone out.

## References

- ADR 0007, ADR 0010 (media), ADR 0011 (POS capabilities, the Clopos rows), ADR 0025, ADR 0026,
  ADR 0027, ADR 0029, ADR 0030, ADR 0031, ADR 0032 (in-process signals), ADR 0033 (registered
  accelerators), ADR 0034 (processors), ADR 0038 (fiscal documents, terminals, seller
  resolution), ADR 0041 (tickets, stations, release modes), ADR 0047, ADR 0048, ADR 0056, ADR 0058
  (`OperationsAlertPort`, the Telegram event classes), ADR 0061, ADR 0073, ADR 0076, ADR 0079,
  ADR 0082, ADR 0087, ADR 0119 (the QR component and its cap), ADR 0136 (combos on a ticket),
  ADR 0151 (the device self-read)
- `platform/docs/operations-gap-map.md` rows `10.14`, `3.2`, `1.2`, `X.36`;
  `platform/docs/operations-spec/settings.md` §10.14 and §4;
  `platform/docs/operations-spec/orders.md` §4.8; `platform/docs/delever-parity-matrix.md`
  (print-to-POS, kiosk hardware); `platform/docs/frontend-information-architecture.md`
  rows `1.2`, `3.2`, `10.5`
- `PosCapability`, `PosOrderExportService`, `OrderPosExportController`,
  `PosProviderCapabilityCatalog`, `V0036`; `FiscalDocumentIssued`,
  `FiscalCustomerReceiptTrigger`, `PaymentFiscalService`, `FiscalDocument`, `V0027`, `V0039`;
  `LegalEntityDirectory`, `FiscalSeller`, `V0053`; `KitchenTicketOpener`,
  `KitchenTicketService`, `KitchenReleaseWorker`, `KitchenAmendmentListener`, `V0030`,
  `V0477`; `DevicePrincipalClass`, `DeviceEnrolmentPort`, `KitchenDeviceService`,
  `TenantRoleCatalog`, `StaffMembers`, `V0192`; `tenant.brand_media` (`V0243`); `V0022`,
  `V0326`; `ConfigurationKeys`; `EndpointCapabilityDeclarationTests`; `OperationsAlertPort`,
  `TelegramEventClass`, `V0488`; `TenantScopedReferenceCatalogTests`
- `frontend/operations/src/app/shared/ui/table-print-card/`,
  `frontend/operations/src/app/shared/ui/qr-code.ts`, `qr-encode.ts`,
  `frontend/operations/src/app/features/settings/settings-nav.ts`

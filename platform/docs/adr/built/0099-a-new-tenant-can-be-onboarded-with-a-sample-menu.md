# ADR 0099: A new tenant can be onboarded with a sample menu it did not write

- Decision status: Proposed
- Implementation status: Built — `SAMPLE_MENU_PUBLISH` exists as decided:
  optional, `CONFIGURING`, sequence 4, materialised `SKIPPED`/`NOT_REQUESTED`
  when a run did not ask for it. The handler is
  `OrderingOnboardingStepHandlers.SampleMenuPublish`, over
  `catalog.api.SampleMenuPort`, `pricing.api.SampleMenuPricingPort` and
  `inventory.api.StockListingPort`; the control plane's start panel carries
  the checkbox, default on for a tenant's first run and off for a restart over
  an ended one. A tenant that authored nothing reaches
  `CATALOG_READINESS_VALIDATE` and `ACTIVATION_SMOKE_TEST` `COMPLETED` and its
  menu is readable through the anonymous storefront query — asserted, not
  assumed, in `SampleMenuPublishStepTests` and, run end to end through the real
  workflow, in `OnboardingFullRunIntegrationTests`. Two limits are real and
  deliberate: the sample is **UZS-only** (a tenant trading in anything else is
  refused `SAMPLE_MENU_UNSUPPORTED_CURRENCY`, because nothing converts its
  hard-coded som amounts), and there is still no way to **remove** the sample —
  this record scopes that out, and the tenant publishing its own catalog to the
  same channel is the only thing that retires the publication, while the brand's
  `UZ` tax profile the step wrote stays live. The sample covers the tenant's
  first brand only, so a tenant with two brands still fails catalogue readiness
  on the second.
- Date proposed: 2026-09-11
- Date decided: —
- Deciders: proposed by Claude and built on the platform owner's instruction of 2026-09-11 ("while onboarding a new tenant ask whether to create a sample menu and publish it to see that all works on our side, later tenant can make their own menu when they fully ready"); Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0008, ADR 0016, ADR 0017, ADR 0018, ADR 0021, ADR 0036, ADR 0038, ADR 0060
- Supersedes / Superseded by: —
- Open inputs: whether the sample items are the right ones to show a prospective
  Uzbek café or restaurant owner, and whether their prices should look current
  (platform owner); whether the sample menu should ever be shown to a real
  customer on a live storefront domain, or only reached by the tenant's own
  staff and the platform's verification (platform owner)

## Context

A tenant onboarded today reaches `READY` only if a human has already authored a
menu. `CATALOG_READINESS_VALIDATE` requires a `PUBLISHED` publication on the
storefront channel with at least one `AVAILABLE` offering; `ACTIVATION_SMOKE_TEST`
requires that a representative item of that menu prices cleanly through
`CartPricingPort` **and** is actually sellable — an `inventory.stock_items` row
that is not sold out. Those two checks are the platform's own proof that a new
tenant works end to end, and neither can run before the tenant has done a day's
data entry in a console it has not been trained on yet.

That ordering is backwards for the moment it matters most. The platform wants to
prove, on its own side, that a freshly provisioned tenant can serve a menu, price
a cart and answer the public storefront — while the tenant is still deciding what
its own menu will be. `tools/seed-horecaos-tenant` already does exactly this for
HorecaOS's own tenant, over HTTP, from a checked-in data file of fourteen
realistic Uzbek café items in three locales. It is a shell script an operator
runs by hand against one named tenant; nothing in the product offers it to the
next tenant.

The constraint that makes the shape non-obvious is module structure, not
product. A sample menu has to write `catalog` (catalog, categories, products,
variants, translations, location offerings, publication), `pricing` (a price
book, a price per variant, a tax profile, activation) and `inventory` (a stock
item per variant per location). `tenancy`, where eleven of the twelve ADR 0008
handlers live, can reach none of those three without closing a module cycle —
which is why `ACTIVATION_SMOKE_TEST` already lives in `ordering` instead
(`OrderingOnboardingStepHandlers`, see its class javadoc).

## Decision

Starting an onboarding run takes a boolean, `sampleMenu`. When it is true, one
new step — `SAMPLE_MENU_PUBLISH`, sequence 4, `CONFIGURING`, immediately after
`DEFAULT_CONFIGURATION_APPLY` and before every validation — creates a clearly
marked sample menu for the tenant's first brand, prices it in the tenant's own
currency (which the step requires to be `UZS`, because the amounts are som and
nothing converts them — see the specification), stocks it at every location the
tenant has, and publishes it to the `STOREFRONT` channel. `CATALOG_READINESS_VALIDATE` and `ACTIVATION_SMOKE_TEST`
then pass with nobody having authored anything.

The step is **not** `requiredInV1`, and a run that did not ask for it still
materialises it — as `SKIPPED`, with the reason on the row — rather than
omitting it. ADR 0008 already settled this argument for `BLOCKED` steps in
`OnboardingService.startRun`: "a template that silently skips a check reads
exactly like one that passed it". A run whose step list is missing a step
entirely is a run whose reader cannot tell that the choice was ever offered. A
`SKIPPED` row says which of the two runs this is, in the same table, to every
reader — the console, the API, and support three months later.

The handler lives in `ordering.application.onboarding`, beside
`ACTIVATION_SMOKE_TEST` and for the same reason: `ordering` already depends on
`catalog.api`, `pricing.api`, `inventory.api` and `tenancy.api`, and nothing
depends on `ordering` that any of those four do. Placing it there adds no module
edge at all. It orchestrates three narrow ports, each owned by the module that
owns the data:

- `catalog.api.SampleMenuPort` — the sample content itself (the items, their
  three locales, their prices), the draft it writes, and the publication.
- `pricing.api.SampleMenuPricingPort` — the price book, the prices, the tax
  profile, the activation.
- `inventory.api.StockListingPort` — one `BINARY` stock item per variant per
  location, which starts available.

The sample is marked as a sample in the data, not only in a comment: catalog
code `SAMPLE-MENU`, name "Sample menu", every category code prefixed `SAMPLE-`
and every product code and SKU prefixed `SAMPLE-`. That prefix is also the
idempotency key: a retried step finds the catalog by `(tenant_id, brand_id,
'SAMPLE-MENU')` — the table's own unique constraint — and adds nothing.

The step records ids and counts in `result_snapshot` and nothing else.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| No step; a "create sample menu" button in the operations console the tenant presses after activation | The tenant cannot activate without a published menu, so the button would sit behind a door it is the key to. It also leaves the platform's own end-to-end proof dependent on somebody pressing something | The catalogue readiness check stops being required for activation, at which point a post-activation sample is a genuine convenience rather than an unblocking |
| Seed the sample menu from a Flyway migration, the way V0098 seeds the default onboarding template | A migration would create this data in every database the platform ever starts — every testcontainer, every developer laptop, staging — for tenants that do not exist yet, and it cannot know a tenant's brand, locations or currency, none of which exist at migration time. `tools/seed-horecaos-tenant`'s own header already refuses this for the same reasons | Never |
| Extend `tools/seed-horecaos-tenant` to take any tenant slug and call it during onboarding | A shell script driving HTTP with a platform-admin token is an operator tool, not a step in a resumable workflow: no lease, no attempt count, no `result_snapshot`, no way for the console to show what it did, and it would need a credential the scheduler does not have | Never for the workflow; the tool stays what it is for HorecaOS's own tenant |
| Put the handler in `tenancy` beside the other eleven, reading and writing `catalog`, `pricing` and `inventory` tables through raw SQL, as five of those eleven already read sibling schemas | Those five only ever `SELECT`. Writing a price book through raw SQL means reimplementing `PriceAuthoringService`'s guards — the ties-with-a-live-book check, the "prices nothing" check, the activation compare-and-set — in a second place that will drift from the first. Reading another module's schema is a boundary compromise; writing it is a second implementation of its rules | Never |
| Put the handler in `catalog`, with `pricing` and `inventory` implementing ports that `catalog` declares (the `VariantPricingLookup` pattern) | Works for `pricing`, which already depends on `catalog`. For `inventory` it adds a new `inventory -> catalog` edge that exists for nothing but this one step, and it puts an orchestration that ends in a publication inside the module whose publication it is — the sequencing (draft, price, stock, publish) is not catalog's business | `inventory` acquires a real reason to depend on `catalog` for something else |
| Materialise `SAMPLE_MENU_PUBLISH` only when the run asked for it | Cheaper, and wrong for the reason ADR 0008 already gives about blocked steps: a missing row and a declined option are indistinguishable to every reader of the run | Never |
| Make the step `requiredInV1` when `sampleMenu` is true | `required` is a property of the step catalogue, not of one run, and `outstandingRequiredSteps` is what gates `READY`. A failed sample menu would then block activation for a tenant that has since authored a real menu — punishing the tenant for having accepted help | The step catalogue becomes per-template data rather than an enum |
| Convert the sample's som amounts into the tenant's currency, or scale them by the currency's ISO 4217 exponent | There is no rate to convert at, and scaling by the exponent is a no-op for the two markets it would be meant to fix — `UZS`, `KZT` and `GEL` all report two fraction digits, while this platform stores whole som. That is the exact reasoning both money modules document as having shipped a 100× bug. Refusing a currency the sample is not authored in is honest; a converted menu is a wrong menu nobody would notice | A per-market price-and-rate table exists, which is when `KZ` or `GE` actually onboards |
| Re-assert every sample offering `AVAILABLE` on every attempt, as first built | It undid an operator's own decision with no audit fact and nothing in the run record to say so, and it disagreed with the sibling stock port, which had always refused to re-list a sold-out item. Creating only what is missing keeps the reason the re-assert existed (a location added between two runs) and drops the part that overwrote somebody | Never |
| Default `sampleMenu` to true on the server when the field is absent | A caller that predates this field — `tools/seed-horecaos-tenant`, `tools/proving-run`, any integration — would silently start creating sample catalogs in tenants that already have real menus. Absent means false; the console is what defaults the checkbox to on | Never |

## Consequences

### Positive

- A tenant can be provisioned, validated and activated end to end on the day it
  is created, and the platform can see its own storefront answer with a real
  menu before the tenant has authored one.
- `CATALOG_READINESS_VALIDATE` and `ACTIVATION_SMOKE_TEST` become checks a new
  tenant passes rather than a wall it hits, without either check being weakened:
  both still require exactly what they required before.
- The sample content has one home — `catalog` — shared in spirit with
  `tools/seed-data/horecaos-tenant.json`, which is where the items, the three
  locales and the price points come from rather than being invented twice.
- A run's step list says, for every tenant, whether a sample menu was offered
  and what happened to it.

### Negative

- A tenant that activates on the sample menu is live, on a public storefront
  endpoint, with a menu it did not write. Nothing in this decision stops a real
  customer ordering "Sample Tashkent plov" if the tenant's storefront domain is
  already reachable. The mitigation is the naming — every code, SKU and name
  carries "Sample" — and the fact that a tenant's domain is normally published
  after activation, not before. It is a real exposure, and it is listed as an
  open input rather than argued away.
- **Removing the sample menu is out of scope.** `catalog` has no unpublish and
  no archive action: `CatalogPublicationController` offers publish, validate,
  history and rollback, and nothing else. What a tenant actually does is publish
  its own catalog to the same channel, which retires the sample publication —
  `CatalogPublicationService.publish` calls `retireActivePublication` before it
  activates the new one, and the partial unique index permits one `PUBLISHED`
  row per brand and channel, so replacement is automatic and total. What is left
  behind is the sample catalog's own draft rows (catalog, categories, products,
  variants, translations, offerings, prices, stock items), visible in the
  operations console as a second catalog named "Sample menu". A dedicated
  "remove the sample menu" action — archive the catalog, delete its offerings and
  stock items, end its price book's window — is a separate change this ADR does
  not make.
- One more thing can fail during onboarding, in a phase that previously only
  applied configuration. A tenant that asked for a sample menu and whose brand
  does not exist yet now fails at step 4 rather than step 5.
- The sample price book competes for scope with the tenant's own. It is assigned
  at `BRAND` scope with priority 0, so any book the tenant later activates at a
  higher priority wins; a tenant that activates its own book at priority 0 will
  be refused by `tiesWithALivePriceBook` and told to give one a higher priority.
  That refusal is correct and it is also a surprise a tenant that never asked for
  a price book may not expect. **It runs in the other direction too**, which is
  the direction the step actually meets: a tenant that already had a live
  `BRAND`-scope book at priority 0 — which is what the API writes when a create
  request simply omits `priority` — makes the *sample's* activation the one
  refused, and the step fails `SAMPLE_PRICING_REFUSED` rather than retrying a
  permanent condition five times.
- **The tax profile outlives the sample.** This step is the only thing on the
  platform that creates one, and nothing retires it: when the tenant publishes
  its own catalog the sample publication is retired automatically, but the
  brand-scoped `UZ`/`INCLUSIVE`/1200 profile stays live and every later quote for
  that brand extracts tax at that rate. For a UZS tenant — the only kind this
  step now serves — that rate is Uzbekistan's standard VAT and is what the
  tenant would have had to set anyway; the surprise is that it was set *for*
  them, permanently, and that no console screen offers to change it (the API's
  `PUT .../tax-profiles/{jurisdictionCode}` is the only way). The jurisdiction is
  the literal `UZ` because `QuoteService.DEFAULT_JURISDICTION` is the only
  jurisdiction any quote resolves against; a non-UZ market needs `QuoteService`
  changed, not this step.
- **A failed optional step is invisible to the stall alert.** ADR 0008's
  stalled-run gauge and ADR 0058's stuck-run listing now count only `required`
  steps, which is what makes their own prose ("a required step has exhausted its
  attempts") true of their SQL. Before this step every row but `TENANT_ACTIVATE`
  was required, so the clause was invisible; `SAMPLE_MENU_PUBLISH` is the first
  step that can be `FAILED` on a run that is nonetheless `READY` and correct, and
  without the clause one such tenant pins a platform-wide maximum forever and
  masks every genuine stall behind it. What that gives up, deliberately: a failed
  or permanently-pending sample menu raises nothing, and the run-detail view in
  the control plane is the only place an operator learns about it. That is the
  right trade — the alert answers "has onboarding stopped", and a
  declined-or-broken offer has not stopped it — but it is a decision, not a side
  effect, which is why it is written here.
- **`resume` refuses a run that has finished.** A `FAILED` optional step can sit
  on a `READY` run, and reopening it there would flip the row to `PENDING`, erase
  the failure the operator was looking at, and report work that nothing would
  ever do — `dueRuns` excludes a `READY` run and `refreshRunStatus` cannot pull
  one back, because a non-required failure is invisible to it. `resume` now
  refuses with a conflict instead, saying so. The cost is that a sample menu that
  failed on a run which has since reached `READY` cannot be retried at all; the
  tenant authors its own menu, which is what it was going to do anyway.
- **The console's checkbox defaults off for a restart.** The start panel is also
  shown over a `CANCELLED` or `FAILED` run, and a tenant on its second run has
  usually spent the time in between authoring something. The server's own decline
  only sees a *published* menu, so a draft the owner is mid-way through is
  invisible to it — default-on there would publish a sample over a tenant that is
  nearly ready, silently. Default-on stands for a tenant's first run, which is
  what the decision above is about.

### Accepted trade-offs

- Three new ports exist for one step. Each is narrow (one or two methods) and
  each is owned by the module that owns the data, which is the same trade
  `StockAvailabilityPort` already made for one `/86` command.
- The sample prices are hard-coded in Java rather than read from
  `tools/seed-data/horecaos-tenant.json`. Reading a file under `tools/` from
  application code would make a developer tool a production dependency; the
  duplication is fourteen numbers that nothing computes from each other.

## Specification

### The step

`OnboardingStep.SAMPLE_MENU_PUBLISH(4, Phase.CONFIGURING, false, null)`.
`BRANDS_AND_LOCATIONS_VALIDATE` through `TENANT_ACTIVATE` shift from 4–12 to
5–13. Sequence numbers are copied into `tenant.onboarding_steps.sequence_number`
when a run is materialised, so runs that already exist keep the order they were
created with; the enum's numbering only describes runs created after it.

`TenantOnboardingStepCompleted.v1`'s `stepKey` enum gains `SAMPLE_MENU_PUBLISH`.
Adding an enum value is a compatible change under ADR 0032, so the frozen
baseline is unchanged.

### Starting a run

`POST /api/v1/control-plane/tenants/{tenantId}/onboarding-runs` gains
`sampleMenu: boolean`. Absent is false. The value is written to the run's input
snapshot under `sampleMenu` — every step's `input_snapshot` carries it, as every
other input already is — and to the `tenant.onboarding_started` audit fact's
`changed` map, so the choice is auditable and carries no personal data.

`OnboardingService.startRun` materialises `SAMPLE_MENU_PUBLISH` as `SKIPPED`
with `last_error_code = 'NOT_REQUESTED'` when the input says false, and as
`PENDING` when it says true. `SKIPPED` is already one of
`ck_onboarding_step_status`'s permitted values (`V0014`), `claimNextStep` only
ever takes `PENDING`, and `outstandingRequiredSteps` only counts `required`
rows — so a skipped sample menu neither runs nor blocks `READY`. No migration is
needed.

### What the step creates

For the tenant's first brand — lowest `code`, which is stable and does not
depend on clock resolution — and every location that brand has:

| Thing | Value |
|---|---|
| Catalog | code `SAMPLE-MENU`, name `Sample menu` |
| Categories | 4: `SAMPLE-MAINS`, `SAMPLE-SHASHLIK`, `SAMPLE-STARTERS`, `SAMPLE-DRINKS` |
| Products | 10, each with one default variant, SKU = product code |
| Locales | `uz`, `ru`, `en` on every category and product; no media of any kind |
| Prices | the amounts below, in the tenant's `default_currency` — **which must be `UZS`**, see below |
| Price book | name `Sample menu prices`, `BRAND` scope, priority 0, activated |
| Tax profile | `UZ`, `INCLUSIVE`, 1200 basis points, only when the brand has none. It outlives the sample — see Consequences |
| Offerings | `AVAILABLE` for `PICKUP` and `DELIVERY` at every location that has no offering for that variant yet; an existing row is never overwritten |
| Stock | one `BINARY` `inventory.stock_items` row per variant per location, which starts available |
| Publication | `STOREFRONT`, `PUBLISHED` |

**The sample is UZS-only.** The amounts are whole som copied from
`tools/seed-data/horecaos-tenant.json`, at the platform's UZS exponent of zero —
not ISO 4217's two, which both frontend money modules deliberately refuse to use
because that reasoning already shipped a 100× bug. Nothing converts them, so a
tenant trading in one of the platform's other declared markets (`KZ`/`KZT`,
`GE`/`GEL`) would get plov at 38 000 of its own currency — wrong by an exchange
rate, published on the anonymous storefront, as the platform's own proof that
the tenant works. Rather than convert badly the step refuses: a tenant whose
`default_currency` is not `UZS` fails with `SAMPLE_MENU_UNSUPPORTED_CURRENCY`
and nothing at all is written. The same refusal is what keeps the hard-coded
Uzbek VAT rate off a tenant it does not describe. A per-market price-and-rate
table is the way to lift this, and it is worth writing when `KZ` or `GE`
actually onboards — not before, because nobody knows those prices today.

The ten items are the ten from `tools/seed-data/horecaos-tenant.json` that need
no image to read as a menu: plov, lagman, manti, lamb/beef/chicken shashlik,
somsa, achichuk salad, non, green tea. Prices are that file's, unchanged.
"Sample" prefixes the name in all three locales.

The step is skipped-in-effect but still `COMPLETED` when the brand already has a
`PUBLISHED` publication on `STOREFRONT` that is not the sample's — a tenant that
authored a real menu between starting a run and this step running must not have
a sample published over it.

### Idempotency

Each of the three ports is idempotent on its own, because a step can die between
any two of them:

- `SampleMenuPort.installSample` looks for `catalog.catalogs` by `(tenant_id,
  brand_id, 'SAMPLE-MENU')` and returns what it finds, creating only what is
  missing. Products, categories and offerings are likewise looked up by code
  before being created. It takes no locale: the locale catalog validation
  requires a name in is catalog's own configuration
  (`horecaos.catalog.default-locale`), and a caller that passed the owner's
  language instead would author a menu that then failed to publish with
  `MISSING_TRANSLATION`.
- `SampleMenuPricingPort.priceSample` asks its own `VariantPricingLookup` which
  of the variants already have an active price and returns without writing when
  all of them do.
- `StockListingPort.ensureListed` finds the stock item before creating one.
- `SampleMenuPort.publishSample` returns the active publication when one already
  exists for this catalog and channel, rather than snapshotting a second.

A second run for the same tenant therefore finds the sample catalog, adds
nothing, and completes.

Both ports that touch state an operator can change share one rule, stated here
because the two drifted apart while it was unwritten: **an existing offering row
and an existing stock item are operator state the installer never overwrites;
only missing rows are created.** `StockListingPort.ensureListed` already refused
to re-list a deliberately sold-out item ("re-listing it would silently put a
dish back on that a kitchen had taken off"); offerings did not, and re-asserted
`AVAILABLE` and the full fulfilment-mode set over whatever was there. They now
agree: `installSample` inserts an offering with `ON CONFLICT DO NOTHING` on the
natural key, so a sample dish an operator set `UNAVAILABLE` or `HIDDEN` stays
that way through every later attempt and every later run — while a location
added to the brand between two runs still starts offering the sample, which is
what the re-assert was for. The conflict target is the natural key rather than a
read-then-write on purpose: `offeringsForLocation` filters `status <> 'HIDDEN'`
in SQL, so a read would see a hidden row as absent and re-create it `AVAILABLE`.

### Result snapshot

```json
{"catalogId": "...", "catalogCode": "SAMPLE-MENU", "publicationId": "...",
 "priceBookId": "...", "categories": 4, "products": 10, "variants": 10,
 "locations": 2, "offeringsCreated": 20, "stockItemsListed": 20, "pricesSet": 10,
 "channel": "STOREFRONT", "created": true}
```

`offeringsCreated` sits beside `stockItemsListed` for the same reason: both
ports create only what is missing, so a record that said nothing about them
could not tell "there was nothing to do" from "something was overwritten". Both
read zero on a retry that found everything already there.

Ids and counts only. No item names, no prices, nothing about a person.
`priceBookId` is absent rather than null when the tenant's own prices already
covered every sample variant and no sample book was needed. The one other shape
is the brand that already published a real menu, which records what it declined
to do and nothing else: `{"channel": "STOREFRONT", "created": false, "reason":
"MENU_ALREADY_PUBLISHED"}`.

### Failures

| Code | Outcome | Meaning |
|---|---|---|
| `NO_BRAND` | `FAILED` | The tenant has no brand to hang a menu on. Resumable: create a brand, resume the run |
| `NO_CHANNEL` | `FAILED` | The tenant has no **active** `STOREFRONT` channel to publish to. `channel.isEmpty() \|\| !channel.sellable()`, the same predicate `ACTIVATION_SMOKE_TEST` applies, because a channel row still exists after it is archived and `publish` throws for an archived one. Checked before anything is written, because a thrown handler becomes `RETRY` — which would retry a permanent condition forever |
| `NO_LOCATION` | `FAILED` | The brand has no location, so nothing can offer the menu |
| `SAMPLE_MENU_UNSUPPORTED_CURRENCY` | `FAILED` | The tenant's `default_currency` is not `UZS`, which is the only currency the sample's amounts are authored in. Checked before anything is written. Not resumable by resuming: it needs a per-market price table, or a tenant that trades in som |
| `SAMPLE_PRICING_REFUSED` | `FAILED` | Pricing refused the sample book permanently — in practice the tenant's own `BRAND`-scope book is live at priority 0 and ties with the sample's. The detail is the refusal's own message. Resumable: give one of the two books a higher priority, or end the other's window, then resume. Distinguished from a thrown handler on purpose: an optimistic-locking failure from two writers racing is transient and keeps its `RETRY` |
| `TRANSIENT_INFRASTRUCTURE` | `RETRY` | Anything thrown; `OnboardingService` already maps a thrown handler to this |

A `FAILED` sample menu does not halt the run: the step is not `required`, so
`claimNextStep` steps past it and `refreshRunStatus` still reaches `READY`.
That is the whole point of it being optional, and it is asserted rather than
assumed — see Testing.

### Control plane

The start panel gains a checkbox, "Create and publish a sample menu so we can
check everything works; the tenant replaces it later", default on. The run view
shows `SAMPLE_MENU_PUBLISH` like any other step, with a hint under it that
explains what the step did or would have done, and the failure codes above join
the existing hint table.

### Testing

- The handler against the migrated schema: it creates, it publishes, a second
  execution creates nothing more, and `CATALOG_READINESS_VALIDATE` and
  `ACTIVATION_SMOKE_TEST` both pass afterwards with the sample menu as the only
  menu the tenant has.
- The published sample menu is readable through
  `GET /api/v1/storefront/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/menu`,
  with prices, in all three locales.
- `OnboardingService.startRun` materialises the step `PENDING` when asked and
  `SKIPPED` when not, and a `SKIPPED` step is never claimed and never blocks
  `READY`.
- A run that asked for a sample menu and whose sample step **failed** still
  drains to `READY`, with a later step `COMPLETED` — the assertion that pins
  `claimNextStep`'s `failed.required`, without which the run silently stalls at
  step 5 while still reporting `PROVISIONING`. And the same run, reached the way
  production reaches it: the handler throws until `MAXIMUM_ATTEMPTS` is spent.
- The rollback story, every half: a `SAMPLE_MENU_PUBLISH` row whose handler is
  absent is released `BLOCKED` and the run still reaches `READY`; an *optional*
  `step_key` this binary has no enum constant for is released `BLOCKED` rather
  than throwing in the claim's row mapper, and the run still reaches `READY`; a
  *required* one is released `FAILED`, the run fails, both stall signals name
  it, and `resume` plus a binary that knows the key finishes the run in place —
  the assertion that rules out the terminal-and-silent `BLOCKED`; and `validate`
  reports an unknown key as an unresolvable `CAPABILITY_ABSENT` check beside the
  six others rather than throwing 500 out of the dry run. That last one is
  written against a `VALIDATING` step on purpose: `SAMPLE_MENU_PUBLISH` is
  `CONFIGURING` and invisible to `validate`'s query, so a test written on it
  would pass with `valueOf` restored.
- The stall alert in both directions: a run whose optional sample menu failed
  reports a stalled age of zero two hours later and the ADR 0058 stuck-run
  listing does not name it; `aRunThatFailedItsRequiredStepIsStalled` asserts the
  mirror, gauge *and* listing — the listing asserted empty and never non-empty
  would leave `AND s.required` free to be narrowed to silence, and would never
  invoke the listing's row mapper at all.
- `resume` refuses such a run and leaves the failed step `FAILED`.
- The whole run, once: `sampleMenu: true` on a tenant that authored nothing,
  driven start-to-`READY` by the real `OnboardingService` with the real
  handlers, ending with the storefront menu readable in Russian and priced.
- An operator's `HIDDEN` or `UNAVAILABLE` offering survives a second attempt,
  and a location added between two runs gets its offerings.
- Each locale's names are asserted against that locale's own prefix, in the
  catalog rows and in the storefront read — an assertion satisfied by any of
  the three prefixes cannot see Uzbek text under the `ru` key.
- A tenant trading in `KZT` is refused, and nothing is written; and the price
  book carries the currency the port was given, asserted on the port because the
  step refuses every currency but one.
- A tenant whose own price book is live at priority 0 fails
  `SAMPLE_PRICING_REFUSED` rather than retrying — and the converse, which is
  what keeps that catch narrow: an activation that loses its compare-and-set
  escapes the real `SampleMenuPricing` and the real handler as an
  `OptimisticLockingFailureException`, for `OnboardingService` to map to
  `RETRY`, instead of spending none of its five attempts on a
  `SAMPLE_PRICING_REFUSED` that waiting would have cleared.
- A storefront channel that is `INACTIVE` — not only `ARCHIVED` — fails
  `NO_CHANNEL` with nothing written. `INACTIVE` is the half `sellable()` alone
  catches, since publication refuses `ARCHIVED` on its own; without it a
  switched-off storefront gets the whole sample published to it and the run only
  fails eight steps later at `ACTIVATION_SMOKE_TEST`.
- The control-plane spec: the checkbox defaults on for a first run, off for a
  restart, and its value reaches the start call; and each of the three sample
  failure codes reaches the DOM as its translated hint, because the hint is the
  only place the console says what to do about it and catalogue key parity
  proves the string exists, not that an operator ever sees it.
- Every `OnboardingStep` appears in `TenantOnboardingStepCompleted.v1`'s
  `stepKey` enum — the compatibility gate only catches removed values, so a
  misspelled or forgotten addition had nothing checking it.

## Rollout and rollback

No migration and no schema change, so there is nothing to roll back in the
database. Rollback is reverting the code — and reverting the code removes
`OnboardingStep.SAMPLE_MENU_PUBLISH` along with the handler, which is the part
the obvious story gets wrong. A binary whose enum has no constant for a key
cannot classify the row at all: `claimNextStep` used to resolve the key with
`valueOf` inside the row mapper, before the handler registry was ever consulted,
so the "no registered handler is released `BLOCKED`" path was unreachable and
the run froze on the row instead — a warning every tick, five steps short of
activation, on every replica.

As of this record, `claimNextStep` resolves the key with `OnboardingStep.find`
and releases an unknown one `CAPABILITY_ABSENT`, so the next such rollback is
safe by construction. Which status it releases it *as* depends on the row's own
`required` column, and the distinction is the whole of the tolerance:

- **Optional** — `BLOCKED`, exactly as a step whose handler is missing.
  Terminal, never claimed again, and no gate on `READY`, because
  `outstandingRequiredSteps` counts only required steps. `SAMPLE_MENU_PUBLISH`
  is the only optional step, so this is the rollback case this record is about,
  and a run that meets it still reaches `READY` on its own.
- **Required** — `FAILED`. `BLOCKED` would be the wrong answer here and was
  briefly the shipped one: a required blocked row is counted by
  `outstandingRequiredSteps`, so `refreshRunStatus` pins the run at
  `PROVISIONING` and `activate` answers `READINESS_INCOMPLETE` forever, while
  the stalled gauge and the ADR 0058 stuck-run listing read only `PENDING` and
  `FAILED` and so never name it — terminal, silent, and recoverable only by
  abandoning the run. `FAILED` is the state the rest of the workflow already
  knows: the run turns `FAILED` and publishes `TenantOnboardingFailed`, both
  stall signals see it, `claimNextStep`'s `failed.required` clause halts the run
  at the unknown step rather than draining it to one step short of `READY`, and
  `resume` reopens it — so the moment a binary that knows the key is back in
  place, an operator recovers the run where it stands, with no hand-written SQL
  and no cancel-and-restart.

Releasing a required unknown key back to `PENDING` with a backoff was considered
and rejected: `release` writes `updated_at` on every call, so a re-release loop
keeps the row permanently young and therefore invisible to both stall signals —
the defect it was meant to fix — while `attempt_count` climbs without bound past
`MAXIMUM_ATTEMPTS`.

The direction this matters in is a rolling deploy, not only a rollback: twelve of
the thirteen steps are required, so a step a newer replica materialises will
normally be required too, and an older replica still serving during the rollout
is what claims it.

That tolerance ships in this wave, not in the wave being rolled back to, so
**this** rollback still needs the outstanding rows retired by hand first:

```sql
UPDATE tenant.onboarding_steps
   SET status = 'SKIPPED', last_error_code = 'NOT_REQUESTED',
       claim_token = NULL, claimed_at = NULL, updated_at = now()
 WHERE step_key = 'SAMPLE_MENU_PUBLISH' AND status IN ('PENDING', 'RUNNING');
```

`SKIPPED` rather than `BLOCKED`, so the row is never claimed again and — being
optional — never gates `READY`, which is the same end state a declined sample
menu already has. Completed rows are left alone: they describe work that really
happened.

## Implementation checklist

- [x] `OnboardingStep.SAMPLE_MENU_PUBLISH` and the renumbering.
- [x] `sampleMenu` on the start request, in the input snapshot, in the audit fact.
- [x] `SKIPPED` materialisation in `OnboardingService.startRun`.
- [x] `catalog.api.SampleMenuPort` and its service, with the content.
- [x] `pricing.api.SampleMenuPricingPort` and its adapter.
- [x] `inventory.api.StockListingPort` and its adapter.
- [x] The handler in `ordering.application.onboarding`.
- [x] The event schema's enum value.
- [x] The control plane's checkbox, step name, and hints in three catalogues.
- [x] Tests, including the storefront read.

## Exit criteria

A tenant created with one brand and one location, whose run was started with
`sampleMenu: true` and whose owner has authored nothing, reaches `READY` with
`CATALOG_READINESS_VALIDATE` and `ACTIVATION_SMOKE_TEST` `COMPLETED`, and the
anonymous storefront menu endpoint returns ten priced products for its location.
A second run for the same tenant completes `SAMPLE_MENU_PUBLISH` without
creating an eleventh product.

## References

- [ADR 0008](../built/0008-resumable-tenant-onboarding-workflow.md)
- [ADR 0016](../partial/0016-brand-catalog-publication-and-location-offerings.md)
- `tools/seed-horecaos-tenant`, `tools/seed-data/horecaos-tenant.json`

# ADR 0099: A new tenant can be onboarded with a sample menu it did not write

- Decision status: Proposed
- Implementation status: Not started
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
currency, stocks it at every location the tenant has, and publishes it to the
`STOREFRONT` channel. `CATALOG_READINESS_VALIDATE` and `ACTIVATION_SMOKE_TEST`
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
  a price book may not expect.

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
| Prices | the tenant's `default_currency`, minor units, from the table below |
| Price book | name `Sample menu prices`, `BRAND` scope, priority 0, activated |
| Tax profile | `UZ`, `INCLUSIVE`, 1200 basis points, only when the brand has none |
| Offerings | `AVAILABLE` for `PICKUP` and `DELIVERY` at every location |
| Stock | one `BINARY` `inventory.stock_items` row per variant per location, which starts available |
| Publication | `STOREFRONT`, `PUBLISHED` |

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

- `SampleMenuPort.installDraft` looks for `catalog.catalogs` by `(tenant_id,
  brand_id, 'SAMPLE-MENU')` and returns what it finds, creating only what is
  missing. Products, categories and offerings are likewise looked up by code
  before being created.
- `SampleMenuPricingPort.priceSample` asks its own `VariantPricingLookup` which
  of the variants already have an active price and returns without writing when
  all of them do.
- `StockListingPort.ensureListed` finds the stock item before creating one.
- `SampleMenuPort.publish` returns the active publication when one already
  exists for this catalog and channel, rather than snapshotting a second.

A second run for the same tenant therefore finds the sample catalog, adds
nothing, and completes.

### Result snapshot

```json
{"catalogId": "...", "catalogCode": "SAMPLE-MENU", "publicationId": "...",
 "priceBookId": "...", "categories": 4, "products": 10, "variants": 10,
 "locations": 2, "channel": "STOREFRONT", "created": true}
```

Ids and counts only. No item names, no prices, nothing about a person.

### Failures

| Code | Outcome | Meaning |
|---|---|---|
| `NO_BRAND` | `FAILED` | The tenant has no brand to hang a menu on. Resumable: create a brand, resume the run |
| `NO_LOCATION` | `FAILED` | The brand has no location, so nothing can offer the menu |
| `SAMPLE_MENU_REJECTED` | `FAILED` | The publication came back `REJECTED`; the detail names the blocker codes |
| `TRANSIENT_INFRASTRUCTURE` | `RETRY` | Anything thrown; `OnboardingService` already maps a thrown handler to this |

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
- The control-plane spec: the checkbox defaults on and its value reaches the
  start call.

## Rollout and rollback

No migration and no schema change, so there is nothing to roll back in the
database. Rollback is reverting the code: runs already started keep their
materialised rows, and a `SAMPLE_MENU_PUBLISH` row with no registered handler is
released `BLOCKED` by `claimNextStep`, which is the existing behaviour for any
step whose handler is absent. The step is optional, so a `BLOCKED` row does not
stop a run reaching `READY`.

## Implementation checklist

- [ ] `OnboardingStep.SAMPLE_MENU_PUBLISH` and the renumbering.
- [ ] `sampleMenu` on the start request, in the input snapshot, in the audit fact.
- [ ] `SKIPPED` materialisation in `OnboardingService.startRun`.
- [ ] `catalog.api.SampleMenuPort` and its service, with the content.
- [ ] `pricing.api.SampleMenuPricingPort` and its adapter.
- [ ] `inventory.api.StockListingPort` and its adapter.
- [ ] The handler in `ordering.application.onboarding`.
- [ ] The event schema's enum value.
- [ ] The control plane's checkbox, step name, and hints in three catalogues.
- [ ] Tests, including the storefront read.

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

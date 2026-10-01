# ADR 0138: Marketplace projection preview and per-channel overrides

- Decision status: Accepted
- Implementation status: Not started — no preview endpoint, no projection
  assembly, and no channel-scoped media override exists. The layers this
  record composes are individually built (`catalog.location_offerings`,
  `catalog.channel_offering_exclusions`, `pricing.price_book_assignments` at
  `CHANNEL` scope) but nothing joins them into a single answer, and nothing
  reads them for any purpose but the live storefront and the live publication.
- Date proposed: 2026-09-25
- Date decided: 2026-10-01
- Deciders: proposed by Claude (wave batch 12, w2-catalog-adrs) from
  `platform/docs/operations-gap-map.md` row `4.6a`, held for an ADR since
  batch 3; Ayubkhon Abbosov (platform owner) decides.
- Depends on: ADR 0010, ADR 0016, ADR 0018, ADR 0025, ADR 0026, ADR 0031,
  ADR 0036, ADR 0040
- Supersedes / Superseded by: —
- Open inputs:
  - The actual per-marketplace content rules (image resolution, description
    length, category-depth limits, price-parity clauses) for Yandex Eda, Uzum
    Tezkor, Wolt, and Express24. This record builds the mechanism a ruleset
    plugs into (a code-owned severity/finding vocabulary, the same shape
    `CatalogValidator` already uses) and leaves the rulesets themselves empty
    — the same posture ADR 0040 already took on "whether Yandex Eda and Uzum
    Tezkor admit a third-party platform integration at all," which it
    correctly treated as a commercial question outside a design decision
    (product, partnerships).
  - Whether a rendered visual mock (matching a marketplace's own app
    chrome, the way Delever's `Предпросмотр меню` does for Glovo/Wolt/Yandex
    Eats/Bolt Food per mobile and desktop) is worth building, or whether the
    data-accurate preview this record specifies is enough for the pilot —
    `catalog.md` 4.10 already commits to the narrower answer for now
    ("the preview button renders the storefront projection only, and says
    so") and this record does not reopen that, but flags it as a product
    question for whenever aggregator onboarding volume justifies the
    investment (product, design).
  - Per-channel image variant selection precedence when both a location and a
    channel each have an override for the same variant — this record decides
    channel-then-location precedence for price (matching ADR 0018's existing
    channel-outranks-location rule) but does not extend that same claim to
    images without the owner confirming operators expect the two axes to
    agree (product).


**Decision record, 2026-10-01.** Accepted by Ayubkhon Abbosov (platform owner) with the instruction "accept all" over ADRs 0136–0144. Every open input above is closed on the default this record proposes for it; where a record defers an input to a named owner, that deferral stands as written and implementation proceeds without it. Implementation starts in operations batch 17 the same day.

## Context

**Gap-map row `4.6a` — "Aggregator preview and the per-channel
projection/override layer" — has sat `NOT BUILT`, severity `2`, `deferred`,
since batch 3**, with the note: *"Nobody can see how a menu will render — or
why a marketplace will reject it — before pushing it, so the first sign of a
bad publication is a live aggregator refusing the feed."* `platform/docs/operations-spec/catalog.md`
§4.10 already describes the shape a preview screen would need without being
able to build it: *"Delever ships both (`Предпросмотр меню`... and
`Предварительная проверка меню` emitting a downloadable deficiency report
before pushing to Yandex Eats or Uzum Tezkor). Both are worth matching and
both are ADR 0040, not built."* — a forward reference to an ADR that, on
inspection, never actually specified this feature: **ADR 0040 is entirely
about inbound aggregator orders and the partner API surface; it names
`marketplace.menu.read`, `marketplace.menu.push`, and
`marketplace.availability.push` as capability codes it owns and states
plainly that *"menu pull, availability push and every outbound Camel
adapter"* are not built, but it never specifies what a projection or a
preview actually contains.** This record is the ADR `catalog.md` was pointing
at and ADR 0040 never wrote.

**What already exists, independently, and has never been composed.**

- `catalog.location_offerings` (ADR 0016): per-location `AVAILABLE`/
  `UNAVAILABLE`/`HIDDEN`, read live.
- `catalog.channel_offering_exclusions` (ADR 0036, `V0020`): a sparse
  per-(channel, variant, optional location) suppression, default offered,
  with a reader (`JdbcCatalogStore.channelExcludedVariantIds`) and, since
  wave P45, a writer too (`JdbcCatalogStore.excludeFromChannel`/
  `includeInChannel`, exposed on `CatalogAuthoringController` as
  `PUT …/exclusions/variants/{variantId}` and `POST …/exclusions/bulk`) — an
  operator can already say a dish is not on a given channel from the catalog
  screen.
- `pricing.price_book_assignments` at `CHANNEL` scope (ADR 0018), resolved
  through `tenant.sales_channels.price_plane_channel_id` (ADR 0036) — fully
  built and already channel-aware.
- `tenant.sales_channels.externally_priced` (ADR 0036/0040): a channel whose
  price is set by the aggregator, not by Qoida's price books at all.
- `catalog.publications`/`publication_items` (ADR 0016): one immutable
  snapshot per `(brand, channel)`, the only thing any client — including a
  marketplace, once ADR 0040's outbound push exists — actually reads.
- `integration.bindings` of category `MARKETPLACE` (ADR 0040): one binding
  per (tenant, location, aggregator), the thing an outbound menu push would
  target.

Five independently-owned mechanisms, each correct on its own, and nothing
answers "what would channel X actually receive if I published right now" by
composing them. An author who wants to know has to reason about five tables
by hand or simply publish and see what an aggregator says — which is
precisely the failure this row names.

**Why a preview is not a second publication mechanism.** ADR 0016 already
rejected serving the storefront from authoring tables directly — *"Draft
edits leak into live menus, and no two reads are guaranteed consistent while
an editor is working"* — and a preview that read live authoring tables would
reproduce exactly that risk for a different audience. The right shape,
argued below, is to run the **same** snapshot-and-validate machinery
`CatalogPublicationService.publish` already uses, addressed at one channel,
and return the result instead of committing it — not a second, parallel
projection engine that could disagree with what publishing actually produces.

## Decision

**A marketplace preview is a dry run of the existing publication pipeline,
scoped to one channel, that returns a result instead of writing one.**
`CatalogPublicationService` gains a `preview(catalogId, channelId)` method
that performs every step `publish` already performs — snapshot the current
draft, resolve `location_offerings` per location bound to that channel,
apply `channel_offering_exclusions` for that channel, resolve
`price_book_assignments` at `CHANNEL` scope (or mark the item
`externally_priced` and omit a Qoida-resolved price entirely), run
`CatalogValidator` including this record's new marketplace-specific rules —
and returns the assembled `publication_items`-shaped payload plus the
validation report, **without** writing a `catalog.publications` row, without
retiring anything, and without a content hash that would imply it is
something a client could ever fetch by reference. A preview is a read; it
declares `catalog.read`, not `catalog.publish`, the same distinction ADR 0016
already draws for `GET …/validation`.

**The composed order is decided here because nowhere else decides it.** Five
independently-accepted mechanisms each answer one question; this record fixes
the order they combine in, since ADR 0016, ADR 0018, and ADR 0036 each wrote
their own piece without reference to what a downstream reader does when more
than one applies at once:

1. A variant absent from `location_offerings` (or `status <> AVAILABLE`) at
   every location bound to the channel is absent from the projection —
   `location_offerings` is the first gate, exactly as it already is for the
   live storefront.
2. A variant present after step 1 but named in `channel_offering_exclusions`
   for this channel (brand-wide or narrowed to the relevant location) is
   dropped — the second gate, per ADR 0036's own account of the table.
3. Price resolves through `price_book_assignments` at `CHANNEL` scope over
   `LOCATION` over `BRAND` (ADR 0018's existing priority order, unchanged
   here), unless `tenant.sales_channels.externally_priced` is true for this
   channel, in which case the projection carries no Qoida-resolved price at
   all and is flagged `EXTERNALLY_PRICED` — a preview must not imply a number
   the aggregator, not Qoida, will actually set.
4. Media resolves through this record's new channel-scoped override (below)
   over the variant's own default `media.assets` (ADR 0010).

**A channel-scoped media override closes the slot ADR 0010 already named and
left open.** ADR 0010's own account (`catalog.md` 4.9, "Not built, named")
already states the gap precisely: *"Per-aggregator image variants and a
per-asset content hash for downstream change detection — ADR 0010, both named
in the parity matrix as the two refinements Delever proved useful and
HorecaOS lacks."* This record supplies the *override* mechanism — where the
choice is made and in what precedence — and leaves the underlying asset model
(upload, verification, content hash) to ADR 0010, which already owns it and
is not reopened here:

```text
catalog.channel_media_overrides
  tenant_id, brand_id, channel_id, entity_type (PRODUCT|VARIANT|CATEGORY)
  entity_id, media_asset_id references media.assets, role, sort_order
  version, timestamps

  primary key (tenant_id, channel_id, entity_type, entity_id, role)
```

An override row replaces the entity's PRIMARY/GALLERY media for that channel
only; an entity with no override row for a channel falls back to its default
`media.assets` relation exactly as today. This mirrors
`channel_offering_exclusions`'s own shape deliberately — sparse, default is
"use the normal thing," scoped to `(channel, entity)` — because an author who
already understands one sparse per-channel override table understands both.

**Marketplace-specific validation is a pluggable ruleset over the same
finding shape `CatalogValidator` already returns, seeded empty.** A new
`marketplace_ruleset_code` on `integration.bindings` (nullable, one of a
closed set this record does not populate — see Open inputs) selects which
additional rules `preview()` runs beyond the ordinary publication blockers.
Findings use the same `{severity, code, entityType, entityId, entityCode,
detail}` shape `GET …/validation` already returns (ADR 0016), with a new
code family this record reserves but does not fill in:

```text
MARKETPLACE_IMAGE_REQUIREMENT_UNMET     -- reserved; ruleset TBD per partner
MARKETPLACE_DESCRIPTION_REQUIREMENT_UNMET
MARKETPLACE_CATEGORY_DEPTH_EXCEEDED
MARKETPLACE_PRICE_PARITY_VIOLATION
```

Building the machinery without the actual per-marketplace rules is
deliberate and matches how ADR 0038 already shipped `catalog.mxik_reference`
with a real search endpoint and an empty table — *"the control has a real
search to call and nothing to return yet"* — because the mechanism and the
content are separable and the content is not this record's to decide.

**Override precedence versus price books, stated once.** The question
`catalog.md` 4.5 already raises without answering fully — *"Separate these
two [price and availability]... A price of zero must mean 'free', never 'not
sold'"* — is respected: this record's projection never substitutes an
override for a missing price or treats an `externally_priced` channel's
absent price as zero. Price and image overrides are independent axes; a
channel may override one, both, or neither, and step 3/4 above resolve them
independently rather than one gating the other.

**Outbound push (ADR 0040's `marketplace.menu.push`,
`marketplace.availability.push`) consumes the same projection this preview
returns, once built.** This record does not build the Camel adapters ADR
0040 already named as unbuilt and out of its own scope; it builds the one
thing an adapter and a human operator would otherwise compute twice and
risk disagreeing about — the composed view of what a channel should receive.
A future outbound-push implementation calls `CatalogPublicationService.preview`
(or a variant of it scoped to "publish for real, to this one channel") rather
than re-deriving the same five-mechanism composition independently.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| A persisted `channel_projection` table, recomputed on a schedule or on every catalog change | A second cached copy of "what a channel would see" that can go stale between recomputation and a real publish — exactly the staleness risk ADR 0016 already rejected for the storefront read, now reintroduced for a preview whose entire purpose is trustworthiness | A preview's read latency becomes a real product problem at a catalog size where recomputing per request is too slow — not evidenced yet at pilot scale |
| Build the preview as a wholly separate read path, not a dry-run of `CatalogPublicationService.publish` | Two implementations of "assemble a menu for a channel" that must be kept in agreement by discipline rather than by construction — the exact failure this record's own Context section names as the current problem (five mechanisms, nobody composes them the same way twice) | Never; reusing `publish`'s own assembly is the point |
| Fold marketplace validation rules directly into `CatalogValidator`'s existing, closed rule set rather than a pluggable per-binding ruleset | `CatalogValidator`'s existing rules are universal — every channel, every publication — and per-marketplace content rules are not: a rule Yandex Eda enforces may not apply to Uzum Tezkor. Hard-coding partner-specific logic into the one validator every publication path shares would make every channel pay the cost of every partner's idiosyncrasies | A rule turns out to be universal in practice across every marketplace this platform integrates; promote it into `CatalogValidator` proper then, the same way `FISCAL_DELIVERY_FEE_UNCLASSIFIED` graduated from a proposal into a shared rule |
| Render a pixel-accurate mock of each marketplace's own app chrome | Real value (Delever ships it) but real cost — four different partner UI shells to maintain, none of them contractually documented, all of them subject to changing without notice. `catalog.md` already committed to the narrower answer for now and this record does not reopen that (see Open inputs) | Aggregator onboarding volume justifies the investment — a product call, not an architecture one |
| Let `channel_offering_exclusions` remain the only per-channel override, with no image override table | Leaves ADR 0010's own named gap (per-aggregator image variants) permanently unaddressed with no mechanism to address it from, and `catalog.md` 4.9 already states the refinement is wanted | Never; the gap is already named as wanted, only unowned |
| Key the media override to `channel_id` alone, no per-location narrowing (unlike `channel_offering_exclusions`, which supports one) | Simpler, but inconsistent with the sibling mechanism this record deliberately mirrors, and a tenant with the same aggregator binding at two locations with different local photography would have no way to differ them | A real request for per-location image variance arrives; the schema above does not need to change to add it, since `channel_offering_exclusions`'s own location-narrowing pattern is available to extend to this table the same way |

## Consequences

### Positive

- Composing five already-accepted mechanisms in one documented order closes
  a real correctness gap: today, nothing guarantees an author's mental model
  of "what a channel will show" matches what `CatalogPublicationService.publish`
  would actually produce, because nothing exercises that composition except
  a real publish.
- Reusing `publish`'s own snapshot-and-validate machinery means a preview and
  a real publication can never structurally disagree — there is one
  implementation of "assemble a menu for a channel," not two.
- The marketplace ruleset mechanism ships independently of any actual
  per-partner content rule, so onboarding the first real marketplace does
  not require a schema change — only populating a ruleset this record
  already has a slot for.
- The channel media override closes a gap ADR 0010 already named and left
  open, using a shape (`channel_offering_exclusions`'s own sparse,
  default-offered pattern) an author who has already learned one screen
  recognises in the other.

### Negative

- A preview with an empty marketplace ruleset looks, to an author, exactly
  like "this menu is fine for Uzum Tezkor" when in truth nobody has told the
  platform what Uzum Tezkor actually requires — the mechanism existing before
  the content does risks a false sense of readiness until the Open input is
  closed per partner.
- Running the full snapshot-and-validate pipeline on every preview request is
  real computational cost at catalog sizes of 1000+ items (the author
  persona `catalog.md` §0 describes), paid on every preview click rather than
  once at publish time — accepted for now (see Alternatives) but a real
  latency risk at scale.
- The channel media override is one more per-(channel, entity) table an
  author can populate inconsistently — a variant overridden for one channel
  and not a sibling channel with the same aggregator category, with nothing
  in this record warning the author the two now diverge.

### Accepted trade-offs

- A preview is deliberately **not** cacheable or referenceable by ID — every
  call recomputes fresh from current draft state, the same discipline ADR
  0072 already applies to promo-code eligibility checks ("no step reads a
  cached answer from an earlier step"), at the cost of repeated computation
  a cached preview would have avoided.
- This record builds the projection and preview layer only; it does not
  build ADR 0040's outbound Camel adapters, so a channel's *actual* live menu
  on a real marketplace is still whatever was last manually configured on
  that marketplace's own merchant portal until ADR 0040's push path exists.
  A preview is honest about what Qoida *would* send, not a guarantee of what
  the aggregator currently shows.

## Specification

### Capability placement

`GET` preview declares `catalog.read` at `BRAND` scope — the same capability
`GET …/validation` already declares, since a preview has no side effect and
reveals nothing a person with ordinary catalog-read access could not already
piece together by hand. Writing a `channel_media_overrides` row declares
`catalog.author`, the same as every other authoring write on
`CatalogAuthoringController`. Setting a binding's `marketplace_ruleset_code`
declares `integration.bindings`' own existing management capability (ADR
0026), since it is a fact about the binding, not about the catalog.

### APIs

```text
GET /api/v1/control-plane/catalogs/{catalogId}/channels/{channelId}/preview
PUT /api/v1/control-plane/channels/{channelId}/media-overrides/{entityType}/{entityId}
```

The preview response carries the same `{publishable, findings[]}` envelope
`GET …/validation` already returns, plus the assembled item list itself (what
`publication_items` would contain), so a single call answers both "is this
publishable to this channel" and "what would it look like."

### Physical model (additive)

```text
integration.bindings  (column added)
  marketplace_ruleset_code varchar(48) null  -- closed set, reserved and empty

catalog.channel_media_overrides
  tenant_id, brand_id, channel_id, entity_type (PRODUCT|VARIANT|CATEGORY)
  entity_id, media_asset_id references media.assets, role, sort_order
  version, timestamps
  primary key (tenant_id, channel_id, entity_type, entity_id, role)
```

### Validator additions (`CatalogValidator`, ruleset-gated)

```text
MARKETPLACE_IMAGE_REQUIREMENT_UNMET
MARKETPLACE_DESCRIPTION_REQUIREMENT_UNMET
MARKETPLACE_CATEGORY_DEPTH_EXCEEDED
MARKETPLACE_PRICE_PARITY_VIOLATION
```

All four are reserved codes with no firing logic until a real ruleset is
authored per Open inputs; `CatalogValidator` returns none of them for a
binding with `marketplace_ruleset_code IS NULL`.

### Events

None. A preview is a read with no side effect (ADR 0031's GET-has-no-side-effect
rule, already applied to `GET …/validation`); a `channel_media_overrides`
write rides the existing `CatalogDraftChanged` event (ADR 0016, ADR 0032)
like any other authoring change.

## Rollout and rollback

No existing data — no preview, no override row, and no ruleset code exists
anywhere today. A tenant that never calls the preview endpoint and never
writes a `channel_media_overrides` row sees no behaviour change to its live
storefront or publication flow: `CatalogPublicationService.publish` itself is
unchanged by this record, since `preview` is a new method beside it, not a
modification to it. Rollback is removing the preview endpoint and the
override table; nothing about a live publication depends on either existing.

## Implementation checklist

- [ ] `catalog.channel_media_overrides` (Flyway; number reserved by the wave
      that picks this up).
- [ ] `integration.bindings.marketplace_ruleset_code` column.
- [ ] `CatalogPublicationService.preview(catalogId, channelId)` composing the
      four-step order in Decision, reusing `publish`'s snapshot assembly.
- [ ] `GET …/catalogs/{id}/channels/{channelId}/preview` on
      `CatalogPublicationController`.
- [ ] `PUT …/channels/{id}/media-overrides/{entityType}/{entityId}` on
      `CatalogAuthoringController`.
- [ ] `CatalogValidator` marketplace-rule scaffolding (Specification), firing
      nothing until a ruleset is authored.
- [ ] Operations screen: `catalog.md` §4.10's preview region, extended to
      show the composed projection and marketplace findings per channel.
- [ ] Domain and API tests: the four-step composition order, the
      `externally_priced` price-omission case, the media-override fallback,
      and a proof that `preview()` and `publish()` agree on item membership
      for a channel with no exclusions or overrides set.

## Exit criteria

An author can select any registered channel for a brand and see, without
publishing, exactly which variants that channel would receive, at what price
(or flagged `EXTERNALLY_PRICED`), with which image, and which publication
blockers or marketplace-specific findings would stop the real publish from
succeeding. A marketplace binding can carry a ruleset code that adds
partner-specific findings to that preview once one is authored, with no
schema change required to add the first one.

## References

- ADR 0010: S3 media lifecycle and filesystem migration — the per-aggregator
  image variant gap this record's override table closes
- ADR 0016: Brand catalog, publication, and location offerings — the
  snapshot-and-validate machinery this record's preview reuses
- ADR 0018: Deterministic pricing, promotions, taxes, and quotes —
  channel-scoped price book resolution and its priority order
- ADR 0036: Sales channels and location serviceability —
  `channel_offering_exclusions`, `externally_priced`, `price_plane_channel_id`
- ADR 0040: Marketplace channel: inbound aggregator orders and the partner
  API — `MARKETPLACE` bindings, the `marketplace.menu.*` capability codes
  this record's future outbound push would use
- `platform/docs/operations-spec/catalog.md` §4.5, §4.9, §4.10
- `platform/docs/operations-gap-map.md` row `4.6a`

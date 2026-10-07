# ADR 0174: Reference data disposition

- Decision status: Accepted — proposed by Claude (batch 19); accepted by the platform owner 2026-10-07
- Implementation status: Not started — of the six vocabularies the row names, four are
  already built under other records and two have no table. Built elsewhere: product comment
  presets (`catalog.comment_presets` and `catalog.product_comment_presets`, V0378, wording per
  locale in V0430, `pos_modifier_code` for the POS round trip, console route
  `/settings/comment-presets`, row `2.1b`); recommended products
  (`catalog.product_recommendations`, V0302, a tab in the product editor, row `4.2h`, BUILT);
  kitchen departments (ADR 0041: `kitchen.stations` with a closed `role` set, `HOT`, `COLD`,
  `GRILL`, `BAR`, `BAKERY`, `PACKING`, `EXPO`, and `kitchen.brand_routing_rules` /
  `location_routing_rules`, V0030, with a department picker in the product editor that writes
  a brand-layer rule, row `4.2g`); and brands, which are `tenant.brands`. Not built: tags,
  ingredients and attributes have no table, no endpoint and no screen. The console's
  `/catalog/reference-data` route (`app.routes.ts`) is the honest not-built page, listed in
  `catalog-shell.html`; a different `reference-data` page under settings (row `10.10`) holds
  cancellation and completion reasons, the business calendar, branch tags
  (`tenant.branch_tags`, V0256) and the SLA buckets, and is not this. The published menu carries
  none of the three: `CatalogSnapshotLoader` writes a product's variants with `sku`, `unitCode`,
  classification, physical facts, groups, `isDefault`, `sortOrder` and `status`, and no `names`
  for a variant, so a size name an author types into the variants tab
  (`catalog.translations`, entity `VARIANT`, `CatalogAuthoringService`) is dropped at
  publication and the storefront prints the unit code instead (`menu.service.ts`: "No variant
  name"). ADR 0016's input "disposition of legacy tags, recommendations, storefront/FAQ content,
  and kitchens" is still open on its status line.
- Date proposed: 2026-10-07
- Date decided: 2026-10-07
- Deciders: proposed by Claude (batch 19); Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0016, ADR 0017, ADR 0024, ADR 0025, ADR 0027, ADR 0030, ADR 0031,
  ADR 0035, ADR 0041, ADR 0055, ADR 0101, ADR 0136, ADR 0137, ADR 0138, ADR 0149, ADR 0158
- Supersedes / Superseded by: — (amends ADR 0016 by closing its open input on legacy
  merchandising disposition for tags, recommendations and kitchens, and by adding the variant
  name to the published variant content that ADR 0016's publication model omitted; leaves the
  input's other clause, storefront and FAQ content, open and not decided here; reopens no rejected
  row: it keeps ADR 0016's rejection of an EAV or JSONB product model, and ADR 0017's refusal of
  ingredient-level depletion "simulated with modifier arithmetic")
- Open inputs: each is closed on its proposed default if the owner accepts the record as
  written; the ones that name a person other than the owner stay with that person and the work
  they block is marked.
  - **Whether tags and ingredients are needed before the pilot** (product, platform owner).
    Proposed default: no. The launch order is storefront, operations, payments, onboarding
    (ADR 0055), and neither blocks it. This record decides their shape so nobody improvises a
    free-text field; the roadmap in `docs/adr/README.md` orders the work. The variant name in
    Decision 1 is the exception: it is a defect fix and the multi-variant dishes of the pilot
    menu need it.
  - **Whether an ingredient may carry an allergen meaning** (legal, product). A missing allergen
    is a safety fact and an incomplete list is worse than none. Proposed default: an
    ingredient is a customer-facing composition label and nothing else; the schema has no allergen
    column and the storefront does not describe the list as complete. An allergen declaration is
    its own decision with its own legal review.
  - **The badge icon set** (design, product). Proposed default: a closed, code-owned list of
    neutral pictogram names in the shared UI library (ADR 0101), starting with `FLAME`, `LEAF`,
    `STAR`, `HEART`, `FISH` and `NEW`; no uploaded image, because an upload is the media pipeline
    and an attack surface for a decoration. A name that makes a dietary claim is not on the list.
  - **Whether the vocabularies are per tenant or per brand** (platform owner). Proposed
    default: per tenant, as `catalog.comment_presets` is, because "spicy" means the same at every
    brand of a chain; a brand publishes only what its own products use.
  - **How selected filter tags combine** (product). Proposed default: a customer who selects
    several sees products that carry all of them.
  - **Whether tags or ingredients reach a marketplace** (product, integration). Proposed
    default: no. ADR 0138's projection and each marketplace's ruleset decide it per channel.
  - **The legacy disposition, for the later migration program only** (product). ADR 0055
    puts nothing legacy in the launch. Proposed default for ADR 0024: `tags` and `product_tags`
    retire (the legacy profile found zero references, no writer, and a table with no tenant key);
    `recommended_products` retire for the same evidence; `kitchens` are mapped by an operator
    to a station role at migration (two seeded rows, a global classification, one link from
    `products.kitchen_id`). Attributes have no legacy source to dispose of.

**To accept as written:** say "accept 0174". Every open input above is then closed on its
proposed default.

**Decision record, 2026-10-07.** Accepted by Ayubkhon Abbosov (platform owner) under the standing instruction "lets finish all" given the same day, which accepts every record proposed in batch 19 (ADRs 0154–0176) on the default each open input proposes. An input that names a person other than the owner, or an external fact (a device model, a legal wording, a provider capability, a dataset publication), stays with that owner as written and implementation proceeds without it, marking what waits. Implementation starts in operations batch 20 (2026-10-07).

## Context

Row `4.7` of `platform/docs/operations-gap-map.md` ("Reference data — attributes, tags,
ingredients, kitchen departments, product comment presets (brands link out)") is BLOCKED,
severity 2, size L. Its note: *"There is no controlled vocabulary for tags, ingredients,
attributes or kitchen departments, so anything that needs one (cross-sell, storefront filters,
R-Keeper comment transport, ticket routing) has nowhere to draw from. Product comment presets
are the exception ..."* and its "Blocked by": *"An owner/product decision: ADR 0016's open input
on legacy merchandising disposition — in particular whether Атрибуты are the variant axis or a
spec-sheet vocabulary — is unanswered, and the spec forbids building any of it first."* The
open-decision table adds that kitchen-department routing "already exists by another route". The
IA's description (§4.7) is "the vocabularies products depend on", owning "attributes (the variant
axis backing `size_id`); brands; tags; ingredients; kitchen departments; product comment presets
(a controlled vocabulary with IDs, because R-Keeper transports them as modifiers)".
`docs/operations-spec/catalog.md` §4.13 lists each as "not built — ADR 0016" and ends: "Nothing on
this screen should be built before its disposition is decided. Listing them here is the point:
they are named, sourced, and unowned, so nobody improvises one into the product editor as a
free-text field."

**The row bundles six decisions, and four of them are already made.** The table in this record's
Decision is the whole disposition, but the shape of the situation is this. The two reasons the row
cites for waiting no longer hold for four of its items. Comment presets were built on their own
in batch 8 because they "do not touch the blocked decision". Kitchen routing was built as row
`4.2g` through ADR 0041's station roles. Recommended products, which catalog.md also lists under
§4.13, were built as row `4.2h`. Brands were never a catalogue concern. What remains is exactly
three vocabularies, and only one of them, attributes, is the question the row's "Blocked by" names.

**The attribute question has an answer in the platform's own code, and it is not the one
Delever's model implies.** Delever's attributes are "a tenant-level registry of product
characteristics — the V2 Settings description gives the examples размеры, вес, объем" and are
"effectively the dimension vocabulary that variants ..." hang off; its `size_id` is the axis
value a variant picks. The parity matrix records the doubt in its own open questions: genuinely
the variant axis, "or a separate free-form spec-sheet vocabulary that happens to coexist with
`size_id`?" Three facts here decide it. (1) A HorecaOS variant is already a named, ordered,
defaultable unit: `catalog.variants` has `sort_order` and `is_default`, and its customer-facing
name is a translation row per locale that the console's variants tab already authors in a "name"
column. The thing an axis registry would supply is a label per variant, and the label exists. (2)
Two of the three examples have homes: weight and volume are `net_weight_grams` and
`net_volume_millilitres` on `catalog.variant_physical_attributes` (ADR 0137, V0448). (3) What is
broken is the last step: the variant's name is **not published**. `CatalogSnapshotLoader` builds
each published variant from the variant row, its classification and physical facts and its groups,
and puts `names` on categories, products, modifier groups and options, but not on variants; the
storefront's own class comment says "translations are published for categories, products and
modifier groups only" and falls back to the unit code. A multi-variant dish therefore cannot show
"Small, Medium, Large" to a customer today, and an attribute registry built on top would feed a
label into a pipe that has nothing at the other end.

**Tags, ingredients and the filter they feed have no consumer and no source.** The published
product carries comment presets and combo groups and nothing a customer could filter or read as a
badge; the legacy dashboard's `tags` table had zero references and no writer (the legacy mapping
audit's `RETIRE` on evidence), and Delever's tags are small icon badges that also feed a storefront
filter. A customer-facing composition list ("Ингредиенты") is a label, not a bill of materials: the
parity matrix corrects the assumption that Delever has recipe costing, ADR 0017 refuses ingredient
depletion until recipes exist and "must not be simulated with modifier hacks", and catalog.md lists
"Skip: ingredient-level BOM and recipe costing".

**The shape to follow is a precedent in the schema.** `catalog.comment_presets` chose a
tenant-wide vocabulary ("'without onions' means the same thing at every brand a tenant runs") with
a brand-scoped assignment table through the product, a code unique per tenant, a status that is
archived and never deleted, and, later, a per-locale translations table because three label columns
could not carry a fourth language (V0430 exists to undo that, and
`LocaleTranslationTablesMigrationTests` exists to prove its backfill). A new vocabulary should start
from the translations table and skip the columns.

## Decision

**Decide each vocabulary once: build tags and ingredients as small tenant-wide registries
with brand-scoped assignments, published with the menu; do not build attributes, and give
the variant its own published name instead; record the four already-built vocabularies as
decided and say where each is authored.**

1. **Variants are named; attributes are not a variant axis.** The variant's translated name is
   its axis label. The published variant content gains `names` (per locale, the way a product's
   does), the storefront wire gains `MenuVariant.name`, and a product with two or more orderable
   variants shows a picker labelled by it. No attribute registry, no `size_id`, no constraint that
   a product's variants pick distinct axis values.
2. **Attributes are not a spec-sheet vocabulary either, for now.** Nothing consumes one. The
   row's examples are covered (size by the variant name, weight and volume by ADR 0137). The row's
   title drops "attributes"; a revisit trigger is recorded below.
3. **Tags are a tenant registry of badges and filter options.** A tag has a code, an optional
   icon from a closed list, two switches (shown as a badge, offered as a filter; at least one is
   on), a sort order, a status, and a name per locale. A product carries an ordered set of at most
   twelve. The published menu carries the tags a brand's products use, and the storefront draws
   the badge and offers the filter.
4. **Ingredients are a tenant registry of composition labels.** An ingredient has a code, a status
   and a name per locale. A product carries an ordered list of at most forty. It is displayed on the
   product page and never priced, stocked, ordered or counted: not a modifier, not a bill of
   materials. It carries no allergen meaning, and the schema has no column for one.
5. **Kitchen departments are the ADR 0041 station roles, already built.** The vocabulary is the
   closed, code-owned role set; a product's department is a brand-layer routing rule written by the
   product editor's picker, with the location layer overriding per branch. No `catalog` table and no
   `products.kitchen_id`.
6. **Comment presets, recommended products and brands stay where they are.** Presets remain a
   tenant-wide coded vocabulary with `pos_modifier_code`; recommendations remain a directional
   product-to-variant link; brands remain `tenant.brands` and the console links out.
7. **One authoring home, and assignments where the author already is.** Tags and ingredients are
   authored in Catalog, Reference data (`/catalog/reference-data`, replacing the not-built page),
   which also holds links out to the screens that own the other vocabularies. A product's tags and
   ingredients are assigned in the product editor.
8. **Controlled and bounded, never free text.** A code is operator-chosen, unique per tenant and
   stable; a rename changes the name, not the code; an archived entry keeps resolving on products
   that carry it, disappears from pickers and from the next publication, and frees nothing.
   Counts are bounded (200 tags and 1,000 ingredients per tenant) so a runaway import or a script
   cannot turn a vocabulary into a dump.
9. **The legacy disposition is recorded for the later program.** Retire `tags`, `product_tags`
   and `recommended_products`; map `kitchens` to station roles by an operator at migration. This
   binds nothing at launch.

## Disposition table

| Vocabulary (row `4.7`) | Disposition | Home | Authored in | Status |
|---|---|---|---|---|
| Attributes (Атрибуты) | Retire as a vocabulary. The variant's own name is the axis label | `catalog.translations` (`VARIANT`), published as `names` on the variant | Product editor, variants tab | The publication gap is this record's Decision 1 |
| Tags (Теги) | Build | `catalog.tags`, `catalog.product_tags`, `catalog.tag_translations` | Catalog, Reference data; assigned in the product editor | Not started |
| Ingredients (Ингредиенты) | Build, as a label only | `catalog.ingredients`, `catalog.product_ingredients`, `catalog.ingredient_translations` | Catalog, Reference data; assigned in the product editor | Not started |
| Kitchen departments (Отделы кухни) | Already decided: ADR 0041 station roles | `kitchen.stations.role`, `kitchen.brand_routing_rules`, `location_routing_rules` | Product editor picker (row `4.2g`); kitchen stations and routing screen | Built |
| Product comment presets | Already decided | `catalog.comment_presets`, `catalog.product_comment_presets` | Settings, comment presets; assigned in the product editor | Built (row `2.1b`) |
| Recommended products | Already decided | `catalog.product_recommendations` | Product editor, recommendations tab | Built (row `4.2h`) |
| Brands | Not catalogue data | `tenant.brands` | Tenancy; the Reference data screen links out | Built |

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Attributes as a variant-axis registry (Delever's `size_id`) | A second source for the variant's customer-facing name, a constraint set (distinct axis values per product) nothing here needs, and a registry that would feed a label into a published variant that carries none. The variant's translated name already is the label | A chain wants one size set shared across hundreds of products with a bulk rename, or a marketplace ruleset (ADR 0138) needs a typed size axis |
| Attributes as a free-form spec-sheet vocabulary | Nothing consumes it. The only plausible consumer is the storefront filter, which tags serve, and a typed property filter (spice level one to five) is a feature nobody has asked for | A tenant needs a filterable property that is not a yes or no |
| One generic vocabulary table with a `kind` column for tags, ingredients and attributes | EAV by another name, which ADR 0016 rejected: unqueryable and unvalidatable at the database level, and a tag's two switches have no place on an ingredient | Never |
| Free-text tags or ingredients on the product | Spelling diverges between products and brands, no filter can rely on it, no translation exists. The same reason ADR 0136 refused a free-text flag | Never |
| Ingredients as a bill of materials with depletion | Needs recipes, yields and costing that do not exist, and ADR 0017 refuses to simulate it with modifier arithmetic; Delever does not have it either | Recipes and yield data exist and a tenant needs ingredient depletion; extend the stock item type, as ADR 0017 says |
| Ingredients as removable modifiers ("no onion") | A modifier is a priced selection with stock and a receipt line; removing an ingredient is a kitchen instruction, which comment presets already carry coded | Tenants want structured, ingredient-linked removals on the kitchen display: a link from a preset to an ingredient, not a modifier |
| Kitchen department as a catalogue field (`products.kitchen_id`, the legacy shape) | ADR 0041 rejected one-layer routing: a brand attribute cannot describe two branches of one chain with different layouts, and a location-only mapping makes every branch re-map the menu | Never |
| Per-brand registries | Duplicated vocabularies for one chain; the meaning is the chain's. Comment presets chose tenant-wide for the same reason | A brand under one tenant is a genuinely different business with its own vocabulary |
| Uploaded badge images | Needs the media pipeline and is a rendering and injection surface for decoration. catalog.md already refuses the static-page editor's raw HTML for this reason | Design asks for brand-specific badge art; then a vetted asset relation, not a free upload |
| Build nothing; keep the not-built page | The storefront has no filters or badges, the variant name stays unpublished, and the row stays blocked on a decision that is now made | Never |

## Consequences

### Positive

- Row `4.7` stops waiting on a question that has an answer: four vocabularies are recorded as
  built, one is retired with its examples accounted for, and two have a shape.
- The variant name finally reaches a customer, which fixes a defect the attribute question had
  been hiding and which every multi-variant dish needs.
- A storefront can have badges and filters from a controlled vocabulary, and a product page can list
  its composition, without anyone typing a free-text field into the product editor.
- New vocabularies start from a translations table, so the next language needs no migration and no
  dual-write release.

### Negative

- Two new registries, four new tables and three translation tables are added to a schema that is
  already wide, with their screens, strings and publication items.
- The publication gains a `TAG` and an `INGREDIENT` item type and variant `names`. Publication items
  are insert-only, so an existing publication is not rewritten; a menu shows the variant name only
  after its next republish.
- A tag with a dietary-sounding name is a claim the tenant makes and the platform cannot check.
  The record declines allergen semantics, not the risk of the words.
- "Attributes" disappears from the row's title, and someone who expected a registry has to read why.

### Accepted trade-offs

- A tenant that wants a typed size set, or a filterable property that is not a yes or no, gets
  neither until a real case arrives; they have names and tags instead.
- Tags and ingredients are tenant-wide, so a brand that wants a different "Spicy" waits for a case
  that justifies per-brand registries.
- The icon list is short and neutral; badge art is not customisable.
- The legacy disposition is a recommendation for a program that is not running.

## Specification

### Physical model

Every table carries `tenant_id` and composite keys that include it (the rule
`tools/checks/tenant_scoped_references.py` enforces); the assignment tables also carry `brand_id`
through the product, the shape `catalog.product_comment_presets` (V0378) already has.

```text
catalog.tags                                   -- tenant-wide, like comment_presets
  id uuid pk, tenant_id, code varchar(32)      -- ^[a-z0-9][a-z0-9_-]{0,31}$, unique per tenant, never shown
  icon_key varchar(24) null                    -- closed list in code; check against it in the service
  show_as_badge boolean not null, show_as_filter boolean not null
  sort_order integer, status varchar(16)       -- ACTIVE | ARCHIVED
  version integer, created_at, updated_at
  unique (tenant_id, code), unique (id, tenant_id)
  check (show_as_badge or show_as_filter)
catalog.tag_translations                       -- the V0430 shape, with no label columns beside it
  tenant_id, tag_id, locale varchar(16), name varchar(80)
  primary key (tag_id, locale), foreign key (tag_id, tenant_id) -> catalog.tags (id, tenant_id) on delete cascade
catalog.product_tags
  id, tenant_id, brand_id, product_id, tag_id, sort_order, version
  foreign key (product_id, tenant_id, brand_id) -> catalog.products ...
  foreign key (tag_id, tenant_id) -> catalog.tags (id, tenant_id)
  unique (product_id, tag_id)

catalog.ingredients                            -- same registry shape; no icon, no switches
catalog.ingredient_translations
catalog.product_ingredients                    -- ordered; same assignment shape as product_tags
```

Counts are enforced in the service (twelve tags and forty ingredients per product; 200 tags and
1,000 ingredients per tenant) with a stable refusal code each. `catalog.translations` is unchanged:
it is brand-keyed and these registries are tenant-wide. Variant names use the existing `VARIANT`
rows. Every migration ends with explicit `GRANT`s for each new table (the hygiene check requires it):
`SELECT, INSERT, UPDATE` on the registries and `SELECT, INSERT, UPDATE, DELETE` on the assignment and
translation tables, because an assignment is replaced as a set and a vocabulary entry is archived,
not deleted. The next free migration number is taken at implementation time from every active
worktree (AGENTS.md).

### Publication

```text
VARIANT entry (existing, in the product item)   + "names": { "ru": ..., "uz-Latn": ..., "en": ... }
PRODUCT item                                    + "tagIds": [...], "ingredientIds": [...]   (ordered)
TAG item        entity_id = tag id,        content { code, icon, showAsBadge, showAsFilter, names, sortOrder }
INGREDIENT item entity_id = ingredient id, content { code, names, sortOrder }
```

`catalog.publication_items.entity_type` is a free `varchar(32)`, so the two new types need no schema
change. A brand's publication contains the `TAG` and `INGREDIENT` items its own active products
reference and none it does not, so one tenant's other brands never see them. `CatalogValidator`
blocks publication when a referenced tag or ingredient has no name in the brand's default locale
(`TAG_NAME_MISSING`, `INGREDIENT_NAME_MISSING`), as a missing product name already does, and when a
product with two or more orderable variants has a variant with no name in that locale
(`VARIANT_NAME_MISSING`, a WARNING for one release, because existing menus have never carried one,
then an error). The storefront response gains `MenuVariant.name`, `PublishedProduct.tags` and
`.ingredients`, with the name resolved for the requested language then the brand default, exactly as
`CommentPresetOption` resolves its label. A product with a single orderable variant shows no variant
picker and no label, as today.

### APIs (ADR 0031) and capabilities (ADR 0025)

```text
GET  /api/v1/control-plane/tenants/{tenantId}/tags                  catalog.read    TENANT
POST /api/v1/control-plane/tenants/{tenantId}/tags                  catalog.author  TENANT  Idempotency-Key
PUT  /api/v1/control-plane/tenants/{tenantId}/tags/{tagId}          catalog.author  TENANT  If-Match version
POST /api/v1/control-plane/tenants/{tenantId}/tags/{tagId}/archive  catalog.author  TENANT  Idempotency-Key, If-Match
(the same four for /ingredients)
GET  /api/v1/control-plane/tenants/{tenantId}/brands/{brandId}/products/{productId}/tags          catalog.read    BRAND
PUT  /api/v1/control-plane/tenants/{tenantId}/brands/{brandId}/products/{productId}/tags          catalog.author  BRAND  replaces the set, in order
(the same two for /ingredients)
```

No new capability: the codes that already guard the sibling vocabulary guard these. The
control-plane OpenAPI group already owns the prefix (ADR 0057). The product editor's existing
variants-tab name field is unchanged; publishing it is the fix.

### Events, audit, PII, policy

- **Events (ADR 0032).** None. These registries reach customers only through a publication, whose
  events are ADR 0016's, and nothing else consumes them. A reporting consumer, if one is wanted, adds
  its own event with its own schema.
- **Audit (ADR 0027).** `catalog.tag.created`, `.updated`, `.archived`, `catalog.ingredient.created`,
  `.updated`, `.archived`, `catalog.product.tags-set` and `.ingredients-set`, written in the same
  transaction, with ids, codes and counts and no names.
- **PII (ADR 0029).** None: vocabulary entries are catalogue content.
- **Policy (ADR 0030).** None needed; the bounds are code constants with a test, not tenant settings.
- **Providers (ADR 0026/0007).** None. A marketplace reading tags or ingredients is a per-channel
  decision in ADR 0138's projection and is not made here.

### Front-end contract

`/catalog/reference-data` becomes a left rail of vocabularies with a list and form on the right:
Tags and Ingredients as editable lists with the per-locale names the tenant's brands support;
Comment presets, Kitchen departments, Recommended products and Brands as links out with a sentence
saying where each is authored. The product editor gains a tags field and an ingredients list on its
main tab, and the variants tab's name field gets a hint that it is what the customer sees. The
storefronts draw the badge icons from the shared library, the filter chips, the ingredient line on the
product page and the variant picker's names. Strings in ru, uz-Latn and en; ADR 0149 governs further
locales.

### Testing

- Publication tests: a variant's name travels into the item and out through the storefront query; a
  menu published before this change keeps serving the unit-code fallback; a brand's publication
  contains only the tags and ingredients its products use; an archived tag leaves the next
  publication and stays on the product's history.
- Validator tests for each new finding and its adjacent passing case.
- Registry tests: code uniqueness and format per tenant, the two-switch check, the icon list, the
  bounds, set replacement keeps order, archive keeps resolving.
- Isolation: a tenant cannot assign another tenant's tag, and an assignment row cannot name a
  product of another brand; foreign-key tests in the style of `TenantScopedReferenceCatalogTests`.
- Storefront tests: language resolution with brand-default fallback; a single-variant product shows
  no picker; filter chips combine as AND.
- Console specs for the Reference data screen, the product editor fields and the empty states.

## Rollout and rollback

Three independent stages, each shippable alone. (1) The variant name: publication content, the
storefront wire and the picker, with the validator finding as a warning; it needs no migration and
no registry. (2) Tags: migration, service, endpoints, screen, publication, storefront. (3)
Ingredients: the same, smaller. Each is additive: nothing existing changes shape, and a menu that
uses none of it publishes as before. Rollback of a stage is not publishing the new item types and
hiding the console fields; the tables and rows stay.

## Implementation checklist

- [ ] Owner answers (or accepts the defaults for) the open inputs above.
- [ ] `CatalogSnapshotLoader` puts `names` on each published variant; `StorefrontCatalogQuery` and
      the storefront service carry `MenuVariant.name`; both storefronts label the variant picker;
      `VARIANT_NAME_MISSING` as a warning.
- [ ] Migration: tag and ingredient registries, translations, assignments, explicit grants.
- [ ] Services and controllers for both registries and both assignments; audit facts; bounds.
- [ ] Publication items and product references; validator findings; storefront response fields.
- [ ] Console: Reference data screen, product editor fields, i18n; icon list in the shared library.
- [ ] Storefronts: badges, filter chips, ingredient line.
- [ ] Update `docs/operations-spec/catalog.md` §4.13 and its match table, the IA's §4.7 text, and
      `docs/delever-parity-matrix.md`'s open questions to point here; ADR 0016's status line.
- [ ] Gap-map row `4.7` re-audited: attributes retired, kitchen departments and presets recorded as built.
- [ ] ADR 0024's migration coverage register updated with the legacy disposition.

## Exit criteria

A multi-variant dish shows "Small / Medium / Large" in the customer's language on both
storefronts. An author creates a tag with an icon in Catalog, Reference data, assigns it to a
product, republishes, and the product card shows the badge and the menu offers the filter; an
ingredient list appears on the product page; none of it can be typed as free text. The Reference data
screen says where comment presets, kitchen departments, recommendations and brands are authored,
and the gap map's row `4.7` no longer lists a blocker.

## References

- ADR 0016 (publication model, the open input and the legacy merchandising section), ADR 0017
  (no ingredient depletion), ADR 0024 and ADR 0055 (migration scope), ADR 0025, ADR 0027, ADR 0030,
  ADR 0031, ADR 0035 and ADR 0101 (shared components), ADR 0041 (station roles and routing),
  ADR 0136, ADR 0137 (weight and volume), ADR 0138, ADR 0149, ADR 0158
- `platform/docs/operations-gap-map.md` rows `4.7`, `4.2g`, `4.2h`, `2.1b` and the open-decision
  table; `platform/docs/operations-spec/catalog.md` §4.13 and the Delever match table;
  `platform/docs/frontend-information-architecture.md` §4.7; `platform/docs/delever-parity-matrix.md`
  (attributes, ingredients, tags, departments and the open questions); `platform/docs/domains/legacy-mapping-audit.md`
  (`tags`, `recommended_products`, `kitchens`) and `legacy-schema-profile.md`
- `CatalogSnapshotLoader`, `CatalogAuthoringService`, `StorefrontCatalogQuery`, `CatalogValidator`,
  `CommentPresetController`, `ProductCommentPresetController`, `KitchenStationController`;
  `V0016`, `V0030`, `V0256`, `V0302`, `V0378`, `V0430`, `V0443`, `V0448`
- `frontend/operations/src/app/app.routes.ts` (`reference-data`), `features/catalog/product-editor-page.ts`
  (the department picker), `features/settings/reference-data/`; `frontend/storefront/src/app/services/menu.service.ts`

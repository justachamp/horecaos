# Operations console — Catalog

The menu, as one restaurant's staff manage it. This specification covers
`apps/operations` section 4 of
[the information architecture](../frontend-information-architecture.md), and is
written so screens can be built from it without returning to the sources.

Authority order used throughout: the built backend (migrations `V0015`, `V0016`,
`V0018`, `V0019`, `V0020`, `V0021` and the `catalog`, `pricing`, `inventory`,
`media`, `tenancy` Java modules) beats the parity matrix; the parity matrix beats
Delever's live documentation; the legacy dashboard wins where staff habit is the
argument. Where a source is wrong for this product it is named and overruled.

---

## 0. The two jobs, and what follows from them

Catalog serves two people who share no working conditions.

**The author.** A manager, seated, with an afternoon, building or restructuring a
menu of 200–1500 items. Wants density, bulk edit, keyboard, undo, and a
validation report that lists every problem at once instead of one failed save at
a time. Tolerates a five-step publication flow because publishing is a
deliberate act.

**The service operator.** A line cook or shift manager, standing, one hand free,
mid-rush, who needs "плов — стоп" to be true across every channel within a
second. Tolerates nothing. No modal, no confirm, no save button, no page they
have to find.

Three design rules follow, and every view below obeys them.

1. **Authoring and availability are different surfaces with different write
   paths.** Authoring writes drafts and requires a publication to reach a
   customer (`CatalogAuthoringController`). Availability writes
   `catalog.location_offerings` / `inventory.positions` and takes effect
   immediately without republishing — the backend already separates these, and
   the UI must not blur them back together. `setOffering` says so in its own
   Javadoc: *"Marking a dish sold out must not require re-validating an entire
   menu."*
2. **The stop list is reachable from everywhere, not from Catalog.** It is bound
   to a global shortcut and a persistent header control in the operations shell,
   because the person who needs it is not browsing the menu. Catalog contains it
   too, but that is the second path, not the first.
3. **The section landing depends on the capability set, not on a fixed route.**
   An actor holding `catalog.author` lands on Products (4.1). An actor holding
   only `inventory.adjust` / `offering.manage` — the kitchen and shift roles —
   lands on Availability & stop list (4.6), and never sees the authoring tree at
   all. Capabilities are already modelled: `CATALOG_READ`, `CATALOG_AUTHOR`,
   `CATALOG_PUBLISH`, `OFFERING_MANAGE`, `INVENTORY_READ`, `INVENTORY_ADJUST`,
   `PRICING_READ`, `PRICING_AUTHOR`, `PRICING_ACTIVATE`, `MEDIA_UPLOAD`
   (`iam/api/Capability.java`).

### Shared conventions

| Concern | Rule |
|---|---|
| Scope | Every Catalog view carries a brand selector and, where the data is location-scoped (4.5, 4.6, 4.7), a location selector, in the header strip beside the title. Brand and location go **into the query**, never into a client-side filter. |
| Money | `bigint` minor units = whole som. Rendered `84 000 so'm`, right-aligned, tabular figures, ink-coloured. Never green, never red. |
| Dates | `DD.MM` in tables, `DD.MM.YYYY` only where the year disambiguates. 24h clock. Mono for times, ids, SKUs, ИКПУ codes. |
| Language | Console chrome ru/uz per user preference. Catalog **content** is authored per locale and the locale being edited is an explicit control, never inferred (see 4.2). |
| Empty value | `—`. |
| Empty state | A row spanning the table, keeping header and frame. |
| Concurrency | Every mutating call sends the row's `version` as the expected version (ADR 0031). A 409 renders as "Изменено другим пользователем" with a **Показать различия** link, never as a silent overwrite. |
| Confirmation | Required only for irreversible or wide-blast actions. The dialog names the object: *"Категория «Салаты» и 14 товаров в ней будут архивированы."* Modal state is the id of the record acted on, not a boolean (Togora §2h). |
| Focus | Visible primary-coloured focus outline; Escape closes; focus trapped and restored. The Togora prototype has none of this and it is a gap to fill, not a pattern to copy. |

---

## 4.1 Products — the brand library

**What it is for.** "Do we already have this dish, and is it in a state that can
be sold?"

**Layout.** Dense list with a persistent left rail of categories and a right
detail drawer. Not master-detail-by-navigation: an author checks twenty products
in a row, and a full page transition per product costs more than the drawer's
width. The drawer opens at 640px with a **Открыть полностью** link promoting it
to the full editor (4.2) for real work.

Delever's own release notes record that returning from a product edit must
preserve scroll position and the active category/type filter. That is the single
most-felt ergonomic property of this screen at 1000+ items and it is achieved
here by keeping filters in query params and never unmounting the list.

### Columns

| Column | Type | Source |
|---|---|---|
| ☐ | selection | client |
| Фото | 32px thumbnail | `catalog.media_relations` where `entity_type='PRODUCT'` and `role='PRIMARY'` → `media.assets.object_key`; renders a hairline placeholder box, never an emoji |
| Название | text, in the brand default locale | `catalog.translations.name` where `entity_type='PRODUCT'`, `locale = brand default` |
| Код | mono | `catalog.products.code` |
| Категория | chips, one per category the product sits in | `catalog.category_products` → `catalog.translations` (CATEGORY) |
| Вариантов | integer, right-aligned; `1` renders as `—` | count of `catalog.variants` where `status<>'ARCHIVED'` |
| Цена | money, or a range `28 000 – 46 000 so'm` across variants | `pricing.prices.amount_minor` for the resolved price book (`pricing.price_book_assignments`, brand scope, current instant) |
| ИКПУ | mono, or a warning chip **Нет ИКПУ** | `catalog.variants.mxik_code` falling back to `catalog.products.mxik_code` — the inheritance the validator implements in `Snapshot.effectiveClassification` |
| Статус | badge: Черновик / Активен / Архив | `catalog.products.status` |
| В меню | integer "n/4 филиалов" | count of `catalog.location_offerings` with `status='AVAILABLE'` over the product's variants, against the brand's location count |
| Опубликован | check or **Не опубликован** chip | product id present in `catalog.publication_items` of the current `PUBLISHED` publication for the brand |
| ⋯ | row action menu | — |

`Вариантов`, `В меню` and `Опубликован` are **links**, not text: clicking
`3/4 филиалов` opens 4.5 filtered to this product with the location axis
expanded. A number in a table always raises "which ones?" and answering it in one
click is cheaper than a second screen (Togora §2j).

### Filters

Two axes given deliberately different visual weight (Togora §2c), because they
are not the same kind of question.

- **Primary axis — status tabs with live counts computed before filtering:**
  `Все (612)` · `Активные (540)` · `Черновики (58)` · `Архив (14)` ·
  `Не опубликованы (23)` · `Без ИКПУ (61)` · `Без фото (88)`. The last three are
  work queues, not statuses, and that is exactly why they belong on the primary
  axis: they are what an author opens this screen to clear.
- **Secondary axis — the category rail** on the left, a tree from
  `catalog.categories` with per-node counts, multi-select, `Без категории` as the
  last node.
- **Dropdowns:** каталог (`catalog.catalogs` — a brand may hold several),
  тип узла (Простой / Составной / Модификатор — derived from variant count and
  `product_modifier_groups`), локация (restricts to products offered there).
- **Search:** one field, debounced 250ms, matching name in any locale, `code`,
  and variant `sku`. Server-side. Delever explicitly fixed a bug where fast
  typing dropped characters; the debounce must be on the request, never on the
  input value.
- No date range. Products are not a time series.

### Sort

Default: **status severity, then name.** Blocking states first — products with a
publication blocker (no active variant, no price, no default variant), then
products missing ИКПУ, then drafts, then active, then archived; alphabetical
within each band. An author opens this screen to find what is wrong, and a
purely alphabetical menu buries it. Column sort is available on Название, Цена,
Вариантов; the severity sort is the only one that is not a column, and it is
labelled `По состоянию` in the sort control.

Row severity uses three channels at once (Togora §2d): a 3px left rule, a
background tint, and a caption line under the name carrying the actual reason —
`Нет активной цены`, `Нет ИКПУ`, `Нет фото`. Strict precedence: a blocker
suppresses the warning caption. A bare badge would not tell the author what to
fix.

### Row actions (⋯)

| Action | Effect | Needs | Unavailable when | Confirm |
|---|---|---|---|---|
| Редактировать | opens 4.2 | `catalog.author` | archived | no |
| Дублировать | copies product, variants, modifier attachments, translations, media relations; new `code` suffixed `-copy`, status `DRAFT` | `catalog.author` | — | no |
| Скопировать ID | copies uuid to clipboard | — | — | no (toast) |
| Добавить в категорию… | picker, writes `catalog.category_products` | `catalog.author` | — | no |
| Задать ИКПУ… | inline classification editor for this product and its variants | `catalog.author` | — | no |
| Стоп во всех филиалах | sets every `location_offerings.status='UNAVAILABLE'` for the product's variants and writes an `AVAILABILITY_CHANGE` movement per stock item with a reason code | `offering.manage` at every affected location | product not offered anywhere | yes — names the count of locations |
| Архивировать | `status='ARCHIVED'` | `catalog.author` | already archived; present in the live publication (offer **Снять с публикации** instead) | yes — names the product and whether it is live |

**Archive, never delete.** The legacy dashboard shipped a hard
`DELETE products/delete/{id}` and staff will ask for it. It is refused:
`pricing.quote_lines.source_variant_id` and every order snapshot reference these
rows, and the publication model's whole promise is that history is answerable.
Archive is the same button with a recoverable outcome.

### Bulk actions

Selection is checkbox per row plus a header checkbox that selects **the current
filtered set**, reporting the true server-side count — Delever's "Выбрать все"
respects the active filter and says how many, and anything else is a trap at
1000 rows.

An action appears only when it is valid for **every** selected row; when a
selection makes one invalid, the action disappears rather than being disabled
with a tooltip nobody reads (Togora §2n). The selection bar states why:
*"Архивировать недоступно: 3 из 40 опубликованы."*

- **Задать ИКПУ и код упаковки** — one code applied to all, or a per-row inline
  table (4.12). This is the single most-used bulk action in this market.
- **Добавить в категорию / убрать из категории**
- **Изменить статус** (Черновик ↔ Активен)
- **Изменить цену** — hands off to 4.8's bulk price change with the selection
  preserved
- **Включить в меню филиала…** / **Убрать из меню филиала…**
- **Стоп / снять со стопа** across a chosen location set
- **Экспорт в Excel** — exports exactly the filtered selection with its columns
- **Архивировать**

### States

| State | Rendering |
|---|---|
| Loading | Skeleton rows preserving column widths; the filter bar is live immediately and its counts arrive separately |
| Empty (no products) | Frame retained, one row: *"В этом каталоге пока нет товаров"* plus **Создать товар** and **Импорт из Excel** |
| Empty (filter) | *"Нет товаров по фильтру"* plus **Сбросить фильтры** |
| Denied | The section is absent from navigation entirely for an actor without `catalog.read`. A deep link renders a 403 panel naming the missing capability and the scope it would be needed at — brand, not tenant |
| Error | Inline banner above the table with the Problem Details `code`, a **Повторить** button, and the previous data still on screen. Never an empty table on error |
| Sold out | Row shows a `СТОП` chip beside the SKU and an amber left rule; the caption names the location count and the reason code |
| Draft | Name in muted ink, `Черновик` badge; not selectable for publication-only bulk actions |
| Not in any menu | Grey `Нет в меню` chip on `В меню` — the most common cause of "why can't customers see it" |

### Keyboard

`/` focuses search · `j`/`k` move the row cursor · `Enter` opens the drawer ·
`Space` toggles selection · `e` opens the full editor · `s` toggles stop for the
current location · `c` copies the id · `Esc` clears selection then closes the
drawer. Row cursor is a real focusable element with `aria-selected`.

---

## 4.2 Product editor

**What it is for.** "Everything true about this dish, in one place, including the
things that will stop it being published."

**Layout.** Full page, identity header, then a tab group as a header extension
(Togora §2k) — the observed ceiling is nine tabs and this uses seven. Not an
accordion: an author moves between Варианты and Модификаторы repeatedly and
accordions lose position. Not a wizard: the product already exists.

A permanent right rail, 280px, shows **Готовность** — the live validation
findings for this product only, from `GET …/catalogs/{id}/validation` filtered by
entity path. The author sees the blockers while editing rather than at publish
time. This is the single highest-value divergence from Delever, whose validation
exists only as a pre-publication report.

Header identity line: thumbnail · name in the editing locale · `code` in mono ·
status badge · `Обновлён 21.08 14:32` · actions **Сохранить черновик**,
**Предпросмотр**, ⋯.

**Locale control.** A segmented `RU · UZ · EN` control in the header sets which
locale the text fields on every tab are editing, and each segment carries a
completeness dot. The brand default locale is marked and cannot be left empty —
`MISSING_TRANSLATION` is a publication blocker in `CatalogValidator`. Delever
models names as `{ru, en, uz}` objects and the legacy dashboard used a
`I18nNameDescriptionSchema` with all three required; matching that is right, but
making the editing locale explicit rather than showing three stacked inputs per
field is better, because a product card has ~8 localised fields and 24 inputs is
unreadable.

### Tab 1 — Основное

| Field | Type | Source | Notes |
|---|---|---|---|
| Название | text, per locale, required in default locale | `catalog.translations.name` | blocker if absent |
| Описание | textarea 2000, per locale | `catalog.translations.description` | |
| Код | mono text, unique per brand | `catalog.products.code` | immutable after first publication; editing offers `Дублировать` instead |
| Каталоги | multi-select | `catalog.catalog_products` | a product may sit in the main and the seasonal catalog |
| Категории | multi-select tree with per-category sort order | `catalog.category_products.sort_order` | |
| Статус | Черновик / Активен / Архив | `catalog.products.status` | |
| Отдел кухни | select | **not built — ADR 0016** (legacy merchandising disposition is an open input). Routes tickets in ADR 0041 | render disabled with the ADR note in a builder-facing comment, or omit until 0016's disposition lands |

### Tab 2 — Варианты

The variant is the priceable, sellable, stockable unit. The parent product is
not sellable — this is Delever's Главный/Вариативный split and Qoida's schema
already agrees (`pricing.prices.priceable_type='VARIANT'`,
`inventory.stock_items.variant_id`).

Inline editable grid, one row per variant, drag handle for `sort_order`:

| Column | Source |
|---|---|
| ≡ drag | `catalog.variants.sort_order` |
| Название варианта | `catalog.translations` (VARIANT) |
| SKU | `catalog.variants.sku` (unique per brand) |
| Ед. изм. | `catalog.variants.unit_code` (default `PIECE`) |
| По умолчанию | `catalog.variants.is_default` — radio, not checkbox; the DB enforces one per product (`ux_variant_single_default`) |
| Базовая цена | `pricing.prices.amount_minor` in the brand's active price book; editing writes a new price row and closes the old one — never an UPDATE, because `ux_price_current` is the determinism guarantee |
| ИКПУ | `catalog.variants.mxik_code`, placeholder showing the inherited product code in muted ink |
| Код упаковки | `catalog.variants.package_code` |
| Статус | `catalog.variants.status` |
| Модификаторы | count → opens tab 3 scoped to this variant (`catalog.variant_modifier_groups`) |

Two blockers surface here live: `PRODUCT_HAS_NO_ACTIVE_VARIANT` and
`PRODUCT_HAS_NO_DEFAULT_VARIANT` (raised only when there is more than one
variant). `VARIANT_HAS_NO_ACTIVE_PRICE` renders as a red cell, not a footnote.

Actions: **Добавить вариант** (opens with the product's ИКПУ pre-inherited),
**Дублировать вариант**, **Архивировать вариант** (confirm; blocked if it is the
only active one, blocked if it is the target of a `modifier_options.linked_variant_id`
that is still active — that is `MODIFIER_OPTION_LINKS_INACTIVE_VARIANT` caught
before it happens rather than after).

### Tab 3 — Модификаторы

Two panes. Left: the brand's reusable modifier groups (`catalog.modifier_groups`)
with a search. Right: groups attached to this product (`product_modifier_groups`)
and, below, per-variant overrides (`variant_modifier_groups`). Attaching is a
click, ordering is a drag.

Per attached group, shown read-only with an **Изменить группу** link into 4.4
(groups are brand-owned and shared — editing one here would silently change
another product, and that is the mistake to design out):

- `is_required`, `minimum_selections`, `maximum_selections`,
  `allow_same_option_multiple_times`
- option list with `code`, name, price
  (`pricing.prices` where `priceable_type='MODIFIER_OPTION'`),
  `maximum_quantity`, `linked_variant_id`, `mxik_code`

A capacity indicator per group — `Мин. 2 из 5 доступных` — precomputes
`MODIFIER_GROUP_MINIMUM_UNSATISFIABLE`, which the database check constraint
cannot catch because it does not count options.

**Not built and named:** hidden modifiers auto-selected by order type (Delever's
Скрытые модификаторы, used for packaging), nested variant-modifiers, and
modifier-level fallback to group values — all **ADR 0016**, listed in the matrix
as absent. Do not fake them with a free-text flag.

### Tab 4 — Фото

See 4.9 for the media contract. On this tab: an ordered gallery
(`catalog.media_relations.sort_order`) with `role` PRIMARY / GALLERY /
THUMBNAIL, drag to reorder, a dropzone, and per-variant photo slots. Every tile
shows the `media.assets.status` — an asset that is not `AVAILABLE` is a
publication blocker (`MEDIA_NOT_AVAILABLE`) and the tile says so on its face.

**Per-aggregator image override** — Delever's second-image-assigned-to-Wolt
behaviour — is **not built; ADR 0010** (the matrix names per-channel image
variants and a content hash as the two missing refinements). The tab reserves the
slot and states it.

### Tab 5 — Фискальные данные

`ИКПУ/MXIK`, `Код упаковки` for the product (the inherited default) with a
per-variant table beneath. Today both are plain `varchar(32)` columns on
`catalog.products` / `catalog.variants` / `catalog.modifier_options` with a
not-blank check and deliberately **no format validation** (`V0021`).

Everything else fiscal is **not built — ADR 0038 (Proposed)**: `marking_required`
and `marking_scheme`, `excisable`, `alcohol_by_volume_bp`, `age_restriction_years`,
`tax_profile_id` override, `source (MANUAL|IMPORT|POS_SYNC)`, `classified_by`,
and validation of the code against `catalog.mxik_reference`. When 0038 is
accepted these move into `catalog.fiscal_classifications` and the warnings become
blockers. Build this tab against the interim columns, and build the search
control as a **typeahead over a reference list** even while the list is a stub —
retrofitting free text into a validated picker is the expensive direction.

**No AI generation of ИКПУ.** Delever ships it behind a liability disclaimer. A
wrong code is a tax classification error on a legal document. Assistive search
over the official list with the operator selecting is the accepted form
(ADR 0038 alternatives table). AI assist on description and composition is fine
and belongs on tab 1.

### Tab 6 — Наличие

Read-mostly summary per location: offering status, fulfilment modes, stock
tracking mode, current binary state, and the last availability movement with its
actor and reason. Writes are possible here but the real surface is 4.6.

| Field | Source |
|---|---|
| Филиал | `tenant.locations` |
| В меню | `catalog.location_offerings.status` (AVAILABLE / UNAVAILABLE / HIDDEN) |
| Типы заказов | `catalog.location_offerings.fulfillment_modes` (`DELIVERY,PICKUP` default) |
| Время приготовления | `catalog.location_offerings.preparation_duration_override` |
| Учёт | `inventory.stock_items.tracking_mode` (BINARY / UNTRACKED; QUANTITY refused by the service) |
| Состояние | `inventory.positions.binary_available` |
| Изменено | latest `inventory.movements` — `occurred_at`, `actor_type`, `actor_id`, `reason_code` |

### Tab 7 — История

`audit.audit_events` for this product and its children, rendered as the flat
four-column event list the legacy prototype validated: `DD.MM` · `HH:MM` mono ·
действие · актор, where the actor column carries non-human actors as
first-class (`Система`, `POS-импорт`, `Excel-импорт`) per ADR 0027.

### Editor states

Loading (skeleton with tabs live) · Not found (404 panel with **К списку**) ·
Denied (read-only rendering for `catalog.read` without `catalog.author`; every
input disabled, no ghost save button) · Unsaved changes (blocks navigation with a
three-button dialog: Сохранить / Отменить / Продолжить редактирование) ·
Conflict (409 with a field-level diff) · Published (a persistent banner:
*"Этот товар опубликован. Изменения увидят клиенты только после публикации меню."*
— the single most important thing an author can misunderstand about this system).

---

## 4.3 Categories and ordering

**What it is for.** "In what order, and under what headings, does the customer
see the menu?"

**Layout.** Two-pane: a drag-and-drop tree on the left, an edit form on the
right. Tree because `catalog.categories.parent_category_id` is genuinely
hierarchical and `V0018` constrains a parent to the same catalog, so the tree is
the shape of the data. Drag because sort order is the whole point and a numeric
`priority` field — which is what the legacy dashboard shipped — makes reordering
ten categories an arithmetic exercise.

**Tree node:** drag handle · name (default locale) · child count · product count
(a link into 4.1 filtered) · status dot · `⋯`.

### Form fields

| Field | Type | Source |
|---|---|---|
| Название | per locale, required in default | `catalog.translations` (CATEGORY) |
| Описание | per locale | `catalog.translations.description` |
| Код | mono, unique per catalog | `catalog.categories.code` |
| Родительская категория | picker, restricted to the same catalog | `catalog.categories.parent_category_id` |
| Порядок | integer, set by drag; editable as a number for precision | `catalog.categories.sort_order` |
| Статус | Черновик / Активен / Архив | `catalog.categories.status` |
| Изображение | single media | `catalog.media_relations` (CATEGORY, PRIMARY) |
| Расписание | time windows | **not built — ADR 0036** owns schedules; `tenant.service_schedules` exists at location level, not per category |

### Products pane

Below the form, the category's products with `category_products.sort_order`,
drag-reorderable, plus **Добавить товары…** opening a picker that respects the
active filter and reports the true count of what "выбрать все" will add.

### Actions

Создать · Переименовать · Переместить (drag, or a picker for deep trees) ·
Архивировать (confirm, naming the child count and product count; children are
re-parented to the archived node's parent, never orphaned) · Изменить порядок
(drag) · Развернуть всё / Свернуть всё.

### States

Empty tree (*"Категории не созданы"* + **Создать категорию**) · Cycle
(`CATEGORY_TREE_HAS_CYCLE` is a validator blocker; the tree renders the cycle
path in red and refuses the drop that would create it — the drop is rejected
client-side and the server rejects it too) · Orphan
(`CATEGORY_PARENT_MISSING`) · A category with no active products renders muted
with `Пусто`, because an empty category is rendered to the customer as an empty
heading.

### Sort

The tree renders in `sort_order`, and that is the customer-facing order. This is
the one screen in Catalog that does **not** sort by severity: the author is
reading the menu as a customer will, and reordering it by problem would defeat
the screen.

### Keyboard

Arrow keys navigate the tree, `←`/`→` collapse/expand, `Alt+↑`/`Alt+↓` reorder
within the parent, `Alt+←` promotes a level, `Enter` edits the name inline.

---

## 4.4 Modifier groups

**What it is for.** "The reusable option sets — sizes, sauces, add-ons — that
products share."

**Layout.** List plus detail drawer. Brand-owned and shared by design
(`catalog.modifier_groups` has no product FK; attachment lives in
`product_modifier_groups` / `variant_modifier_groups`), which is what makes this
a separate screen rather than a subform. Delever models modifiers as a product
*type* instead; that is worse — it means "соус карри" appears in the goods list
alongside dishes and must be filtered out of every product query forever.

### Columns

| Column | Source |
|---|---|
| Название | `catalog.translations` (MODIFIER_GROUP) |
| Код | `catalog.modifier_groups.code` |
| Правило | rendered from `is_required`, `minimum_selections`, `maximum_selections` as `Обязательно, 1` / `Необязательно, до 3` / `2–4` |
| Повтор | `allow_same_option_multiple_times` |
| Опций | count of active `catalog.modifier_options` |
| Используется в | count of `product_modifier_groups` + `variant_modifier_groups`, a link |
| Без ИКПУ | count of options with no `mxik_code` and no classified `linked_variant_id` |
| Статус | `catalog.modifier_groups.status` |

### Detail drawer — options table

`code` · name per locale · price (`pricing.prices`, `MODIFIER_OPTION`) ·
`maximum_quantity` · `linked_variant_id` (a picker over active variants;
"этот модификатор — сам товар") · `mxik_code` · `package_code` · `sort_order` ·
`status`. Inline editable, drag-ordered.

A live capacity line above the table computes selectable capacity exactly as
`CatalogValidator` does — `options.size()`, or `Σ maximum_quantity` when repeats
are allowed — and turns red when `minimum_selections` exceeds it, with the
validator's own wording: *"Требуется 3 выбора, доступно 2"*.

### Filters and sort

Tabs: `Все` · `Обязательные` · `Необязательные` · `Не используются` ·
`Без ИКПУ`. Search over name and code. Sorted by severity: unsatisfiable groups
first, then groups with no options (`MODIFIER_GROUP_HAS_NO_OPTIONS`, a blocker),
then unused, then alphabetical.

### Actions

Создать группу · Дублировать · Добавить опцию · Архивировать (confirm naming
every product that uses it — *"Группа «Соусы» используется в 14 товарах"* — and
refused outright while any of them are in the live publication) · Bulk: задать
ИКПУ, изменить цены опций, архивировать.

---

## 4.5 Menus — the per-location, per-channel assortment

**What it is for.** "What does this branch actually sell, on which channel, at
what price?"

**This is the single biggest divergence between the built backend and what the
market requires, and the specification must be honest about it.**

What exists: `catalog.location_offerings (location_id, variant_id, status,
fulfillment_modes, preparation_duration_override)` — one row per branch per
variant, three states, and a fulfilment-mode list. Channels exist separately in
`tenant.sales_channels` with a `price_plane_channel_id`, and price books can be
assigned at `CHANNEL` scope (`pricing.price_book_assignments`, fixed and bound in
ADR 0036).

What does not exist: a **named Menu entity** bound to branches, per-channel item
enablement, per-item schedules, and per-item counted stock.
[IA Part 5 §2 and §3](../frontend-information-architecture.md) name this as a
data-model decision, and ADR 0036's covered list includes "per-channel menu item
enablement" without `V0020` building a table for it. **Owning ADR: 0036, with an
0016 amendment for the Menu entity itself. Neither is built.**

The screen is therefore specified in two layers, and the first is buildable
today.

### Layer A — offering matrix (buildable now)

**Layout.** A frozen-first-column matrix: rows are variants, columns are
locations. Not a list, because the question is comparative — "which branches are
missing this?" — and a list forces the operator to hold four branches in their
head. At one location the matrix degrades to a list and that is correct, since
the pilot is single-location.

Row group headers are categories, collapsible, carrying their own counts.

| Element | Source |
|---|---|
| Row label | product name · variant name · SKU (mono) |
| Цена | `pricing.prices` for the resolved book at the row's scope |
| Cell (per location) | `catalog.location_offerings.status` rendered as a three-state control: **В меню** (AVAILABLE) / **Стоп** (UNAVAILABLE) / **Скрыт** (HIDDEN) |
| Cell secondary line | `fulfillment_modes` as three tiny toggles: Дост · Самов · Зал |
| Cell tint | amber when UNAVAILABLE, muted when HIDDEN, dashed outline when no offering row exists at all — an unlisted item is visibly different from a stopped one |
| Готовность | `preparation_duration_override`, `—` when the location default applies |

`HIDDEN` and `UNAVAILABLE` are genuinely different and the UI must teach the
difference: hidden is not shown to the customer at all (seasonal, off-menu);
unavailable is shown as sold out (temporarily out). Delever conflates these into
a single toggle and its docs record that setting a price to 0 once deleted the
menu row — a warning about what conflation costs.

### Layer B — channel plane (needs ADR 0036 tables)

A second axis, switched by a segmented control above the matrix: **Локации** /
**Каналы**. In Каналы mode, columns come from `tenant.sales_channels` for the
tenant (`code`, `display_name`, `system_type`, `status`), each cell holding two
independent facts:

- `offered_on_channel` — boolean, **not built; ADR 0036**
- `price_on_channel` — resolvable **today** through
  `pricing.price_book_assignments` at `CHANNEL` scope, honouring the channel's
  `price_plane_channel_id` (the mechanism by which QR and kiosk take hall prices,
  which Delever ships as a global switch and Qoida gets for free from the price
  plane pointer)

**Separate these two.** Delever's menu conflates availability and price on one
toggle, and the parity matrix's own open question asks whether Qoida should split
them. It should. A price of zero must mean "free", never "not sold" — the
`ck_price_amount` constraint already treats zero as legitimate.

An `externally_priced` channel (`tenant.sales_channels.externally_priced`, true
for aggregators that price on their own side) renders its price column read-only
with an `Внешняя цена` chip. Editing it is meaningless and offering the control
is a lie.

### Filters

Status tabs with counts: `Все` · `В меню` · `Стоп` · `Скрытые` ·
`Не в меню` · `Без цены на канале`. Then: category rail (secondary axis),
location multi-select, channel multi-select, search over name/SKU. No date
range — this is a current-state view; history lives in 4.6's movement log.

### Actions

| Action | Effect | Needs | Unavailable | Confirm |
|---|---|---|---|---|
| Cell click | cycles В меню → Стоп → Скрыт, optimistic, immediate | `offering.manage` at that location | no capability at that location — the cell renders read-only rather than failing on click | no |
| Добавить товары в меню… | picker respecting the active category filter, reporting the true count | `offering.manage` | — | no |
| Массово: включить в меню | writes AVAILABLE across the selection × chosen locations | `offering.manage` at all | any selected variant is archived | yes when > 25 rows |
| Массово: стоп | UNAVAILABLE + a movement per stock item with a reason code | `offering.manage` + `inventory.adjust` | — | yes, with a reason picker |
| Массово: типы заказов | sets `fulfillment_modes` | `offering.manage` | — | no |
| Копировать меню в филиал… | **not built** — needs the Menu entity (ADR 0016 amendment). Delever's Копировать в меню is its most-cited chain feature; specify it, do not fake it with 400 individual writes | — | — | yes |
| Включить для агрегатора… | **not built — ADR 0036 / 0040.** Delever's one-button "enable everything for this aggregator at base price" is the correct shape for onboarding a marketplace and should be matched once the channel-item table exists | — | — | yes |
| Экспорт меню в Excel | current matrix, exactly as filtered | `catalog.read` | — | no |

### Sort

Category order, then `variants.sort_order` — the customer's order, because this
screen is read as a menu. A **По проблемам** toggle re-sorts to severity: no
price, then not offered anywhere, then stopped, then the rest. Two sort modes,
one toggle, because this screen genuinely serves both readings.

### States

Loading (matrix skeleton with frozen column) · Empty (*"В этом филиале нет
товаров в меню"* + **Добавить товары**) · Denied per-location (cells read-only,
a header note naming which locations are read-only) · Offline/error (the last
good matrix stays, cells become non-interactive, a banner offers **Повторить**) ·
Branch closed (`tenant.location_service_state.mode = 'FORCE_CLOSED'` renders the
column header with a `Закрыт` chip, the reason code and `effective_until` — the
matrix must not imply a branch is selling when its manager has closed it) ·
Channel archived (`sales_channels.status` — column greyed, publish to it refused).

### Keyboard

Arrow keys move the cell cursor. `Space` cycles the cell. `1`/`2`/`3` set В меню
/ Стоп / Скрыт directly. `Shift+↓` extends selection down a column, `Shift+→`
across a row. `f` fills the row from the current cell (the DataGrid fill-down the
parity plan already requires). This screen is where keyboard speed earns the most
in an afternoon of authoring.

---

## 4.6 Availability and the stop list

**What it is for.** "Плов кончился" — in one tap, from a phone, in the kitchen,
propagated to every channel immediately.

**This is the service-speed view and it is designed backwards from that one
gesture.** Everything else on the screen is secondary.

**Layout.** Two tabs, matching the vocabulary Delever's kitchen staff already
use (`Доступные продукты` / `На стопе`), over a large-target list — 56px rows,
one action per row, no drawer, no modal, no confirm. Touch first; the desktop
rendering is the same list at the same size. A grid of small cells is wrong here:
the operator is not comparing, they are finding one dish and hitting it.

Header: location selector (defaulting to the operator's bound location and hidden
entirely when they have exactly one), a big search field autofocused on open, and
two counts — `Доступно 412` · `На стопе 7`.

### Row

| Element | Source |
|---|---|
| Фото 40px | `catalog.media_relations` PRIMARY |
| Название · вариант | `catalog.translations` |
| SKU | `catalog.variants.sku`, mono |
| Category caption | `catalog.category_products` |
| Right control | **Стоп** / **Вернуть** button, 44px minimum target |
| On-stop rows only | who and when: `inventory.movements.actor_id`, `occurred_at` (`с 14:32, Азиз`), and the `reason_code` |

### The gesture

One tap writes `PUT …/locations/{locationId}/inventory/variants/{variantId}/availability`
with `available=false`. `InventoryService.setAvailability` records an
`AVAILABILITY_CHANGE` movement with its reason, actor and idempotency key, and is
a no-op when the state already matches — so a double tap is harmless. The row
moves to `На стопе` with an **Отменить** affordance in a toast for 8 seconds.

**No confirmation dialog.** The action is instantly reversible, it is recorded
with an actor, and the cost of a mis-tap (one dish briefly unavailable) is
smaller than the cost of a confirm step during a rush. Confirmation is required
only for the bulk variant.

**A reason is optional on a single stop and required on a bulk stop.**
`inventory.movements.reason_code` is nullable and `AvailabilityRequest` carries
`reasonCode`. Offer a four-chip picker inline on the row after the tap —
`Закончилось` · `Нет продукта` · `Оборудование` · `Другое` — which writes a
second nothing if ignored. Making the reason mandatory on the single-tap path
would destroy the gesture; making it optional on a 40-row bulk stop would destroy
the evidence.

### Bulk

Long-press or the header **Выбрать** enters selection mode. `Добавить в стоп (N)`
/ `Убрать со стопа (N)` mirror Delever's labels exactly, because staff migrating
from it already read them. Bulk requires a reason and one confirm naming the
count.

### Filters

Tabs `Доступные` / `На стопе` with live counts, plus a category chip row and
search. Nothing else. A date range on this screen would be furniture.

### Sort

`На стопе` sorts by **most recently stopped first** — the operator's next action
is almost always to return the thing they stopped an hour ago. `Доступные` sorts
by category then menu order, because the operator is scanning for a dish they can
picture in the menu's shape, not alphabetically.

### The availability explainer

A dish can be unbuyable for six independent reasons, and Delever offers no single
place to see which. Qoida should beat it here (IA "where operations beats
Delever" §6). Tapping the row's info affordance opens a compact panel resolving,
in order, exactly the projection ADR 0017 specifies:

| Layer | Source | Verdict line |
|---|---|---|
| Опубликовано | `catalog.publication_items` in the active `catalog.publications` | *"Нет в текущей публикации меню"* |
| В меню филиала | `catalog.location_offerings.status` | *"Скрыт в этом филиале"* |
| Тип заказа | `location_offerings.fulfillment_modes` | *"Только самовывоз"* |
| Наличие | `inventory.positions.binary_available` | *"На стопе с 14:32, Азиз — Закончилось"* |
| Филиал | `tenant.location_service_state.mode` + `reason_code` + `effective_until` | *"Филиал закрыт до 18:00 — авария"* |
| Канал | `tenant.sales_channel_locations.status`, `channel_fulfillment_modes` | *"Не продаётся в Telegram"* |
| Цена | `pricing.prices` | *"Нет активной цены"* |

Each line links to the screen that owns it. This panel is the answer to the most
common support question in this product and it costs one endpoint.

### States

Loading (skeleton rows; search live immediately) · Empty stop list (*"Ничего не
на стопе"* — a good state, rendered calmly, not as an error) · Denied (an actor
with `inventory.read` but not `inventory.adjust` sees the list with no buttons —
affordances omitted, not disabled) · Write failure (the row reverts with a red
flash and a **Повторить** chip in place of the button; never a toast that
disappears while the dish is still on sale) · Stale (`inventory.positions.position_sequence`
lets the client detect a change made elsewhere; the row updates in place with a
brief highlight) · Unlisted variant (a variant with no `inventory.stock_items`
row is *unavailable*, per `InventoryService`, and renders as `Не в наличии` with
a **Внести в наличие** action requiring `inventory.adjust`) · Quantity mode
(`tracking_mode='QUANTITY'` is accepted by the schema and refused by the service;
if such a row exists, render it read-only with *"Количественный учёт пока не
поддерживается"* rather than a control that will 409).

### Keyboard

`/` search · `j`/`k` · `Enter` or `s` toggles stop · `u` undoes the last stop ·
`Tab` switches tabs. The whole screen is operable without a pointer, which
matters on a kitchen terminal with a membrane keyboard.

### Not built, named

Per-day counted portions with an automatic daily reset (Delever's
`Кол-во для ежедневного остатка по умолчанию`, a company-level default that
auto-resets) is **ADR 0017's QUANTITY mode plus a scheduled seed that no ADR
owns**. Per-aggregator stop thresholds and stop *scope* + stop *source* (POS
terminal vs operator vs rule) are likewise unowned; ADR 0041 will bring the KDS
origin. Do not model these as a boolean with a comment.

---

## 4.7 Scheduled availability

**What it is for.** "Breakfast until 11:00, draught beer only in the hall after
17:00."

**Layout.** A list of named schedules with a weekly grid editor, plus an
assignment pane. Not a per-product time field: Delever puts `График продукта` on
each menu row, which means changing the breakfast window means editing forty
rows. A named schedule assigned to many items is the same feature with one edit.

`tenant.service_schedules` / `service_schedule_rules` / `service_schedule_exceptions`
already exist from `V0020` and are **location-and-fulfilment-mode scoped**
(`location_service_bindings`). Item-level scheduling is **not built**: ADR 0016's
sketch carried `sales_schedule_id` on `location_offerings` and `V0016` never
created the column — ADR 0036 records exactly this. **Owning ADR: 0036, with the
column belonging to `catalog.location_offerings`.**

### Schedule list columns

| Column | Source |
|---|---|
| Название | `tenant.service_schedules.name` |
| Окна | rendered from `service_schedule_rules` (`day_of_week`, `opens_at`, `closes_at`) as `Пн–Пт 08:00–11:00` |
| Предзаказ | `service_schedules.accepts_scheduled_orders` |
| Применён к | count of `location_service_bindings` + (once built) item bindings, a link |
| Исключений | count of `service_schedule_exceptions` still in the future |

### Weekly grid editor

Seven rows × 24 columns of 30-minute cells, click-drag to paint a window,
multiple windows per day supported (`sequence` on the rules table). Empty cells
render a dashed outline, not blank — you see the shape of the closed hours, which
is the actual planning question (Togora §2g).

### Exceptions

A dated list: `exception_date` (DD.MM), `closed_all_day`, `opens_at`/`closes_at`,
`label` (`Навруз`), `reason` (required, `varchar(400)`), `created_by`. Sorted
soonest first; past exceptions collapse behind **Показать прошедшие**. The DB
requires either closed-all-day with no times or open with both times — the form
enforces the same shape so the constraint is never the first thing the user hears
about it.

### Actions

Создать расписание · Дублировать · Назначить (multi-select over locations ×
fulfilment modes, and — when built — over menu items) · Добавить исключение
(reason required) · Архивировать (refused while bound; the dialog lists the
bindings).

### States

A schedule with no rules renders `Нет окон — филиал закрыт всегда` in red,
because an empty schedule silently closes a branch. A schedule whose windows do
not cover the current instant shows a live `Сейчас закрыто · откроется в 08:00`
line computed from `ServiceabilityResolver`'s `next_available_at`.

---

## 4.8 Prices

**What it is for.** "What does this cost, on this channel, at this branch, right
now — and change 300 of them at once."

**Layout.** Two views behind one tab group.

### 4.8a Price books (list + detail)

| Column | Source |
|---|---|
| Название | `pricing.price_books.name` |
| Валюта | `price_books.currency` (`UZS`) |
| Статус | `price_books.status` (DRAFT / ACTIVE / ARCHIVED) |
| Действует | `valid_from` – `valid_until` (`—` = бессрочно) |
| Приоритет | `price_books.priority`, right-aligned |
| Назначения | rendered from `price_book_assignments`: `Бренд` · `Филиал: Чиланзар` · `Канал: Uzum Tezkor` |
| Позиций | count of `pricing.prices` with `valid_until IS NULL` |

Detail: the assignment editor (scope BRAND / LOCATION / CHANNEL with its window
and priority) and the price table — `priceable_type` (VARIANT / MODIFIER_OPTION /
FEE), the entity, `amount_minor`, `valid_from`, `valid_until`.

**Determinism is a first-class UI concern here.** Overlapping books are settled
by `priority`, and channel outranks location (ADR 0036). The detail view carries
a **Проверить цену** simulator: pick a variant, a location, a channel and an
instant, and it shows which book won, why, and the resolved amount. Delever has
nothing like it and its own docs cannot say how many price planes exist. A
priority collision — two active books at the same scope with the same priority
and overlapping windows — renders as a red banner listing both.

### 4.8b Bulk price change (Delever's Прейскурант)

Delever's `price-changer` is documented as **a single screen recording with zero
prose**; the parity matrix flags the ambiguity and the IA answers it: Qoida
builds a *bulk change tool* over a filtered selection, and the *named price list*
half is already `pricing.price_books`. Both readings are served, neither is
guessed at.

**Layout.** A three-step inline flow on one page — filter, preview, apply — not a
wizard modal, because the operator will iterate on the filter and a modal makes
that a re-open.

1. **Selection.** The 4.1 filter set (category, status, channel, location,
   search), plus a price band. Shows `Выбрано 187 позиций`.
2. **Change.** Radio: `Процент` (+/− with a percent field), `Абсолютно`
   (+/− som), `Установить` (a fixed amount). Plus rounding: `До 1 000`,
   `До 500`, `Без округления` — this market prices in whole thousands and
   without rounding a 7% rise produces `43 870 so'm`.
3. **Preview and apply.** A table: товар · вариант · было · станет · разница
   (%), sorted by largest absolute change first so the outliers are on screen
   before anyone commits. Apply writes a new `pricing.prices` row per variant and
   closes the previous one — never an UPDATE, because `ux_price_current` and the
   audit trail both depend on the close-and-open shape.

Target book selector at the top: which price book receives the change. Applying
to an `ACTIVE` book is live immediately for new quotes and requires a confirm
naming the count and the book. Applying to a `DRAFT` book does not, and that is
the recommended path for a planned price change — say so in the UI.

**States:** a variant with no current price shows `—` in «было» and is included
only when the change type is `Установить`; the other two are undefined against no
base and the rows are excluded with a stated count. A `FEE`-type price row is
excluded from product bulk changes entirely and edited only in the book detail.

### Not built, named

Promotions, promo codes, markup (наценка), and the redemption ledger are
**ADR 0018, decided and unbuilt**. Per-channel *item* pricing beyond price-book
scope resolution is **ADR 0036**. Neither is faked with a discount field on the
product — the legacy dashboard carried `has_discount`, `discount`,
`discount_type`, `tag_discount` on the product row and that is precisely the
model ADR 0018 exists to replace.

---

## 4.9 Photographs

**What it is for.** "Every dish has a picture that will actually load, and the
publication will not be blocked by one that will not."

**Layout.** Two surfaces. The per-product gallery (4.2 tab 4) is where a photo is
attached. A separate **Фотопокрытие** grid is where the gap is closed: a
thumbnail wall of every active variant with no PRIMARY media relation, filtered
by category, with a dropzone per tile. Closing 88 missing photos one product page
at a time is the job this grid exists to prevent.

### The upload contract (ADR 0010, built)

1. Client requests an upload slot → `media.assets` row created with
   `status='PENDING_UPLOAD'`, an **immutable, server-allocated** `object_key`, a
   `declared_content_type`, `declared_size_bytes` and optionally
   `declared_checksum_sha256`.
2. Client PUTs the bytes to the presigned URL.
3. Finalize reads the object store back and fills `verified_content_type`,
   `verified_size_bytes`, `verified_checksum_sha256`; only then does the asset
   reach `AVAILABLE`.
4. Catalog references `media_asset_id` and **never a URL** — a URL in a business
   table is what made the legacy system's storage unmovable.

### Fields shown per tile

`media.assets.status` · `original_filename` · `width_px × height_px` ·
`verified_size_bytes` · `rejection_code` / `rejection_detail` when REJECTED ·
`role` and `sort_order` from `catalog.media_relations`.

### States

| State | Rendering |
|---|---|
| PENDING_UPLOAD | progress ring; the tile is not draggable |
| UPLOADED | *"Проверяется"* spinner |
| AVAILABLE | the image |
| REJECTED | red tile carrying `rejection_code` and `rejection_detail` verbatim, plus **Заменить**. A rejected asset must say why — the schema enforces it, so the UI has no excuse |
| DELETION_REQUESTED / DELETED | grey tile, **Открепить** only |
| Publication blocker | any referenced asset not `AVAILABLE` raises `MEDIA_NOT_AVAILABLE`, a blocker. The tile carries a red rule and the product's readiness rail names it |

### Actions

Загрузить (multi-file dropzone) · Сделать основным (`role='PRIMARY'`) ·
Изменить порядок (drag → `sort_order`) · Открепить (removes the
`media_relations` row, not the asset; no confirm — it is reversible) ·
Удалить ассет (confirm; only for assets not referenced elsewhere) ·
Загрузка по ссылке (**not built** — belongs with the Excel import's
`Ссылка на фото` column, ADR 0012, and must be SSRF-guarded; specify it here so
nobody builds a bare `fetch(url)`).

### Not built, named

Per-aggregator image variants and a per-asset content hash for downstream change
detection — **ADR 0010**, both named in the parity matrix as the two refinements
Delever proved useful and Qoida lacks.

---

## 4.10 Publication and channel readiness

**What it is for.** "Is this menu fit to show a customer, and make it live."

**Layout.** Three stacked regions on one page — readiness, channels, history —
because publishing is one decision informed by all three, and splitting them into
tabs means the operator publishes without having read the report.

### Region 1 — readiness report

Sourced from `GET /api/v1/control-plane/tenants/{t}/brands/{b}/catalog/catalogs/{id}/validation`,
which returns `publishable` plus a list of findings with `severity`, `code`,
`entityType`, `entityId`, `entityCode`, `detail`. Codes are stable strings so the
UI translates them; `detail` is the fallback, not the primary text.

Header: a single verdict line — **Готово к публикации** or
**N блокирующих проблем** — then two grouped lists.

| Finding code | Severity | Renders as |
|---|---|---|
| `PRODUCT_HAS_NO_ACTIVE_VARIANT` | blocker | «Товар без активного варианта» |
| `PRODUCT_HAS_NO_DEFAULT_VARIANT` | blocker | «Не выбран вариант по умолчанию» |
| `VARIANT_HAS_NO_ACTIVE_PRICE` | blocker | «Нет активной цены» |
| `MODIFIER_GROUP_HAS_NO_OPTIONS` | blocker | «Группа модификаторов без опций» |
| `MODIFIER_GROUP_MINIMUM_UNSATISFIABLE` | blocker | «Требуется больше выборов, чем доступно опций» |
| `MODIFIER_OPTION_LINKS_INACTIVE_VARIANT` | blocker | «Модификатор ссылается на неактивный вариант» |
| `CATEGORY_TREE_HAS_CYCLE` | blocker | «Цикл в дереве категорий» (renders the path) |
| `CATEGORY_PARENT_MISSING` | blocker | «Родительская категория не найдена» |
| `MISSING_TRANSLATION` | blocker | «Нет названия на языке бренда» |
| `MEDIA_NOT_AVAILABLE` | blocker | «Изображение не готово» |
| `OFFERING_REFERENCES_UNKNOWN_VARIANT` | blocker | «Филиал предлагает неизвестный вариант» |
| `FISCAL_CLASSIFICATION_MISSING` | warning today | «Нет ИКПУ/MXIK» |
| `FISCAL_CLASSIFICATION_NOT_ENFORCED` | warning, catalog-level | «N позиций без ИКПУ — агрегаторы отклонят меню» |
| `PRICING_VALIDATION_NOT_WIRED` | warning, catalog-level | «Проверка цен не выполнялась» |

Every finding row carries the entity name and a **deep link to the exact tab of
the exact editor** that fixes it — `entityType` + `entityId` are in the payload
precisely so this is possible, and a finding you cannot click is a finding
somebody re-searches for by hand. Grouped by code with a count, expandable;
sorted blockers first, then by count descending, because forty instances of one
code are one fix.

The two catalog-level warnings are rendered as banners above the list, not as
list rows. `PRICING_VALIDATION_NOT_WIRED` in particular means a check **did not
run** — a different and more alarming thing than a check that failed — and the
validator emits it on every report for exactly that reason.

**When ADR 0038 is accepted**, `FISCAL_CLASSIFICATION_MISSING` becomes a blocker
and two more join it: `FISCAL_DELIVERY_FEE_UNCLASSIFIED` and
`FISCAL_RESTRICTED_NODE_ON_UNVERIFIED_CHANNEL`. Build the severity from the
payload, never from a client-side table, so this is a backend change only.

### Region 2 — channels

One card per row from `tenant.sales_channels` (tenant-scoped): `display_name`,
`system_type` (WEB / IOS / ANDROID / TELEGRAM / KIOSK / QR_TABLE / CALL_CENTRE /
AGGREGATOR / POS), `status`, `externally_priced`, price plane, and the currently
live publication for this brand + channel from `catalog.publications` where
`status='PUBLISHED'` — `content_hash` (mono, 8 chars), `activated_at`,
`created_by`.

Per card: **Опубликовать** and a `Черновик отличается` / `Актуально` chip
computed by comparing the draft's content hash with the live publication's. That
comparison is free — `content_hash` is exactly what it is for, and it also means
"publishing again would change nothing" can be said before the click.

Archived channels render greyed with publication refused; the publication service
already rejects an unregistered or archived channel code.

### Region 3 — publication history

| Column | Source |
|---|---|
| Когда | `catalog.publications.created_at` / `activated_at` / `retired_at` |
| Канал | `publications.channel` |
| Статус | `publications.status` (VALIDATING / READY / REJECTED / PUBLISHED / RETIRED) |
| Хеш | `publications.content_hash`, mono 8 chars |
| Позиций | count of `publication_items` |
| Кто | `publications.created_by` |
| Проблемы | count from `publications.validation_report` jsonb |

Sorted newest first. A `REJECTED` row is kept and clickable — the service records
rejections deliberately so *"why did publishing fail an hour ago"* has an answer,
and the UI must not hide the row that answers it.

### Actions

| Action | Effect | Needs | Unavailable | Confirm |
|---|---|---|---|---|
| Проверить | GET validation, no side effect | `catalog.read` | — | no |
| Опубликовать | POST publication: snapshot → validate → retire previous → activate, in one transaction | `catalog.publish` at brand scope | channel archived or unregistered; already publishing | yes — names the channel, the item count and whether a live menu is being replaced |
| Откатить к публикации | POST activate on an earlier publication; republishes the snapshot, never edits history | `catalog.publish` | target is `REJECTED` (refused server-side: you cannot roll back to a menu already known broken) | yes — names the publication's date and hash |
| Скачать отчёт | the validation report as a text file | `catalog.read` | — | no |
| Предпросмотр | render the draft as a channel will | `catalog.read` | see below | no |

A publish attempt that fails validation returns **HTTP 200 with the report**, not
an error status — a considered "no" is a completed request. The UI must render it
as a result panel, not as a red toast: the operator now has the list they need.

### Aggregator preview and pre-publication check

Delever ships both (`Предпросмотр меню` in mobile and desktop modes for Glovo,
Wolt, Yandex Eats and Bolt Food; `Предварительная проверка меню` emitting a
downloadable deficiency report before pushing to Yandex Eats or Uzum Tezkor).
Both are worth matching and both are **ADR 0040, not built**. Until then the
preview button renders the storefront projection only, and says so, rather than
implying an aggregator's rendering it cannot know.

### States

Loading (verdict line as a skeleton; findings stream in) · Clean
(*"Ошибок нет"*, calm, with the publish button primary) · Publishing (button
becomes a progress state; the page does not navigate) · Rejected (result panel
with the findings, publish button re-enabled) · Denied (`catalog.read` without
`catalog.publish` sees the whole page and no publish buttons) · No live
publication (the channel card says `Меню не опубликовано` in amber — customers
see nothing at all, which is the most consequential state on this screen and must
not be a subtle grey).

---

## 4.11 Import: Excel and POS

**What it is for.** "Get 800 items in without typing them, and see exactly what
happened to each one."

**Layout.** A wizard for the import itself, and a persistent jobs list beneath
it. The wizard is the one place a modal ladder is right, because the steps are
strictly ordered and the operator must not wander off mid-import.

### 4.11a Excel

Steps: **Файл** (dropzone; **Скачать шаблон** for create and update variants) →
**Сопоставление** (column → field mapping, remembered per brand) → **Проверка**
(server-side dry run) → **Результат**.

The dry run is not optional. The legacy dashboard already shipped it —
`POST products/import-jobs/{mode}?dry_run=true` returning
`{total_rows, success_rows, failed_rows, products_updated, variants_updated}` —
and staff will expect it. Qoida keeps the summary and adds what the legacy lacked:
**a row-level result table**. Per row: line number, the source values, the
outcome (`Создан` / `Обновлён` / `Пропущен` / `Ошибка`), and for an error the
stable code and the offending column. **Never a silent skip** — the IA names this
explicitly as a place operations beats Delever, whose importer drops unmapped
external ids without a word.

Columns the template must carry: `code`, name per locale, description per locale,
category code, SKU, unit, price, `ИКПУ`, `код упаковки`, and
`Ссылка на фото` (Delever's image-by-URL column, fetched server-side and
SSRF-guarded — an operator-supplied URL fetched by the server is an SSRF vector
and the guard is not optional).

Export is the same table shape, so an export → edit → import round trip is
lossless. Export respects the active filter.

### 4.11b POS sync

`ADR 0012` owns the mechanism — raw evidence, staging, deterministic diff,
mapping conflicts, versioned fields — and it is **not built**. The screen it
implies:

- **Соответствия** — a persistent mapping table of external id ↔ Qoida entity
  with `Связано` / `Не связано` / `Конфликт` states and manual pairing. This
  table is what makes a re-import idempotent, and Delever's absence of it is why
  its imports silently skip.
- **Прогоны** — job history with per-item outcomes.
- Import language selection per integration, display-order carry-over from the
  POS, duplicate modifier/variant de-duplication, price re-import as an opt-in
  toggle (re-importing prices over locally-set ones is the classic destructive
  default).

The parity matrix records that ADR 0012 is scoped to POS sources and that
operator-driven bulk import needs the same machinery. **Widen ADR 0012 rather
than building a second importer**; two import paths with two result models is how
a system ends up unable to say what changed a menu.

### Jobs list

| Column | Source |
|---|---|
| Когда | job `created_at` — **not built; ADR 0012** |
| Источник | Excel / iiko / R-Keeper / 1C |
| Режим | Создание / Обновление, dry-run flag |
| Строк | total / success / failed |
| Статус | Выполняется / Завершён / Завершён с ошибками / Отменён |
| Кто | actor |

Sorted newest first. A failed job's report is downloadable and its rows are
re-runnable after correction.

### States

Loading · Empty (*"Импортов ещё не было"* + **Скачать шаблон**) · File rejected
(wrong type/size, stated before upload) · Dry run with zero valid rows (the
**Применить** button is absent, not disabled) · Partial success (the result
banner is amber and states both counts; success is never claimed on a partial) ·
In progress (progress by row count, and the wizard may be left — the job
continues and appears in the list).

---

## 4.12 Fiscal classification workbench (ИКПУ/MXIK)

**What it is for.** "Every priceable node carries a legal classification code,
before an inspector or an aggregator finds one that does not."

This screen exists because ADR 0038 says it must: *"because it is a wall, the
tools to pass it belong to this decision — bulk assignment across a filtered
selection, a per-brand coverage report."* Delever ships bulk ИКПУ editing
precisely because it is the daily reality of onboarding here.

**Layout.** A coverage header over an inline-editable data grid. Not a form:
the operator is filling 300 cells, and fill-down plus paste-a-column is the
entire ergonomic argument.

### Header — coverage

Three figures with the sources that already support them:
`Варианты: 540 из 601 (90%)` · `Модификаторы: 88 из 140` ·
`Доставка (FEE): не классифицирована`. The partial indexes
`ix_variants_unclassified` and `ix_modifier_options_unclassified` exist in `V0021`
to serve exactly this question. Each figure links to the grid filtered to the
gap.

### Grid columns

| Column | Source |
|---|---|
| ☐ | selection |
| Тип | VARIANT / MODIFIER_OPTION / FEE |
| Товар · вариант | `catalog.translations` |
| SKU / код | `catalog.variants.sku`, `catalog.modifier_options.code` |
| Категория | `catalog.category_products` |
| ИКПУ / MXIK | editable; `catalog.variants.mxik_code`, `modifier_options.mxik_code`, falling back to `catalog.products.mxik_code` shown in muted ink as the inherited value |
| Код упаковки | editable; `package_code` |
| Источник | **not built — ADR 0038** (`fiscal_classifications.source`) |
| Кто, когда | **not built — ADR 0038** (`classified_by`, `classified_at`) |
| Маркировка · акциз · возраст | **not built — ADR 0038** |

Inherited values render distinctly from own values, because a variant showing its
product's code must not read as classified in its own right — the validator's
inheritance rule (`effectiveClassification`) is what makes a single-variant dish
classifiable in one place, and the UI has to teach it.

### Editing

Inline cell edit, `Enter` commits and moves down, `Ctrl+D` fills down from the
cell above, paste from a spreadsheet fills a column. The ИКПУ cell is a
**typeahead over the reference list** (`catalog.mxik_reference` — not built,
ADR 0038) showing `code — label_ru`; until the list exists it accepts free text
with the same control shape, so the retrofit is data, not UI.

No format validation client-side. `V0021` is explicit: the code's shape belongs
to the official list, not to a regex someone will have to migrate. What **is**
checked is that a present code is not blank — an empty string satisfies "set"
while classifying nothing, and would make the coverage figure lie.

### Bulk

Select rows → **Присвоить ИКПУ** (one code to all, with the affected count named)
or **Редактировать построчно** (a compact table of just the selection). This is
Delever's `Массовое редактирование товаров` and it is the right shape.

### States

Fully classified (a green summary line, and the grid still reachable) · Partially
classified (the default; the gap filter is the landing) · Unverified code
(once `mxik_reference` exists: an amber cell with `Код не найден в справочнике`,
non-blocking until ADR 0038 is accepted, blocking after) · Denied (`catalog.read`
without `catalog.author` renders read-only) · Save conflict (per-cell 409 with
the other value shown).

---

## 4.13 Reference data

**What it is for.** The small vocabularies products depend on. One screen, a left
rail of vocabularies, a simple list-and-form on the right. Deliberately three
clicks away: an author touches these monthly, not hourly.

| Vocabulary | Status |
|---|---|
| Теги | **not built — ADR 0016** (legacy merchandising disposition, an open input). Storefront badges and filter facets |
| Ингредиенты | **not built — ADR 0016.** A customer-facing composition label, **not** a bill of materials. Delever does not have a BOM either; the parity brief's assumption is corrected in the matrix and must not be re-introduced |
| Отделы кухни | **not built — ADR 0016 / ADR 0041.** Routes tickets and segments kitchen load; the highest-value item on this list |
| Атрибуты | **not built — ADR 0016.** Whether these are the variant axis or a spec-sheet vocabulary is an open question in the matrix; decide before building |
| Комментарии к продукту | **not built — ADR 0016.** Must be a controlled vocabulary with ids, not free text, because R-Keeper transports them as modifiers |
| Рекомендованные товары | **not built — ADR 0016.** Cross-sell, filtered to active + in-menu + not-stopped |
| Бренды | Built, but it is **tenancy**, not catalog: `tenant.brands`. Link out, do not duplicate the CRUD |

Nothing on this screen should be built before its disposition is decided. Listing
them here is the point: they are named, sourced, and unowned, so nobody
improvises one into the product editor as a free-text field.

---

## What Delever has that we should match

| Capability | Verdict |
|---|---|
| Two-layer model: tenant catalog + per-branch Menu bound to channels | **Match.** This is the correct shape and Qoida lacks the Menu entity. IA Part 5 §2. It is what copy-menu, bind-to-branch, per-channel price and per-item stock all hang off |
| Per-channel price overrides, several planes simultaneously | **Match, and already better.** `price_book_assignments` at CHANNEL scope with `priority` and a `price_plane_channel_id` pointer is an open map keyed by channel id, which the matrix's own open question recommends over a fixed enum. Delever cannot say how many planes it has |
| Stop-list operated from the kitchen, propagating to every channel | **Match exactly, including the labels** `Добавить в стоп (N)` / `Убрать со стопа (N)` / tabs `Доступные продукты` / `На стопе`. Staff migrating from Delever already read these |
| Bulk ИКПУ / package-code editing | **Match.** 4.12. It is the daily reality of onboarding in this market |
| Excel export and import with a photo-URL column | **Match, and beat** with a mandatory dry run and per-item outcome reporting. Delever skips unmapped rows silently |
| Pre-publication validation with a downloadable deficiency report | **Match and beat.** Qoida's `CatalogValidator` already produces stable codes with entity paths; Delever's report is a text file. Qoida makes every finding clickable and shows findings *while editing* (4.2's readiness rail), not only at publish |
| Copy menu into another menu / branch | **Match** once the Menu entity exists. Explicitly the chain feature Delever's release notes call out |
| Mass "enable for aggregator" at base price | **Match** once channel-item enablement exists. Onboarding a marketplace by toggling 600 items by hand is not a workflow |
| Per-item sale schedule | **Match the capability, beat the design.** Named schedules assigned to many items, not a time field per menu row |
| Product types Главный / Вариативный / Простой / Модификатор | **Match** — Qoida's product + variants + shared modifier groups expresses all four without a type enum. A "Простой" product is a product with one variant |
| Combo (Комбо) with choice-sets and per-variant price maps | **Match later — ADR 0016, not built.** Real demand, real complexity; not pilot |
| Hidden modifiers auto-selected by order type (packaging) | **Match later — ADR 0016.** Small, and it is how packaging charges reach the receipt |
| Product physical/nutritional attributes, catchweight, splittable, portions | **Match later — ADR 0016.** `splittable` and marking interact (ADR 0038 forbids splittable on marked goods), so build them together |
| Аggregator menu preview (mobile/desktop, per marketplace) | **Match later — ADR 0040** |
| ABC-XYZ analysis, demand forecasting, holidays, kitchen buffer | **Later — ADR 0043.** Analytics, not catalog; and the forecast's 09:00→09:00 business day is a cross-cutting decision the reporting ADR owns |
| Auto-add rules (plain, product-triggered, portion-band) | **Match later.** Portion-band is unimplementable without portions as a product attribute — ADR 0016 first |
| **Skip: AI generation of ИКПУ / package code** | A wrong code is a tax classification error on a legal document, and generating it transfers that risk to Qoida invisibly. ADR 0038 permits assistive search with a human selecting. AI on descriptions is fine |
| **Skip: Рецепты and general editorial CMS** | Website-builder work, and Delever's static-page editor accepts raw HTML — an XSS surface pointed at the tenant's own customers |
| **Skip: ingredient-level BOM and recipe costing** | Delever does not have it either; the matrix corrects the brief's assumption. Depletion lives in the POS |
| **Skip: price 0 meaning "not sold"** | Delever's conflation of price and availability. Zero is a valid price for a free modifier; `ck_price_amount` already says so |
| **Beat: the availability explainer** | Six independent reasons a dish can be unbuyable, one panel that resolves all of them in order. Delever has no such screen |
| **Beat: validation while editing** | The readiness rail in 4.2, not a report at the end |
| **Beat: the price simulator** | Which book won, and why. Delever's own docs cannot answer this |

---

## What the legacy dashboard did that staff will expect

Read from `legacy-archive/qoida-dashboard/src` — `types/Product.ts`,
`types/Category.ts`, `schemas/product.schema.ts`, `schemas/category.schema.ts`,
`pages/Settings/Products.page.tsx`, `pages/Settings/Categories.page.tsx`.

| Legacy behaviour | Disposition |
|---|---|
| `name` and `description` as `{en, ru, uz}` objects, all required | **Keep.** `catalog.translations` with a per-locale row; the brand default is a publication blocker |
| Product carries `category` (one) and `kitchen` (one) | **Keep the shape, extend it.** Qoida allows several categories via `category_products`. `kitchen` is the kitchen department — **not built, ADR 0016/0041** — and staff will look for it on the product form |
| `image` as a single file uploaded multipart with the create/update call | **Change, and explain it.** ADR 0010's presign → upload → verify flow means the photo is uploaded separately and can be pending. The editor must show that state rather than pretending the old synchronous behaviour |
| `status_id` on / off | **Keep as** `DRAFT` / `ACTIVE` / `ARCHIVED`. Two states were not enough to express "being built" |
| `priority` (integer) for category order | **Keep the field, change the gesture.** Drag sets `sort_order`; the number stays editable for precision |
| `time_enabled` + `start` + `finish` `HH:MM` per product | **Keep the capability.** This is per-item scheduling and staff already use it. Qoida models it as a named schedule (4.7) — **not built, ADR 0036** — and must not lose it |
| `stock_enabled` boolean per product | **Keep as** `inventory.stock_items.tracking_mode` (BINARY vs UNTRACKED) |
| `has_discount` / `discount` / `discount_type` / `tag_discount` on the product row | **Do not carry over.** ADR 0018 owns discounts as priority-ranked rules with recorded sources; a discount column on the product is exactly the non-deterministic pricing that ADR replaces. Migration maps these to promotion rules |
| Excel import with `dry_run` and a `{total_rows, success_rows, failed_rows, products_updated, variants_updated}` summary | **Keep, and extend** with per-row results. Staff already trust the dry run; taking it away would be a regression |
| Import in two modes, `create` and `update` | **Keep.** The distinction prevents an update file creating 800 duplicates |
| Copy-id button on every row | **Keep.** Support and POS reconciliation use it constantly |
| Hard delete (`DELETE products/delete/{id}`) | **Refuse, and say why.** Order and quote history reference these rows. `Архивировать` is offered in its place, in the same position in the row menu |
| Debounced search (500ms) over a flat product list scoped by `vendor_id` | **Keep at 250ms**, server-side, scoped by brand + catalog. Delever's own release note about dropped characters under fast typing is the warning |
| Dispatcher role sees products read-only | **Keep as** capability-derived: `catalog.read` without `catalog.author` renders the same screens with no write affordances |

---

## Data the backend does not have yet

Named precisely, with the owning ADR. Nothing in this list may be improvised into
an existing table.

| Missing data | Where it is needed | Owner |
|---|---|---|
| A named **Menu** entity between catalog and channel, bound to locations | 4.5, copy-menu, bind-to-branch | **ADR 0016 amendment** — none exists; IA Part 5 §2 names it as a data-model decision |
| `offered_on_channel` per (item, channel) — separate from price | 4.5 layer B | **ADR 0036** (listed in its covered scope; `V0020` did not build the table) |
| Per-item sale schedule (`sales_schedule_id` on `catalog.location_offerings`) | 4.7, 4.5 | **ADR 0036** (ADR 0016 sketched the column; `V0016` never created it) |
| Counted per-item stock with a daily default and automatic reset | 4.6 | **ADR 0017** — `QUANTITY` mode is decided and refused by the service; the scheduled daily seed is unowned |
| Stop **scope** and stop **source** (operator / POS terminal / rule) | 4.6 | **ADR 0017 + ADR 0041** |
| Per-aggregator stop threshold | 4.5, 4.6 | **ADR 0040** |
| `catalog.fiscal_classifications` — `marking_required`, `marking_scheme`, `excisable`, `alcohol_by_volume_bp`, `age_restriction_years`, `tax_profile_id`, `source`, `classified_by`, `classified_at` | 4.2 tab 5, 4.12 | **ADR 0038 (Proposed)** |
| `catalog.mxik_reference` — `code`, `parent_code`, `label_ru/uz/en`, `default_package_codes`, validity window | 4.12 typeahead | **ADR 0038 (Proposed)** |
| Blocker-severity fiscal findings: `FISCAL_DELIVERY_FEE_UNCLASSIFIED`, `FISCAL_RESTRICTED_NODE_ON_UNVERIFIED_CHANNEL` | 4.10 | **ADR 0038 (Proposed)** |
| Combo/bundle products with choice-sets and per-variant price maps | 4.2 | **ADR 0016** |
| Nested variant-modifiers; hidden modifiers auto-selected by order type; modifier-level fallback to group values | 4.2 tab 3 | **ADR 0016** |
| Product physical and nutritional attributes: weight, measure, КБЖУ, `splittable`, catchweight + weight quantum, **portions as a decimal** | 4.2 tab 1 | **ADR 0016** (portions also block auto-add type 3) |
| Kitchen department on the product | 4.2, ticket routing | **ADR 0016 disposition + ADR 0041** |
| Tags, ingredients, attributes, product-comment presets, recommended products | 4.13 | **ADR 0016** (explicitly an open input: legacy merchandising disposition) |
| Per-channel image variants; per-asset content hash | 4.9 | **ADR 0010** |
| Image fetch by URL, SSRF-guarded | 4.9, 4.11a | **ADR 0010 + ADR 0012** |
| Excel import job entity: mapping profile, dry-run result, per-row outcomes | 4.11a | **ADR 0012** — currently scoped to POS sources only and must be widened |
| POS external-id mapping table with linked / unlinked / conflict states | 4.11b | **ADR 0012** |
| Auto-add rules: plain, product-triggered, portion-band | 4.9 of the IA | Unowned; closest **ADR 0018 / ADR 0019**. Needs a decision before design |
| Aggregator menu preview and per-marketplace pre-publication checks | 4.10 | **ADR 0040 (Proposed)** |
| ABC-XYZ, demand forecasting, holiday calendar, kitchen buffer, and the business-day boundary that crosses midnight | not in this section | **ADR 0043** |
| Promotions, promo codes, markup (наценка), redemption ledger | 4.8 | **ADR 0018** — decided, unbuilt |
| Tenant-level catalog switches (`Использовать логику остатков`, QR/kiosk price plane) | 4.5, 4.6 | **ADR 0030** scoped configuration; the QR/kiosk half is already expressible as `sales_channels.price_plane_channel_id` and needs no switch |

### One correction to a source

`catalog.products.tax_category_code` exists in `V0016` and is unused. ADR 0038
drops it in the same migration that creates `catalog.fiscal_classifications`,
"because a second unenforced classification column would be picked up by exactly
one adapter and disagree with this table forever." **Do not surface
`tax_category_code` in any screen.** It is a field with no meaning that would
acquire one the moment an operator typed into it.

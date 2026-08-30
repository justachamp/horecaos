# Operations spec — Settings

`apps/operations` · section 10 of [the frontend information architecture](../frontend-information-architecture.md) ·
audience: one restaurant's own admins and managers, never HorecaOS staff.

Sources, in authority order: [the Delever parity matrix](../delever-parity-matrix.md) §Настройки and
§Настройки → Интеграции · Delever's live documentation · `legacy-archive/qoida-dashboard` ·
the IA · [the Togora report](../togora-prototype-report.md) · the built backend
(`src/main/resources/db/migration/`).

---

## 0. The problem this section has to solve

Delever's settings area is 47 catalogued capabilities spread over four navigation levels, with
delivery tariffs in two different places under the same word (*Тарифы* is both the SaaS
subscription and the delivery rate table), loyalty under order settings in v1 and under marketing
in v2, and the beta toggle on a personal profile page while being tenant-global. The legacy HorecaOS
dashboard had the opposite failure: three settings pages total, with the whole branch
configuration — hours, delivery pricing, Telegram routing — crammed into one `Конфиг` tab of
`Vendor.detail.page.tsx` as an untyped JSON blob (`work_time`, `delivery`, `prices_per_km`,
`peak_hours`).

Both fail the same way: **a person cannot find a thing, and cannot tell what level a value came
from.** HorecaOS has a real answer to the second problem that neither competitor has — ADR 0030
resolves every scoped value down `PLATFORM → TENANT → BRAND → LOCATION` and can explain itself.
The single most important design instruction in this document is therefore:

> **Every value on every settings screen shows where it came from, and every screen states the
> level it is currently editing.** A settings screen that renders a flat form is wrong even when
> every field in it is correct.

### Three organising rules

1. **One scope bar, always visible, always the first thing read.** It names the brand and the
   location being edited. Changing it re-resolves the whole screen; it never navigates away.
2. **Settings hold what other screens *read*. They never hold what a person *authors*.** Promotions,
   campaigns, menus, zones and courier rates are authoring surfaces and live in Marketing, Catalog
   and Delivery. Settings holds the registries and policies those surfaces resolve against. This
   is why HorecaOS's Settings is fourteen screens where Delever's is forty-seven.
3. **A settings screen earns its place by being opened during setup or during an incident.**
   Anything opened neither is reference data (10.10) or belongs to control-plane.

### Navigation

A grouped left rail, not an alphabetical list. Group headings are nouns a restaurant manager uses.

| Group | Screens |
|---|---|
| **Заведение / The business** | 10.1 Brand profile · 10.2 Locations · 10.12 Languages & formats |
| **Продажи / Selling** | 10.4 Sales channels · 10.5 Channel setup · 10.3 Order policy · 10.13 Delivery policy |
| **Деньги и налоги / Money and tax** | 10.6 Payment methods · 10.7 Fiscalization · 10.14 Printing & receipts |
| **Сообщения / Messages** | 10.9 Notifications |
| **Подключения / Connections** | 10.8 Integrations |
| **Справочники / Reference** | 10.10 Reference data · 10.11 Data & privacy |

Numbers 10.1–10.11 are the IA's. 10.12–10.14 are added here: language/locale, delivery policy and
printing were folded into other rows in the IA and each is opened by a different person for a
different reason, so each gets a door.

**Excluded from Settings on purpose, with where they went instead:**

| Delever puts it in settings | HorecaOS puts it | Why |
|---|---|---|
| Надбавки и скидки, промокоды | Marketing 6.1 / 6.2 | A marketer authors these weekly; an admin sets a payment method twice a year. Different people, different cadence. |
| Программа лояльности | Marketing 6.3 | Same. Delever moved it there itself in v2. |
| Зоны доставки, геозоны филиалов, регионы | Delivery 3.6 | Map authoring needs a map shell, not a form shell. Settings links to it. |
| Тарифы доставки, тип курьера, тариф курьера, бонусы/штрафы | Delivery 3.7 / 3.4 | Rate tables are versioned commercial documents, edited alongside the zones they bind to. |
| Отзывы (tags, review CRM) | Customers 5.4 / 5.5 | It is service recovery, not configuration. |
| Атрибуты, бренды, отделы, комментарии к продуктам | Catalog 4.7 | They are catalog vocabularies; the product editor is the only screen that reads them. |
| Пользователи и роли | Staff 9.1 | Access control is a staff concern, and gating settings behind the screen that grants access to settings is a loop. |
| История изменений | Staff 9.3 | One audit log for the whole console, filterable to settings. Settings screens deep-link into it. |
| Тарифы (subscription), Баланс | Finance 8.6 | Delever's own SaaS commerce shown inside the merchant's panel. It is the merchant's HorecaOS bill, which is finance. |
| Управление версиями (v1/v2 module toggles) | Nowhere | Explicitly declined. Rollout is a platform concern behind control-plane 8.1 flags. Tenant-scoped flags never live on a tenant screen, and never on a personal profile page. |
| Order status vocabulary CRUD | Nowhere | The state machine is code-owned (`ordering.orders.ck_order_status`, `OrderStateMachine`). Declined in the matrix and in ADR 0036. |

---

## 1. Patterns every settings screen shares

Specified once here; each view below references them rather than repeating them.

### 1.1 The scope bar

A single sticky row directly under the page title, before any content.

```
Бренд:  [ Rayhon ▾ ]   Филиал:  [ Все филиалы ▾ ]        Уровень редактирования: БРЕНД
```

- **Brand picker** — `tenant.brands.display_name`, filtered by ADR 0025 grants. Hidden entirely
  when the signed-in principal has exactly one brand in scope; a picker with one option is noise.
- **Location picker** — `tenant.locations.display_name`, options grouped by status; a `SUSPENDED`
  or `DRAFT` location shows its status inline. Default `Все филиалы` (= edit at brand level).
- **Level readout** — the plain-language name of the level the screen will write to. It is text,
  not a control, and it is the thing an operator reads to avoid the single worst settings mistake:
  changing something for thirty branches while believing they changed it for one.
- Disabled with an explanatory chip on screens whose keys are not settable at LOCATION
  (`ConfigurationKey.settableAt`), e.g. `notifications.quiet_hours_start_hour` is
  PLATFORM/TENANT/BRAND only.
- The scope selection lives in the URL query (`?brand=…&location=…`) so it survives a drill-down
  and back, per Togora §2a, and so a link pasted into a chat opens the same thing.

### 1.2 `InheritedField` — the control that makes the section work

Every scalar setting renders through one control with five visual states, driven by ADR 0030's
`ResolutionTrace`.

| State | Rendering | Actions offered |
|---|---|---|
| **Set at this level** | Value in normal weight. Chip `Задано здесь`. | Edit · `Вернуть наследование` (deletes the row at this level) |
| **Inherited** | Value muted. Chip `Из бренда «Rayhon»` / `Из компании` / `Значение HorecaOS`. | `Переопределить здесь` |
| **Explicitly unset here** | Chip `Снято здесь`, then the value resolution continued to, muted, beneath. | `Задать значение` · `Вернуть наследование` |
| **Not settable at this level** | Value muted, chip disabled: `Задаётся на уровне бренда`. | Link that switches the scope bar to that level |
| **Locked by plan** | Field greyed, `LockedState` inline with the module name and a `Подробнее` link to Finance 8.6. | none |

Clicking any chip opens the **resolution trace popover** — the four levels as a ladder, most
specific at the top, each row carrying the value or `не задано` / `снято явно`, who set it
(`tenant.configuration_values.set_by`), when (`.updated_at`) and why (`.reason`). The winning row
is marked. This is ADR 0030's `explain()` rendered literally, and it is the answer to every
"why is this branch behaving differently" support call the legacy product generated.

`is_explicit_null` deserves the separate state it gets: "nobody set this here" and "somebody
deliberately removed it here" are different facts, ADR 0030 stores them differently, and a UI that
collapses them re-creates the ambiguity the ADR exists to remove.

### 1.3 Settings save differently from policies

Two mechanisms, and the screen must never blur them.

**A setting** (`tenant.configuration_values`, a typed scalar) saves on blur. A toast confirms with
the level named — *«Порог опоздания: 15 мин — задано для филиала Чиланзар»* — and offers `Отменить`
for 10 seconds. A reason is optional and prompted only for keys flagged sensitive.

**A policy** (`tenant.policies`, a versioned JSON document — order acceptance, delivery tariff,
approval thresholds) is edited as a draft and activated. The read view carries a version banner:

```
Активна версия 7 · с 12.08 14:20 · А. Каримов · «переход на подтверждение вручную в час пик»
[ Изменить ]  [ История версий ]
```

Editing opens a draft; `Сохранить черновик` persists it with `status='DRAFT'`; `Активировать`
opens a **diff review** (field-by-field before/after, per Togora §2h "Record" depth) requiring a
reason, then writes `tenant.policy_current` transactionally. Above the ADR 0027 risk threshold it
creates an `audit.approval_requests` row instead and the banner becomes
`Ожидает подтверждения · запросил А. Каримов 12.08 14:20`. Requester may never approve.
A version is never edited in place and never deleted — orders pin `acceptance_policy_id` and
`acceptance_policy_version` and must still resolve years later.

### 1.4 States, uniformly

| State | Rendering |
|---|---|
| Loading | Skeleton rows in the shape of the content. Never a spinner over a populated form — a settings screen that flickers between two values teaches the user to distrust it. |
| Empty | `EmptyState` with the one action that fills it: *«Каналы продаж не настроены»* + `Добавить канал`. |
| Denied | `DeniedState`: the capability name in plain language and who to ask. Distinct component from Locked. ADR 0025. |
| Locked by plan | `LockedState`: what the module does, its billing unit, `Подключить` → Finance 8.6. ADR 0021. The IA is explicit that conflating these two is a defect. |
| Save error | `InlineAlert` beside the field, not a toast — the user must not lose which field failed. Optimistic-concurrency loss (`version` mismatch) renders *«Изменено другим пользователем»* with a diff and `Перезагрузить` / `Применить поверх`. |
| Stale | Any screen showing provider or resolution state carries a `StaleIndicator` after 60 s and refetches on focus. |

### 1.5 Sort order, uniformly

Operational lists sort by what needs a person, not by name (Togora §2e). Concretely: severity
weight ascending, then the list's natural key. Every view below states its own weight function.
Reference lists that operators pick from under time pressure (cancellation reasons, payment
methods) are the exception — they sort by an explicit `display_order` an admin controls, because
muscle memory beats freshness at 20:30 on a Friday.

### 1.6 Keyboard

| Key | Action |
|---|---|
| `/` | Find a setting (§10.0) from anywhere in Settings |
| `⌘K` | Global console command palette |
| `⌘S` | Save draft |
| `⌘⏎` | Activate policy / submit the primary action of the open dialog |
| `Esc` | Close dialog or popover, discarding an unsaved draft only after a confirm |
| `↑ ↓ ← →` | Move the cell cursor in a `MatrixGrid` (10.4) or `DataGrid` |
| `Space` | Toggle the focused matrix cell |
| `Shift`+click / `Shift`+arrows | Range-select matrix cells for bulk toggle |
| `⌥1…9` | Jump to the nth tab of a tabbed detail (10.2, 10.5, 10.8) |

Everything reachable by mouse is reachable by keyboard, including the resolution-trace popover
(the Togora prototype used `title=` tooltips for exactly this and the report calls it out as a
defect to fix, not a pattern to copy).

---

## 10.0 Settings home & "Find a setting"

**What it is for.** Answer "where is the thing I need" in one step, and "is this restaurant
actually able to trade" in one glance.

**Layout.** A readiness panel above a grouped index. Not a wall of identical tiles — a wall of
tiles is the junk drawer with rounded corners.

### Readiness panel

A short list of blocking and near-blocking conditions, computed live, each a link to the screen
and scope that fixes it. Sorted by severity weight: blocking (0) → expiring (1) → advisory (2),
then by count descending.

| Condition | Source | Severity |
|---|---|---|
| Location has no active fiscal assignment | `tenant.location_fiscal_assignments` — *not built, ADR 0038* | Blocking |
| Location active but bound to no sales channel | `tenant.sales_channel_locations` absent for the location | Blocking |
| Channel active with no enabled payment method | `tenant.channel_payment_methods` — zero enabled rows | Blocking |
| Channel active with no enabled fulfilment mode | `tenant.channel_fulfillment_modes` — zero enabled rows | Blocking |
| Location with no schedule bound for an enabled mode | `tenant.location_service_bindings` missing `(location, mode)` | Blocking |
| Priceable nodes without ИКПУ | `catalog.variants.mxik_code IS NULL`, `catalog.modifier_options.mxik_code IS NULL` (indexes `ix_variants_unclassified`, `ix_modifier_options_unclassified` exist for exactly this count) | Blocking once ADR 0038 lands; **advisory today**, because V0021 deliberately left the columns nullable while 0038 is Proposed |
| Installation credential unverified or failing | `integration.installations.last_connection_status = 'FAILED'` / `'UNVERIFIED'` | Blocking for POS/payment, advisory otherwise |
| Notification template blocked in provider moderation | *not built, ADR 0020* | Blocking for OTP, advisory otherwise |
| Location manually forced closed with no expiry | `tenant.location_service_state.mode='FORCE_CLOSED' AND effective_until IS NULL` | Advisory, and the single most valuable row here — it is the fryer-broke-on-Tuesday-still-closed-on-Saturday failure ADR 0036's comment names |
| Secret past its rotation period | *not built, ADR 0028* | Advisory |

Each row states the scope: *«Чиланзар — нет фискального назначения»*. Counts are links to the
filtered view that produced them (Togora §2j).

### Index

The six groups from §0, each screen with a one-line purpose and, where cheap, a live number:
*Каналы продаж — 6 активных, 1 без способов оплаты*. Numbers here and in the readiness panel come
from the same query, so they cannot disagree (Togora §2o).

### Find a setting

`/` opens a `Combobox` over the **key registry**: `ConfigurationKeys.all()` (ADR 0030 exposes
code, description, owner module, settable levels), plus policy keys, plus reference-list names.
Results read:

```
Порог опоздания заказа          Заказы → Правила заказа        задано: бренд «Rayhon» (15 мин)
НДС                             Налоги → Фискализация          наследуется от компании (12%)
Тихие часы                      Сообщения → Уведомления        не задано  ·  только бренд и выше
```

Enter navigates to the screen with the scope bar already set to the level the value was found at.
This one control does more for findability than any amount of navigation redesign, and it is only
possible because ADR 0030 made the key registry code-owned and enumerable.

**Actions:** none destructive. **States:** the readiness panel's empty state is the good one —
*«Всё настроено для приёма заказов»* — and should be visibly a success, not a blank.

---

## 10.1 Brand profile

**What it is for.** What the brand is called and what it looks like everywhere a customer sees it.

**Layout.** A single form, one column, three fieldsets. It is a form because it is one record with
no list behind it, and it is opened rarely.

**Scope:** BRAND. The location picker is disabled here with the chip
`Задаётся на уровне бренда`.

| Field | Type | Source |
|---|---|---|
| Название бренда | string, 200 | `tenant.brands.display_name` |
| Код | string, immutable after creation | `tenant.brands.code` (`^[A-Z0-9][A-Z0-9_-]{0,31}$`) |
| Slug | string, immutable after creation | `tenant.brands.slug` |
| Статус | `DRAFT / ACTIVE / SUSPENDED / ARCHIVED`, read-only | `tenant.brands.status` — changed only in control-plane 2.3 |
| Юридическое название компании | string | `tenant.tenants.legal_name` — TENANT scope, shown read-only with a chip |
| Логотип | image 1:1, ≤1 MB | `media.assets` with `owner_scope='BRAND'` — **the asset's *purpose* is not built**: `media.assets` has no role/purpose column, so "which asset is the logo" is currently unexpressible. ADR 0010. |
| Баннер для агрегаторов и QR-меню | image 3:1 | same gap. Delever ships this as a separate asset precisely because aggregator listings need a different crop |
| Описание | localized text | `catalog.translations` covers catalog entities only; a brand description has no home — **not built, ADR 0002** |
| Основной телефон | phone, UZ format | **not built** — `tenant.brands` carries no contact columns. ADR 0002 |
| Telegram-канал бренда | handle | **not built**, ADR 0002 |
| Валюта | read-only, `UZS` | `tenant.tenants.default_currency` |
| Часовой пояс по умолчанию | read-only IANA | `tenant.tenants.default_timezone` |

**Actions.** `Сохранить` (per-fieldset, on blur per §1.3). `Заменить логотип` opens the
`MediaUploader` with a 1:1 crop; upload is presigned per ADR 0010 and the field shows
`Загружается…` until `media.assets.status` reaches its terminal verified value — never optimistic,
because an image that failed verification and renders anyway is how a broken logo reaches an
aggregator listing.

**States.** Denied without `tenant.brand.write`. An `ARCHIVED` brand renders the whole form
read-only with a banner rather than hiding it — history must stay readable.

**Delever comparison.** Delever's *Основные настройки* is one company-wide profile with logo, an
extra aggregator asset, trade name, phone, bot handle and description — matched above. Delever
also has a *Бренды* registry (screencast-only) under catalog settings; HorecaOS's brand is a
first-class tenancy object, so no second registry exists.

**Legacy comparison.** `Company` in the legacy dashboard was `{name, description}` as `{en, ru, uz}`
plus `slug`, `image`, `background_image` — the background image is the aggregator banner above, and
staff will look for it.

---

## 10.2 Locations

**What it is for.** Everything true of one physical point: where it is, when it is open, how fast
it cooks, who it fiscalizes as, and what it sells through.

**Layout.** Master-detail. The list is the master because a chain manager's question is almost
always comparative ("which branches are shut?"), and the detail is tabbed because a branch record
has genuinely independent concerns edited by different people.

### 10.2a Location list

| Column | Type | Source |
|---|---|---|
| (severity rail) | 4px left border | computed, see states |
| Филиал | text + code | `tenant.locations.display_name`, `.code` |
| Статус | pill | `tenant.locations.status` |
| Сейчас | computed pill: `Открыт` / `Закрыт по расписанию` / `Закрыт вручную` / `Открыт вручную` | `tenant.location_service_state.mode` resolved against `tenant.service_schedules` + `_rules` + `_exceptions` in `tenant.locations.timezone` |
| Причина / до | text | `location_service_state.reason_code`, `.note`, `.effective_until` |
| Часы | compact schedule chip per mode | `tenant.location_service_bindings` → `tenant.service_schedules.name` |
| Приготовление | minutes | resolved `tenant.preparation_bands.duration_minutes` for now |
| Лимит заказов | integer or `—` | `tenant.location_service_state.max_concurrent_orders` |
| Каналы | count, links to 10.4 filtered | `tenant.sales_channel_locations` where `status='ACTIVE'` |
| Юр. лицо / ИНН | text | **not built, ADR 0038** (`tenant.location_fiscal_assignments` → `tenant.legal_entities.tin`) |
| Часовой пояс | IANA, shown only when it differs from the brand's | `tenant.locations.timezone` |

**Filters.** Status tabs with live counts — `Все (12) · Активные (9) · Черновики (1) · Приостановлены (2)`
— then a dropdown `Состояние сейчас` (открыт / закрыт по расписанию / закрыт вручную / открыт вручную),
a dropdown `Канал` (which locations sell on channel X), and a free-text search over name, code and
address. Counts are computed before filtering so the operator sees where the work is (Togora §2b).

**Sort.** Severity weight: forced-closed with no expiry (0) → forced-closed with expiry (1) →
closed by schedule during declared hours i.e. an exception day (2) → active and open (3) →
draft/suspended (4); then `display_name`. Alphabetical-only is the wrong default: it puts the shut
branch in the middle of the list.

**Row severity on three channels** (Togora §2d): background tint, 4px left border, and a caption
line under the name carrying the actual reason text from `location_service_state.note` — not a bare
badge, because the manager needs to know *why* without opening the row.

**Actions.** Per row: `Открыть`, `Закрыть сейчас…`, `Открыть принудительно…`, `Снять
переопределение`, `История изменений` (deep link to 9.3 filtered to this location).

`Закрыть сейчас…` is a modal, not a toggle. It requires `reason_code` (from the reference list in
10.10) and offers `До конца дня` / `На N минут` / `До ручного открытия`, mapping to
`location_service_state.effective_until`. The database refuses a reasonless override
(`ck_location_service_reason`) so the UI must never present a bare switch. Choosing
`До ручного открытия` shows an explicit warning naming the failure it causes.

**Bulk.** Select rows → `Закрыть выбранные…`, `Открыть выбранные`, `Назначить расписание…`,
`Привязать к каналу…`. An action appears only when valid for *every* selected row: `Снять
переопределение` is hidden the moment one selected row is already `FOLLOW_SCHEDULE`. Partial
failure is reported per row, never as one toast — 12 of 14 applied, 2 named with their reason.

**Empty state.** *«Филиалов пока нет»* with the note that locations are provisioned by HorecaOS
(control-plane 2.3) and a `Запросить филиал` action, because the IA excludes location creation from
operations deliberately: legal entity, residency and metering must be settled at creation.

### 10.2b Location detail — tabs

Nine tabs is Delever's observed ceiling (Togora §2k); this uses six.

**Tab 1 — Основное**

| Field | Source |
|---|---|
| Название, код, slug | `tenant.locations.display_name`, `.code`, `.slug` |
| Часовой пояс | `tenant.locations.timezone` |
| Статус | `tenant.locations.status`, read-only |
| Телефон | **not built, ADR 0002** |
| Адрес (структурированный) | **not built, ADR 0002.** Note the asymmetry worth fixing together: `customer.addresses` gained structure and a `coordinate_source` in V0021, while a *branch* has no address at all |
| Точка на карте (широта/долгота) | **not built, ADR 0002** — and ADR 0037 needs it: `RADIUS` distance mode is haversine *from the location point*, so this column must land with 0037 or the default distance mode cannot be computed |
| Обложка | `media.assets` `owner_scope='LOCATION'`, purpose gap as in 10.1 |
| Теги | **not built** — Delever's *Теги филиалов* page exists but is empty; low value, wave 2 |
| Порядок сортировки | **not built** — drives storefront branch ordering |
| Атрибуты заведения (посадочных мест, средний чек, парковка, детская комната) | **not built** — Delever has these; storefront-facing, wave 2 |

**Tab 2 — Часы** — the schedule tab.

HorecaOS's model is materially better than both predecessors and the screen must show why. Delever
has a fixed pair of "venue hours" and "delivery hours"; the legacy dashboard had a `work_time`
JSON blob per vendor plus a `Нерабочие дни` list. ADR 0036 replaced both with **named reusable
timetables bound per fulfilment mode**.

- A binding row per fulfilment mode: `Доставка → «Стандартное»`, `Самовывоз → «Стандартное»`,
  `В зале → «Зал»`. Source `tenant.location_service_bindings.fulfillment_mode`, `.schedule_id`.
  A mode enabled on a channel serving this location but with no binding is rendered as a blocking
  error inline, because the resolver refuses it.
- Each binding shows the schedule's weekly grid read-only with `Открыть расписание` →
  the shared schedule editor. The editor is a `ScheduleGrid`: seven rows, `DayOfWeekToggle`,
  several windows per day (`tenant.service_schedule_rules` allows many rows per day — an
  afternoon-closing venue is two rows), plus a `24/7` shortcut.
  **Render an overnight window explicitly**: `closes_at <= opens_at` means the window ends the next
  day, and the grid must draw it crossing midnight rather than as an empty range. The migration
  comment names this as the failure mode.
- `Принимает предзаказы` toggle — `tenant.service_schedules.accepts_scheduled_orders`. Labelled
  with its real meaning: *«закрыт сейчас, но заказ на завтра принимает»*.
- **Исключения** — a dated list: date, `Закрыт весь день` or replacement hours, label, reason.
  `tenant.service_schedule_exceptions`. One row per date is enforced by the database; the UI must
  offer `Заменить` rather than silently adding a second.
  Sort: upcoming first, past collapsed behind `Показать прошедшие`.
- A banner when the schedule is shared: *«Это расписание используют ещё 11 филиалов»* with a link
  to them. Editing a shared schedule from a branch page and silently changing thirty branches is
  the single worst thing this screen could do.

**Tab 3 — Загрузка и приготовление**

| Field | Source |
|---|---|
| Лимит одновременных заказов | `tenant.location_service_state.max_concurrent_orders` (null = без ограничения) |
| Текущая занятость | live count of `tenant.location_capacity_holds` where `released_at IS NULL` — shown as `7 / 20` with the Togora occupancy tint. Note this table is interim and is replaced by a count of open orders when ADR 0019's orders table is the counted set |
| Интервалы времени приготовления | `tenant.preparation_bands` — a table: mode (or `любой`), day (or `любой`), from–to, minutes, priority |

The prep-band editor is a small `DataGrid`, not a form, because a peak-hour setup is four to six
rows and Delever's equivalent (*Интервал времени приготовления заказа*) is a repeating group.
Validation surfaced inline: bands do not wrap past midnight (`ck_preparation_band_window`), so an
after-midnight rush is two rows and the UI says so rather than rejecting the save with a
constraint name. Overlaps are legal and resolved by `priority` — show the resolved value for
"right now" above the table so the author can see which row is winning.

**Tab 4 — Фискальные данные** — read-only summary plus a link to 10.7.
Legal entity, ИНН, VAT registration, effective-from date, and the fiscal terminals bound here.
All **not built, ADR 0038**. Read-only here on purpose: assigning a branch to a legal entity is an
approval-bearing act and belongs on one screen (10.7), not on thirty branch pages.

**Tab 5 — Каналы** — which channels sell from this location.
A checkbox list of `tenant.sales_channels` for the tenant, writing `tenant.sales_channel_locations`.
Unchecked means the resolver returns `CHANNEL_NOT_ENABLED`; state that in the helper text, because
absence-means-refused is the safe behaviour and it surprises people.

**Tab 6 — Уведомления** — per-location routing. See 10.9; rendered here as the same component with
the scope pinned, because the person setting up a new branch is on this page.

---

## 10.3 Order policy

**What it is for.** The rulebook every order is measured against: how it gets accepted, when it is
late, and what an operator may do when they take one by phone.

**Layout.** A single scrollable form of five titled cards with a persistent scope bar, plus a
sticky "resolved for" preview. Not tabs: these are read together during setup and an operator
comparing two branches needs to scroll one page, not six.

**Scope:** TENANT / BRAND / LOCATION. This is where the section's inheritance story is most
visible and most valuable.

### Card 1 — Приём заказа (a policy, not a setting)

`ordering.acceptance`, `tenant.policies` + `tenant.policy_current`, document shape
`OrderAcceptancePolicy`.

| Field | Type | Source |
|---|---|---|
| Режим | `AUTO_CONFIRM` / `RESTAURANT_APPROVAL` | `document.mode` |
| Кто подтверждает | `NONE` / `HORECAOS_OPERATIONS` / `POS` / `EITHER` | `document.approvalChannel` |
| Тайм-аут подтверждения | seconds, 30–1800 | `document.approvalTimeoutSeconds` |
| Что делать по тайм-ауту | `AUTO_REJECT` / `AUTO_CONFIRM` | `document.timeoutAction` |
| Требовать причину отказа | boolean | `document.rejectionReasonRequired` |
| Сообщать клиенту, пока заказ ждёт | boolean | `document.notifyCustomerWhilePending` |

The form enforces the record's own invariants *as affordances*, not as errors: choosing
`AUTO_CONFIRM` collapses the other four fields rather than letting the user fill them and then
rejecting the save. The 30 s–30 min bound is a slider with the ends labelled.

Version banner and draft→diff→activate per §1.3. `Активировать` is confirmed with the sentence
*«Новые заказы в филиале Чиланзар будут подтверждаться вручную. Уже принятые заказы не
изменятся.»* — naming the object and the blast radius, per Togora §2h.

**Delever comparison.** Delever has *Автопринятие заказа* as a toggle plus a channel list plus a
minimum-prior-successful-orders gate. HorecaOS's mode/channel/timeout/timeout-action model is richer
and versioned, but is missing two things Delever has and this screen should carry:

- **Auto-accept restricted to a set of channels** — *not built.* The document needs an
  `eligibleChannelIds` field; ADR 0002/0030 own it. Without it a tenant cannot say "auto-accept the
  bot, hand-check the aggregator", which every one of them wants.
- **Minimum prior successful orders before auto-accept applies** — *not built.* It is an
  anti-fraud gate, not a convenience, and it is cheap.

### Card 2 — Тайминги и SLA

| Field | Type | Source |
|---|---|---|
| Начало и конец рабочего дня | two local times | **not built** — needs a config key; ADR 0043 depends on it (a business day that crosses midnight is an open question the matrix names) |
| Среднее время заказа | minutes | **not built**, config key |
| Максимальное время заказа | minutes | **not built**, config key |
| Заказ опаздывает с | minutes | **not built**, config key. The single most-used value on the order board |
| Цвет индикатора опоздания | colour | **not built**, config key. Must be validated against the SLA ramp for contrast (IA Part 4) |
| Минимальная сумма заказа | money UZS | overlaps `fulfillment.service_zone_versions.min_basket_minor` — **decide once**: the zone value wins for delivery, this one applies to pickup and dine-in. Say so in the helper text |
| Расчёт дистанции | `RADIUS` / `ROAD` | `fulfillment.delivery_tariffs.distance_mode` — **per tariff, not global.** Shown here read-only with a link to 3.7, because Delever's global toggle is the worse design and HorecaOS already decided against it (ADR 0037) |
| Интервал опроса маршрутизации | minutes | **not built**, ADR 0037 routing port |

Lateness must be modelled as a computed **overlay**, never as an order status (IA Part 4,
`StatusPill extensions`). This card sets the threshold; nothing here creates a state.

**Judgement:** Delever sets all of these tenant-wide only. HorecaOS should make the late threshold and
average/maximum order time settable at LOCATION. A mall food-court branch and a highway branch do
not have the same honest promise, and forcing one number makes the whole late indicator noise.

### Card 3 — Автоматизация

Auto-dispatch, multi-provider cascade, batching radius and unpaid-order timeout are Delever
settings that HorecaOS deliberately moved into **Delivery 3.8 dispatch rules**, a single
provider-agnostic rule engine. This card therefore shows a read-only summary of the rules
currently in force for the scope, with a link. Reason: Delever duplicates near-identical config
inside five provider pages and consequently cannot express provider fallback at all.

### Card 4 — Условия

| Field | Source |
|---|---|
| Ограничение по количеству заказов на филиал | `tenant.location_service_state.max_concurrent_orders` — echoed here read-only, edited in 10.2 |
| Не принимать заказы из других зон доставки | `CATCHMENT` zone enforcement, ADR 0037 — **not built** |
| Что делать с адресом вне зоны | `REJECT` / `OFFER_PICKUP` / `MANUAL_REVIEW` | `delivery.out_of_zone_policy`, ADR 0030 key — **not built** |
| Подбор филиала для предзаказа вне рабочего времени | `по расстоянию` / `по времени открытия` | **not built**, config key |
| Показывать курьеру и кухне только оплаченные заказы | boolean | **not built**, ADR 0042 |
| Кто принимает первым | `курьер` / `филиал` | **not built.** This one is design-forcing, not configuration: it reorders the lifecycle. The matrix flags it, and ADR 0019's state machine must carry it as a parameter or refuse it |

### Card 5 — Оформление заказа оператором

What a call-centre operator may do in Orders 1.3. Delever treats operator entry as its own sales
channel; ADR 0036 agrees — `CALL_CENTRE` is a `system_type`.

| Field | Source |
|---|---|
| Доступные способы оплаты | `tenant.channel_payment_methods` for the `CALL_CENTRE` channel — edited in 10.4, echoed here |
| Доступные типы получения | `tenant.channel_fulfillment_modes` for that channel |
| Оператор может отметить «клиент просит перезвонить» | `ordering.orders.callback_requested` — ADR 0039, **not built** |
| Оператор может вводить промокод | **not built**, config key; ADR 0018 promotions |
| Показывать поле «сдача с» | `ordering.orders.cash_tendered_expected_minor` — ADR 0039, **not built** |

### Resolved-for preview

A sticky panel on the right: `Действует для: филиал Чиланзар`, then the resolved value of every
field on the page with its origin level. It is the same data as the per-field chips, collected —
and it is what a manager screenshots and sends to a franchisee.

---

## 10.4 Sales channels

**What it is for.** Which routes into the restaurant exist, and what each one is allowed to do.

**Layout.** A list over a matrix. The list is the registry; the matrix is the capability grid.
They are one screen because the only reason to look at the registry is to change the matrix.

**Scope:** TENANT. `tenant.sales_channels` is tenant-owned by design — a channel is not a brand's
property (ADR 0036 states this and enforces it by keying child tables on `(tenant_id, id)`).

### 10.4a Channel registry

| Column | Type | Source |
|---|---|---|
| Название | string | `tenant.sales_channels.display_name` |
| Код | string, immutable | `.code` — publications reference it, orders snapshot it (`ordering.orders.channel_code_snapshot`) |
| Тип | enum badge | `.system_type` ∈ `WEB, IOS, ANDROID, TELEGRAM, KIOSK, QR_TABLE, CALL_CENTRE, AGGREGATOR, POS` |
| Статус | `ACTIVE / INACTIVE / ARCHIVED` | `.status` |
| Филиалы | count → 10.2 filtered | `tenant.sales_channel_locations` |
| Способы оплаты | count | `tenant.channel_payment_methods` enabled |
| Типы получения | chips | `tenant.channel_fulfillment_modes` enabled |
| Цены берёт из | channel name or `свои` | `.price_plane_channel_id` |
| Цену определяет партнёр | boolean badge | `.externally_priced` |
| Заказы без регистрации | boolean | `.guest_orders_allowed` |
| Подключение | installation name → 10.8 | `.provider_installation_id` → `integration.installations.display_name` |

**Filters.** Type dropdown with counts; status tabs; a `Только с проблемами` toggle.

**Sort.** Severity: active with zero payment methods or zero fulfilment modes (0) → active with
zero locations (1) → active and complete (2) → inactive (3) → archived (4); then `display_name`.

**Actions.** `Добавить канал` (name, type, code, status — type is fixed at creation and can never
be changed, because behaviour keys on it). `Изменить`. `Отключить` (sets `INACTIVE`).
`Архивировать` — confirmed, and the copy says what the constraint says: channels archive, never
delete, because every order carries its channel forever. `Дублировать настройки в…` copies the
matrix rows to another channel.

**Never offer delete.** The database has no delete path for a channel that has ever sold anything,
and offering a control that always fails is worse than not offering it.

### 10.4b The capability matrix

A `MatrixGrid`: rows = channels, columns = the thing being granted. **Two matrices, not four.**

1. **Способы оплаты × канал** — `tenant.channel_payment_methods.enabled`. Columns come from
   `payments.payment_methods` once ADR 0038 lands; today the column is a bare
   `payment_method_code` string with no owning registry, which is precisely the gap 0038 closes —
   until then the column set is the code-owned provisional `CASH`, `CLICK`, `PAYME`.
2. **Типы получения × канал** — `tenant.channel_fulfillment_modes.enabled`, columns
   `DELIVERY / PICKUP / DINE_IN`.

Delever ships four matrices; the other two (menu items per channel, prices per channel) are
**deliberately not here**. Item suppression is `catalog.channel_offering_exclusions` and belongs to
the menu editor (Catalog 4.4) where the person choosing what to hide is already standing; price
per channel is `sales_channels.price_plane_channel_id` plus ADR 0018 price books, and a tick-box
grid is the wrong control for money. The IA states the principle: **`offered_on_channel` and
`price_on_channel` are separate concerns and must not be conflated.**

**Cell states.** Enabled (filled) · disabled (empty) · **unavailable** (hatched, not clickable) —
a method whose fiscal responsibility cannot be discharged at this location, e.g. `CASH` with no
active fiscal terminal (ADR 0038 makes that a serviceability precondition, not a warning). Hovering
a hatched cell explains why in one sentence.

**Bulk.** Range-select with `Shift`; `Включить выбранные` / `Выключить выбранные`; a row header
click toggles the whole channel, a column header click toggles the whole method. The row/column
bulk action is offered only when it is valid for every cell in it — a column containing a hatched
cell offers `Включить остальные` instead, with the count.

**Confirmation.** Turning off the last enabled payment method on an active channel is confirmed,
because it silently stops sales: *«У канала «Сайт» не останется способов оплаты. Оформить заказ
через него будет невозможно.»*

**Empty.** A newly created channel arrives with an empty matrix, which the model treats as "sells
nothing" rather than "sells everything". The screen must say that in the empty state, not leave the
admin to discover it in production.

### The vocabulary correction worth making loudly

Delever's registry is called *Типы заказов* in v1 and *Каналы продаж* in v2, and its type enum mixes
`Зал` (a fulfilment mode) with `Киоск` and `Бот` (channels). ADR 0036 separates them: **dine-in is a
fulfilment mode, never a channel**; a QR-table order and a waiter-entered order are both `DINE_IN`
arriving through different channels. The UI must use both words consistently and never offer
`Зал` as a channel type — this is exactly why Delever's order-type and channel filters disagree.

---

## 10.5 Channel setup

**What it is for.** Configure the actual storefront behind each channel: the bot, the site, the
kiosk, the QR menu, the apps.

**Layout.** Master-detail off 10.4 — selecting a channel opens a per-type configuration page. The
form differs entirely by `system_type`, so this is not one form with conditional fields; it is a
type-dispatched set of forms sharing a shell.

Common to every type: name, status, languages offered (subset of 10.12), theme colour, social
links, `Предпросмотр` in the matching frame (`PhoneFrame`, `TelegramMiniAppFrame`, `KioskFrame`
9:16, `AggregatorCardFrame`).

### TELEGRAM

Token paste + `Проверить` (validates and auto-fills bot display name and `@username`); menu
button label and URL; mini-app mode; per-language texts; public-offer link; a dynamic button; the
positive/negative review chat routing. Backing: `integration.installations` with
`provider_category='NOTIFICATION'`/`OTHER`, `secret_reference` for the token — **the token is never
rendered after entry** (`SecretInput`, ADR 0028), which is a straight improvement on pasting it into
a plain text field.

### WEB

Subdomain or custom domain. **Domain verification is DNS TXT, not credential handover.** Delever's
documented onboarding asks the tenant to give a Delever manager their Cloudflare and registrar
credentials; HorecaOS issues a TXT record, shows it with a copy button, polls, and shows
`Не подтверждён / Проверяется / Подтверждён` with the last check time. Also: menu ordering, colours,
header layout, behaviours, social links, about text, SEO meta title/description templates with the
recommended length counters Delever documents (≤60 / ≤160), and static pages.
**Static pages are block-based and sanitized**, never raw HTML — Delever accepts raw HTML from
tenants, which is an XSS surface pointed at the tenant's own customers.

### KIOSK

Delever's kiosk form is the most completely documented page in its whole settings tree, and it is
worth matching field for field because every field is a real Uzbek compliance or hardware fact:
kiosk name, legal company name, branch, the customer profile orders are booked under, payment
types, order types, company address, **ИНН для фискализации**, printer width (58/80 mm), VAT rate,
service PIN, device login and password, status; plus idle media (9:16, ≤1 MB), content type, fiscal
operator, fiscal URL/IP, terminal protocol, marking endpoint, kiosk marking ID, marking toggle,
table service, available reports.

HorecaOS's position: **the kiosk stays a first-class channel from day one — it costs one row — and the
device/hardware integration is declined for now** (matrix: "Self-service kiosk hardware
integration" is on the do-not-build list). So this form ships with identity, branch, booking
customer profile, payment/order types and idle media; the fiscal identity comes from the location's
legal entity rather than a typed-in ИНН (ADR 0038: a kiosk is a `fiscal_terminals` row of kind
`KIOSK` bound to a location, "the whole of kiosk fiscal identity at the cost of one row"); and the
printer/terminal/marking block renders as a `LockedState` naming what is not built.
Device credentials, if ever issued, are `SecretInput` and reveal-once.

### QR_TABLE

Three modes, exactly as Delever documents them and ADR 0047 adopts them: view-only menu ·
pull-up-and-settle an open POS bill · full self-ordering. Plus payment types, order types, callback
toggle, promo-code toggle, change-due toggle. Mode 2 and 3 require POS capabilities
(`OpenTicketReadCapability`, `TicketSettlementCapability` — ADR 0047 adds these to ADR 0011) and the
mode selector must **disable the modes the bound POS does not declare**, with the reason named,
rather than letting an admin choose a mode that silently fails at a table.

### IOS / ANDROID

Per platform and independent of each other: payment types, fulfilment types, app store links,
version gating, push credentials (`SecretInput`).

### AGGREGATOR

Read-only here; configured in 10.8 because an aggregator channel is an installation with mappings,
not a storefront. The channel page links to it.

### CALL_CENTRE

Read-only summary of card 5 of 10.3.

**States across all types.** A channel whose `provider_installation_id` points at an installation
with `last_connection_status='FAILED'` shows a banner at the top of the form, not at the bottom.
Denied, locked and empty per §1.4.

---

## 10.6 Payment methods

**What it is for.** The tenant's own list of ways a customer can pay, and who is on the hook for the
receipt.

**Layout.** List + edit drawer. Short list, frequently read, rarely written.

| Column | Type | Source |
|---|---|---|
| Порядок | drag handle | `display_order` — **not built**; add it, because operators pick from this list under pressure |
| Иконка | image 1:1 ≤1 MB | `media.assets` |
| Название | localized ru / uz-Latn / en | `payments.payment_methods.display_name` + a texts table — **not built, ADR 0038** |
| Код | immutable string | `.code` — **not built, ADR 0038** |
| Базовый тип | `CASH / CARD / ONLINE / CASHBACK / DEPOSIT / GLOBAL_PAY` | Delever's enum; HorecaOS's equivalent is `responsibility` plus `settles_from_balance` — see below |
| Кто выдаёт чек | `PARTNER / TERMINAL / MARKETPLACE / OPERATOR` | `.responsibility` — **not built, ADR 0038** |
| Эквайринг | installation → 10.8 | `.provider_installation_id` — required when `PARTNER` |
| Договор | reference | `.contract_reference` — required when `MARKETPLACE` |
| Списывается с баланса | boolean | `.settles_from_balance` — ADR 0046, for `LOYALTY_POINTS`. `CUSTOMER_DEPOSIT` is withdrawn: HorecaOS holds no customer funds |
| Активен | boolean | `.active` |
| Каналы | count → 10.4 | `tenant.channel_payment_methods` |

**Everything in this table is unbuilt.** Today `tenant.channel_payment_methods.payment_method_code`
is a bare `varchar(32)` with a format check and **no foreign key** — the migration's own comment
says so and names ADR 0038 as the owner. That means today a channel can enable a payment method that
names nothing. This screen is the fix, and it is a pilot blocker.

**Actions.** `Добавить`, `Изменить`, `Отключить`. **Never delete** — a delivered order's tender and
its fiscal document both point at the row that governed them.

**Validation, at activation and not at receipt time** (ADR 0038's explicit choice):
- `PARTNER` requires a bound `PAYMENT` installation whose `capability_snapshot` includes
  `IssueFiscalReceipt`. Rendered as a blocking inline error naming the installation.
- `MARKETPLACE` requires a contract reference.
- `TERMINAL` (cash, courier terminal, kiosk, dine-in POS) requires an active fiscal-capable
  terminal bound to every location that offers it; where none is active the method is **not
  offered on any channel serving that location**, and the matrix in 10.4 hatches those cells.

The failure this prevents is worth writing on the screen: a tenant ticks "Click fiscalizes",
nothing fiscalizes, and the gap surfaces at a tax audit rather than at configuration.

**Delever comparison.** Delever's list columns (№/icon, payment type, payment integration, status,
actions) and its six-value base-type enum are matched above. Its model is weaker in one decisive
place: nothing on a Delever payment type says who issues the receipt, so the fiscalization
question is answered by a separate free-form list under order settings — *«Типы оплат,
фискализируемые через Delever»* — which can drift out of agreement with the payment list.
HorecaOS puts the responsibility on the method itself. That single move removes a whole class of
silent fiscal gaps and should not be traded away for parity.

**Legacy comparison.** The legacy dashboard hard-coded `PaymentMethod` as a TypeScript enum —
`cash / click / payme / online / terminal / free / bank_transfer` — with a parallel translation
enum. `free` and `bank_transfer` are real and staff will look for them; they map to a
`responsibility` of `OPERATOR`/`MARKETPLACE` with zero settlement and must be expressible as rows.

---

## 10.7 Fiscalization

**What it is for.** Whom this restaurant trades as, for tax; and whether every dish it sells can
legally appear on a receipt.

**Layout.** Three tabs, because these are three different jobs done by three different people at
three different times: a bookkeeper assigns entities, an IT admin registers terminals, and a
catalog manager fills in ИКПУ codes.

**Everything on this screen is ADR 0038, Proposed, Not started.** V0021 shipped an interim slice:
`catalog.products.mxik_code`, `catalog.variants.mxik_code`, `catalog.modifier_options.mxik_code`
and the matching `package_code` columns, nullable, unvalidated except non-blank, plus two partial
indexes for the coverage question. That is deliberately the smaller thing, so operators can start
entering codes before the full model lands.

### Tab 1 — Юридические лица

| Field | Source |
|---|---|
| Код, наименование, краткое наименование | `tenant.legal_entities.code`, `.legal_name`, `.short_name` |
| ИНН | `.tin` — unique per tenant |
| Плательщик НДС | `.vat_registered` |
| Свидетельство НДС | `.vat_certificate_reference` |
| Налоговый профиль | `.tax_profile_id` → ADR 0018 `pricing.tax_profiles` |
| Юридический адрес, телефон | `.registered_address`, `.contact_phone` |
| Статус | `.status` |

Below it, the **assignment table**: location × legal entity × effective from/until, with the ADR
0027 approval reference. `tenant.location_fiscal_assignments`.

The critical interaction: **assignments are effective-dated and may not overlap.** An exclusion
constraint refuses two active assignments for one location, because overlap means two INNs are
simultaneously correct and one branch issues receipts under two taxpayers in one evening. The UI
must therefore present a re-registration as *«закрыть текущее назначение и открыть новое с даты»*,
one dialog, one transaction — never two independent rows the user is trusted to keep tidy.

A location with no active assignment cannot be activated for any channel that can produce a
receipt obligation. Surface that on the row, in 10.2's list, and in the 10.0 readiness panel.

### Tab 2 — Фискальные терминалы

`fiscal.fiscal_terminals`: kind (`POS / COURIER_TERMINAL / KIOSK / VIRTUAL`), location, legal
entity, provider binding, terminal reference, capability snapshot, status, last health check.
Sort by severity: failing health (0) → never checked (1) → healthy (2).
Actions: `Проверить связь` (writes `last_health_check_at`/`last_health_status`), `Отключить`.
Endpoints come from the platform-owned approved catalogue (`integration.provider_environments`),
never from a field the tenant types — this closes the request-forgery path at the model, and the
form must show a *chooser*, not a URL input.

### Tab 3 — Классификация товаров

Not an editor — a **coverage report and a bulk tool**. The editing happens in the product editor
(Catalog 4.2); this tab exists because the fiscal blocker is a per-brand number and somebody has to
close it before launch.

- A headline: `неклассифицировано: 143 из 1 204 позиций` from `ix_variants_unclassified` +
  `ix_modifier_options_unclassified`.
- A table of unclassified priceable nodes: type (`VARIANT` / `MODIFIER_OPTION` / `FEE`), name,
  category, locations offering it. Sorted by "offered in most locations" descending — fix the ones
  actually being sold first.
- **Bulk assign** across a filtered selection: pick a code from `catalog.mxik_reference` (a
  searchable list with `label_ru/uz/en`), pick a package code from the reference's
  `default_package_codes`, apply. ADR 0038 names bulk assignment as belonging to this decision
  precisely because the classification requirement is a wall and the tools to pass it must ship
  with the wall.
- The **delivery fee line** is a classifiable node (`priceable_type='FEE'`) and gets its own row at
  the top. It is the one people forget, and a brand publishing to a channel that can charge
  delivery with an unclassified fee is a blocker (`FISCAL_DELIVERY_FEE_UNCLASSIFIED`).
- **No AI generation of ИКПУ.** Delever ships it behind a liability disclaimer; the matrix declines
  it explicitly. A tax classifier must be entered or confirmed by a human. AI assist stays on
  descriptions and composition, in the product editor.

Also on this tab, read-only: VAT defaults per tax profile, and the list of payment methods that
fiscalize through each responsibility — a projection of 10.6, not a second editable list.

---

## 10.8 Integrations

**What it is for.** Connect the restaurant to everything outside HorecaOS, and see at a glance which
connection is broken right now.

**Layout.** A hub of category sections over a per-installation detail with tabs. The hub is
severity-sorted, not alphabetical, because the reason a manager opens this screen at 19:40 on a
Friday is that something has stopped.

**Scope.** Installations are TENANT-owned (`integration.installations.tenant_id`); bindings are
BRAND or LOCATION (`integration.bindings.brand_id`, `.location_id`, with
`ck_binding_location_implies_brand`). This is the same tenant-default → branch-override shape as
ADR 0030 configuration, and the UI must present it that way: one account, many bindings, each
binding able to override configuration (`configuration_override` jsonb).

Delever's spine is "almost every integration is installed per branch" — credentials, service IDs,
fiscal INNs and API tokens all at branch granularity. HorecaOS's model is better and must be shown as
better: **the account is held once, and the branch overrides only what genuinely differs.**
Duplicating a Payme merchant ID across thirty branch forms is how one of them ends up stale.

### 10.8a Hub

Sections by `provider_category`: `POS`, `PAYMENT`, `DELIVERY`, `NOTIFICATION`, `GEOCODING`, `OTHER`
(aggregators and analytics live in `OTHER` until the enum grows — say so rather than inventing a
category the database does not have).

| Column | Source |
|---|---|
| Провайдер / название | `integration.installations.provider_type`, `.display_name` |
| Среда | `.environment_code` → `integration.provider_environments`, with `is_production` badged |
| Статус | `.status` ∈ `DRAFT / ACTIVE / SUSPENDED / RETIRED` |
| Связь | `.last_connection_status` ∈ `SUCCEEDED / FAILED / UNVERIFIED` + `.last_connection_check_at` |
| Возможности | chips from `integration.binding_capabilities.capability_code`, primary marked |
| Привязки | count → the bindings tab |
| Версия адаптера | `.adapter_version` |
| Учётные данные | `Заданы` / `Не заданы` + rotation age — **never the value** (`.secret_reference` only; ADR 0028) |

**Sort.** `FAILED` (0) → `UNVERIFIED` (1) → `SUSPENDED` (2) → `ACTIVE`+`SUCCEEDED` (3) → `DRAFT` (4)
→ `RETIRED` (5); then provider name. Row severity on three channels per Togora §2d, with the
caption line carrying `last_connection_evidence` truncated — the operator learns *why* without
opening the row.

**Filters.** Category tabs with counts · a `Филиал` dropdown (show only installations bound here) ·
a `Только с ошибками` toggle · search over provider and display name.

### 10.8b Installation detail — tabs

1. **Учётные данные** — `SecretInput` fields, masked, reveal-once at entry and never re-rendered;
   `external_account_reference` in clear (it identifies without being sensitive);
   `non_sensitive_config` rendered as typed fields per provider. `Проверить связь` runs the ADR 0011
   discovery flow and writes `last_connection_*`. `Ротировать` follows ADR 0028's dual-secret window
   and shows which value verified.
2. **Филиалы** — the bindings table: brand or location, status, priority, effective from/until,
   overrides. Add/remove bindings; a binding without a scope is refused by the database
   (`ck_binding_scope`) so the form makes scope mandatory.
3. **Возможности** — `integration.binding_capabilities`: capability, enabled, primary, version,
   verified at. A capability the adapter does not declare cannot be enabled, and the UI must not
   let it be the sole business path (ADR 0011). One primary per scope and capability is a database
   unique index, so the control is a radio, not a checkbox.
4. **Соответствия** — the mapping tables, `integration.provider_entity_mappings`. A `MappingPane`:
   dual list, link/unlink, "unmapped only" filter, bulk auto-match by name. Entity types:
   products, payment types, discounts, couriers, cancellation reasons, branch/venue codes.
   Ambiguous mappings are **conflicts to resolve, never last-write-wins** — both directions are
   unique in the database — so a conflicting pair renders as a two-sided card the user must
   resolve, not a row that silently overwrote something.
5. **Ошибки и здоровье** — merchant-scoped: last successful inbound order per branch per provider,
   error frequency over 7 days, an operator-legible cause taxonomy, and a **scoped replay** of
   this tenant's dead letters. This is a subset of control-plane 4.2 with the platform machinery
   removed; the merchant sees their own failures and can retry them, not the queue underneath.

### Partner / aggregator API credentials

A tenant registering its own partner integration gets a **proper OAuth client with a rotatable
secret** (ADR 0026 + 0028): client id shown, secret revealed once, `Ротировать`, `Отозвать`, last
used timestamp. Delever mints the credential as `base64(login:password)` of a real panel user;
the matrix lists that among the security anti-patterns HorecaOS records as explicit non-goals, and it
must not reappear here as "how it worked before".

**States.** A `RETIRED` installation stays visible and read-only. An installation the plan does not
include renders `LockedState` in the hub with its billing unit — Delever's "module lock" concept,
correctly separated from permission (ADR 0021 vs ADR 0025).

---

## 10.9 Notifications

**What it is for.** What the customer is told, in which language, through which channel; and where
the restaurant's own staff alerts go.

**Layout.** Two tabs — *Шаблоны* (templates) and *Маршрутизация* (routing). Templates are a
list-plus-editor; routing is a form per location.

**Everything here is ADR 0020, Accepted, Not started.** Name the tables precisely so the screens can
be built against them: `notifications.templates`, `notifications.template_versions`,
`notifications.preferences`, `notifications.recipient_endpoints`, `notifications.notifications`,
`notifications.delivery_attempts`, `notifications.delivery_status_events`.

### Tab 1 — Шаблоны

**The key.** Delever keys an order-status template on `(status × source × order type × language)`.
HorecaOS keys it on **`(status × channel × fulfilment mode × source channel × locale)`** and resolves
brand override → tenant override → platform default, with locale falling back requested → brand
default → tenant default. That is ADR 0020's stated resolution order; the screen must show which
of those steps produced the template being previewed, using the same origin chip as §1.2.

| Column | Source |
|---|---|
| Событие | `notifications.templates.template_key` — order statuses from the canonical machine (`CONFIRMED`, `PREPARING`, `READY`, `FULFILLING`, `COMPLETED`, `CANCELLED`, `REJECTED`), plus OTP, payment link, review request |
| Класс | `.notification_class` ∈ `TRANSACTIONAL_REQUIRED / TRANSACTIONAL_OPTIONAL / MARKETING / SECURITY / OPERATIONS_ALERT` |
| Канал доставки | `.channel` — SMS / Telegram / push / email |
| Языки | completeness chips per locale from `notifications.template_versions.locale` |
| Версия | `.version_number` + `.status` |
| Модерация | provider approval state — **the one that blocks sending** |
| Активен | `notifications.templates.status` |

**Sort.** Severity: blocked in moderation and required (0) → missing a locale that a channel
actually serves (1) → draft (2) → active (3); then event order along the order lifecycle, not
alphabetically — an admin reading this list is walking an order through its life.

**Editor.** A `TemplateEditor` with `VariableChip` insertion and a live preview inside the matching
frame. Variables are **allowlisted typed variables from a versioned schema**
(`template_versions.variables_schema`) — ADR 0020 refuses object-graph access because a template
that can walk an object is a PII exfiltration path. The thirteen Delever documents and HorecaOS should
match: order id, restaurant name, delivery time, preparation time, product list, total, discount
amount, delivery fee, courier first name, courier surname, courier phone, customer name,
customer phone. Plus a `Показывать цены товаров` toggle, which Delever has and which changes the
rendered product list.

Left pane: language tabs (`LocalizedFieldGroup`, ru / uz-Latn / en, default marked, completeness
indicated). Right pane: the key fields — event, source channels, fulfilment modes, default
language, delivery channel, price toggle, active. This is Delever's exact layout and it is good;
keep it.

**Moderation state is a first-class column and a hard gate.** Eskiz and Playmobile pre-approve SMS
texts; ADR 0020 keeps the local record referencing the approved external template. A template
`PENDING` or `REJECTED` **blocks sending**, and the editor shows that as a banner with the provider's
reason and a `Отправить на модерацию` action — not as a badge someone might miss. Delever's docs do
not model this at all and the IA calls it out as something HorecaOS adds.

**Actions.** `Создать`, `Изменить` (creates a new `template_version`, never mutates), `Активировать`,
`Тестовая отправка` (to a staff phone, recorded as an audit fact), `Дублировать на другой язык`.
Activation of a `TRANSACTIONAL_REQUIRED` template is confirmed by naming what it changes.

**Consent is visible but not editable here.** A `MARKETING`-class template shows
*«отправляется только клиентам с согласием»* with a link to Customers 5.2. Unsubscribe changes
future eligibility and never erases delivery evidence — say so where an admin might expect a
delete button.

### Tab 2 — Маршрутизация

Where the restaurant's own alerts go. Delever gives each branch five Telegram chat IDs by event
class; the legacy HorecaOS dashboard gave each vendor two (`tg_chat_id`, `tg_delivery_chat_id`) and
staff use them daily.

| Field | Source |
|---|---|
| Новые заказы | `notifications.recipient_endpoints.operations_endpoint_reference`, per location |
| Ошибки интеграций | same |
| Отменённые заказы | same |
| Сбои автовызова курьера | same |
| Изменения стоп-листа | same |
| Смены агрегаторов открыты/закрыты | same |

Each row: channel (Telegram / SMS / email), destination, optional Telegram topic id, `Проверить`
(sends a test message), enabled. Scope LOCATION, inheriting from BRAND — a chain routes everything
to one ops chat and overrides two branches, and the origin chip makes that legible.
`operations_endpoint_reference` is configuration, not personal data (ADR 0020 says so explicitly),
which is why these can be plain fields while a customer's phone number can never be.

Also here: **тихие часы** — `notifications.quiet_hours_start_hour` (a real registered key today,
`explicitNullTerminates`, settable at PLATFORM/TENANT/BRAND only). The explicit-null semantics
matter and the UI has the state for it: *«Тихие часы сняты для этого бренда»* is not the same as
*«не задано»*.

---

## 10.10 Reference data

**What it is for.** The short controlled lists that operators pick from all day, and that reports
group by.

**Layout.** A single page of independent list cards, each expandable to a small editor. One page,
because each list is five to fifteen rows and giving each its own screen is the junk drawer.

### Причины отмены

The most-used list in the console, and the one Delever and HorecaOS model most differently.
`ordering.order_outcome_reasons` + `ordering.order_outcome_reasons_texts` — **ADR 0039,
Accepted, Not started.** Today `ordering.order_state_history.reason_code` is a bare `varchar(64)`
with no registry behind it.

| Field | Type | Source |
|---|---|---|
| Порядок | drag | `display_order` |
| Внутреннее название | text, operator-facing | `.internal_name` — *«Не дозвонились»* |
| Текст для клиента | localized ru / uz-Latn / en | `_texts.customer_text` — the softened wording |
| Системная категория | closed platform enum | `.system_category` — what cross-tenant reporting groups by |
| Что со списанием | `RELEASE / RETURN_TO_STOCK / WRITE_OFF / NO_EFFECT` | `.stock_disposition` |
| Кто несёт затраты | `TENANT / CUSTOMER / COURIER_PARTNER / PLATFORM` | `.liability_party` |
| Возврат по умолчанию | `FULL / NONE / DISCRETIONARY` | `.customer_refund` |
| Активна | boolean | `.status` |

Three things the screen must teach, each in one line of helper text:

1. The two texts are **different statements**. The operator needs *«Не дозвонились»* in the list;
   the customer gets what the tenant wrote. Publishing the internal name to a customer is exactly
   what the split prevents. Show both side by side in the row, never one behind an expander.
2. The **stock disposition is set once by an admin, not chosen by an operator at cancel time.**
   ADR 0039's alternatives table is explicit: under pressure operators pick whatever closes the
   dialog fastest and the write-off rate becomes noise. The cancel dialog in Orders 1.2 therefore
   shows the consequence read-only — *«со списанием»* — and does not offer a checkbox.
3. Reasons are **versioned and snapshotted** onto the outcome. Renaming one next year must not
   rewrite last year's cancellation funnel. So `Изменить` warns that it creates a new version and
   `Удалить` does not exist; `Отключить` does.

**Sort:** `display_order`, admin-controlled. Not by usage, not alphabetically — the operator's
hand learns positions.

### Причины завершения

Same table, `kind='COMPLETION'`. Simpler: name plus `allowed_fulfillment_modes`. Delever's four
values are the right starting set — *Доставлен*, *Заказ забран*, *Доставлено сторонней службой*,
*Самовывоз выполнен* — and the mode restriction is the thing Delever lacks: without it
*«Самовывоз выполнен»* lands on a delivery order and both the courier SLA report and the
third-party settlement quietly lose it.

### Производственный календарь

Holidays including movable Islamic dates, the weekend definition, and the **business-day boundary
that may cross midnight**. **Not built.** The matrix records the open question: Delever's forecast
window defaults to 09:00→09:00. This is a reporting foundation (ADR 0043) and a settings screen
cannot invent it; the card links to the open question rather than shipping a half-answer.

Note the relationship to `tenant.service_schedule_exceptions`: a holiday in the calendar should
*offer* to create schedule exceptions across selected schedules, and never silently create them.

### Границы SLA

Tenant-configurable time buckets for the SLA distribution reports. **Not built, ADR 0043.**
HorecaOS beats Delever here on purpose: Delever hard-codes six buckets, which cannot be changed later
without invalidating historical comparison. Changing a boundary must therefore be versioned and
the reports must state which boundary set they were computed under.

### Теги филиалов

**Not built.** Delever's page exists and is empty. Wave 2.

---

## 10.11 Data & privacy

**What it is for.** How long this restaurant keeps personal data and how it answers a customer who
asks for theirs.

**Layout.** A read-mostly page: retention schedules as a table, consent state definitions as
reference, and a small request queue. Tier 3.

| Item | Source |
|---|---|
| Сроки хранения: брошенные корзины, локация курьера, записи кандидатов | ADR 0029 — **not built** |
| Определения состояний согласия | ADR 0015 consent model — partially built (`customer.*`) |
| Запросы на выгрузку и удаление (DSAR) | ADR 0029 — **not built** |
| Аудит выгрузок PII | `audit.audit_events` where `action_code` is an export and `audit_class='SECURITY'` — the table exists (V0007) |

The one thing worth building early even in a thin form: **an export from any screen in the console
is an audited PII egress event**, and the merchant should be able to see their own egress log. The
audit table already supports it (`actor_display`, `capability_used`, `evidence_reference`).

---

## 10.12 Languages & regional formats

**What it is for.** Which languages this brand sells in, and what a date and a price look like.

**Layout.** One short form. It gets its own door because it is set once at onboarding by somebody
who will never open any other settings screen, and because half the console's other forms
(`LocalizedFieldGroup`, roughly forty of them) read their tab set from it.

| Field | Type | Source |
|---|---|---|
| Поддерживаемые языки | multi-select from `uz-Latn, uz-Cyrl, ru, en` (+ `kk`, `ka` on the roadmap) | **not built.** There is no per-brand language list. `catalog.translations.locale` exists per entity, so today the set of languages is implied by whatever anyone happened to translate |
| Язык по умолчанию | single select | `platform.default_locale` (`ConfigurationKeys.DEFAULT_LOCALE`, default `"uz"`, tenant-visible) — a **real registered key today**, resolvable at every level |
| Валюта | read-only `UZS` | `tenant.tenants.default_currency` |
| Часовой пояс | IANA | `tenant.tenants.default_timezone`; per-location `tenant.locations.timezone` |
| Формат даты | read-only `DD.MM` | platform reference data (control-plane 8.3) |
| Формат времени | read-only 24h | same |
| Формат телефона | read-only UZ | same |
| Денежный формат | read-only: whole som, thousand-grouped, no minor units | same |

**Judgement.** Currency, date, time and phone formats are **derived from country and shown
read-only**, not offered as choices. Every one of them is a source of subtle breakage when a
tenant sets it wrong, none of them varies within Uzbekistan, and control-plane 8.3 already owns the
country reference data. The one genuinely tenant-owned choice is the language set and the default.

Two consequences the screen must state, because they surprise people:

- Removing a language does **not** delete translations. It stops offering the tab and stops
  resolving to that locale. `catalog.translations` rows survive; ADR 0020's template locale fallback
  survives.
- The default language is what a notification falls back to when the customer's preference is
  unknown — that is ADR 0020's resolution order, and it is the field's real consequence, so say it
  there rather than leaving it as an abstract preference.

**Do not inherit cents-based assumptions.** UZS is a large integer with no minor unit; every money
control in the console is `MoneyInput` with thousand grouping (IA Part 4 names this explicitly).
The Togora report's convention of rendering all machine data — ids, times, phones, INNs, money —
in a monospaced face is worth codifying here and applying console-wide.

---

## 10.13 Delivery policy

**What it is for.** The delivery rules that are *policy* rather than *pricing*: who may see what,
what happens to an address nobody covers, and what a courier is allowed to do.

**Layout.** A form of three cards, scope-aware. Deliberately small, because the substantial
delivery objects live elsewhere and this screen's job is to say so clearly.

**Boundary, stated on the screen.** Zones, tariffs, courier types, courier fares and dispatch rules
are **not here** — they are Delivery 3.6, 3.7, 3.4 and 3.8. Delever mixes all of them into
*Настройки → Доставка* and the result is a page where an anti-fraud radius sits next to a rate
table. This card set links out with a one-line summary of what is currently in force
(`fulfillment.delivery_tariffs.name`, active zone count) so a person who arrived here is not lost.

### Card 1 — Адреса вне зоны

| Field | Source |
|---|---|
| Что делать с адресом вне зоны | `delivery.out_of_zone_policy` ∈ `REJECT / OFFER_PICKUP / MANUAL_REVIEW` — ADR 0030 key, ADR 0037 consumer. **Not built** |
| Не принимать заказы из чужих зон (catchment) | ADR 0037 `CATCHMENT` zone role. **Not built** |
| Резервный коэффициент дистанции при недоступности маршрутизации | `fulfillment.delivery_tariffs.road_factor_basis_points` — per tariff, shown read-only with a link |

`MANUAL_REVIEW` holds the cart for an operator to approve a manual fee with a reason, audited —
say that in the helper text, because choosing it creates work for the operations queue and the
person choosing it should know.

### Card 2 — Что видит и может курьер

All ADR 0042, Proposed, Not started. Delever's *Базовые настройки доставки* is the reference and its
field list is good; reproduce it.

| Field | Delever label |
|---|---|
| Биллинг курьеров (личный баланс, удержания) | Включить биллинг для курьеров |
| Проверять рабочий график перед принятием заказа | Проверять рабочий график курьеров |
| Показывать только готовые заказы | Показывать курьерам только готовые заказы |
| Показывать адрес клиента до принятия | Локация клиента у курьера перед принятием заказа |
| Проверять статус оплаты после доставки | Проверка статуса оплаты после доставки |
| Срок принятия назначенного заказа, мин | Срок получение заказа |
| Максимум одновременных заказов у курьера | Макс. количество заказов у курьера |

Each is an authorization gate on a courier action, not a display preference. Label them that way —
"проверять рабочий график" reads as a report setting and is in fact the mechanism that stops a
courier off shift from taking an order.

### Card 3 — Проверка действий по GPS

| Field | Source |
|---|---|
| Проверять действия курьера по радиусу | master toggle — **not built, ADR 0042** |
| Радиус принятия заказа, км | from the pickup point |
| Радиус смены статуса, м | from the customer point |

Anti-fraud geofencing. Worth matching exactly, including the unit asymmetry (kilometres for accept,
metres for the delivery confirmation) — Delever documents it that way because the two distances are
genuinely different orders of magnitude, and normalising them to one unit makes both fields harder
to set correctly.

---

## 10.14 Printing & receipts

**What it is for.** What gets printed, where, and what the customer ends up holding.

**Layout.** A short page of three cards. It is thin on purpose and the page should say why rather
than pretending to be complete.

**No ADR owns printing.** This is a genuine gap, not an oversight in this document: ADR 0011's
capability port list is `CatalogRead`, `AvailabilityRead`, `OrderApproval`, `OrderExport`,
`OrderCancellation`, `PreparationStatus` — **there is no print capability**, and the parity matrix
names print-to-POS as one of two capabilities "genuinely absent from the port list". ADR 0038 owns
the *fiscal* document but not the *paper*. A new decision is needed before this screen can be more
than the three cards below.

### Card 1 — Печать через POS

| Field | Source |
|---|---|
| Печатать заказ на POS | per binding — needs a `PrintCapability` port on ADR 0011. **Not built** |
| Что печатать: кухонный талон / чек клиента / оба | **not built** |
| Автопечать при подтверждении заказа | **not built** |

Until the capability exists, the `Печать на POS` action in Orders 1.2 must be **suppressed, not
disabled**, for bindings that do not declare it — the IA says exactly this, and the Togora review
queue pattern (§2n) is the right precedent: omit an affordance that cannot work rather than
graying it out.

### Card 2 — Фискальный чек

Read-only projection of 10.7: which legal entity issues, which responsibility discharges the
obligation for each payment method, and where the customer receives the receipt (a link, an SMS, a
paper roll at the kiosk). Sourced from `payments.payment_methods.responsibility` and
`fiscal.fiscal_documents` — **not built, ADR 0038**.

The one thing that must be true and visible: **every accepted order resolves to issued, evidenced
as not required, or visibly blocked.** ADR 0038's words. There is no path where an order is
delivered and nobody knows whether a receipt exists, and this card is where an admin confirms the
configuration that makes that so. The per-order queue is Finance 8.2.

### Card 3 — Ширина ленты и оформление

Printer width (58 / 80 mm), whether to print the logo, footer text per language. Today the only
place a printer width exists is the kiosk form, and **kiosk hardware integration is declined**
(matrix). So this card renders as `LockedState` naming the declined scope and the ADR that would
have to exist, rather than shipping fields nothing reads.

**Judgement:** do not build a receipt-template designer. A restaurant that needs one has a POS that
already has one, and HorecaOS's job at the print boundary is to hand the POS a correct order and to
hand the customer a correct fiscal document.

---

## 2. What Delever has that we should match

| Delever capability | Why match |
|---|---|
| Kiosk configuration field-for-field (ИНН, printer width, VAT, PIN, fiscal operator, terminal, marking endpoint) | Every field is a real Uzbek compliance or hardware fact. Match the *model*; the hardware integration stays declined |
| Per-channel payment-method and order-type matrices | Already built (`tenant.channel_payment_methods`, `tenant.channel_fulfillment_modes`). Match the grid UI too — a matrix is the right control |
| Payment-type registry with icon, localized name and a base type | Match, and improve by putting fiscal responsibility on the row |
| Dual-text cancellation reasons (internal + customer-facing) | Match. ADR 0039 already goes further with disposition and liability |
| Completion reasons | Match, plus the fulfilment-mode restriction Delever lacks |
| SMS/status templates keyed by status × source × order type × language, with drag-in merge variables and a show-prices toggle | Match the layout exactly — left content pane with language tabs, right parameter pane. It is well designed |
| Five per-branch Telegram alert channels by event class | Match. The legacy HorecaOS dashboard had two and staff use them daily |
| Branch prep-time intervals by time of day | Already built (`tenant.preparation_bands`), richer than Delever's |
| Per-branch order limit | Already built (`tenant.location_service_state.max_concurrent_orders`) |
| Auto-accept restricted to a channel set, gated by prior successful orders | **Not built and should be.** It is the difference between "auto-accept" being usable and being dangerous |
| Late threshold + operator-chosen indicator colour | Match, and make it location-overridable |
| Change history with a field-level before/after diff | Match — `audit.audit_events.change_document` already holds it. Lives in Staff 9.3 |
| Per-branch integration installation with credentials at branch granularity | Match the *capability*, not the shape: one account, many bindings, override only what differs |
| Provider mapping tables with unmapped filters | Match. It is the most repeated integration UI in the whole inventory |

### Beat, deliberately

| Where | What HorecaOS does instead | Why |
|---|---|---|
| Inheritance | Every value shows its level and can explain itself (ADR 0030 trace) | Delever's settings are flat; a franchisee cannot tell what their head office set |
| Explicit unset | `is_explicit_null` rendered as its own state | "Never set here" and "deliberately removed here" are different facts and Delever cannot express either |
| Fiscal identity | Legal entity as an object with effective-dated location assignment | Delever puts an ИНН in a field on a branch; a re-registration then rewrites what old receipts meant |
| Fiscal responsibility | On the payment method, validated at activation | Delever keeps a separate "types we fiscalize" list that can drift out of agreement with the payment list |
| Domain setup | DNS TXT verification | Delever's documented onboarding asks tenants to hand Cloudflare and registrar credentials to a Delever manager |
| Partner credentials | OAuth client with a rotatable secret, revealed once | Delever's client secret is `base64(login:password)` of a real panel user |
| Secrets | Never rendered after entry; rotation is a first-class action | ADR 0028 |
| Zones | One zone entity with a typed role | Delever has three overlapping geometry layers and its docs never say which wins |
| Delivery fee | One written total order of resolution, each step recording what decided it | Delever has four possible fee sources and no stated precedence |
| Dispatch config | One provider-agnostic rule engine | Delever duplicates near-identical config in five provider pages and therefore cannot express fallback |
| SLA buckets | Tenant-configurable and versioned | Delever hard-codes six, unchangeable without invalidating history |
| Template moderation | A blocking state on the template row | Delever does not model provider moderation at all |
| Static pages | Block-based, sanitized | Delever accepts raw HTML from tenants, pointed at their own customers |
| Findability | A search over the code-owned key registry | Only possible because ADR 0030 made keys enumerable. Nothing in Delever can do this |

### Skip, deliberately

| Delever capability | Reason |
|---|---|
| Тарифы (SaaS subscription) and Баланс (prepaid wallet with expiring credit) | The merchant's HorecaOS bill is Finance 8.6. Expiring prepaid credit is declined outright in the matrix |
| Управление версиями — per-module v1/v2 toggles | A symptom of running two frontends. HorecaOS builds once and rolls out with control-plane flags |
| Beta toggle on the personal account page | Tenant-scoped flags never live on a user-scoped screen. The matrix records that Delever moved this control twice and it is still in the wrong place |
| Order status CRUD | A tenant-editable state machine makes event contracts, automations and cross-tenant reporting ungovernable |
| Программа лояльности, Надбавки и скидки in Settings | Marketing authoring surfaces, not configuration |
| Бесплатная геозона as a third geometry layer | Collapses into one zone entity with a typed role |
| Global road-vs-radius distance toggle | Distance mode is per tariff (ADR 0037), because one tenant genuinely wants both |
| AI generation of ИКПУ | A wrong code is a tax classification error on a legal receipt. Declined |
| Review tags, review settings | Customers 5.5 |
| Атрибуты, отделы, бренды, комментарии к продуктам | Catalog 4.7 |

---

## 3. What the legacy dashboard did that staff will expect

The legacy console's settings were three routes — `/settings/companies`, `/settings/categories`,
`/settings/kitchens` — plus product and variant editors. Small, but every one of these behaviours
is muscle memory:

1. **A company-level config that cascades to every vendor.** `Company.detail.page.tsx` has a
   `Конфиг` tab headed *«График работы (для всех поставщиков компании)»* and *«Конфиг доставки (для
   всех поставщиков компании)»*, and `Vendor.detail.page.tsx` has the identical tab for one vendor.
   That is inheritance, hand-rolled, with no way to see which level a value came from. Staff already
   think in these two levels; 10.2's cascade action and §1.2's origin chip are the same idea done
   properly, and the phrase *«для всех филиалов бренда»* should be reused verbatim.
2. **Per-day working hours as a 7-row grid of two time inputs**, plus a **Нерабочие дни** list of
   `{date, start, end}`. Preserved by `tenant.service_schedules` + `_rules` + `_exceptions`, and the
   editor should keep the same shape.
3. **The delivery config field names**, which staff will say out loud: базовая дистанция (км),
   максимальная дистанция (км), цена доставки за базовую дистанцию, минимальная сумма заказа,
   значение скидки, тип скидки (сумма / дистанция), минимальная сумма заказа для скидки,
   `prices_per_km` rows, `peak_hours` rows of `{start, end, distance, distance_price}`.
   Every one maps onto ADR 0037: bands, `max_distance_meters`, `min_fee_minor`, zone
   `min_basket_minor`, `free_delivery_from_minor`, and time rules with
   `multiplier_basis_points` / `surcharge_minor`. **Provide a migration note in the UI** — a
   one-time import that shows the old blob beside the new tariff — or these tenants will believe
   the feature was removed.
4. **Two Telegram chat IDs per vendor**, `tg_chat_id` and `tg_delivery_chat_id`. 10.9's routing tab
   must include both meanings (general orders, delivery) among its event classes.
5. **`tin` on the vendor.** The legacy model already put the INN at branch level. ADR 0038 keeps
   that truth and improves it; do not present it as a new concept.
6. **`pre_order` and `visibility_distance` on the vendor.** The first maps to
   `service_schedules.accepts_scheduled_orders`; the second has no home — it is a storefront
   visibility radius and belongs with zones (3.6). Name it in the migration mapping or it is lost.
7. **Cards-vs-table view toggle** (`ViewModeToggle`, «Карточки» / «Таблица») and a **metric strip**
   above every settings list (`SettingsScaffold` renders up to four toned metrics). Both are cheap,
   both were used everywhere, and the metric strip is the same discipline the Togora report
   recommends: tiles derived from the table's own data so they cannot disagree with it.
8. **Search on every settings list**, always in the same place (top-left of the scaffold header).
   Keep the position.

---

## 4. Data the backend does not have yet

Named precisely, with the owning decision. Everything not listed here is built and cited above.

### Blocks the pilot

| Missing | Owner |
|---|---|
| `payments.payment_methods` (code, display name, `responsibility`, `provider_installation_id`, `contract_reference`, `settles_from_balance`, active) — and the foreign key from `tenant.channel_payment_methods.payment_method_code` onto it | ADR 0038, Proposed |
| `tenant.legal_entities` (tin, vat_registered, tax_profile_id, registered_address) | ADR 0038 |
| `tenant.location_fiscal_assignments` with the non-overlap exclusion constraint | ADR 0038 |
| `fiscal.fiscal_terminals`, `fiscal.fiscal_documents` | ADR 0038 |
| `catalog.fiscal_classifications` and `catalog.mxik_reference`; the three `CatalogValidator` blockers | ADR 0038 |
| Location contact and geography: phone, structured address, latitude/longitude on `tenant.locations`. **`RADIUS` distance mode cannot be computed without the point**, so this lands with ADR 0037 at the latest | ADR 0002 + ADR 0037 |
| The whole `notifications` schema — templates, template versions, endpoints, notifications, delivery attempts, status events | ADR 0020, Accepted, Not started |
| Provider template moderation state and the send block it implies | ADR 0020 |
| `ordering.order_outcome_reasons` + `_texts`, `ordering.order_outcomes` | ADR 0039, Accepted, Not started |
| `fulfillment.service_zones` / `_versions` / `zone_location_bindings` / `regions` / `delivery_tariffs` / `_bands` / `_time_rules` / `delivery_fee_resolutions` | ADR 0037, Accepted, Not started |
| `delivery.out_of_zone_policy` as a registered ADR 0030 key | ADR 0037 + ADR 0030 |
| Order-policy config keys with no declaration today: business-day start/end, average order time, maximum order time, late threshold, late indicator colour, minimum order sum, routing poll interval, pre-order branch resolution rule, operator promo-code permission | ADR 0030 registry (`ConfigurationKeys`), content owned by ADR 0002 / 0019 / 0037 |
| Auto-accept eligible-channel set and minimum-prior-successful-orders gate on the acceptance policy document | ADR 0002 + ADR 0030 |
| ADR 0030 control-plane read and write APIs, and the resolution trace endpoint the origin chip calls | ADR 0030 — the checklist item is open |
| ADR 0030 caching with outbox-driven invalidation (a settings screen that shows a stale value after save is unusable) | ADR 0030 + ADR 0033 |

### Blocks a good version of the section, not the pilot

| Missing | Owner |
|---|---|
| A purpose/role column on `media.assets`, so "the logo" and "the aggregator banner" are distinguishable | ADR 0010 |
| Brand contact fields and a brand description with translations | ADR 0002 |
| Per-brand supported-language set (only `platform.default_locale` exists) | ADR 0030 key, content ADR 0002 |
| Location sort order, tags, and venue attributes | ADR 0002 |
| Courier policy settings: billing mode, shift enforcement, ready-only visibility, address-before-accept, post-delivery payment check, acceptance SLA, max concurrent orders, GPS radii | ADR 0042, Proposed |
| Business calendar, business-day boundary that may cross midnight, weekend definition | ADR 0043 + an open product question the matrix records |
| Tenant-configurable SLA bucket boundaries, versioned | ADR 0043 |
| `ordering.orders.callback_requested`, `cash_tendered_expected_minor` | ADR 0039 |
| Courier-first vs branch-first acceptance ordering — **design-forcing, not configuration**: it reorders the lifecycle and ADR 0019's state machine must carry it as a parameter or refuse it | ADR 0019 |
| Retention schedules, consent state definitions, DSAR request handling | ADR 0029 |
| **A print capability port on ADR 0011, and a decision that owns printing and receipt presentation at all.** Nothing owns it today | new ADR |

### Deliberately absent, do not add

Tenant-editable order statuses · a second brand registry under catalog · a third geometry layer ·
a global road-vs-radius toggle · per-module v1/v2 flags · an expiring prepaid wallet · AI-generated
ИКПУ · a receipt-template designer · kiosk hardware provisioning.

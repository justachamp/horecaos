# Operations spec — Brands and locations

**Section:** the restaurant's own structure — its brands, its branches, and how each branch
is configured for trade.
**IA references:** operations 10.1 (Brand profile), 10.2 (Locations), the location half of
10.4 (Sales channels), 10.7 (Fiscalization), and 3.6/3.7 (Delivery zones and tariffs) as they
are seen from a branch.
**Primary ADRs:** 0002 (domain model), 0036 (sales channels and serviceability — **built**,
migration V0020), 0030 (configuration resolution), 0037 (delivery zones and tariffs — accepted,
**not started**), 0038 (legal entities and fiscal identity — proposed, **not started**),
0016 (catalog publication and location offerings — built), 0010/0015 (media), 0027 (audit and
approvals), 0025 (capabilities).

**Conventions used throughout.** 24-hour clock, no AM/PM. Dates `DD.MM` inside the current
year, `DD.MM.YYYY` otherwise. Money in whole so'm with a narrow no-break space as the
thousands separator (`45 000 so'm`); minor units never rendered. All wall-clock times are the
**location's** timezone (`tenant.locations.timezone`), never the operator's browser, and every
time that is not the operator's own zone carries the branch's city abbreviation beside it.
Machine data — codes, INN, ids, phone numbers, coordinates — in the mono face. Absent value
is `—`, never blank. Uzbek and Russian throughout; the interface language is the operator's,
the branch's own localized name is shown in the interface language with the other locales
one hover away.

---

## 0. Why this section exists and where it sits

Almost everything here is configuration, and configuration screens are normally three clicks
away for a reason. **One thing in this section is not configuration: the manual open/closed
switch.** It is hit mid-service, with a queue building, by a manager who is standing up. It
must be reachable from the shell in one action from any screen, and it must be reachable from
the branch row in the location list without opening the branch.

So the section splits in two:

| Path | What lives there | Reached from |
|---|---|---|
| **Main path — during service** | The open/closed switch with its reason and expiry; the closed-branch watch strip; per-branch capacity and current load; the "why can't I sell here" explainer | Shell header control, live board, location list rows, KDS |
| **Three clicks away — between services** | Brand record, location profile, schedule library, exceptions calendar, preparation bands, channel matrix, zones, tariffs, legal entities | Настройки → Филиалы |

**Navigation tree.**

```
Настройки
  └─ Бренды и филиалы
       ├─ Бренды                     (list; hidden and redirected when the tenant has one brand)
       │    └─ Бренд                 (record, tabs)
       ├─ Филиалы                    (the registry — the default landing page of this section)
       │    └─ Филиал                (record, tabs: Профиль · Часы · Приготовление · Каналы ·
       │                              Доставка · Фискальные данные · История)
       ├─ Графики работы             (brand-scoped named timetables; library + editor)
       ├─ Исключения                 (all dated exceptions across all schedules, calendar view)
       ├─ Каналы × Филиалы           (matrix)
       ├─ Зоны доставки              (map; ADR 0037, not built)
       ├─ Тарифы доставки            (list + editor + simulator; ADR 0037, not built)
       └─ Юридические лица           (registry; ADR 0038, not built)
```

The brand list is **suppressed when the tenant has exactly one brand** and the section lands
directly on Филиалы. Most tenants have one brand; making them pass through a one-row list to
reach their branches every time is the kind of tax that makes staff stop using the console.
The brand record stays reachable from a link in the location list header (`Бренд: Nasiba →`).

---

## 1. View — Филиалы (location list)

### 1.1 What it is for

*"Which of my branches is trading right now, and which one needs me?"*

### 1.2 Layout

A **list with a severity-sorted queue at the top**, not an alphabetical registry. This is the
one settings screen a manager opens during service, so it is shaped like an operational queue:
status tabs with live counts, a table whose rows carry severity on three channels (tint, left
border, caption line — Togora pattern 2d), and row actions that do the two things worth doing
without opening the record (close/reopen, and change capacity).

A tenant with three branches sees three rows and does not need a queue; a tenant with forty
does. The same layout serves both because at three rows the severity sort is invisible and
harmless.

### 1.3 Columns

| Column | Type | Source |
|---|---|---|
| *(selection)* | checkbox | — |
| Филиал | text, bold, with brand name beneath in muted small when the tenant has >1 brand | `tenant.locations.display_name`; brand from `tenant.brands.display_name` |
| Код | mono text | `tenant.locations.code` |
| Торговое состояние | badge + caption | derived — `ServiceabilityResolver` over (location, default channel, each bound mode). Badge shows the *worst* mode; caption names the reason via `ServiceabilityReason` |
| Причина / до | text + relative time | `tenant.location_service_state.reason_code`, `.note`, `.effective_until` (rendered `до 21:30` or `до отмены`) |
| Загрузка | `n / m` with a bar | numerator: `count(*) from tenant.location_capacity_holds where location_id = ? and released_at is null`; denominator: `tenant.location_service_state.max_concurrent_orders` (null → `n / ∞`) |
| Часы сегодня | text, e.g. `10:00–23:00` or `10:00–15:00, 18:00–02:00` | resolved from `tenant.location_service_bindings` → `tenant.service_schedules` → `service_schedule_rules` for today's `day_of_week`, overridden by `service_schedule_exceptions` for today's date |
| Режимы | three small pills (Дост · Самов · Зал), lit when served | presence of a row in `tenant.location_service_bindings` per `fulfillment_mode` |
| Каналы | count, clickable | `count(*) from tenant.sales_channel_locations where location_id = ? and status = 'ACTIVE'` |
| Готовка | minutes | `tenant.preparation_bands.duration_minutes` for the band matching now (highest `priority`, then narrowest); `—` when no band covers now |
| Меню | count + warning dot | available offerings: `count(*) from catalog.location_offerings where location_id = ? and status = 'AVAILABLE'`; the dot lights when the brand has no live `catalog.publications` row for a channel this branch trades on (`NO_LIVE_MENU`) |
| Часовой пояс | text, shown only when the tenant spans more than one | `tenant.locations.timezone` |
| ИНН | mono | **not built — ADR 0038** (`tenant.location_fiscal_assignments` → `tenant.legal_entities.tin`) |
| Зона | count | **not built — ADR 0037** (`fulfillment.zone_location_bindings`) |
| Статус записи | badge, only visible when the Все tab is active | `tenant.locations.status` — DRAFT / ACTIVE / SUSPENDED / ARCHIVED |
| Изменён | `DD.MM HH:mm` | `tenant.locations.updated_at` |

The **Каналы** and **Меню** counts are links, not text (Togora pattern 2j): clicking Каналы
opens the branch's Каналы tab, clicking Меню opens the catalog section filtered to this
location's offerings. A number in a table always raises "which ones?"; answer it in one click.

Default visible columns: Филиал, Код, Торговое состояние, Причина/до, Загрузка, Часы сегодня,
Режимы, Каналы, Готовка. The rest are behind a column chooser persisted per user. ИНН and Зона
are hidden until 0038 and 0037 land rather than shown as a column of dashes.

### 1.4 Filters

- **Status tabs with live counts, computed before filtering** so the counts do not collapse as
  the selection narrows:
  `Требуют внимания (n)` · `Открыты (n)` · `Закрыты вручную (n)` · `Вне часов (n)` ·
  `На пределе (n)` · `Все (n)`.
  *Требуют внимания* is the union of: manually closed with `effective_until IS NULL`; at
  capacity; closed today by an exception nobody has looked at; trading on zero active channels;
  `NO_LIVE_MENU`; and — once 0038 lands — active with no fiscal assignment.
- **Бренд** — dropdown, rendered only when the tenant has more than one brand. Default: all.
- **Канал** — dropdown of `tenant.sales_channels` (ACTIVE and INACTIVE, archived excluded),
  filtering to branches bound to that channel. Default: all. Changing it re-evaluates the
  Торговое состояние column *for that channel*, because "closed" is channel-specific.
- **Режим** — segmented control Доставка / Самовывоз / В зале / Все. Same re-evaluation.
- **Поиск** — one field over `display_name`, `code`, `slug`, and (once built) address and phone.
  Debounced 300 ms, `/` focuses it.
- **Статус записи** — dropdown, default "Кроме архивных". Archived locations are never in the
  default set; they are reachable and never deleted.

No date range. This is a registry, not a queue over time; a date filter here answers no
question anyone has.

Filters live in query params so drilling into a branch and coming back preserves them.

### 1.5 Sort order

Default is **severity, then name** — not alphabetical, not by creation date:

1. Manually closed with no expiry (`mode = 'FORCE_CLOSED' AND effective_until IS NULL`) — the
   fryer-died-on-Thursday-and-nobody-reopened case, which is the single most expensive silent
   failure in this section.
2. At capacity (open holds ≥ `max_concurrent_orders`).
3. Manually closed with an expiry.
4. Closed today by exception.
5. Outside service hours.
6. Force-open (an override that is also worth seeing).
7. Trading normally.

Within each band, `display_name` collated for `ru`/`uz`. Column headers are sortable and an
explicit sort replaces the severity sort with a visible "Сортировка: Название ↑ · сбросить"
chip, so nobody wonders why the order changed.

### 1.6 Actions

**Per row (inline, no menu — these are the two worth one click):**

| Action | What it does | Needs | Unavailable when | Confirm |
|---|---|---|---|---|
| Закрыть / Открыть | Opens the quick service-state dialog (§6) | `location.service-state.change` at this LOCATION | Location status is DRAFT or ARCHIVED | No — the dialog *is* the confirmation, and it is reason-mandatory |
| Вместимость | Inline number stepper writing `max_concurrent_orders` | `serviceability.manage` | — | No; optimistic with undo toast for 8 s |

**Row menu (⋯):** Открыть филиал · Часы · Каналы · Почему нельзя продавать? (§12) ·
Дублировать филиал · История изменений · Архивировать.

**Header actions:** `+ Филиал` · `Экспорт CSV` · column chooser.

**Bulk actions** appear in a bar when ≥1 row is selected, showing `Выбрано: n`. Each is offered
only when it is valid for **every** selected row; an action invalid for some rows is disabled
with a tooltip naming the first offending row and the count (`Недоступно: у 3 филиалов другой
бренд — «Chorsu», …`).

| Bulk action | Valid when | Notes |
|---|---|---|
| Закрыть выбранные | every row is ACTIVE and the actor holds the capability at each | One reason and one expiry for the batch; a per-row result list afterwards, not a single toast |
| Открыть выбранные | every row is currently overridden | Requires a reason on the reopen too, because a reopen is also an override of what the schedule said |
| Привязать график | **all selected rows share one brand** — `service_schedules.brand_id` is NOT NULL and the FK matches brand on both sides, so a cross-brand bind fails at the database. Do not offer what the database will refuse | Choose mode + schedule |
| Задать вместимость | always (any ACTIVE row) | One value for the batch |
| Включить/выключить канал | every selected row is bound to that channel, or none is | Writes `sales_channel_locations.status`; a whole-matrix PUT per ADR 0036, not per-cell |
| Заменить полосы приготовления | all rows share one brand | Replaces the whole band set — say so in the confirm, this is destructive |
| Архивировать | every row is not ARCHIVED and has no open capacity holds | **Confirm**, naming each branch |

Partial failure is reported as a per-row result table (`✓ Chilonzor · ✗ Yunusobod — 409,
изменён другим пользователем`), never as one aggregate toast. The bulk-close endpoint must
produce N audit records, not one (IA 9.3).

### 1.7 States

- **Loading** — skeleton rows keeping the header and the tab bar, counts as shimmering pills.
  Never a full-page spinner: the tabs are the context.
- **Empty (no branches at all)** — a first-run card inside the table frame, not a bare page:
  *"У этого бренда пока нет филиалов. Филиал — это точка, в которой готовят и выдают заказы."*
  with `Создать филиал`. Empty-as-a-table-row keeps the frame (Togora 2p).
- **Empty (filter matched nothing)** — `<td colspan>` row: *"Нет филиалов по фильтру"* +
  `Сбросить фильтры`.
- **Denied** — the section is not rendered at all in the nav without `location.read`; deep-linking
  gives a 403 card naming the missing capability and the person to ask, never a blank page.
  A user with `location.read` but not `serviceability.manage` sees every column and every row
  action is *omitted*, not disabled (Togora 2n) — except the close/open control, which is
  omitted only if they also lack `location.service-state.change`.
- **Error** — an inline error band above the table with the request id in mono and `Повторить`.
  The last successfully loaded rows stay on screen greyed rather than disappearing.
- **Stale** — the trading state and load counters refresh every 15 s. If the stream drops,
  a muted bar reads *"Данные от 14:32 · обновление приостановлено"* and the load bars grey out.
  Never show a live-looking number that is not live.

**Business states rendered on the row** (severity precedence is strict; only the top one gets
the caption):

| State | Tint | Left border | Caption |
|---|---|---|---|
| Закрыт вручную, без срока | rose | 4px rose | `Закрыт: сломалась печь · с 19:14 · до отмены` |
| На пределе | rose | 4px rose | `Очередь заполнена: 12 / 12` |
| Закрыт вручную, до времени | amber | 4px amber | `Закрыт: нет персонала · до 21:30` |
| Закрыт по исключению | amber | 4px amber | `Навруз — не работает` (`service_schedule_exceptions.label`) |
| Вне часов | none | 4px transparent | `Откроется в 10:00` (from `Serviceability.nextAvailableAt`) |
| Принудительно открыт | sky | 4px sky | `Открыт вручную: продлённые часы · до 02:00` |
| Нет живого меню | amber | 4px amber | `Нет опубликованного меню для канала «Telegram»` |
| Черновик | grey | 4px grey | `Черновик — не торгует` |

The transparent left border on normal rows keeps them aligned with flagged ones.

### 1.8 Keyboard

`/` search · `j`/`k` move · `Enter` open branch · `Space` select · `Shift+click` range ·
`x` toggle selection · `c` quick-close dialog for the focused row · `o` reopen ·
`1`…`6` switch tab · `Esc` clear selection then close · `g l` go to locations · `?` shortcut help.
Rows are real focusable elements with `role="row"` and Enter handling — the legacy prototype's
`<tr onClick>` with no keyboard path is a gap to fill, not a pattern to copy.

---

## 2. View — Филиал → Профиль (location record, identity tab)

### 2.1 What it is for

*"Is this branch's identity, contact and address right — the things a customer and a courier
see?"*

### 2.2 Layout

**Master-detail with a tab group extending the header** (Togora 2k). The header block carries
the identity line — name, code, brand, trading badge, and the open/close control — and stays
visible across every tab, because the manager who came here to fix hours still needs to see
that the branch is shut. Tabs: `Профиль · Часы · Приготовление · Каналы · Доставка ·
Фискальные данные · История`. Seven; nine is the observed ceiling.

The tab body is a **two-column sectioned form**, uppercase section labels, save bar pinned to
the bottom that appears only when the form is dirty and states what will change.

### 2.3 Fields

**Секция «Идентификация»**

| Field | Type | Source |
|---|---|---|
| Название | text, required, ≤200 | `tenant.locations.display_name` |
| Название (ru / uz / en) | three text fields behind a locale switcher | **not built** — `catalog.translations` exists but its `ck_translation_entity_type` admits only CATALOG/CATEGORY/PRODUCT/VARIANT/MODIFIER_GROUP/MODIFIER_OPTION. Needs a `tenant.translations` or an entity-type extension. No owning ADR; nearest is 0002 |
| Код | mono text, required, `^[A-Z0-9][A-Z0-9_-]{0,31}$`, unique per (tenant, brand) | `tenant.locations.code` |
| Slug | mono text, required, lowercase, unique per (tenant, brand) | `tenant.locations.slug` |
| Бренд | read-only after creation | `tenant.locations.brand_id` |
| Часовой пояс | IANA timezone picker, required | `tenant.locations.timezone` |
| Статус записи | segmented DRAFT / ACTIVE / SUSPENDED / ARCHIVED | `tenant.locations.status` |
| Описание (ru / uz / en) | textarea | **not built** — same gap as localized name |

**Секция «Контакты и адрес»** — *the entire section is unbuilt.*

| Field | Type | Source |
|---|---|---|
| Телефон | phone, `+998 XX XXX-XX-XX` mask, required | **not built** — no column on `tenant.locations`, no owning ADR |
| Адрес | text | **not built** |
| Ориентир | text | **not built** — the market needs this as a first-class field (ADR 0015 makes the same point for customer addresses) |
| Точка на карте | map pin, lat/lon | **not built** — and this one blocks ADR 0037: `RADIUS` distance is "haversine from the location point" and there is no location point column. ADR 0037's implementation must add it or a schema-extension ADR must own it |
| Регион | dropdown | **not built — ADR 0037** (`fulfillment.regions`) |

**Секция «Медиа»**

| Field | Type | Source |
|---|---|---|
| Обложка | single image, 16:9, ≤2 MB | `media.assets` with `owner_scope = 'LOCATION'`, `owner_id = location id`. **The role binding is not built**: `catalog.media_relations` covers catalog entities only, so nothing records *which* asset is the cover. Needs a `tenant.media_relations` or an entity-type extension — ADR 0010 |
| Фон | single image, 16:9 | same gap |

**Секция «Торговля»**

| Field | Type | Source |
|---|---|---|
| Порядок в списке | integer | **not built** — storefront merchandising, IA 6.8, no ADR |
| Теги | multi-select chips | **not built** — IA 10.10 reference data, no ADR |
| Предзаказ | derived, read-only here with a link to Часы | `tenant.service_schedules.accepts_scheduled_orders` of the bound schedule |
| Одновременных заказов | integer ≥1 or «без ограничения» | `tenant.location_service_state.max_concurrent_orders` |
| Меню | link to the branch's offerings | `catalog.location_offerings` |
| Атрибуты зала (посадка, средний чек, парковка, детская, 3D-тур) | — | **not built, and deliberately deferred** — see §14 |

### 2.4 Actions

| Action | What it does | Needs | Unavailable when | Confirm |
|---|---|---|---|---|
| Сохранить | Optimistic-concurrency PUT with the loaded `version`; a 409 re-reads and shows a field-level diff rather than overwriting | `location.manage` | form clean | No |
| Отменить изменения | Reverts to loaded values | — | form clean | Yes, if dirty for >30 s |
| Активировать | DRAFT/SUSPENDED → ACTIVE (`Location.activate()`) | `location.manage` | already ACTIVE; **or** any precondition fails — no schedule bound to any mode, no active channel, and once 0038 lands, no fiscal assignment | Yes — lists the preconditions checked |
| Приостановить | ACTIVE → SUSPENDED | `location.manage` | not ACTIVE | Yes — *"Филиал перестанет принимать заказы во всех каналах"* |
| Архивировать | → ARCHIVED | `location.manage` | open capacity holds exist | Yes, typed confirmation of the branch code |
| Дублировать | Creates a DRAFT copy of profile, schedule bindings, prep bands and channel bindings; never copies fiscal assignment or zones | `location.manage` | — | No — the copy is a draft |
| Применить ко всем филиалам бренда | The cascade: applies the *currently edited section* to every ACTIVE location of the brand | `location.manage` at BRAND | more than one section is dirty | **Yes** — a preview listing each target branch and the fields it will overwrite, with per-branch opt-out checkboxes |

The cascade deserves its own note. The legacy dashboard had it (`companies/{id}/vendors/work-time`
patched every vendor of the company at once) and staff will look for it. Delever has it too
(«Загрузить геозоны» in bulk). Keep it, but **never as a fire-and-forget PATCH over a JSON
blob**: show the target list, show the diff per branch, let the operator drop branches from the
batch, and produce one audit record per branch.

### 2.5 States

Loading: field skeletons, header identity resolved first so the tab group is usable.
Empty: not applicable — a record always has content.
Denied: read-only rendering of every field, save bar absent, a muted banner naming the capability.
Error on save: field-level errors mapped from the API problem detail (ADR 0031), plus a
non-field band for constraint violations (`Код уже занят в этом бренде`).
Business states: a DRAFT branch shows a persistent amber banner listing what still blocks
activation, each item a link to the tab that fixes it. This is the single most useful thing on
the screen during onboarding and it should be built first.

---

## 3. View — Филиал → Часы

### 3.1 What it is for

*"When is this branch open, per fulfilment mode, and what happens on Navruz?"*

### 3.2 Layout

**Three mode cards over a shared week grid**, not a form of paired time fields.

Delever puts "рабочее время филиала" and "часы доставки" as two fixed schedules on the branch.
ADR 0036 rejected that shape and it is right to: a fixed pair cannot express pickup closing
before dine-in, and thirty branches on one Ramadan timetable should edit one object. So the
model here is: **named, reusable timetables live in a brand-level library; the branch binds one
per fulfilment mode.**

The card layout makes this legible without teaching anyone the word "binding":

```
┌ ДОСТАВКА ────────────────────┐  ┌ САМОВЫВОЗ ──────────────────┐  ┌ В ЗАЛЕ ─────────────────┐
│ График: «Будни 10–23»   [↗]  │  │ График: «Будни 10–23»  [↗]  │  │ Не обслуживается       │
│ Сегодня: 10:00–23:00         │  │ Сегодня: 10:00–23:00        │  │ [Начать обслуживать]   │
│ Предзаказ: да                │  │ Предзаказ: да               │  │                        │
│ [Сменить график]             │  │ [Сменить график]            │  │                        │
└──────────────────────────────┘  └─────────────────────────────┘  └────────────────────────┘
```

Beneath the cards, a **read-only week grid** overlays the bound schedules of all three modes on
one seven-column canvas, each mode a differently-hatched band, so "pickup shuts two hours before
the hall" is visible rather than inferred. Today's column is outlined and a hairline marks the
current time.

### 3.3 Fields

| Field | Type | Source |
|---|---|---|
| Режим | DELIVERY / PICKUP / DINE_IN | `tenant.location_service_bindings.fulfillment_mode` |
| График | schedule picker, brand-scoped | `tenant.location_service_bindings.schedule_id` → `tenant.service_schedules` |
| Название графика | text | `tenant.service_schedules.name` |
| Принимает предзаказы | boolean, read-only here (it belongs to the schedule) | `tenant.service_schedules.accepts_scheduled_orders` |
| Окна недели | list of (day, opens, closes) | `tenant.service_schedule_rules.day_of_week` (ISO 1=Mon…7=Sun), `.opens_at`, `.closes_at` |
| Исключения | dated overrides | `tenant.service_schedule_exceptions` |
| Время до предзаказа (доставка / самовывоз) | minutes | **not built — ADR 0019**; Delever has separate pre-order lead times per mode |

**Two rendering rules that are correctness, not polish.**

1. `closes_at <= opens_at` means the window ends **the following day**. A branch open
   `18:00–02:00` must render as one continuous band crossing midnight into the next column,
   not as an empty range. The migration comment says exactly this; the UI has to honour it or
   the branch reads as shut all evening.
2. Several rows may cover one day. A venue closing for the afternoon has two rules for that
   day, and the grid must draw two bands, never collapse them to first-open/last-close.

### 3.4 Filters

None on this tab. It shows one branch. The week grid has a mode legend whose entries toggle
band visibility — a view control, not a filter.

### 3.5 Actions

| Action | What it does | Needs | Unavailable when | Confirm |
|---|---|---|---|---|
| Сменить график | Writes `PUT /service-bindings` with `{fulfillmentMode, scheduleId}` | `serviceability.manage` | only schedules of **this branch's brand** are offered — the composite FK refuses the rest at the database, so never list them | No |
| Начать обслуживать «В зале» | Creates the binding for that mode | `serviceability.manage` | no schedule exists for the brand yet — then offer `Создать график` instead | No |
| Перестать обслуживать | Deletes the binding | `serviceability.manage` | orders are in flight for that mode | **Yes** — *"Филиал перестанет принимать заказы «В зале» во всех каналах"* |
| Открыть график | Navigates to the schedule editor (§4), with a banner naming how many other branches use it | `serviceability.read` | — | No |
| Добавить исключение | Opens the exception dialog (§5) scoped to the bound schedule | `serviceability.manage` | — | No |
| 24/7 | Shortcut writing seven 00:00–00:00 rules onto a new schedule named `Круглосуточно` | `serviceability.manage` | — | No |

### 3.6 States

- A mode with **no binding** renders as "Не обслуживается", which is the truth: `location_service_bindings`
  is where a location declares it serves a mode at all, and the resolver intersects it with the
  channel's own mode list. A missing binding is not an error state and must not be styled as one.
- A schedule shared with other branches shows `Используется в 12 филиалах` on the card and again,
  louder, at the top of the editor. Editing a shared timetable is the correct behaviour and also
  the most dangerous action in this section.
- An exception affecting today or tomorrow shows as an inline callout above the cards:
  `21.03 Навруз — не работает` with `Изменить` / `Удалить`.
- Denied: cards render read-only, `Сменить график` omitted.

---

## 4. View — Графики работы (schedule library and editor)

### 4.1 What it is for

*"Edit one timetable and have every branch on it follow."*

### 4.2 Layout

**Master-detail.** Left: a compact list of the brand's schedules. Right: the editor — a
seven-row week builder over a rules table, with the exceptions list beneath.

### 4.3 List columns

| Column | Type | Source |
|---|---|---|
| Название | text | `tenant.service_schedules.name` (unique per tenant+brand) |
| Бренд | text | `tenant.service_schedules.brand_id` |
| Окна | summary, e.g. `Пн–Пт 10:00–23:00 · Сб–Вс 10:00–02:00` | collapsed from `service_schedule_rules` |
| Предзаказы | yes/no pill | `service_schedules.accepts_scheduled_orders` |
| Филиалов | count, clickable | `count(distinct location_id) from tenant.location_service_bindings where schedule_id = ?` |
| Исключений (будущих) | count | `service_schedule_exceptions` where `exception_date >= today` |
| Изменён | `DD.MM HH:mm` | `service_schedules.updated_at` |

Sort: by branch count descending. The timetable governing twenty branches is the one an editor
needs to find, and it is rarely the one first alphabetically.

Filter: brand dropdown (when >1); search over name; a `Только неиспользуемые` toggle that
surfaces orphan schedules nobody bound — the cheapest way to keep the library from rotting.

### 4.4 Editor fields

| Field | Type | Source |
|---|---|---|
| Название | text, required, unique per (tenant, brand) | `service_schedules.name` |
| Принимает предзаказы | switch | `service_schedules.accepts_scheduled_orders` |
| Правило: день | Mon…Sun toggle row | `service_schedule_rules.day_of_week` (1–7, ISO) |
| Правило: открытие | time, `HH:mm` | `service_schedule_rules.opens_at` |
| Правило: закрытие | time, `HH:mm` | `service_schedule_rules.closes_at` |
| Порядок | integer, implicit | `service_schedule_rules.sequence` |

The week builder is a day-of-week toggle row plus per-day window chips: `Пн [10:00–15:00] [18:00–02:00] +`.
A window whose close is ≤ open shows an explicit `+1 день` badge so the operator sees that
`18:00–02:00` is intentional and not a typo.

### 4.5 Actions

| Action | What it does | Needs | Unavailable when | Confirm |
|---|---|---|---|---|
| Сохранить | Replaces the whole rule set (rules are a child collection with `ON DELETE CASCADE`; a whole-set write is the only sane semantic) | `serviceability.manage` | clean | **Yes when bound to >1 branch**, naming the count and listing the branches: *"График «Будни 10–23» используется в 12 филиалах. Изменения вступят в силу немедленно."* |
| Создать | New schedule under the brand | `serviceability.manage` | — | No |
| Дублировать | Copies rules and, optionally, exceptions | `serviceability.manage` | — | No |
| Удалить | Deletes | `serviceability.manage` | any `location_service_bindings` row references it — the FK will refuse, so **disable the action and say which branches** rather than surfacing a 409 | Yes |
| 24/7 | Fills seven `00:00–00:00` rules | `serviceability.manage` | — | No |
| Копировать понедельник на будни / на всю неделю | Convenience fills | `serviceability.manage` | Monday has no window | No |

### 4.6 States

Empty library: *"У бренда нет графиков работы. Создайте один и привяжите к филиалам."* +
`Создать график` + a `Будни 10:00–23:00` starter.
Validation errors are inline per window: overlapping windows on one day (allowed by the schema
but never intentional — warn, do not block), a window shorter than 15 minutes (warn), a day with
no window (that is a closed day, fine — say so explicitly with a `закрыто` chip rather than
leaving the row blank).
Denied: read-only, save bar absent.

### 4.7 Keyboard

`Tab` walks day → open → close. Typing `1000` in a time field means `10:00`. `Cmd/Ctrl+S` saves.
`Alt+↓` copies the focused day's windows to the next day — the single fastest way to build a
week and worth the shortcut.

---

## 5. View — Исключения (non-working-day exceptions)

### 5.1 What it is for

*"What has anyone declared about the holidays, and did someone remember Navruz for every brand?"*

### 5.2 Layout

**A twelve-month calendar for the year, with a list beneath.** A dated exception is a calendar
fact; making the operator read it as a table of ISO dates is how 21.03 gets entered on the wrong
schedule. Days carrying an exception are marked; hovering shows the label and the affected
schedules; clicking opens the day's exceptions.

The list beneath is the working surface: it is sorted, filterable and bulk-editable.

### 5.3 Fields

| Field | Type | Source |
|---|---|---|
| Дата | date, `DD.MM.YYYY` | `tenant.service_schedule_exceptions.exception_date` |
| График | schedule name + brand | `.schedule_id` |
| Тип | radio: «Не работаем» / «Другие часы» | `.closed_all_day` |
| Открытие / Закрытие | time pair, shown only for «Другие часы» | `.opens_at`, `.closes_at` |
| Название | text, required, ≤200 — customer-facing wording (`Навруз`) | `.label` |
| Причина | text, required, ≤400 — internal (`решение управляющего, приказ №14`) | `.reason` |
| Кто добавил | actor | `.created_by` |
| Когда | `DD.MM HH:mm` | `.created_at` |
| Затронуто филиалов | derived count | join through `location_service_bindings` |

The schema enforces the important rule and the form must mirror it exactly: **either the day is
closed and both times are null, or it is open and both times are set** (`ck_schedule_exception_hours`).
Rendering both time fields greyed under a selected «Не работаем» is right; letting them hold
stale values that the API then rejects is not — clear them on switch.

One row per (schedule, date) is enforced by `uq_schedule_exception_date`. When the operator picks
a date that already has an exception on that schedule, the form switches to editing it and says
so, rather than failing on submit.

### 5.4 Filters

- **Период** — segmented `Ближайшие 90 дней` (default) · `Этот год` · `Прошедшие` · `Все`.
  Default forward-looking: a past exception is history, and history is not what anyone opens
  this screen for.
- **Бренд** — dropdown (when >1).
- **График** — dropdown.
- **Тип** — Все / Не работаем / Другие часы.
- **Поиск** over `label` and `reason`.

### 5.5 Sort order

By `exception_date` ascending from today — the nearest thing that will surprise someone comes
first. Past exceptions, when shown, sort descending below a divider.

### 5.6 Actions

| Action | What it does | Needs | Unavailable when | Confirm |
|---|---|---|---|---|
| Добавить исключение | Creates a row on one schedule | `serviceability.manage` | — | No |
| Добавить на несколько графиков | Same date/label/reason written to every selected schedule — the Navruz case | `serviceability.manage` at BRAND | selection spans brands the actor cannot manage | **Yes**, listing the schedules and the branch count each affects |
| Изменить | Edits | `serviceability.manage` | date is in the past | No |
| Удалить | Deletes | `serviceability.manage` | date is today and the branch is currently closed by it — reopening mid-day is the service-state switch's job, not a config delete | **Yes** |
| Импорт праздников | Seeds the year's public holidays from a reference calendar | `serviceability.manage` | — | Yes, with a dry-run preview and per-row opt-out |

`Импорт праздников` needs the business calendar of IA 10.10 (movable Islamic dates included) —
**not built, no owning ADR.** Until it exists, the multi-schedule add covers the same need with
more typing, and that is acceptable; a wrong holiday calendar is worse than none.

### 5.7 States

Empty: *"Исключений нет. Добавьте нерабочие дни и праздники заранее — филиалы закроются сами."*
The wording matters: the value of this screen is that nobody has to remember on the day.
A **conflict callout** when an exception opens hours that fall entirely outside the schedule's
own windows — legal, occasionally intended, usually a mistake. Warn, do not block.

---

## 6. Control — the manual open/closed switch

### 6.1 What it is for

*"The fryer just died. Stop taking orders here, now, and tell me why later — and reopen by
itself so I do not lose Saturday."*

### 6.2 Layout

Not a page. **A dialog reachable from three places**: the shell header's branch status control
(always visible to anyone holding `location.service-state.change`), the location list row, and
the KDS. Delever buries this on the branch edit form; that is a bad design and it costs revenue,
because during service nobody navigates to Настройки → Филиалы → Chilonzor → Редактировать.

Additionally, a persistent **closed-branch strip** at the top of the live board and the location
list whenever any branch is force-closed: `Закрыты вручную: Chilonzor (до отмены) · Yunusobod
(до 21:30)` with a one-click reopen each. This strip is the whole mechanism against the
Thursday-fryer failure, and it should be impossible to dismiss.

### 6.3 Fields

| Field | Type | Source |
|---|---|---|
| Режим | radio: Следовать графику / Закрыть / Открыть принудительно | `tenant.location_service_state.mode` — `FOLLOW_SCHEDULE` / `FORCE_CLOSED` / `FORCE_OPEN` |
| Причина | required select, ≤48 chars | `.reason_code` — **the schema takes free text and there is no reason registry; the vocabulary must be code-owned in the frontend until IA 10.10 reference data exists** |
| Комментарий | text ≤400, required when the reason is `OTHER` | `.note` |
| До | preset chips + custom datetime | `.effective_until` (timestamptz) |
| Кто и когда | read-only | `.changed_by`, `.changed_at` |
| Одновременных заказов | integer ≥1 or «без ограничения» | `.max_concurrent_orders` — separate endpoint (`PUT /capacity`), shown here because it is the other lever for the same problem |

**Proposed reason vocabulary** (closed): `EQUIPMENT_FAILURE` Сломалось оборудование ·
`NO_STAFF` Нет персонала · `POWER_OUTAGE` Нет электричества · `OVERLOADED` Перегрузка кухни ·
`OUT_OF_STOCK` Нет продуктов · `WEATHER` Погода · `RENOVATION` Ремонт ·
`PRIVATE_EVENT` Закрытое мероприятие · `OTHER` Другое.
(force-open): `EXTENDED_HOURS` Продлённые часы · `SPECIAL_EVENT` Мероприятие ·
`CATCH_UP` Догоняем очередь · `OTHER`.

`ck_location_service_reason` makes the reason **mandatory on any override and forbidden on
FOLLOW_SCHEDULE**, and `ck_location_service_expiry` forbids an expiry on FOLLOW_SCHEDULE. The
dialog must clear both fields when switching back to FOLLOW_SCHEDULE rather than sending
values the database will reject.

**Duration presets:** `30 мин` · `1 час` · `2 часа` · `До конца дня` (computed as the end of
today's last window in the branch's timezone, shown as the resolved time so nobody guesses) ·
`До отмены`. The presets are the feature. `До отмены` is deliberately the last chip and is
styled as the dangerous one, because an elapsed `effective_until` returns the branch to the
schedule by *being read as elapsed* — no job, no operator action — while `NULL` means someone
must remember.

### 6.4 Actions

| Action | What it does | Needs | Unavailable when | Confirm |
|---|---|---|---|---|
| Закрыть филиал | `POST /service-state` with mode, reason, note, expiry | `location.service-state.change` at that LOCATION | branch is DRAFT or ARCHIVED | The dialog is the confirmation; the submit button reads `Закрыть до 21:30`, restating the effect |
| Открыть филиал | Sets `FOLLOW_SCHEDULE` (or `FORCE_OPEN` with a reason) | same | already following the schedule | No — but if the schedule says the branch is shut right now, the button reads `Открыть принудительно` and requires a reason, because it is a different act |
| Продлить | Pushes `effective_until` out | same | mode is FOLLOW_SCHEDULE | No |
| Задать вместимость | `PUT /capacity` | `serviceability.manage` | — | No |

Capability scope matters and ADR 0036 already fixed it: `location.service-state.change` is
LOCATION-scoped, so a branch manager closes their own branch without holding rights over the
network. The shell control lists only the branches the actor may act on.

### 6.5 States

- **Closing while orders are in flight** — the dialog shows `В работе: 7 заказов` and a note
  that closing stops *new* orders and does not cancel these. Say it explicitly; the alternative
  is a manager who closes the branch and then phones support asking why orders are still coming.
- **Already closed by exception or outside hours** — the dialog says so and offers
  `Открыть принудительно` as the meaningful action instead.
- **At capacity** — the switch offers `Поднять лимит` beside `Закрыть`, since the manager who
  reaches for the close button when the queue is full usually wants the other lever.
- **Conflict (409)** — someone else changed the state; re-read and show what they did, with
  their name and the time, and let the operator re-apply. Never silently overwrite.
- **Denied** — the shell control is omitted, not disabled.

### 6.6 Keyboard

`c` from anywhere in the operations shell opens the dialog on the operator's default branch.
`1`/`2`/`3` pick a reason from the top three. `Enter` submits. `Esc` cancels. The whole
interaction is four keystrokes because it happens while someone is holding a pan.

---

## 7. View — Филиал → Приготовление (preparation bands and capacity)

### 7.1 What it is for

*"Why does this branch promise 25 minutes at 15:00 and 45 at 20:00 — and is that still right?"*

### 7.2 Layout

**A day-strip editor over a rules table.** The strip is a 24-hour horizontal canvas per
fulfilment mode showing bands as blocks labelled with their minutes; the table beneath is the
authoritative editable list. Editing on the strip drags edges; editing in the table types
numbers. Both write the same rows.

Overlap is legal and settled by `priority`, so the strip draws overlapping bands stacked with
the winning one solid and the losers hatched — the operator must be able to see that the
Friday-evening band is being shadowed by a lower-priority one, which is otherwise invisible
until a customer gets the wrong promise.

### 7.3 Fields

| Field | Type | Source |
|---|---|---|
| Режим | DELIVERY / PICKUP / DINE_IN / «любой» | `tenant.preparation_bands.fulfillment_mode` (null = any) |
| День недели | Mon…Sun / «любой» | `.day_of_week` (null = any, else 1–7 ISO) |
| С | time | `.starts_at` |
| По | time | `.ends_at` — **must be > starts_at; bands do not wrap past midnight** (`ck_preparation_band_window`). The after-midnight case is two rows and the editor must split it automatically with a visible note |
| Минут | integer 1–1440 | `.duration_minutes` |
| Приоритет | integer | `.priority` |

### 7.4 Actions

| Action | What it does | Needs | Unavailable when | Confirm |
|---|---|---|---|---|
| Сохранить | `PUT /preparation-bands` — **a whole-set replace**, matching the built endpoint (`BandsRequest`) | `serviceability.manage` | clean | **Yes when the set shrinks**: *"Будет удалено 3 полосы"* |
| Добавить полосу | Appends a row | `serviceability.manage` | — | No |
| Копировать на другие филиалы | Cascade to selected branches of the same brand | `serviceability.manage` at BRAND | selection empty | Yes, with the target list |
| Проверить | Local dry-run: for each 15-minute slot of the week inside the bound opening windows, shows which band wins | `serviceability.read` | no bands | No |

`Проверить` is worth building. The resolution rule — the band covering the order's start
instant, then the max of that and any line-level `preparation_duration_override` — is stated in
`PreparationPromise` and is exactly the kind of rule that nobody can hold in their head.

### 7.5 States

- **No band covers a currently-open hour** — an amber callout listing the uncovered windows.
  `Serviceability.preparationMinutes` is null there, so the storefront quotes no time at all;
  that is a real customer-facing hole and the editor must name it.
- **A band outside every opening window** — muted warning, not an error. Legal and pointless.
- **Capacity panel** on the same tab: current open holds against `max_concurrent_orders`,
  drawn as the Togora slot lattice degenerated to one row — occupied slots solid, free slots
  **dashed outlines rather than blank**, because unsold capacity is the planning question.
  Source: `tenant.location_capacity_holds` (`released_at IS NULL`) against
  `tenant.location_service_state.max_concurrent_orders`.
  Note in the UI copy that the cap is advisory at browse and authoritative at checkout.
  `location_capacity_holds` is explicitly interim — it disappears when ADR 0019's
  `ordering.orders` becomes the counted set — so do not build a screen that reads it directly;
  read it through the capacity port.

---

## 8. View — Филиал → Каналы, and the Каналы × Филиалы matrix

### 8.1 What they are for

*"Which routes does this branch sell through — and, from the other direction, which branches
does the Telegram bot cover?"*

### 8.2 Layout

Two views over the same table, because both questions get asked and neither is a filter of the
other.

**On the branch:** a card list, one card per registered channel of the tenant, each in one of
three states — *не подключён* (no row), *подключён* (`status = 'ACTIVE'`), *приостановлен*
(`status = 'INACTIVE'`). Three states, not a checkbox: absent and paused both refuse with
`CHANNEL_NOT_ENABLED`, but only one of them was a decision someone made, and conflating them
loses that.

**Tenant-wide:** an editable **matrix grid**, channels as rows, branches as columns, with
row and column bulk toggles. This is the shape for "switch the whole aggregator on for the
five branches in Tashkent", and it is the component ADR 0035 lists as missing (`MatrixGrid`).

### 8.3 Fields

| Field | Type | Source |
|---|---|---|
| Канал | name + system-type icon | `tenant.sales_channels.display_name`, `.system_type` (WEB, IOS, ANDROID, TELEGRAM, KIOSK, QR_TABLE, CALL_CENTRE, AGGREGATOR, POS) |
| Код канала | mono | `tenant.sales_channels.code` |
| Состояние канала | badge | `tenant.sales_channels.status` — ACTIVE / INACTIVE / ARCHIVED |
| Подключён здесь | tri-state | `tenant.sales_channel_locations.status` or row absence |
| Режимы канала | pills, read-only on this tab | `tenant.channel_fulfillment_modes.fulfillment_mode` where `enabled` |
| Способы оплаты | pills, read-only | `tenant.channel_payment_methods.payment_method_code` where `enabled` — **codes only; the registry they should point at is ADR 0038's `payments.payment_methods`, not built** |
| Ценовая плоскость | text, read-only | `tenant.sales_channels.price_plane_channel_id` |
| Внешнее ценообразование | pill | `tenant.sales_channels.externally_priced` |
| Живое меню | ✓/✗ per channel | `catalog.publications` for (tenant, brand, channel code) |
| Скрытые позиции | count, clickable | `catalog.channel_offering_exclusions` for this channel, at this location or brand-wide |

The **effective mode set** is the intersection of the channel's modes and the branch's bound
modes, and the card must show it explicitly:
`Канал: Доставка, Самовывоз · Филиал: Доставка · Итого здесь: Доставка`.
This intersection is where most "why can't customers order pickup on the bot" questions end,
and showing the arithmetic answers them without a support ticket.

### 8.4 Filters (matrix view)

Channel system-type dropdown · brand dropdown (>1) · a `Только активные каналы` toggle
(archived channels are hidden by default but never removed — every historical order references
one) · branch search narrowing the columns.

### 8.5 Actions

| Action | What it does | Needs | Unavailable when | Confirm |
|---|---|---|---|---|
| Подключить канал к филиалу | Whole-matrix `PUT` with an expected version — **never a per-cell PATCH** (ADR 0036: a matrix edited cell by cell from two tabs produces a combination neither operator chose) | `channel.manage` | channel is ARCHIVED | No for one cell; the save bar batches edits and states the count |
| Приостановить здесь | Sets `INACTIVE` | `channel.manage` | not bound | No |
| Отключить здесь | Deletes the row | `channel.manage` | not bound | **Yes** — explain that removing is not the same as pausing, and that the branch will report `CHANNEL_NOT_ENABLED` either way |
| Включить строку / столбец | Bulk over one channel or one branch | `channel.manage` | — | Yes when it touches >5 cells, with the count |
| Скрыть позицию на канале | Adds a `catalog.channel_offering_exclusions` row (optionally location-scoped) | `catalog.manage` | — | No — sparse exclusions, read live, take effect immediately |
| Открыть канал | Navigates to the channel record (Sales channels section) | `channel.read` | — | No |

A stale matrix write returns 409; re-read and render the differences as a cell-level diff before
letting the operator re-apply. Two people configuring an aggregator rollout at once is the
normal case, not the exotic one.

### 8.6 States

- Zero channels bound → the branch cannot sell at all. This is a **blocking** state: red banner
  on the branch header, and the branch appears in `Требуют внимания`.
- Channel ACTIVE at tenant level but INACTIVE here → grey card with the pause reason absent
  (there is no reason column; if that turns out to matter, it is a schema addition, and it
  probably does — note it as a candidate).
- Channel whose plan entitlement was withdrawn → ADR 0021 forces it INACTIVE and never deletes
  it; render as `Недоступно в вашем тарифе` with a link, not as an error.
- `NO_LIVE_MENU` → amber, with `Опубликовать меню` linking to catalog.

---

## 9. View — Филиал → Доставка, and Зоны доставки (ADR 0037 — not built)

### 9.1 What they are for

*"Where does this branch deliver, and what does the customer pay?"*

**Everything in this section is unbuilt.** `fulfillment` currently contains only
`package-info.java`: no zone table, no tariff table, no PostGIS, no distance calculation. The
spec below is written against ADR 0037's accepted physical model so the screens can be built
the moment the migration lands, and so nothing is designed that the model cannot support.

### 9.2 Layout

**Зоны доставки** is a **map-first master-detail**: the canvas is the primary surface, a
collapsible list of zones sits left, and the selected zone's properties open in a right rail.
A polygon editor cannot be a form; a form cannot be a polygon editor.

**Филиал → Доставка** is a read-mostly summary: the branch's bound zones drawn on a small map,
its tariff resolution chain, and the two switches that belong to the branch rather than to the
zone.

### 9.3 Fields — zone

| Field | Type | Source (ADR 0037) |
|---|---|---|
| Код | mono, unique per (tenant, brand) | `fulfillment.service_zones.code` |
| Название (ru/uz/en) | text | `service_zones.display_name_ru/uz/en` |
| Роль | DELIVERY / CATCHMENT | `service_zones.zone_role` |
| Версия | integer + status DRAFT/ACTIVE/RETIRED/DISCARDED | `service_zone_versions.version`, `.status` |
| Геометрия | polygon/multipolygon on the map | `service_zone_versions.area geography(MultiPolygon,4326)` |
| Исходная форма | circle centre+radius, or polygon | `service_zone_versions.authoring_shape jsonb` |
| Приоритет | integer | `service_zone_versions.priority` |
| Площадь | km², read-only | `service_zone_versions.area_sq_meters` |
| Тариф | tariff picker | `service_zone_versions.delivery_tariff_id` |
| Бесплатная доставка от | money | `service_zone_versions.free_delivery_from_minor` |
| Минимальная сумма заказа | money | `service_zone_versions.min_basket_minor` |
| Филиалы | multi-select | `fulfillment.zone_location_bindings` |
| Кто и когда активировал | actor + timestamp | `service_zone_versions.activated_by`, `.activated_at` |

### 9.4 Fields — tariff

| Field | Type | Source |
|---|---|---|
| Название, статус, версия, валюта | — | `fulfillment.delivery_tariffs` |
| Источник цены | TARIFF / PROVIDER_QUOTE | `delivery_tariffs.fee_source` |
| Режим расстояния | RADIUS / ROAD | `delivery_tariffs.distance_mode` |
| Коэффициент объезда | basis points | `delivery_tariffs.road_factor_basis_points` |
| Максимальное расстояние | metres | `delivery_tariffs.max_distance_meters` |
| Мин / макс стоимость | money | `delivery_tariffs.min_fee_minor`, `.max_fee_minor` |
| Полосы: от / до / база / за км | table | `delivery_tariff_bands.from_meters`, `.to_meters`, `.base_minor`, `.per_km_minor` |
| Временные правила: дни, окно, множитель, надбавка | table | `delivery_tariff_time_rules.day_of_week_mask`, `.local_from_time`, `.local_to_time`, `.multiplier_basis_points`, `.surcharge_minor` |

The band table must **show the tiling as a continuous ruler**, not as six independent rows,
because the activation rule is that bands tile `[0, max_distance_meters)` with no gap and no
overlap. A gap is what makes 4 700 m unpriceable while 4 600 and 4 800 price fine, and nobody
finds that until a customer reports it. Draw the ruler with the gap in red and refuse activation.

### 9.5 Actions

| Action | What it does | Needs | Unavailable when | Confirm |
|---|---|---|---|---|
| Нарисовать зону | Creates a DRAFT version | `zone.manage` | — | No |
| Новая версия | Clones the active version into a DRAFT — **geometry, priority, tariff and threshold edits never mutate an active version** | `zone.manage` | a DRAFT already exists for this zone | No |
| Активировать | Validates (self-intersection, area ceiling, inside the brand's regions) then activates; ADR 0027 approval above the risk threshold | `zone.manage` + approval | validation fails | **Yes** — *"Активация изменит зону обслуживания для 6 филиалов немедленно"* |
| Вывести из обращения | RETIRED | `zone.manage` | it is the only active DELIVERY zone of a trading branch | Yes |
| Симуляция | `POST /delivery-tariffs/{id}/simulate` — full resolver against a supplied point, basket and fixed clock, writing nothing | `zone.read` | — | No |
| Импорт зон | Legacy JSON → DRAFT versions rendered on the map beside their source | `zone.manage` | — | Yes, with row-level outcome reporting |

**The simulator is on the main path, not three clicks away.** "What would delivery cost from
Chilonzor to this address at 19:00" must be answerable before activation, not after a customer
finds out. Build it as a right-rail panel of the zone map: drop a pin, pick a time, see the
resolved zone, band, time rule, distance, distance source and the fee — the same evidence the
`fulfillment.delivery_fee_resolutions` row will carry.

### 9.6 States

- **Overlapping zones** — legal and normal (a premium inner-city zone inside a wider city zone).
  Render the overlap hatched and, on hover, name the winner and the reason
  (`приоритет 20 > 10`), matching the documented rank: priority desc, then smaller area, then
  zone id ascending.
- **A branch with no zone** — cannot take delivery orders. Blocking banner on the branch.
- **A zone with no tariff** — `NO_TARIFF`, the quote is refused. There is **no implicit zero**:
  a missing tariff and free delivery must never look alike, and the UI must not render an
  unpriced zone as `0 so'm`.
- **DRAFT left sitting** — ADR 0037 predicts operators will nudge a polygon and leave the
  corrected zone in DRAFT. Counter it: a persistent `Черновики зон: 3` chip in the section
  header, and the branch's Доставка tab showing `Есть неактивированная версия` on any zone
  bound to it.
- **PostGIS absent** — the whole section renders a single explanatory card rather than an
  error, because until the migration lands this is a not-yet, not a fault.

---

## 10. View — Филиал → Фискальные данные, and Юридические лица (ADR 0038 — not built)

### 10.1 What it is for

*"Which company issues the receipt for orders taken at this branch, and from when?"*

### 10.2 Layout

On the branch: a **timeline of assignments**, not a single INN field. The whole point of ADR
0038's model is that a branch's fiscal identity has a validity range and history — a
re-registration must not rewrite what a delivered order's receipt said. A single editable field
would destroy exactly that.

Tenant-wide: `Юридические лица` as a plain list-and-record.

### 10.3 Fields

**Legal entity** — all from `tenant.legal_entities`: `code`, `legal_name`, `short_name`, `tin`
(unique per tenant, mono, validated by length and checksum), `vat_registered`,
`vat_certificate_reference`, `tax_profile_id` (ADR 0018 profile), `registered_address`,
`contact_phone`, `status`, `version`.

**Assignment** — from `tenant.location_fiscal_assignments`: `legal_entity_id`, `effective_from`,
`effective_until`, `approved_by`, `approval_reference`, `version`.

An exclusion constraint forbids overlapping ranges for one location. The form must therefore
never offer a start date inside an existing range without also proposing to close that range —
present it as *"Сменить юридическое лицо с 01.09.2026"*, which closes the current assignment
and opens the new one in one transaction, rather than as two independent date fields the
operator has to reconcile.

### 10.4 Actions

| Action | What it does | Needs | Unavailable when | Confirm |
|---|---|---|---|---|
| Назначить юридическое лицо | Creates the assignment | `fiscal.manage` + ADR 0027 approval | overlapping range | **Yes**, restating the effective date and that past orders keep their old entity |
| Сменить с даты | Closes the current range and opens a new one | same | date in the past | **Yes** |
| Создать юридическое лицо | Registry row | `fiscal.manage` | duplicate TIN in tenant | No |

### 10.5 States

- **Branch with no active assignment** — blocking: it cannot be activated for any channel that
  can produce a receipt obligation. Red banner, and the branch sits in `Требуют внимания`.
- **Assignment ending soon** — amber chip 30 days out.
- **Unregistered-for-VAT entity** — a note explaining that its tax profile differs, because a
  tenant with one registered and one unregistered company is a normal shape here and the price
  difference will otherwise look like a bug.

---

## 11. View — Бренды (brand list)

### 11.1 What it is for

*"Which trade marks does this tenant operate, and which one am I working in?"*

### 11.2 Layout

A short list, suppressed entirely when the tenant has one brand. Most restaurants have one.

### 11.3 Columns

| Column | Type | Source |
|---|---|---|
| Логотип | 40px thumbnail | `media.assets` `owner_scope = 'BRAND'` — **role binding not built** (see §2.3) |
| Бренд | text | `tenant.brands.display_name` |
| Код / Slug | mono | `tenant.brands.code`, `.slug` |
| Статус | badge | `tenant.brands.status` — DRAFT / ACTIVE / SUSPENDED / ARCHIVED |
| Филиалов | count, clickable → filtered location list | `count(*) from tenant.locations where brand_id = ?` |
| Графиков | count | `tenant.service_schedules` |
| Живых публикаций | count per channel | `catalog.publications` |
| Изменён | `DD.MM HH:mm` | `tenant.brands.updated_at` |

Sort: ACTIVE first, then by name. Filter: status dropdown, search.

### 11.4 Actions

`Открыть` · `Приостановить` (confirm — every location of the brand stops trading) ·
`Архивировать` (confirm, typed code).

**Brand creation is deliberately absent from operations.** IA Part 2 states it: merchants
*request* an additional brand or location, and provisioning happens in control-plane 2.3, so
legal entity, residency, entitlement and metering are settled at creation. The button here is
`Запросить новый бренд`, which opens a request, not a form. Say why in the empty state; an
operator who cannot find "create" and is not told why will file a bug.

---

## 12. View — Бренд (brand record)

### 12.1 What it is for

*"The identity every branch of this brand inherits."*

### 12.2 Layout

Tabbed record: `Профиль · Медиа · Филиалы · Графики · Каналы · История`.

### 12.3 Fields

| Field | Type | Source |
|---|---|---|
| Название | text ≤200 | `tenant.brands.display_name` |
| Название (ru/uz/en), Описание | text | **not built** — same translation gap as §2.3 |
| Код | mono, `^[A-Z0-9][A-Z0-9_-]{0,31}$` | `tenant.brands.code` |
| Slug | mono | `tenant.brands.slug` |
| Статус | badge/segmented | `tenant.brands.status` |
| Логотип | image 1:1 | `media.assets` BRAND-scoped; role binding not built |
| Баннер (агрегатор + QR-меню) | image | same |
| Телефон | phone | **not built** — no column |
| Телеграм-бот | handle | **not built — ADR 0020 / channel setup** |
| Страна | dropdown driving currency, phone format, timezone | **not built at brand level** — `tenant.tenants.default_currency` / `.default_timezone` exist at tenant scope only; per-brand currency is an IA requirement (2.3) with no column |
| Языки, язык по умолчанию | multi-select + default | **not built** — `platform.default_locale` exists as an ADR 0030 configuration key at PLATFORM scope and can be overridden at BRAND scope today, which covers the default but not the supported set |
| Валюта | read-only | `tenant.tenants.default_currency` |
| Старый идентификатор | mono, read-only, visible only during migration | `tenant.brands.legacy_company_id` |

### 12.4 Actions

`Сохранить` (optimistic concurrency on `version`) · `Приостановить` / `Активировать`
(confirm — cascades to trading) · `Архивировать` (confirm) ·
`Применить контакт ко всем филиалам` (the cascade, with the same preview-and-opt-out
treatment as §2.4).

### 12.5 States

DRAFT brand: banner listing what blocks activation (no location, no schedule, no channel).
Denied: read-only.
Suspended brand: every location row in the Филиалы tab is greyed with `Бренд приостановлен`,
because a per-branch open switch will not help and the operator should not go hunting.

---

## 13. Panel — «Почему нельзя продавать?» (serviceability explainer)

### 13.1 What it is for

*"The bot says we are closed. We are standing here and we are open. Why?"*

### 13.2 Layout

A right-side drawer, opened from the branch header, the location list row menu, the KDS and the
order-entry branch picker. Three pickers at the top — **филиал × канал × режим** — then the
resolver's eight rules rendered **in evaluation order** with pass/fail and a fix link each.

```
Филиал: Chilonzor   Канал: Telegram-бот   Режим: Доставка          [обновить]

✓ 1–2  Канал активен и подключён к филиалу
✓ 3    Канал поддерживает «Доставка»
✗ 4    Закрыт вручную: сломалась печь · с 19:14 · до отмены      [Открыть филиал]
·  5    Дата-исключение                     не проверялось
·  6    Часы работы                         не проверялось
·  7    Живое меню                          не проверялось
·  8    Лимит одновременных заказов         не проверялось

Ответ: недоступно · MANUALLY_CLOSED · возобновление: неизвестно · предзаказ: да
```

Rules after the first failure render as **"not evaluated"**, greyed — not as passes. The
resolver short-circuits, and showing rule 6 as green when it never ran is a lie that sends
someone to the opening-hours screen for an hour. This is precisely the failure
`ServiceabilityReason`'s own doc comment names.

### 13.3 Fields

Every line maps to one `ServiceabilityReason` value: `CHANNEL_NOT_ENABLED` (rules 1–2),
`FULFILMENT_MODE_UNAVAILABLE` (3), `MANUALLY_CLOSED` (4), `CLOSED_BY_EXCEPTION` (5),
`OUTSIDE_SERVICE_HOURS` (6), `NO_LIVE_MENU` (7), `AT_CAPACITY` (8). The footer renders the
`Serviceability` record verbatim: `available`, `reason`, `nextAvailableAt`,
`acceptsScheduledOrders`, `preparationMinutes`.

`acceptsScheduledOrders` must be shown even when unavailable, because "closed now" and "cannot
pre-order" are different facts and the schema keeps them separate on purpose.

### 13.4 Actions

Each failing rule carries the one action that fixes it: `Открыть филиал` (§6),
`Подключить канал` (§8), `Изменить график` (§4), `Удалить исключение` (§5),
`Опубликовать меню` (catalog), `Поднять лимит` (§7). No generic "go to settings".

### 13.5 States

The panel never caches: every open re-runs the resolver and stamps the answer with the time it
was computed. A stale availability explainer is worse than none.

---

## 14. What Delever has, and what we do about it

### Match

| Delever | Why we match it |
|---|---|
| Branch registry with list and full edit form | The spine of the section |
| Per-day-of-week open/close, separate venue and delivery schedules, 24/7 shortcut | The need is real; our shape (named schedules bound per mode) is strictly more expressive and covers theirs |
| Time-of-day preparation intervals | Built (`tenant.preparation_bands`); a Friday rush quoting 45 minutes instead of 25 is the difference between a late order and an honest one |
| Per-branch order limit | Built (`max_concurrent_orders`) |
| Per-branch phone, address, landmark, map pin, INN | Not built and must be — every one of these blocks a real workflow |
| Order types permitted per branch | Built as `location_service_bindings` per mode, intersected with the channel |
| Delivery zones with per-zone tariff, free-delivery-from threshold, minimum basket | ADR 0037 |
| Zone tariff outranking branch tariff | ADR 0037 fee-resolution step 4, stated once and in one place — which Delever never does |
| Regions with SW/NE bounding box constraining the geocoder | ADR 0037 `fulfillment.regions`. This is a good idea and prevents a genuine failure: an unconstrained geocoder returns a same-named street in another country and the error surfaces when a courier is standing somewhere else |
| Branch tags | Cheap, useful for grouping; not built, no ADR |
| Per-branch Telegram chat IDs for five event classes | ADR 0020; the legacy Qoida dashboard had two of them (`tg_chat_id`, `tg_delivery_chat_id`) and staff use them |
| Bulk "load geozones" from the registry | Keep as a proper import wizard with row-level outcomes |

### Beat

1. **The open/closed switch belongs in the shell, not on the branch edit form.** Delever buries
   it in settings. During service nobody navigates four levels to stop the orders. Ours is one
   keystroke from anywhere, reason-mandatory, with duration presets and a self-expiring close.
2. **A close carries a reason, an actor and an expiry.** Delever's is a boolean activity toggle.
   The schema already enforces reason-on-override; the UI must make `до 21:30` the easy path and
   `до отмены` the deliberate one. This single change removes the most expensive silent failure
   in branch operations.
3. **Reusable named timetables instead of hours-on-the-branch.** Thirty branches on one Ramadan
   schedule edit one object. Delever edits thirty forms, or runs a bulk patch nobody can review.
4. **One zone entity with a typed role**, replacing Delever's three overlapping geometry layers
   (branch geozone, delivery zone, "free geozone") whose interaction its own docs never explain.
   A free geozone is a delivery zone whose tariff resolves to zero.
5. **Fee resolution written down once, with evidence per quote.** Delever has at least four fee
   sources and no stated precedence. Ours records zone version, tariff version, band, time rule,
   distance and distance source on every resolution, so "why was this delivery 18 000 so'm" has
   an answer six weeks later.
6. **Zones are versioned and a quote pins the version.** A polygon edit cannot retroactively
   change what a past order was charged.
7. **The serviceability explainer.** Delever has no single answer to "why can't I sell here";
   its operators go screen to screen. One resolver, one panel, rules in evaluation order,
   unevaluated rules shown as unevaluated.
8. **Fiscal identity as a dated assignment, not a field.** A re-registration must not rewrite
   what a delivered order's receipt said.
9. **The cascade shows its work.** Delever's bulk actions and the legacy dashboard's
   company-scope patch both fire and forget. Ours previews the target list, allows per-branch
   opt-out, and produces one audit record per branch.
10. **Absent vs paused vs active channel binding**, three states rendered distinctly, instead of
    a checkbox that loses the difference between "never configured" and "deliberately off".

### Skip, and why

| Skipped | Reason |
|---|---|
| Venue attributes — seats, average cheque, parking, playground, 3D virtual tour | Storefront marketing content, not operations. Nothing in the console reads them and no order depends on them. If a storefront needs them, they belong to a CMS/merchandising surface, not the branch record a manager opens during service |
| "Free geozone" as a separate layer | Collapsed into a DELIVERY zone whose tariff resolves to zero (ADR 0037) |
| Branch geozone as a separate table | It is the `CATCHMENT` role |
| Delever's own SaaS commerce inside the merchant console — Тарифы (subscription), Баланс, dunning | That is Qoida's billing, not the restaurant's structure; it lives in the plan/entitlement surface |
| Version management / v1-v2 feature flags per tenant | An artefact of Delever shipping two admin panels at once. Do not reproduce the condition |
| Per-branch POS/integration credentials on the branch form | Real and needed, but owned by 10.8 Integrations; this section links to them and does not duplicate the fields |
| Floor plan (sections and tables) on the branch record | ADR 0047 dine-in; a genuinely separate surface with its own canvas |

### Where sources disagree

- **IA 10.2 lists "venue and delivery schedules" as two fields on the branch; ADR 0036 makes
  them named bindings per mode.** ADR 0036 is right and is built. The IA's phrasing is a
  description of Delever, not a requirement.
- **The parity matrix says Delever's branch form carries "Тариф доставки (required)".** ADR 0037
  makes the tariff resolve zone-first, location-second, brand-default-third. Keep the per-branch
  tariff binding as a field, but render it as *one step of a resolution chain* with the chain
  visible, not as the answer.
- **Delever's docs for delivery zones, delivery tariffs and company settings v1 are
  video-only** — `settings/company.md` is seven embedded Loom videos and no prose;
  `geozony/zony-dostavki.md` and `dostavka/fares.md` are a single Loom each. The branch registry
  page (`admin-panel-1/settings/company/filialy.md`) and the regions page **do** carry text, and
  the field lists in §14 "Match" are taken from them. Where this spec asserts something about
  Delever's zone or tariff form beyond what the parity matrix records, it is inference and is
  marked as such — it is not read from documentation, because there is none.

---

## 15. What the legacy dashboard did that staff will expect

Read from `legacy-archive/qoida-dashboard`. Legacy `Company` = brand (`tenant.brands.legacy_company_id`);
legacy `Vendor` = branch (`tenant.locations.legacy_vendor_id`).

| Legacy behaviour | Where it went |
|---|---|
| `name` and `description` as `{en, ru, uz}` on both company and vendor | **Regression risk.** `display_name` is a single string today. Staff type three languages and will notice. Named as a gap below |
| Vendor list columns: Название, Телефон, Максимальная видимость, Цена доставки | Phone returns in §2.3 (unbuilt). "Максимальная видимость" (`visibility_distance`, default 100 000) is Delever's max-distance under another name → ADR 0037 `delivery_tariffs.max_distance_meters`. "Цена доставки" (`delivery_price`, default 12 000) → the tariff's base band |
| `tin` on the vendor form | §10, as a dated assignment rather than a text field |
| `pre_order` boolean per vendor | `service_schedules.accepts_scheduled_orders` — moved to the schedule, which is more expressive. Explain the move in the UI copy, because the field moved screens |
| `latitude` / `longitude` with regex-validated manual entry | Replaced by a map pin, keeping numeric entry as a fallback — operators here do paste coordinates. **Column unbuilt** |
| `city_id` free text | → ADR 0037 `fulfillment.regions` |
| `image` + `background_image` upload | `media.assets` LOCATION/BRAND scope; role binding unbuilt |
| Work-time config: `working_days.{monday..sunday}.{start,end}` + `non_working_days[{date,start,end}]` | Exactly `service_schedule_rules` + `service_schedule_exceptions`. Note the legacy shape allowed **one window per day**; ours allows several, so nothing is lost. Note also that legacy `non_working_days` carried replacement hours — so did the exception model, and the schema enforces the either/or the legacy JSON did not |
| Delivery config: `distance`, `max_distance`, `distance_price`, `min_order_price`, `discount{value,type,min_order_price,times[]}`, `prices_per_km[{distance,price}]`, `peak_hours[{start,end,distance,price}]` | Every one maps onto ADR 0037: base band, `max_distance_meters`, band `base_minor`, zone `min_basket_minor`, zone `free_delivery_from_minor`, `delivery_tariff_bands`, `delivery_tariff_time_rules`. **This is the strongest evidence that ADR 0037's model is the right size** — it was reverse-engineered from a real config the client already runs |
| `tg_chat_id`, `tg_delivery_chat_id` | ADR 0020, unbuilt. Two of Delever's five classes |
| **Company-scope patch**: `PATCH companies/{id}/vendors/work-time` applying to every vendor at once, with a raw-JSON textarea | The cascade in §2.4 and §4.5 — same power, with a preview, per-branch opt-out and per-branch audit. **Do not lose the capability**; do lose the textarea |
| Vendor-scope patch of the same three configs | Now first-class tabs |
| Dispatcher role sees the list read-only, action buttons omitted | Keep exactly — affordances omitted, not disabled |
| Hard `DELETE /vendors/{id}` | **Deliberately dropped.** Locations archive. Every order references its location forever, and a deleted row makes that order unattributable in every report — the same argument ADR 0036 makes for channels |
| Metric strip above the table (Всего поставщиков, Патч-конфиг, Режим) | Keep the shape, change the content: counts derived from the same array the table renders so they cannot disagree with it (Togora 2o). "Патч-конфиг: Готов" was chrome; replace with `Закрыто вручную`, `На пределе`, `Без канала` |
| Russian-only interface | Uzbek and Russian, switchable |

---

## 16. Data the backend does not have yet

| Missing | Precisely what | Owner |
|---|---|---|
| Location contact phone | column on `tenant.locations` | **No ADR.** Nearest owner ADR 0002. Needs a schema-extension decision |
| Location address and landmark | columns or a structured value on `tenant.locations` | **No ADR.** ADR 0015 fixes the shape for *customer* addresses (entrance/floor/apartment/landmark inside `encrypted_fields`); a branch address is not personal data and should be plain columns |
| Location coordinates | `latitude`, `longitude` on `tenant.locations` | **ADR 0037 depends on it and does not create it.** `RADIUS` distance is "haversine from the location point"; the point does not exist. Must land with 0037 or before |
| Localized names and descriptions for brands and locations | `catalog.translations.ck_translation_entity_type` admits catalog entities only | **No ADR.** ADR 0016 owns the catalog table; a tenant-scope equivalent needs a decision |
| Media role binding for brand logo, branch cover, aggregator banner | `media.assets` carries `owner_scope IN ('TENANT','BRAND','LOCATION')` but nothing records which asset plays which role; `catalog.media_relations` is catalog-only | **ADR 0010** |
| Per-brand country, currency, locale set | `tenant.tenants.default_currency` / `.default_timezone` are tenant-scope only; IA 2.3 requires per-brand | **ADR 0034** (residency, country → currency/locale/timezone) + **ADR 0002** |
| Service-state reason vocabulary | `location_service_state.reason_code` is free `varchar(48)` with no registry | **ADR 0036** left it open; IA 10.10 reference data would own the table. Code-owned enum in the frontend until then |
| Channel binding pause reason | `sales_channel_locations` has `status` and no reason | **ADR 0036**. Candidate addition; the same argument that made a close reason mandatory applies |
| Pre-order lead time per fulfilment mode | Delever and legacy both have it; `accepts_scheduled_orders` is a boolean only | **ADR 0019** |
| Payment-method registry behind `channel_payment_methods.payment_method_code` | The column is a code with no FK; V0020's own comment says point it at `payments.payment_methods` once 0038 lands | **ADR 0038** |
| Legal entities and per-branch fiscal assignment | `tenant.legal_entities`, `tenant.location_fiscal_assignments` | **ADR 0038** — Proposed, not started |
| Delivery zones, regions, tariffs, bands, time rules, fee resolutions | the whole `fulfillment` schema per §9.3–9.4 | **ADR 0037** — Accepted, not started. PostGIS not enabled |
| Branch tags | vocabulary + assignment | **No ADR.** IA 10.10 |
| Named `Menu` entity bound to a branch | `catalog.location_offerings` is variant-level; copy-menu and bind-to-branch have nothing to hang on | **ADR 0016 divergence**, named in IA §4.4 as the single biggest gap |
| Storefront sort order for branches | integer on the location | **No ADR.** IA 6.8 |
| Per-branch Telegram chat IDs by event class | five channels per branch, with topic ids | **ADR 0020** |
| Business calendar (public and movable Islamic holidays, weekend definition, business-day boundary crossing midnight) | needed by `Импорт праздников` (§5.6) and by reporting | **No ADR.** IA 10.10 |
| Venue attributes | seats, average cheque, parking, playground, virtual tour | **No ADR — and deliberately not requested.** See §14 Skip |

Two of these are **blocking**, not merely missing: a branch has no coordinates, so ADR 0037
cannot compute a fee; and a branch has no phone or address, so no courier and no customer can
reach it. Everything else in this section can be built and shipped around its gap. These two
cannot.

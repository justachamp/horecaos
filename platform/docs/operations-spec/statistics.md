# Operations spec — Statistics and reports

**Application:** `apps/operations` · **IA section:** Part 2 §7 Reports (7.1–7.10)
**Audience:** branch manager, owner, finance, operations director. Not HorecaOS staff.
**Sources:** [parity matrix](../delever-parity-matrix.md) §Дашбоард/Клиенты and
§Персонал · Delever live docs (fetched, cited inline) ·
the legacy `qoida-dashboard` archive (outside this repository, in the founding
machine's HorecaOS workspace) ·
[IA](../frontend-information-architecture.md) §7 ·
[Togora report](../togora-prototype-report.md) §2 ·
[ADR 0043](../adr/partial/0043-reporting-analytics-and-the-metric-layer.md) (Accepted,
implementation not started).

---

## 0. Scope, and what this section is deliberately not

This section owns **every number a manager reads about the past**. It does not
own numbers about *now*.

| Concern | Owner | Why the split matters |
|---|---|---|
| "How many orders are in the kitchen right this second" | **0.1 Live board**, ADR 0045 | Reading a day-grain fact for a live counter makes it both stale and expensive. ADR 0043 says this explicitly. |
| "How many orders did we do yesterday" | **7.x, here**, ADR 0043 | Closed, versioned, reproducible, exportable. |
| Courier payout arithmetic (`К оплате`) | **8.5 Courier payouts**, ADR 0042 | A payout is a money movement with an approval, not a report. 7.4 *reports on* courier performance; it never computes what to pay. |
| Cash reconciliation and инкассация | **8.3**, ADR 0042 | Same reason. |

Two rules bind every screen below and are not restated per view:

1. **No surface computes an aggregate.** Every number resolves to a metric id
   from `reporting.metric_definitions` (ADR 0043). A tile that cannot name its
   metric id does not ship. This is why the metric dictionary (§2.12) is a real
   screen and not a tooltip.
2. **Every money figure is grouped by legal entity, never by tenant** (ADR 0038
   + 0043). A tenant trading as two companies on one evening produces a
   tenant-level revenue total that reconciles to neither tax filing.

Money is whole som throughout, rendered `1 234 500 сум`, mono font, right
aligned. Dates DD.MM, times 24h, `Asia/Tashkent`. Durations are `мм:сс` under an
hour and `ч:мм` above.

---

## 1. Shared machinery

Specified once because it appears on all ten views. Building it twice is how two
screens come to disagree.

### 1.1 The global filter bar

One sticky bar, **two rows with deliberately different visual weight**
(Togora §2c — different weight says "different axis", not "one long wrap").

**Row 1 — the period axis (primary; dark-filled pills).**

| Control | Type | Detail |
|---|---|---|
| Период | Pill group + reveal | `Сегодня · Вчера · 7 дней · Месяц · Период…`. `Период…` reveals two inline `<input type="date">` beside the pill rather than opening an overlay (Togora §2b). |
| Гранулярность | Segmented | `Час · День · Неделя · Месяц`. `Час` is disabled with a tooltip when the range exceeds 3 days; `День` disabled above 400 days. Never silently re-buckets. |
| Опердень | Read-only chip | `Опердень 00:00→00:00`. Renders the tenant's `reporting.business_day_start`. Clicking opens the provenance panel (§1.2). Turns amber and the range is **refused** when the selected range spans two boundary regimes (ADR 0043 requires refusal, not silent mixing). |
| ← / → | Icon buttons | Shift the whole range back/forward by its own length. Keyboard `[` `]`. |

**Row 2 — the slice axis (secondary; amber-outlined, smaller).**

| Control | Type | Source | Detail |
|---|---|---|---|
| Филиал | Multiselect + search | `tenant.locations.display_name` | Default all. **Hidden entirely for a single-location tenant** — the pilot is single-location and an always-"Все" control is noise. |
| Канал | Multiselect, grouped | `tenant.sales_channels.display_name`, grouped by `system_type` | Group headers `<option disabled>` as separators (Togora §2b). Archived channels appear only if the period contains orders on them. |
| Тип получения | Segmented | `ordering.orders.fulfillment_mode` | `Все · Доставка · Самовывоз · В зале`. |
| Юрлицо | Dropdown | ADR 0038 legal-entity assignment; `fact_order.legal_entity_id` | **Rendered only when the tenant has more than one.** On money views it is not optional: with two entities and "Все" selected the view renders per-entity sub-totals and no combined figure. |
| Тип оплаты | Multiselect | `fact_order_tender.payment_method_code` — *not built* | Renders **locked** with a lock glyph and the tooltip "Появится вместе с платежами (ADR 0013/0046)". Locked, not hidden: a manager who cannot find the payment filter assumes it exists somewhere else. |

**Counts live inside the controls** where cardinality allows, computed *before*
the filter is applied so they do not collapse as the selection narrows
(Togora §2b). A control in a non-default state gets a 2px border in its axis
colour so "this view is filtered" is visible without reading the values.

**Persistence:** every filter is a query param. Filters survive drill-down and
browser-back (Togora §2a). The bar's state is shared across all 7.x views — a
manager who set "Чиланзар, доставка, вчера" on the overview keeps it when they
open the SLA report. Switching view never resets the slice axis; it *may* clamp
the period axis (e.g. ABC forces ≥28 days) with a one-line inline notice saying
it did and why.

### 1.2 Metric provenance — the "?" that every number carries

Every tile, column header and chart axis carries a hairline `?` affordance
opening a 420px side panel (never a tooltip — a tooltip is not keyboard
reachable, Togora §2f):

```
Средний чек                                     average_check.v1
Определение   Валовая выручка ÷ количество завершённых заказов
Входит        только COMPLETED
Не входит     отменённые, отклонённые, истёкшие
Возвраты      уменьшают revenue.net, не средний чек
Валюта        UZS, целые сумы, округление вниз
Опердень      00:00 Asia/Tashkent
Данные на     22.08.2026 14:07 · закрыто до 21.08
Статус        ⚠ Предварительное определение — не подписано финансами
```

That last line is required by ADR 0043: metric semantics ship as version 1 and
are marked provisional until finance signs them. **A provisional metric renders
its number with an amber left rule.** Delever ships LTV and RFM with no stated
definition at all; the parity matrix names that as a credibility problem, and
this panel is the answer.

### 1.3 States

Applies to every view.

| State | Rendering |
|---|---|
| **Loading** | Skeleton preserving the table header and the tile frame — never a spinner over an empty page, so column widths do not jump. Tiles resolve independently of tables; one slow chart does not block the strip. |
| **Empty (no data in period)** | Table keeps its header and frame; a single full-width row: `Нет данных за выбранный период` plus a `Расширить до 7 дней` action (Togora §2p). Never a blank card. |
| **Empty (not yet built)** | The view renders its real chrome greyed, with one line naming the blocking ADR: `Отчёты по курьерам появятся вместе с ADR 0042 (курьерская компенсация и смены).` A missing report must be legibly *unbuilt*, not apparently *broken*. |
| **Denied** | Distinguish, per IA 9.1: **`report.read` not granted** → "Нет доступа к отчётам. Запросить у администратора." **Locked by plan** → an inline upsell naming the plan. Never the same message. A `LOCATION`-scoped grant reading a sibling branch gets the denied form, not an empty table. |
| **Error** | Inline band above the content, the request's correlation id in mono, and a `Повторить` button. The previously loaded data stays on screen behind it — a manager mid-comparison must not lose the figures they were reading. |
| **Stale / re-cut pending** | Amber band: `Данные за 21.08 пересчитываются после окна расчётов. Цифры могут измениться.` Shown while a business day is inside its 24-hour settle window. |
| **Divergence detected** | Red band: `Пересчёт за 19.08 разошёлся с сохранённым итогом. Передано в поддержку.` ADR 0043 alerts on divergence rather than overwriting; the manager must be told, because they may have already acted on the earlier number. |
| **Partial period** | Any range whose last day is today renders the tile deltas against the *same elapsed fraction* of the comparison period, and says so: `по 14:07`. Comparing a half day against a full day is the single most common dashboard lie. |

### 1.4 Table conventions, sort, keyboard

- **Sort defaults are per-view and stated below. Operational queues sort by
  severity; commercial tables sort by magnitude; ledgers sort by time.** Never
  "newest first" as a reflex.
- Row severity on three channels at once, with strict precedence
  (Togora §2d): background tint + 4px left border + a caption line under the
  primary cell carrying the *actual reason text*. Normal rows carry a
  transparent 4px border so they stay aligned.
- **Every aggregate count is a link** to the filtered view that produced it
  (Togora §2j), setting the *complete* route — period, slice, and the target
  view's own tab — never a partial one.
- All machine data (ids, money, times, phone, INN) in mono.
- Absent value is `—`, always. Never `0`, never blank. `NULL` and zero are
  different answers and ADR 0043 is explicit that `NULL` is not zero.
- Totals row is pinned to the bottom of the viewport, not the bottom of the
  scroll. A 40-row branch table whose total requires scrolling is a table whose
  total nobody reads.

**Keyboard** (this console is used standing up):

| Key | Action |
|---|---|
| `d` `w` `m` | Period = today / 7 days / month |
| `[` `]` | Shift period back / forward |
| `g` then `o s b c p m` | Jump to Overview / oStanding orders / Branch / Courier / Products / Marketing |
| `/` | Focus the view's search |
| `e` | Open export dialog for the current view with the current filters |
| `?` | Shortcut sheet |
| `Esc` | Close panel/modal; second `Esc` clears the slice axis |

### 1.5 Export — one mechanism, ten call sites

Export is **asynchronous, capability-gated, quota-bounded, audited, and
delivered as a short-lived presigned URL** (ADR 0043). It is never a synchronous
download, because the reports people actually export are the ones large enough
to time out.

The dialog (Togora §2h "Create" ladder, 560px):

1. **Что** — read-only summary of the current view + filters, in words:
   `Отчёт по заказам · 01.08–22.08 · Чиланзар, Юнусабад · доставка`.
2. **Столбцы** — checkbox list, defaulting to the visible columns. Columns
   carrying PII (`Имя клиента`, `Телефон`, `Адрес`) are grouped under a heading
   `Персональные данные` with a warning rule.
3. **Формат** — `Excel (.xlsx)` / `CSV (UTF-8)`. Excel default: this market
   opens exports in Excel and a raw CSV with Cyrillic is a support ticket.
4. Footer: `Отмена` / `Создать выгрузку`.

Rules:
- Requesting a PII column without `customer.pii.export` is **rejected** naming
  the column — never silently narrowed (ADR 0043). The dialog disables those
  checkboxes up front with the reason, so the rejection is not a surprise.
- Every export writes an ADR 0027 `BUSINESS` audit fact with requester, report,
  filter, column set, metric versions and **row count**.
- Exceeding the per-principal daily row cap surfaces `Лимит выгрузок исчерпан
  (12 000 / 50 000 строк)` and offers `Запросить разрешение` (ADR 0027
  approval), not a bigger default.
- The export lands in the **Export centre** (§2.11), and a toast links to it.
  The file link expires; the row stays, so "who exported the customer base last
  Tuesday" is answerable.

---

## 2. The views

### 2.1 — 7.1 Business overview · `/reports` *(tier P)*

**For:** the one screen a manager opens between services to answer "is today
going normally, and if not, where."

**Layout:** a dashboard — a single scroll of four bands, **not tabs**. Delever
splits this across eight tabs; a tab is a filing cabinet and this screen is a
glance. Tabs also destroy the only thing that makes a dashboard useful, which is
that two unrelated numbers are visible at the same moment (cancellations rising
*while* prep time rises is a different story from either alone).

**Default period is `Сегодня`, not month-to-date.** Delever defaults to
month-to-date; at 15:00 on a Tuesday nobody is asking about the 4th.

**Band A — five tiles.** Each tile: metric name, big mono number, a delta chip,
a 24-cell hour sparkline, and a `?`. **The delta compares against the same
weekday last week, never against yesterday** — Saturday against Friday is noise
dressed as a trend. Every tile is a link.

| Tile | Metric id | Source | Links to |
|---|---|---|---|
| Выручка | `revenue.gross.v1` | `fact_order.gross_revenue_som` — *not built* | 7.2 daily report |
| Заказы | `orders.count.v1` | `fact_order` count where terminal `COMPLETED` | 7.2 order log |
| Средний чек | `average_check.v1` | derived per registry | 7.2 order log |
| Отмены | `orders.cancelled.v1` | `fact_order` where `terminal_status = CANCELLED` | cancellation panel (below) |
| Опоздания | `orders.late.v1` — *definition open, see §7* | `fact_order.seconds_total` vs promise | 7.2 late-orders tab |

The Отмены tile carries a second line: `12 (4,1%) · чаще всего: нет курьера`.
The Опоздания tile carries `7 · медиана +14 мин`. A bare count on either is
useless; the manager's next question is always "why" and "how bad".

**Band B — timing against target.** Three horizontal bullet gauges, not dials.

| Gauge | Actual | Target | Source |
|---|---|---|---|
| Приготовление | median `seconds_to_ready` | `tenant.preparation_bands.duration_minutes` resolved for the band | actual *not built*; target **built** (V0020) |
| Доставка | median delivery seconds | `sla_bucket_set.v1` ≤30 min | *not built — ADR 0042* |
| Самовывоз | median ready→collected | — | *not built — ADR 0041* |

Each gauge prints `среднее 26 мин · в норме 78%` and links to 7.3. **Median, not
mean**, on the face; the mean is available in the `?` panel. One catastrophic
two-hour order moves a mean and misrepresents the shift.

**Band C — mix.** Three compact charts sharing one legend:
- **Каналы** — horizontal bars, share of *count*, with a toggle to share of
  *revenue*. Bars, not Delever's pie: five-plus channels in a pie is unreadable
  and pies cannot be compared week to week.
- **Тип получения** — a single stacked bar, delivery / pickup / dine-in.
- **Оплата** — rendered as a locked placeholder card naming ADR 0013/0046
  rather than omitted, for the reason in §1.1.

**Band D — the funnel and the branch table.**
- **Воронка по финальному статусу**: `RECEIVED → CONFIRMED → PREPARING → READY
  → FULFILLING → COMPLETED`, with `REJECTED / EXPIRED / CANCELLED /
  PAYMENT_FAILED` as labelled drop-offs beside the appropriate stage. Statuses
  are the code-owned twelve from `ordering.orders.ck_order_status` — this is
  never a tenant vocabulary. Clicking a drop-off opens the **cancellation
  panel** (peek modal, Togora §2i): reason, count, share, and **what it cost** —
  `stock_disposition` and `liability_party` from `ordering.order_outcomes`
  (ADR 0039). A cancellation before production and four cooked dishes binned at
  the pass are not the same event, and Delever reports them identically.
- **Branch table** — rendered only for multi-location tenants; columns as 7.3's
  leaderboard, top five rows, `Все филиалы →`.

**Actions:** `Экспорт` (§1.5), `Открыть живую доску →` (0.1), period/slice via
the bar. No destructive actions exist on this screen; nothing here needs
confirmation.

**Sort:** the branch table sorts by revenue descending. Not alphabetical — a
manager scanning a leaderboard is looking for the top and the bottom.

**Freshness:** a right-aligned `Данные на 14:07 · закрыто по 21.08` line at the
top of the scroll, always visible. ADR 0023 forbids shipping a report that
cannot state its freshness.

---

### 2.2 — 7.2 Order reports · `/reports/orders` *(tier P)*

**For:** the per-order evidence behind every number on the overview — the screen
you open when a figure looks wrong, or when a customer disputes something.

**Layout:** master (tab strip) + table + export. Six tabs, because these really
are six different tables over the same rows, and a single table with 30 columns
serves none of them. Tab labels carry live counts.

| Tab | What it is | Sort |
|---|---|---|
| **Этапы** | Per-stage duration audit (Delever `Все отчёты`) | `Общее время` desc — the slow ones are the point |
| **Заказы** | Commercial / CRM log (Delever `Все отчёты по заказам`) | `Дата` desc |
| **Посуточно** | Day-grain operating table (Delever `Общий отчёт`) | `Дата` desc |
| **Сводка** | Roll-up by order type and by branch×channel (Delever `Ежедневный отчёт 1 и 2`) | fixed |
| **Опоздания** | Late orders | `Минуты задержки` desc |
| **Агрегаторы** | Aggregator order log + liveness | `Дата` desc |

#### Tab «Этапы» — per-stage durations

| Column | Type | Source |
|---|---|---|
| ID заказа | mono link | `ordering.orders.public_order_number` — **built** |
| Филиал | text | `tenant.locations.display_name` — **built** |
| Канал | icon + text | `orders.channel_code_snapshot` — **built** |
| Принят оператором | duration | `RECEIVED→CONFIRMED` from `ordering.order_state_history` — **built**, derivable |
| Принят филиалом | duration | `order_state_history` `CONFIRMED→PREPARING` — **built**, derivable |
| Приготовлен | duration | `PREPARING→READY`; true kitchen fire→ready is `kitchen.tickets.started_at/ready_at` — *not built, ADR 0041* |
| Курьер в пути | duration | *not built — ADR 0042* |
| Общее время | duration | `created_at → closed_at` — **built** |

**Severity rendering:** rows whose `Общее время` exceeds the resolved
`preparation_bands` promise get an amber left border and the caption
`+14 мин к обещанному`; rows over 60 minutes get red and `критическая
задержка`. Incident outranks warning and suppresses its caption (Togora §2d).

Clicking a row opens a peek modal (Togora §2i): the two parallel pipelines —
**kitchen clock and logistics clock side by side, as two independent dot strips
with the timestamp printed under every completed stage as visible text**
(Togora §2f). This is the single most valuable widget in the section: it makes
visible that production and delivery are separate clocks that can disagree,
which is precisely the difference between "the kitchen was late" and "the
courier was late". A single linear status bar destroys that distinction and
Delever ships a single linear list of timestamps. The modal's one primary button
is `Открыть заказ →` (1.2 order detail).

#### Tab «Заказы» — commercial log

Columns: `ID заказа` · `Дата` · `Филиал` · `Канал` · `Тип получения` ·
`Предзаказ (Да/Нет)` · `Оператор` · `Курьер` + `Тип курьера` · `Статус` ·
`Сумма заказа` · `Сумма доставки` · `Скидка` · `Итого` · `Имя клиента` ·
`Телефон`.

Sources: order money from `ordering.orders.subtotal_minor / fee_minor /
discount_minor / total_minor` (**built**); `Оператор` from
`order_state_history.actor_id` where `trigger = OPERATIONS_ACTION` (**built**,
weakly — a real `operator_principal_id` is ADR 0039/0043);
`Курьер`, `Тип курьера` *not built — ADR 0042*; customer name/phone from
`ordering.order_customer_snapshots.display_name_encrypted / contact_encrypted`
(**built**, ADR 0029 envelope-encrypted).

**PII rule:** `Имя клиента` and `Телефон` are **masked by default**
(`+998 90 *** ** 12`) with a per-row reveal that writes an audit fact. They are
never in the default export column set. Delever prints the whole base in the
clear on a page a marketer can leave open on a shared terminal.

Search: one box searching `public_order_number` **and per-provider external
ids** (IA 1.1) — a customer quoting a Yandex order number must be findable.

#### Tab «Посуточно» — the day-grain operating table

One row per business day. This is the table finance actually reads.

| Column | Metric | Source |
|---|---|---|
| Дата | — | `fact_order.business_date` — *not built* |
| По общей цене | `revenue.gross.v1` | *not built* |
| По цене продукта | net of delivery fee | `subtotal_minor` sum — **built** |
| Скидки | — | `discount_minor` sum — **built** |
| Возвраты | `revenue.refunded.v1`, on the *refund's* date | *not built — ADR 0013* |
| Отменённые заказы | count + share | **built** from `status` |
| Повторные заказы | count | needs `is_first_order` — *not built* |
| Кол-во / Сумма доставки | pair | **built** (`fulfillment_mode`) |
| Кол-во / Сумма самовывоза | pair | **built** |
| Кол-во / Сумма агрегаторов | pair | **built** (`channel_code_snapshot`) |
| Средний чек | `average_check.v1` | *not built* |

Delever additionally ships `Отменено но продано` and `Бесплатная доставка` and
`Кешбэк`. **We skip `Отменено но продано`**: it is a column that exists to paper
over a data-entry correction, and ADR 0039's `order_outcomes` with an explicit
`stock_disposition` describes the same events truthfully. Cashback returns with
ADR 0046.

Totals row pinned. Weekend rows get a hairline tint — a manager comparing days
needs the week's shape without reading dates.

#### Tab «Сводка» — the two roll-ups

**Сводка 1**: by `Тип заказа` — `Кол-во заказов`, `Сумма`, `Сумма с учётом
доставки`, `Итого`. Small, four rows, correct.

**Сводка 2**: Delever's version is a single table with four horizontal
mega-blocks (Доставка / Самовывоз / Миллениум / Итого) × five channels ×
three measures per branch — roughly forty columns of horizontal scroll.
**This is bad design and we do not copy it.** Ours is a **pivot**: rows =
branch, columns = channel, with a segmented control above choosing the measure
(`Кол-во · Сумма · Средний чек`) and a second control choosing the split
(`Тип получения: все / доставка / самовывоз / в зале`). Same information,
one screen, no horizontal scroll, and the manager can actually compare two cells
because they are adjacent.

#### Tab «Опоздания»

Columns: `ID заказа` · `Минуты задержки` · `Филиал` · `Тип заказа` · `Канал` ·
`Цена заказа` · `Продукты` · `Дата создания` · `Причина` (where an outcome was
recorded).

Sorted by `Минуты задержки` **descending** — severity, not time. The whole point
of the queue is the worst case. Rows above 30 minutes late are red, 10–30 amber.
A one-line summary sits above the table: `За период: 47 опозданий, медиана
+12 мин, худший +71 мин.`

**`Минуты задержки` is currently uncomputable** — see §7.

#### Tab «Агрегаторы»

Columns: `ID заказа` · `Агрегатор` · `Внешний ID` · `Филиал` · `Сумма` ·
`Комиссия` · `Статус` · `Дата`.

`Комиссия` renders `—` and not `0`, always, until ADR 0040 supplies it —
ADR 0043 is explicit that `NULL` is not zero here and that
`revenue.net.v1` does not subtract it.

Above the table, the **aggregator liveness matrix**: branches × aggregator
channels, each cell the timestamp of the last successful inbound order.
Cells older than 4 hours during service hours go amber, older than 12 red. This
is a two-second read that catches a broken integration before a customer does,
and it is the one genuinely operational thing on an otherwise historical screen.
Clicking a cell jumps to 10.8 health & errors.

**Actions (all tabs):** `Экспорт` (§1.5) · per-row `Открыть заказ →` ·
per-row reveal-PII (audited). No bulk actions: this is a reading surface, and
selection with nothing to select for is clutter.

---

### 2.3 — 7.3 Branch & SLA reports · `/reports/branches` *(tier 2)*

**For:** which branch is slow, and whether it is slow at the pass or at the door.

**Layout:** two stacked tables under the shared bar. Hidden from the navigation
for single-location tenants — nothing on it is meaningful for one branch except
the bucket distribution, which is folded into the overview's Band B `?` panel in
that case.

**Table A — branch leaderboard.** Columns: `Филиал` · `Заказы` · `Доставка /
Самовывоз / Агрегаторы` (counts) · `Выручка` · `Средний чек` · `Ср. время
приготовления` · `Ср. время доставки` · `Отмены %` · `В норме %`.
Source: `reporting.agg_branch_day` — *not built*.
Sort: `Выручка` desc, with a persistent secondary sort control.

**Table B — SLA time buckets.** Rows = branch, columns = the **platform-fixed,
versioned** six from `sla_bucket_set.v1`: `≤30 · 30–35 · 35–40 · 40–50 · 50–60 ·
>60`, each cell showing count and share, plus `Всего` and `Медиана`.
Source: `reporting.agg_sla_bucket_day` — *not built*.

Two corrections to Delever here, both deliberate:

- **Delever's own documentation lists the branch buckets as "under 30, under 35,
  30–40, 40–50, 35–60, over 60"** — overlapping and non-exhaustive, which means
  the percentages cannot sum and two adjacent columns double-count the same
  order. We use ADR 0043's non-overlapping half-open set and store raw elapsed
  seconds on the fact so a `v2` set can be re-cut retroactively.
- **The IA (§7.3) asks for tenant-configurable boundaries; ADR 0043 fixes them
  in code. ADR 0043 is right and the IA is stale.** A tenant editing a bucket
  rewrites the meaning of every chart already drawn, including last quarter's,
  with nothing recording that it happened. The compensating affordance is that
  raw seconds are stored, so a tenant whose promise is 45 minutes gets a `v2`
  bucket set in a release rather than a settings toggle — and gets its history
  re-cut correctly rather than reinterpreted.

Cells are tinted on a single green→red ramp keyed to the bucket, not to the
value, so a manager reads the shape of a row without reading numbers. The bucket
set version is printed under the table: `Интервалы: sla_bucket_set.v1`.

---

### 2.4 — 7.4 Courier reports · `/reports/couriers` *(tier 2)*

**For:** who delivers fast, who delivers far, and whether the third-party
delivery invoice is correct.

**Entirely unbuilt.** Renders the unbuilt state (§1.3) naming ADR 0042 until
courier shifts, assignments and earnings exist. Specified now so it is not
designed in a hurry later.

**Layout:** three tabs — `Эффективность` · `По времени` · `Внешняя доставка`.

**Tab «Эффективность»** — one row per courier: `Имя` · `Тип курьера` ·
`Кол-во заказов` · `Сумма всех заказов` · `Мин. / Ср. / Макс. расстояние` ·
`Общий пробег, км` · `Ср. время доставки` · `Макс. время доставки` ·
`Вовремя %`. Source `fulfillment.courier_assignment_earnings` +
`reporting.fact_delivery` — *not built, ADR 0042/0043*.
Sort: `Вовремя %` ascending. **The worst performer is the reason the screen
exists**; sorting by order count puts the busiest courier on top, which nobody
needed to look up.

**Tab «По времени»** — the same six fixed buckets as 7.3, scoped `COURIER`
(`agg_sla_bucket_day.scope_kind`). Adds a `Смена` column so an off-shift
delivery is visibly an exception rather than silently averaged in.

**Tab «Внешняя доставка»** — the reconciliation table, and the most valuable
report in this group because it is the only one that finds money.
Columns: `Служба` · `ID заказа` · `Внешний ID` · `Сумма заказа` ·
`Сумма доставки` (charged to the customer) · `Стоимость службы` (billed by the
provider) · `Разница` · `Статус сверки`.
`Разница` = `delivery_cost_variance.v1` = `provider_billed_som −
fee_charged_som`. `Статус сверки` ∈ `PENDING · MATCHED · VARIANCE · UNBILLED`
from `reporting.fact_delivery.reconciliation_status`.
**`UNBILLED` rows are excluded from the variance total and counted separately**,
per ADR 0043 — an unbilled order reading as a zero-variance match is exactly the
bug this metric exists to prevent, and Delever's version has no status column at
all, only a raw `Сумма яндекса`.
Sort: `|Разница|` descending, `UNBILLED` pinned above.
Bulk action: select rows → `Отметить как сверенные` (writes an audit fact,
requires confirmation naming the count and the total som affected). Offered only
when **every** selected row is `VARIANCE` or `MATCHED`; a selection containing an
`UNBILLED` row disables it with the reason stated on the button.

**Explicitly not here:** `К оплате`, штрафы, бонусы, часы. Those are 8.5.
Delever ships the payroll table inside the courier report, and a report that
also disburses money is a report nobody can safely give a shift supervisor.

---

### 2.5 — 7.5 Staff reports · `/reports/staff` *(tier 2)*

**For:** which operator sells, and how long an order sits in a human's hands.

**Layout:** two tabs, `Общие сведения` and `По продуктам`.

**Tab «Общие сведения»** — one row per operator: `Оператор` ·
`Кол-во заказов` · `Общая сумма` · `Средний чек` · `Ср. время обработки` ·
per-channel `Кол-во / Сумма / Средний чек` for `Админ · Бот · Приложение ·
Сайт` · `Доставка / Самовывоз` split.
Source: `fact_order.operator_principal_id` — *not built*; today only
`order_state_history.actor_id` exists and is a string, not a principal.

**Machine principals appear as rows** (IA 7.5) — the Telegram bot, the website,
each aggregator — clearly typed with a distinct glyph. Delever lets automated
gateways appear in an operator leaderboard unmarked, which produces the reliable
comedy of "the bot" winning employee of the month. Marking them is cheaper than
excluding them, because the manager genuinely wants the comparison.

`Ср. время обработки` is the metric that matters and is the one Delever buries;
it goes third from the left, not last.

**Tab «По продуктам»** — operator × product upsell: `Общее количество` ·
`Глубина чека` (average portions of that item per receipt containing it) ·
`Общая сумма`. Filter by product. This is the only report that tells a
call-centre manager whether coaching worked.

**Deliberately skipped: telephony KPIs** (calls handled, answer speed,
conversion). Delever's own documented instance shows `Нет данных` for this
report, which suggests it is not populated in practice. It returns only with
IA 1.6 and a telephony provider, and until then the tab is not rendered at all —
an empty tab teaches people the screen is broken.

---

### 2.6 — 7.6 Customer analytics · `/reports/customers` *(tier 2)*

**For:** is the base growing, and are people coming back.

**Layout:** dashboard, monthly-shaped. Period defaults to `Месяц`, and the view
clamps ranges under 28 days with a stated notice — a one-day retention cohort is
not a number.

**Band A — tiles, each with a published formula in its `?` panel.** ADR 0043's
open input names exactly this: Delever presents LTV and RFM with no stated
period baseline and no rule on cancelled or refunded orders, and two dashboards
disagreeing about average check is unrecoverable.

| Tile | Definition as shipped |
|---|---|
| Новые клиенты | Unique customers whose *first completed order* falls in the period. Registration without an order does not count. |
| Глубина чека | Mean `item_count` per completed order. |
| Частота заказов | Completed orders ÷ distinct ordering customers, in the period. |
| Ценность клиента | `average_check.v1 × Частота заказов`. |
| LTV | Mean gross revenue per customer over their whole lifetime to date, **not** a projection. A projected LTV needs a churn model we do not have and will not pretend to. |
| Повторные, % | Share of completed orders placed by customers with a prior completed order. |

All six exclude cancelled, rejected and expired orders; refunds reduce revenue
on the refund's date. This is stated on the screen, not only in the panel.

**Band B — acquisition.** A bar chart of customers by registration source
(`customer.principal_links` / brand profile origin — partially **built**), plus
a dual-axis line: daily registrations against daily first-orders. The gap
between those two lines is the only acquisition diagnostic a restaurant needs.

**Band C — retention.** Three widgets:
- Distribution of customers by lifetime order count (1, 2, 3 … 10+), bar.
- New vs returning revenue, stacked area by week.
- Cohort retention grid: rows = first-order month, columns = months since,
  cells = share returning. Read as a triangle, tinted on one ramp.

**Band D — the product funnel.** `Входы → Зарегистрировано → Добавил в корзину
→ Заказал`, by day. Source `reporting.fact_behaviour` from the `analytics.events`
topic — *not built, and the single item in this whole spec that cannot be
backfilled.* ADR 0043 ships behavioural events first, before any consumer, for
exactly this reason. Until they exist the band renders the unbuilt state with
the line `Данные воронки собираются с момента включения телеметрии — их нельзя
восстановить задним числом.`

**RFM lives in 5.3, not here.** RFM is a *segment builder* whose output is an
audience handed to a campaign; putting a builder inside a read-only analytics
screen is how Delever ends up with two segment tools (V1 `Сегменты` and V2
`Segmentation`) that disagree. This screen links to it: `Построить сегмент →`.

---

### 2.7 — 7.7 Product analytics · `/reports/products` *(tier 2)*

**For:** what to keep, what to promote, and what to take off the menu.

**Layout:** master table + a classification header. Period defaults to the
previous whole month; the view refuses ranges under 28 days for ABC/XYZ, because
a Pareto over four days is an artefact of one large party order.

**Tab «Продажи»** — the product report. One row per variant:
`Продукт` · `Категория` · `Кол-во (доставка)` · `Сумма (доставка)` ·
`Кол-во (самовывоз)` · `Сумма (самовывоз)` · `Кол-во (итого)` ·
`Сумма (итого)` · `Доля выручки, %`.
Source: `reporting.fact_order_line` — *not built*; derivable today from
`ordering.order_lines` (`quantity`, `final_amount_minor`,
`product_name_snapshot`) joined to `catalog.category_products` — **built**.
Sort: `Сумма (итого)` desc.

**Tab «ABC»** — classification. Columns: `Продукт` · `Выручка` ·
`Доля, %` · `Накопленная доля, %` · `Класс`.
**The cumulative column is the point and Delever does not have it.** Delever's
ABC page states the 80/15/5 split in prose and shows a class letter; a manager
who disputes a product being class C has nothing to look at. Ours stores the run
and its parameters (`reporting.classification_run.parameters_json`) and prints
them under the table: `Окно 01.07–31.07 · пороги 80/95% · metric revenue.gross.v1
· рассчитано 01.08 03:14`. An ABC list with no recorded thresholds is an
opinion.

**Tab «XYZ»** — `Продукт` · `Кол-во` · `Сумма` · `Среднее` ·
`Стандартное отклонение` · `Коэффициент вариации` · `Класс`.

**The ABC×XYZ matrix is a 3×3 filter, not a ninth table.** Nine clickable cells
above the product table, each showing its member count; clicking filters the
table. `AX` (high value, stable) and `CZ` (low value, erratic) are the two cells
anyone acts on, and a matrix that is a filter puts the action one click from the
insight. Delever renders them as separate reports.

**Kiosk sales report: skipped.** Present in Delever's UI, shows `Нет данных` in
the documented instance, and kiosk is IA 10.5 tier 2. Kiosk orders appear here
as a channel like any other; a separate report for one channel is a precedent
that ends in eleven reports.

**Row severity:** products currently on stop (`catalog.location_offerings` /
stop list) get a 3px amber left border and `СТОП` beside the name (Togora §2d,
already the prototype's convention). A high-revenue item on stop is the single
most actionable row in this table and Delever does not mark it.

---

### 2.8 — 7.8 Demand forecast · `/reports/forecast` *(tier 3)*

**For:** how many portions to prep tomorrow.

**Layout:** chart + table, per branch, per day, drillable to product.

- Chart: `План` vs `Факт` by hour across the **operating day, which may cross
  midnight** (`reporting.business_day_start`). The 09:00→09:00 case is the
  reason this is a stated field and not a `date_trunc`.
- Table: `Продукт` · `Прогноз, шт` · `Факт, шт` · `Ошибка, %` ·
  `Модель` · `Праздник`.
  Source `reporting.fact_forecast` — *not built*.

`absolute_percentage_error` is a **stored fact written back after the day
closes** (ADR 0043), and it is shown on the face of the report, not hidden.
The model is seasonal-naive with a day-of-week/hour profile and a holiday factor
— deliberately not machine learning, deliberately explainable to a kitchen
manager who asks why it wants 40 portions. Delever names its module after
ClickHouse and explains nothing.

Actions: `Пересчитать` (rate-limited under ADR 0033, confirmation naming the
window), `Праздники →` (10.10 calendar).

**Actual (wave 48, 2026-09-05 owner decision) is a historical average, not
this chart.** `План`/`Факт`, `Прогноз, шт`, `Ошибка, %`, `Модель` and
`Праздник` all describe the seasonal-naive model above, which remains **not
built** — no `forecast_run`, no `fact_forecast`, no holiday factor. What
shipped instead: `GET .../reporting/demand-history` — **built**, straight off
`fact_order` — answers "how many orders actually happened in this hour, on
this weekday's most recent occurrences", per branch, with the sample size
always stated and no number published below three qualifying dates. No
per-product breakdown (`fact_order_line` has no hour to bucket by) and no
`Пересчитать`/`Праздники` actions — there is no run to recompute and no model
for a holiday to adjust. See ADR 0043's implementation status for the full
accounting and `demand-forecast-page.ts` for the screen itself.

---

### 2.9 — 7.9 Marketing reports · `/reports/marketing` *(tier 2)*

**For:** did the promo pay for itself.

Three tabs.

**«Промокоды — сводка»**: `Промокод` · `Кампания` · `Погашений` ·
`Уникальных клиентов` · `Сумма скидки` · `Выручка по заказам с кодом` ·
`Средний чек с кодом` · `Средний чек без кода (тот же период)`.
That last column is the one that answers the question and neither Delever nor
the legacy dashboard has it. A redemption count without a counterfactual is a
vanity metric.
Source: `reporting.fact_promotion_redemption` — *not built*.

**«Промокоды — погашения»**: per-redemption log — `Дата` · `Промокод` ·
`Клиент` (masked) · `Канал` · `ID заказа` · `Сумма скидки`. Sort by date desc.

**«Кампании»**: per-campaign delivery and read counts, SMS/push/Telegram.
Source: ADR 0044 — *not built*.

Delever also ships `Отчёт по комментариям`. That belongs to 5.4 Reviews as a
service-recovery board, not to a marketing report — a comment needs an action
and an owner, and a read-only report gives it neither.

---

### 2.10 — 7.10 Geography · `/reports/geography` *(tier 3)*

**For:** where the orders are, and where the zone boundary is wrong.

Order-density heatmap over the delivery zones (3.6), today's orders as pins,
histograms of delivery duration and delivery distance, and a day-of-week × hour
volume grid.

The heatmap earns its place only when overlaid on the **actual zone polygons** —
density alone is a picture, density against the boundary you drew is a decision
(extend the zone, split the branch catchment, re-price the tariff). Delever's is
a bare heatmap over Tashkent.

The day × hour grid follows the Togora heat-strip technique (§2l): discretised
cells, one colour ramp with a legend, and a tooltip rendered as an
absolutely-positioned sibling *outside* the `overflow:hidden` strip listing the
specific orders in that cell.

Source: `ordering` addresses are structured and geocoded (V0021, **built**);
the aggregation is *not built*.

---

### 2.11 — Export centre · `/reports/exports` *(tier P, ships with the first export)*

**For:** "where is the file I asked for", and "who took the customer base".

**Layout:** list. Not a modal — an asynchronous job with a lifecycle needs a
place to live, and a toast that has scrolled away is not that place.

| Column | Source |
|---|---|
| Отчёт | `reporting.report_exports.report_id` |
| Фильтры | `filter_json`, rendered in words |
| Столбцы | `column_set`; a PII badge when it contains one |
| Строк | `row_count` |
| Запросил | `requested_by` |
| Создан / Истекает | timestamps, `expires_at` |
| Статус | `QUEUED · RUNNING · READY · EXPIRED · FAILED` |

Sort: created desc. Actions: `Скачать` (only while `READY`; the presigned URL is
short-lived and the button disappears rather than 403s), `Повторить`,
`Отменить` (while `QUEUED`, confirmation not required — cancelling a queued job
is reversible by repeating it).

A quota strip at the top: `Выгружено сегодня: 12 400 из 50 000 строк`.

**Every row here is also an ADR 0027 audit fact.** The row count is the point:
ADR 0029 names the difference between viewing one customer and exporting fifty
thousand as the case audit exists to catch.

---

### 2.12 — Metric dictionary · `/reports/metrics` *(tier 2)*

**For:** settling an argument about a number.

A searchable list of `reporting.metric_definitions`: `metric_id` · `Версия` ·
`Определение` · `Что входит / не входит` · `Гранулярность` · `Источник` ·
`Округление` · `Подписано финансами` · `Действует с`.

Three clicks deep, never on the main path, and worth building anyway: it is the
screen a manager is sent to when they say "your average check is wrong", and its
existence is what makes that conversation end. Delever has no such surface,
which is why the parity matrix records its metric definitions as unstated.

Filter: a toggle `Только предварительные` surfacing every unsigned definition —
the checklist ADR 0043 leaves finance.

---

## 3. Which of these matter daily, and which are monthly

Three or four screens carry the section. The rest are periodic and should be
navigable but not prominent.

**Daily, on the main path** — one click from anywhere:
1. **7.1 Business overview**, period `Сегодня`. The only screen a manager opens
   without a reason.
2. **7.2 «Опоздания»**, and **7.2 «Этапы»** behind it. Yesterday's late orders
   are today's staffing decision.
3. **7.2 «Агрегаторы»** liveness matrix. A dead aggregator channel loses money
   silently and is invisible everywhere else.

**Weekly:** 7.3 branch and SLA · 7.4 courier efficiency · 7.5 operator general.

**Monthly, deliberately three clicks away:** 7.7 ABC/XYZ · 7.6 cohorts and LTV ·
7.9 promo effectiveness · 7.2 «Сводка» · 7.10 geography. None of these change
meaningfully inside a week, and putting a monthly report on a daily screen
trains people to ignore the screen.

**Per-shift, but not here:** the live board (0.1). It belongs to ADR 0045 and to
a wallboard shell, not to a reporting workspace with a date picker.

---

## 4. Cost: what is cheap on PostgreSQL, and what needs ADR 0043 first

HorecaOS is PostgreSQL-only where Delever runs a columnar store. ADR 0043 is
**Accepted but not started**: no `reporting` fact tables exist beyond
`reporting.tenant_summaries`. That makes the question concrete — what can be
served from `ordering.*` today at pilot scale (one location), and what cannot be
served at all until the star schema and the close job exist.

**Cheap today** — a single-location day is hundreds of orders; these are indexed
scans over `ordering.orders` and `ordering.order_lines` and are safe to run
against the primary with a statement timeout:

- 7.1 tiles and mix charts for a range of ≤31 days.
- 7.2 «Заказы» and «Этапы» — these are *filtered lists*, one row per order. The
  order board already runs this shape.
- 7.2 «Посуточно» for ≤92 days.
- 7.2 «Агрегаторы» log and the liveness matrix (the matrix is a
  `max(created_at) group by (location, channel)` over a small set).
- 7.7 «Продажи» product report for ≤31 days.
- 5.1 customer header counters.

Each of these still goes through the metric registry and the typed
`POST /queries` endpoint — cheap does not mean "let the screen write SQL". The
registry is what stops the overview and the export disagreeing, and it costs
nothing to put in place before the facts exist.

**Needs the ADR 0043 star schema, the close job, and `business_date` on the
fact** — these are the per-day × per-branch × per-channel matrices and the
window functions, and they are the ones that become a checkout-latency incident
if run live against OLTP tables indexed for point writes:

- 7.2 «Сводка 2» (branch × channel × measure pivot).
- 7.3 both tables — SLA buckets require a self-join per order over
  `order_state_history` and are quadratic in the naive form.
- 7.4 all three tabs (also blocked on ADR 0042 data existing at all).
- 7.6 cohorts, LTV, new-vs-returning, repeat distribution — every one of these
  needs `is_first_order` precomputed; deriving it live is a full-history scan
  per customer per query.
- 7.7 ABC and XYZ — cumulative Pareto shares and standard deviation over a
  month of lines, per tenant, per run.
- 7.8 forecasting.
- 7.10 heatmaps and the day×hour grid.
- Any range longer than a quarter, for anything.

**Blocked on data that does not exist at all, not on the query engine:**
7.6 Band D (funnel — needs `analytics.events`), payment mix (ADR 0013/0046),
courier anything (ADR 0042), true kitchen timings (ADR 0041), aggregator
commission (ADR 0040), cancellation cost (ADR 0039).

**The sequencing this implies**, matching ADR 0043's rollout: emit behavioural
events now, because that is the only item that cannot be done later; build the
metric registry and typed query API before any screen, and back the cheap
reports with it against the OLTP tables; then `fact_order` /
`fact_order_line` / the close job, reconciled against `ordering` before anything
reads them; then everything in the second list. Exports last, with capabilities,
quotas and audit in place before the first file is produced.

Two operational guards are not optional given one cluster serves both workloads:
a separate read-only role with `SELECT` on `reporting` and nothing else, and a
statement timeout on the analytics role. ADR 0043's negative consequence — a
pathological analytics query becoming a checkout incident — is real from the
first report, not from the first fact table.

---

## 5. Delever: match, beat, skip

### Match

- The eight-tab dashboard's **content**, reorganised into one overview plus a
  reports index. Every KPI, the funnel, the source and payment mix, the branch
  and operator and courier leaderboards.
- All fourteen named reports, mapped: Общий отчёт → 7.2 «Посуточно» ·
  Ежедневный 1/2 → 7.2 «Сводка» · Отчёт по заказам + Все отчёты → 7.2 «Этапы» и
  «Заказы» · По продуктам → 7.7 «Продажи» · По внешней доставке → 7.4 ·
  По опоздавшим → 7.2 «Опоздания» · Общий аггрегатор → 7.2 «Агрегаторы» ·
  По филиалам + по времени → 7.3 · По курьерам (сумма доставки, по времени) →
  7.4 · По операторам (общие, по продуктам) → 7.5 · По промокодам → 7.9 ·
  ABC/XYZ → 7.7.
- The **six SLA buckets** as a concept, and the green/amber/red reading of them.
- Excel export on every report. This market opens exports in Excel.
- The **external-delivery cost reconciliation** table. It is Delever's best
  report and the only one that reliably finds money.
- The order-stage timing report. It is what a restaurant argues about.

### Beat

| Where | What we do instead | Why |
|---|---|---|
| Metric definitions | A code-owned registry, versioned, with a per-number provenance panel and a signed-by-finance flag | Delever's LTV, RFM and average check have no stated definition. Two surfaces disagreeing is unrecoverable — the merchant starts checking every number by hand. |
| SLA buckets | Non-overlapping, exhaustive, platform-fixed, versioned, raw seconds stored | Delever's documented branch buckets overlap (`до 35`, `30-40`, `35-60`), so the percentages cannot sum. |
| Cancellations | Reason **plus** `stock_disposition` and `liability_party` | Delever reports a release-before-production and four binned dishes identically. |
| Сводка 2 | A pivot with measure and split selectors | Delever's forty-column mega-block table cannot be read or compared. |
| ABC | Cumulative share column, stored thresholds, stored run parameters | An ABC class with no recorded threshold is an opinion, not a finding. |
| Export | Async, capability-gated, quota-bounded, audited with row count, presigned and expiring | Delever's `Скачать` prints the customer base to a file with no record that it happened. |
| Promo reports | An average-check-without-code counterfactual column | A redemption count alone cannot tell you whether the promo paid. |
| Product report | Stop-list state rendered as row severity | A high-revenue item on stop is the most actionable row on the screen. |
| Late orders | Sorted by minutes late descending, with a median/worst summary line | Delever sorts by time; the queue exists for the worst case. |
| PII | Name and phone masked by default with an audited reveal | Delever prints them in the clear on a shared terminal. |
| Aggregator liveness | On the main path, with staleness thresholds | Delever buries it; a dead channel is silent revenue loss. |
| Legal entity | Money reports group by entity, never by tenant | A multi-entity tenant total reconciles to neither filing (ADR 0038). |

### Skip, with the reason

| Skipped | Reason |
|---|---|
| Embedded BI workspace (DataLens/Metabase) | Puts tenant isolation inside a BI tool's row-level permissions instead of ADR 0025 grants, and makes every metric definable twice. ADR 0043 rejects it. **We should be honest that a merchant comparing the two will experience this as a missing feature, because it is one.** |
| Ad-hoc SQL / free query builder | Same reason. `POST /queries` takes metric ids and dimension ids, never an expression. |
| Kiosk sales report | Shows `Нет данных` in Delever's own documented instance; kiosk orders appear as a channel like any other. |
| Operator telephony KPIs (calls, answer speed, conversion) | Also `Нет данных` in Delever's instance; requires the whole telephony category (IA 1.6, tier 3). |
| `Отменено но продано` column | A correction hack. ADR 0039 `order_outcomes` describes the same events truthfully. |
| ClickHouse-branded product forecast | Replaced by ADR 0043's seasonal-naive model with a stored error rate. Explainable beats sophisticated for a kitchen manager. |
| Reviews as a "% of sum of four survey types" chart | The stated metric is not meaningful. Review analytics belongs to 5.4 with a per-dimension mean and a distribution. |
| Courier payroll inside the courier report | 8.5 owns money movements. A report that also pays people cannot be given to a shift supervisor. |
| Report duplication (courier reports under both Дашборд and Персонал) | One report, one place. |

---

## 6. What the legacy dashboard did

**Nothing — and that is the finding.**

`legacy-archive/qoida-dashboard/src/pages/Dashboard.page.tsx` renders an empty
white card. The only statistics implementation in the file is commented out, and
it is a Grafana iframe:

```
// <iframe src="http://172.100.0.6:3000/d-solo/ddfv8fdsqae4ga/statistika-po-zakazam?orgId=1&from=…&to=…&panelId=1" />
```

Three things follow, and they are worth more than any feature list:

1. **The client's staff have never had in-product analytics.** There is no
   muscle memory to preserve and no regression risk in this section — the only
   section of the console where that is true. Everything here is net new, which
   means it should be judged on whether a manager uses it, not on parity.
2. **Analytics was already delegated to an external tool at a hardcoded internal
   IP, per-organisation by query string.** Whoever needed a number got a Grafana
   link. Expect the first request after go-live to be "can I have the Grafana
   back" — the answer is 7.1 plus the export centre, and it needs to be better
   than a Grafana panel on day one, not eventually.
3. **The dispatcher role never saw a dashboard at all:** the page redirects
   `UserRole.DISPATCHER` straight to `/orders/new`. That role split is right and
   we keep it — 7.x is gated on `report.read`, dispatchers do not get it, and
   their home is 3.1, not a chart.

The legacy app also establishes conventions this section inherits: an
`InfiniteTable` component (infinite scroll, not pagination — keep it for the
per-order logs, which are the tables people scroll), a hardcoded five-brand
filter on the order board (a multi-brand tenant is normal here; the brand axis
must be a real dimension, `dim_brand`, not a constant), and client-side-only
permission checks (IA 9.1 already names this as something to fix — every 7.x
capability is server-enforced).

---

## 7. Data the backend does not have yet, named precisely

`ordering`, `catalog`, `pricing`, `inventory`, `customer`, `tenancy` and `media`
are built. The `reporting` schema exists and holds exactly one projection,
`reporting.tenant_summaries` (V0011). **Everything in ADR 0043's physical model
is unbuilt.**

### Blocking, in rough order of how much of this spec they unlock

| Missing | Precisely what | Owning ADR |
|---|---|---|
| The whole star schema | `reporting.dim_*`, `fact_order`, `fact_order_line`, `fact_order_tender`, `fact_delivery`, `fact_promotion_redemption`, `fact_behaviour`, `agg_branch_day`, `agg_sla_bucket_day`, `classification_run/_result`, `forecast_run`, `fact_forecast`, `metric_definitions`, `report_exports` | **0043** |
| `business_date` on an order | It exists **only** on `ordering.order_number_counters.business_date` (V0022 line 376), as a counter key. `ordering.orders` has no business date — every report today must recompute it from `created_at` and `tenant.locations.timezone`, per query. | **0043** (`reporting.business_day_start` via 0030) |
| The metric registry and typed query API | `GET /reporting/metrics`, `POST /reporting/queries`, `GET /reporting/reports/{id}`, `POST /reporting/exports` | **0043** |
| Capabilities | `report.read`, `report.export`, `customer.pii.export`, `forecast.manage`, platform-scoped `metric.manage` — none are in the 0025 registry | **0043** + 0025 |
| **Payment method on an order** | `ordering.orders` has `payment_status_projection` and **no payment method column at all**. `tenant.channel_payment_methods.payment_method_code` is configuration, not a record of what was paid. Every payment-mix chart and cash-collection split in this spec is unservable. | **0013** + **0046** (`fact_order_tender`) |
| Cancellation semantics | `ordering.order_outcome_reasons`, `ordering.order_outcomes` with `stock_disposition ∈ {RELEASE, RETURN_TO_STOCK, WRITE_OFF, NO_EFFECT}` and `liability_party ∈ {TENANT, CUSTOMER, COURIER_PARTNER, PLATFORM}`. Today only `order_state_history.reason_code varchar(64)` exists, unvalidated and unlocalised. | **0039** |
| Operator identity on an order | `operator_principal_id`. Today only `order_state_history.actor_id varchar(255)` — a string, not a principal, so 7.5 cannot join to a staff dimension. | **0039** + 0043 |
| Kitchen timings | `kitchen.tickets.started_at / ready_at / target_ready_at`, `kitchen.ticket_events`. `PREPARING→READY` from `order_state_history` is an approximation, not a fire-to-pass time, and must be labelled as one. | **0041** |
| All courier and delivery data | `fulfillment.courier_shifts`, `courier_assignment_earnings` (`distance_meters`, `on_time_outcome`, `promised_delivery_end`, `grace_seconds`, `on_time_policy_version`), `courier_ledger_entries`. 7.4 is entirely blocked. | **0042** |
| External-delivery reconciliation | `fact_delivery.provider_billed_som`, `variance_som`, `reconciliation_status` | **0042** + 0043 |
| Aggregator commission | `fact_order.aggregator_commission_som`. **Renders `—`, never `0`** | **0040** |
| Behavioural telemetry | `analytics.events` topic (`session_started`, `customer_registered`, `cart_item_added`, `checkout_started`, `order_placed`), keyed by session, subject = ADR 0029 keyed hash. **Cannot be backfilled.** | **0043** + 0032 |
| Refunds | `fact_order.refunded_som`, `refunded_on_business_date` | **0013** |
| Promotion redemptions | `fact_promotion_redemption` | promotions ADR — *does not exist*; nearest is **0044** |
| Campaign delivery stats | per-recipient delivery and read receipts | **0044** |
| Legal entity per order | `fact_order.legal_entity_id`, snapshotted from the assignment that priced the order | **0038** + 0043 |
| Reviews | four scored dimensions | reviews/feedback ADR — *does not exist* |
| Holiday calendar | `reporting.holidays`, `calendar_version` | **0043** |

### One genuine gap that no ADR closes, and it blocks a tier-P report

**Nothing stores the time promised to the customer.**

`ordering.orders` has `approval_deadline_at` (an internal approval timeout) and
nothing else. `tenant.preparation_bands.duration_minutes` (**built**, V0020) is
the *rule* that resolves a promise, not the promise itself — and it is
tenant-editable, so a manager who widens a band next month retroactively makes
last month's late orders punctual. ADR 0041 gives `kitchen.tickets.target_ready_at`
(prep only) and ADR 0042 gives `courier_assignment_earnings.promised_delivery_end`
(delivery only); neither exists yet and neither is a single order-level promise.

Consequences, which need deciding before 7.1 ships:

- **`orders.late.v1` is currently uncomputable.** The overview tile and the
  entire 7.2 «Опоздания» tab depend on it. It is a tier-P report.
- Delever defines lateness as "actual exceeded the time promised to the customer
  **or** the system's standard duration" — an *or* between two different
  baselines, which is why its late report is not defensible.
- **Recommendation:** add `promised_ready_at` and `promised_delivery_at`,
  snapshotted onto `ordering.orders` at confirmation from the resolved band and
  tariff, immutable thereafter, with `promise_policy_version` beside them —
  exactly the pattern ADR 0043 already uses for `metric_calculation_version` and
  ADR 0042 uses for `on_time_policy_version`. This belongs in ADR 0019/0039
  scope and should be raised as an amendment rather than invented inside a
  report.

### Open questions this spec raises

1. **What is the business-day boundary for the pilot tenant?** ADR 0043 defaults
   to `00:00` and Delever's operating window defaults to `09:00→09:00`. A
   restaurant closing at 02:00 that sees those orders on the next date concludes
   the report is broken. This is a per-tenant onboarding answer, not a default,
   and it must be captured before the first close job runs — changing it later
   needs an ADR 0027 approval and a full recut.
2. **Do cancelled-but-fiscalized orders count in `revenue.gross.v1`?** ADR 0038
   fiscal receipts and ADR 0039 outcomes can disagree with the terminal status.
   Finance signs this with the version-1 definitions.
3. **`is_first_order` across brands.** A tenant under `TENANT_SHARED` identity
   has one customer across brands; under `BRAND_ISOLATED` the same human is two
   accounts (`customer.customer_accounts.identity_partition_brand_id`). "New
   customers" means two different things and the tile must say which.
4. **Aggregator-acquired customers.** Aggregators mask phone numbers, so an
   aggregator order may have no resolvable customer account. Are those orders in
   the denominator of `Частота заказов`? They must be excluded and counted
   separately, or every retention metric is diluted by a channel that cannot
   retain.
5. **Retention for behavioural events.** ADR 0043's provisional 400 days cannot
   reach production unconfirmed by legal, and the cohort grid's usable depth is
   whatever that number turns out to be.

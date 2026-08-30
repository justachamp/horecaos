# Operations spec — Orders

`apps/operations` · IA section 1 · screens 1.1 Order board, 1.2 Order detail,
1.3 New order, 1.4 Drafts, 1.5 Reservations.

This is the section the console exists for. Everything else in operations is
configuration for what happens here. It is used standing up, on a 1366×768
laptop in a call centre or a 24" screen on a manager's desk, during service,
by somebody who is also on the phone.

Sources, in the order they were trusted: the
[parity matrix](../delever-parity-matrix.md) Заказы section, Delever's own
documentation (fetched — see §12 for what is video-only and therefore
unreadable), the archived `qoida-dashboard` pages and types (the archive lives
outside this repository, in the founding machine's Qoida workspace), the
[information architecture](../frontend-information-architecture.md) Part 2, the
[Togora extraction report](../togora-prototype-report.md), and the built backend
— `V0022__create_carts_orders_and_order_processes.sql`,
`ordering/web/OperationsOrderController.java`, and
`docs/domains/state-machines.md`.

Where a field exists today the real table and column are named. Where it does
not, the entry reads **not built — ADR NNNN** and names the owning decision. A
screen may be designed against an unbuilt field; it may not be built against
one.

---

## 0. Rulings made before the screens

Four places where the sources disagree. Deciding them first keeps them out of
every section below.

**0.1 One order, one location. The multi-step order does not exist.**
IA 1.2 lists "multi-step (multi-branch) composition with per-step status". The
parity matrix declines Delever's `steps[]` outright, and `ordering.orders` binds
`location_id` with a foreign key and a trigger that refuses rebinding. The IA
line is stale. There is no per-step status, no step tab, no step money. An order
that needs two kitchens is two orders, and the customer is told so on the call.

**0.2 There is no backward status transition, and the IA promises one.**
IA 1.1 owns "backward status transitions (gated, reason-required, audited — an
improvement on legacy's silent one-click reversals)". The legacy dashboard did
have them: `OrderActionButtons.tsx` renders a `FaBackward` button on `READY`,
`DELIVERING` and `COMPLETED` that PUTs a status straight backwards with no
reason and no confirmation. But `OrderStateMachine` and the `ck_order_status`
CHECK own a forward-only graph, `docs/domains/state-machines.md` states that
tenants and code alike may not extend it, and `order_state_history` has
`ck_order_history_moves` refusing a no-op transition.

The state machine is right and the IA is loose. A correction is not a
transition backwards; it is a *compensating* transition the machine must
declare, carrying its own trigger. Until `OrderStateMachine` declares
`READY -> PREPARING` and `FULFILLING -> READY` as `OPERATIONS_ACTION`
transitions gated on `ORDER_STATE_OVERRIDE`, the console offers **no** backward
affordance — not a disabled one, none (Togora §2n: omit, do not disable). This
is named as a gap in §11 and is the single most likely complaint from staff who
used the legacy board.

**0.3 Cancellation write-off is not an operator choice.**
IA 1.2 says "cancel with reason + write-off type", matching Delever's
*со списанием / без списания*. ADR 0039 rejects that explicitly: under pressure
operators pick whatever closes the dialog fastest and the write-off rate becomes
noise. `stock_disposition` lives on the reason an admin configured once. The
cancel dialog **displays** the disposition the chosen reason carries, read-only,
so the operator can see the consequence — and cannot pick it.

**0.4 The board's default view is a severity queue, not a status partition.**
Delever and the legacy dashboard both open on "new orders". That is the wrong
first screen: a new order is the least urgent thing on it, because nothing has
gone wrong with it yet. The board opens on **Внимание**, a saved view over
several statuses, and Новые is the tab next to it. See §2.2.

---

## 1. Shared vocabulary

### 1.1 The canonical statuses, and what an operator calls them

`ordering.orders.status`, `ck_order_status`. Code-owned; no tenant may reorder
or extend them.

| Status | ru | uz-Latn | Operator meaning |
|---|---|---|---|
| `RECEIVED` | Принят | Qabul qilindi | In the door, nothing decided |
| `PAYMENT_AUTHORIZING` | Оплата | To'lov | Waiting on the payment provider |
| `AWAITING_APPROVAL` | На подтверждении | Tasdiqlashda | Waiting on **us**; has a deadline |
| `PAYMENT_FAILED` | Оплата не прошла | To'lov o'tmadi | Dead unless re-invoiced |
| `CONFIRMED` | Подтверждён | Tasdiqlangan | Accepted, not yet on the line |
| `REJECTED` | Отклонён | Rad etilgan | We refused it |
| `EXPIRED` | Просрочен | Muddati o'tgan | Approval deadline passed |
| `PREPARING` | Готовится | Tayyorlanmoqda | On the line |
| `READY` | Готов | Tayyor | Waiting for a courier or a customer |
| `FULFILLING` | В доставке | Yetkazilmoqda | Delivery only |
| `COMPLETED` | Завершён | Yakunlandi | Terminal |
| `CANCELLED` | Отменён | Bekor qilindi | Terminal |

Two edges are conditional on the order, not the actor: `READY -> FULFILLING` is
delivery only, `READY -> COMPLETED` is pickup and dine-in only. The console must
not render a "на доставку" affordance on a pickup order.

`CONFIRMED -> CANCELLED` exists in the model and is **refused by the application
today** (`OrderStateService.CancellationNotPermittedException`). It opens when
ADR 0039 lands. Until then the cancel action disappears at `CONFIRMED` and the
detail states why, in words, rather than showing a greyed button.

### 1.2 The two projections and the three lanes

`payment_status_projection` and `fulfillment_status_projection` are columns on
`ordering.orders`, written from the payment and fulfilment aggregates' events,
never decided by ordering. They exist so a list renders without joining four
modules. Treat them as display-only: never drive an action off a projection,
always off `status` plus the owning aggregate.

Togora's most transferable finding (§3, "two independent progress clocks") is
that production and delivery are separate clocks that can disagree, and that a
single linear bar destroys the distinction between "the kitchen is late" and
"the courier is late". Qoida already has the shape:

| Lane | Column / source | Owner |
|---|---|---|
| Commercial | `ordering.orders.status` | ADR 0019, built |
| Production | `fulfillment_status_projection` (`IN_PREPARATION`/`READY`), later the kitchen ticket | ADR 0041, not built |
| Delivery | `fulfillment.shipments.status` | ADR 0014, not built |

The detail's timeline (§4.9) renders these as parallel strips, not one line.

### 1.3 Money

Every amount is `bigint` minor units plus `currency char(3)`. UZS has no
subunit in practice: render whole som, thin-space grouped, no decimals, no
currency symbol on every row — `146 000`, with `сум` only on the total. Machine
data is monospace throughout (Togora §2p): order numbers, phones, amounts,
external references, timestamps.

`ck_order_total_reconciles` guarantees
`total = subtotal + tax + fee − discount`. The money panel must show all five
and must never display a computed total that disagrees with `total_minor`; if
they disagree the panel renders an error, because that is data corruption and
hiding it is worse than an ugly screen.

### 1.4 Time

24-hour clock, `DD.MM` dates, tenant timezone. Today's timestamps render as
`HH:mm` alone; anything not today gets `DD.MM HH:mm`. Durations render as
`12 мин`, `1 ч 04 мин`.

The default date filter is the **business date**, not the calendar day, because
an operating day legitimately crosses midnight (IA 7.8). Where
`reporting.agg_*` is unavailable the board derives it from the location's
operating-day window; where that too is unavailable it falls back to calendar
day and *says so* in the filter chip.

### 1.5 Personal data

`display_name_encrypted`, `contact_encrypted`, `address_encrypted`,
`delivery_instructions_encrypted` on `ordering.order_customer_snapshots`, and
`note_encrypted` on `ordering.order_lines`, are ADR 0029 protected.

- The board shows the customer's name in full and the phone **masked**
  (`+998 90 ••• •• 42`) with a reveal control. Reveal is a separate
  capability (`CUSTOMER_PII_REVEAL`) and a separate audited call with a stated
  purpose — the pattern already implemented for line notes at
  `GET /orders/{orderId}/lines/{lineId}/note?purpose=`.
- Copy-to-clipboard of a phone counts as a reveal. The legacy dashboard's
  clipboard button (`OrderItem.tsx`) is kept, but it now performs the reveal
  call rather than copying from a payload that already contained the number.
- `anonymized_at` non-null means the ADR 0029 retention job blanked the
  columns. The customer panel then renders **Данные удалены по сроку
  хранения** and no reveal control. It is not an error state and must not look
  like one.

### 1.6 Live updates

ADR 0045 (not built). The board subscribes `ORDER_QUEUE` at `LOCATION` scope
plus `COUNTERS`; the detail subscribes `ORDER_DETAIL` at `ORDER` scope. Signals
carry `{resourceId, version}` and the client re-reads. `COUNTERS` carries its
integers inline. Frames coalesce at 250 ms — a forty-order bulk assignment must
not produce forty fetches.

Until ADR 0045 exists the board polls every 10 s while the tab is visible and
stops when hidden, and the header carries a "обновлено HH:mm:ss" stamp plus a
manual refresh (the legacy dashboard's `FaRepeat` button, which staff use).
**The stamp is not optional.** A queue that silently stopped updating looks
identical to a quiet shift, and that is how a restaurant loses an hour.

---

## 2. Screen 1.1 — Order board

### 2.1 What it is for

*Which orders need me right now, and what is wrong with them.*

### 2.2 Layout

A dense table under a two-row control block, full width, header fixed, body
scrolling (Togora §1 "screen frame"). Not a board of cards: at fifteen columns
and a hundred rows a table is the only shape that lets an eye scan one axis.
The Kanban shape belongs to the dispatch board (IA 3.1) and the kitchen queue
(IA 2.1), where the columns *are* the work stages; here the work stage is one
attribute among fifteen.

```
┌ Заказы · 47 активных ───────────────── обновлено 14:32:07 ⟳ ─ + Новый заказ ┐
│ [Внимание 6] [Новые 4] [Готовятся 18] [В доставке 12] [Завершены] [Отменены] [Все]
├────────────────────────────────────────────────────────────────────────────┤
│ 🔍 поиск ─── 22.08 сегодня ▾ │ Филиал ▾ │ Канал ▾ │ Тип ▾ │ Оплата ▾ │ Курьер ▾ │ ⋯ ещё
├────────────────────────────────────────────────────────────────────────────┤
│ ▌ №      время   филиал  тип/канал  клиент      позиции  сумма  оплата  статус  курьер ⋯│
└────────────────────────────────────────────────────────────────────────────┘
```

Row height 44 px, no zebra striping (the severity tint needs the background).
Column widths fixed, not content-derived, so rows do not reflow as data
arrives.

### 2.3 Tabs

One row of tabs, each with a live count computed **before** the other filters
apply within that tab's own scope, so a count never collapses to zero as a
filter narrows (Togora §2b). Counts come from `COUNTERS` (ADR 0045) or, until
then, from a `GET .../orders/counts` companion call.

| # | Tab | Membership | Notes |
|---|---|---|---|
| 1 | **Внимание** | `AWAITING_APPROVAL` ∪ `PAYMENT_FAILED` ∪ orders with any `ordering.order_process_states.status IN ('MANUAL_ACTION_REQUIRED','FAILED_RETRYABLE')` ∪ late orders (§2.7) ∪ `callback_requested` unresolved | **Default tab.** A saved view, not a partition — its rows also appear in their status tab, deliberately. If it is empty the shift is fine. |
| 2 | Новые | `RECEIVED`, `PAYMENT_AUTHORIZING`, `AWAITING_APPROVAL` | |
| 3 | Готовятся | `CONFIRMED`, `PREPARING`, `READY` | |
| 4 | В доставке | `FULFILLING` | Hidden when the tenant has no delivery-capable channel |
| 5 | Завершены | `COMPLETED` | |
| 6 | Отменены | `CANCELLED`, `REJECTED`, `EXPIRED` | |
| 7 | Все | everything | The only tab where the date range is mandatory |

The count badge on **Внимание** is the only one that is coloured; it turns red
above zero. The rest are neutral. Delever's tabs are all identical weight,
which teaches an operator nothing.

Tabs are routes (`/orders?tab=attention`), so a supervisor can send someone a
link to the attention queue.

### 2.4 Filters

Query parameters are the source of truth; `localStorage` restores the last-used
set **per tab** on cold entry only. That is the legacy behaviour
(`getStorageKey(status)` in `Orders.page.tsx`, which staff rely on) plus the
deep-linkability the legacy build lacked.

Primary row, always visible:

| Filter | Control | Default | Source |
|---|---|---|---|
| Search | text input, `/` focuses | empty | §2.8 |
| Period | date-range pill pair with presets *Сегодня · Вчера · 7 дней · Период* | Сегодня (business date) | `ordering.orders.created_at` |
| Филиал | multi-select dropdown, searchable, with a live active-order count per branch | all branches the actor is scoped to | `tenant.locations` via `location_id` |
| Канал | multi-select, `<optgroup>` by `system_type` | all | `tenant.sales_channels.display_name`, matched via `channel_id`; the row displays `channel_code_snapshot` |
| Тип | segmented control Доставка / Самовывоз / В зале | all | `fulfillment_mode` |
| Оплата | multi-select | all | `payment_status_projection` + method (**not built — ADR 0013**) |
| Курьер | searchable single-select, with an "Без курьера" option | all | **not built — ADR 0014** `fulfillment.assignment_attempts.courier_id` |

Secondary row behind **⋯ ещё**, and a chip appears in the primary row for each
one that is set:

| Filter | Control | Source |
|---|---|---|
| Мои заказы | toggle | `created_by_actor_id = me` — **not built — ADR 0039** |
| Только опаздывающие | toggle | derived, §2.7 |
| С проблемой | toggle | `order_process_states.status` in the two failure states |
| Требуется звонок | toggle | `callback_requested` — **not built — ADR 0039** |
| Агрегатор | multi-select of bindings | **not built — ADR 0040** `marketplace_binding_id` |
| Способ оплаты | multi-select | **not built — ADR 0013** |
| Фискализация | multi-select of `PENDING/BLOCKED/FAILED/ISSUED` | **not built — ADR 0038** `fiscal.fiscal_documents.status` |

A control that is filtering shows it in its own border and fill (Togora §2b),
not only by a chip elsewhere. **Сбросить фильтры** clears everything except the
tab and the period.

Delever reuses one filter vocabulary across the order list and every order
report. Match that: the same component and the same query-parameter names serve
IA 7.2, so a manager who filters the board and then opens the report keeps the
selection.

### 2.5 Columns

Default-visible set first. A column picker (persisted per user, IA 0.2) governs
the rest; `Филиал` auto-hides for a single-location tenant.

| # | Column | Type | Source | Notes |
|---|---|---|---|---|
| 1 | selection | checkbox | — | §2.10; header checkbox selects the loaded page only, and says so |
| 2 | severity rail | 4 px left border | derived §2.7 | transparent when normal, so rows stay aligned |
| 3 | **№** | mono text + copy | `ordering.orders.public_order_number` | scoped per location per business date (`order_number_counters`), so it is short and repeats across branches — always render the branch beside it when several are in view. Under it: the severity caption (§2.7) and any external reference badge (§2.8) |
| 4 | **Время** | time | `created_at` | second line: the promise — `→ 15:20` from **not built — ADR 0014** `delivery_plans.promised_delivery_end`, or `estimated_ready_at` for pickup |
| 5 | **Филиал** | text | `tenant.locations.display_name` | |
| 6 | **Тип / канал** | icon pair + text | `fulfillment_mode`, `channel_code_snapshot`, `tenant.sales_channels.system_type` | one cell; the channel icon carries a `title` and an accessible name |
| 7 | **Клиент** | text + masked phone | `order_customer_snapshots.display_name_encrypted`, `contact_encrypted` | §1.5. Guest orders (`guest_reference_hash` set) show **Гость** |
| 8 | **Позиции** | count + first line | `count(order_lines)`, `order_lines.product_name_snapshot` of line 1 | `4 поз · Лагман…` — enough to recognise an order on the phone |
| 9 | **Сумма** | mono money | `total_minor`, `currency` | right-aligned |
| 10 | **Оплата** | badge + method | `payment_status_projection`; method **not built — ADR 0013** | `NOT_REQUIRED` renders as **Наличными** once ADR 0013 lands, and as `—` before |
| 11 | **Статус** | badge | `status` | §1.1 vocabulary; `AWAITING_APPROVAL` additionally shows the countdown to `approval_deadline_at` |
| 12 | **Курьер** | name + shift dot | **not built — ADR 0014 / 0042** | `—` when unassigned; a hollow dot when the courier is off shift (§2.9) |
| 13 | ⋯ | overflow menu | — | §2.9 |

Behind the picker: **Создал** (`created_by_actor_id`, ADR 0039), **Принял**
(`accepted_by_actor_id`, ADR 0039), **Ф** (fiscal chip, ADR 0038), **Доставка**
(`customer_delivery_fee_minor`, ADR 0014), **Скидка** (`discount_minor`),
**Дистанция** (ADR 0014), **Внешний №** (ADR 0040).

The legacy dashboard rendered each order as a **card** carrying its own line
items, both money breakdowns and a courier block — roughly 220 px per order,
four orders per screen. That was readable and unusable: an operator could not
see the queue. The table is the right shape; the card's content survives as the
detail view and as columns 8 and 12.

### 2.6 Sort order

Operational queues sort by severity, not by time (Togora §2e). Within the four
live tabs the comparator is:

```
severity(o) =
  0  process in MANUAL_ACTION_REQUIRED        (a human must act, nothing else will)
  1  LATE                                     (promise already breached)
  2  AWAITING_APPROVAL with < 2 min to deadline
  3  PAYMENT_FAILED
  4  AT_RISK                                  (predicted breach)
  5  callback_requested unresolved
  6  everything else
then: promise time ascending  (nulls last)
then: created_at ascending    (oldest first — the person who waited longest)
```

`created_at` **ascending**, not descending. Delever and the legacy dashboard
both put newest first, which is right for a log and wrong for a queue: it pushes
the order that has been waiting longest off the bottom of the screen. Newest-first
is kept for **Завершены**, **Отменены** and **Все**, which are logs.

Column headers are clickable to override the sort. The override is a chip that
says **Сортировка: по сумме ↓ · вернуть к очереди**, because an operator who
sorted by amount at 12:00 and forgot is an operator working the wrong order at
13:00.

### 2.7 Severity, and the late overlay

Delever ships a tenant-configured lateness threshold in minutes and an
admin-chosen highlight colour. Match the threshold; refuse the colour. Severity
colour is semantic and belongs to the design system — a tenant who picks green
for "late" has broken every screen at once.

**Inputs.** `promised_delivery_end` for delivery, `estimated_ready_at` for
pickup and dine-in (both **not built — ADR 0014**), `now`, `status`, and a
policy resolved through ADR 0030 at key `ordering.lateness`:

```
ordering.lateness            (ADR 0030 document, per fulfilment mode)
  at_risk_before_seconds     default 300   — flag before the promise, not after
  late_after_seconds         default 0     — grace past the promise
  no_promise_fallback_seconds default 2700 — 45 min from created_at when no plan exists
```

**Levels.**

| Level | Predicate | Rail | Row tint |
|---|---|---|---|
| `BLOCKED` | any process `MANUAL_ACTION_REQUIRED` | 4 px danger | danger-subtle |
| `LATE` | non-terminal and `now > promise + late_after` | 4 px danger | danger-subtle |
| `AT_RISK` | non-terminal and `now > promise − at_risk_before` | 4 px warning | warning-subtle |
| normal | — | 4 px transparent | none |

Strict precedence, and the lower caption is suppressed when a higher one is
present (Togora §2d). **Terminal orders are never flagged**, whatever their
history — a completed order that ran late is a report row, not a queue row.

**The caption is the point.** A bare red row teaches nothing. Under the order
number, in the severity colour, one line of real text:

```
#0142   опаздывает +12 мин · курьер не назначен
#0139   POS: заказ не отправлен — товар не сопоставлен
#0151   подтвердить за 01:12
```

Three channels at once — tint, rail, caption — is the pattern that works, and
the transparent rail on normal rows keeps the number column aligned.

**Nothing on the board writes lateness.** It is derived per render from the
promise and the clock, so it needs no column, no job and no event. That matters:
Delever backs it with a stored flag and a report, and a stored flag is wrong
five seconds after it is written.

### 2.8 Search

One input, `/` to focus, 300 ms debounce, minimum two characters. It resolves
four kinds of thing, in this order, and **says which one it matched**:

1. **Our order number** — `public_order_number`, exact or prefix. Scoped per
   location per day, so `142` legitimately matches several orders; results
   show branch and date and the operator picks.
2. **A phone number** — detected by pattern (`+998…`, `9 digits`, `90…`).
   Issued as `POST /api/v1/operations/customer-lookups` with the number **in
   the body**, never a query string (ADR 0039 — and the number must not land in
   an access log, a browser history or a `Referer`). Resolves through ADR 0015's
   keyed `normalized_hash`, which is deliberately not unique: several accounts
   may come back and the operator picks from masked name plus last-order date.
   Every lookup is a `SECURITY`-class ADR 0027 audit fact.
3. **An aggregator's id** — **not built — ADR 0040**
   `ordering.order_external_references.reference_value_normalised`, matched
   across the tenant. Normalisation uppercases and strips whitespace, hyphens
   and a leading `#`, so an operator reading `YE-2291-04` off a courier's phone
   finds `ye229104`. Several rows may match — disambiguate by provider and
   branch in the result. Reference types: `PARTNER_ORDER_ID`,
   `PARTNER_DISPLAY_CODE`, `PARTNER_VENUE_ORDER_NO`, `DELIVERY_CLAIM_ID`,
   `POS_ORDER_ID`. This is the one Delever calls out by name — Yandex Eats,
   Wolt, Glovo, Uzum — and it is the difference between answering a courier in
   four seconds and in four minutes.
4. **A UUID** — the internal `orders.id`, for support pasting from a log.

Search is **not** scoped to the active tab. A customer ringing about yesterday's
cancelled order should be found from the Внимание tab. When a hit lies outside
the current tab and period, the result row says so and offers **показать в
«Все»**.

Where a match is an external reference, the row carries a badge —
`Wolt · WLT-88213` — under the order number.

### 2.9 Row actions

Delever's whole order UX is a twelve-item `...` menu opening a dozen dialogs
(§12.1). That is a design that makes every action equally far away, which means
the two an operator performs three hundred times a shift cost the same as the
one they perform twice a month.

**The split:** at most two inline affordances on the row, everything else in the
overflow. The inline affordance is the single most likely next action for that
row's state, and it is a button, not a menu item.

| Row state | Inline | Rest |
|---|---|---|
| `AWAITING_APPROVAL` | **Принять** · **Отклонить** | overflow |
| `PAYMENT_FAILED` | **Выставить счёт** | overflow |
| `CONFIRMED` | **На кухню** | overflow |
| `READY`, delivery, no courier | **Назначить курьера** | overflow |
| `READY`, pickup | **Выдан** | overflow |
| `FULFILLING` | **Доставлен** | overflow |
| process `MANUAL_ACTION_REQUIRED` | **Разобрать** → the detail's integration panel | overflow |
| terminal | none | overflow (read-only items only) |

Every inline mutation sends `If-Match` with the row's `version` and an
`Idempotency-Key` (ADR 0031). A `409` re-reads the row in place, shows
**Заказ изменился — обновлено**, and does not retry.

Overflow menu, in this order, with unavailable entries **omitted** rather than
disabled (Togora §2n; the IA's server-supplied `actions[]` capability array is
the correct origin for this — the client renders what the server permits and
never computes availability itself):

`Открыть` · `Копировать номер` · `Позвонить клиенту` · `Назначить курьера` ·
`Вызвать службу доставки` · `Изменить заказ →` (submenu of the amendment
commands, §4.11) · `Комментарий кухне` · `Печать в POS` · `Фискализировать` ·
`Завершить` · `Отменить`.

### 2.10 Bulk actions

Checkbox column, header checkbox selects the loaded page and labels itself
**Выбрать 50 на странице** — never a silent select-all across a filtered set of
unknown size. A selection bar replaces the filter row while anything is
selected: `Выбрано 12 · Назначить курьера · Отменить · Печать · Снять выбор`.

**An action is offered only when it is valid for every selected row.** Not
disabled — absent, with one line saying why: *«Отменить» недоступно: 3 из 12
заказов уже завершены*. This is what keeps an operator from learning that a
greyed button means "try again".

| Bulk action | Valid when | Confirm |
|---|---|---|
| Назначить курьера | every row is delivery, non-terminal, and the courier is on shift | no — it is reversible |
| Отменить | every row is cancellable at its current status | yes, plus the reason registry, plus approval threshold per item |
| Печать в POS | every row's location has a POS binding declaring `print` | no |
| Фискализировать | every row has a `FAILED` or `BLOCKED` fiscal document | yes |
| Экспорт CSV | always | yes — it is an audited PII egress (IA 7.2) |

**Semantics** (ADR 0039, and this is not negotiable): N independent commands
under one `bulk_operation_id`, each in its own transaction, each with a
per-item idempotency key `{bulkKey}:{orderId}`. The endpoint returns `202` with
the bulk id and a **per-item outcome list**, never a single success or failure.
Batch cap 200. A single all-or-nothing transaction is refused — it produces a
lock convoy during exactly the peak that caused the bulk action, and one
already-cancelled order fails the other 199.

The UI therefore needs a **result panel**, not a toast: `197 назначено · 3
проблемы`, the three listed with their `item_problem_code`, and **Повторить
проблемные** re-running under the same bulk key so successes replay their stored
responses instead of executing twice.

### 2.11 States

| State | Rendering |
|---|---|
| Loading, first | Skeleton rows at the real row height. Never a spinner over an empty frame — the header, tabs and filters render immediately from the URL |
| Loading, refetch | Rows stay, the refresh stamp pulses. Never blank a populated queue |
| Empty, no filters | **Заказов пока нет** + the New order button. Table header and frame stay (Togora §2p) |
| Empty, filtered | **Ничего не найдено по фильтрам** + **Сбросить фильтры**, and the count of what would show without them |
| Empty, Внимание | **Всё в порядке** — a positive statement, not a null result |
| Denied | The tab renders, rows do not: **Нет доступа к заказам этого филиала**, naming the capability and the branch. Never a blank page and never a 403 code |
| Error | Inline banner above the table carrying the ADR 0031 `errorCode` and correlation id, with **Повторить**. Previously loaded rows stay |
| Stale stream | Amber banner **Связь потеряна — данные от 14:31** with **Обновить**. The rows are not hidden; an operator working from a two-minute-old queue with a warning is better off than one staring at a spinner |
| Partial scope | When the actor is scoped to some branches, the branch filter shows only those and states **Показаны 2 филиала из 5** |

### 2.12 Keyboard

The board is keyboard-first (IA Part 2, operator shell). Rows are focusable and
Enter opens.

| Key | Action |
|---|---|
| `/` | focus search · `Esc` clears and blurs |
| `j` / `k`, `↓` / `↑` | move row focus |
| `Enter` | open the focused order |
| `Space` | toggle selection |
| `1`–`7` | switch tab |
| `a` | approve the focused order (only on `AWAITING_APPROVAL`) |
| `x` | open the cancel dialog |
| `c` | open assign-courier |
| `p` | print to POS |
| `n` | new order |
| `r` | refresh |
| `?` | shortcut sheet |
| `Esc` | close dialog · leave selection mode |

Destructive keys never act directly: `x` opens a dialog whose confirm is a
click or `Enter` on a focused button, never a bare keystroke.

---

## 3. Screen 1.2 — Order detail

### 3.1 What it is for

*Everything about this one order, and every action I can take on it.*

### 3.2 Layout

A full route, `/orders/:orderId`, not a drawer. Reasons: operators paste order
links to each other and to the kitchen; the content does not fit a drawer at
1366 px; and a drawer over a live-updating queue means the row under it moves.
Board filters live in query parameters, so browser-back restores the queue
exactly (Togora §2a).

Master-detail within the page: a two-column body under a full-width sticky
header.

```
┌ ← Заказы   Заказ #0142 · Юнусабад   [Готовится]   опаздывает +12 мин ──── [Принять][⋯]┐
├──────────────────────────────── 2fr ──────────────┬──────── 1fr ─────────────────────┤
│ Состав                                            │ Клиент                            │
│ Деньги                                            │ Адрес и доставка                  │
│ Комментарии                                       │ Оплата                            │
│ Хронология                                        │ Фискализация                      │
│ Ревизии                                           │ Интеграции                        │
│                                                   │ Кто оформил / кто принял          │
└───────────────────────────────────────────────────┴───────────────────────────────────┘
```

Below 1200 px the right column stacks under the left, **customer and address
first** — on a small screen the thing you are reading aloud on the phone
outranks the thing you are looking at.

The header is sticky and carries: back link (a text link above the title, not
chrome — Togora §2a), order number, branch, status badge, the severity caption
when present, the promise clock, and the primary action for the current state
plus an overflow.

Nine tabs is the observed ceiling for a detail (Togora §2k) and this detail
needs none: everything fits two columns, and a tab hides exactly the thing an
operator needs to glance at while talking.

### 3.3 Header

| Element | Source |
|---|---|
| Order number | `public_order_number` (mono) + copy |
| Branch | `tenant.locations.display_name` |
| Status badge | `status` |
| Severity caption | derived §2.7 |
| Promise clock | `promised_delivery_end` / `estimated_ready_at` — **not built — ADR 0014**; live countdown, turning warning then danger |
| Approval countdown | `approval_deadline_at`, only on `AWAITING_APPROVAL` |
| Version | `version`, mono, small — support asks for it, and it is what `If-Match` carries |
| Primary action | §3.11 table |
| Ревизия N | **not built — ADR 0039** `current_revision`; a chip that opens §3.10 |

### 3.4 Состав — the lines

A table, not cards. One row per `ordering.order_lines`, ordered by
`line_number`.

| Column | Type | Source |
|---|---|---|
| # | int | `line_number` |
| Наименование | text | `product_name_snapshot`, with `variant_name_snapshot` beneath in muted small |
| Модификаторы | list | `ordering.order_line_modifiers.group_name_snapshot` → `option_name_snapshot`, indented under the line, `×qty` when `quantity > 1`, price when `final_amount_minor > 0` |
| Комментарий клиента | reveal control | `order_lines.note_encrypted` — renders as **💬 есть комментарий** until revealed via `GET .../lines/{lineId}/note?purpose=`, audited |
| SKU | mono | `sku_snapshot` — behind the column picker |
| Кол-во | int | `quantity` |
| Цена | mono money | `unit_amount_minor` |
| Сумма | mono money | `final_amount_minor` |
| Из них НДС | mono money | `tax_amount_minor` — behind the picker |

`base_amount_minor` differing from `final_amount_minor` means a line-level
discount applied: render the base struck through beside the final (Togora §2p,
sale-price display), and the reason from
`ordering.order_adjustments.description_code` where
`adjustment_type = 'ITEM_DISCOUNT'`.

Every name is a **snapshot**. The panel states this once, quietly: *«Названия и
цены зафиксированы на момент оформления»*. Otherwise a manager who renamed a
dish will report a bug.

A line whose `source_variant_id` no longer exists in the current publication
still renders — that is the entire point of the snapshot columns — but the
reorder affordance for it is absent.

### 3.5 Деньги

| Row | Source | Notes |
|---|---|---|
| Сумма позиций | `subtotal_minor` | |
| Скидка | `discount_minor` | expandable into `order_adjustments` where `adjustment_type IN ('ITEM_DISCOUNT','ORDER_DISCOUNT')`, each with `description_code`, `source_type`, `source_id`, `source_version` — which promotion, at which version |
| Сборы | `fee_minor` | expandable into `adjustment_type = 'FEE'`, which is where packaging (the legacy `Посуда`) and the service charge live |
| Доставка | **not built — ADR 0014/0037** `delivery_plans.customer_delivery_fee_minor` | shown as a separate line even though it arrives inside `fee_minor`, because it is the number a customer argues about |
| НДС (в сумме) | `tax_minor` | labelled as included — ADR 0018 prices are VAT-inclusive |
| **Итого** | `total_minor` | |
| Сдача с | **not built — ADR 0039** `cash_tendered_expected_minor` | plus the derived `tendered − total`; recomputed on every revision. It is an operational hint, never a payment transaction |

Two figures Delever puts side by side and the external-logistics report depends
on: **what the customer paid for delivery** and **what the provider billed us**
(`fulfillment.delivery_quotes.price_minor` / the settled cost — **not built —
ADR 0014**). Show both here, not only in IA 8.4, because the operator refunding
a delivery fee needs to know which number they are giving away.

**Externally priced orders.** When `pricing_authority = 'EXTERNAL'` (**not built
— ADR 0040**) the whole panel is read-only and headed **Цены партнёра — Qoida
их не пересчитывает**, sourced from `ordering.order_external_pricing`. If
`arithmetic_verified` is false the panel carries a warning. Never silently show
a partner's numbers in the same styling as our own.

### 3.6 Комментарии — three channels, and one that has no home

The legacy dashboard had exactly three note channels, each with its own modal
and its own array on the order: `internal_note` (operator→operator),
`vendor_note` (operator→restaurant) and `courier_note` (operator→courier).
Staff use all three.

| Channel | Direction | Source |
|---|---|---|
| Комментарий клиента к заказу | customer → us | `order_customer_snapshots.delivery_instructions_encrypted` — built, reveal-gated |
| Комментарий клиента к позиции | customer → kitchen | `order_lines.note_encrypted` — built, reveal-gated, §3.4 |
| Комментарий кухне | operator → kitchen | **not built — ADR 0039** `SET_KITCHEN_NOTE` |
| Комментарий курьеру | operator → courier | **no owner** — see §11 |
| Внутренняя заметка | operator → operator | **no owner** — see §11 |

The customer's own words are personal data and never render unrevealed. Ours
are not, and render in full. Rendering both in one undifferentiated list, as the
legacy dashboard did, is how a customer's note ends up on a screenshot in a
group chat.

### 3.7 Клиент

| Field | Source |
|---|---|
| Имя | `order_customer_snapshots.display_name_encrypted` |
| Телефон | `contact_encrypted`, masked, reveal-gated, with call and copy |
| Тип | account (`customer_account_id`) vs **Гость** (`guest_reference_hash`) |
| Можно связаться по заказу | `transactional_contact_allowed` — when false, the call and message affordances are absent and the panel says why |
| История заказов | link to IA 5.2, plus an inline count and last-order date |
| Происхождение | **not built — ADR 0039** `customer_accounts.origin` — an `OPERATOR` account is labelled **создан оператором**, has no consent, and shows no marketing affordance at all |
| Требуется звонок | **not built — ADR 0039** `callback_requested`; cleared only by an operator, recording `callback_resolved_at` / `_by` |
| Анонимизирован | `anonymized_at` — §1.5 |

### 3.8 Адрес и доставка

| Field | Source |
|---|---|
| Адрес | `order_customer_snapshots.address_encrypted`, reveal-gated |
| дом / квартира / подъезд / этаж / ориентир | structured fields **inside** `customer.addresses.encrypted_fields` (V0021 note) — decrypted and rendered as labelled fields, never as one string |
| Координаты | `customer.addresses.latitude` / `longitude` — in clear |
| Точность геокодирования | `customer.addresses.coordinate_source`; `NOT_GEOCODED` is legitimate here, not an error — a mahalla house described by its ориентир has no point, and the panel says **адрес по ориентиру** rather than showing a broken map |
| Зона | **not built — ADR 0037** `delivery_fee_resolutions.zone_id` + the resolution's `outcome` and `reason_code` — the answer to "why did this cost 25 000" |
| Расстояние | **not built — ADR 0037** `distance_meters`, `distance_mode` |
| Обещано | **not built — ADR 0014** `promised_delivery_start/end` |
| Курьер | **not built — ADR 0014** name, phone, vehicle; shift state from ADR 0042 `courier_shifts` |
| Служба доставки | **not built — ADR 0014** `shipments.provider_type`, `external_shipment_id`, live status |
| Код выдачи | **not built — ADR 0040** `order_handover_challenges.status` — `PENDING/VERIFIED/BYPASSED/FAILED/EXPIRED`. The expected value is **never** sent to the client; verification is server-side |

A map is shown only when coordinates exist, and the legacy behaviour of opening
Yandex Maps in a new tab (`addressOnMap` in `Order.detail.page.tsx`) is kept as
a secondary affordance, because couriers ask for a shareable link.

### 3.9 Оплата and Фискализация

**Оплата** — `payment_status_projection` is the display; the authority is ADR
0013 (not built).

| Field | Source |
|---|---|
| Статус | `payment_status_projection` |
| Способ | **not built — ADR 0013**; the registry is ADR 0038 §"tenant payment-method registry" |
| Транзакции | **not built — ADR 0013** append-only transactions, each with provider, amount, time, external reference |
| Возвраты | **not built — ADR 0013** |
| Сдача | **not built — ADR 0039** `cash_tendered_expected_minor` |

The legacy dashboard let an operator set `payment_status` and
`payment_transaction_id` **by hand** from a dropdown (`updateOrderSchema`).
That is a hole: it lets an operator mark an unpaid order paid with no evidence
and no audit beyond a status string. It is not reproduced. Its legitimate use —
"the customer paid at the door" — is `CHANGE_PAYMENT_METHOD` plus a settled cash
transaction, not an editable enum.

**Фискализация** — **not built — ADR 0038** `fiscal.fiscal_documents`.

| Field | Source |
|---|---|
| Статус | `status`: `PENDING/REQUESTED/ISSUED/FAILED/BLOCKED/CORRECTION_*/VOID_*/NOT_REQUIRED` |
| Причина блокировки | `blocked_reason_code` — `CLASSIFICATION_MISSING`, `MARKS_INCOMPLETE`, `NO_FISCAL_PATH`, `TERMINAL_OFFLINE`. **`BLOCKED` is work, not an error**, and appears in the Внимание tab |
| ИНН | `legal_entity_id` → the entity's INN, plus `fiscal_assignment_version` |
| Чек | `external_receipt_id`, `fiscal_sign`, `receipt_reference` + the customer-facing URL |
| Попыток | `attempt_count`, `failure_code` |
| Маркировка | `fiscal_unit_marks` count vs line quantity |

`Фискализировать` retries **the same document** — never creates a second. Two
receipts for one order cannot be deleted, only corrected, and cost an accountant
a day.

### 3.10 Хронология and Ревизии

**Хронология** is built: `ordering.order_state_history`, served by
`GET .../orders/{orderId}/timeline`.

Render as parallel lanes, not one line (§1.2, Togora §2f):

```
Коммерческий  ●─────●─────●─────○
              14:02  14:03 14:11  Готовится
              Принят Подтв. Кухня
Кухня         ●─────○                    (ADR 0041, not built)
Доставка      ○                          (ADR 0014, not built)
```

Rules: completed stage = filled mark **with its timestamp printed underneath as
visible text**, never in a `title` attribute — a tooltip is not
keyboard-reachable. Current stage = hollow mark with the label under it. Future
= muted. Between stages, the elapsed duration.

Each entry expands to `from_status → to_status`, `trigger`
(`CHECKOUT | APPROVAL_DECISION | APPROVAL_TIMEOUT | PAYMENT_RESULT |
OPERATIONS_ACTION | KITCHEN_PROGRESS | CUSTOMER_ACTION | SYSTEM`), `reason_code`,
`actor_type`,
`actor_id` resolved to a name, and `occurred_at`. `sequence_number` is allocated
from the order's version, so a gap means a lost update — if the sequence has a
gap the panel says **пропущена запись N**, because hiding it hides a bug.

`ordering.approval_decisions` are shown here too, including the ones with
`effective = false`: *«Отклонил Ш. Каримов 14:03 — не применено, заказ уже
подтверждён»*. Delever shows only the winner. Showing the loser is how two
operators stop arguing about who did what.

**Ревизии** — **not built — ADR 0039** `ordering.order_revisions`. One row per
revision: number, source (`CHECKOUT | AMENDMENT`), the amendment that produced
it, `created_by`, `created_at`, the five money figures, and the delta against
its predecessor. Revision 1 is the checkout snapshot and is byte-identical
forever.

Every read on this screen that involves lines or money is **revision-aware**.
The default view is `current_revision`; a revision selector re-renders the
lines and the money panel at that revision using `order_lines.revision_from` /
`revision_to`. A join that forgets to pin a revision double-counts, and the
mistake stays invisible until someone reconciles a total by hand.

### 3.11 Интеграции

`ordering.order_process_states` — built, and the most underused thing in the
schema. One row per order per concern: `ORDER_PAYMENT`, `RESTAURANT_APPROVAL`,
`ORDER_INVENTORY`, `POS_ORDER_EXPORT`, `ORDER_FULFILLMENT`,
`ORDER_NOTIFICATION`. Only `ORDER_INVENTORY` is driven today.

| Column | Source |
|---|---|
| Процесс | `process_name`, in operator words — «Экспорт в POS», «Резерв склада» |
| Статус | `status`: `WAITING/COMPLETED/FAILED_RETRYABLE/MANUAL_ACTION_REQUIRED/COMPENSATED` |
| Попыток | `attempt_count` |
| Следующая попытка | `next_attempt_at` — the answer to "is it stuck or just slow" |
| Ошибка | `last_error` |
| Действие | retry / resolve, per process |

`MANUAL_ACTION_REQUIRED` is what puts an order in the Внимание tab and what the
row caption quotes. Delever surfaces POS failure only at creation time, in a
toast; an order that fails to reach the POS forty minutes later is invisible.
This panel is the improvement, and it costs nothing because the table already
exists.

The POS failure causes Delever names — unmapped product, unmapped payment type,
product inactive in the POS — each get a **fix path**, not just a message: a
link to the catalog sync mapping table (IA 4.5) with that item pre-selected.

**Never apply an amendment while a POS export attempt is unacknowledged**
(ADR 0039). The amendment affordances disappear and this panel explains why: the
failure being prevented is a kitchen holding two tickets for one order and
cooking the first.

### 3.12 Attribution

**not built — ADR 0039**. `created_by_actor_type/id` — who entered the order;
`accepted_by_actor_type/id` and `accepted_at` — who moved it to `CONFIRMED`.
Written once, never overwritten: a leaderboard a later action can rewrite
measures nothing. Machine principals appear as pseudo-operators (IA 7.5), so an
auto-confirmed order shows **система**, not an empty cell.

The legacy dashboard showed `Оператор: (Не указан)` in red italic on every
self-service order, which trained staff to ignore the field. Show the channel
instead: **оформлен клиентом · Telegram**.

---

## 4. Actions

### 4.1 The contract

Every mutation, without exception: an intent-named endpoint, `Idempotency-Key`,
`If-Match` with the expected `version`, a declared ADR 0025 capability at
`LOCATION` scope, and a reason where the state machine asks for one (ADR 0031,
already enforced by `OperationsOrderController`).

There is no partial `PUT`. Delever exposes one and ADR 0039 rejects it by name:
an unbounded edit has no consequence vector, and six months later nobody can say
whether a given save re-fiscalized, released stock, or reprinted the kitchen
ticket.

A `409 STALE_VERSION` is handled by re-reading and telling the operator what
changed, never by retrying. A `409 RESOURCE_CONFLICT` from
`IllegalTransitionException` renders the from/to pair in words.

### 4.2 Availability

Availability is **server-supplied**. The detail response carries an `actions[]`
array (IA 1.2) and the client renders exactly that. The client never computes
availability from a status string — the rules involve the POS export state, the
amendment cut point, approval thresholds and capability grants, and a client
that reimplements them will disagree with the server at the worst moment.

Unavailable actions are **omitted**. The one exception: an action that is
temporarily unavailable for a stated, transient reason renders as a disabled
control with that reason attached — «Изменение недоступно: ожидается
подтверждение POS». Permanently unavailable is invisible; temporarily blocked is
visible and explained.

### 4.3 Built today

| Action | Endpoint | Needs | Unavailable when | Confirm |
|---|---|---|---|---|
| **Принять** | `POST .../approval-decisions` `{decisionId, action:APPROVE, reasonCode?}` | `ORDER_APPROVE` | status ≠ `AWAITING_APPROVAL` | no |
| **Отклонить** | same, `action:REJECT` | `ORDER_APPROVE` | status ≠ `AWAITING_APPROVAL` | yes — reason required |
| **Продвинуть** | `POST .../state-actions` `{targetStatus, reasonCode}` + `If-Match` | `ORDER_ADVANCE` | the transition is not in the machine; `READY→FULFILLING` on a pickup order | no |
| **Отменить** | `POST .../cancellations` `{reasonCode}` + `If-Match` | `ORDER_CANCEL` | status is `CONFIRMED` or later (**refused today**), or terminal | yes |
| **Показать комментарий** | `GET .../lines/{lineId}/note?purpose=` | `CUSTOMER_PII_REVEAL` | no note | no |

`decisionId` is client-supplied and stable across retries of one human decision,
so the same click arriving twice is one decision. The response reports the
outcome that actually settled the order — which may be another operator's — so
a second click gives the same answer as the first rather than an error. The UI
must render that honestly: **«Уже принят — Ш. Каримов, 14:03»**, not a generic
success.

### 4.4 Amendment (ADR 0039, not built)

Ten commands, closed set. Each produces a **new immutable revision**; none edits
a revision and none creates a second order.

| Command | Operator label | Dialog | Consequences the dialog must state before confirm |
|---|---|---|---|
| `ADD_LINES` | Добавить позиции | item search + basket, same component as 1.3 | reprice · reserve · charge the difference · re-fiscalize · POS amend |
| `CHANGE_LINE_QUANTITY` | Изменить количество | inline stepper on the line | reprice · reserve or release the delta · charge or refund |
| `REMOVE_LINES` | Удалить позиции | line checkboxes + removal reason | reprice · release **or write off** per the removal reason · refund |
| `CHANGE_PAYMENT_METHOD` | Изменить оплату | method picker filtered by the channel's `channel_payment_methods` | totals unchanged · void or refund the old intent · **re-fiscalize under the new method's rules** |
| `CHANGE_DELIVERY_ADDRESS` | Изменить адрес | address picker + map | reprice — the ADR 0037 zone fee may change · charge or refund the difference |
| `CHANGE_FULFILLMENT_TIME` | Изменить время | now / scheduled | reprice if it crosses a price plane · re-evaluate the hold |
| `CHANGE_CONTACT` | Изменить контакт | name, phone | none |
| `SET_KITCHEN_NOTE` | Комментарий кухне | textarea | none |
| `SET_CALLBACK_REQUESTED` | Требуется звонок | toggle | none |
| `SET_CASH_TENDERED` | Сдача с | amount | none |

Amendment lifecycle, and the UI state for each:

```
DRAFT -> PRICED
PRICED -> AWAITING_CUSTOMER_CONFIRMATION -> AWAITING_PAYMENT -> APPLIED
PRICED -> APPLIED            (no increase, no payment required)
any non-terminal -> REJECTED | EXPIRED
```

- `PRICED` shows the **delta**, big: `+18 000 сум · было 146 000 → 164 000`.
  Never only the new total. The operator is reading a change to a customer.
- `AWAITING_CUSTOMER_CONFIRMATION` is an explicit attestation control —
  «Клиент подтвердил по телефону» — with the operator, the time and the channel
  recorded. An increased total cannot commit without it. Charging more than the
  customer agreed to is the failure this prevents.
- `AWAITING_PAYMENT` on an online-paid order blocks the revision until the
  incremental payment succeeds.
- **Expiry is 15 minutes**, the ADR 0018 quote TTL. The dialog shows the
  countdown. An expired amendment applies nothing.
- **One open amendment per order**, on a partial unique index. A second operator
  opening the dialog sees **«Заказ редактирует Ш. Каримов»** with the elapsed
  time, and may take it over only after the first one's amendment expires.
- A decrease above a configured amount needs ADR 0027 four-eyes approval; the
  dialog turns into an approval request rather than a confirm.
- **The cut point.** Financial commands stop at `READY` by default, resolved
  through ADR 0030 per location. Past it, the answer to "add a dessert" is a
  second order, and the UI says exactly that, with a button that starts one
  prefilled from this customer and address. Delever lets the edit through and
  leaves the fiscal consequence to chance.

`ADD_LINES` whose item is unavailable applies **nothing**: no reservation, no
quote acceptance, no revision. The dialog reports which item and offers to
remove it and re-price.

### 4.5 Cancellation (ADR 0039, not built)

`POST /api/v1/operations/orders/{orderId}/cancellation`.

The dialog:

1. **Reason** — a searchable list from `ordering.order_outcome_reasons` where
   `kind = 'CANCELLATION'` and `status = 'ACTIVE'`, showing `internal_name`
   («Не дозвонились», «Клиент передумал», «Курьер опоздал», «Нет товара»)
   grouped by `system_category`. Typeahead, because tenants accumulate dozens.
2. **What the customer will be told** — the reason's localised `customer_text`,
   read-only, rendered verbatim. The two texts are different statements and
   publishing the internal one to a customer is what the split prevents.
3. **Consequences**, read-only, derived from the reason:
   - `stock_disposition` → **Списание** / **Возврат на склад** / **Освободить
     резерв** / **Без движения**
   - `liability_party` → **За счёт заведения / клиента / службы доставки /
     платформы**
   - `customer_refund` → **Полный возврат** / **Без возврата** / **По
     решению**
4. **Free-text note**, optional, audited.
5. Confirm, naming the object: *«Заказ #0142 на 146 000 сум будет отменён.
   Клиенту вернётся 146 000 сум.»* (Togora §2h — the confirm modal names the
   object and states irreversibility.)

Stock consequence, and this is the closed ADR 0017 open input: **before** the
inventory reservation is committed, cancellation always releases it and the
disposition is ignored. **After** commitment the disposition decides —
`RETURN_TO_STOCK` writes a return movement, `WRITE_OFF` a waste movement,
`NO_EFFECT` nothing (correct only for `UNTRACKED` items). A cancellation never
reopens a committed reservation. The dialog states which of the two situations
applies, in words, because "we already cooked it" is the difference.

Cancelling an order with a live shipment must cascade to the provider (ADR 0014
`ASSIGNED -> CANCELLED: Provider permits`). Where the provider does not permit
it, the dialog says so and the cancellation still proceeds, raising a
`MANUAL_ACTION_REQUIRED` telling somebody to phone the courier service.

Bulk cancellation uses this same registry and the same approval thresholds,
applied per item.

### 4.6 Completion (ADR 0039, not built)

`POST .../completion`. Reason from the same registry with `kind = 'COMPLETION'`
— «Доставлен», «Забрали заказ», «Доставлен сторонней службой», «Самовывоз
выполнен» — filtered by `allowed_fulfillment_modes` and **validated on use**.
Without that filter «Самовывоз выполнен» lands on a delivery order and both the
courier SLA report and the external-logistics settlement quietly lose it.

Where exactly one reason is valid for the order's mode and no policy asks
otherwise, the action completes without a dialog. An operator confirming
«Доставлен» on every delivery three hundred times a shift is a dialog that
teaches people to click through dialogs.

### 4.7 Courier (ADR 0014 / 0042, not built)

- **Назначить курьера** — searchable picker showing name, vehicle, current
  load, distance, and shift state. A courier who is **off shift** appears with a
  hollow dot and is not selectable unless the location's policy allows
  out-of-shift assignment; the picker says which. Assignment is idempotent and
  audited; re-assigning is allowed and recorded, not blocked.
- **Снять курьера** — confirm, reason optional. The legacy dashboard had this
  (`confirmDetachCourier`) and staff expect it.
- **Вызвать службу доставки** — dispatches to the configured providers. Where
  the provider returns a quote that differs from the estimate, the operator sees
  a **quote-delta confirmation** before it books: *«Оценка была 22 000, служба
  вернула 31 000. Подтвердить?»* — the Millenium pattern, which is Delever's one
  genuinely good order-menu design and should be matched exactly.
- **Отменить у службы** — cascading cancel; states whether the provider permits
  it.

### 4.8 POS (ADR 0011 / 0012, not built)

**Печать** does not print locally; it sends a print request to the POS. Say so
in the label — **Печать в POS** — because an operator who thinks a printer is
attached will press it twice.

The action is **absent** where the location's POS binding declares no `print`
capability (ADR 0011 capability matrix, IA 3.2). Not disabled — absent. This is
the whole reason the capability matrix exists.

**Отправить в POS повторно** appears when `POS_ORDER_EXPORT` is
`FAILED_RETRYABLE` or `MANUAL_ACTION_REQUIRED`.

### 4.9 Payment (ADR 0013, not built)

- **Выставить счёт** — re-issue a payment invoice. Delever's own page documents
  the fields: phone, order id, payment type. Ours needs only the phone (the
  order is in context) plus the method, because the case it exists for is *the
  customer's payment account is registered to a different number*. Idempotent;
  a re-issue never creates a second charge. The action belongs on the order
  detail, **not** in a separate top-level tool as Delever has it — an operator
  who has the order open should not have to go find a form and retype its id.
- **Возврат** — ADR 0013 refund, with the reason and, above a threshold, ADR
  0027 approval.

### 4.10 Fiscal (ADR 0038, not built)

**Фискализировать** — `POST .../fiscal-document/retry`, reason required,
audited. Reuses the same document. Present only when the document is `FAILED`
or `BLOCKED`, and where the blocking reason is fixable in the console
(`CLASSIFICATION_MISSING`) the action is accompanied by a link that goes and
fixes it.

### 4.11 The action matrix

Read down the status, across the action. `●` available, `○` conditionally
available, blank omitted.

| | RECEIVED | PAY_AUTH | AWAIT_APPR | PAY_FAILED | CONFIRMED | PREPARING | READY | FULFILLING | COMPLETED | CANCELLED |
|---|---|---|---|---|---|---|---|---|---|---|
| Принять / Отклонить | | | ● | | | | | | | |
| На кухню | | | | | ● | | | | | |
| Готов | | | | | | ● | | | | |
| На доставку | | | | | | | ○ delivery | | | |
| Выдан / Доставлен | | | | | | | ○ pickup | ● | | |
| Отменить | ● | ● | ● | ● | ○ 0039 | ○ 0039 | ○ 0039 | ○ 0039 | | |
| Изменить состав / оплату / адрес | ○ 0039 | | ○ 0039 | | ○ 0039 | ○ 0039 | ○ cut point | | | |
| Комментарий кухне | ● | ● | ● | | ● | ● | ● | ● | | |
| Назначить / снять курьера | | | | | ● | ● | ● | ● | | |
| Вызвать службу | | | | | ● | ● | ● | ○ | | |
| Печать в POS | | | | | ● | ● | ● | ● | ● | |
| Выставить счёт | ● | ● | ● | ● | ● | ● | ● | ● | | |
| Возврат | | | | | | | | | ● | ○ |
| Фискализировать | | | | | ○ | ○ | ○ | ○ | ○ | ○ |
| Требуется звонок | ● | ● | ● | ● | ● | ● | ● | ● | | |

`REJECTED` and `EXPIRED` behave as `CANCELLED`. Every column with no `●`
renders no action group at all rather than an empty toolbar.

---

## 5. Screen 1.3 — New order (taking an order by phone)

### 5.1 What it is for

*A customer is on the phone. Get their order into the system before they hang
up.*

### 5.2 Layout

One screen, three panes, no wizard. A wizard is wrong here: the customer does
not supply information in order. They give a phone number, then two dishes, then
change the address, then a third dish, then ask the total. Every pane must be
reachable at any moment.

```
┌ Новый заказ ─────────────────────────────────── Черновик · 00:42 ── [Отмена][Создать]┐
├─ 320px ──────────┬────────── flexible ──────────┬────── 360px ──────────────────────┤
│ КЛИЕНТ           │ МЕНЮ                          │ ЗАКАЗ                             │
│  телефон 🔍      │  поиск позиции  F2            │  филиал ▾                         │
│  результаты      │  ─ категории ─                │  тип: доставка/самовывоз/зал      │
│  ┌ имя         ┐ │  сетка позиций                │  время: сейчас / ко времени       │
│  └ последний   ┘ │                               │  оплата ▾                          │
│                  │ КОРЗИНА                       │  промокод                          │
│ АДРЕС            │  позиция ×2  18 000  −/+ 🗑   │  сдача с                           │
│  сохранённые     │  модификаторы                 │  комментарий кухне                 │
│  + новый (карта) │  комментарий к позиции        │  ─────────────                     │
│  подъезд/этаж…   │                               │  итого        164 000              │
└──────────────────┴───────────────────────────────┴────────────────────────────────────┘
```

The phone field is focused on open. That single decision is worth more than
anything else on the screen.

### 5.3 Customer pane

1. **Phone.** Uzbek mask `+998 (__) ___-__-__`. On nine digits, fires
   `POST /api/v1/operations/customer-lookups` — body, never query string.
2. **Results.** Zero, one or several. The `normalized_hash` index is
   deliberately not unique: a household shares a phone and a recycled number
   changes owner. Each result shows masked name, last-order date and order
   count; picking one is a **selection, never a merge**. ADR 0015's prohibition
   on automatic phone-based merging is not relaxed here, and the screen offers
   no merge control.
3. **No match** → **Создать клиента**: name (first, last), phone prefilled. The
   account is created with `origin = 'OPERATOR'` and
   `created_by_actor_id`, has no principal link, an `UNVERIFIED` contact point
   and zero consent decisions. **It is non-contactable for marketing**, the
   panel says so, and there is no control to change that — whether an operator
   may take verbal marketing consent is an open legal input in ADR 0039 and
   until it is answered the UI offers nothing.
4. **История** — the legacy dashboard and Delever both put an order-history
   peek behind an icon next to the phone. Keep it: a popover of the last five
   orders with **Повторить**, which prefills the basket. It is the single
   fastest path to a complete order for a regular.
5. Every lookup is a `SECURITY`-class ADR 0027 audit fact. This screen is a PII
   surface pointed at the tenant's entire customer base, and it is auditable
   because of that, not despite it.

### 5.4 Address pane

Saved addresses from `customer.addresses` (label, masked line, coordinate
source). **+ Новый адрес** opens a map picker plus the structured fields this
market requires — дом, квартира, **подъезд**, **этаж**, **ориентир** — which
live inside `encrypted_fields`, not as columns (V0021).

An address with no coordinates is legitimate: `coordinate_source =
'NOT_GEOCODED'` with an ориентир is how a large share of addresses here work.
The form must accept it and the screen must not block on a map pin. Low-confidence
geocoding shows a warning and asks the operator to confirm the pin.

The address drives the branch: ADR 0037 zone resolution picks the location, the
branch selector shows the resolved one with **по зоне** beside it, and an
override is allowed and recorded. Out-of-zone shows the refusal `reason_code`
in words, not a silent empty menu.

### 5.5 Menu and basket

- Item search (`F2` from anywhere) over the location's published catalog, with
  the interactive category grid beneath as the fallback for a customer who is
  browsing aloud.
- **A sold-out item is visible and not addable.** `inventory.positions.
  binary_available = false` renders the item struck through with **стоп**
  beside it, plus the single "why can't I sell this?" explainer (IA 2.5).
  Hiding it makes the operator say "we don't have that on the menu", which is a
  different and wrong sentence.
- Modifier groups enforce their min/max at selection; required groups block the
  add.
- Per-line: quantity stepper, customer comment, remove. `+`/`−` act on the
  focused line.
- Auto-added items (IA 4.9 — packaging, cutlery) appear in the basket flagged
  **добавлено автоматически** and removable only where the rule allows.

### 5.6 Order pane

| Field | Source / rule |
|---|---|
| Филиал | resolved from the zone, overridable. **Changing it rebuilds the cart** — `ordering.reject_cart_rebinding()` refuses to move a cart between locations, so the UI must warn that prices and availability change, and re-price |
| Канал | fixed to the tenant's operator channel (`tenant.sales_channels`, `system_type` for the admin panel). Aggregator orders entered by hand set `entry_mode = 'MANUAL'` with no live binding — **not built — ADR 0040** |
| Тип | `fulfillment_mode`, offered only where `tenant.channel_fulfillment_modes.enabled` |
| Время | Сейчас / Ко времени. Out-of-hours shows the branch-resolution warning before it is committed, not after |
| Оплата | from `tenant.channel_payment_methods` intersected with the ADR 0030 operator policy — which methods an operator may offer is configurable and is not the same set the storefront shows |
| Промокод | when the operator policy permits |
| Сдача с | `SET_CASH_TENDERED`; shows change owed live. If a later amendment pushes the total above it, the operator gets `CASH_TENDERED_INSUFFICIENT` as an **acknowledgeable** notice, not a block — the customer can hand over more |
| Требуется звонок | toggle, when the policy offers it |
| Комментарий кухне | free text |
| Итого | the ADR 0018 quote, re-priced on every change, with the quote's 15-minute TTL visible as a countdown once it is priced |

**Создать** → `POST /api/v1/operations/orders` with `Idempotency-Key`,
capability `ORDER_PLACE` at `LOCATION` scope. On success, route straight to the
order detail — the legacy dashboard did exactly this (`navigate('/orders/'+id)`)
and it is right: the operator is still on the phone and needs to read the number
back.

### 5.7 States

| State | Rendering |
|---|---|
| Draft timer | Elapsed time in the header. A cart expires (`carts.expires_at`); at two minutes remaining the header warns and offers to extend by re-pricing |
| Quote expired | Basket stays, totals grey, **Пересчитать** — never silently re-price behind the operator |
| Item became unavailable | The line turns danger with **стоп** and the create button is blocked until it is removed. No partial reservation (ADR 0019) |
| Location closed | Warning at the branch selector with the next opening time, and the pre-order path offered |
| Out of zone | The refusal reason from ADR 0037 in words, plus the nearest serviceable branch if any |
| POS mapping failure | Surfaced **at creation**, with the specific cause (unmapped product, unmapped payment type, product inactive in POS) and a link to the mapping table. This is Delever's behaviour and it is correct; what Delever lacks is the same surfacing forty minutes later, which §3.11 adds |
| No capability | The New order button is absent from the board, not disabled |

### 5.8 Keyboard

`Tab` order follows the panes left to right. `F2` item search from anywhere.
`Enter` in item search adds the top hit at quantity 1 and returns focus to the
search field — so an operator can type four dishes without touching the mouse.
`Ctrl+Enter` creates. `Esc` in a pane returns focus to that pane's first field;
`Esc` twice prompts to discard.

---

## 6. Screen 1.4 — Drafts and abandoned carts

**Tier 2.** *Which baskets did people build and never finish, and where.*

A list over `ordering.carts` where `status IN ('ACTIVE','EXPIRED','ABANDONED')`
and `converted_order_id IS NULL`. Columns: cart id (mono), `created_at`, channel
(`channel_id` → display name), location, owner (account or **гость** via
`guest_reference_hash`), line count and first line, `expires_at`, `status`.
Above it, the abandonment breakdown **by channel** — which is the only reason
the screen exists, since the number a marketer wants is "the Telegram bot loses
40% of baskets", not a list.

Sort by `created_at` descending; this is a log, not a queue. Filters: period,
channel, location, owner type.

Actions: open the customer (where there is an account), and — where the tenant
has ADR 0044 campaigns — hand the cart to a recovery audience. **No** action
that converts a cart into an order on the customer's behalf: nobody agreed to
that basket.

Guest carts carry `guest_reference_hash`, a keyed hash and never the reference
itself, so a guest cart is not attributable to a person and the screen must not
pretend otherwise.

---

## 7. Screen 1.5 — Reservations

**Tier 3, and it should stay there.** Delever puts table booking under Orders
because its floor-plan model is thin. Qoida has no floor-plan entity at all —
ADR 0047 owns dine-in table service, sections, tables and time-interval
availability, and none of it is built.

When it lands: create with client name and phone (plus optional secondary
phone), optional comment, branch, date, from/to interval, table selection,
multi-table bookings, and an external reservation id shown as the human-facing
identifier. Unknown phone auto-creates a customer under the same
`origin = 'OPERATOR'` rules as §5.3.

The list is a day view per branch, not a table — a booking's shape is a time
interval and a lattice reads it faster (Togora §2g, the slots grid: dashed
outlines for free capacity, occupancy in the column header, and the `isToday`
guard so tomorrow's plan never shows "past").

---

## 8. What Delever has that we should match

| Capability | Verdict | Why |
|---|---|---|
| Status tabs with live counts | **Match**, and add the Внимание tab | Delever's tabs are a status partition; the first question is severity |
| Filter vocabulary reused across list and reports | **Match exactly** | A manager who filters the board and opens a report should keep the selection |
| Search resolving aggregator ids | **Match, and beat** | ADR 0040 normalises and searches across providers; Delever's is per-provider |
| Bulk courier assignment | **Match, and beat** | Delever ships select-and-save; ADR 0039 adds per-item idempotency and a partial-failure result panel |
| Late-order threshold | **Match** the threshold | It is the right primitive |
| Late-order admin-chosen colour | **Skip** | Severity colour is semantic; a tenant picking green for "late" breaks every screen |
| Millenium quote-delta confirmation | **Match exactly** | Estimate → re-quote → operator accepts or abandons is the best thing in Delever's order menu |
| Cancellation reasons with dual internal/customer text | **Match** | ADR 0039 carries both, plus a system category so cross-tenant reporting survives fifty near-duplicates |
| Completion reasons | **Match**, and add `allowed_fulfillment_modes` | Without the filter, «Самовывоз выполнен» lands on a delivery order and the SLA report loses it |
| Cancellation write-off as an operator checkbox | **Beat** | ADR 0039: the disposition lives on the reason an admin set once and finance can audit |
| Manual fiscalization | **Match**, one document only | Two receipts for one order can only be corrected, never deleted |
| Print to POS | **Match**, hidden by capability | ADR 0011's capability matrix exists so operations can hide what a tenant's POS cannot do |
| POS errors at creation | **Match, and beat** | Delever surfaces them in a toast at creation; §3.11 surfaces the same failure forty minutes later, which is when it actually hurts |
| Kitchen comment | **Match** | `SET_KITCHEN_NOTE` |
| Re-issue payment invoice | **Match**, but on the order | Delever makes it a separate top-level tool that asks you to retype the order id you already have open |
| Order status timeline | **Match, and beat** | Delever renders time-per-stage; ours renders three lanes and shows losing approval decisions |
| Twelve-item overflow menu | **Beat** | §2.9: two inline affordances chosen by state, everything else in the overflow |
| Free-form «Изменить заказ» | **Beat** | ADR 0039's ten intent-named commands, each with a declared consequence vector |
| Multi-branch `steps[]` | **Skip** | Declined in the matrix; two nested state machines for a behaviour no evidence shows anyone using |
| Tenant-editable order statuses | **Skip** | The same status name would mean different things in two tenants, and every report and automation becomes ungovernable |
| Externally computed «Свободная скидка» on our own channels | **Skip on Qoida channels, allow on marketplace** | It exists so aggregators can push a discount we cannot re-derive. On an aggregator order that is unavoidable and flagged; extending it to our channels destroys ADR 0018's central promise |
| Live courier map as an Orders sibling | **Move** | It is IA 3.2, under Delivery. An operator working the queue does not need a map; a dispatcher does |
| Table reservations under Orders | **Move to tier 3** | ADR 0047, and it needs a floor-plan model nothing has |
| Live dashboard counters | **Move** | IA 0.1 Live board, on the wallboard shell where a shift supervisor can see them from across the room |

---

## 9. What the legacy dashboard did that staff will expect

Read from `legacy-archive/qoida-dashboard/src`.

| Legacy behaviour | Where it went |
|---|---|
| Sidebar status counts (`new`, `cooking`, `ready`, `delivering`, `completed`, `cancelled`), `1000+` capped | Tab counts, §2.3 — same numbers, closer to the work |
| Filters persisted per status in `localStorage` | §2.4, plus query params so a link now carries the filter |
| Company (brand) multi-select — five hardcoded brands | Brand is above the operations app now; the equivalent axis is Филиал and Канал. A multi-brand tenant selects brand in the app shell |
| Platform multi-select (android / ios / web / telegram / support) | Канал filter over `tenant.sales_channels.system_type` |
| **Мои заказы** toggle | §2.4 secondary row, on `created_by_actor_id` (ADR 0039) |
| Editable page-number input with total pages | Kept. Operators navigate by page number and typing `47` beats clicking Next forty-six times |
| Manual refresh button and "Updating…" text | Kept as the refresh stamp, §1.6 |
| Copy-phone clipboard button | Kept, now performing an audited reveal, §1.5 |
| Create order → pick company → search customer → *"Добавить клиента: +998…"* as the last option in the dropdown | Kept as the shape of §5.3. Offering "create this one" as a search result rather than a separate button is genuinely good and is not in Delever |
| Order detail: three note channels | §3.6 — two of the three have no owner yet, named in §11 |
| `Посуда` (packaging) as its own money line | §3.5, as a `FEE` adjustment with its own row |
| Both "before discount" and "after discount" for subtotal and delivery | §3.5 |
| Attach / detach courier with a confirm on detach | §4.7 |
| Open the address in Yandex Maps in a new tab | §3.8, secondary affordance |
| Backward status buttons, one click, no reason | **Not reproduced.** §0.2 — the honest replacement is a declared compensating transition under `ORDER_STATE_OVERRIDE`, and it does not exist yet. Expect complaints |
| Operator-editable `payment_status` and `payment_transaction_id` | **Not reproduced.** §3.9 — it let an operator mark an unpaid order paid with no evidence |
| Add/remove products on a `new` order only | Widened by ADR 0039 to the amendment cut point, with consequences declared |
| Cancel with a free-text reason | Replaced by the versioned reason registry, §4.5. Free text stays as an optional note |
| `Заказ не найден` on 404 | Kept |

---

## 10. Permissions

| Capability | Grants |
|---|---|
| `order.read` | The board and the detail, at `LOCATION` scope |
| `order.place` | New order (§5) |
| `order.approve` | Принять / Отклонить |
| `order.advance` | The kitchen path |
| `order.cancel` | Cancellation |
| `order.state.override` | Compensating transitions — **not yet declared by the state machine**, §0.2 |
| `customer.pii.reveal` | Phone, address and note reveal, with a stated purpose |
| `customer.read` | The customer panel and the lookup |

All at `LOCATION` scope: a branch manager approving their own branch's orders
must not need a grant reaching the whole brand, and the ADR 0025 build gate
enforces that the declared scope is no wider than the path.

A denied action is **absent**, not disabled. A denied *screen* renders its frame
and says which capability is missing and at which scope — a blank page teaches
nobody who to ask.

---

## 11. Data the backend does not have yet

Named precisely, with the owning decision. Everything not listed here exists in
`V0022` or earlier and can be built against today.

| Missing | Owner | Blocks |
|---|---|---|
| `order_revisions`, `order_amendments`, `order_amendment_commands`, `order_lines.revision_from/to` | ADR 0039 | Every amendment; §4.4 |
| `order_outcome_reasons` (+ `_texts`), `order_outcomes` | ADR 0039 | Cancellation and completion reasons, the write-off, the liability party; §4.5, §4.6 |
| `orders.created_by_actor_type/id`, `accepted_by_actor_type/id`, `accepted_at` | ADR 0039 | Мои заказы filter, the Создал/Принял columns, operator leaderboards; §3.12 |
| `orders.callback_requested`, `callback_resolved_at/by` | ADR 0039 | The callback flag and its filter |
| `orders.cash_tendered_expected_minor` | ADR 0039 | Сдача; §3.5, §5.6 |
| `bulk_operations`, `bulk_operation_items` | ADR 0039 | Bulk result panel and safe re-run; §2.10 |
| `customer_accounts.origin`, `created_by_actor_id` | ADR 0039 (extends 0015) | Operator-created customers and their marketing suppression; §5.3 |
| Payment method on the order, transactions, refunds, invoice re-issue | ADR 0013 | The Оплата panel beyond the projection; §3.9, §4.9 |
| `fiscal.fiscal_documents`, `_lines`, `_unit_marks` | ADR 0038 | The Фискализация panel, the fiscal chip, manual retry; §3.9, §4.10 |
| `fulfillment.delivery_plans` (`promised_delivery_start/end`, `estimated_ready_at`, `customer_delivery_fee_minor`), `shipments`, `delivery_quotes`, `assignment_attempts` | ADR 0014 | The promise clock, **the whole late overlay**, courier assignment, provider dispatch, the quote-delta confirmation; §2.7, §3.8, §4.7 |
| `fulfillment.delivery_fee_resolutions`, `service_zones` | ADR 0037 | Zone, distance, the fee explanation, address→branch resolution; §3.8, §5.4 |
| `ordering.orders.origin`, `pricing_authority`, `entry_mode`, `marketplace_binding_id`; `order_external_references`; `order_external_pricing`; `order_handover_challenges` | ADR 0040 | Aggregator id search, externally priced orders, the handover code; §2.8, §3.5, §3.8 |
| Kitchen ticket and station state | ADR 0041 | The production lane of the timeline; §1.2, §3.10 |
| `courier_shifts` and shift enforcement on assignment | ADR 0042 | The off-shift courier state; §4.7 |
| `GET /operations/streams` and the `ORDER_QUEUE` / `ORDER_DETAIL` / `COUNTERS` channels | ADR 0045 | Live counts and live rows; §1.6 |
| POS export driven at all — `order_process_states.POS_ORDER_EXPORT` is recognised by the schema and written by nothing | ADR 0011 / 0012 | Печать в POS, resend, the amendment interlock; §3.11, §4.8 |

Three things that have **no owning decision at all**, and each is a genuine gap
rather than an unbuilt one:

1. **Lateness as a defined projection.** The promise (ADR 0014) and the
   threshold (ADR 0030 key `ordering.lateness`) both have homes; the derived
   `LATE` / `AT_RISK` levels, their precedence against `BLOCKED`, and the rule
   that terminal orders are never flagged, do not. Togora's report already names
   this: a "threat" is a computed comparison of a promise time, a live estimate
   and a boundary, and Qoida has no such concept. §2.7 specifies it; something
   must own it.
2. **The operator→courier note and the internal operator note.** ADR 0039's
   command set is closed and carries only `SET_KITCHEN_NOTE`. The legacy
   dashboard had three note channels and staff use all three. Adding two
   commands is a one-line ADR amendment; discovering the omission after cutover
   is a regression report. §3.6.
3. **Compensating transitions.** §0.2. `OrderStateMachine` declares no backward
   edge and `ORDER_STATE_OVERRIDE` exists as a capability with nothing to
   authorise. Either the machine declares `READY -> PREPARING` and
   `FULFILLING -> READY` as `OPERATIONS_ACTION` transitions, or the IA's promise
   of gated backward transitions is withdrawn. It cannot stay as it is.

---

## 12. Notes on the sources

### 12.1 What Delever's documentation actually says

Fetched 22.08.2026. The parity matrix's caveat is accurate and, if anything,
understated.

- `user-guide/admin-panel/orders` is a **navigation index**. It contains no
  column list, no status tabs, no filters and no action-menu enumeration.
- `orders/create` is **three embedded videos** with four sentences of text. The
  four sentences are worth having and are used in §5: lookup by phone with
  auto-create on no match; address by map or search with дом / квартира /
  подъезд / этаж / ориентир; items by search or interactive menu; order type and
  pre-order time; payment type; an order-history icon beside the phone.
- `orders/additionally` enumerates the overflow menu and is **the only page
  that does**. Twelve entries, eleven of them video-only: change payment type,
  add order, edit order, assign courier, kitchen comment, **print** (the one
  with real text — "the request is sent to the POS system"), complete process,
  cancel order, Yandex Delivery, call courier, cancel in Millenium, attach
  Millenium with an estimated cost shown for confirmation.
- `orders/sendinvoice` is the one page with usable text: phone, order id,
  payment type, Send.

So: the twelve-item menu is confirmed from the source, and everything about the
list — columns, tabs, filters, the state machine — remains a reconstruction from
the reports, settings and integrator-API pages. Nothing in this specification
should be defended on the grounds that "Delever does it", except the twelve menu
entries, the invoice fields, and the create-order sentences above.

### 12.2 Delever's order UX, judged

The menu is the design. Twelve entries in one flat list means the operator's
most frequent action — accept this order — costs the same two clicks as
"cancel the Millenium delivery", which they will do twice a month. Nothing on
the row tells them what the order needs; the menu is the same on every row
whatever its state, so learning it is memorising twelve positions rather than
recognising a situation.

The three that belong on the main path, because they are what an operator does
hundreds of times a shift and each is state-determined:

- **accept / reject** at `AWAITING_APPROVAL`,
- **advance** along the kitchen path,
- **assign a courier** at `READY`.

Three more belong one click away, in the overflow, because they are frequent but
not per-order-per-shift: cancel, add items, change payment.

The remaining six belong three clicks away, on the detail, next to the thing
they act on: fiscalize belongs beside the fiscal status; print and resend belong
beside the POS export state; cancel-at-provider and attach-provider belong
beside the shipment; re-issue an invoice belongs beside the payment. Delever
puts them all in the same list at the same distance, and the cost of that is
paid by the operator every time they open it looking for the one they want.

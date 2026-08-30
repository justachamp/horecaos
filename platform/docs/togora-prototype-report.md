# Togora prototype — extraction report

What the Togora admin-panel prototype does, what HorecaOS should take from it, and
what it should not. Source: `legacy-archive/togora-admin-panel/togora`, read in
full — roughly 4 200 lines across nine files.

Read alongside [the frontend README](../frontend/README.md), which states the
prototype contract, and
[the information architecture](frontend-information-architecture.md), which holds
the screen inventory.

## Why this document exists

Togora is a prototype for a comparable product built by the same team. Its
engineering pattern is the fastest way to a reviewable console, and the HorecaOS
control-plane prototype adopts it. Its visual language is the opposite of the
HorecaOS design system's, and the prototype adopts none of it. Separating those two
judgements is the point of the report.

---

## 1. Engineering pattern

### Stack and build

Vite 7 + React 19. Two runtime dependencies: `react`, `react-dom`. No router, no state library, no CSS framework, no component library, no TypeScript. `vite.config.js` sets `base: '/INSALE/'` on build — a GitHub-Pages-style deploy path, revealing the prototype was shipped as a static review artefact.

`index.css` is 23 lines: a global `* { margin:0; padding:0; box-sizing:border-box }` reset plus exactly three real rules — `.header-slot.past/.current/.future`, which exist only because the slots grid header needs `!important` to beat an inline style. **Everything else in 4200 lines is an inline `style={{}}` object.** That is the single most important structural fact: there are no classes, no cascade, no specificity questions, and every visual decision is co-located with the markup that uses it.

### State organisation

All state lives in `App.jsx` as 33 `useState` hooks, grouped by literal comment banners:

```
/* ═══ GLOBAL STATE ═══ */   activeMenu
/* Orders */                 ordersSub, selectedOrderId, listFilter, dateFilter,
                             calendarDate, stageFilter, processDateFilter, ... (14)
/* Merchants */              merchantsSub, merchantsFilter, selectedMerchantId,
                             showAddMerchant, merchantTab, deleteReqDialog, ... (18)
/* Couriers */               couriersSub, couriersFilter, selectedCourierId,
                             mapFilter, selectedPin, mapZoom, mapPan, ... (11)
/* Showcase */               showcaseFilter, showcaseCat
```

Each is handed down as an explicit `value`/`setValue` pair. `Orders` takes 18 props, `Merchants` 25, `Couriers` 17 — all written out longhand at the call site. No context, no reducer, no memoisation.

There is exactly **one** piece of state that does not live in `App`: `slotDialogOrder` in `Orders.jsx:62`. It is transient, nothing else reads it, and it is correctly local. That single exception shows the author understood the rule and broke it deliberately.

Naming convention is consistent and worth noting: `selected*Id` for master-detail, `*Sub` for sub-navigation, `*Filter` for filter axes, `*Modal`/`*Dialog` for overlays, `show*` for booleans.

### Navigation without a router

Two-level discriminated state. `activeMenu` picks the section; a per-section `*Sub` picks the view. `data.jsx:296 menuItems` **is** the routing table:

```js
{ id: "orders", icon: "📋", label: "Заказы", sub: [
  { id: "list", label: "Список" }, { id: "process", label: "Процесс" },
  { id: "slots", label: "Слоты" } ]}
```

Three rules make it behave like a router:

1. **Clicking a top-level item resets that section's sub-view to its first child** — `if (item.id === "orders") setOrdersSub(item.sub[0].id)`. Re-entering a section is always a clean entry.
2. **Clicking a sub-item clears that section's selected entity** — `setOrdersSub(sub.id); setSelectedOrderId(null)`. "Route change deselects the detail," stated explicitly rather than emerging from unmounting.
3. **Sub-menus render only for the active section** (`item.sub && isActive`), so the rail never shows more than one expanded tree.

Cross-section navigation is a multi-setter call, and the *full* route must be set:

```js
// Couriers.jsx — courier's current order → order detail
setActiveMenu("orders"); setOrdersSub("list"); setSelectedOrderId(cd.currentOrder.id);
```

Sections receive `setActiveMenu`/`setOrdersSub`/`setSelectedOrderId` as props purely to enable these jumps. `Merchants` and `Couriers` get them; `Showcase` does not.

### Section decomposition

Each section is a single default-export function containing an **ordered chain of guard clauses**, each returning a complete screen. Precedence is encoded by ordering.

`Orders.jsx`:
```
if (selectedOrderId)                            → detail       (beats everything)
if (ordersSub === "list" && !selectedOrderId)   → list
if (ordersSub === "process")                    → pipeline cards
if (ordersSub === "slots")                      → capacity grid
return <fallback/>
```

Because the detail guard comes first, selecting a row is a modal-like route with no route. The list's filter state is untouched, so returning restores it for free.

Three variants of the same shape:

- **`Orders.jsx`** — everything inline in the dispatcher; two small local components hoisted above it (`OrderStatusBadge`, `DateFilterRow`).
- **`Merchants.jsx`** — fully factored into named components (`MerchantsList`, `AddMerchantDialog`, `MerchantDetail`, `MerchantsLoad`) with the dispatcher at the *bottom*, forwarding wholesale: `if (selectedMerchantId) return <MerchantDetail {...props} />`.
- **`Couriers.jsx`** — hybrid. `renderAddCourierModal()` is a `function` declaration placed *after* its first call site and invoked from two branches, so the modal can appear over both the list and the detail. Relies on hoisting; deliberate.

### The screen frame

Repeated verbatim seven times, never abstracted:

```jsx
<div style={{ background:"#fff", padding:"14px 28px",
              display:"flex", alignItems:"center", justifyContent:"space-between",
              borderBottom:"1px solid #E5E7EB" }}>
  <div>
    <div style={{fontSize:18, fontWeight:700}}>Заказы</div>
    <div style={{fontSize:11, color:"#9CA3AF"}}>Все заказы · {ordersList.length}</div>
  </div>
  {/* one primary action OR one search input */}
</div>
<div style={{ flex:1, overflow:"auto", padding:"20px 28px" }}>…</div>
```

Header is fixed, body scrolls. The subtitle is always `label · count`. Exactly one right-hand affordance.

### Shared component contract (`components.jsx`, 111 lines)

Five tokens, four style objects, five components, one helper. That is the entire design system.

```js
export const font = "'DM Sans', sans-serif";  export const mono = "'JetBrains Mono', monospace";
export const dark = "#1A1A2E";  export const muted = "#6B7280";  export const borderC = "#E5E7EB";

export const lblS = {...};  // form label
export const inpS = {...};  // input
export const selS = { ...inpS, background:"#fff" };
export const thS  = {...};  // <th>
```

Style objects are consumed by spread, which is how the contract stays open: `style={{...thS, width: 40}}`, `<Card style={{padding:0, overflow:"hidden"}}>` (Card merges `...style` last, so callers always win).

The colour system is two lookup maps **keyed by the Russian display string**:
```js
orderStatusMap["На доставке"] → { bg:"#EFF6FF", color:"#2563EB" }
statusColors["Ожидает оплаты"] → { bg, text, dot }
```
Status is a string, not an enum; the map is the whole type system. Every consumer does `map[s] || {bg:"#F5F5F5", color:"#6B7280"}` — an unmapped status degrades to grey rather than crashing.

Components:

| Component | Contract | Note |
|---|---|---|
| `Badge({status})` | dot + pill; looks its own colours up | Caller passes only the string |
| `SectionHeader({icon, title, accent, right})` | icon chip + title + optional right slot | `right` is where a collapse toggle or button goes |
| `Field({label, value, mono, wide})` | uppercase caption over a value | `wide` = `flex:"1 1 200px"` |
| `Card({children, style})` | white, 14px radius, two-layer shadow | **Swallows every prop except `children`/`style`** |
| `FR({items})` | flex-wrap row of `Field`s from a data array | The load-bearing one |

**`FR` is the highest-leverage idea in the codebase.** Every detail pane is authored as data, not markup:

```jsx
<FR items={[
  { label:"Способ",   value: o.payment.method },
  { label:"Товары",   value: fmtMoney(o.payment.itemsTotal), mono:true },
  { label:"Доставка", value: fmtMoney(o.payment.deliveryFee), mono:true },
  { label:"Итого",    value: fmtMoney(o.payment.total), mono:true },
]} />
```

That is why a 1143-line file remains readable. It also means adding a field is a one-line data edit, which is exactly what a design prototype needs during review.

**Real bug worth naming:** `Card` accepts only `children` and `style`. `Couriers.jsx` writes `<Card style={{…}} onClick={e => e.stopPropagation()}>` inside the add-courier overlay — the handler is dropped, so clicking *inside* that modal closes it. `Merchants.jsx` avoids this by wrapping in an `Overlay` whose inner `<div>` stops propagation. Any shared wrapper must spread `...rest` or this recurs.

### Fixture data shape (`data.jsx`, 543 lines)

Three deliberately different shapes:

1. **One deep singleton per detail screen.** `ORDER` is a single fully-populated order with nested `merchant`, `client`, `dispatch`, `items[]`, `orderGabarit`, `courier`, `payment`, `delivery`, `risks[]`, `incident`, `threat`, `history[]`. The detail view renders it *regardless of which row was clicked* (`const o = ORDER;`). `getMerchantDetail(id)` and `getCourierDetail(id)` are the same trick made honest — functions that splice the clicked id into an otherwise constant object: `({ id: selectedMerchantId, brand: "Pizza Roma", … })`. One rich record beats fifty shallow ones for design work.

2. **Flat arrays for tables** — `ordersList` (100+), `merchantsTableData` (12), `couriersTableData` (8), `showcaseProducts` (14), `moderationData` (4), `couriersWorkData` (7).

3. **Purpose-built projections** where a screen needs a different shape: `processOrders` (stage indices + parallel timestamp arrays), `merchantsLoadData` (`{name, cap, orders:[{start:[h,m], end:[h,m], label}]}`), `mapCouriers` (lat/lng + from/to + speed).

**The most transferable authoring habit:** `ordersList` is written backwards from the visual states the slots grid must produce, with block comments naming each:

```
// FULL WINDOW: 14:00–15:30 — fill all 20 lines (green)
// PARTIAL: 11:00–12:30 — 5 orders
// PARTIAL: 17:00–18:30 — 3 orders (already have 1: ORD-284721)
```

The fixture is a test matrix. It also includes genuine outliers — `4 500 000 сум` beside `36 000`; `МАКС ВИП ТОГОВРА` beside `KFC`; a 1840-product merchant beside a 15-product one — so layouts break during design rather than during build.

Stage vocabularies are exported arrays of `{key, label, color}`; progress is an **integer index** into the array with a **parallel sparse timestamp array** where `null` means "not yet reached":

```js
{ mStage: 5, lStage: 4,
  mDates: ["14:32","14:35","14:38","14:45","15:02","15:10"],
  lDates: ["14:33","14:36","14:40","15:05","15:12", null] }
```

Cheap to author, renders directly, no date parsing anywhere.

---

## 2. Interaction patterns worth stealing

### a. Master-detail as state precedence

`if (selectedOrderId) return <detail/>` before any list branch. Back is `setSelectedOrderId(null)` rendered as a text link *above* the title, not a chrome button. Because filters live in `App`, they survive the round trip.

*Problem solved:* drilling in and back out of a filtered queue fifty times a shift without re-establishing context.
*HorecaOS:* control-plane 2.1→2.2 (tenant directory→overview), 3.3 installations explorer, 7.5 audit log, 9.1 migration runs. In Angular use real routes so deep links and browser-back work, but **keep filters in query params** so the survival property is preserved.

### b. Filter bar: one row, mixed controls, counts inside the control

The orders list puts status pills, a 1px vertical divider, and date pills in a single wrapping flex row. `Календарь` is a pill that *reveals* a native `<input type="date">` beside it rather than opening an overlay.

Where cardinality is high, the pill row degrades to a `<select>` that signals its own filtered state (`Orders.jsx:590`):

```js
border: stageFilter !== "all" ? `2px solid ${…}` : `1px solid ${borderC}`,
background: stageFilter !== "all" ? (stageFilter === "incident" ? "#FEF2F2" : …) : "#fff",
```

…and the option labels carry **live counts computed before filtering**, with `<option disabled>` used as group separators:

```
Статус: Все (8)   Инцидент (2)   Угроза (3)
── 🏪 Мерчант ──   Заявка (1)  Согласовано (1)  …
── 🚚 Логистика ── Слот (1)   First Pick (0)  …
```

*Problem solved:* the operator sees where the work is before choosing a filter, and counts don't collapse as the selection narrows.
*HorecaOS:* IA 1.1 already requires live per-status counts on order-board tabs. Apply the same to 4.2 dead letters (counts per failure cause), 3.3 installations (per credential status), 6.1 fiscalization (per operator/cause). Keep the divider-grouped single row and the "this control is filtering" visual state.

### c. Two filter axes given different visual weight

`Showcase.jsx` stacks status filters (dark fill = primary axis) above category filters (amber outline, smaller = secondary axis). Different treatment says "different axis" rather than "one long wrap".

*HorecaOS:* catalog products (category × status), audit log (actor type × outcome), provider registry (category × lifecycle state).

### d. Row severity on three channels simultaneously

Every orders-list row carries background tint **and** a 4px left border **and** a caption line under the id with the actual reason text:

```js
getRowBg      = o => o.incident ? "#FEF2F2" : o.threat ? "#FFFBEB" : "#fff";
getRowBorderLeft = o => o.incident ? "4px solid #DC2626"
                      : o.threat  ? "4px solid #F59E0B" : "4px solid transparent";
```
```jsx
{o.incident && <div style={{color:"#DC2626"}}>{o.incidentText || "Инцидент"}</div>}
{!o.incident && o.threat && <div style={{color:"#D97706"}}>{o.threatText || "Угроза"}</div>}
```

Note the strict precedence — incident outranks threat, and the threat caption is explicitly suppressed when an incident is present. The transparent left border keeps normal rows aligned with flagged ones.

*Problem solved:* the operator learns *why* a row is flagged without opening it. A bare severity badge would not.
*HorecaOS:* late-order highlight (1.1), fiscalization failures (6.1), stop-listed products (the prototype already does this in `Showcase.jsx` and the products tab: `borderLeft:"3px solid #F59E0B"` + `СТОП` beside the SKU), expired credentials (3.3).

### e. Sort by severity, not by time

```js
const w = o => o.incident ? 0 : o.threat ? 1 : 2;
sortedProcess = [...filteredProcess].sort((a,b) => w(a) - w(b));
```

The queue orders itself by what needs a human, after filtering.

*HorecaOS:* dispatch board, 10.2 tenant issue queue, 4.2 dead letters, 6.1 fiscalization.

### f. Dual parallel pipelines on one row — the most HorecaOS-relevant widget here

Each process card shows the **kitchen pipeline and the logistics pipeline side by side**, as two independent dot-and-connector strips with separate stage vocabularies and separate current indices.

Rendering rules (`Orders.jsx:740`): filled = solid colour + `✓`; current = 18px hollow ring, 3px coloured border, `0 0 0 3px ${color}33` halo, inner dot, and the **stage label printed underneath only for the current stage**; future = `#E5E7EB`. The connector segment takes the *next* stage's colour, so colour flows forward. Hovering a filled dot shows `label — HH:MM` from the parallel `mDates`/`lDates` array via a plain `title` attribute.

*Problem solved:* it makes visible that production and delivery are **separate clocks that can disagree** — precisely the distinction between "the merchant is late" and "the courier is late", which a single linear status bar destroys.
*HorecaOS:* IA 1.2 explicitly requires "status timeline with per-stage clocks"; 2.1/2.3 kitchen; ADR 0041 production routing. Build this. Render with squares and hairlines instead of circles-with-halos; **put the timestamp under every completed stage as visible text, not in a `title`** — a `title` tooltip is not keyboard-reachable.

### g. The slots grid — capacity as a fixed-size lattice

`Orders.jsx:806`. Columns = 12 time windows (`05:00–06:30` … `21:30–23:00`), rows = 20 fixed capacity "lines". Orders are packed into the first free line:

```js
const grid = timeWindows.map(() => Array(LINES).fill(null));
slotOrders.forEach(o => {
  const wIdx = timeWindows.indexOf(o.slotTime); if (wIdx === -1) return;
  for (let l = 0; l < LINES; l++) if (!grid[wIdx][l]) { grid[wIdx][l] = o; break; }
});
```

Empty cells render a **dashed outline**, not blank — you see the shape of unsold capacity, which is the actual planning question. Column headers carry three stacked rows: the window, a phase label (`завершён` / `сейчас` / nothing), and occupancy `12/20` tinted green at full and red at zero. Whole columns tint green when full and red when empty.

Time phase is computed against the wall clock **and only when the selected date is today**:

```js
const getSlotPhase = (windowStr) => {
  if (!isToday) return "future";     // tomorrow's plan never shows "past"
  if (nowMinutes >= endMin) return "past";
  if (nowMinutes >= startMin) return "current";
  return "future";
};
```

That `isToday` guard is the detail that makes the widget correct rather than merely pretty.

*HorecaOS:* this shape is missing entirely. ADR 0014 covers scheduled delivery sourcing but there is no capacity lattice in the schema or the IA. It would serve 3.6 delivery zones, 3.8 dispatch rules, 3.5 courier shifts, and generalises directly to 2.6 kitchen capacity ("max preparations per hour per product per branch").

### h. Modal ladder — three depths, three sizes, one overlay

| Depth | Width | Content |
|---|---|---|
| Confirm | 400 | One sentence naming the object and stating irreversibility, Cancel / Confirm |
| Record | 500–600 | Read-only `FR` grid or a flat edit form |
| Create | 560 | Sectioned form, uppercase section labels, 2-col grid, footer border, Cancel / Create |

One `Overlay` (`Merchants.jsx:47`): `position:fixed; inset:0; rgba(0,0,0,0.5)`, click-outside closes, inner `onClick={e => e.stopPropagation()}`.

**Modal state is the id of the record being acted on, not a boolean:** `deleteReqDialog = "REQ-01"`, `reqModal`, `netModal`, `productModal`, `slotDialogOrder`. "Which one" and "is it open" become one variable, and the confirmation copy can name the object: *"Реквизит REQ-01 будет удалён. Это действие нельзя отменить."*

*HorecaOS:* adopt the id-as-modal-state convention wholesale. The confirmation copy pattern fits every destructive platform action — secret rotation (7.4), selective replay (4.2), retention override (6.5), residency change.

### i. Peek modal from a dense cell

A slot cell shows only `#284719`. Clicking opens a 420px card: id + slot line, status badge, a 2×3 label/value grid, incident/threat callout blocks, and one primary button `Открыть заказ` that closes the peek and navigates to the full detail. The dense grid stays dense; drill-down is a two-step ladder.

*HorecaOS:* dispatch board cells, capacity lattice cells, live-board tiles, ID mapping explorer (9.2).

### j. Aggregate counts are links to the view that produced them

`Merchants.jsx:138` — the `branches` count in the merchants table is clickable and jumps to that merchant's **Nets tab**; the `products` count jumps to the **Products tab**; both call `e.stopPropagation()` so the row's own click doesn't win:

```jsx
<span onClick={e => { e.stopPropagation();
                      setSelectedMerchantId(m.id); setMerchantTab("nets"); }}>{m.branches}</span>
```

*Problem solved:* a number in a table always raises "which ones?" — this answers it in one click without a second screen.
*HorecaOS:* this is the whole cross-module navigation problem (installation → tenant → brand → provider). Every aggregate in the control plane — open issues, failed messages, entitlement count, location count — should link to the filtered or tabbed view behind it. And the lesson from `setActiveMenu + setOrdersSub + setSelectedOrderId`: a jump must set the **complete** route, never a partial one.

### k. Tab group as a header extension

`Merchant detail` has nine tabs (`Основное, Реквизиты, Сети, Медиа, Витрина, Документы, Финансы, Заказы, Пользователи`) rendered inside the white header block, below the entity identity line, as 2.5px bottom-border underlines. The active tab lives in `App` state and is reset explicitly on the back link (`setSelectedMerchantId(null); setMerchantTab("main")`) rather than implicitly by unmount.

*HorecaOS:* 2.2 tenant overview, provider detail, 1.2 order detail. Nine is the observed ceiling, not a target.

### l. Heat-strip timeline with an overlap tooltip

`MerchantsLoad` (`Merchants.jsx:763`) discretises 08:00–21:00 into 52 × 15-minute cells per merchant. For each cell it counts orders whose interval overlaps the cell, divides by that merchant's `cap`, and buckets into four colours with a legend (`0–30% Низкая` … `85%+ Перегрузка`):

```js
if (oStart < slotEnd && oEnd > slotStart) { count++; overlapping.push(o); }
return { count, ratio: count / merchant.cap, overlapping };
```

The row label shows `пик: n/cap` coloured by the same thresholds. Hovering a cell shows a dark tooltip listing **the specific overlapping orders** with their windows — the tooltip is rendered as an absolutely-positioned sibling *outside* the `overflow:hidden` strip, which is the real technique for this widget class. Hour grid lines are drawn as absolutely-positioned 1px divs behind the cells.

*HorecaOS:* 2.6 kitchen capacity, per-branch load in the branch picker (0.1), 3.5 courier shift coverage, and — highest value on the platform side — 4.1 message flow showing per-tenant integration throughput against rate-limit ceilings.

### m. Fake map with a real interaction model

`Couriers.jsx:756`. `toX(lng)/toY(lat)` linearly project a fixed lat/lng bounding box into percentages. Layers, bottom to top:

1. SVG dashed grid at 10% intervals
2. SVG road lines and building-block `<rect>`s
3. rotated street labels (`transform: rotate(90deg)`, `pointerEvents:"none"`)
4. SVG route lines — **blue from origin to the courier's current position, green from current position to destination**, with endpoint circles
5. avatar pins bordered 3px in the courier's status colour, with a name chip beneath
6. the selected-pin popover, **clamped to stay on canvas**: `left: ${Math.min(Math.max(px,10),75)}%`

Zoom is `transform: scale()` on a wrapper with +/− buttons and a `1.25x` mono readout. Pan is a manual `mousedown` → `window.addEventListener("mousemove")` closure that unregisters on mouseup. An `Анимация` toggle drives an 800ms `setInterval` tick; positions interpolate `lat + (to.lat - lat) * ((tick * speed) % 1)`.

*HorecaOS:* 3.2 live map needs real tiles, but **the layer order, the two-colour route split at the current position, status-as-pin-border, and the clamped popover transfer directly**. The animation toggle is worth keeping in any prototype: it lets a reviewer see motion without a backend.

### n. Inline actions on a review queue, derived from state

`Moderation.jsx` is a card-per-application list. Pending rows get a 4px amber left border and Approve/Reject buttons; decided rows show a badge and **no buttons at all** — affordances are omitted rather than disabled.

*HorecaOS:* 6.5 approvals, 8.4 template moderation, 2.5 onboarding blockers. Better sourced: IA 1.2 already specifies a server-supplied `actions[]` capability array driving which affordances render — same principle, correct origin.

### o. Stat tiles derived from the table's own data

`Merchants.jsx:60` computes four tiles by `reduce` over `merchantsTableData` — count, total products, total revenue (with `formatRevenue` collapsing to `млн`/`млрд`), new-this-month. Because they derive from the same array the table renders, **they cannot disagree with it**. Worth preserving as a discipline in any prototype.

### p. Small conventions worth codifying

- **Empty state as a table row:** `<td colSpan={7}>Нет заказов по фильтру</td>` — the table keeps its header and frame.
- **Absent value is `—`** everywhere: `{o.slotTime || "—"}`, courier with no order.
- **Mono for machine data:** every id, time, phone, INN, account number and money figure is `JetBrains Mono`. Consistently applied; the right instinct.
- **Sale price display:** current price in red, original struck through beside it in muted small.

### Absent, and deliberately noted

No bulk selection anywhere (no checkboxes, no select-all), no column sorting, no pagination on a 100-row table, no keyboard affordances at all (rows are `<tr onClick>` with no `tabIndex`, no `role`, no Enter handling), no focus-visible styling, no Escape-closes-modal, no focus trap. Hover is implemented by mutating `e.currentTarget.style` in ~30 places rather than CSS `:hover`.

IA 1.1 requires bulk selection and bulk courier assignment with defined partial-failure semantics, and Part 2 requires a keyboard-first operator shell. These are **gaps to fill, not patterns to copy**.

---

## 3. Data-shape insights

### Slot-based scheduled delivery is the organising concept

Every order carries `slotTime: "15:30–17:00"` + `slotDate`. Capacity is `12 windows × 20 lines`. Courier work statuses are offer states — `FIRST PICK` / `SLOT PICK` / `FP OFFER 00:47`. Courier config is slot-shaped: `ordersPerSlot: 3`, `priority: "Слот"`, `offerResponse: "45 сек"`, `maxAttempt: 5`.

This is a **pre-booked delivery-window market**, not on-demand dispatch.

*HorecaOS:* ADR 0014 covers scheduled delivery sourcing, but the schema (`V0001`–`V0019`) contains no slot, window, or capacity table. The lattice — capacity per window per zone, and an order's booked window — is a genuine **model gap**, not merely a UI gap.

### Two independent progress clocks per order

`mStage` (merchant/kitchen, 6 stages) and `lStage` (logistics, 6 stages) advance independently with separate timestamp arrays; the aggregate `stage` (8 stages) is a third. Operators filter by lane-specific stage, with the dropdown grouping the two vocabularies under separate headers.

*HorecaOS:* `ordering` is unbuilt (ADR 0019); ADR 0041 covers kitchen execution. The prototype's evidence is that order state must be **at least two concurrent lanes**, not one enum. Model it as `(production_stage, fulfilment_stage)` with independent timestamps, and let the display status be derived.

### Incident vs threat — a record and a projection, both first-class

```js
incident: { active, id:"INC-00412", createdAt, courier, reason,
            description, location, status:"Ожидает решения" }
threat:   { active, type:"Опоздание", severity:"high", message,
            plannedDelivery:"15:30", estimatedDelivery:"15:42",
            delta:"+12 мин", slotEnd:"15:30" }
```

An **incident has an identity** — it can be worked, assigned and resolved. A **threat has none** — it is a computed comparison of a promise time, a live estimate, and the slot boundary. Both propagate into list rows, process cards, map pins, modals and the courier detail.

*HorecaOS:* has neither. The threat shape is what makes "late-order highlight" (IA 1.1) *computable* rather than hand-waved: you need a promised time, a live estimate, and a signed delta against a boundary. Both deserve an ADR.

### Merchant is a three-level tree that HorecaOS half-has

```
merchant
  └ legals[]   { INN, form (ООО/ИП), address, bank, account, commission: "15%" }
      └ nets[] { region, address, location:[lat,lng], workDays, workTime,
                 maxLoad: 20, pickupTime:"15 мин", prepareBuffer:"5 мин",
                 legalId: "REQ-01",
                 orders, revenue, payout, income }
```

Two facts stand out. **Commission lives on the legal entity, not the merchant** — the fixture has one merchant with entities at 15% and 12%. And **each branch carries throughput config** (`maxLoad`, `pickupTime`, `prepareBuffer`) that is neither catalog nor address, and on which every capacity screen depends.

*HorecaOS:* has `tenant.brands` + `tenant.locations`; IA 2.4 and ADR 0038 cover legal entities and fiscal identity. Missing: branch-level throughput configuration, and the commission-on-legal-entity relationship.

### Money: whole integers and pre-formatted strings, inconsistently

`sum: 284000` (int, formatted at render), but `revenue: "48 200 000"` (a display string that `Merchants.jsx:64` must parse back to total it: `parseInt(m.revenue.replace(/\s/g,""), 10)`). And `Showcase.jsx` renders `₽` while everything else renders `сум` — a copy-paste bug that a prototype with no shared formatter will always eventually produce.

*HorecaOS:* already correct (whole som, VAT-inclusive, deterministic quotes in `pricing.quotes`). Do not import the string-money habit; the prototype demonstrates exactly why one formatter module is non-negotiable.

### The product carries logistics attributes HorecaOS's catalog does not

```js
{ prepTime: 15, temp: "Горячее", fragile: true, weight: 450,
  avail: "08:00–23:00", nets: ["NET-01","NET-02"],
  status: "Скрыт", inStock: false, stopList: true,
  modType: "size", mods: [{name:"25 см", price:42000}, …] }
```

Three observations:

- `prepTime` / `temp` / `fragile` are **logistics attributes on the product** that the slot and dispatch models consume — a fragile hot item constrains courier type, prep buffer and window.
- `modType` is a **typed modifier axis** (`size` | `person` | `tara`), with each option carrying an absolute price, not a delta. HorecaOS has `catalog.modifier_groups`/`modifier_options` but no typed axis.
- `status` / `inStock` / `stopList` are **three independent flags**, and the UI treats "stop-list" as a filter value alongside statuses. The prototype has no stop *scope* and no stop *source* — IA 2.5 demands both, so this is a place HorecaOS should be strictly better rather than a shape to copy.

Also: `catalog.location_offerings` exists in HorecaOS, which is the right home for `nets[]`. The per-item availability window (`avail`) has no home yet.

### Order gabarit — a tiny model that unlocks correct assignment

`orderGabarit: { size: "Средний", couriersNeeded: 1 }` matched against courier `maxGabarit: "Средний / 15 кг"`. Order size determines courier capability *and* how many couriers are needed.

*HorecaOS:* nothing like it. ADR 0042 covers courier compensation, not capability matching. Cheap to model, and dispatch is wrong without it.

### History as a flat uniform event list with a non-human actor column

```js
{ date:"18.02.2026", time:"14:40", action:"Курьер назначен", actor:"Авто-диспетчер" }
```

Actors observed: `Система`, `Авто-диспетчер`, `GPS`, `Pizza Roma`, `Алишер К.`, `Диспетчер`, `Курьер`. Same shape on the order and on the courier. Rendered as a four-column table with mono time, muted date and actor, and a Скрыть/Показать collapse in the section header.

*HorecaOS:* this is `audit.audit_events` with ADR 0027's "non-human actors are first-class". The prototype validates the *display*; HorecaOS can render its real audit rows in this exact shape today (IA 7.5).

### Courier: person + contract + policy + performance, with two status axes

```js
identity:    passport "AB 1234567", phone, photo
employment:  type "Штат"|"Найм", shift "Утренняя (05:00–15:00)", workDays
capability:  transport, maxGabarit "Средний / 15 кг", ordersPerSlot 3
policy:      offerResponse "45 сек", maxAttempt 5, priority "Слот", regionWork
performance: rating 4.8, completed 1198, cancelled 42, late 44, latePercent "3.4%",
             revenue vs earnings
status:      "Активен"        ← account lifecycle
workStatus:  "BUSY"           ← live work state
```

The work view adds `TAKEN`, `OFFERING`, `PENALTY`, `ABSENT`, each paired with a **free-text `statusText` carrying a live countdown** — `FP OFFER 00:47`, `Late 00:47`, `ожидание SLOT PICK`, `5 мин.`. The code is filterable; the text is human.

*HorecaOS:* no courier entity exists. The **two-axis status** (account lifecycle × live work state) is the shape to copy. IA 3.3 mentions "online status and rating (read-only)" but not offer/penalty/absent states — those are what a dispatch board actually needs.

### What is absent from the fixtures, and what that tells you

No tenant. No brand. No channel. No currency or locale. No VAT breakdown on the order total (`vat: true` sits on the product, but `payment` is only `itemsTotal / deliveryFee / discount / total`). No external provider ids, no idempotency keys, no roles or permissions, no pagination cursors.

Togora is single-tenant, single-currency, single-country, single-channel. **Every HorecaOS screen must add a tenant/brand axis to shapes that have none here** — and because HorecaOS's house rule is that tenant and brand predicates go *in the query*, the filter bar needs a scope selector the prototype never had, and it belongs in the header strip beside the title.

---

## 4. What NOT to carry over

| Togora choice | Where | Conflicts with | HorecaOS equivalent |
|---|---|---|---|
| `font = "'DM Sans', sans-serif"`, loaded from Google Fonts in `index.html` | everywhere | IBM Plex Sans mandated | `--q-font-sans`. `JetBrains Mono` → `--q-font-mono` (IBM Plex Mono). **Keep the habit** of mono for ids/times/money — that part is right. |
| Inline `fontSize:` on nearly every element — 8, 9, 9.5, 10, 10.5, 11, 12, 13, 14, 15, 16, 17, 18, 20, 22, 28, 32, 36, 40, 48 | everywhere | Closed type scale; "never set a font-size inline" | The seven classes in `tokens.css`. Table cell → `.q-body-sm`; table header and field label → `.q-caption`; page title → `.q-title`; stat value → `.q-data-lg .q-tnum`. The 8px and 9px micro-labels have **no equivalent** and must rise to 12. |
| `borderRadius: 14` (Card), `20` (badges), `10`/`8` (buttons, inputs), `16` (modals), `50%` (avatars) | everywhere | `--q-radius: 0px`; flat-square is the brand | 0 everywhere. Photo avatars in console tables should go entirely — the id and the name carry the identity. |
| `boxShadow: "0 1px 3px rgba(0,0,0,.04), 0 4px 12px rgba(0,0,0,.03)"` on every Card; `0 20px 60px` on modals; `boxShadow + transform: scale(1.04)` on slot-cell hover | `components.jsx:88`, modals, `Orders.jsx:990` | No drop shadows on console surfaces | `1px solid var(--q-hairline)` and nothing more. Modal elevation = hairline + scrim. Hover = background to `--q-surface-1`, never scale. |
| `linear-gradient(145deg,#F8F9FC,#EEF0F5)` page; `linear-gradient(135deg,#3B82F6,#8B5CF6)` logo and user avatar; `linear-gradient(135deg,#3B82F6,#2563EB)` primary button | `App.jsx:70,86,177`, `Orders.jsx:1110` | No gradients | Flat `--q-surface-1` page; flat `--q-primary` button. |
| Emoji as a system: nav icons `📊📋🏪🚴🛒👤⚡✅💳📈🗺️⚙️`; `SectionHeader icon`; merchant `logo:"🍕"`; product `photo:"🍕"`; order-item `emoji`; `⭐ 4.8`; `✓ Одобрить`/`✕ Отклонить`; `🚫 СТОП`; `🕐`/`📅` on date pills; `⚠️`/`ℹ️` on risk rows; `── 🏪 Мерчант ──` inside a `<select>` | `data.jsx:296`, all files | No emoji | Carbon icons at 16px for nav and actions. Rating = the number in `.q-tnum`, no star. Approve/Reject = text buttons (primary + ghost-danger). Merchant/product image = a real thumbnail from `media.assets`, or nothing — never a pictograph standing in for a brand. Severity = tint + text token, no glyph. Option-group separators = `<optgroup label>`. |
| Dark navy sidebar `#1A1A2E`, active item `rgba(59,130,246,0.15)` fill with `#60A5FA` text, `borderRight: 1px solid #2A2A4A` | `App.jsx:75` | Console is a light surface; blue is scarce | Sidebar on `--q-canvas` with a `--q-hairline` right border. Active item = `--q-surface-2` fill + 3px `--q-primary` left rule + `--q-ink` text. `--q-inverse` (#161616) exists if a dark rail is genuinely wanted, but navy-with-blue-glow is not it. |
| Blue as decoration: every id is `#2563EB`; every clickable number blue; logo, avatar, active tab, active filter pills, `SectionHeader accent="#2563EB"`, the `+ Добавить` button, the modal CTA | everywhere | "Platform blue scarcely — links, primary actions, focus, selection. Never decoration." | Ids in `--q-ink` mono *unless* they are genuinely links (then blue is correct). Active filter/tab = `--q-ink` fill or underline, not blue. Section headers get no accent colour at all. Reserve `--q-primary` for links, the single primary action, focus, and selection. |
| Ten hues in play: `#2563EB #059669 #7C3AED #D97706 #DC2626 #0891B2 #6366F1 #8B5CF6 #F59E0B #EA580C`; money green, unpaid red, payout purple, income blue | `components.jsx:8-25`, `data.jsx:248-275`, everywhere | Four semantic tints | `--q-success/warning/error/info` with their `-tint`/`-text` pairs. **Money is `--q-ink`, right-aligned, `.q-tnum`** — colour on money is noise. Purple/cyan/indigo/violet have no token; map to the four or go neutral. The 8- and 6-colour `stagesDef`/`merchantStagesDef`/`logisticsStagesDef` ramps collapse to: done `--q-ink`, current `--q-primary`, future `--q-surface-2`. |
| Four-hue heat ramp green→yellow→orange→red for load | `Merchants.jsx:779` | Four semantic tints; a ramp is ordinal, not categorical | A single-hue sequential ramp (four steps of one colour) plus the legend. Four *hues* read as four *categories*. |
| `textTransform:"uppercase"` + `letterSpacing: 0.5–1` on every field label and `<th>`, at 9–11px | `components.jsx:27,30,71` | Sentence case | Sentence case in `.q-caption` (12px). This is the prototype's single worst legibility choice. |
| Status maps keyed by Russian display string — `orderStatusMap["На доставке"]` | `components.jsx:8` | ru/uz content required; codes are the contract | Key by a stable code (`OUT_FOR_DELIVERY`) and look up both the localised label and the tint. Otherwise nothing translates and a copy edit silently breaks the colour. |
| `onMouseEnter={e => e.currentTarget.style.background = "#F9FAFB"}` in ~30 places; `transition: "all 0.15s"`; `transform: scale(1.04)` on cells | everywhere | "Only transform and opacity animate. Console table data never animates." | CSS `:hover` on a class. Transition `background-color` at `--q-dur-fast`, or not at all. Never `transition: all`. Never scale a data cell. |
| `₽` in Showcase vs `сум` elsewhere; `toLocaleString("ru-RU")` inline in five files; dates as `18.02.2026 14:32` | `Showcase.jsx:100`, everywhere | `84 000 so'm`, tabular figures, 24h, `DD.MM` | One formatter module: `money()`, `dateTime()`, `time()`. In a console table use `18.02 14:32`; show the year only where it matters. |
| No keyboard access, no focus ring, no `role`/`aria`; modals do not close on Escape; no focus trap or restore | everywhere | Operator console is keyboard-first (IA Part 2, shell (a)) | Rows as real links or `<tr tabIndex={0}>` with Enter to open; **visible `--q-primary` focus outline** (the one place blue is mandatory); Escape closes modals; trap and restore focus. |
| `Card` swallows every prop but `children`/`style`, silently breaking the add-courier modal | `components.jsx:88` + `Couriers.jsx:213` | — | Any shared wrapper spreads `...rest` onto its element. |
| 33 `useState` in one component; 25-prop call sites; no router | `App.jsx` | — | See §5. |
| Twelve nav items, seven of which render nothing (`dashboard`, `clients`, `dispatch`, `finance`, `analytics`, `regions`, `settings`) | `data.jsx:296` + `App.jsx:190` | — | Every nav item either renders a screen or renders a stub naming its tier ("Wave 2"). A dead click in a review costs credibility. |

---

## 5. Concrete recommendations for the HorecaOS control-plane prototype

### Copy wholesale

1. **The section-dispatcher shape.** One file per IA section group, one exported component, ordered guard clauses, detail-beats-subview precedence, fallback at the end.
2. **`FR` / `Field` — detail panes authored as data arrays.** The highest-leverage 30 lines in the prototype. Rebuild as `<FieldRow items={[{label, value, mono, wide}]}/>` with `.q-caption` labels and `.q-body-sm` values, sentence case.
3. **Modal state = the id of the record acted on**, never a boolean. And confirmation copy that names the object and states irreversibility.
4. **Counts inside filter controls**, computed before filtering.
5. **Fixtures authored backwards from visual states**, with block comments naming each state: `// FULL COLUMN`, `// ZERO ROWS`, `// CREDENTIAL EXPIRED`, `// TENANT WITH 1 BRAND, 47 LOCATIONS`. Include the ugly cases — a 4 500 000 so'm figure beside 36 000, a 30-character tenant name beside a 3-character one.
6. **Three fixture shapes:** one deep singleton per detail screen (+ `getXDetail(id)`), flat arrays for tables, purpose-built projections for the two or three screens that need a different shape.
7. **Row severity on three channels** — tint + left rule + reason caption — with a strict precedence rule and a transparent left border on normal rows so alignment holds.
8. **Severity-first sort** on every operational queue.
9. **Empty state as one full-width row** inside the table frame.
10. **`—` for absent values**, everywhere.
11. **Stat tiles derived by `reduce`** from the same array the table renders, so they cannot disagree.
12. **An animation/live toggle** on any live view, so a reviewer sees motion without a backend.
13. **The screen frame:** fixed header (title + `label · count` subtitle + exactly one right-hand affordance), scrolling body. Add the tenant/brand scope selector to the header — Togora had no such axis and HorecaOS cannot render a screen without it.

### Adapt

1. **Filter bar → Carbon.** Status becomes tabs with counts (IA 1.1 says tabs); secondary axes become dropdowns whose trigger displays the applied value; date becomes a range control. Keep the divider-grouped single row and the "this control is filtering" visual state.
2. **Dual pipeline → the order timeline.** Squares and hairline connectors; done `--q-ink`, current `--q-primary`, future `--q-surface-2`. Print the stage label under **every** stage, not just the current one. Put the timestamp under completed stages as visible text — a `title` tooltip is not keyboard-reachable.
3. **Slots grid → a capacity lattice component.** Columns = windows, rows = capacity lines, `n/cap` in the header, dashed outline for free cells, today-only phase computation. Confine the green/red tint to the **header cell**, not all 240 body cells — at HorecaOS's flat weight, whole-column tinting shouts.
4. **Heat strip → keep the overlap arithmetic and the outside-`overflow` tooltip.** Swap the four-hue ramp for a single-hue sequential ramp with a legend.
5. **Map → real tiles.** Keep the layer order, the two-colour route split at the courier's current position, status-as-pin-border, the clamped popover and the zoom readout. Drop the hand-rolled `window`-listener pan for the map library's own.
6. **Master-detail → real routes** (`/tenants/:id`, `/tenants/:id/installations`) so deep links and browser-back work — but keep filters in query params so the "filters survive the detail round trip" property survives.
7. **Merchant's nine tabs → tenant overview tabs**, reordered by IA tier: Overview, Brands & locations, Legal entities, Installations, Entitlements, Audit. Nine is the ceiling.
8. **Moderation card row → the approvals queue** (6.5) and onboarding blockers (2.5), with affordances driven by a server `actions[]` array rather than a client-side `isPending`.
9. **Incident and threat → platform vocabulary.** An incident is a record with an id and a lifecycle; a threat is a computed projection with a promise, an estimate and a signed delta. Both belong in the control plane as "open issue" (10.2) and "SLO burn / predicted breach" (1.1).

### Drop

1. **The entire visual layer** — DM Sans, radii, shadows, gradients, emoji, the navy rail, decorative blue, coloured money, uppercase 9px micro-labels, ten hues.
2. **Prop drilling.** 33 `useState` in `App` with 25-prop call sites. Colocate per-section state in the section component; put anything linkable (selection, active tab, filters) in the URL. If shared state is genuinely needed, one `useReducer` in one context — not 33 setters threaded through three levels.
3. **String money and per-file formatting.** One formatter module. `Showcase.jsx`'s stray `₽` is what happens without one.
4. **Display-string keys** for status colour maps.
5. **`getMerchantDetail(id)` returning the same merchant regardless of id.** Fine for a screenshot, actively misleading in review when a reviewer clicks two rows and sees Pizza Roma twice. Key fixtures by id and return the match; fall back to a clearly-labelled generic record.
6. **`title`-attribute tooltips as the sole carrier of information** (stage timestamps).
7. **`transition: all`, JS hover mutation, `transform: scale` on data.**
8. **Dead nav items.** Every entry renders a screen or a tier-labelled stub.

### Suggested build order

The control-plane IA is 10 sections and ~40 screens; the prototype exists to answer "right screen, right order, right density", not to cover the tree. Five tier-P screens exercise every pattern worth stealing, and the rest of the control plane is recombination:

| Screen | Patterns it validates |
|---|---|
| **2.2 Tenant overview** | master-detail precedence, `FieldRow` data-driven detail, tab group, aggregate-count-as-link |
| **3.3 Installations explorer** | severity rows (expired credential / high error rate), filter counts, cross-module deep link to tenant and provider |
| **4.2 Dead letters & replay** | severity-first sort, filter counts by cause, **bulk selection** (the prototype's biggest gap), the confirmation ladder for an audited destructive action |
| **7.5 Audit log** | the history table shape — already validated by the prototype, four columns with non-human actors first-class, real `audit.audit_events` rows behind it |
| **6.1 Fiscalization operations** | severity + bulk retry + the incident/threat distinction, on a legal go-live blocker |

Add **1.1 Platform health** last, as the place the stat-tile and heat-strip patterns land.
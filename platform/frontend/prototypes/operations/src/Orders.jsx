/* The order board and the order detail.
 *
 * This is the screen the console exists for. Three decisions shape it and
 * everything else follows from them:
 *
 * 1. **It opens on Attention, not on New.** A new order is the least urgent
 *    thing on the board, because nothing has gone wrong with it yet. The first
 *    question at 19:34 is not "what arrived" but "what needs a human".
 *
 * 2. **Severity ranks the queue; time only breaks ties.** Newest-first is right
 *    for a log and wrong for a queue — it pushes the person who has waited
 *    longest off the bottom of the screen. Within a severity the oldest comes
 *    first, and lateness is an overlay on a status, never a status.
 *
 * 3. **The queue never leaves.** Opening an order docks a column beside the
 *    list instead of covering it, and the board sheds its lower-value columns
 *    rather than growing a horizontal scrollbar. An operator reading 0138's
 *    address must still see 0151's approval deadline run out.
 *
 * Action availability is not computed here. `actionsFor()` in Orders.data.jsx
 * stands in for the server's `actions[]` array; this file renders exactly what
 * it is handed and nothing for what it is not. Unavailable is absent — a greyed
 * button teaches an operator that grey means "try again".
 */

import { useEffect, useMemo, useRef, useState } from "react";
import {
  ink, inkMuted, inkSubtle, hairline, canvas, surface1, blue,
  uzs, dt, day, StatusPill, Button, EmptyState,
} from "./components";
import {
  ORDERS, TABS, STATUS, MODE, PAYMENT_PROJECTION, LOCATIONS, CHANNELS, COURIERS, ACTORS,
  CANCEL_REASONS, DISPOSITION_TEXT, LIABILITY_TEXT, REFUND_TEXT, LEVEL, GAPS, BULK,
  NOW_MS, BUSINESS_DATE, severityOf, sortRows, actionsFor, isTerminal, bulkOutcome,
  minsTo, countdown, dur,
} from "./Orders.data";

const hhmm = (iso) => dt(iso).slice(6);
const clock = (ms) => { const d = new Date(ms), p = (n) => String(n).padStart(2, "0"); return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`; };
const loc = (id) => LOCATIONS.find((l) => l.id === id);
const chan = (id) => CHANNELS.find((c) => c.id === id);
const courier = (id) => COURIERS.find((c) => c.id === id);
const dotStyle = (on) => ({ width: 7, height: 7, borderRadius: "50%", flexShrink: 0, background: on ? "var(--q-success)" : "transparent", border: on ? "none" : `1px solid ${inkSubtle}` });
const iconBtn = { height: 32, width: 32, background: "transparent", border: `1px solid ${hairline}`, color: inkMuted, cursor: "pointer" };
const linkBtn = { background: "none", border: "none", color: blue, cursor: "pointer", padding: 0, font: "inherit" };

/* ── local primitives ──────────────────────────────────────────────────────
 * Not in components.jsx because nothing else in the console needs them.
 */

/** Where a view needs data the backend does not have, it says so and names the
 *  owning decision. A screen may be designed against an unbuilt field; it may
 *  not pretend the field is there. */
function Gap({ adr, children }) {
  return <span className="q-caption" style={{ color: inkSubtle }}>{children ? `${children} · ` : ""}not built · {adr}</span>;
}

function Panel({ title, children, note }) {
  return (
    <section style={{ borderBottom: `1px solid ${hairline}`, padding: "16px 20px" }}>
      <h3 className="q-emphasis" style={{ margin: "0 0 12px", color: ink }}>{title}</h3>
      {note ? <p className="q-caption" style={{ margin: "-6px 0 12px", color: inkSubtle }}>{note}</p> : null}
      {children}
    </section>
  );
}

function Line({ label, value, mono, tone }) {
  return (
    <div style={{ display: "flex", gap: 16, padding: "5px 0", alignItems: "baseline" }}>
      <span className="q-caption" style={{ color: inkSubtle, minWidth: 128, flexShrink: 0 }}>{label}</span>
      <span
        className={mono ? "q-body-sm q-tnum" : "q-body-sm"}
        style={{ color: tone || ink, marginLeft: "auto", textAlign: "right", fontFamily: mono ? "var(--q-font-mono)" : undefined, minWidth: 0, wordBreak: "break-word" }}
      >
        {value ?? "—"}
      </span>
    </div>
  );
}

const Note = ({ children, tone }) => (
  <p className="q-caption" style={{ margin: "8px 0 0", color: tone || inkSubtle }}>{children}</p>
);

/** ADR 0029: the phone is masked until an operator states a purpose, and the
 *  reveal is a separate audited call. Copying counts as a reveal. */
function Phone({ order, revealed, onReveal }) {
  const cu = order.customer;
  if (cu.anonymizedAt) return <span className="q-caption" style={{ color: inkSubtle }}>Retention period elapsed — data erased</span>;
  if (cu.guest) return <span className="q-caption" style={{ color: inkSubtle }}>Guest · no account</span>;
  return (
    <span style={{ display: "inline-flex", alignItems: "center", gap: 8 }}>
      <span className="q-caption q-tnum" style={{ color: inkMuted, fontFamily: "var(--q-font-mono)", whiteSpace: "nowrap" }}>
        {revealed ? cu.phoneMasked.replace(/•••/, "123").replace(/•• /, "45 ") : cu.phoneMasked}
      </span>
      {!revealed ? (
        <button type="button" className="q-caption" style={linkBtn} onClick={(e) => { e.stopPropagation(); onReveal(); }}>Reveal</button>
      ) : null}
    </span>
  );
}

/* ══ the screen ═══════════════════════════════════════════════════════════ */

export default function Orders({ orderId, setOrderId, filter, setFilter, search, setSearch, onNewOrder }) {
  const tab = TABS.some((t) => t.id === filter) ? filter : "attention";
  const tabDef = TABS.find((t) => t.id === tab);

  const [branch, setBranch] = useState("all");
  const [mode, setMode] = useState("all");
  const [more, setMore] = useState(false);
  const [lateOnly, setLateOnly] = useState(false);
  const [problemOnly, setProblemOnly] = useState(false);
  const [sortBy, setSortBy] = useState(null);
  const [selected, setSelected] = useState([]);
  const [revealed, setRevealed] = useState([]);
  const [menu, setMenu] = useState(null);
  const [dialog, setDialog] = useState(null);
  const [bulkResult, setBulkResult] = useState(null);
  const [toast, setToast] = useState(null);
  const [focus, setFocus] = useState(0);
  const searchRef = useRef(null);

  /* ADR 0045 is not built, so the board polls and stamps what it last saw. A
   * queue that silently stopped updating looks identical to a quiet shift, and
   * that is how a restaurant loses an hour. */
  const [tick, setTick] = useState(0);
  const [synced, setSynced] = useState(0);
  useEffect(() => { const h = setInterval(() => setTick((t) => t + 1), 1000); return () => clearInterval(h); }, []);
  const stale = tick - synced > 25;

  /* Counts are computed over each tab's own scope, before the other filters
   * apply, so a count never collapses to zero as a filter narrows. */
  const counts = useMemo(() => Object.fromEntries(TABS.map((t) => [t.id, ORDERS.filter(t.member).length])), []);
  const inScope = useMemo(() => ORDERS.filter((o) => loc(o.locationId)?.inScope), []);

  const searched = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (q.length < 2) return null;
    const norm = (s) => s.toLowerCase().replace(/[-\s#]/g, "");
    return inScope.filter((o) => o.number.startsWith(q) || o.customer.name.toLowerCase().includes(q)
      || (o.externalRefs || []).some((r) => norm(r.value).includes(norm(q))));
  }, [search, inScope]);

  const filtered = useMemo(() => {
    /* Search is not scoped to the active tab: a customer ringing about
     * yesterday's cancelled order must be findable from Attention. */
    let rows = searched || inScope.filter(tabDef.member);
    if (branch !== "all") rows = rows.filter((o) => o.locationId === branch);
    if (mode !== "all") rows = rows.filter((o) => o.mode === mode);
    if (lateOnly) rows = rows.filter((o) => severityOf(o).level === "LATE");
    if (problemOnly) rows = rows.filter((o) => (o.processes || []).some((p) => ["MANUAL_ACTION_REQUIRED", "FAILED_RETRYABLE"].includes(p.status)));
    return rows;
  }, [searched, tabDef, branch, mode, lateOnly, problemOnly, inScope]);

  const rows = useMemo(() => {
    if (sortBy === "total") return [...filtered].sort((a, b) => b.money.total - a.money.total);
    if (sortBy === "time") return [...filtered].sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt));
    return sortRows(filtered, tabDef.queue && !searched);
  }, [filtered, sortBy, tabDef, searched]);

  const open = orderId ? ORDERS.find((o) => o.id === orderId) : null;
  const filtersOn = branch !== "all" || mode !== "all" || lateOnly || problemOnly;
  const reset = () => { setBranch("all"); setMode("all"); setLateOnly(false); setProblemOnly(false); };
  const say = (t) => { setToast(t); setMenu(null); };
  const openMenu = (o, e) => setMenu({ order: o, x: e.clientX, y: e.clientY });

  /* Keyboard. Destructive keys never act directly — `x` opens the dialog whose
   * confirm is a click, never a bare keystroke. */
  useEffect(() => {
    const onKey = (e) => {
      const typing = ["INPUT", "TEXTAREA", "SELECT"].includes(e.target.tagName);
      if (e.key === "/" && !typing) { e.preventDefault(); searchRef.current?.focus(); return; }
      if (e.key === "Escape") { setDialog(null); setMenu(null); setSelected([]); return; }
      if (typing || e.metaKey || e.ctrlKey) return;
      const row = rows[focus];
      if (e.key === "j" || e.key === "ArrowDown") { e.preventDefault(); setFocus((f) => Math.min(f + 1, rows.length - 1)); }
      else if (e.key === "k" || e.key === "ArrowUp") { e.preventDefault(); setFocus((f) => Math.max(f - 1, 0)); }
      else if (e.key === "Enter" && row) setOrderId(row.id);
      else if (e.key === " " && row) { e.preventDefault(); setSelected((s) => (s.includes(row.id) ? s.filter((x) => x !== row.id) : [...s, row.id])); }
      else if (e.key >= "1" && e.key <= "7") setFilter(TABS[Number(e.key) - 1].id);
      else if (e.key === "x" && row) setDialog({ kind: "cancel", order: row });
      else if (e.key === "c" && row) setDialog({ kind: "courier", order: row });
      else if (e.key === "r") setSynced(tick);
      else if (e.key === "n") onNewOrder();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [rows, focus, tick, setFilter, setOrderId, onNewOrder]);

  const act = (order, a) => {
    if (a.id === "cancel") return setDialog({ kind: "cancel", order });
    if (a.id === "courier") return setDialog({ kind: "courier", order });
    if (a.id === "open") return setOrderId(order.id);
    if (a.id === "resolve" || a.id === "resend") { setOrderId(order.id); return say(`#${order.number} · integrations panel opened`); }
    if (a.id === "call" || a.id === "copy") {
      setRevealed((r) => [...new Set([...r, order.id])]);
      return say(`#${order.number} · reveal recorded — purpose: operator call, audited (ADR 0027)`);
    }
    say(`#${order.number} · ${a.label} sent with If-Match ${order.version} and an idempotency key`);
  };

  return (
    <div style={{ display: "flex", alignItems: "flex-start", minWidth: 0 }}>
      {/* ══ board ══════════════════════════════════════════════════════════ */}
      <div style={{ flex: 1, minWidth: 0 }}>
        <header style={{ display: "flex", alignItems: "baseline", gap: 16, marginBottom: 16, flexWrap: "wrap" }}>
          <h1 className="q-title" style={{ margin: 0, color: ink }}>Orders</h1>
          <span className="q-body-sm q-tnum" style={{ color: inkMuted }}>{counts.new + counts.preparing + counts.delivering} active</span>
          <span style={{ marginLeft: "auto", display: "flex", alignItems: "center", gap: 12 }}>
            <span className="q-caption q-tnum" style={{ color: stale ? "var(--q-warning-text)" : inkSubtle }}>updated {clock(NOW_MS + synced * 1000)}</span>
            <button type="button" onClick={() => setSynced(tick)} className="q-caption"
              style={{ background: "none", border: `1px solid ${hairline}`, color: inkMuted, height: 28, padding: "0 10px", cursor: "pointer" }}>Refresh</button>
            <Button size="sm" onClick={onNewOrder}>New order</Button>
          </span>
        </header>

        {/* Only Attention's badge is coloured; tabs of identical weight teach an
            operator nothing. Each is a route, so a supervisor can send a link. */}
        <div style={{ display: "flex", borderBottom: `1px solid ${hairline}`, overflowX: "auto" }}>
          {TABS.map((t) => {
            const on = t.id === tab;
            const alarm = t.id === "attention" && counts[t.id] > 0;
            return (
              <button key={t.id} type="button" className="q-body-sm"
                onClick={() => { setFilter(t.id); setSelected([]); setFocus(0); }}
                style={{
                  padding: "10px 14px", background: "transparent", border: "none", cursor: "pointer", marginBottom: -1,
                  borderBottom: on ? `2px solid ${blue}` : "2px solid transparent", color: on ? ink : inkMuted,
                  whiteSpace: "nowrap", display: "inline-flex", alignItems: "center", gap: 8,
                }}>
                {t.label}
                <span className="q-caption q-tnum" style={{
                  color: alarm ? "var(--q-error-text)" : inkSubtle,
                  background: alarm ? "var(--q-error-tint)" : "transparent", padding: alarm ? "1px 6px" : 0,
                }}>{counts[t.id]}</span>
              </button>
            );
          })}
        </div>

        {/* The selection bar replaces the filter row while anything is selected. */}
        {selected.length ? (
          <BulkBar rows={ORDERS.filter((o) => selected.includes(o.id))} onClear={() => setSelected([])}
            onRun={(action) => { setBulkResult({ action, items: bulkOutcome(action.id, ORDERS.filter((o) => selected.includes(o.id))) }); setSelected([]); }} />
        ) : (
          <div style={{ background: canvas, border: `1px solid ${hairline}`, borderTop: "none", padding: 10 }}>
            <div style={{ display: "flex", gap: 10, alignItems: "center", flexWrap: "wrap" }}>
              <input ref={searchRef} value={search} onChange={(e) => setSearch(e.target.value)} className="q-body-sm"
                placeholder="Order number, phone, partner id     /"
                style={{ height: 32, minWidth: 260, padding: "0 8px", background: canvas, color: ink, border: "none", borderBottom: `1px solid ${hairline}`, outline: "none" }}
                onFocus={(e) => { e.target.style.borderBottom = `2px solid ${blue}`; }}
                onBlur={(e) => { e.target.style.borderBottom = `1px solid ${hairline}`; }} />
              <span className="q-caption" style={{ border: `1px solid ${hairline}`, padding: "6px 10px", color: inkMuted, whiteSpace: "nowrap" }}>
                {BUSINESS_DATE} today<span style={{ color: inkSubtle }}> · business date</span>
              </span>
              <Sel value={branch} onChange={setBranch} options={[{ value: "all", label: "All branches" },
                ...LOCATIONS.filter((l) => l.inScope).map((l) => ({ value: l.id, label: l.forceClosed ? `${l.name} · closed until ${hhmm(l.forceClosed.until)}` : l.name }))]} />
              <Segmented value={mode} onChange={setMode} options={[{ value: "all", label: "All" }, { value: "DELIVERY", label: "Delivery" }, { value: "PICKUP", label: "Pickup" }, { value: "DINE_IN", label: "Dine-in" }]} />
              <Toggle on={lateOnly} onClick={() => setLateOnly((v) => !v)} label="Late only" />
              <Toggle on={problemOnly} onClick={() => setProblemOnly((v) => !v)} label="Has a problem" />
              <button type="button" onClick={() => setMore((v) => !v)} className="q-caption" style={{ ...linkBtn, marginLeft: "auto" }}>
                {more ? "Fewer filters" : "More filters"}
              </button>
              {filtersOn ? <button type="button" onClick={reset} className="q-caption" style={linkBtn}>Reset filters</button> : null}
            </div>
            {more ? (
              <div style={{ display: "flex", gap: 16, flexWrap: "wrap", marginTop: 10, paddingTop: 10, borderTop: `1px solid ${hairline}` }}>
                <Gap adr={GAPS.attribution}>My orders</Gap>
                <Gap adr={GAPS.attribution}>Callback requested</Gap>
                <Gap adr={GAPS.external}>Aggregator</Gap>
                <Gap adr={GAPS.payment}>Payment method</Gap>
                <Gap adr={GAPS.fiscal}>Fiscal status</Gap>
                <span className="q-caption" style={{ color: inkSubtle }}>These use the same query-parameter names as the order report (IA 7.2), so a filtered board carries into it.</span>
              </div>
            ) : null}
            <div style={{ marginTop: 8, display: "flex", gap: 16, flexWrap: "wrap" }}>
              <span className="q-caption" style={{ color: inkSubtle }}>Showing 3 of 4 branches — Mirobod is outside your scope</span>
              {sortBy ? (
                <span className="q-caption" style={{ color: "var(--q-info-text)", background: "var(--q-info-tint)", padding: "2px 8px" }}>
                  Sorted by {sortBy === "total" ? "amount" : "newest"} ·{" "}
                  <button type="button" onClick={() => setSortBy(null)} style={linkBtn}>back to the queue</button>
                </span>
              ) : null}
              {searched ? <span className="q-caption" style={{ color: "var(--q-info-text)" }}>Search resolves order numbers, names and partner ids across every tab and date</span> : null}
            </div>
          </div>
        )}

        {stale ? (
          <div className="q-body-sm" style={{ background: "var(--q-warning-tint)", color: "var(--q-warning-text)", border: `1px solid ${hairline}`, borderTop: "none", padding: "8px 12px", display: "flex", gap: 12 }}>
            Link lost — showing data from {clock(NOW_MS + synced * 1000)}. The rows are not hidden.
            <button type="button" onClick={() => setSynced(tick)} style={linkBtn}>Refresh</button>
          </div>
        ) : null}

        {bulkResult ? <BulkResult result={bulkResult} onClose={() => setBulkResult(null)} /> : null}

        <BoardTable
          rows={rows} dense={!!open} openId={orderId} focus={focus} selected={selected} setSelected={setSelected}
          revealed={revealed} setRevealed={setRevealed} onOpen={(o) => setOrderId(o.id)} onSort={setSortBy}
          onMenu={openMenu} onAct={act}
          empty={
            tab === "attention" && !filtersOn && !search ? <EmptyState title="All clear" description="Nothing on the board needs a human right now." />
              : filtersOn || search ? (
                <EmptyState title="Nothing matches these filters"
                  description={`${inScope.filter(tabDef.member).length} orders would show without them.`}
                  action={<Button variant="tertiary" size="sm" onClick={() => { reset(); setSearch(""); }}>Reset filters</Button>} />
              ) : <EmptyState title="No orders yet" action={<Button size="sm" onClick={onNewOrder}>New order</Button>} />
          } />

        <p className="q-caption" style={{ color: inkSubtle, marginTop: 12 }}>
          j / k move · Enter opens · Space selects · 1–7 switch tab · x cancel · c courier · r refresh · Live rows and
          live counts are <Gap adr={GAPS.stream} />; the board polls every 10 s while the tab is visible.
        </p>
      </div>

      {/* ══ detail ═════════════════════════════════════════════════════════ */}
      {open ? (
        <Detail order={open} onClose={() => setOrderId(null)} onAct={act} onMenu={openMenu}
          revealed={revealed.includes(open.id)} onReveal={() => setRevealed((r) => [...r, open.id])} />
      ) : null}

      {menu ? <Overflow menu={menu} onClose={() => setMenu(null)} onAct={act} /> : null}
      {dialog?.kind === "cancel" ? <CancelDialog order={dialog.order} onClose={() => setDialog(null)} onDone={(m) => { setDialog(null); say(m); }} /> : null}
      {dialog?.kind === "courier" ? <CourierDialog order={dialog.order} onClose={() => setDialog(null)} onDone={(m) => { setDialog(null); say(m); }} /> : null}
      {toast ? (
        <div className="q-body-sm" style={{ position: "fixed", left: 24, bottom: 24, zIndex: 60, maxWidth: 520, background: "var(--q-inverse)", color: "var(--q-inverse-ink)", padding: "12px 16px", display: "flex", gap: 16 }}>
          <span style={{ minWidth: 0 }}>{toast}</span>
          <button type="button" onClick={() => setToast(null)} aria-label="Dismiss" style={{ background: "none", border: "none", color: "var(--q-inverse-ink-muted)", cursor: "pointer" }}>✕</button>
        </div>
      ) : null}
    </div>
  );
}

/* ── filter controls ───────────────────────────────────────────────────────
 * A control that is filtering says so in its own border and fill, not only by a
 * chip somewhere else.
 */

function Sel({ value, onChange, options }) {
  const on = value !== "all";
  return (
    <select value={value} onChange={(e) => onChange(e.target.value)} className="q-body-sm"
      style={{ height: 32, padding: "0 8px", color: on ? "var(--q-info-text)" : ink, background: on ? "var(--q-info-tint)" : canvas, border: `1px solid ${on ? blue : hairline}`, borderRadius: "var(--q-radius)" }}>
      {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
    </select>
  );
}

function Segmented({ value, onChange, options }) {
  return (
    <div style={{ display: "inline-flex", border: `1px solid ${hairline}` }}>
      {options.map((o) => {
        const on = o.value === value;
        return (
          <button key={o.value} type="button" onClick={() => onChange(o.value)} className="q-caption"
            style={{ height: 30, padding: "0 10px", border: "none", cursor: "pointer", whiteSpace: "nowrap", background: on ? "var(--q-info-tint)" : "transparent", color: on ? "var(--q-info-text)" : inkMuted }}>
            {o.label}
          </button>
        );
      })}
    </div>
  );
}

function Toggle({ on, onClick, label }) {
  return (
    <button type="button" onClick={onClick} className="q-caption"
      style={{ height: 32, padding: "0 10px", cursor: "pointer", whiteSpace: "nowrap", background: on ? "var(--q-info-tint)" : "transparent", color: on ? "var(--q-info-text)" : inkMuted, border: `1px solid ${on ? blue : hairline}` }}>
      {label}
    </button>
  );
}

/* ── the table ─────────────────────────────────────────────────────────────
 * Fixed column widths so rows do not reflow as data arrives, and no zebra
 * striping — the severity tint needs the background.
 */

function BoardTable({ rows, dense, openId, focus, selected, setSelected, revealed, setRevealed, onOpen, onSort, onMenu, onAct, empty }) {
  /* The column picker, driven by the one thing that actually decides it. All
   * thirteen columns fit a manager's 24" screen; on the call centre's 1366 they
   * do not, and a status you have to scroll sideways to read is a status nobody
   * reads. Two columns whose data also lives in the detail step back, and the
   * board says which two rather than losing them silently. */
  const [wide, setWide] = useState(typeof window === "undefined" || window.innerWidth >= 1560);
  useEffect(() => {
    const onResize = () => setWide(window.innerWidth >= 1560);
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, []);
  const full = wide && !dense;

  if (!rows.length) return <div style={{ border: `1px solid ${hairline}`, borderTop: "none" }}>{empty}</div>;

  const th = (label, o = {}) => (
    <th key={label} className="q-caption" onClick={o.sort ? () => onSort(o.sort) : undefined}
      style={{ textAlign: o.align || "left", padding: "8px 12px", background: surface1, color: inkMuted, fontWeight: 600, borderBottom: `1px solid ${hairline}`, whiteSpace: "nowrap", width: o.w, cursor: o.sort ? "pointer" : "default" }}>
      {label}
    </th>
  );
  const allOn = rows.every((r) => selected.includes(r.id));
  const cell = { padding: "8px 12px", verticalAlign: "top", color: ink };
  const clip = { overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" };

  return (
    <div style={{ border: `1px solid ${hairline}`, borderTop: "none", background: canvas, overflowX: "auto" }}>
      <table style={{ width: "100%", borderCollapse: "collapse", tableLayout: "fixed" }}>
        <thead>
          <tr>
            {!dense ? (
              <th style={{ width: 36, background: surface1, borderBottom: `1px solid ${hairline}`, padding: "8px 0 8px 12px" }}>
                <input type="checkbox" checked={allOn} aria-label={`Select the ${rows.length} rows loaded on this page`}
                  onChange={() => setSelected(allOn ? [] : rows.map((r) => r.id))} style={{ accentColor: blue }} />
              </th>
            ) : null}
            <th style={{ width: 3, background: surface1, borderBottom: `1px solid ${hairline}`, padding: 0 }} />
            {th("No.", { w: dense ? 140 : 132 })}
            {th("Time", { w: 66, sort: "time" })}
            {!dense ? th("Branch", { w: 80 }) : null}
            {full ? th("Type / channel", { w: 104 }) : null}
            {th("Customer", { w: dense ? 140 : 206 })}
            {!dense ? th("Items", { w: 88 }) : null}
            {!dense ? th("Total", { align: "right", w: 112, sort: "total" }) : null}
            {full ? th("Payment", { w: 96 }) : null}
            {th("Status", { w: 112 })}
            {!dense ? th("Courier", { w: 104 }) : null}
            {th("", { w: dense ? 148 : 168, align: "right" })}
          </tr>
        </thead>
        <tbody>
          {rows.map((o, i) => {
            const sev = severityOf(o);
            const lv = LEVEL[sev.level];
            const isOpen = o.id === openId;
            const cr = courier(o.courierId);
            const inline = actionsFor(o).filter((a) => a.inline);
            return (
              <tr key={o.id} onClick={() => onOpen(o)}
                style={{ cursor: "pointer", height: 44, background: isOpen ? "var(--q-info-tint)" : lv.tint, outline: i === focus ? `1px solid ${blue}` : "none", outlineOffset: -1, borderBottom: `1px solid ${hairline}` }}>
                {!dense ? (
                  <td style={{ ...cell, padding: "10px 0 8px 12px" }} onClick={(e) => e.stopPropagation()}>
                    <input type="checkbox" checked={selected.includes(o.id)} aria-label={`Select order ${o.number}`} style={{ accentColor: blue }}
                      onChange={() => setSelected(selected.includes(o.id) ? selected.filter((x) => x !== o.id) : [...selected, o.id])} />
                  </td>
                ) : null}
                {/* Transparent on normal rows, so the number column never jumps. */}
                <td style={{ padding: 0, background: lv.rule }} />
                <td style={cell}>
                  <span className="q-body-sm q-tnum" style={{ fontFamily: "var(--q-font-mono)" }}>#{o.number}</span>
                  {sev.caption ? (
                    <div className="q-caption" style={{ color: sev.level === "NORMAL" ? inkMuted : lv.text, marginTop: 3 }}>{sev.caption}</div>
                  ) : null}
                  {(o.externalRefs || []).length ? (
                    <div className="q-caption" style={{ color: inkSubtle, marginTop: 3, fontFamily: "var(--q-font-mono)" }}>
                      {o.externalRefs[0].provider} · {o.externalRefs[0].value}
                    </div>
                  ) : null}
                </td>
                <td style={cell}>
                  <div className="q-body-sm q-tnum">{hhmm(o.createdAt)}</div>
                  <div className="q-caption q-tnum" style={{ color: inkSubtle, marginTop: 3 }}>{o.promisedAt ? `→ ${hhmm(o.promisedAt)}` : "no promise"}</div>
                </td>
                {!dense ? (
                  <td style={cell}>
                    <div className="q-body-sm">{loc(o.locationId)?.name}</div>
                    {loc(o.locationId)?.forceClosed ? <div className="q-caption" style={{ color: "var(--q-warning-text)", marginTop: 3 }}>closed</div> : null}
                  </td>
                ) : null}
                {full ? (
                  <td style={cell}>
                    <div className="q-body-sm">{MODE[o.mode]}</div>
                    <div className="q-caption" style={{ color: inkSubtle, marginTop: 3 }}>{chan(o.channelId)?.name}</div>
                  </td>
                ) : null}
                <td style={cell}>
                  <div className="q-body-sm" style={clip}>{o.customer.guest ? "Guest" : o.customer.name}</div>
                  {/* The phone lives in the detail while one is open, so the
                      strip does not carry a reveal control twice. */}
                  {!dense ? (
                    <div style={{ marginTop: 3, overflow: "hidden" }} onClick={(e) => e.stopPropagation()}>
                      <Phone order={o} revealed={revealed.includes(o.id)} onReveal={() => setRevealed([...revealed, o.id])} />
                    </div>
                  ) : null}
                </td>
                {!dense ? (
                  <td style={cell}>
                    <div className="q-body-sm q-tnum">{o.lines.length} items</div>
                    <div className="q-caption" style={{ ...clip, color: inkSubtle, marginTop: 3 }}>{o.lines[0].name}</div>
                  </td>
                ) : null}
                {!dense ? (
                  <td style={{ ...cell, textAlign: "right" }}>
                    <span className="q-body-sm q-tnum" style={{ fontFamily: "var(--q-font-mono)", whiteSpace: "nowrap" }}>{uzs(o.money.total)}</span>
                  </td>
                ) : null}
                {full ? (
                  <td style={cell}>
                    <StatusPill tone={PAYMENT_PROJECTION[o.paymentProjection].tone}>{PAYMENT_PROJECTION[o.paymentProjection].label}</StatusPill>
                    <div className="q-caption" style={{ color: inkSubtle, marginTop: 3 }}>{o.paymentMethod || <Gap adr={GAPS.payment} />}</div>
                  </td>
                ) : null}
                <td style={cell}>
                  <StatusPill tone={STATUS[o.status].tone}>{STATUS[o.status].label}</StatusPill>
                  {/* The countdown lives here until it becomes the severity
                      caption, and then only there — the same number twice on
                      one row reads as two different facts. */}
                  {o.status === "AWAITING_APPROVAL" && o.approvalDeadlineAt && sev.rank !== 2 ? (
                    <div className="q-caption q-tnum" style={{ color: inkMuted, marginTop: 3 }}>{countdown(o.approvalDeadlineAt)} left</div>
                  ) : null}
                </td>
                {!dense ? (
                  <td style={cell}>
                    {cr ? (
                      <span style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
                        <span style={dotStyle(cr.onShift)} /><span className="q-body-sm">{cr.name}</span>
                      </span>
                    ) : <span className="q-body-sm" style={{ color: inkSubtle }}>—</span>}
                    <div className="q-caption" style={{ marginTop: 3 }}><Gap adr={GAPS.courier} /></div>
                  </td>
                ) : null}
                <td style={{ ...cell, textAlign: "right" }} onClick={(e) => e.stopPropagation()}>
                  <span style={{ display: "inline-flex", gap: 6, justifyContent: "flex-end", flexWrap: "wrap" }}>
                    {/* With a detail docked the row keeps its single most likely
                        next action and sheds the second one. */}
                    {(dense ? inline.slice(0, 1) : inline).map((a) => (
                      <Button key={a.id} size="sm" variant={a.primary ? "primary" : "tertiary"} onClick={() => onAct(o, a)}>{a.label}</Button>
                    ))}
                    <button type="button" onClick={(e) => onMenu(o, e)} aria-label={`Actions for order ${o.number}`} className="q-body-sm" style={iconBtn}>⋯</button>
                  </span>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
      {!full ? (
        <p className="q-caption" style={{ margin: 0, padding: "8px 12px", color: inkSubtle, borderTop: `1px solid ${hairline}` }}>
          Two columns are hidden at this width — type / channel and payment. Both are on the order detail. Widen the
          window past 1560 px, or open an order, to see where they went.
        </p>
      ) : null}
    </div>
  );
}

/** Entries the server did not permit are absent, not disabled. The one exception
 *  is an action blocked for a stated, transient reason. */
function Overflow({ menu, onClose, onAct }) {
  const all = actionsFor(menu.order);
  const items = all.filter((a) => !a.inline && !a.hidden);
  const blockedCancel = all.find((a) => a.id === "cancel-blocked");
  return (
    <div onClick={onClose} style={{ position: "fixed", inset: 0, zIndex: 55 }}>
      <div onClick={(e) => e.stopPropagation()}
        style={{ position: "fixed", top: Math.min(menu.y, window.innerHeight - 400), left: Math.min(menu.x, window.innerWidth - 300), width: 288, background: canvas, border: `1px solid ${hairline}`, padding: "4px 0" }}>
        {items.map((a) => (
          <button key={a.id} type="button" disabled={a.disabled} onClick={() => { onClose(); onAct(menu.order, a); }} className="q-body-sm"
            style={{ display: "block", width: "100%", textAlign: "left", padding: "8px 14px", background: "transparent", border: "none", cursor: a.disabled ? "not-allowed" : "pointer", color: a.danger ? "var(--q-error-text)" : a.disabled ? inkSubtle : ink }}>
            {a.label}{a.submenu ? " →" : ""}
            {a.reason ? <div className="q-caption" style={{ color: "var(--q-warning-text)", marginTop: 2 }}>{a.reason}</div> : null}
            {a.adr && !a.reason ? <div className="q-caption" style={{ color: inkSubtle, marginTop: 2 }}>not built · {a.adr}</div> : null}
          </button>
        ))}
        {blockedCancel ? (
          <p className="q-caption" style={{ margin: 0, padding: "8px 14px", color: inkSubtle, borderTop: `1px solid ${hairline}` }}>
            Cancellation is refused from {STATUS[menu.order.status].label} onward until ADR 0039 lands. It is not shown
            greyed out, because trying again will not make it available.
          </p>
        ) : null}
      </div>
    </div>
  );
}

/* ── bulk ──────────────────────────────────────────────────────────────────
 * An action is offered only when it is valid for every selected row. Not
 * disabled — absent, with one line saying why.
 */

function BulkBar({ rows, onClear, onRun }) {
  const plans = BULK.map((b) => ({ ...b, bad: rows.filter((o) => !b.valid(o)).length }));
  return (
    <div style={{ background: "var(--q-info-tint)", border: `1px solid ${blue}`, borderTop: "none", padding: "10px 12px", display: "flex", gap: 12, alignItems: "center", flexWrap: "wrap" }}>
      <span className="q-emphasis" style={{ color: "var(--q-info-text)" }}>{rows.length} selected</span>
      {plans.filter((p) => !p.bad).map((p) => <Button key={p.id} size="sm" variant="tertiary" onClick={() => onRun(p)}>{p.label}</Button>)}
      <button type="button" onClick={onClear} className="q-caption" style={{ ...linkBtn, marginLeft: "auto" }}>Clear selection</button>
      <div style={{ flexBasis: "100%" }}>
        {plans.filter((p) => p.bad).map((p) => (
          <div key={p.id} className="q-caption" style={{ color: "var(--q-info-text)", marginTop: 4 }}>
            “{p.label}” is unavailable: {p.bad} of {rows.length} selected {p.noun}
          </div>
        ))}
        <div className="q-caption" style={{ color: inkMuted, marginTop: 4 }}>
          The header checkbox selects the rows loaded on this page, never an unbounded filtered set of unknown size.
        </div>
      </div>
    </div>
  );
}

/** N independent commands under one bulk id, each in its own transaction — so
 *  the answer is a per-item outcome panel, never a toast saying "done". */
function BulkResult({ result, onClose }) {
  const ok = result.items.filter((i) => i.ok).length;
  const bad = result.items.filter((i) => !i.ok);
  return (
    <div style={{ background: canvas, border: `1px solid ${hairline}`, borderTop: "none", padding: 12 }}>
      <div style={{ display: "flex", gap: 12, alignItems: "baseline" }}>
        <span className="q-emphasis" style={{ color: ink }}>{result.action.label} · {ok} done{bad.length ? ` · ${bad.length} problems` : ""}</span>
        <span className="q-caption q-tnum" style={{ color: inkSubtle, fontFamily: "var(--q-font-mono)" }}>bulk_operation_id bo-9d41c7</span>
        <button type="button" onClick={onClose} className="q-caption" style={{ ...linkBtn, marginLeft: "auto" }}>Dismiss</button>
      </div>
      {bad.map((i) => <div key={i.orderId} className="q-caption" style={{ color: "var(--q-error-text)", marginTop: 6 }}>#{i.number} — {i.problem}</div>)}
      {bad.length ? (
        <div style={{ marginTop: 10, display: "flex", gap: 12, alignItems: "center", flexWrap: "wrap" }}>
          <Button size="sm" variant="tertiary" onClick={onClose}>Retry the problems</Button>
          <span className="q-caption" style={{ color: inkSubtle }}>
            Re-runs under the same bulk key, so the {ok} that succeeded replay their stored responses instead of executing twice.
          </span>
        </div>
      ) : null}
    </div>
  );
}

/* ── the detail ────────────────────────────────────────────────────────────
 * Docked beside the queue rather than over it. At this width the specification's
 * below-1200px rule applies: customer and address come first, because the thing
 * you read aloud on the phone outranks the thing you are looking at.
 */

function Detail({ order: o, onClose, onAct, onMenu, revealed, onReveal }) {
  const sev = severityOf(o);
  const lv = LEVEL[sev.level];
  const actions = actionsFor(o);
  const primary = actions.find((a) => a.primary);
  const secondary = actions.filter((a) => a.inline && !a.primary);
  const cr = courier(o.courierId);
  const l = loc(o.locationId);
  const m = o.money;
  const reconciles = m.subtotal + m.tax + m.fee - m.discount === m.total;
  const reason = o.outcome ? CANCEL_REASONS.find((r) => r.id === o.outcome.reasonId) : null;
  const posStuck = (o.processes || []).some((p) => p.key === "POS_ORDER_EXPORT" && p.status === "MANUAL_ACTION_REQUIRED");

  return (
    <aside style={{ width: 460, flexShrink: 0, marginLeft: 24, background: canvas, border: `1px solid ${hairline}`, position: "sticky", top: 72, maxHeight: "calc(100vh - 96px)", overflowY: "auto" }}>
      <header style={{ padding: "14px 20px", borderBottom: `1px solid ${hairline}`, background: lv.tint }}>
        <button type="button" onClick={onClose} className="q-caption" style={linkBtn}>← Back to the queue</button>
        <div style={{ display: "flex", alignItems: "baseline", gap: 10, marginTop: 6, flexWrap: "wrap" }}>
          <span className="q-subhead q-tnum" style={{ color: ink, fontFamily: "var(--q-font-mono)" }}>#{o.number}</span>
          <span className="q-body-sm" style={{ color: inkMuted }}>{l?.name}</span>
          <StatusPill tone={STATUS[o.status].tone}>{STATUS[o.status].label}</StatusPill>
          <span className="q-caption q-tnum" style={{ color: inkSubtle, marginLeft: "auto", fontFamily: "var(--q-font-mono)" }}>v{o.version}</span>
        </div>
        {sev.caption ? <div className="q-body-sm" style={{ color: sev.level === "NORMAL" ? inkMuted : lv.text, marginTop: 6 }}>{sev.caption}</div> : null}
        <div className="q-caption" style={{ color: inkSubtle, marginTop: 6 }}>
          {o.promisedAt ? `Promised ${hhmm(o.promisedAt)} · ${minsTo(o.promisedAt) >= 0 ? `in ${dur(minsTo(o.promisedAt))}` : `${dur(minsTo(o.promisedAt))} ago`} · ` : ""}
          <Gap adr={GAPS.promise}>promise clock</Gap>
        </div>
        <div style={{ display: "flex", gap: 8, marginTop: 12, flexWrap: "wrap", alignItems: "center" }}>
          {primary ? <Button size="sm" onClick={() => onAct(o, primary)}>{primary.label}</Button> : null}
          {secondary.map((a) => <Button key={a.id} size="sm" variant="tertiary" onClick={() => onAct(o, a)}>{a.label}</Button>)}
          <button type="button" onClick={(e) => onMenu(o, e)} aria-label={`More actions for order ${o.number}`} className="q-body-sm" style={iconBtn}>⋯</button>
          {l?.forceClosed ? (
            <span className="q-caption" style={{ color: "var(--q-warning-text)" }}>
              {l.name} is force-closed until {hhmm(l.forceClosed.until)} — {l.forceClosed.reason}
            </span>
          ) : null}
        </div>
      </header>

      {/* Customer first: this is what you say into the phone. */}
      <Panel title="Customer">
        {o.customer.anonymizedAt ? (
          <p className="q-body-sm" style={{ margin: 0, color: inkMuted }}>
            Data erased on the retention schedule, {day(o.customer.anonymizedAt)}. This is not an error state and there
            is nothing left to reveal.
          </p>
        ) : (
          <>
            <Line label="Name" value={o.customer.guest ? "Guest" : o.customer.name} />
            <Line label="Phone" value={<Phone order={o} revealed={revealed} onReveal={onReveal} />} />
            <Line label="Type" value={o.customer.guest ? "Guest — guest_reference_hash" : "Account"} />
            <Line label="History" value={`${o.customer.ordersCount} orders${o.customer.lastOrderAt ? `, last ${dt(o.customer.lastOrderAt)}` : ""}`} />
            {!o.customer.contactAllowed ? (
              <Note tone="var(--q-warning-text)">Transactional contact consent was withdrawn, so call and message are absent here rather than disabled.</Note>
            ) : null}
            {o.callbackRequested ? (
              <Note>Callback requested {hhmm(o.callbackRequestedAt)} — cleared only by an operator. <Gap adr={GAPS.attribution} /></Note>
            ) : null}
          </>
        )}
      </Panel>

      <Panel title="Address and delivery">
        {o.address ? (
          <>
            <Line label="Street" value={`${o.address.street} ${o.address.house}`} />
            <Line label="Flat / entrance" value={[o.address.flat && `flat ${o.address.flat}`, o.address.entrance && `entrance ${o.address.entrance}`, o.address.floor && `floor ${o.address.floor}`].filter(Boolean).join(" · ") || "—"} />
            <Line label="Landmark" value={o.address.landmark} />
            <Line label="Coordinates" mono value={o.address.lat ? `${o.address.lat}, ${o.address.lon}` : "by landmark — not geocoded"} />
            {o.address.coordinateSource === "NOT_GEOCODED" ? (
              <Note>A mahalla house described by its landmark has no point. That is legitimate here, not a broken map.</Note>
            ) : null}
            <Line label="Zone and fee" value={<Gap adr={GAPS.zone} />} />
          </>
        ) : <p className="q-body-sm" style={{ margin: 0, color: inkMuted }}>{MODE[o.mode]} — no delivery address.</p>}
        {o.mode === "DELIVERY" ? (
          <>
            <Line label="Courier" value={cr
              ? <span style={{ display: "inline-flex", alignItems: "center", gap: 6 }}><span style={dotStyle(cr.onShift)} />{cr.name} · {cr.vehicle}</span>
              : "Not assigned"} />
            {cr && !cr.onShift ? (
              <Note tone="var(--q-error-text)">
                {cr.name} went off shift at {hhmm(cr.offShiftSince)} while still carrying this order. Reassign, or phone
                them. Shift state is <Gap adr={GAPS.shift} />
              </Note>
            ) : null}
            <Line label="Delivery service" value={<Gap adr={GAPS.shipment} />} />
            <Line label="Handover code" value={<Gap adr={GAPS.external} />} />
          </>
        ) : null}
      </Panel>

      <Panel title="Composition" note="Names and prices are fixed at the moment the order was placed.">
        {o.lines.map((ln) => (
          <div key={ln.n} style={{ display: "flex", gap: 10, padding: "7px 0", borderBottom: `1px solid ${hairline}` }}>
            <span className="q-caption q-tnum" style={{ color: inkSubtle, width: 14, flexShrink: 0 }}>{ln.n}</span>
            <div style={{ minWidth: 0, flex: 1 }}>
              <div className="q-body-sm" style={{ color: ink, textDecoration: ln.soldOut ? "line-through" : "none" }}>{ln.name}</div>
              {ln.variant ? <div className="q-caption" style={{ color: inkMuted }}>{ln.variant}</div> : null}
              {ln.mods.map((md) => (
                <div key={md.option} className="q-caption" style={{ color: inkMuted, paddingLeft: 10 }}>
                  {md.group} → {md.option}{md.price ? ` · ${uzs(md.price)}` : ""}
                </div>
              ))}
              {ln.note ? <div className="q-caption" style={{ color: blue }}>Customer comment — reveal to read (audited)</div> : null}
              {ln.soldOut ? <div className="q-caption" style={{ color: "var(--q-error-text)" }}>Sold out now — the line still renders, the reorder affordance does not</div> : null}
              {ln.discountReason ? <div className="q-caption" style={{ color: "var(--q-success-text)" }}>{ln.discountReason}</div> : null}
            </div>
            <span className="q-caption q-tnum" style={{ color: inkMuted, width: 26, textAlign: "right", flexShrink: 0 }}>×{ln.qty}</span>
            <span className="q-body-sm q-tnum" style={{ fontFamily: "var(--q-font-mono)", width: 108, textAlign: "right", flexShrink: 0 }}>
              {ln.baseMinor !== ln.finalMinor ? <span style={{ color: inkSubtle, textDecoration: "line-through", marginRight: 6 }}>{uzs(ln.baseMinor)}</span> : null}
              {uzs(ln.finalMinor)}
            </span>
          </div>
        ))}
      </Panel>

      <Panel title="Money and payment">
        {o.pricingAuthority === "EXTERNAL" ? (
          <p className="q-body-sm" style={{ margin: "0 0 10px", padding: "8px 10px", color: "var(--q-warning-text)", background: "var(--q-warning-tint)" }}>
            Partner prices — Qoida does not recompute them. Read from order_external_pricing and never rendered in the
            same styling as our own numbers. <Gap adr={GAPS.external} />
          </p>
        ) : null}
        <Line label="Items" value={uzs(m.subtotal)} mono />
        <Line label="Discount" value={m.discount ? `− ${uzs(m.discount)}` : "—"} mono />
        {(o.discounts || []).map((d) => (
          <div key={d.code} className="q-caption" style={{ color: inkSubtle, paddingLeft: 128 }}>{d.code} · {d.sourceType} {d.sourceId} v{d.sourceVersion}</div>
        ))}
        <Line label="Fees" value={m.fee ? uzs(m.fee) : "—"} mono />
        <Line label="Delivery charged" value={m.deliveryFee ? uzs(m.deliveryFee) : "—"} mono />
        <Line label="Delivery cost to us" value={m.providerCost ? uzs(m.providerCost) : "—"} mono tone={inkMuted} />
        <Line label="Total" value={uzs(m.total)} mono />
        <div className="q-caption" style={{ color: inkSubtle, paddingLeft: 128 }}>VAT 12% is inside the total — ADR 0018 prices are VAT-inclusive</div>
        {m.cashTendered ? (
          <>
            <Line label="Change from" value={uzs(m.cashTendered)} mono />
            <Line label="Change owed" value={uzs(m.cashTendered - m.total)} mono />
          </>
        ) : null}
        {!reconciles ? (
          <p className="q-body-sm" style={{ margin: "10px 0 0", padding: "8px 10px", color: "var(--q-error-text)", background: "var(--q-error-tint)" }}>
            The total does not reconcile: {uzs(m.subtotal)} + {uzs(m.tax)} + {uzs(m.fee)} − {uzs(m.discount)} ≠ {uzs(m.total)}.
            arithmetic_verified is false. This is data corruption, and hiding it would be worse than an ugly panel.
          </p>
        ) : null}
        <div style={{ marginTop: 12, paddingTop: 12, borderTop: `1px solid ${hairline}` }}>
          <Line label="Payment" value={<StatusPill tone={PAYMENT_PROJECTION[o.paymentProjection].tone}>{PAYMENT_PROJECTION[o.paymentProjection].label}</StatusPill>} />
          <Line label="Method" value={o.paymentMethod || <Gap adr={GAPS.payment} />} />
          <Line label="Transactions and refunds" value={<Gap adr={GAPS.payment} />} />
          <Note>
            The projection is display-only and there is no editable payment-status control: marking an unpaid order paid
            by hand leaves no evidence, and “the customer paid at the door” is a cash transaction, not a dropdown.
          </Note>
        </div>
        {o.outcome && reason ? (
          <div style={{ marginTop: 12, paddingTop: 12, borderTop: `1px solid ${hairline}` }}>
            <Line label="Cancelled" value={`${reason.name} · ${dt(o.outcome.at)} · ${ACTORS[o.outcome.by]?.name}`} />
            <Line label="Stock" value={DISPOSITION_TEXT[reason.disposition]} />
            <Line label="Liability" value={LIABILITY_TEXT[reason.liability]} />
            <Line label="Refund" value={REFUND_TEXT[reason.refund]} />
            <Line label="Note" value={o.outcome.note} />
          </div>
        ) : null}
      </Panel>

      <Panel title="Fiscalisation">
        {o.fiscal ? (
          <>
            <Line label="Status" value={o.fiscal.status} />
            {o.fiscal.blockedReason ? (
              <>
                <Line label="Blocked because" value={o.fiscal.blockedText} tone="var(--q-error-text)" />
                <Note>
                  Blocked is work, not an error, which is why this order sits in Attention. Fiscalise retries the same
                  document — never a second one, because two receipts for one order can only be corrected.
                </Note>
                <div style={{ marginTop: 8 }}>
                  <Button size="sm" variant="tertiary" onClick={() => onAct(o, { id: "fiscalize", label: "Fix the classification" })}>Fix the classification</Button>
                </div>
              </>
            ) : null}
            <Line label="Receipt" value={o.fiscal.receiptId} mono />
            <Line label="Fiscal sign" value={o.fiscal.sign} mono />
            <Line label="Attempts" value={o.fiscal.attempts} mono />
          </>
        ) : null}
        <div style={{ marginTop: 6 }}><Gap adr={GAPS.fiscal}>whole panel</Gap></div>
      </Panel>

      <Panel title="Integrations">
        {(o.processes || []).map((p) => {
          const bad = ["MANUAL_ACTION_REQUIRED", "FAILED_RETRYABLE"].includes(p.status);
          return (
            <div key={p.key} style={{ padding: "8px 10px", marginBottom: 6, background: bad ? "var(--q-error-tint)" : surface1, borderLeft: `3px solid ${bad ? "var(--q-error)" : "transparent"}` }}>
              <div style={{ display: "flex", gap: 8, alignItems: "baseline" }}>
                <span className="q-body-sm" style={{ color: ink }}>{p.name}</span>
                <span className="q-caption" style={{ color: bad ? "var(--q-error-text)" : inkMuted, marginLeft: "auto" }}>{p.status}</span>
              </div>
              <div className="q-caption" style={{ color: inkSubtle, marginTop: 2 }}>
                attempt {p.attempts}{p.nextAttemptAt ? ` · next ${hhmm(p.nextAttemptAt)}` : bad ? " · no further automatic attempt" : ""}
              </div>
              {p.error ? <div className="q-caption" style={{ color: "var(--q-error-text)", marginTop: 4 }}>{p.error}</div> : null}
              {p.fix ? (
                <button type="button" className="q-caption" style={{ ...linkBtn, paddingTop: 4 }} onClick={() => onAct(o, { id: "resolve", label: p.fix })}>{p.fix} →</button>
              ) : null}
            </div>
          );
        })}
        {posStuck ? (
          <Note tone="var(--q-warning-text)">
            Amendment is unavailable while a POS export attempt is unacknowledged. The failure being prevented is a
            kitchen holding two tickets for one order and cooking the first.
          </Note>
        ) : null}
        <Note>Only stock reservation is driven today; POS export is recognised by the schema and written by nothing — <Gap adr="ADR 0011 / 0012" /></Note>
      </Panel>

      <Panel title="Comments">
        <Line label="Customer, to the order" value={o.hasCustomerNote ? <span style={{ color: blue }}>Comment present — reveal to read</span> : "—"} />
        <Line label="Customer, to a line" value={o.lines.some((x) => x.note) ? <span style={{ color: blue }}>Comment present — reveal to read</span> : "—"} />
        <Line label="To the kitchen" value={o.kitchenNote || <Gap adr={GAPS.attribution} />} />
        <Line label="To the courier" value={<span className="q-caption" style={{ color: "var(--q-warning-text)" }}>no owning decision — a genuine gap</span>} />
        <Line label="Internal note" value={<span className="q-caption" style={{ color: "var(--q-warning-text)" }}>no owning decision — a genuine gap</span>} />
        <Note>
          The customer's own words are personal data and never render unrevealed; ours render in full. One
          undifferentiated list is how a customer's note ends up in a screenshot in a group chat.
        </Note>
      </Panel>

      <Panel title="Timeline and provenance">
        <Lanes order={o} />
        <div style={{ marginTop: 12, paddingTop: 12, borderTop: `1px solid ${hairline}` }}>
          <Line label="Revision" value={`${o.revision} · checkout snapshot`} />
          <Line label="Placed by" value={o.createdBy === "ac-sys" ? `by the customer · ${chan(o.channelId)?.name}` : ACTORS[o.createdBy]?.name} />
          <Line label="Accepted by" value={o.acceptedBy ? `${ACTORS[o.acceptedBy]?.name}${o.acceptedAt ? ` · ${hhmm(o.acceptedAt)}` : ""}` : "—"} />
          <Note>
            Written once and never overwritten — a leaderboard a later action can rewrite measures nothing. Revisions
            and attribution are <Gap adr={GAPS.revisions} />
          </Note>
        </div>
      </Panel>
    </aside>
  );
}

/* Three lanes, not one line: production and delivery are separate clocks that
 * can disagree, and a single bar destroys the difference between "the kitchen is
 * late" and "the courier is late". Timestamps print as visible text, never in a
 * title attribute — a tooltip is not keyboard-reachable. */
function Lanes({ order: o }) {
  const history = o.history || [
    { seq: 1, at: o.createdAt, from: null, to: "RECEIVED", trigger: "CHECKOUT", actor: o.createdBy },
    ...(o.acceptedAt ? [{ seq: 2, at: o.acceptedAt, from: "RECEIVED", to: "CONFIRMED", trigger: "APPROVAL_DECISION", actor: o.acceptedBy }] : []),
    ...(isTerminal(o) ? [{ seq: 3, at: o.completedAt || o.outcome?.at || o.createdAt, from: "PREPARING", to: o.status, trigger: "OPERATIONS_ACTION", actor: o.outcome?.by || o.acceptedBy || "ac-sys" }] : []),
  ];
  const gap = history.find((h, i) => i > 0 && h.seq !== history[i - 1].seq + 1);

  return (
    <>
      <div className="q-caption" style={{ color: inkSubtle, marginBottom: 6 }}>Commercial</div>
      {history.map((h) => {
        const current = h.to === o.status && !isTerminal(o);
        return (
          <div key={h.seq} style={{ display: "flex", gap: 10, padding: "4px 0" }}>
            <span style={{ width: 8, height: 8, borderRadius: "50%", marginTop: 5, flexShrink: 0, background: current ? "transparent" : ink, border: current ? `1px solid ${ink}` : "none" }} />
            <div style={{ minWidth: 0 }}>
              <div className="q-body-sm" style={{ color: ink }}>{h.from ? `${STATUS[h.from]?.label} → ` : ""}{STATUS[h.to]?.label}</div>
              <div className="q-caption q-tnum" style={{ color: inkSubtle }}>
                {hhmm(h.at)} · {h.trigger} · {ACTORS[h.actor]?.name || "—"}{h.reasonCode ? ` · ${h.reasonCode}` : ""}
              </div>
            </div>
          </div>
        );
      })}
      {gap ? (
        <Note tone="var(--q-error-text)">
          Sequence gap — entry {gap.seq - 1} is missing. sequence_number is allocated from the order's version, so a gap
          means a lost update, and hiding it would hide a bug.
        </Note>
      ) : null}
      {(o.decisions || []).filter((d) => !d.effective).map((d, i) => (
        <Note key={i} tone={inkMuted}>
          {ACTORS[d.actor]?.name} chose {d.action} at {hhmm(d.at)} — {d.note}.
          {ACTORS[d.actor]?.disabled ? " That account has since been disabled; the decision stays in the record." : ""}
        </Note>
      ))}
      <div className="q-caption" style={{ color: inkSubtle, marginTop: 12 }}>Kitchen lane — <Gap adr={GAPS.kitchenLane} /></div>
      <div className="q-caption" style={{ color: inkSubtle, marginTop: 4 }}>Delivery lane — <Gap adr={GAPS.shipment} /></div>
    </>
  );
}

/* ── dialogs ───────────────────────────────────────────────────────────────*/

function Modal({ title, onClose, children, footer, width = 560 }) {
  return (
    <div onClick={onClose} style={{ position: "fixed", inset: 0, background: "rgba(22,22,22,0.5)", zIndex: 60, display: "flex", alignItems: "center", justifyContent: "center", padding: 24 }}>
      <div onClick={(e) => e.stopPropagation()} style={{ width, maxWidth: "100%", maxHeight: "100%", background: canvas, border: `1px solid ${hairline}`, display: "flex", flexDirection: "column" }}>
        <div style={{ padding: "14px 20px", borderBottom: `1px solid ${hairline}`, display: "flex", alignItems: "center" }}>
          <span className="q-subhead" style={{ color: ink }}>{title}</span>
          <button type="button" onClick={onClose} aria-label="Close" className="q-body" style={{ marginLeft: "auto", background: "none", border: "none", color: inkMuted, cursor: "pointer" }}>✕</button>
        </div>
        <div style={{ padding: 20, overflowY: "auto" }}>{children}</div>
        <div style={{ padding: "12px 20px", borderTop: `1px solid ${hairline}`, display: "flex", gap: 8, justifyContent: "flex-end" }}>{footer}</div>
      </div>
    </div>
  );
}

/** The write-off is not an operator choice. The disposition, the liability and
 *  the refund are shown read-only from the reason an admin configured once —
 *  under pressure an operator picks whatever closes the dialog fastest, and the
 *  write-off rate becomes noise. */
function CancelDialog({ order: o, onClose, onDone }) {
  const [q, setQ] = useState("");
  const [pick, setPick] = useState(null);
  const [note, setNote] = useState("");
  const r = CANCEL_REASONS.find((x) => x.id === pick);
  const preCommit = ["RECEIVED", "PAYMENT_AUTHORIZING", "AWAITING_APPROVAL", "PAYMENT_FAILED"].includes(o.status);

  return (
    <Modal title={`Cancel order #${o.number}`} onClose={onClose}
      footer={<>
        <Button variant="ghost" size="sm" onClick={onClose}>Keep the order</Button>
        <Button variant="danger" size="sm" disabled={!r}
          onClick={() => onDone(`#${o.number} cancelled — ${r.name} · ${DISPOSITION_TEXT[r.disposition]} · ${REFUND_TEXT[r.refund]}`)}>Cancel the order</Button>
      </>}>
      <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search reasons" className="q-body-sm"
        style={{ height: 32, width: "100%", padding: "0 8px", border: "none", borderBottom: `1px solid ${hairline}`, outline: "none", marginBottom: 12 }} />
      <div style={{ border: `1px solid ${hairline}`, maxHeight: 190, overflowY: "auto" }}>
        {CANCEL_REASONS.filter((x) => x.name.toLowerCase().includes(q.toLowerCase())).map((x) => (
          <button key={x.id} type="button" onClick={() => setPick(x.id)} className="q-body-sm"
            style={{ display: "block", width: "100%", textAlign: "left", padding: "8px 12px", border: "none", borderBottom: `1px solid ${hairline}`, cursor: "pointer", background: pick === x.id ? "var(--q-info-tint)" : "transparent", color: pick === x.id ? "var(--q-info-text)" : ink }}>
            {x.name}<span className="q-caption" style={{ color: inkSubtle, marginLeft: 8 }}>{x.category}</span>
          </button>
        ))}
      </div>
      {r ? (
        <div style={{ marginTop: 16 }}>
          <div className="q-caption" style={{ color: inkSubtle, marginBottom: 4 }}>What the customer will be told</div>
          <p className="q-body-sm" style={{ margin: 0, padding: "8px 10px", background: surface1, color: ink }}>{r.customerText}</p>
          <div style={{ marginTop: 12 }}>
            <Line label="Stock" value={DISPOSITION_TEXT[r.disposition]} />
            <Line label="Liability" value={LIABILITY_TEXT[r.liability]} />
            <Line label="Refund" value={REFUND_TEXT[r.refund]} />
          </div>
          <Note>
            These three follow the reason and cannot be changed here. The reservation for this order is{" "}
            {preCommit ? "not yet committed, so cancelling releases it and the disposition is ignored"
              : "already committed, so the disposition decides — we have already cooked it"}.
          </Note>
          <textarea value={note} onChange={(e) => setNote(e.target.value)} rows={2} placeholder="Optional note (audited)" className="q-body-sm"
            style={{ width: "100%", marginTop: 12, padding: 8, border: `1px solid ${hairline}`, resize: "vertical" }} />
          <p className="q-body-sm" style={{ margin: "12px 0 0", color: ink }}>
            Order #{o.number} for {uzs(o.money.total)} will be cancelled.
            {r.refund === "FULL" ? ` The customer will be refunded ${uzs(o.money.total)}.` : " The customer will not be refunded."} This cannot be undone.
          </p>
        </div>
      ) : null}
    </Modal>
  );
}

/** A courier who is off shift appears with a hollow dot and is not selectable;
 *  the picker says which policy governs that. Reassigning is recorded, never
 *  blocked. */
function CourierDialog({ order: o, onClose, onDone }) {
  const [pick, setPick] = useState(null);
  return (
    <Modal title={`Assign a courier to #${o.number}`} onClose={onClose}
      footer={<>
        <Button variant="ghost" size="sm" onClick={onClose}>Cancel</Button>
        <Button size="sm" disabled={!pick} onClick={() => onDone(`#${o.number} assigned to ${courier(pick).name} — idempotent and audited`)}>Assign</Button>
      </>}>
      {COURIERS.map((c) => (
        <button key={c.id} type="button" disabled={!c.onShift} onClick={() => setPick(c.id)}
          style={{ display: "flex", gap: 10, alignItems: "center", width: "100%", textAlign: "left", padding: "10px 12px", marginBottom: 6, border: `1px solid ${pick === c.id ? blue : hairline}`, background: pick === c.id ? "var(--q-info-tint)" : "transparent", cursor: c.onShift ? "pointer" : "not-allowed", opacity: c.onShift ? 1 : 0.6 }}>
          <span style={dotStyle(c.onShift)} />
          <span style={{ minWidth: 0 }}>
            <span className="q-body-sm" style={{ color: ink }}>{c.name}</span>
            <span className="q-caption" style={{ color: inkSubtle, display: "block" }}>
              {c.vehicle} · {c.load} active · {c.km} km away{!c.onShift ? ` · off shift since ${hhmm(c.offShiftSince)}` : ""}
            </span>
          </span>
        </button>
      ))}
      <Note>
        This branch's policy does not allow out-of-shift assignment, so off-shift couriers are shown and not selectable
        rather than hidden — an operator looking for Shoxrux needs to see why he is not there. Courier data is{" "}
        <Gap adr={GAPS.courier} /> and shift state is <Gap adr={GAPS.shift} />.
      </Note>
    </Modal>
  );
}

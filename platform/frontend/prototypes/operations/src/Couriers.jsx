/* Couriers — the delivery fleet: who is working, who has what, what they are owed.
 *
 * The one decision that shapes this whole file: **there is one dispatch surface.**
 * A delivery carried by Alisher and a delivery carried by Yandex sit in the same
 * queue, in the same sort order, with the same columns. The difference is one
 * cell — Carried by — and one detail block. The operator never chooses a tab
 * before they can see their work. The in-house fleet is ADR 0042 and still
 * Proposed; the partners are built. That difference is a column, not a screen.
 *
 * Four views, in the order a Friday evening needs them:
 *
 *   Dispatch   §3   severity-sorted queue + fleet rail. The daily screen.
 *   Fleet      §5   find a courier, see whether they can work, open the record.
 *   Shifts     §7   who is working, and who should be but is not.
 *   Cash       §8   the tenant's money a courier is carrying, at the moment
 *                   they stop carrying it.
 *
 * Sorting is by severity, never by time (§3 sort order). Lateness is an overlay
 * on a status, never a status. A bulk action is offered only when it is valid for
 * every selected row (ADR 0039) — a mixed selection disables it and says how many
 * cannot, rather than quietly acting on the valid subset.
 *
 * Not prototyped, deliberately — see the return note: §4 live map, §6 the
 * seven-tab courier record, §7.1 roster editor, §9–§11 configuration, §12 payout
 * run, §14 statement, §15 reports.
 */

import { useEffect, useMemo, useRef, useState } from "react";
import {
  NOW, CASH_CEILING, ENFORCEMENT, BRANCHES, ZONES, COURIER_TYPES, COURIERS, PARTNERS,
  QUEUE, QUEUE_TABS, SHIFTS, COVERAGE, COVERAGE_START_HOUR, expandCoverage,
  HANDOVERS, HANDOVER_LINES, VARIANCE_REASONS, ledgerFor,
  branchName, zoneName, typeName, courierById, partnerById,
} from "./Couriers.data";
import {
  Button, Card, Drawer, EmptyState, Field, FilterBar, KpiTile, SearchInput, SectionHeader,
  Select, StatusPill, Tabs,
  canvas, hairline, ink, inkMuted, inkSubtle, surface1, blue, dt, uzs,
} from "./components";

/* ── local helpers ─────────────────────────────────────────────────────────*/

const hhmm = (iso) => dt(iso).slice(6);
const minsSince = (iso) => Math.round((new Date(NOW) - new Date(iso)) / 60000);
const hm = (sec) => `${Math.floor(sec / 3600)}:${String(Math.floor((sec % 3600) / 60)).padStart(2, "0")}`;
const mmss = (s) => `${String(Math.floor(s / 60)).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
const signed = (m) => (m < 0 ? `−${uzs(Math.abs(m))}` : uzs(m));

/* Severity is a signal channel of its own: a tint, a 3 px left rule and a caption
 * saying why. Three channels, strict precedence, and the fill is reserved for
 * "the platform does not know what happened" — a late order gets the rule and the
 * caption but no fill, so the two adjacent bands stay separable. Normal rows get
 * a transparent rule so alignment never jumps. */
const SEV = {
  problem: { tint: "var(--q-error-tint)", rule: "var(--q-error)", text: "var(--q-error-text)", order: 0 },
  late:    { tint: canvas,                rule: "var(--q-error)", text: "var(--q-error-text)", order: 1 },
  risk:    { tint: "var(--q-warning-tint)", rule: "var(--q-warning)", text: "var(--q-warning-text)", order: 2 },
};
const sevOf = (r) => (r.severity ? SEV[r.severity] : null);
const sevOrder = (r) => (r.severity ? SEV[r.severity].order : 3);

/* The two status axes (§2). Conflating them is the single most common mistake in
 * this domain, so they are two maps and never one. */
const WORK = {
  RESTRICTED: { tone: "suspended", label: "Restricted" },
  OFF_SHIFT:  { tone: "neutral",   label: "Off shift" },
  STALE:      { tone: "pending",   label: "No signal" },
  OFFERED:    { tone: "info",      label: "Offered" },
  AT_BRANCH:  { tone: "info",      label: "At branch" },
  CARRYING:   { tone: "info",      label: "Carrying" },
  IDLE:       { tone: "healthy",   label: "Idle" },
};
const ACCOUNT = {
  ACTIVE: { tone: "active", label: "Active" },
  SUSPENDED: { tone: "suspended", label: "Suspended" },
  PENDING_ACTIVATION: { tone: "pending", label: "Pending" },
  ARCHIVED: { tone: "neutral", label: "Archived" },
};

const stageDone = (list, name) => !!list.find((s) => s.s === name)?.t;
const isAssignable = (c) => c.account === "ACTIVE" && !["OFF_SHIFT", "RESTRICTED"].includes(c.work);
const maxLoad = (c) => COURIER_TYPES.find((t) => t.id === c.typeId)?.maxConcurrent ?? 3;
const hasRoom = (c) => c.load < maxLoad(c);

/* Rail order (§3.1): assignable with room → at capacity → healthy partners →
 * no signal → off shift → restricted → open circuits. */
const railRank = (u) => {
  if (u.kind === "partner") return u.health === "open" ? 6 : 2;
  if (u.work === "RESTRICTED" || u.account === "SUSPENDED") return 5;
  if (u.work === "OFF_SHIFT") return 4;
  if (u.work === "STALE") return 3;
  return hasRoom(u) ? 0 : 1;
};

/* Shared table chrome. These four tables are hand-rolled rather than the shared
 * DataTable because every one of them carries a severity rule in the row's own
 * left border, and a normal row needs that border transparent rather than absent
 * so the alignment of the whole column never jumps. */
const TH = { textAlign: "left", padding: "10px 12px", background: surface1, color: inkMuted, fontWeight: 600, borderBottom: `1px solid ${hairline}`, whiteSpace: "nowrap" };
const THR = { ...TH, textAlign: "right" };
const TD = { padding: "10px 12px", color: ink, verticalAlign: "top" };
const TDR = { ...TD, textAlign: "right", whiteSpace: "nowrap" };

/* A header row. `r` right-aligns, `w` fixes a width, a bare string is a plain
 * left-aligned column. */
function Head({ cols }) {
  return (
    <thead>
      <tr>
        {cols.map((c, i) => {
          const o = typeof c === "string" ? { l: c } : c;
          return <th key={i} className="q-caption" style={{ ...(o.r ? THR : TH), ...(o.w ? { width: o.w } : null) }}>{o.l}</th>;
        })}
      </tr>
    </thead>
  );
}

/* ── small primitives ──────────────────────────────────────────────────────*/

function Block({ title, description, right, children }) {
  return (
    <section style={{ marginTop: 28 }}>
      <div style={{ display: "flex", alignItems: "flex-end", gap: 16, marginBottom: 10 }}>
        <div style={{ minWidth: 0 }}>
          <h2 className="q-subhead" style={{ margin: 0, color: ink }}>{title}</h2>
          {description ? <p className="q-body-sm" style={{ margin: "2px 0 0", color: inkMuted }}>{description}</p> : null}
        </div>
        {right ? <div style={{ marginLeft: "auto", flexShrink: 0 }}>{right}</div> : null}
      </div>
      {children}
    </section>
  );
}

/* A panel that states a fact about the whole screen. Tint plus a 3 px left rule
 * plus prose — never a coloured strip on its own. */
function Note({ tone = "neutral", title, children, action }) {
  const map = {
    warning: { tint: "var(--q-warning-tint)", rule: "var(--q-warning)", text: "var(--q-warning-text)" },
    error: { tint: "var(--q-error-tint)", rule: "var(--q-error)", text: "var(--q-error-text)" },
    neutral: { tint: surface1, rule: ink, text: inkMuted },
  }[tone];
  return (
    <div style={{ display: "flex", gap: 12, alignItems: "flex-start", background: map.tint, border: `1px solid ${hairline}`, borderLeft: `3px solid ${map.rule}`, padding: "10px 14px", marginBottom: 12 }}>
      <div style={{ minWidth: 0 }}>
        <div className="q-emphasis" style={{ color: map.text }}>{title}</div>
        {children ? <div className="q-caption" style={{ color: map.text, marginTop: 2 }}>{children}</div> : null}
      </div>
      {action ? <div style={{ marginLeft: "auto", flexShrink: 0 }}>{action}</div> : null}
    </div>
  );
}

/* Load as squares rather than a number alone — a dispatcher reads "full" faster
 * than they read "3 of 3". Plain divs; there is no chart library here. */
function LoadSquares({ load, max }) {
  return (
    <span style={{ display: "inline-flex", gap: 2, verticalAlign: "middle" }}>
      {Array.from({ length: max }).map((_, i) => (
        <span key={i} style={{ width: 10, height: 4, background: i < load ? (load >= max ? "var(--q-warning)" : ink) : "var(--q-surface-2)" }} />
      ))}
    </span>
  );
}

/* Column 3 of the queue, compact. Production and delivery are two clocks that can
 * disagree, and that disagreement is the difference between "the kitchen is late"
 * and "the courier is late" — which decides LATE vs LATE_EXCUSED on the courier's
 * pay. A single linear bar destroys the distinction the pay model rests on, so
 * there are two strips. Every stage label and every completed timestamp is
 * visible text in the peek panel; the table carries the strip plus the latest
 * completed stage, because eleven labels do not fit beside eleven columns. */
function Strip({ name, stages }) {
  const lastDone = [...stages].reverse().find((s) => s.t);
  return (
    <div style={{ minWidth: 132 }}>
      <div style={{ display: "flex", gap: 2, marginBottom: 3 }}>
        {stages.map((s) => (
          <span key={s.s} title={`${s.s}${s.t ? ` ${s.t}` : ""}`}
            style={{ width: 14, height: 4, background: s.t ? ink : "var(--q-surface-2)" }} />
        ))}
      </div>
      <div className="q-caption" style={{ color: inkSubtle, whiteSpace: "nowrap" }}>
        {name} · {lastDone ? `${lastDone.s.toLowerCase()} ${lastDone.t}` : "not started"}
      </div>
    </div>
  );
}

function FullPipeline({ stages, name }) {
  return (
    <div style={{ marginBottom: 12 }}>
      <div className="q-caption" style={{ color: inkSubtle, marginBottom: 6 }}>{name}</div>
      <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
        {stages.map((s) => (
          <div key={s.s} style={{ minWidth: 78 }}>
            <div style={{ height: 4, background: s.t ? ink : "var(--q-surface-2)", marginBottom: 4 }} />
            <div className="q-caption" style={{ color: s.t ? ink : inkSubtle }}>{s.s}</div>
            <div className="q-caption q-tnum" style={{ color: inkSubtle }}>{s.t || "—"}</div>
          </div>
        ))}
      </div>
    </div>
  );
}

/* Modal state is the record being acted on, never a boolean, so the copy can name
 * the object. Confirmation is required for anything that changes what a person is
 * paid, costs money at a partner, or is not reversible — and for nothing else.
 * Assignment is none of those, and a dispatcher does it forty times an evening. */
function Confirm({ title, body, danger, confirmLabel, onConfirm, onCancel }) {
  return (
    <div onClick={onCancel} style={{ position: "fixed", inset: 0, background: "rgba(22,22,22,0.5)", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 60, padding: 24 }}>
      <div onClick={(e) => e.stopPropagation()} style={{ background: canvas, border: `1px solid ${hairline}`, width: 520, maxWidth: "100%" }}>
        <div style={{ padding: 24 }}>
          <div className="q-subhead" style={{ color: ink }}>{title}</div>
          <div className="q-body-sm" style={{ color: inkMuted, marginTop: 8 }}>{body}</div>
        </div>
        <div style={{ display: "flex", gap: 8, justifyContent: "flex-end", padding: 16, borderTop: `1px solid ${hairline}` }}>
          <Button variant="ghost" size="sm" onClick={onCancel}>Cancel</Button>
          <Button variant={danger ? "danger" : "primary"} size="sm" onClick={onConfirm}>{confirmLabel}</Button>
        </div>
      </div>
    </div>
  );
}

/* ── fleet rail ────────────────────────────────────────────────────────────
 * One card per assignable unit, in-house and partner in the same list. Off-shift
 * couriers stay, disabled, with the reason: "where is Shoxrux" is a question the
 * rail should answer rather than dodge, and removing the row makes the dispatcher
 * phone him to find out. */

const RAIL_ACTIONS = {
  message: "Message", endShift: "End shift", restrict: "Restrict",
  handover: "Prompt a handover", openShift: "Open a shift", reinstate: "Reinstate",
};

function CourierCard({ c, selected, onSelect, onOpen }) {
  const w = WORK[c.work];
  const max = maxLoad(c);
  const blocked = !isAssignable(c);
  return (
    <div
      onClick={() => onSelect(c.id)}
      style={{
        borderBottom: `1px solid ${hairline}`, borderLeft: `3px solid ${selected ? blue : "transparent"}`,
        background: selected ? "var(--q-info-tint)" : canvas, padding: "10px 12px", cursor: "pointer",
        opacity: blocked ? 0.62 : 1,
      }}
    >
      <div style={{ display: "flex", gap: 8, alignItems: "baseline" }}>
        <span className="q-emphasis" style={{ color: ink, minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{c.name}</span>
        <span className="q-caption q-tnum" style={{ marginLeft: "auto", color: inkSubtle, flexShrink: 0 }}>{c.load} / {max}</span>
        <LoadSquares load={c.load} max={max} />
      </div>
      <div style={{ display: "flex", gap: 6, alignItems: "center", marginTop: 6, flexWrap: "wrap" }}>
        <StatusPill tone={w.tone}>{w.label}</StatusPill>
        <span className="q-caption" style={{ color: inkSubtle }}>{typeName(c.typeId)}</span>
      </div>
      <div className="q-caption" style={{ color: c.work === "RESTRICTED" ? "var(--q-error-text)" : inkMuted, marginTop: 4 }}>{c.live}</div>
      <div className="q-caption q-tnum" style={{ color: inkSubtle, marginTop: 4, display: "flex", gap: 10, flexWrap: "wrap" }}>
        {c.shiftOpenedAt ? <span>{hm(minsSince(c.shiftOpenedAt) * 60)} on shift</span> : null}
        <span>{c.deliveredToday} today</span>
        {c.batteryPercent != null ? <span>{c.batteryPercent}%{c.charging ? " charging" : ""}</span> : null}
        {c.positionAgeMin > 2 ? <span>position {c.positionAgeMin} min old</span> : null}
      </div>
      {c.cashOnHand > 0 ? (
        <div className="q-caption q-tnum" style={{ color: c.cashOnHand > CASH_CEILING ? "var(--q-warning-text)" : inkMuted, marginTop: 4 }}>
          Cash {uzs(c.cashOnHand)}{c.cashOnHand > CASH_CEILING ? " — over the ceiling, prompt a handover" : ""}
        </div>
      ) : null}
      {c.phoneRevealed ? (
        <a href={`tel:${c.phone}`} onClick={(e) => e.stopPropagation()} className="q-caption"
          style={{ fontFamily: "var(--q-font-mono)", color: blue, marginTop: 4, display: "inline-block" }}>{c.phone}</a>
      ) : (
        <div className="q-caption" style={{ color: inkSubtle, marginTop: 4 }}>Phone hidden — needs the reveal capability (ADR 0029)</div>
      )}
      {c.blockReason ? <div className="q-caption" style={{ color: inkMuted, marginTop: 4 }}>{c.blockReason}</div> : null}
      {/* Affordances are omitted rather than disabled when an action is not
          permitted, driven by the server actions[] array — disabled-with-no-reason
          is the worst of both. */}
      <div style={{ marginTop: 6, display: "flex", gap: 12, flexWrap: "wrap" }}>
        {[["open", "Open record"], ...c.actions.map((a) => [a, RAIL_ACTIONS[a]])].filter(([, l]) => l).map(([a, label]) => (
          <button key={a} type="button" onClick={(e) => { e.stopPropagation(); if (a === "open") onOpen(c.id); }}
            className="q-caption" style={{ background: "none", border: "none", color: blue, padding: 0, cursor: "pointer" }}>{label}</button>
        ))}
      </div>
    </div>
  );
}

function PartnerCard({ p, selected, onSelect }) {
  const tone = p.health === "healthy" ? "healthy" : p.health === "degraded" ? "degraded" : "failed";
  const label = p.health === "healthy" ? "Healthy" : p.health === "degraded" ? "Degraded" : "Circuit open";
  return (
    <div
      onClick={() => onSelect(p.id)}
      style={{
        borderBottom: `1px solid ${hairline}`, borderLeft: `3px solid ${selected ? blue : "transparent"}`,
        background: selected ? "var(--q-info-tint)" : canvas, padding: "10px 12px", cursor: "pointer",
        opacity: p.health === "open" ? 0.62 : 1,
      }}
    >
      <div style={{ display: "flex", gap: 8, alignItems: "baseline" }}>
        <span className="q-emphasis" style={{ color: ink }}>{p.name}</span>
        <span className="q-caption q-tnum" style={{ marginLeft: "auto", color: inkSubtle }}>{p.inFlight} in flight</span>
      </div>
      <div style={{ marginTop: 6 }}><StatusPill tone={tone}>{label}</StatusPill></div>
      <div className="q-caption" style={{ color: inkMuted, marginTop: 4 }}>{p.healthNote}</div>
      <div className="q-caption q-tnum" style={{ color: inkSubtle, marginTop: 4 }}>
        Last quote {uzs(p.lastQuoteMinor)} · {hhmm(p.lastQuoteAt)} · accepted {p.acceptanceToday}% of {p.requestedToday}
      </div>
      <div className="q-caption" style={{ color: inkSubtle, marginTop: 4 }}>
        {p.capabilities.map((cap) => `${cap.key}: ${cap.ok ? "yes" : "no"}`).join(" · ")}
      </div>
    </div>
  );
}

/* ── the assign control ────────────────────────────────────────────────────
 * A native <select> in the row, not a dialog and not only a drag target. The
 * dispatcher does this mid-phone-call with one hand; drag is unreachable by
 * keyboard; and a native listbox escapes the table's horizontal scroll container
 * that would clip an absolutely-positioned popover. Candidates default to the
 * order's branch — the legacy behaviour staff already have in their hands — with
 * an explicit escape to every branch. */
function AssignSelect({ row, allBranches, onAssign }) {
  const inBranch = (c) => allBranches || c.branchIds.includes(row.branchId);
  const free = COURIERS.filter((c) => isAssignable(c) && hasRoom(c) && inBranch(c));
  const busy = COURIERS.filter((c) => isAssignable(c) && !hasRoom(c) && inBranch(c));
  const cannot = COURIERS.filter((c) => !isAssignable(c) && inBranch(c));
  return (
    <select
      value=""
      onClick={(e) => e.stopPropagation()}
      onChange={(e) => { e.stopPropagation(); onAssign(row, e.target.value); }}
      className="q-body-sm"
      /* A fixed width, not a minimum: a native select otherwise grows to its widest
       * option, and one courier called Muhammadaziz Abdurahmonov would set the width
       * of a whole column. */
      style={{ height: 32, width: 184, maxWidth: "100%", padding: "0 8px", background: canvas, color: ink, border: `1px solid ${hairline}`, borderRadius: "var(--q-radius)" }}
    >
      <option value="">Assign…</option>
      <optgroup label="In-house — free capacity">
        {free.map((c) => <option key={c.id} value={c.id}>{c.name} — {c.load}/{maxLoad(c)}, {c.deliveredToday} today</option>)}
      </optgroup>
      <optgroup label="In-house — at capacity">
        {busy.map((c) => <option key={c.id} value={c.id} disabled>{c.name} — full at {maxLoad(c)}</option>)}
      </optgroup>
      <optgroup label="Partners">
        {PARTNERS.map((p) => (
          <option key={p.id} value={p.id} disabled={p.health === "open" || !p.zonesCovered.includes(row.zoneId)}>
            {p.name}{p.health === "open" ? " — circuit open" : !p.zonesCovered.includes(row.zoneId) ? ` — ${zoneName(row.zoneId)} out of zone` : ` — quote ${uzs(p.lastQuoteMinor)}`}
          </option>
        ))}
      </optgroup>
      <optgroup label="Cannot be assigned">
        {cannot.map((c) => <option key={c.id} value={c.id} disabled>{c.name} — {WORK[c.work].label.toLowerCase()}</option>)}
      </optgroup>
    </select>
  );
}

/* ── bulk actions (ADR 0039) ───────────────────────────────────────────────
 * An action is offered only when it is valid for every selected row. A mixed
 * selection disables it and says how many cannot and why — it never silently
 * acts on the valid subset, because a partial bulk that reports success is how a
 * dispatcher re-runs it and double-books every courier that succeeded. */
const BULK_ACTIONS = [
  {
    id: "assign", label: "Assign to one courier",
    ok: (r, sel) => r.carriedBy.kind !== "partner" && !stageDone(r.logistics, "Picked up")
      && sel.every((o) => o.branchId === sel[0].branchId),
    why: "are already picked up, carried by a partner, or at a different branch",
  },
  { id: "external", label: "Call an external courier", ok: (r) => r.carriedBy.kind === "none", why: "already have an assignment" },
  { id: "print", label: "Print", ok: () => true, why: "" },
  {
    id: "cancel", label: "Cancel shipment",
    ok: (r) => ["partner", "unknown", "courier"].includes(r.carriedBy.kind),
    why: "have no active shipment to cancel",
  },
];

/* ── view 1: dispatch board ────────────────────────────────────────────────*/

function Dispatch({ onOpenCourier }) {
  const [statusTab, setStatusTab] = useState("all");
  const [branch, setBranch] = useState("all");
  const [zone, setZone] = useState("all");
  const [source, setSource] = useState("any");
  const [carriedBy, setCarriedBy] = useState(null); /* set by clicking a rail card */
  const [search, setSearch] = useState("");
  const [allBranches, setAllBranches] = useState(false);
  const [selected, setSelected] = useState([]);
  const [cursor, setCursor] = useState(-1);
  const [peek, setPeek] = useState(null);
  const [confirm, setConfirm] = useState(null);
  const [result, setResult] = useState(null);
  const searchRef = useRef(null);

  /* Counts are computed before filtering (§20) so a count never collapses as the
   * selection narrows. */
  const counts = useMemo(() => ({
    needs: QUEUE.filter((r) => r.carriedBy.kind === "none").length,
    offered: QUEUE.filter((r) => r.carriedBy.kind === "offered").length,
    toBranch: QUEUE.filter((r) => ["courier", "partner", "unknown"].includes(r.carriedBy.kind) && !stageDone(r.logistics, "Picked up")).length,
    road: QUEUE.filter((r) => stageDone(r.logistics, "Picked up") && !stageDone(r.logistics, "Delivered")).length,
    problem: QUEUE.filter((r) => r.severity === "problem").length,
    all: QUEUE.length,
  }), []);

  const rows = useMemo(() => {
    const byTab = {
      needs: (r) => r.carriedBy.kind === "none",
      offered: (r) => r.carriedBy.kind === "offered",
      toBranch: (r) => ["courier", "partner", "unknown"].includes(r.carriedBy.kind) && !stageDone(r.logistics, "Picked up"),
      road: (r) => stageDone(r.logistics, "Picked up") && !stageDone(r.logistics, "Delivered"),
      problem: (r) => r.severity === "problem",
      all: () => true,
    }[statusTab];
    const q = search.trim().toLowerCase();
    return QUEUE
      .filter(byTab)
      .filter((r) => branch === "all" || r.branchId === branch)
      .filter((r) => zone === "all" || r.zoneId === zone)
      .filter((r) => source === "any"
        || (source === "ours" && ["courier", "offered"].includes(r.carriedBy.kind))
        || (source === "partners" && ["partner", "unknown"].includes(r.carriedBy.kind)))
      .filter((r) => !carriedBy || r.carriedBy.courierId === carriedBy || r.carriedBy.partnerId === carriedBy)
      .filter((r) => !q || r.shortId.includes(q) || r.customer.toLowerCase().includes(q) || r.address.toLowerCase().includes(q))
      /* Severity first, then the stage rank, then the promise. Never creation
       * time: a queue sorted by time makes the operator find the emergency
       * instead of showing it to them. "Courier has no signal while carrying" is
       * a late-stage row that this lifts to the top, which is the point. */
      .sort((a, b) => sevOrder(a) - sevOrder(b) || a.rank - b.rank
        || new Date(a.promisedEnd) - new Date(b.promisedEnd) || a.shortId.localeCompare(b.shortId));
  }, [statusTab, branch, zone, source, carriedBy, search]);

  /* Keyboard. Speed matters here more than anywhere else in the console. */
  useEffect(() => {
    const onKey = (e) => {
      if (e.target.tagName === "INPUT" || e.target.tagName === "SELECT") {
        if (e.key === "Escape") e.target.blur();
        return;
      }
      if (e.key === "j") setCursor((c) => Math.min(c + 1, rows.length - 1));
      else if (e.key === "k") setCursor((c) => Math.max(c - 1, 0));
      else if (e.key === "x" && rows[cursor]) {
        const id = rows[cursor].id;
        setSelected((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id]));
      } else if (e.key === " " && rows[cursor]) { e.preventDefault(); setPeek(rows[cursor].id); }
      else if (e.key === "/") { e.preventDefault(); searchRef.current?.querySelector("input")?.focus(); }
      else if (e.key === "Escape") {
        if (peek) setPeek(null);
        else if (selected.length) setSelected([]);
        else { setStatusTab("all"); setBranch("all"); setZone("all"); setSource("any"); setCarriedBy(null); setSearch(""); }
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  const selRows = QUEUE.filter((r) => selected.includes(r.id));
  const railUnits = [
    ...COURIERS.map((c) => ({ ...c, kind: "courier" })),
    ...PARTNERS.map((p) => ({ ...p, kind: "partner" })),
  ].sort((a, b) => railRank(a) - railRank(b) || a.name.localeCompare(b.name));

  const onShift = COURIERS.filter((c) => c.shiftOpenedAt).length;
  const openCircuits = PARTNERS.filter((p) => p.health === "open");
  const peekRow = QUEUE.find((r) => r.id === peek);

  const applyBulk = (action) => {
    const problems = action.id === "cancel"
      ? selRows.filter((r) => r.carriedBy.partnerId === "pt-noor").map((r) => ({ id: r.shortId, why: "Noor cannot tell us what cancelling costs" }))
      : [];
    setResult({ label: action.label, applied: selRows.length - problems.length, problems, bulkId: "b-8841" });
    setSelected([]);
    setConfirm(null);
  };

  return (
    <>
      <SectionHeader
        title="Dispatch"
        description="Every delivery in flight, ours and our partners', in one queue ordered by what needs a human first. Times are Asia/Tashkent."
        right={<span className="q-caption q-tnum" style={{ color: inkSubtle }}>Live as of {hhmm(NOW)} · polling every 15 s</span>}
      />

      {counts.problem > 0 ? (
        <Note tone="error" title={`${counts.problem} deliveries where the platform does not know what happened`}>
          An uncertain provider outcome is two couriers or none, and a double charge. These outrank every late order.
        </Note>
      ) : null}
      {openCircuits.length ? (
        <Note tone="warning" title={`${openCircuits.map((p) => p.name).join(" and ")} — circuit open`}>
          {openCircuits.map((p) => `${p.name}: ${p.healthNote}`).join(" · ")}. In-house assignment is unaffected.
        </Note>
      ) : null}
      {onShift === 0 ? (
        <Note tone="warning" title="No courier has an open shift">Orders can still be sent to Yandex or Noor.</Note>
      ) : null}
      <Note tone="neutral" title={`Shift enforcement: ${ENFORCEMENT.label}`}>
        Resolved at {ENFORCEMENT.resolvedAt} level, {ENFORCEMENT.policyVersion}. An off-shift courier still appears as a candidate and the attempt is logged.
      </Note>

      <div style={{ display: "flex", gap: 16, alignItems: "flex-start" }}>
        {/* ── queue ─────────────────────────────────────────────────────── */}
        <div style={{ flex: 1, minWidth: 0 }}>
          <Tabs
            tabs={QUEUE_TABS.map((t) => ({ ...t, count: counts[t.id] }))}
            active={statusTab}
            onChange={(id) => { setStatusTab(id); setCursor(-1); }}
          />

          <FilterBar>
            <div ref={searchRef}><SearchInput value={search} onChange={setSearch} placeholder="Order, customer or street  ( / )" /></div>
            <Select label="Branch" value={branch} onChange={setBranch}
              options={[{ value: "all", label: "Every branch I hold" }, ...BRANCHES.map((b) => ({ value: b.id, label: b.name }))]} />
            <Select label="Zone" value={zone} onChange={setZone}
              options={[{ value: "all", label: "Any zone" }, ...ZONES.map((z) => ({ value: z.id, label: z.name }))]} />
            <Select label="Source" value={source} onChange={setSource}
              options={[{ value: "any", label: "Any" }, { value: "ours", label: "Our couriers" }, { value: "partners", label: "Partners" }]} />
            <label className="q-caption" style={{ display: "inline-flex", alignItems: "center", gap: 6, color: inkMuted }}>
              <input type="checkbox" checked={allBranches} onChange={(e) => setAllBranches(e.target.checked)} />
              Search couriers across all branches
            </label>
            {carriedBy ? (
              <Button variant="ghost" size="sm" onClick={() => setCarriedBy(null)}>
                Filtered to {courierById(carriedBy)?.name || partnerById(carriedBy)?.name} — clear
              </Button>
            ) : null}
          </FilterBar>

          {selected.length ? (
            <div style={{ border: `1px solid ${hairline}`, borderTop: "none", background: "var(--q-info-tint)", padding: 12, display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
              <span className="q-emphasis" style={{ color: "var(--q-info-text)" }}>{selected.length} selected</span>
              <button type="button" onClick={() => setSelected(rows.map((r) => r.id))} className="q-caption"
                style={{ background: "none", border: "none", color: blue, cursor: "pointer", padding: 0 }}>
                Select all {rows.length} in this filter
              </button>
              <span style={{ marginLeft: "auto", display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
                {BULK_ACTIONS.map((a) => {
                  const bad = selRows.filter((r) => !a.ok(r, selRows));
                  return (
                    <span key={a.id} style={{ display: "inline-flex", flexDirection: "column", alignItems: "flex-end" }}>
                      <Button variant={a.id === "cancel" ? "danger" : "tertiary"} size="sm" disabled={bad.length > 0}
                        onClick={() => setConfirm({ kind: "bulk", action: a })}>{a.label}</Button>
                      {bad.length ? (
                        <span className="q-caption" style={{ color: inkMuted, marginTop: 2, maxWidth: 240, textAlign: "right" }}>
                          {bad.length} of {selRows.length} selected {a.why}
                        </span>
                      ) : null}
                    </span>
                  );
                })}
                <Button variant="ghost" size="sm" onClick={() => setSelected([])}>Clear</Button>
              </span>
            </div>
          ) : null}

          {result ? (
            <div style={{ border: `1px solid ${hairline}`, borderTop: "none", padding: 12, background: canvas }}>
              <div className="q-emphasis" style={{ color: ink }}>
                {result.label}: {result.applied} applied, {result.problems.length} problems
              </div>
              <div className="q-caption" style={{ color: inkSubtle, fontFamily: "var(--q-font-mono)", marginTop: 2 }}>bulk_operation_id {result.bulkId}</div>
              {result.problems.map((p) => (
                <div key={p.id} className="q-caption" style={{ color: "var(--q-error-text)", marginTop: 4 }}>{p.id} — {p.why}</div>
              ))}
              <div style={{ marginTop: 8, display: "flex", gap: 8 }}>
                {result.problems.length ? <Button variant="tertiary" size="sm" onClick={() => setResult(null)}>Retry the {result.problems.length}</Button> : null}
                <Button variant="ghost" size="sm" onClick={() => setResult(null)}>Dismiss</Button>
              </div>
            </div>
          ) : null}

          <QueueTable
            rows={rows} selected={selected} setSelected={setSelected} cursor={cursor} setCursor={setCursor}
            allBranches={allBranches} onPeek={setPeek}
            onAssign={(row, unit) => setResult({
              label: `Order ${row.shortId} offered to ${courierById(unit)?.name || partnerById(unit)?.name || unit}`,
              applied: 1, problems: [],
              bulkId: "one command · not confirmed, because assignment is reversible and a dispatcher does it forty times an evening",
            })}
            onConfirm={setConfirm}
          />

          <p className="q-caption" style={{ color: inkSubtle, marginTop: 8, maxWidth: 760 }}>
            Dragging a row onto a fleet card would assign it and is worth building as an accelerant, but the in-row
            combobox is the authoritative control: drag is unreachable by keyboard and a drop onto a full or off-shift
            card must fail loudly. Not prototyped here. The map (§4) is not prototyped either — a drawn-on map would
            imply a courier position feed that ADR 0045 has not built.
          </p>
        </div>

        {/* ── fleet rail ────────────────────────────────────────────────── */}
        <aside style={{ width: 320, flexShrink: 0, border: `1px solid ${hairline}`, background: canvas, maxHeight: "calc(100vh - 96px)", overflowY: "auto", position: "sticky", top: 72 }}>
          <div style={{ padding: "10px 12px", borderBottom: `1px solid ${hairline}`, background: surface1 }}>
            <div className="q-emphasis" style={{ color: ink }}>Fleet ({COURIERS.length + PARTNERS.length})</div>
            <div className="q-caption" style={{ color: inkSubtle, marginTop: 2 }}>
              {onShift} on shift · click a card to filter the queue
            </div>
          </div>
          {railUnits.map((u) => (u.kind === "partner"
            ? <PartnerCard key={u.id} p={u} selected={carriedBy === u.id} onSelect={(id) => setCarriedBy(carriedBy === id ? null : id)} />
            : <CourierCard key={u.id} c={u} selected={carriedBy === u.id}
                onSelect={(id) => setCarriedBy(carriedBy === id ? null : id)} onOpen={onOpenCourier} />))}
        </aside>
      </div>

      {peekRow ? (
        <Drawer title={`Order ${peekRow.shortId} — ${peekRow.customer}`} onClose={() => setPeek(null)}>
          {peekRow.reason ? <Note tone={peekRow.severity === "risk" ? "warning" : "error"} title="Why this row is here">{peekRow.reason}</Note> : null}
          <FullPipeline name="Kitchen" stages={peekRow.kitchen} />
          <FullPipeline name="Logistics" stages={peekRow.logistics} />
          <div style={{ display: "grid", gridTemplateColumns: "repeat(2, minmax(0,1fr))", gap: 16, marginTop: 8 }}>
            <Field label="Branch" value={branchName(peekRow.branchId)} />
            <Field label="Zone" value={zoneName(peekRow.zoneId)} />
            <Field label="Address" value={peekRow.address} />
            <Field label="Distance" value={`${peekRow.distanceKm.toFixed(1)} km`} />
            <Field label="Promised by" value={hhmm(peekRow.promisedEnd)} />
            <Field label="Latest assignment" value={hhmm(peekRow.latestAssignmentAt)} />
            <Field label="Customer phone" value={peekRow.phone} mono />
            <Field label="Payment" value={peekRow.payment.collectMinor ? `${peekRow.payment.method} — collect ${uzs(peekRow.payment.collectMinor)}` : peekRow.payment.method} />
            <Field label="Total" value={uzs(peekRow.totalMinor)} />
            <Field label="Carried by" value={<CarriedBy row={peekRow} />} />
          </div>
        </Drawer>
      ) : null}

      {confirm?.kind === "bulk" ? (
        <Confirm
          title={`${confirm.action.label} — ${selRows.length} orders`}
          danger={confirm.action.id === "cancel"}
          confirmLabel={confirm.action.label}
          body={confirm.action.id === "cancel"
            ? `Cancelling ${selRows.length} shipments. The cost is unknown for the Noor rows — Noor cannot tell us what cancelling costs. Each order is cancelled as its own command under one bulk_operation_id; a failure on one does not roll back the others.`
            : `${selRows.length} orders at ${branchName(selRows[0]?.branchId)}. Runs as ${selRows.length} independent commands under one bulk_operation_id, each idempotent on the order.`}
          onConfirm={() => applyBulk(confirm.action)}
          onCancel={() => setConfirm(null)}
        />
      ) : null}
      {confirm?.kind === "unassign" ? (
        <Confirm
          title={`Unassign order ${confirm.row.shortId}`}
          confirmLabel="Unassign"
          body={`${confirm.row.customer}'s order returns to sourcing. ${confirm.row.carriedBy.kind === "offered" ? "The outstanding offer is withdrawn." : ""}`}
          onConfirm={() => { setResult({ label: `Unassigned ${confirm.row.shortId}`, applied: 1, problems: [], bulkId: "single" }); setConfirm(null); }}
          onCancel={() => setConfirm(null)}
        />
      ) : null}
    </>
  );
}

/* Column 9. One cell, three shapes — a courier, a partner, or the assign control.
 * This cell is the entire reason there is one dispatch surface and not two. */
function CarriedBy({ row }) {
  const k = row.carriedBy;
  if (k.kind === "courier" || k.kind === "offered") {
    const c = courierById(k.courierId);
    return (
      <div style={{ minWidth: 0 }}>
        <div className="q-body-sm" style={{ color: ink }}>{c.name}</div>
        <div className="q-caption" style={{ color: inkSubtle }}>
          {typeName(c.typeId)}{k.kind === "offered" ? ` · offer expires ${mmss(k.expiresSec)}` : ""}
        </div>
      </div>
    );
  }
  if (k.kind === "partner" || k.kind === "unknown") {
    const p = partnerById(k.partnerId);
    return (
      <div style={{ minWidth: 0 }}>
        <div className="q-body-sm" style={{ color: ink }}>{p.name}</div>
        <div className="q-caption" style={{ color: inkSubtle, fontFamily: "var(--q-font-mono)" }}>{k.ref}</div>
        {k.kind === "unknown" ? <div className="q-caption" style={{ color: "var(--q-error-text)" }}>State unknown</div> : null}
      </div>
    );
  }
  return <span className="q-caption" style={{ color: inkSubtle }}>Unassigned</span>;
}

function QueueTable({ rows, selected, setSelected, cursor, setCursor, allBranches, onPeek, onAssign, onConfirm }) {
  if (!rows.length) {
    return (
      <div style={{ border: `1px solid ${hairline}`, borderTop: "none", background: canvas, padding: 32, textAlign: "center" }}>
        <div className="q-body" style={{ color: ink }}>No deliveries match this filter</div>
        <div className="q-caption" style={{ color: inkSubtle, marginTop: 4 }}>Press Escape to clear every filter.</div>
      </div>
    );
  }
  return (
    <div style={{ border: `1px solid ${hairline}`, borderTop: "none", background: canvas, overflowX: "auto" }}>
      <table style={{ width: "100%", borderCollapse: "collapse", minWidth: 1280 }}>
        <Head cols={[{ l: "", w: 32 }, "Order", "Pipelines", "Timing", "Branch", "Customer", "Address",
          { l: "Distance", r: true }, "Carried by", "Payment", { l: "Total", r: true }]} />
        <tbody>
          {rows.map((r, i) => {
            const s = sevOf(r);
            const focused = i === cursor;
            const late = r.lateByMin;
            return (
              <tr
                key={r.id}
                tabIndex={0}
                onFocus={() => setCursor(i)}
                onClick={() => onPeek(r.id)}
                style={{
                  background: s ? s.tint : canvas,
                  borderBottom: `1px solid ${hairline}`,
                  borderLeft: `3px solid ${s ? s.rule : "transparent"}`,
                  outline: focused ? `2px solid ${blue}` : "none", outlineOffset: -2,
                  cursor: "pointer",
                }}
              >
                <td style={TD}>
                  <input type="checkbox" checked={selected.includes(r.id)} onClick={(e) => e.stopPropagation()}
                    onChange={() => setSelected(selected.includes(r.id) ? selected.filter((x) => x !== r.id) : [...selected, r.id])} />
                </td>
                <td style={{ ...TD, minWidth: 190, maxWidth: 230 }}>
                  <div className="q-body-sm" style={{ fontFamily: "var(--q-font-mono)", color: ink }}>{r.shortId}</div>
                  <div className="q-caption" style={{ color: inkSubtle }}>{r.channel}</div>
                  {r.reason ? <div className="q-caption" style={{ color: s.text, marginTop: 4 }}>{r.reason}</div> : null}
                </td>
                <td style={TD}>
                  <Strip name="Kitchen" stages={r.kitchen} />
                  <div style={{ height: 6 }} />
                  <Strip name="Logistics" stages={r.logistics} />
                </td>
                <td style={{ ...TD, whiteSpace: "nowrap" }}>
                  {/* Lateness is an overlay on a status, never a status. */}
                  <div className="q-body-sm q-tnum" style={{ color: late ? "var(--q-error-text)" : ink }}>
                    {late ? `Late by ${late} min` : r.scheduled ? `For ${hhmm(r.scheduled)}` : `${Math.max(0, -minsSince(r.promisedEnd))} min left`}
                  </div>
                  <div className="q-caption q-tnum" style={{ color: inkSubtle }}>promised {hhmm(r.promisedEnd)}</div>
                </td>
                <td style={{ ...TD, whiteSpace: "nowrap" }}>
                  <span className="q-body-sm">{branchName(r.branchId)}</span>
                  {BRANCHES.find((b) => b.id === r.branchId)?.status === "FORCE_CLOSED"
                    ? <div className="q-caption" style={{ color: "var(--q-warning-text)" }}>Force-closed 19:05</div> : null}
                </td>
                <td style={{ ...TD, minWidth: 130, maxWidth: 160 }}>
                  <div className="q-body-sm">{r.customer}</div>
                  <a href={`tel:${r.phone}`} onClick={(e) => e.stopPropagation()} className="q-caption"
                    style={{ fontFamily: "var(--q-font-mono)", color: blue }}>{r.phone}</a>
                </td>
                <td style={{ ...TD, minWidth: 150, maxWidth: 210 }}>
                  <div className="q-body-sm">{r.address}</div>
                  <div className="q-caption" style={{ color: inkSubtle }}>{zoneName(r.zoneId)}</div>
                </td>
                <td className="q-body-sm q-tnum" style={{ ...TD, textAlign: "right" }}>{r.distanceKm.toFixed(1)} km</td>
                <td style={{ ...TD, minWidth: 190 }}>
                  {r.carriedBy.kind === "none"
                    ? <AssignSelect row={r} allBranches={allBranches} onAssign={onAssign} />
                    : (
                      <div onClick={(e) => e.stopPropagation()}>
                        <CarriedBy row={r} />
                        {r.actions.includes("unassign") || r.actions.includes("reassign") ? (
                          <button type="button" onClick={() => onConfirm({ kind: "unassign", row: r })} className="q-caption"
                            style={{ background: "none", border: "none", color: blue, padding: "4px 0 0", cursor: "pointer" }}>
                            {r.actions.includes("reassign") ? "Reassign" : "Unassign"}
                          </button>
                        ) : null}
                      </div>
                    )}
                </td>
                <td style={{ ...TD, whiteSpace: "nowrap" }}>
                  <div className="q-body-sm">{r.payment.method}</div>
                  {r.payment.collectMinor
                    ? <div className="q-caption q-tnum" style={{ color: inkMuted }}>collect {uzs(r.payment.collectMinor)}</div> : null}
                </td>
                <td className="q-body-sm q-tnum" style={TDR}>{uzs(r.totalMinor)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

/* ── view 2: courier list (§5) ─────────────────────────────────────────────*/

const FLEET_TABS = [
  { id: "onShift", label: "On shift", f: (c) => !!c.shiftOpenedAt },
  { id: "available", label: "Available", f: (c) => c.work === "IDLE" },
  { id: "carrying", label: "Carrying", f: (c) => ["CARRYING", "AT_BRANCH", "OFFERED"].includes(c.work) },
  { id: "offShift", label: "Off shift", f: (c) => c.work === "OFF_SHIFT" },
  { id: "suspended", label: "Suspended", f: (c) => c.account === "SUSPENDED" },
  { id: "all", label: "All", f: () => true },
];

function Fleet({ onOpenCourier }) {
  const [tab, setTab] = useState("all");
  const [search, setSearch] = useState("");
  const [type, setType] = useState("all");
  const [docs, setDocs] = useState("any");

  const q = search.trim().toLowerCase();
  const rows = COURIERS
    .filter(FLEET_TABS.find((t) => t.id === tab).f)
    .filter((c) => type === "all" || c.typeId === type)
    .filter((c) => docs === "any" || (docs === "flagged" && c.documents))
    .filter((c) => !q || c.name.toLowerCase().includes(q) || c.phone.includes(q))
    /* Blockers first — a manager opening this at 17:00 wants what stops work,
     * not the alphabet. Then on shift by load, then off shift alphabetically. */
    .sort((a, b) => {
      const rank = (c) => (c.account === "SUSPENDED" || (c.documents || "").includes("expired") ? 0 : c.shiftOpenedAt ? 1 : 2);
      return rank(a) - rank(b) || (rank(a) === 1 ? b.load - a.load : 0) || a.name.localeCompare(b.name);
    });

  return (
    <>
      <SectionHeader
        title="Couriers"
        description="Find a courier, see whether they can work today, and open their record. Account status is a decision a manager made; work state is what the clock and the courier's own actions produced."
        right={<Button size="sm">Add courier</Button>}
      />
      <Tabs tabs={FLEET_TABS.map((t) => ({ ...t, count: COURIERS.filter(t.f).length }))} active={tab} onChange={setTab} />
      <FilterBar>
        <SearchInput value={search} onChange={setSearch} placeholder="Name or phone" />
        <Select label="Type" value={type} onChange={setType}
          options={[{ value: "all", label: "Any type" }, ...COURIER_TYPES.map((t) => ({ value: t.id, label: t.name }))]} />
        <Select label="Documents" value={docs} onChange={setDocs}
          options={[{ value: "any", label: "Any" }, { value: "flagged", label: "Expiring or expired" }]} />
      </FilterBar>

      <div style={{ border: `1px solid ${hairline}`, borderTop: "none", background: canvas, overflowX: "auto" }}>
        <table style={{ width: "100%", borderCollapse: "collapse", minWidth: 1020 }}>
          <Head cols={["Name", "Work state", "Account", "Type", "Branches", "Phone", "Today",
            { l: "On-time 30 d", r: true }, { l: "Balance", r: true }]} />
          <tbody>
            {rows.map((c) => {
              const blocked = c.account === "SUSPENDED" || (c.documents || "").includes("expired");
              return (
                <tr key={c.id} tabIndex={0} onClick={() => onOpenCourier(c.id)}
                  style={{
                    background: blocked ? "var(--q-error-tint)" : canvas,
                    borderBottom: `1px solid ${hairline}`,
                    borderLeft: `3px solid ${blocked ? "var(--q-error)" : c.documents ? "var(--q-warning)" : "transparent"}`,
                    cursor: "pointer",
                  }}>
                  <td style={{ ...TD, maxWidth: 210 }}>
                    <div className="q-emphasis" style={{ color: blocked ? inkMuted : ink }}>{c.name}</div>
                    {c.documents ? <div className="q-caption" style={{ color: blocked ? "var(--q-error-text)" : "var(--q-warning-text)" }}>{c.documents}</div> : null}
                    {c.blockReason ? <div className="q-caption" style={{ color: inkMuted, marginTop: 2 }}>{c.blockReason}</div> : null}
                  </td>
                  <td style={TD}>
                    <StatusPill tone={WORK[c.work].tone}>{WORK[c.work].label}</StatusPill>
                    <div className="q-caption" style={{ color: inkMuted, marginTop: 4, maxWidth: 190 }}>{c.live}</div>
                  </td>
                  <td style={TD}><StatusPill tone={ACCOUNT[c.account].tone}>{ACCOUNT[c.account].label}</StatusPill></td>
                  <td className="q-body-sm" style={TD}>{typeName(c.typeId)}</td>
                  <td style={TD}>
                    <div className="q-caption" style={{ color: inkMuted }}>{c.branchIds.map(branchName).join(", ") || "—"}</div>
                  </td>
                  <td className="q-body-sm" style={{ ...TD, fontFamily: "var(--q-font-mono)" }}>
                    {c.phoneRevealed ? c.phone : <span className="q-caption" style={{ fontFamily: "var(--q-font-sans)", color: inkSubtle }}>Hidden</span>}
                  </td>
                  <td className="q-caption q-tnum" style={{ ...TD, color: inkMuted, whiteSpace: "nowrap" }}>
                    {c.deliveredToday} delivered
                    <div>{c.shiftOpenedAt ? `${hm(minsSince(c.shiftOpenedAt) * 60)} on shift` : "no shift"}</div>
                  </td>
                  <td className="q-body-sm q-tnum" style={{ ...TD, textAlign: "right" }}>
                    {c.onTime30}%
                    <div className="q-caption" style={{ color: inkSubtle }}>{c.onTimeSample}</div>
                  </td>
                  <td className="q-body-sm q-tnum" style={TDR}>
                    {signed(c.balance)}
                    {c.balance < -CASH_CEILING ? <div className="q-caption" style={{ color: "var(--q-warning-text)" }}>Over the cash ceiling</div> : null}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      <p className="q-caption" style={{ color: inkSubtle, marginTop: 8, maxWidth: 720 }}>
        Balance: positive means the tenant owes the courier; negative means the courier is holding the tenant's cash.
        One balance, not two (ADR 0042). Money is never coloured — a negative balance past the ceiling gets a caption,
        not a red number. On-time excludes LATE_EXCUSED from both sides: a courier is never scored down for a late kitchen.
      </p>
    </>
  );
}

/* ── view 3: shift board (§7) ──────────────────────────────────────────────*/

function Shifts({ onOpenCourier }) {
  const [tab, setTab] = useState("all");
  const [branch, setBranch] = useState("all");
  const [confirm, setConfirm] = useState(null);

  const TABS = [
    { id: "open", label: "Open", f: (s) => s.state === "OPEN" },
    { id: "approval", label: "Needs approval", f: (s) => s.approval === "Awaiting" },
    { id: "cash", label: "Cash unconfirmed", f: (s) => ["NOT_DECLARED", "DECLARED", "VARIANCE"].includes(s.cash) },
    { id: "missed", label: "Missed roster", f: (s) => s.state === "MISSED" },
    { id: "closed", label: "Closed today", f: (s) => s.state === "CLOSED" },
    { id: "all", label: "All", f: () => true },
  ];

  const rows = SHIFTS
    .filter(TABS.find((t) => t.id === tab).f)
    .filter((s) => branch === "all" || s.branchId === branch)
    .sort((a, b) => a.rank - b.rank || b.paidSeconds - a.paidSeconds);

  const CASH = {
    NOT_DECLARED: { tone: "pending", label: "Not declared" },
    DECLARED: { tone: "info", label: "Declared" },
    CONFIRMED: { tone: "healthy", label: "Confirmed" },
    VARIANCE: { tone: "failed", label: "Variance" },
    "—": { tone: "neutral", label: "No cash" },
  };
  const sevForShift = (s) => (s.rank === 0 ? SEV.problem : s.rank <= 3 ? SEV.risk : null);

  return (
    <>
      <SectionHeader
        title="Shifts"
        description="Who is actually working, and who should be but is not. The roster is what a manager planned; the shift is what happened; only the shift produces paid hours."
      />
      <Note tone="neutral" title={`Enforcement: ${ENFORCEMENT.label}`}>
        {ENFORCEMENT.policyVersion}, resolved at {ENFORCEMENT.resolvedAt} level and snapshotted onto every assignment
        attempt — tightening this in October does not make September look illegal.
      </Note>

      <div style={{ display: "grid", gridTemplateColumns: "repeat(4, minmax(0,1fr))", gap: 12, marginBottom: 20 }}>
        <KpiTile label="On shift now" value={SHIFTS.filter((s) => s.state === "OPEN").length} meta="across 2 branches" />
        <KpiTile label="Rostered, not opened" value={SHIFTS.filter((s) => s.state === "MISSED").length} meta="Bekzod Yusupov, since 18:00" />
        <KpiTile label="Open past roster" value={SHIFTS.filter((s) => s.varianceSeconds > 1800 && s.state === "OPEN").length} meta="longest 6h 04m" />
        <KpiTile label="Awaiting approval" value={SHIFTS.filter((s) => s.approval === "Awaiting").length} meta="unpaid until approved" />
      </div>

      <CoverageStrip />

      <Block title="Today's shifts">
        <Tabs tabs={TABS.map((t) => ({ ...t, count: SHIFTS.filter(t.f).length }))} active={tab} onChange={setTab} />
        <FilterBar>
          <Select label="Branch" value={branch} onChange={setBranch}
            options={[{ value: "all", label: "Every branch" }, ...BRANCHES.map((b) => ({ value: b.id, label: b.name }))]} />
        </FilterBar>
        <div style={{ border: `1px solid ${hairline}`, borderTop: "none", background: canvas, overflowX: "auto" }}>
          <table style={{ width: "100%", borderCollapse: "collapse", minWidth: 980 }}>
            <Head cols={["Courier", "Branch", "Rostered", "Opened", "Closed", { l: "Paid hours", r: true },
              { l: "Variance", r: true }, "Cash", "State", ""]} />
            <tbody>
              {rows.map((s) => {
                const c = courierById(s.courierId);
                const sev = sevForShift(s);
                return (
                  <tr key={s.id} style={{
                    background: sev ? sev.tint : canvas, borderBottom: `1px solid ${hairline}`,
                    borderLeft: `3px solid ${sev ? sev.rule : "transparent"}`,
                  }}>
                    <td style={{ ...TD, maxWidth: 200 }}>
                      <button type="button" onClick={() => onOpenCourier(c.id)} className="q-emphasis"
                        style={{ background: "none", border: "none", padding: 0, color: blue, cursor: "pointer", textAlign: "left" }}>{c.name}</button>
                      {s.note ? <div className="q-caption" style={{ color: sev ? sev.text : inkMuted, marginTop: 2 }}>{s.note}</div> : null}
                    </td>
                    <td className="q-body-sm" style={TD}>{branchName(s.branchId)}</td>
                    <td className="q-body-sm q-tnum" style={TD}>{s.rostered}</td>
                    <td className="q-body-sm q-tnum" style={TD}>
                      {s.openedAt ? hhmm(s.openedAt) : "—"}
                      <div className="q-caption" style={{ color: inkSubtle, fontVariantNumeric: "normal" }}>
                        {s.openSource ? s.openSource.toLowerCase() : "never opened"}
                      </div>
                      {s.openedBy ? <div className="q-caption" style={{ color: inkSubtle }}>{s.openedBy}</div> : null}
                    </td>
                    <td className="q-body-sm q-tnum" style={TD}>
                      {s.closedAt ? hhmm(s.closedAt) : "—"}
                      {s.closeSource ? <div className="q-caption" style={{ color: s.closeSource === "AUTO_CLOSED" ? "var(--q-warning-text)" : inkSubtle }}>{s.closeSource.toLowerCase().replace("_", " ")}</div> : null}
                    </td>
                    <td className="q-body-sm q-tnum" style={{ ...TD, textAlign: "right" }}>{hm(s.paidSeconds)}</td>
                    <td className="q-body-sm q-tnum" style={{ ...TD, textAlign: "right" }}>
                      {s.varianceSeconds ? `${s.varianceSeconds > 0 ? "+" : "−"}${hm(Math.abs(s.varianceSeconds))}` : "—"}
                    </td>
                    <td style={TD}><StatusPill tone={CASH[s.cash].tone}>{CASH[s.cash].label}</StatusPill></td>
                    <td className="q-caption" style={{ ...TD, color: inkMuted }}>{s.state.toLowerCase().replace(/_/g, " ")}</td>
                    <td style={{ ...TD, whiteSpace: "nowrap" }}>
                      {s.approval === "Awaiting" ? (
                        <Button variant="tertiary" size="sm" onClick={() => setConfirm(s)}>Approve hours</Button>
                      ) : s.state === "MISSED" ? (
                        <Button variant="tertiary" size="sm" onClick={() => setConfirm(s)}>Open on their behalf</Button>
                      ) : null}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </Block>

      {confirm ? (
        <Confirm
          title={confirm.state === "MISSED"
            ? `Open a shift for ${courierById(confirm.courierId).name}`
            : `Approve ${hm(confirm.paidSeconds)} for ${courierById(confirm.courierId).name}`}
          confirmLabel={confirm.state === "MISSED" ? "Open the shift" : "Approve hours"}
          body={confirm.state === "MISSED"
            ? `This creates paid hours from now at ${branchName(confirm.branchId)} and is audited against you with a reason. It is not the courier opening their own shift.`
            : `Rostered ${confirm.rostered}, actual ${hm(confirm.paidSeconds)}, variance ${confirm.varianceSeconds > 0 ? "+" : "−"}${hm(Math.abs(confirm.varianceSeconds))}. Approving changes what this person is paid. You may not approve hours you requested (ADR 0027 four eyes).`}
          onConfirm={() => setConfirm(null)}
          onCancel={() => setConfirm(null)}
        />
      ) : null}
    </>
  );
}

/* Coverage is ordinal, so this is a single-hue neutral ramp, not four hues. Zero
 * coverage against a live roster is a severity rather than a low value, so it is
 * the one cell that gets the error tint, and the gap is named in prose beneath —
 * a heat strip nobody can read out loud is decoration. Plain divs. */
function CoverageStrip() {
  const cells = COVERAGE.map((c) => ({ ...c, series: expandCoverage(c.runs) }));
  const shade = (open, rostered) => {
    if (!rostered) return surface1;
    if (!open) return "var(--q-error-tint)";
    if (open === 1) return "var(--q-surface-2)";
    if (open === 2) return inkSubtle;
    if (open === 3) return inkMuted;
    return ink;
  };
  return (
    <Card style={{ padding: 16 }}>
      <div className="q-emphasis" style={{ color: ink }}>Coverage, 15-minute cells, 11:00 – 23:00</div>
      <div className="q-caption" style={{ color: inkSubtle, marginTop: 2, marginBottom: 12 }}>
        Open shifts against rostered shifts. Darker is more couriers; a tinted cell is rostered coverage with nobody on shift.
      </div>
      {cells.map((c) => (
        <div key={c.branchId} style={{ display: "flex", alignItems: "center", gap: 12, marginBottom: 6 }}>
          <div className="q-caption" style={{ width: 132, flexShrink: 0, color: inkMuted }}>{branchName(c.branchId)}</div>
          <div style={{ display: "flex", gap: 1, flex: 1, minWidth: 0 }}>
            {c.series.map((cell, i) => (
              <span key={i}
                title={`${String(COVERAGE_START_HOUR + Math.floor(i / 4)).padStart(2, "0")}:${String((i % 4) * 15).padStart(2, "0")} — ${cell.open} of ${cell.rostered} on shift`}
                style={{ flex: 1, height: 18, background: shade(cell.open, cell.rostered) }} />
            ))}
          </div>
        </div>
      ))}
      <div className="q-caption" style={{ color: "var(--q-error-text)", marginTop: 8 }}>
        Sebzor has two rostered couriers and nobody on shift from 20:00 — the branch was force-closed at 19:05 and the
        roster was never withdrawn.
      </div>
      <div className="q-caption" style={{ color: inkSubtle, marginTop: 4 }}>
        The roster editor (§7.1) is not prototyped. Copy-last-week and publish are the two controls that get used.
      </div>
    </Card>
  );
}

/* ── view 4: cash handovers (§8) ───────────────────────────────────────────*/

const HV_STATUS = {
  AWAITING_DECLARATION: { tone: "neutral", label: "Awaiting declaration" },
  AWAITING_CONFIRMATION: { tone: "pending", label: "Awaiting confirmation" },
  VARIANCE: { tone: "failed", label: "Variance" },
  CONFIRMED: { tone: "healthy", label: "Confirmed" },
};

function Cash() {
  const [openId, setOpenId] = useState("hv-2");
  const [counted, setCounted] = useState("812000");
  const [reason, setReason] = useState("");
  const [confirm, setConfirm] = useState(null);

  /* Money that does not reconcile outranks money that has not been counted yet. */
  const rows = [...HANDOVERS].sort((a, b) => {
    if (a.rank !== b.rank) return a.rank - b.rank;
    if (a.rank === 0) return Math.abs((b.confirmedMinor ?? 0) - (b.declaredMinor ?? 0)) - Math.abs((a.confirmedMinor ?? 0) - (a.declaredMinor ?? 0));
    return new Date(a.closedAt) - new Date(b.closedAt);
  });

  const open = HANDOVERS.find((h) => h.id === openId);
  const lines = HANDOVER_LINES[openId] || [];
  const countedMinor = Number(counted.replace(/\D/g, "")) || 0;
  const variance = open ? countedMinor - (open.declaredMinor ?? 0) : 0;

  return (
    <>
      <SectionHeader
        title="Cash handovers"
        description="Account for the tenant's money a courier is carrying, at the moment they stop carrying it. Three figures, always shown separately, and a variance is never absorbed into another number."
      />
      <Note tone="warning" title="Sanjar Xolmatov is holding 4 200 000 so'm on an open shift">
        1 200 000 over the ceiling. Cash on hand is a supervision fact during service, not an accounting fact at 23:00.
      </Note>

      <div style={{ border: `1px solid ${hairline}`, background: canvas, overflowX: "auto" }}>
        <table style={{ width: "100%", borderCollapse: "collapse", minWidth: 940 }}>
          <Head cols={["Courier", "Branch", "Shift closed", { l: "Expected", r: true }, { l: "Declared", r: true },
            { l: "Confirmed", r: true }, { l: "Variance", r: true }, "Status", { l: "Orders", r: true }]} />
          <tbody>
            {rows.map((h) => {
              const c = courierById(h.courierId);
              const sev = h.status === "VARIANCE" ? SEV.problem : h.status === "AWAITING_DECLARATION" ? SEV.risk : null;
              const v = h.confirmedMinor != null && h.declaredMinor != null ? h.confirmedMinor - h.declaredMinor : null;
              return (
                <tr key={h.id} onClick={() => { setOpenId(h.id); setCounted(String(h.confirmedMinor ?? "")); setReason(""); }}
                  style={{
                    background: h.id === openId ? "var(--q-info-tint)" : sev ? sev.tint : canvas,
                    borderBottom: `1px solid ${hairline}`,
                    borderLeft: `3px solid ${sev ? sev.rule : "transparent"}`, cursor: "pointer",
                  }}>
                  <td style={{ ...TD, maxWidth: 240 }}>
                    <div className="q-emphasis">{c.name}</div>
                    {h.note ? <div className="q-caption" style={{ color: sev ? sev.text : inkMuted, marginTop: 2 }}>{h.note}</div> : null}
                  </td>
                  <td className="q-body-sm" style={TD}>{branchName(h.branchId)}</td>
                  <td className="q-body-sm q-tnum" style={TD}>{dt(h.closedAt)}</td>
                  <td className="q-body-sm q-tnum" style={TDR}>{uzs(h.expectedMinor)}</td>
                  <td className="q-body-sm q-tnum" style={TDR}>{h.declaredMinor != null ? uzs(h.declaredMinor) : "—"}</td>
                  <td className="q-body-sm q-tnum" style={TDR}>{h.confirmedMinor != null ? uzs(h.confirmedMinor) : "—"}</td>
                  <td className="q-body-sm q-tnum" style={TDR}>{v ? signed(v) : "—"}</td>
                  <td style={TD}><StatusPill tone={HV_STATUS[h.status].tone}>{HV_STATUS[h.status].label}</StatusPill></td>
                  <td className="q-body-sm q-tnum" style={TDR}>{h.ordersInShift}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      {open ? (
        <Block title={`Reconcile — ${courierById(open.courierId).name}`}
          description={`Shift closed ${dt(open.closedAt)} at ${branchName(open.branchId)}. Counted at the branch, one hand on the notes.`}>
          <div style={{ display: "flex", gap: 16, alignItems: "flex-start", flexWrap: "wrap" }}>
            <Card style={{ flex: "1 1 420px", minWidth: 320, padding: 0 }}>
              <div className="q-emphasis" style={{ padding: 12, borderBottom: `1px solid ${hairline}`, color: ink }}>
                Expected — {uzs(open.expectedMinor)}
              </div>
              {lines.length ? (
                <table style={{ width: "100%", borderCollapse: "collapse" }}>
                  <Head cols={["Order", { l: "Total", r: true }, { l: "Captured", r: true },
                    { l: "Loyalty", r: true }, { l: "Cash due", r: true }]} />
                  <tbody>
                    {lines.map((l) => (
                      <tr key={l.order} style={{ borderBottom: `1px solid ${hairline}` }}>
                        <td className="q-body-sm" style={{ ...TD, fontFamily: "var(--q-font-mono)" }}>{l.order}</td>
                        <td className="q-body-sm q-tnum" style={TDR}>{uzs(l.totalMinor)}</td>
                        <td className="q-body-sm q-tnum" style={TDR}>{l.capturedMinor ? uzs(l.capturedMinor) : "—"}</td>
                        <td className="q-body-sm q-tnum" style={TDR}>{l.loyaltyMinor ? uzs(l.loyaltyMinor) : "—"}</td>
                        <td className="q-body-sm q-tnum" style={TDR}>{uzs(l.dueMinor)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              ) : (
                <div className="q-body-sm" style={{ padding: 16, color: inkMuted }}>
                  The per-order breakdown is only fixtured for the row with a variance — that is the row a cashier opens.
                </div>
              )}
            </Card>

            <Card style={{ flex: "0 1 380px", minWidth: 300 }}>
              <div className="q-caption" style={{ color: inkSubtle }}>Declared by the courier</div>
              <div className="q-data-lg" style={{ color: ink }}>{open.declaredMinor != null ? uzs(open.declaredMinor) : "Not declared"}</div>

              <label className="q-caption" style={{ color: inkSubtle, display: "block", marginTop: 20 }}>Confirmed by the cashier</label>
              <input
                value={counted}
                onChange={(e) => setCounted(e.target.value)}
                inputMode="numeric"
                className="q-data-lg q-tnum"
                style={{ width: "100%", marginTop: 4, padding: "8px 10px", background: canvas, color: ink, border: `1px solid ${hairline}`, borderRadius: "var(--q-radius)", outline: "none" }}
                onFocus={(e) => { e.target.style.border = `2px solid ${blue}`; }}
                onBlur={(e) => { e.target.style.border = `1px solid ${hairline}`; }}
              />

              <div style={{ marginTop: 16, borderTop: `1px solid ${hairline}`, paddingTop: 12 }}>
                <div className="q-caption" style={{ color: inkSubtle }}>Variance — confirmed less declared</div>
                <div className="q-data-lg q-tnum" style={{ color: ink }}>{signed(variance)}</div>
              </div>

              {variance !== 0 ? (
                <div style={{ marginTop: 12 }}>
                  <Select label="Reason" value={reason} onChange={setReason} options={VARIANCE_REASONS} />
                  <div className="q-caption" style={{ color: "var(--q-warning-text)", marginTop: 6 }}>
                    A reason code is required while the variance is not zero. It becomes its own permanent ledger entry —
                    the variance is never folded into the expected figure.
                  </div>
                </div>
              ) : null}

              <div style={{ display: "flex", gap: 8, marginTop: 16, flexWrap: "wrap" }}>
                <Button size="sm" disabled={variance !== 0 && !reason} onClick={() => setConfirm("confirm")}>Confirm handover</Button>
                <Button variant="tertiary" size="sm" onClick={() => setConfirm("partial")}>Record a partial</Button>
              </div>
              <div style={{ marginTop: 8 }}>
                <button type="button" onClick={() => setConfirm("override")} className="q-caption"
                  style={{ background: "none", border: "none", color: blue, padding: 0, cursor: "pointer" }}>
                  Override and close without confirmation
                </button>
              </div>
              <p className="q-caption" style={{ color: inkSubtle, marginTop: 12 }}>
                A shift cannot reach Closed with an unconfirmed handover unless a manager overrides with an audited
                reason. Skipping this is how the ledger stops being true, so the console makes the skip visible rather
                than easy.
              </p>
            </Card>
          </div>
        </Block>
      ) : null}

      {confirm ? (
        <Confirm
          title={confirm === "override" ? `Close ${courierById(open.courierId).name}'s shift unaccounted` : `Confirm ${uzs(countedMinor)} from ${courierById(open.courierId).name}`}
          danger={confirm === "override"}
          confirmLabel={confirm === "override" ? "Close it unaccounted" : confirm === "partial" ? "Record the partial" : "Confirm handover"}
          body={confirm === "override"
            ? `This closes the shift with ${uzs(Math.abs(open.expectedMinor - countedMinor))} unaccounted. The entry is permanent and is audited against you.`
            : `${uzs(countedMinor)} counted against ${uzs(open.declaredMinor ?? 0)} declared, a variance of ${signed(variance)}. ${confirm === "partial" ? "The shift stays in reconciling." : "The entry is permanent and cannot be edited."}`}
          onConfirm={() => setConfirm(null)}
          onCancel={() => setConfirm(null)}
        />
      ) : null}
    </>
  );
}

/* ── courier record, as a drawer ───────────────────────────────────────────
 * §6 specifies a seven-tab record on its own route so a manager can link a
 * colleague to a courier's ledger. That route is not prototyped. What is here is
 * the part service actually reaches for: the card, the blockers, and the ledger
 * for the open settlement period — append-only, newest first, with a running
 * balance and no edit affordance anywhere, because the table is INSERT/SELECT
 * only at the grant level and a UI that offers editing lies about the system. */
function CourierRecord({ id, onClose }) {
  const c = courierById(id);
  if (!c) return null;
  const entries = ledgerFor(id);
  let running = c.balance;
  const withBalance = entries.map((e) => { const row = { ...e, running }; running -= e.amountMinor; return row; });

  return (
    <Drawer title={c.name} onClose={onClose} width={720}>
      <div style={{ display: "flex", gap: 8, flexWrap: "wrap", marginBottom: 16 }}>
        <StatusPill tone={WORK[c.work].tone}>{WORK[c.work].label}</StatusPill>
        <StatusPill tone={ACCOUNT[c.account].tone}>{ACCOUNT[c.account].label}</StatusPill>
      </div>
      {c.blockReason ? <Note tone={c.account === "SUSPENDED" ? "error" : "warning"} title="Cannot take offers">{c.blockReason}</Note> : null}

      <div style={{ display: "grid", gridTemplateColumns: "repeat(4, minmax(0,1fr))", gap: 12, marginBottom: 20 }}>
        <KpiTile label="Delivered today" value={c.deliveredToday} />
        <KpiTile label="On shift" value={c.shiftOpenedAt ? hm(minsSince(c.shiftOpenedAt) * 60) : "—"} />
        <KpiTile label="Balance" value={signed(c.balance)} meta="open period" />
        <KpiTile label="On-time 30 d" value={`${c.onTime30}%`} meta={c.onTimeSample} />
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "repeat(3, minmax(0,1fr))", gap: 16, marginBottom: 20 }}>
        <Field label="Courier type" value={typeName(c.typeId)} />
        <Field label="Branches" value={c.branchIds.map(branchName).join(", ") || "—"} />
        <Field label="Dispatch pools" value={c.pools.join(", ") || "—"} />
        <Field label="Phone" value={c.phoneRevealed ? c.phone : "Hidden — needs the reveal capability"} mono={c.phoneRevealed} />
        <Field label="Plate" value={c.plate} mono />
        <Field label="Licence expiry" value={c.licenceExpiry ? c.licenceExpiry.split("-").reverse().join(".") : "—"} />
        <Field label="Cash on hand" value={c.cashOnHand ? uzs(c.cashOnHand) : "—"} />
        <Field label="Battery" value={c.batteryPercent != null ? `${c.batteryPercent}%${c.charging ? ", charging" : ""}` : "—"} />
        <Field label="Position age" value={c.positionAgeMin != null ? `${c.positionAgeMin} min` : "—"} />
      </div>

      <div className="q-emphasis" style={{ color: ink, marginBottom: 6 }}>Ledger — open settlement period</div>
      {withBalance.length ? (
        <div style={{ border: `1px solid ${hairline}`, overflowX: "auto" }}>
          <table style={{ width: "100%", borderCollapse: "collapse", minWidth: 620 }}>
            <Head cols={["Occurred", "Type", { l: "Amount", r: true }, { l: "Running", r: true }, "Origin", "Source"]} />
            <tbody>
              {withBalance.map((e) => (
                <tr key={e.id} style={{ borderBottom: `1px solid ${hairline}` }}>
                  <td className="q-caption q-tnum" style={{ ...TD, color: inkMuted }}>
                    {dt(e.occurredAt)}
                    {e.recordedAt !== e.occurredAt ? <div className="q-caption" style={{ color: inkSubtle }}>recorded {dt(e.recordedAt)}</div> : null}
                  </td>
                  <td className="q-body-sm" style={TD}>
                    {e.label}
                    {e.type === "PRIOR_PERIOD_ADJUSTMENT" ? <div className="q-caption" style={{ color: inkMuted }}>{e.source}</div> : null}
                  </td>
                  <td className="q-body-sm q-tnum" style={TDR}>{signed(e.amountMinor)}</td>
                  <td className="q-body-sm q-tnum" style={TDR}>{signed(e.running)}</td>
                  <td className="q-caption" style={{ ...TD, color: inkMuted }}>
                    {e.origin}
                    {e.approval ? <div className="q-caption" style={{ color: inkSubtle }}>{e.approval}</div> : null}
                  </td>
                  <td className="q-caption" style={{ ...TD, color: inkMuted }}>
                    {e.source}
                    <div className="q-caption" style={{ color: inkSubtle, fontFamily: "var(--q-font-mono)" }}>{e.id}</div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <EmptyState title="No ledger entries in the open period" description="Earnings post at delivery and shift close." />
      )}
      <p className="q-caption" style={{ color: inkSubtle, marginTop: 12 }}>
        Every bonus and penalty names the rule or the person that produced it. Not prototyped from §6: the Work,
        Shifts, Vehicle &amp; documents, Restrictions and Audit tabs, and the three-column create form.
      </p>
    </Drawer>
  );
}

/* ── shell ─────────────────────────────────────────────────────────────────*/

const VIEWS = [
  { id: "dispatch", label: "Dispatch" },
  { id: "fleet", label: "Couriers" },
  { id: "shifts", label: "Shifts" },
  { id: "cash", label: "Cash handovers" },
];

export default function Couriers({ courierId, setCourierId, tab, setTab }) {
  const view = VIEWS.find((v) => v.id === tab) ? tab : "dispatch";
  const open = (id) => setCourierId(id);

  return (
    <>
      <Tabs tabs={VIEWS} active={view} onChange={setTab} />
      {view === "dispatch" ? <Dispatch onOpenCourier={open} /> : null}
      {view === "fleet" ? <Fleet onOpenCourier={open} /> : null}
      {view === "shifts" ? <Shifts onOpenCourier={open} /> : null}
      {view === "cash" ? <Cash /> : null}
      {courierId ? <CourierRecord id={courierId} onClose={() => setCourierId(null)} /> : null}
    </>
  );
}

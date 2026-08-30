/* Settings → Brands and locations.
 *
 * Almost everything in this section is configuration, and configuration lives
 * three clicks away for a reason. One thing here is not configuration: the
 * manual open/closed switch. It is hit mid-service, with a queue building, by a
 * manager who is standing up — so it is on the main path, it is reason-mandatory,
 * and it carries duration presets so `until 21:30` is easier to pick than
 * `until cancelled`. That one change removes the most expensive silent failure
 * in this section: the fryer that died on Thursday and nobody reopened.
 *
 * What is built here, and why these four:
 *
 *   1. The location list, shaped as an operational queue rather than an
 *      alphabetical registry — severity sort, three severity channels per row,
 *      and the two actions worth doing without opening a branch.
 *   2. The service-state dialog, reachable from the row, the strip and the
 *      record header, plus the undismissable closed-branch strip above the list.
 *   3. The branch record: hours (three bindings over one week grid), preparation
 *      bands with the capacity lattice, channels as three states not a checkbox,
 *      and fiscal identity as the dated assignment it will be.
 *   4. «Why can't I sell here» — the resolver's rules in evaluation order, with
 *      everything after the first failure shown as not evaluated.
 *
 * Interface chrome is English, matching the rest of this prototype; the content
 * — branches, reasons, schedules, people — is Uzbek and Russian as it will ship.
 */

import { useEffect, useMemo, useRef, useState } from "react";
import {
  Button, Card, Drawer, EmptyState, FilterBar, SearchInput, Select, StatusPill, Tabs, Field,
  canvas, hairline, ink, inkMuted, inkSubtle, surface1, blue, dt, day, uzs,
} from "./components";
import {
  BRANDS, CHANNELS, LOCATIONS, SCHEDULES, MODES, MODE_LABEL, DOW_LABEL,
  CLOSE_REASONS, OPEN_REASONS, PENDING_FIELDS, FISCAL_FIELDS,
  NOW_ISO, NOW_MIN, TODAY_DATE, TOMORROW_DATE, TODAY_DOW,
  TONE_STYLE, pillOf, mono, Block, Callout, PendingField, LoadBar, ModePills, CountLink,
  activationBlockers, activeChannels, bandsFor, boundModes, brandOf, closingNow,
  expandWindows, fromMin, preparationNow, reasonLabel, resolveState, scheduleOf,
  serviceabilityTrace, toMin, uncoveredWindows, windowLabel, windowsLabel, windowsToday,
} from "./Places.data";

/* ── the closed-branch strip (§6.2) ────────────────────────────────────────
 * Impossible to dismiss, and that is the entire mechanism against a branch
 * staying shut over a weekend because the person who closed it went home.
 */
function ClosedStrip({ rows, onReopen, onOpenBranch }) {
  const closed = rows.filter((r) => r.serviceState.mode === "FORCE_CLOSED");
  if (!closed.length) return null;
  return (
    <div
      style={{
        background: "var(--q-error-tint)", border: `1px solid ${hairline}`,
        borderLeft: "3px solid var(--q-error)", padding: "12px 16px", marginBottom: 16,
      }}
    >
      <div className="q-emphasis" style={{ color: "var(--q-error-text)" }}>
        Closed manually: {closed.length} {closed.length === 1 ? "branch" : "branches"}
      </div>
      <div style={{ display: "flex", flexWrap: "wrap", gap: 16, marginTop: 8 }}>
        {closed.map((r) => (
          <div key={r.id} style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <button
              type="button"
              onClick={() => onOpenBranch(r.id)}
              className="q-body-sm"
              style={{ background: "none", border: "none", padding: 0, color: blue, cursor: "pointer", textAlign: "left" }}
            >
              {r.name}
            </button>
            <span className="q-caption" style={{ color: inkMuted }}>
              {r.serviceState.effectiveUntil
                ? `until ${dt(r.serviceState.effectiveUntil).slice(6)}`
                : "until cancelled"}
              {" · "}{reasonLabel(r.serviceState.reasonCode).toLowerCase()}
            </span>
            <Button size="sm" variant="tertiary" onClick={() => onReopen(r)}>Reopen</Button>
          </div>
        ))}
      </div>
    </div>
  );
}

/* ── the service-state dialog (§6) ─────────────────────────────────────────
 * Four keystrokes, because it happens while someone is holding a pan. The
 * schema makes a reason mandatory on any override and forbids one on
 * FOLLOW_SCHEDULE, so switching back clears both fields here rather than
 * sending values the database will reject.
 */
function ServiceStateDialog({ loc, onClose, onApply }) {
  const st = resolveState(loc);
  const scheduleShut = ["OUTSIDE_HOURS", "CLOSED_BY_EXCEPTION"].includes(st.key);
  const [mode, setMode] = useState(loc.serviceState.mode === "FORCE_CLOSED" ? "FOLLOW_SCHEDULE" : "FORCE_CLOSED");
  const [reason, setReason] = useState("");
  const [note, setNote] = useState("");
  const [preset, setPreset] = useState("2h");
  const [max, setMax] = useState(loc.serviceState.maxConcurrentOrders || "");

  const reasons = mode === "FORCE_OPEN" ? OPEN_REASONS : CLOSE_REASONS;
  const closeOfDay = closingNow(loc) || "23:00";
  const PRESETS = [
    { id: "30m", label: "30 min", until: fromMin((NOW_MIN + 30) % 1440) },
    { id: "1h", label: "1 hour", until: fromMin((NOW_MIN + 60) % 1440) },
    { id: "2h", label: "2 hours", until: fromMin((NOW_MIN + 120) % 1440) },
    { id: "eod", label: "End of day", until: closeOfDay },
    { id: "none", label: "Until cancelled", until: null },
  ];
  const chosen = PRESETS.find((p) => p.id === preset);
  const needsNote = reason === "OTHER";
  const valid = mode === "FOLLOW_SCHEDULE" || (reason && (!needsNote || note.trim().length > 0));

  /* 1/2/3 pick the top three reasons; Enter submits. */
  useEffect(() => {
    const onKey = (e) => {
      if (e.target.tagName === "INPUT" || e.target.tagName === "TEXTAREA") return;
      if (["1", "2", "3"].includes(e.key) && mode !== "FOLLOW_SCHEDULE") setReason(reasons[Number(e.key) - 1].code);
      if (e.key === "Enter" && valid) submit();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  /* A preset resolving to a wall-clock time at or before now means tomorrow —
     «end of day» on a branch trading until 02:00 is 02:00 the next morning, and
     writing today's date there would expire the close the moment it is made. */
  const untilIso = (t) => (t === null ? null
    : `${toMin(t) > NOW_MIN ? TODAY_DATE : TOMORROW_DATE}T${t}:00`);

  const submit = () => {
    onApply(loc, mode === "FOLLOW_SCHEDULE"
      ? { mode: "FOLLOW_SCHEDULE", reasonCode: null, note: null, effectiveUntil: null, changedAt: NOW_ISO, changedBy: "Sanjar Tursunov", maxConcurrentOrders: max === "" ? null : Number(max) }
      : {
          mode, reasonCode: reason, note: note || null,
          effectiveUntil: untilIso(chosen.until),
          changedAt: NOW_ISO, changedBy: "Sanjar Tursunov",
          maxConcurrentOrders: max === "" ? null : Number(max),
        });
  };

  const submitLabel =
    mode === "FOLLOW_SCHEDULE" ? "Follow the schedule"
      : mode === "FORCE_OPEN" ? `Open until ${chosen.until || "cancelled"}`
      : `Close until ${chosen.until || "cancelled"}`;

  const Radio = ({ id, label, hint }) => (
    <label
      style={{
        display: "flex", gap: 8, alignItems: "flex-start", padding: "10px 12px",
        border: `1px solid ${mode === id ? blue : hairline}`, cursor: "pointer",
        background: mode === id ? "var(--q-info-tint)" : canvas,
      }}
    >
      <input type="radio" checked={mode === id} onChange={() => setMode(id)} style={{ marginTop: 2 }} />
      <span style={{ minWidth: 0 }}>
        <span className="q-body-sm" style={{ color: ink, display: "block" }}>{label}</span>
        <span className="q-caption" style={{ color: inkMuted }}>{hint}</span>
      </span>
    </label>
  );

  return (
    <Drawer title={`Trading state — ${loc.name}`} onClose={onClose} width={520}>
      <div className="q-caption" style={{ color: inkMuted, marginBottom: 16 }}>
        <span style={mono}>{loc.code}</span> · {brandOf(loc.brandId).name} · {loc.timezone.split("/")[1]} time
      </div>

      {/* Closing does not cancel what is already cooking. Say so, or the same
          manager phones support ten minutes later. */}
      {loc.inFlight ? (
        <Callout tone={loc.lateInFlight ? "amber" : "sky"} title={`In flight: ${loc.inFlight} orders`}>
          Closing stops new orders. It does not cancel these.
          {loc.lateInFlight
            ? ` ${loc.lateInFlight} of them are already late, the worst by ${loc.worstLateMinutes} min.`
            : ""}
        </Callout>
      ) : null}

      {scheduleShut ? (
        <div style={{ marginTop: 12 }}>
          <Callout tone="amber" title={`The schedule already says this branch is shut — ${st.caption.toLowerCase()}`}>
            Reopening now is a force-open, which is a different act and needs its own reason.
          </Callout>
        </div>
      ) : null}

      {st.key === "AT_CAPACITY" ? (
        <div style={{ marginTop: 12 }}>
          <Callout tone="rose" title={`Queue full: ${loc.holds} / ${loc.serviceState.maxConcurrentOrders}`}>
            Raising the limit is usually the lever you want here, not closing.
          </Callout>
        </div>
      ) : null}

      <div style={{ display: "grid", gap: 8, marginTop: 16 }}>
        <Radio id="FOLLOW_SCHEDULE" label="Follow the schedule" hint="Clears the override, reason and expiry" />
        <Radio id="FORCE_CLOSED" label="Close the branch" hint="Stops new orders on every channel" />
        <Radio id="FORCE_OPEN" label="Force open" hint="Trades outside the timetable" />
      </div>

      {mode !== "FOLLOW_SCHEDULE" ? (
        <>
          <div className="q-caption" style={{ color: inkSubtle, margin: "20px 0 6px" }}>
            REASON — REQUIRED
          </div>
          <div style={{ display: "flex", flexWrap: "wrap", gap: 6 }}>
            {reasons.map((r, i) => (
              <button
                key={r.code}
                type="button"
                onClick={() => setReason(r.code)}
                className="q-body-sm"
                style={{
                  padding: "6px 10px", cursor: "pointer", background: reason === r.code ? ink : canvas,
                  color: reason === r.code ? "#fff" : ink, border: `1px solid ${reason === r.code ? ink : hairline}`,
                }}
              >
                {r.label}
                {i < 3 ? <span className="q-caption" style={{ opacity: 0.6, marginLeft: 6 }}>{i + 1}</span> : null}
              </button>
            ))}
          </div>
          <div className="q-caption" style={{ color: inkSubtle, marginTop: 6 }}>
            The vocabulary is code-owned until IA 10.10 reference data exists — the column is free text.
          </div>

          <div className="q-caption" style={{ color: inkSubtle, margin: "20px 0 6px" }}>
            COMMENT{needsNote ? " — REQUIRED FOR «OTHER»" : ""}
          </div>
          <textarea
            value={note}
            onChange={(e) => setNote(e.target.value)}
            rows={2}
            maxLength={400}
            placeholder="Tandir yorildi, usta ertaga keladi"
            className="q-body-sm"
            style={{ width: "100%", padding: 8, border: `1px solid ${hairline}`, background: canvas, color: ink, resize: "vertical" }}
          />

          <div className="q-caption" style={{ color: inkSubtle, margin: "20px 0 6px" }}>UNTIL</div>
          <div style={{ display: "flex", flexWrap: "wrap", gap: 6 }}>
            {PRESETS.map((p) => {
              const on = preset === p.id;
              const dangerous = p.id === "none";
              return (
                <button
                  key={p.id}
                  type="button"
                  onClick={() => setPreset(p.id)}
                  className="q-body-sm"
                  style={{
                    padding: "6px 10px", cursor: "pointer",
                    background: on ? (dangerous ? "var(--q-error-tint)" : ink) : canvas,
                    color: on ? (dangerous ? "var(--q-error-text)" : "#fff") : dangerous ? "var(--q-error-text)" : ink,
                    border: `1px solid ${on ? (dangerous ? "var(--q-error)" : ink) : hairline}`,
                  }}
                >
                  {p.label}
                  {p.until ? <span className="q-caption q-tnum" style={{ opacity: 0.7, marginLeft: 6 }}>{p.until}</span> : null}
                </button>
              );
            })}
          </div>
          <div className="q-caption" style={{ color: inkSubtle, marginTop: 6 }}>
            An elapsed expiry returns the branch to its schedule by being read as elapsed — no job, no
            operator. «Until cancelled» means somebody has to remember.
          </div>
        </>
      ) : null}

      <div className="q-caption" style={{ color: inkSubtle, margin: "20px 0 6px" }}>CONCURRENT ORDERS</div>
      <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
        <input
          value={max}
          onChange={(e) => setMax(e.target.value.replace(/\D/g, ""))}
          placeholder="no limit"
          className="q-body-sm q-tnum"
          style={{ width: 120, height: 32, padding: "0 8px", border: `1px solid ${hairline}`, background: canvas, color: ink }}
        />
        <span className="q-caption" style={{ color: inkSubtle }}>
          Separate endpoint (PUT /capacity) — advisory at browse, authoritative at checkout
        </span>
      </div>

      {loc.serviceState.changedAt ? (
        <div className="q-caption" style={{ color: inkSubtle, marginTop: 20 }}>
          Last changed by {loc.serviceState.changedBy} · {dt(loc.serviceState.changedAt)}
        </div>
      ) : null}

      <div style={{ display: "flex", gap: 8, marginTop: 24, borderTop: `1px solid ${hairline}`, paddingTop: 16 }}>
        <Button onClick={submit} disabled={!valid}>{submitLabel}</Button>
        <Button variant="ghost" onClick={onClose}>Cancel</Button>
      </div>
      {!valid ? (
        <div className="q-caption" style={{ color: "var(--q-error-text)", marginTop: 8 }}>
          {reason ? "A comment is required when the reason is «Other»." : "Pick a reason — the database refuses an override without one."}
        </div>
      ) : null}
    </Drawer>
  );
}

/* ── «Why can't I sell here» (§13) ─────────────────────────────────────────*/
function WhyDrawer({ loc, onClose, onFix }) {
  const bound = boundModes(loc);
  const [channelId, setChannelId] = useState((loc.channels[0] || CHANNELS[0]).channelId || CHANNELS[0].id);
  const [mode, setMode] = useState(bound[0] || "DELIVERY");
  const trace = serviceabilityTrace(loc, channelId, mode);

  const MARK = { pass: "✓", fail: "✗", skipped: "·" };
  const COLOR = { pass: "var(--q-success-text)", fail: "var(--q-error-text)", skipped: inkSubtle };

  return (
    <Drawer title="Why can't I sell here?" onClose={onClose} width={620}>
      <div style={{ display: "flex", gap: 8, flexWrap: "wrap", marginBottom: 16 }}>
        <Select
          label="Branch" value={loc.id} onChange={() => {}}
          options={[{ value: loc.id, label: loc.name }]}
        />
        <Select
          label="Channel" value={channelId} onChange={setChannelId}
          options={CHANNELS.map((c) => ({ value: c.id, label: c.name }))}
        />
        <Select
          label="Mode" value={mode} onChange={setMode}
          options={MODES.map((m) => ({ value: m, label: MODE_LABEL[m] }))}
        />
      </div>

      <div style={{ border: `1px solid ${hairline}` }}>
        {trace.rules.map((r) => (
          <div
            key={r.n}
            style={{
              display: "flex", gap: 12, padding: "10px 12px", borderBottom: `1px solid ${hairline}`,
              background: r.state === "fail" ? "var(--q-error-tint)" : canvas,
              borderLeft: `3px solid ${r.state === "fail" ? "var(--q-error)" : "transparent"}`,
            }}
          >
            <span className="q-body-sm" style={{ color: COLOR[r.state], width: 12 }}>{MARK[r.state]}</span>
            <span className="q-caption q-tnum" style={{ color: inkSubtle, width: 28 }}>{r.n === 1 ? "1–2" : r.n}</span>
            <span style={{ minWidth: 0, flex: 1 }}>
              <span className="q-body-sm" style={{ color: r.state === "skipped" ? inkSubtle : ink, display: "block" }}>
                {r.label}
              </span>
              <span className="q-caption" style={{ color: inkSubtle }}>
                {r.detail}{r.state === "fail" ? ` · ${r.reason}` : ""}
              </span>
            </span>
            {r.state === "fail" ? (
              <Button size="sm" variant="tertiary" onClick={() => onFix(r.reason)}>{r.fix}</Button>
            ) : null}
          </div>
        ))}
      </div>

      <div className="q-caption" style={{ color: inkSubtle, marginTop: 8 }}>
        The resolver short-circuits. Rules after the first failure never ran, so they are shown as not
        evaluated rather than as passes.
      </div>

      <div style={{ marginTop: 16, padding: 12, background: surface1, border: `1px solid ${hairline}` }}>
        <div className="q-emphasis" style={{ color: ink }}>
          Answer: {trace.available ? "available" : "unavailable"}
          {trace.reason ? ` · ${trace.reason}` : ""}
        </div>
        <div className="q-body-sm" style={{ color: inkMuted, marginTop: 4 }}>
          Resumes: {trace.nextAvailableAt || "unknown"} · pre-orders:{" "}
          {trace.acceptsScheduledOrders ? "yes" : "no"} · preparation:{" "}
          {trace.preparationMinutes === null ? "no band covers now" : `${trace.preparationMinutes} min`}
        </div>
        <div className="q-caption" style={{ color: inkSubtle, marginTop: 4 }}>
          Computed {dt(trace.computedAt)} · never cached
        </div>
      </div>
    </Drawer>
  );
}

/* ── bulk bar ──────────────────────────────────────────────────────────────
 * An action is offered only when it is valid for every selected row. A mixed
 * selection disables it and names the count and the first offender — it never
 * quietly acts on the valid subset, which is how an operator ends up with half
 * a batch applied and no way to tell which half.
 */
function BulkBar({ rows, selected, onClear, onCloseSelected }) {
  const chosen = rows.filter((r) => selected.has(r.id));
  if (!chosen.length) return null;

  const rule = (label, predicate, why) => {
    const bad = chosen.filter((r) => !predicate(r));
    return {
      label,
      ok: bad.length === 0,
      message: bad.length
        ? `${bad.length} of ${chosen.length} selected cannot be ${why} — «${bad[0].name}»`
        : null,
    };
  };

  /* Two of these fail as a set rather than row by row: schedules and band sets
     are brand-scoped, and the composite foreign key refuses a cross-brand write
     at the database. Do not offer what the database will refuse. */
  const brands = new Set(chosen.map((r) => r.brandId));
  const brandRule = (label) => ({
    label,
    ok: brands.size === 1,
    message: brands.size > 1
      ? `${chosen.length} selected span ${brands.size} brands — «${brandOf([...brands][0]).name}» and «${brandOf([...brands][1]).name}». A cross-brand write is refused by the database`
      : null,
  });

  const actions = [
    rule("Close selected", (r) => r.status === "ACTIVE", "closed: not active"),
    rule("Reopen selected", (r) => r.serviceState.mode !== "FOLLOW_SCHEDULE", "reopened: not overridden"),
    brandRule("Bind schedule"),
    rule("Set capacity", (r) => r.status === "ACTIVE", "set: not active"),
    brandRule("Replace preparation bands"),
    rule("Archive", (r) => r.status !== "ARCHIVED" && r.holds === 0, "archived: open capacity holds"),
  ];

  return (
    <div
      style={{
        display: "flex", flexWrap: "wrap", gap: 8, alignItems: "center",
        padding: 12, background: ink, color: "#fff",
      }}
    >
      <span className="q-body-sm">Selected: {chosen.length}</span>
      {actions.map((a) => (
        <span key={a.label} title={a.message || ""}>
          <button
            type="button"
            disabled={!a.ok}
            onClick={a.label === "Close selected" ? onCloseSelected : undefined}
            className="q-body-sm"
            style={{
              padding: "6px 10px", background: "transparent", color: "#fff",
              border: `1px solid ${a.ok ? "#6f6f6f" : "#393939"}`,
              opacity: a.ok ? 1 : 0.45, cursor: a.ok ? "pointer" : "not-allowed",
            }}
          >
            {a.label}
          </button>
        </span>
      ))}
      <button
        type="button"
        onClick={onClear}
        className="q-body-sm"
        style={{ marginLeft: "auto", background: "transparent", border: "none", color: "#c6c6c6", cursor: "pointer" }}
      >
        Clear
      </button>
      {actions.filter((a) => a.message).length ? (
        <div className="q-caption" style={{ width: "100%", color: "#c6c6c6" }}>
          {actions.find((a) => a.message).message}
        </div>
      ) : null}
    </div>
  );
}

/* ── view 1: the location list ─────────────────────────────────────────────*/

const TAB_DEFS = [
  { id: "attention", label: "Needs attention" },
  { id: "open", label: "Open" },
  { id: "closed", label: "Closed manually" },
  { id: "outside", label: "Outside hours" },
  { id: "capacity", label: "At capacity" },
  { id: "all", label: "All" },
];

function LocationList({ rows, onOpen, onCloseDialog, onWhy, onCapacity, stale, setStale }) {
  const [tab, setTab] = useState("attention");
  const [brand, setBrand] = useState("all");
  const [channel, setChannel] = useState("all");
  const [mode, setMode] = useState("all");
  const [search, setSearch] = useState("");
  const [recordStatus, setRecordStatus] = useState("live");
  const [sortByName, setSortByName] = useState(false);
  const [selected, setSelected] = useState(new Set());
  const [cursor, setCursor] = useState(0);
  const searchRef = useRef(null);

  /* Counts are computed before filtering, so they do not collapse as the
     selection narrows — the tabs are the context, not a result. */
  const withState = useMemo(() => rows.map((r) => ({ ...r, st: resolveState(r) })), [rows]);
  const inTab = (r, id) => {
    if (id === "all") return true;
    if (id === "attention") return r.st.attention;
    if (id === "open") return ["TRADING", "FORCE_OPEN", "NO_LIVE_MENU"].includes(r.st.key);
    if (id === "closed") return r.serviceState.mode === "FORCE_CLOSED";
    if (id === "outside") return ["OUTSIDE_HOURS", "CLOSED_BY_EXCEPTION"].includes(r.st.key);
    if (id === "capacity") return r.st.key === "AT_CAPACITY";
    return true;
  };
  const counts = TAB_DEFS.map((t) => ({
    ...t,
    count: withState.filter((r) => (t.id === "all" ? true : r.status !== "ARCHIVED") && inTab(r, t.id)).length,
  }));

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    let out = withState.filter((r) => {
      if (recordStatus === "live" && r.status === "ARCHIVED") return false;
      if (recordStatus === "archived" && r.status !== "ARCHIVED") return false;
      if (!inTab(r, tab)) return false;
      if (brand !== "all" && r.brandId !== brand) return false;
      if (channel !== "all" && !r.channels.some((c) => c.channelId === channel)) return false;
      if (mode !== "all" && !r.bindings[mode]) return false;
      if (q && ![r.name, r.code, r.slug].join(" ").toLowerCase().includes(q)) return false;
      return true;
    });
    out = out.slice().sort((a, b) =>
      sortByName ? a.name.localeCompare(b.name, "ru") : a.st.rank - b.st.rank || a.name.localeCompare(b.name, "ru"));
    return out;
  }, [withState, tab, brand, channel, mode, search, recordStatus, sortByName]);

  /* Rows are focusable and keyboard-driven. The legacy prototype's <tr onClick>
     with no keyboard path is a gap to fill, not a pattern to copy. */
  useEffect(() => {
    const onKey = (e) => {
      const typing = ["INPUT", "TEXTAREA", "SELECT"].includes(e.target.tagName);
      if (e.key === "/" && !typing) { e.preventDefault(); searchRef.current?.querySelector("input")?.focus(); return; }
      if (typing) return;
      if (e.key === "j") setCursor((c) => Math.min(c + 1, filtered.length - 1));
      if (e.key === "k") setCursor((c) => Math.max(c - 1, 0));
      if (e.key === "Enter" && filtered[cursor]) onOpen(filtered[cursor].id);
      if (e.key === "c" && filtered[cursor]) onCloseDialog(filtered[cursor]);
      if (e.key === "x" && filtered[cursor]) toggle(filtered[cursor].id);
      if (e.key === "Escape") setSelected(new Set());
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  const toggle = (id) => setSelected((s) => {
    const n = new Set(s);
    if (n.has(id)) n.delete(id); else n.add(id);
    return n;
  });

  const multiBrand = new Set(LOCATIONS.map((l) => l.brandId)).size > 1;
  const multiZone = new Set(LOCATIONS.map((l) => l.timezone)).size > 1;

  const metrics = [
    { label: "Closed manually", value: withState.filter((r) => r.serviceState.mode === "FORCE_CLOSED").length },
    { label: "At capacity", value: withState.filter((r) => r.st.key === "AT_CAPACITY").length },
    { label: "No active channel", value: withState.filter((r) => r.status === "ACTIVE" && !activeChannels(r).length).length },
    { label: "No live menu", value: withState.filter((r) => r.st.key === "NO_LIVE_MENU").length },
  ];

  const TH = ({ children, align, width }) => (
    <th
      className="q-caption"
      style={{
        textAlign: align || "left", padding: "10px 12px", background: surface1, color: inkMuted,
        fontWeight: 600, borderBottom: `1px solid ${hairline}`, whiteSpace: "nowrap", width,
      }}
    >
      {children}
    </th>
  );

  return (
    <>
      {/* Counts derived from the same array the table renders, so they cannot
          disagree with it. */}
      <div style={{ display: "flex", gap: 1, marginBottom: 16, background: hairline, border: `1px solid ${hairline}` }}>
        {metrics.map((m) => (
          <div key={m.label} style={{ flex: 1, background: canvas, padding: 12 }}>
            <div className="q-caption" style={{ color: inkMuted }}>{m.label}</div>
            <div className="q-data-lg" style={{ color: m.value ? ink : inkSubtle, marginTop: 2 }}>{m.value}</div>
          </div>
        ))}
      </div>

      <Tabs tabs={counts} active={tab} onChange={setTab} />

      <FilterBar>
        <div ref={searchRef}>
          <SearchInput value={search} onChange={setSearch} placeholder="Name, code or slug   /" />
        </div>
        {multiBrand ? (
          <Select
            label="Brand" value={brand} onChange={setBrand}
            options={[{ value: "all", label: "All brands" }, ...BRANDS.map((b) => ({ value: b.id, label: b.name }))]}
          />
        ) : null}
        <Select
          label="Channel" value={channel} onChange={setChannel}
          options={[{ value: "all", label: "All channels" },
            ...CHANNELS.filter((c) => c.status !== "ARCHIVED").map((c) => ({ value: c.id, label: c.name }))]}
        />
        <Select
          label="Mode" value={mode} onChange={setMode}
          options={[{ value: "all", label: "All modes" }, ...MODES.map((m) => ({ value: m, label: MODE_LABEL[m] }))]}
        />
        <Select
          label="Record" value={recordStatus} onChange={setRecordStatus}
          options={[{ value: "live", label: "Except archived" }, { value: "archived", label: "Archived only" }, { value: "any", label: "All" }]}
        />
        {sortByName ? (
          <span className="q-caption" style={{ display: "inline-flex", gap: 8, alignItems: "center", color: inkMuted }}>
            Sort: name ↑
            <button
              type="button" onClick={() => setSortByName(false)}
              className="q-caption"
              style={{ background: "none", border: "none", color: blue, cursor: "pointer", padding: 0 }}
            >
              reset
            </button>
          </span>
        ) : null}
        <span style={{ marginLeft: "auto", display: "flex", gap: 8 }}>
          <Button size="sm" variant="ghost" onClick={() => setSortByName((v) => !v)}>Sort by name</Button>
          <Button size="sm" variant="ghost" onClick={() => {}}>Columns</Button>
          <Button size="sm" variant="tertiary" onClick={() => {}}>Export CSV</Button>
          <Button size="sm" onClick={() => {}}>New branch</Button>
        </span>
      </FilterBar>

      <BulkBar
        rows={filtered}
        selected={selected}
        onClear={() => setSelected(new Set())}
        onCloseSelected={() => onCloseDialog(filtered.find((r) => selected.has(r.id)))}
      />

      <div style={{ border: `1px solid ${hairline}`, borderTop: "none", background: canvas, overflowX: "auto" }}>
        <table style={{ width: "100%", borderCollapse: "collapse" }}>
          <thead>
            <tr>
              <TH />
              <TH width={240}>Branch</TH>
              <TH>Code</TH>
              <TH>Trading state</TH>
              <TH width={260}>Reason / until</TH>
              <TH>Load</TH>
              <TH>Hours today</TH>
              <TH>Modes</TH>
              <TH align="right">Channels</TH>
              <TH align="right">Prep</TH>
              <TH />
            </tr>
          </thead>
          <tbody>
            {filtered.map((r, i) => {
              const t = TONE_STYLE[r.st.tone];
              const focused = i === cursor;
              const prep = preparationNow(r, boundModes(r)[0] || "DELIVERY");
              return (
                <tr
                  key={r.id}
                  role="row"
                  tabIndex={0}
                  onFocus={() => setCursor(i)}
                  onClick={() => onOpen(r.id)}
                  onKeyDown={(e) => { if (e.key === "Enter") onOpen(r.id); }}
                  style={{
                    background: selected.has(r.id) ? "var(--q-info-tint)" : t.tint === "transparent" ? canvas : t.tint,
                    borderBottom: `1px solid ${hairline}`,
                    outline: focused ? `2px solid ${blue}` : "none", outlineOffset: -2,
                    cursor: "pointer",
                  }}
                >
                  <td style={{ padding: "8px 8px 8px 12px", borderLeft: `3px solid ${t.rule}`, width: 36 }}>
                    <input
                      type="checkbox"
                      checked={selected.has(r.id)}
                      onClick={(e) => e.stopPropagation()}
                      onChange={() => toggle(r.id)}
                      aria-label={`Select ${r.name}`}
                    />
                  </td>
                  <td style={{ padding: "8px 12px", width: 240, maxWidth: 240 }}>
                    {/* A branch whose name carries its shopping centre is normal
                        here and must not stretch the row to ten lines. Two
                        lines, then the full string on hover. */}
                    <div
                      className="q-emphasis"
                      title={r.name}
                      style={{
                        color: ink, display: "-webkit-box", WebkitLineClamp: 2,
                        WebkitBoxOrient: "vertical", overflow: "hidden",
                      }}
                    >
                      {r.name}
                    </div>
                    {multiBrand ? (
                      <div className="q-caption" style={{ color: inkSubtle }}>{brandOf(r.brandId).name}</div>
                    ) : null}
                  </td>
                  <td className="q-body-sm" style={{ padding: "8px 12px", ...mono, color: inkMuted }}>
                    {r.code}
                    {multiZone ? (
                      <div className="q-caption" style={{ color: inkSubtle }}>{r.timezone.split("/")[1]}</div>
                    ) : null}
                  </td>
                  <td style={{ padding: "8px 12px" }}>
                    <StatusPill tone={pillOf(r.st)}>{r.st.badge}</StatusPill>
                  </td>
                  <td className="q-caption" style={{ padding: "8px 12px", color: t.text, maxWidth: 260 }}>
                    {r.st.caption}
                  </td>
                  <td style={{ padding: "8px 12px" }}>
                    <LoadBar held={r.holds} max={r.serviceState.maxConcurrentOrders} stale={stale} />
                  </td>
                  <td className="q-body-sm q-tnum" style={{ padding: "8px 12px", color: inkMuted, whiteSpace: "nowrap" }}>
                    {windowsLabel(windowsToday(r, boundModes(r)[0]))}
                  </td>
                  <td style={{ padding: "8px 12px" }}><ModePills loc={r} /></td>
                  <td style={{ padding: "8px 12px", textAlign: "right" }}>
                    <CountLink
                      n={activeChannels(r).length}
                      onClick={() => onOpen(r.id, "channels")}
                      title="Active bindings — opens the branch's channels tab"
                    />
                  </td>
                  <td className="q-body-sm q-tnum" style={{ padding: "8px 12px", textAlign: "right", whiteSpace: "nowrap", color: prep === null ? inkSubtle : ink }}>
                    {prep === null ? "—" : `${prep} min`}
                  </td>
                  <td style={{ padding: "8px 12px", whiteSpace: "nowrap" }} onClick={(e) => e.stopPropagation()}>
                    <span style={{ display: "flex", gap: 4, justifyContent: "flex-end" }}>
                      {["DRAFT", "ARCHIVED"].includes(r.status) ? null : (
                        <Button
                          size="sm"
                          variant={r.serviceState.mode === "FORCE_CLOSED" ? "primary" : "tertiary"}
                          onClick={() => onCloseDialog(r)}
                        >
                          {r.serviceState.mode === "FORCE_CLOSED" ? "Reopen" : "Close"}
                        </Button>
                      )}
                      <Button size="sm" variant="ghost" onClick={() => onCapacity(r)}>Capacity</Button>
                      <Button size="sm" variant="ghost" onClick={() => onWhy(r)}>Why?</Button>
                    </span>
                  </td>
                </tr>
              );
            })}
            {!filtered.length ? (
              <tr>
                <td colSpan={11} style={{ padding: 0 }}>
                  <EmptyState
                    title="No branches match the filter"
                    description="The counts above are computed before filtering, so they still show what exists."
                    action={<Button variant="tertiary" onClick={() => { setTab("all"); setSearch(""); setBrand("all"); setChannel("all"); setMode("all"); }}>Reset filters</Button>}
                  />
                </td>
              </tr>
            ) : null}
          </tbody>
        </table>
      </div>

      <div style={{ display: "flex", gap: 12, alignItems: "center", marginTop: 8 }}>
        <span className="q-caption" style={{ color: inkSubtle }}>
          {stale
            ? `Data from ${dt(NOW_ISO).slice(6)} · refresh paused — load bars are not live`
            : `Trading state and load refresh every 15 s · last update ${dt(NOW_ISO).slice(6)}`}
        </span>
        <button
          type="button"
          onClick={() => setStale(!stale)}
          className="q-caption"
          style={{ background: "none", border: "none", color: blue, cursor: "pointer", padding: 0 }}
        >
          {stale ? "Resume stream" : "Simulate a dropped stream"}
        </button>
        <span className="q-caption" style={{ color: inkSubtle, marginLeft: "auto" }}>
          j / k move · Enter open · x select · c close · / search
        </span>
      </div>

      <div className="q-caption" style={{ color: inkSubtle, marginTop: 12, maxWidth: 900 }}>
        Nine columns by default. Menu count, record status, timezone and last change sit behind the
        column chooser, persisted per user. TIN and delivery zone are not offered at all: ADR 0038 and
        ADR 0037 are not started, and a column of dashes would be worse than their absence.
      </div>
    </>
  );
}

/* ── view 2: the branch record ─────────────────────────────────────────────*/

const RECORD_TABS = [
  { id: "profile", label: "Profile" },
  { id: "hours", label: "Hours" },
  { id: "prep", label: "Preparation" },
  { id: "channels", label: "Channels" },
  { id: "fiscal", label: "Fiscal identity" },
];

/** The week grid. Three lanes per day so "pickup shuts two hours before the
 *  hall" is visible rather than inferred, and a window that wraps past midnight
 *  draws as one band crossing into the next column rather than as nothing. */
function WeekGrid({ loc }) {
  const H = 200;
  const lanes = boundModes(loc);
  return (
    <div style={{ border: `1px solid ${hairline}`, background: canvas, padding: 16 }}>
      <div style={{ display: "flex", gap: 16, marginBottom: 8 }}>
        {lanes.map((m) => (
          <span key={m} className="q-caption" style={{ display: "inline-flex", alignItems: "center", gap: 6, color: inkMuted }}>
            <span style={{
              width: 12, height: 8,
              background: m === "DELIVERY" ? ink : m === "DINE_IN" ? "var(--q-surface-2)" : canvas,
              border: `1px solid ${ink}`,
            }} />
            {MODE_LABEL[m]} · {scheduleOf(loc.bindings[m]).name}
          </span>
        ))}
      </div>
      <div style={{ display: "flex" }}>
        <div style={{ width: 40, position: "relative", height: H }}>
          {[0, 6, 12, 18, 24].map((h) => (
            <span
              key={h}
              className="q-caption q-tnum"
              style={{ position: "absolute", top: (h / 24) * H - 6, color: inkSubtle }}
            >
              {String(h % 24).padStart(2, "0")}:00
            </span>
          ))}
        </div>
        <div style={{ display: "flex", flex: 1, gap: 1, background: hairline, border: `1px solid ${hairline}` }}>
          {[1, 2, 3, 4, 5, 6, 7].map((d) => (
            <div
              key={d}
              style={{
                flex: 1, background: canvas, position: "relative", height: H,
                outline: d === TODAY_DOW ? `1px solid ${ink}` : "none", outlineOffset: -1,
              }}
            >
              {lanes.map((m, li) => {
                const wins = expandWindows(scheduleOf(loc.bindings[m]).rules)[d];
                return wins.map((w, wi) => (
                  <span
                    key={`${m}-${wi}`}
                    title={`${MODE_LABEL[m]} ${windowLabel(w)}`}
                    style={{
                      position: "absolute",
                      left: `${(li / lanes.length) * 100 + 4}%`,
                      width: `${100 / lanes.length - 8}%`,
                      top: (w.from / 1440) * H,
                      height: Math.max(2, ((w.to - w.from) / 1440) * H),
                      background: m === "DELIVERY" ? ink : m === "DINE_IN" ? "var(--q-surface-2)" : canvas,
                      border: `1px solid ${ink}`,
                    }}
                  />
                ));
              })}
              {d === TODAY_DOW ? (
                <span style={{ position: "absolute", left: 0, right: 0, top: (NOW_MIN / 1440) * H, height: 1, background: "var(--q-error)" }} />
              ) : null}
              <span className="q-caption" style={{ position: "absolute", bottom: 2, left: 4, color: inkSubtle }}>
                {DOW_LABEL[d]}
              </span>
            </div>
          ))}
        </div>
      </div>
      <div className="q-caption" style={{ color: inkSubtle, marginTop: 8 }}>
        A window whose close is at or before its open ends the next day — 10:00–02:00 draws as one band
        crossing midnight into the following column, and carries a +1 in the label.
      </div>
    </div>
  );
}

function HoursTab({ loc, onOpenWhy }) {
  const exceptionsSoon = MODES
    .map((m) => scheduleOf(loc.bindings[m]))
    .filter(Boolean)
    .flatMap((s) => s.exceptions.map((e) => ({ ...e, schedule: s })))
    .filter((e) => e.date >= TODAY_DATE)
    .sort((a, b) => a.date.localeCompare(b.date));
  const seen = new Set();
  const exceptions = exceptionsSoon.filter((e) => (seen.has(e.id) ? false : seen.add(e.id)));

  const usedBy = (sid) => LOCATIONS.filter((l) => MODES.some((m) => l.bindings[m] === sid)).length;

  return (
    <>
      {exceptions.filter((e) => e.date === TODAY_DATE).map((e) => (
        <div key={e.id} style={{ marginBottom: 16 }}>
          <Callout
            tone="amber"
            title={`${day(e.date).slice(0, 5)} ${e.label} — ${e.closedAllDay ? "closed all day" : `${e.opens}–${e.closes}`}`}
            right={<span style={{ display: "flex", gap: 8 }}>
              <Button size="sm" variant="tertiary" onClick={() => {}}>Edit</Button>
              <Button size="sm" variant="ghost" onClick={() => {}}>Delete</Button>
            </span>}
          >
            {e.reason} · added by {e.createdBy}, {dt(e.createdAt)}. Deleting it will not reopen the branch
            today — that is the trading switch's job, not a configuration delete.
          </Callout>
        </div>
      ))}

      <div style={{ display: "grid", gridTemplateColumns: "repeat(3, minmax(0,1fr))", gap: 16 }}>
        {MODES.map((m) => {
          const s = scheduleOf(loc.bindings[m]);
          const w = windowsToday(loc, m);
          return (
            <Card key={m} style={{ padding: 16 }}>
              <div className="q-caption" style={{ color: inkSubtle, letterSpacing: "0.06em" }}>
                {MODE_LABEL[m].toUpperCase()}
              </div>
              {s ? (
                <>
                  <div className="q-body" style={{ color: ink, marginTop: 8 }}>{s.name}</div>
                  <div className="q-body-sm q-tnum" style={{ color: inkMuted, marginTop: 4 }}>
                    Today: {windowsLabel(w)}
                  </div>
                  <div className="q-caption" style={{ color: inkMuted, marginTop: 4 }}>
                    Pre-orders: {s.acceptsScheduled ? "yes" : "no"} · used by {usedBy(s.id)} branches
                  </div>
                  <div style={{ display: "flex", gap: 8, marginTop: 12 }}>
                    <Button size="sm" variant="tertiary" onClick={() => {}}>Change schedule</Button>
                    <Button size="sm" variant="ghost" onClick={() => {}}>Open</Button>
                  </div>
                </>
              ) : (
                <>
                  {/* Not an error state, and never styled as one: a missing
                      binding is how a branch says it does not serve this mode. */}
                  <div className="q-body" style={{ color: inkMuted, marginTop: 8 }}>Not served</div>
                  <div className="q-caption" style={{ color: inkSubtle, marginTop: 4 }}>
                    No binding row. The resolver intersects this with the channel's own mode list.
                  </div>
                  <div style={{ marginTop: 12 }}>
                    <Button size="sm" variant="tertiary" onClick={() => {}}>Start serving</Button>
                  </div>
                </>
              )}
            </Card>
          );
        })}
      </div>

      {boundModes(loc).length ? (
        <div style={{ marginTop: 16 }}><WeekGrid loc={loc} /></div>
      ) : (
        <div style={{ marginTop: 16 }}>
          <EmptyState
            title="No schedule bound to any mode"
            description="A branch with no binding serves nothing. Bind one per fulfilment mode — schedules are brand-scoped and reusable, so thirty branches on one Ramadan timetable edit one object."
            action={<Button onClick={() => {}}>Bind a schedule</Button>}
          />
        </div>
      )}

      <Block title="Exceptions" description="Dated overrides on the schedules this branch is bound to. Forward-looking by default — a past exception is history.">
        {exceptions.length ? (
          <div style={{ border: `1px solid ${hairline}`, background: canvas }}>
            {exceptions.map((e) => (
              <div key={e.id} style={{ display: "flex", gap: 16, padding: "10px 12px", borderBottom: `1px solid ${hairline}` }}>
                <span className="q-body-sm q-tnum" style={{ color: ink, width: 96 }}>{day(e.date)}</span>
                <span style={{ minWidth: 0, flex: 1 }}>
                  <span className="q-body-sm" style={{ color: ink, display: "block" }}>{e.label}</span>
                  <span className="q-caption" style={{ color: inkSubtle }}>
                    {e.schedule.name} · {e.reason} · {e.createdBy}
                  </span>
                </span>
                <StatusPill tone={e.closedAllDay ? "failed" : "pending"}>
                  {e.closedAllDay ? "Closed all day" : `${e.opens}–${e.closes}`}
                </StatusPill>
              </div>
            ))}
          </div>
        ) : (
          <EmptyState
            title="No upcoming exceptions"
            description="Add non-working days and holidays in advance and the branches close themselves — nobody has to remember on the day."
            action={<Button variant="tertiary" onClick={() => {}}>Add an exception</Button>}
          />
        )}
        <div className="q-caption" style={{ color: inkSubtle, marginTop: 8 }}>
          The schema enforces either/or: closed with both times null, or open with both set. Switching
          type clears the times rather than leaving stale values the API will reject.
        </div>
      </Block>
    </>
  );
}

/* Preparation bands and the capacity lattice. */
function PrepTab({ loc }) {
  const bands = bandsFor(loc);
  const gaps = uncoveredWindows(loc);
  const max = loc.serviceState.maxConcurrentOrders;
  const winner = (b) => {
    const rivals = bands.filter((o) => o.id !== b.id && toMin(o.from) < toMin(b.to) && toMin(o.to) > toMin(b.from)
      && (!o.day || !b.day || o.day === b.day) && (!o.mode || !b.mode || o.mode === b.mode));
    return !rivals.some((o) => o.priority > b.priority);
  };

  return (
    <>
      {gaps.length ? (
        <div style={{ marginBottom: 16 }}>
          <Callout tone="amber" title="No band covers part of today's opening hours">
            {gaps.map((g) => `${fromMin(g.from)}–${fromMin(g.to)}`).join(", ")} — the storefront quotes no
            preparation time at all in those minutes, which is a customer-facing hole rather than a
            missing setting.
          </Callout>
        </div>
      ) : null}

      <Block first title="Day strip" description="Bands as they land on the clock. An overlap is legal and settled by priority — the loser is drawn hollow, because a shadowed band is otherwise invisible until a customer gets the wrong promise.">
        <div style={{ border: `1px solid ${hairline}`, background: canvas, padding: 16 }}>
          <div style={{ position: "relative", height: 26 * bands.length + 24 }}>
            {bands.map((b, i) => {
              const win = winner(b);
              return (
                <span
                  key={b.id}
                  title={`${b.minutes} min · priority ${b.priority}`}
                  style={{
                    position: "absolute", top: i * 26, height: 20,
                    left: `${(toMin(b.from) / 1440) * 100}%`,
                    width: `${((toMin(b.to) - toMin(b.from)) / 1440) * 100}%`,
                    background: win ? ink : canvas, border: `1px solid ${ink}`,
                    color: win ? "#fff" : inkMuted, display: "flex", alignItems: "center",
                    paddingLeft: 6, overflow: "hidden",
                  }}
                  className="q-caption"
                >
                  {b.minutes} min
                </span>
              );
            })}
            <span style={{ position: "absolute", top: 0, bottom: 20, left: `${(NOW_MIN / 1440) * 100}%`, width: 1, background: "var(--q-error)" }} />
            {[0, 6, 12, 18].map((h) => (
              <span key={h} className="q-caption q-tnum" style={{ position: "absolute", bottom: 0, left: `${(h / 24) * 100}%`, color: inkSubtle }}>
                {String(h).padStart(2, "0")}:00
              </span>
            ))}
          </div>
        </div>
      </Block>

      <Block title="Bands" description="The authoritative list. Saving replaces the whole set, matching the built endpoint — a shrinking set is confirmed by count.">
        <div style={{ border: `1px solid ${hairline}`, background: canvas }}>
          <table style={{ width: "100%", borderCollapse: "collapse" }}>
            <thead>
              <tr>
                {["Mode", "Day", "From", "To", "Minutes", "Priority", ""].map((h) => (
                  <th key={h} className="q-caption" style={{ textAlign: "left", padding: "8px 12px", background: surface1, color: inkMuted, borderBottom: `1px solid ${hairline}` }}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {bands.map((b) => (
                <tr key={b.id} style={{ borderBottom: `1px solid ${hairline}` }}>
                  <td className="q-body-sm" style={{ padding: "8px 12px", color: ink }}>{b.mode ? MODE_LABEL[b.mode] : "any"}</td>
                  <td className="q-body-sm" style={{ padding: "8px 12px", color: ink }}>{b.day ? DOW_LABEL[b.day] : "any"}</td>
                  <td className="q-body-sm q-tnum" style={{ padding: "8px 12px", color: ink }}>{b.from}</td>
                  <td className="q-body-sm q-tnum" style={{ padding: "8px 12px", color: ink }}>{b.to}</td>
                  <td className="q-body-sm q-tnum" style={{ padding: "8px 12px", color: ink }}>{b.minutes}</td>
                  <td className="q-body-sm q-tnum" style={{ padding: "8px 12px", color: ink }}>{b.priority}</td>
                  <td className="q-caption" style={{ padding: "8px 12px", color: inkSubtle }}>
                    {b.splitOf ? `split from ${b.splitOf} — bands never wrap past midnight` : b.note || ""}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div style={{ display: "flex", gap: 8, marginTop: 12 }}>
          <Button variant="tertiary" onClick={() => {}}>Add a band</Button>
          <Button variant="ghost" onClick={() => {}}>Check</Button>
          <Button variant="ghost" onClick={() => {}}>Copy to other branches</Button>
        </div>
      </Block>

      <Block title="Capacity" description="Open holds against the concurrent-order limit. Free slots are drawn as dashed outlines rather than left blank, because unsold capacity is the planning question.">
        <Card>
          <div style={{ display: "flex", gap: 4, flexWrap: "wrap" }}>
            {Array.from({ length: max || 8 }).map((_, i) => (
              <span
                key={i}
                style={{
                  width: 28, height: 28,
                  background: max && i < loc.holds ? (loc.holds >= max ? "var(--q-error)" : ink) : "transparent",
                  border: max && i < loc.holds ? "none" : `1px dashed ${max ? hairline : "transparent"}`,
                }}
              />
            ))}
          </div>
          <div className="q-body-sm" style={{ color: ink, marginTop: 12 }}>
            {max ? `${loc.holds} of ${max} slots held` : "No limit set — the branch accepts orders until the kitchen says otherwise"}
          </div>
          <div className="q-caption" style={{ color: inkSubtle, marginTop: 4 }}>
            The cap is advisory at browse and authoritative at checkout. Holds are read through the
            capacity port, not the interim table — they disappear when ADR 0019's orders become the
            counted set.
          </div>
        </Card>
      </Block>
    </>
  );
}

/** Channels as three states, never a checkbox: absent and paused both refuse
 *  with CHANNEL_NOT_ENABLED, but only one of them was a decision. */
function ChannelsTab({ loc }) {
  const bound = boundModes(loc);
  return (
    <>
      {!activeChannels(loc).length ? (
        <div style={{ marginBottom: 16 }}>
          <Callout tone="rose" title="This branch cannot sell at all">
            No channel is bound and active here, so every route into it answers CHANNEL_NOT_ENABLED.
          </Callout>
        </div>
      ) : null}

      <div style={{ display: "grid", gridTemplateColumns: "repeat(2, minmax(0,1fr))", gap: 16 }}>
        {CHANNELS.filter((c) => c.status !== "ARCHIVED").map((c) => {
          const b = loc.channels.find((x) => x.channelId === c.id);
          const state = !b ? "absent" : b.status === "ACTIVE" ? "active" : "paused";
          const effective = bound.filter((m) => c.modes.includes(m));
          const live = loc.liveMenuFor.includes(c.id);
          return (
            <Card key={c.id} style={{ padding: 16, background: state === "absent" ? surface1 : canvas }}>
              <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                <span className="q-body" style={{ color: ink }}>{c.name}</span>
                <span className="q-caption" style={{ ...mono, color: inkSubtle }}>{c.code}</span>
                <span style={{ marginLeft: "auto" }}>
                  <StatusPill tone={state === "active" ? "active" : state === "paused" ? "pending" : "neutral"}>
                    {state === "active" ? "Connected" : state === "paused" ? "Paused here" : "Not connected"}
                  </StatusPill>
                </span>
              </div>

              {b && b.entitlementWithdrawn ? (
                <div className="q-caption" style={{ color: inkMuted, marginTop: 8 }}>
                  Not available on your plan — forced inactive by entitlement, never deleted.
                </div>
              ) : null}

              {/* The arithmetic, shown rather than left to be inferred. Most
                  "why can't customers order pickup on the bot" questions end here. */}
              <div className="q-caption" style={{ color: inkMuted, marginTop: 10 }}>
                Channel: {c.modes.map((m) => MODE_LABEL[m]).join(", ") || "—"}
                {" · "}Branch: {bound.map((m) => MODE_LABEL[m]).join(", ") || "—"}
              </div>
              <div className="q-emphasis" style={{ color: effective.length ? ink : "var(--q-error-text)", marginTop: 2 }}>
                Effective here: {effective.map((m) => MODE_LABEL[m]).join(", ") || "nothing"}
              </div>

              <div className="q-caption" style={{ color: inkSubtle, marginTop: 10 }}>
                Payments: {c.payments.join(", ")} · price plane {c.pricePlane}
                {c.externallyPriced ? " · externally priced" : ""}
              </div>
              <div className="q-caption" style={{ color: live ? inkSubtle : "var(--q-warning-text)", marginTop: 4 }}>
                Live menu: {live ? "published" : "no publication for this channel"}
              </div>

              <div style={{ display: "flex", gap: 8, marginTop: 12 }}>
                {state === "absent" ? <Button size="sm" variant="tertiary" onClick={() => {}}>Connect</Button> : null}
                {state === "active" ? <Button size="sm" variant="ghost" onClick={() => {}}>Pause here</Button> : null}
                {state === "paused" ? <Button size="sm" variant="tertiary" onClick={() => {}}>Resume</Button> : null}
                {b ? <Button size="sm" variant="ghost" onClick={() => {}}>Disconnect</Button> : null}
              </div>
            </Card>
          );
        })}
      </div>

      <div className="q-caption" style={{ color: inkSubtle, marginTop: 12 }}>
        Edits batch into one whole-matrix PUT with an expected version, never a per-cell PATCH — a matrix
        edited cell by cell from two tabs produces a combination neither operator chose. Pausing here has
        no reason column; that is a candidate schema addition under ADR 0036.
      </div>
    </>
  );
}

function ProfileTab({ loc, onGoTab }) {
  const blockers = loc.status === "DRAFT" ? activationBlockers(loc) : [];
  const s = scheduleOf(loc.bindings[boundModes(loc)[0]]);
  return (
    <>
      {blockers.length ? (
        <div style={{ marginBottom: 24 }}>
          <Callout tone="amber" title="This branch is a draft and cannot be activated yet">
            <ul style={{ margin: "6px 0 0", paddingLeft: 18 }}>
              {blockers.map((b) => (
                <li key={b.text}>
                  <button
                    type="button"
                    onClick={() => onGoTab(b.tab)}
                    className="q-body-sm"
                    style={{ background: "none", border: "none", padding: 0, color: b.pending ? inkSubtle : blue, cursor: "pointer" }}
                  >
                    {b.text}
                  </button>
                </li>
              ))}
            </ul>
          </Callout>
        </div>
      ) : null}

      <Block first title="Identity">
        <Card>
          <div style={{ display: "grid", gridTemplateColumns: "repeat(3, minmax(0,1fr))", gap: 24 }}>
            <Field label="Name" value={loc.name} />
            <Field label="Code" value={loc.code} mono />
            <Field label="Slug" value={loc.slug} mono />
            <Field label="Brand" value={brandOf(loc.brandId).name} />
            <Field label="Timezone" value={loc.timezone} mono />
            <Field label="Record status" value={loc.status} />
            <PendingField spec={PENDING_FIELDS.nameLocales} />
            <Field label="Legacy vendor id" value={loc.legacyVendorId} mono />
            <Field label="Updated" value={dt(loc.updatedAt)} />
          </div>
        </Card>
      </Block>

      <Block title="Contact and address" description="The whole section is unbuilt. Two of these are blocking rather than merely missing: with no coordinates ADR 0037 cannot compute a delivery fee, and with no phone or address no courier and no customer can reach the branch.">
        <Card>
          <div style={{ display: "grid", gridTemplateColumns: "repeat(3, minmax(0,1fr))", gap: 24 }}>
            <PendingField spec={PENDING_FIELDS.phone} />
            <PendingField spec={PENDING_FIELDS.address} />
            <PendingField spec={PENDING_FIELDS.landmark} />
            <PendingField spec={PENDING_FIELDS.point} />
            <PendingField spec={PENDING_FIELDS.region} />
            <PendingField spec={PENDING_FIELDS.cover} />
          </div>
        </Card>
      </Block>

      <Block title="Trade">
        <Card>
          <div style={{ display: "grid", gridTemplateColumns: "repeat(3, minmax(0,1fr))", gap: 24 }}>
            <Field label="Concurrent orders" value={loc.serviceState.maxConcurrentOrders || "no limit"} />
            <Field
              label="Pre-orders"
              value={s ? `${s.acceptsScheduled ? "yes" : "no"} — set on «${s.name}»` : "—"}
            />
            <Field label="Available offerings" value={loc.offerings.available || "—"} />
            <Field label="Today" value={`${loc.todayOrders} orders · ${uzs(loc.todayRevenueMinor)}`} />
            <PendingField spec={PENDING_FIELDS.tags} />
            <PendingField spec={PENDING_FIELDS.sortOrder} />
          </div>
          {loc.offerings.soldOut.length ? (
            <div style={{ marginTop: 16, borderTop: `1px solid ${hairline}`, paddingTop: 12 }}>
              <div className="q-caption" style={{ color: inkSubtle, marginBottom: 6 }}>SOLD OUT HERE</div>
              {loc.offerings.soldOut.map((o) => (
                <div key={o.name} className="q-body-sm" style={{ color: ink }}>
                  {o.name} <span className="q-caption" style={{ color: inkMuted }}>— {o.reason}</span>
                </div>
              ))}
            </div>
          ) : null}
          <div className="q-caption" style={{ color: inkSubtle, marginTop: 12 }}>
            Pre-orders moved from the branch to the schedule, where it is more expressive. Staff who
            remember it on the vendor form will look for it here first.
          </div>
        </Card>
      </Block>
    </>
  );
}

function FiscalTab({ loc }) {
  return (
    <>
      <Callout tone="amber" title="Fiscal identity is not built — ADR 0038, proposed, not started">
        No `tenant.legal_entities` and no `tenant.location_fiscal_assignments` exist yet. Until they do,
        a branch has no answer to «which company issues the receipt for orders taken here», and once
        they land an ACTIVE branch without an assignment will be blocked from activation.
      </Callout>

      <Block title="What this tab will carry" description="A timeline of dated assignments, not a single editable INN field — a re-registration must not rewrite what a delivered order's receipt said.">
        <Card>
          <ul style={{ margin: 0, paddingLeft: 18 }}>
            {FISCAL_FIELDS.map((f) => (
              <li key={f} className="q-body-sm" style={{ color: inkMuted, marginBottom: 4 }}>{f}</li>
            ))}
          </ul>
          <div className="q-caption" style={{ color: inkSubtle, marginTop: 12 }}>
            An exclusion constraint forbids overlapping ranges for one location, so the form will offer
            «change legal entity from 01.09.2026» — closing the current range and opening the new one in
            one transaction — rather than two date fields the operator has to reconcile.
          </div>
        </Card>
      </Block>
    </>
  );
}

function LocationRecord({ loc, tab, setTab, onBack, onCloseDialog, onWhy }) {
  const st = resolveState(loc);
  const t = TONE_STYLE[st.tone];
  return (
    <>
      <button
        type="button"
        onClick={onBack}
        className="q-body-sm"
        style={{ background: "none", border: "none", padding: 0, color: blue, cursor: "pointer", marginBottom: 12 }}
      >
        ← All branches
      </button>

      {/* The identity block stays across every tab: the manager who came here to
          fix the hours still needs to see that the branch is shut. */}
      <div
        style={{
          border: `1px solid ${hairline}`, borderLeft: `3px solid ${t.rule}`,
          background: t.tint === "transparent" ? canvas : t.tint, padding: 16, marginBottom: 24,
        }}
      >
        <div style={{ display: "flex", gap: 16, alignItems: "flex-start", flexWrap: "wrap" }}>
          <div style={{ minWidth: 0 }}>
            <h1 className="q-title" style={{ margin: 0, color: ink }}>{loc.name}</h1>
            <div className="q-caption" style={{ color: inkMuted, marginTop: 4 }}>
              <span style={mono}>{loc.code}</span> · {brandOf(loc.brandId).name} ·{" "}
              <span style={mono}>{loc.timezone}</span> · record {loc.status.toLowerCase()}
            </div>
          </div>
          <div style={{ marginLeft: "auto", display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
            <StatusPill tone={pillOf(st)}>{st.badge}</StatusPill>
            <Button variant="ghost" onClick={() => onWhy(loc)}>Why can't I sell here?</Button>
            {["DRAFT", "ARCHIVED"].includes(loc.status) ? null : (
              <Button
                variant={loc.serviceState.mode === "FORCE_CLOSED" ? "primary" : "tertiary"}
                onClick={() => onCloseDialog(loc)}
              >
                {loc.serviceState.mode === "FORCE_CLOSED" ? "Reopen branch" : "Close branch"}
              </Button>
            )}
          </div>
        </div>
        <div className="q-body-sm" style={{ color: t.text, marginTop: 8 }}>
          {st.caption}
          {loc.serviceState.note ? ` · ${loc.serviceState.note}` : ""}
          {loc.serviceState.changedBy ? (
            <span className="q-caption" style={{ color: inkMuted }}>
              {" "}· {loc.serviceState.changedBy}, {dt(loc.serviceState.changedAt)}
            </span>
          ) : null}
        </div>
        {loc.lateInFlight ? (
          <div className="q-caption" style={{ color: inkMuted, marginTop: 4 }}>
            {loc.inFlight} in flight · {loc.lateInFlight} late, worst by {loc.worstLateMinutes} min —
            lateness is an overlay on these orders, not a state of the branch.
          </div>
        ) : null}
      </div>

      <Tabs tabs={RECORD_TABS} active={tab} onChange={setTab} />

      {tab === "profile" ? <ProfileTab loc={loc} onGoTab={setTab} /> : null}
      {tab === "hours" ? <HoursTab loc={loc} /> : null}
      {tab === "prep" ? <PrepTab loc={loc} /> : null}
      {tab === "channels" ? <ChannelsTab loc={loc} /> : null}
      {tab === "fiscal" ? <FiscalTab loc={loc} /> : null}
    </>
  );
}

/* ── the section ───────────────────────────────────────────────────────────*/

export default function Places({ locationId, setLocationId, tab, setTab }) {
  /* Service-state edits are held here rather than mutating the fixtures, so the
     strip, the list, the record header and the explainer all read one truth. */
  const [overrides, setOverrides] = useState({});
  const [dialogFor, setDialogFor] = useState(null);
  const [whyFor, setWhyFor] = useState(null);
  const [toast, setToast] = useState(null);
  const [stale, setStale] = useState(false);

  const rows = useMemo(
    () => LOCATIONS.map((l) => (overrides[l.id] ? { ...l, serviceState: { ...l.serviceState, ...overrides[l.id] } } : l)),
    [overrides],
  );
  const current = locationId ? rows.find((r) => r.id === locationId) : null;

  const apply = (loc, next) => {
    setOverrides((o) => ({ ...o, [loc.id]: { ...(o[loc.id] || {}), ...next } }));
    setDialogFor(null);
    setToast(
      next.mode === "FOLLOW_SCHEDULE"
        ? `${loc.name} follows its schedule again`
        : `${loc.name}: ${reasonLabel(next.reasonCode).toLowerCase()} · ${next.effectiveUntil ? `until ${dt(next.effectiveUntil).slice(6)}` : "until cancelled"}`,
    );
  };

  const openBranch = (id, which) => { setLocationId(id); setTab(which || "profile"); };

  return (
    <div style={{ maxWidth: 1440 }}>
      {!current ? (
        <>
          <div style={{ display: "flex", alignItems: "flex-start", gap: 16, marginBottom: 16 }}>
            <div style={{ minWidth: 0 }}>
              <h1 className="q-title" style={{ margin: 0, color: ink }}>Branches</h1>
              <p className="q-body-sm" style={{ margin: "4px 0 0", color: inkMuted, maxWidth: 720 }}>
                Which branch is trading right now, and which one needs somebody. Sorted by what needs a
                human first — oldest first inside a severity — not alphabetically.
              </p>
            </div>
            <div style={{ marginLeft: "auto", flexShrink: 0, textAlign: "right" }}>
              <div className="q-caption" style={{ color: inkSubtle }}>BRANDS</div>
              <div className="q-body-sm" style={{ color: ink }}>
                {BRANDS.map((b) => b.name).join(" · ")}
              </div>
              <div className="q-caption" style={{ color: inkSubtle, marginTop: 2 }}>
                {SCHEDULES.length} schedules · {CHANNELS.filter((c) => c.status !== "ARCHIVED").length} channels
              </div>
            </div>
          </div>

          <ClosedStrip
            rows={rows}
            onReopen={(r) => setDialogFor(r)}
            onOpenBranch={(id) => openBranch(id)}
          />

          <LocationList
            rows={rows}
            stale={stale}
            setStale={setStale}
            onOpen={openBranch}
            onCloseDialog={(r) => r && setDialogFor(r)}
            onWhy={(r) => setWhyFor(r)}
            onCapacity={(r) => setDialogFor(r)}
          />
        </>
      ) : (
        <LocationRecord
          loc={current}
          tab={tab || "profile"}
          setTab={setTab}
          onBack={() => setLocationId(null)}
          onCloseDialog={(r) => setDialogFor(r)}
          onWhy={(r) => setWhyFor(r)}
        />
      )}

      {dialogFor ? (
        <ServiceStateDialog
          loc={rows.find((r) => r.id === dialogFor.id)}
          onClose={() => setDialogFor(null)}
          onApply={apply}
        />
      ) : null}

      {whyFor ? (
        <WhyDrawer
          loc={rows.find((r) => r.id === whyFor.id)}
          onClose={() => setWhyFor(null)}
          onFix={(reason) => {
            setWhyFor(null);
            if (reason === "MANUALLY_CLOSED" || reason === "AT_CAPACITY") setDialogFor(whyFor);
            else openBranch(whyFor.id, reason === "OUTSIDE_SERVICE_HOURS" || reason === "CLOSED_BY_EXCEPTION" ? "hours" : "channels");
          }}
        />
      ) : null}

      {toast ? (
        <div
          style={{
            position: "fixed", left: 24, bottom: 24, zIndex: 60, background: ink, color: "#fff",
            padding: "12px 16px", display: "flex", gap: 16, alignItems: "center",
          }}
        >
          <span className="q-body-sm">{toast}</span>
          <button
            type="button"
            onClick={() => setToast(null)}
            className="q-body-sm"
            style={{ background: "none", border: "none", color: "#78a9ff", cursor: "pointer" }}
          >
            Dismiss
          </button>
        </div>
      ) : null}
    </div>
  );
}

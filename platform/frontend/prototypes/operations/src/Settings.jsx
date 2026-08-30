/* Settings — the configuration section of the operations console.
 *
 * The spec's warning is that this becomes a junk drawer: Delever's version is 47
 * capabilities over four navigation levels, the legacy dashboard's was one
 * untyped JSON blob in a tab called `Конфиг`. Both fail the same way — a person
 * cannot find a thing, and cannot tell what level a value came from. So the file
 * is built on two commitments and everything else is downstream of them.
 *
 * 1. A grouped rail, never an alphabetical list, over a home screen that answers
 *    "can this restaurant trade right now" before it answers anything else.
 *    Findability is a severity-sorted readiness panel plus one search over the
 *    key registry — possible only because ADR 0030 made keys enumerable.
 *
 * 2. Every value shows where it came from and every screen states the level it
 *    writes to. ADR 0030 resolves PLATFORM → TENANT → BRAND → LOCATION and can
 *    explain itself, so `InheritedField` renders that trace literally instead of
 *    rendering a flat form that happens to hold correct values. `is_explicit_null`
 *    gets its own state: "nobody set this here" and "somebody deliberately
 *    removed it here" are different facts and the UI may not collapse them.
 *
 * Four views are built to depth — home, locations, sales channels, order policy.
 * The other ten render their own scope note naming what backs them and which ADR
 * owns the gap, which is more honest than a shallow form over absent tables.
 */

import { useEffect, useMemo, useRef, useState } from "react";
import { ORDERS } from "./data";
import {
  ink, inkMuted, inkSubtle, hairline, surface1, canvas, blue, uzs, dt,
  StatusPill, Card, SectionHeader, Button, Tabs, FilterBar, Select, SearchInput,
  KpiTile, EmptyState, Drawer, Field,
} from "./components";
import {
  TENANT, BRANDS, LOCATIONS, SCHEDULES, CLOSE_REASONS, SALES_CHANNELS,
  PAYMENT_METHOD_COLUMNS, FULFILMENT_MODES, CHANNEL_PAYMENTS, CHANNEL_MODES,
  CELL_BLOCKED_REASON, INSTALLATIONS, CONFIG_KEYS, ACCEPTANCE_POLICY, READINESS,
  SEVERITY_LABEL, SETTINGS_NAV,
} from "./Settings.data";

/* ── severity, on three channels ───────────────────────────────────────────
 * Tint, a 3px left rule, and a caption saying why. A normal row keeps the rule
 * in transparent so the left edge never moves — a list whose alignment jumps
 * when something goes wrong is harder to scan, not easier. Advisory is neutral
 * grey rather than blue: platform blue here means primary action, link, focus
 * or selection, and nothing else.
 */
const SEV = {
  0: { tint: "var(--q-error-tint)", rule: "var(--q-error)", text: "var(--q-error-text)" },
  1: { tint: "var(--q-warning-tint)", rule: "var(--q-warning)", text: "var(--q-warning-text)" },
  2: { tint: surface1, rule: "var(--q-ink-subtle)", text: inkMuted },
  3: { tint: canvas, rule: "transparent", text: inkSubtle },
};

const mono = { fontFamily: "var(--q-font-mono)" };
const LEVELS = ["LOCATION", "BRAND", "TENANT", "PLATFORM"];

const levelLabel = (level, b, l) => ({
  LOCATION: l ? `Location «${l}»` : "Location", BRAND: b ? `Brand «${b}»` : "Brand",
  TENANT: "Company", PLATFORM: "Qoida default",
}[level]);

/* Mid-sentence form. Lower-casing the whole label would lower-case the brand
 * name with it, and a brand's name is not the console's to alter. */
const originLabel = (level, b, l) => ({
  LOCATION: l ? `location «${l}»` : "location", BRAND: b ? `brand «${b}»` : "brand",
  TENANT: "company", PLATFORM: "Qoida default",
}[level]);

/* ── ADR 0030, rendered ────────────────────────────────────────────────────
 * Three small literal functions the whole section rests on. `entryAt` reads what
 * is *stored* at one level; `trace` walks the ladder most-specific-first and
 * marks the row that won; `fieldState` answers the only question the control
 * needs — what is true at the level this screen is currently editing.
 */

function entryAt(key, level, brandId, locationId) {
  const stored = key.levels[level];
  if (!stored) return { state: "UNSET" };
  if (level === "BRAND") return stored[brandId] || { state: "UNSET" };
  if (level === "LOCATION") return (locationId && stored[locationId]) || { state: "UNSET" };
  return stored;
}

function trace(key, brandId, locationId) {
  const rows = LEVELS.map((level) => {
    const settable = key.settableAt.includes(level);
    const applicable = level !== "LOCATION" || Boolean(locationId);
    const usable = settable && applicable;
    return { level, settable, applicable, entry: usable ? entryAt(key, level, brandId, locationId) : { state: "UNSET" } };
  });

  let winner = null;
  let cleared = false;
  for (const row of rows) {
    if (!row.settable || !row.applicable) continue;
    if (row.entry.state === "SET") { winner = row; break; }
    if (row.entry.state === "NULLED" && key.explicitNullTerminates) { winner = row; cleared = true; break; }
    /* A non-terminating explicit null clears the value *at this level* and lets
     * resolution continue underneath. Folding that back into "not set" would
     * re-create the ambiguity ADR 0030 exists to remove. */
  }
  return { rows, winner, cleared, value: winner && !cleared ? winner.entry.value : undefined };
}

function fieldState(key, editLevel, brandId, locationId) {
  if (key.type === "readonly") return "readonly";
  if (!key.settableAt.includes(editLevel)) return "not-settable";
  const here = entryAt(key, editLevel, brandId, locationId);
  return here.state === "SET" ? "set-here" : here.state === "NULLED" ? "unset-here" : "inherited";
}

const fmt = (key, value) => {
  if (value === undefined || value === null) return "not set";
  if (key.type === "money") return uzs(value);
  if (key.type === "minutes") return `${value} min`;
  if (key.type === "hour") return `${String(value).padStart(2, "0")}:00`;
  if (key.type === "boolean") return value ? "Yes" : "No";
  return String(value);
};

/* ── local primitives ──────────────────────────────────────────────────────
 * components.jsx belongs to every section and a section may not widen it, so
 * the shapes this screen needs and nothing else needs live here.
 */

/** A square token. Reads as text first; the border carries no meaning alone. */
function Chip({ children, tone = "neutral", onClick }) {
  const t = {
    neutral: { color: inkMuted, background: canvas, border: hairline },
    origin: { color: inkMuted, background: surface1, border: hairline },
    here: { color: ink, background: canvas, border: ink },
    cleared: { color: "var(--q-warning-text)", background: "var(--q-warning-tint)", border: hairline },
    locked: { color: inkSubtle, background: surface1, border: hairline },
  }[tone];
  const style = {
    display: "inline-flex", alignItems: "center", height: 22, padding: "0 8px", whiteSpace: "nowrap",
    border: `1px solid ${t.border}`, background: t.background, color: t.color, maxWidth: "100%",
  };
  if (!onClick) return <span className="q-caption" style={style}>{children}</span>;
  return (
    <button type="button" onClick={onClick} className="q-caption" style={{ ...style, cursor: "pointer" }}>
      {children}
    </button>
  );
}

/** A severity-carrying list. One component, two screens, so a location row and a
 *  channel row cannot drift apart in how they signal trouble. */
function SeverityList({ columns, rows, selected, onToggle, showActions, empty }) {
  if (!rows.length) return empty || <EmptyState title="No rows match these filters" />;
  const grid = `${onToggle ? "32px " : ""}${columns.map((c) => c.width).join(" ")}`;
  const cellPad = "10px 16px 10px 13px";
  const indent = onToggle ? 48 : 0;

  return (
    <div style={{ border: `1px solid ${hairline}`, background: canvas, overflowX: "auto" }}>
      <div
        className="q-caption"
        style={{
          display: "grid", gridTemplateColumns: grid, gap: 16, alignItems: "center", padding: cellPad,
          background: surface1, color: inkMuted, borderBottom: `1px solid ${hairline}`, fontWeight: 600, minWidth: 900,
        }}
      >
        {onToggle ? <span /> : null}
        {columns.map((c) => <span key={c.key} style={{ textAlign: c.align || "left" }}>{c.label}</span>)}
      </div>

      {rows.map((row) => {
        const sev = SEV[Math.min(row.severity, 3)];
        const on = selected?.includes(row.id);
        return (
          <div
            key={row.id}
            style={{
              borderBottom: `1px solid ${hairline}`, borderLeft: `3px solid ${sev.rule}`,
              background: on ? "var(--q-info-tint)" : sev.tint, minWidth: 900,
            }}
          >
            <div style={{ display: "grid", gridTemplateColumns: grid, gap: 16, alignItems: "center", padding: cellPad }}>
              {onToggle ? (
                <input
                  type="checkbox" checked={Boolean(on)} onChange={() => onToggle(row.id)}
                  aria-label={`Select ${row.selectLabel || row.id}`}
                  style={{ accentColor: blue, width: 16, height: 16 }}
                />
              ) : null}
              {columns.map((c) => (
                <div key={c.key} className="q-body-sm" style={{ color: ink, textAlign: c.align || "left", minWidth: 0 }}>
                  {row.cells[c.key]}
                </div>
              ))}
            </div>
            {row.caption ? (
              <div className="q-caption" style={{ padding: "0 16px 10px 13px", color: sev.text, marginLeft: indent }}>
                {row.caption}
              </div>
            ) : null}
            {row.actions && showActions ? (
              <div style={{ padding: "0 16px 8px 13px", display: "flex", gap: 4, marginLeft: indent }}>{row.actions}</div>
            ) : null}
          </div>
        );
      })}
    </div>
  );
}

/**
 * `InheritedField` — the control that makes the section work. Five states driven
 * by ADR 0030's resolution trace, and the chip is a button expanding the trace
 * inline rather than a `title=` tooltip: the Togora report names the tooltip
 * version as a defect to fix, not a pattern to copy, and an inline expansion is
 * keyboard-reachable by construction.
 */
function InheritedField({ keyDef: k, editLevel, brandId, locationId, brandName, locationName, onSwitchScope }) {
  const [open, setOpen] = useState(false);
  const t = useMemo(() => trace(k, brandId, locationId), [k, brandId, locationId]);
  const state = fieldState(k, editLevel, brandId, locationId);
  const toggle = () => setOpen(!open);

  const chip = () => {
    if (state === "readonly") return <Chip tone="locked">Read-only · {k.adr}</Chip>;
    if (state === "not-settable") {
      const highest = k.settableAt[k.settableAt.length - 1];
      const word = highest === "BRAND" ? "brand" : highest === "TENANT" ? "company" : "platform";
      return <Chip tone="locked" onClick={() => onSwitchScope?.(highest)}>Set at {word} level</Chip>;
    }
    if (state === "set-here") return <Chip tone="here" onClick={toggle}>Set here</Chip>;
    if (state === "unset-here") return <Chip tone="cleared" onClick={toggle}>Cleared here</Chip>;
    if (!t.winner) return <Chip tone="origin" onClick={toggle}>Not set anywhere</Chip>;
    return <Chip tone="origin" onClick={toggle}>From {originLabel(t.winner.level, brandName, locationName)}</Chip>;
  };

  const shown = state === "readonly" ? k.readonlyValue : t.cleared ? "cleared" : fmt(k, t.value);
  const muted = state === "inherited" || state === "not-settable" || state === "readonly" || t.cleared;
  const act = (label) => <Button size="sm" variant="ghost" disabled={!k.built}>{label}</Button>;

  return (
    <div style={{ padding: "12px 0", borderBottom: `1px solid ${hairline}` }}>
      <div style={{ display: "flex", gap: 16, alignItems: "baseline", flexWrap: "wrap" }}>
        <div style={{ flex: "1 1 240px", minWidth: 0 }}>
          <div className="q-body-sm" style={{ color: ink }}>{k.label}</div>
          <div className="q-caption" style={{ ...mono, color: inkSubtle, marginTop: 2 }}>{k.code}</div>
        </div>
        <div className={muted ? "q-body-sm" : "q-emphasis"} style={{ color: muted ? inkMuted : ink, textAlign: "right" }}>
          {shown}
        </div>
        {chip()}
      </div>

      {k.note ? <div className="q-caption" style={{ color: inkMuted, marginTop: 6, maxWidth: 620 }}>{k.note}</div> : null}
      {!k.built ? (
        <div className="q-caption" style={{ color: "var(--q-warning-text)", marginTop: 6 }}>
          Not built — no configuration key is declared for this yet. Owner: {k.adr}.
        </div>
      ) : null}

      <div style={{ display: "flex", gap: 4, marginTop: 8, flexWrap: "wrap" }}>
        {state === "set-here" ? <>{act("Edit")}{act("Return to inherited")}</> : null}
        {state === "inherited" ? act("Override here") : null}
        {state === "unset-here" ? <>{act("Set a value")}{act("Return to inherited")}</> : null}
      </div>

      {open ? (
        <div style={{ marginTop: 8, border: `1px solid ${hairline}`, background: surface1 }}>
          <div className="q-caption" style={{ padding: "8px 12px", color: inkMuted, borderBottom: `1px solid ${hairline}` }}>
            Where this value comes from — most specific first
            {k.explicitNullTerminates ? " · an explicit clear at any level stops resolution for this key" : ""}
          </div>
          {t.rows.map((r) => {
            const won = t.winner && r.level === t.winner.level;
            const body = !r.applicable ? "no branch selected"
              : !r.settable ? "not settable at this level"
                : r.entry.state === "SET" ? fmt(k, r.entry.value)
                  : r.entry.state === "NULLED" ? "explicitly cleared" : "not set";
            return (
              <div
                key={r.level}
                style={{
                  display: "flex", gap: 12, alignItems: "baseline", padding: "8px 12px",
                  borderBottom: `1px solid ${hairline}`, background: won ? canvas : "transparent",
                  borderLeft: won ? `3px solid ${ink}` : "3px solid transparent",
                }}
              >
                <span className="q-caption" style={{ color: inkMuted, width: 150, flexShrink: 0 }}>
                  {levelLabel(r.level, brandName, locationName)}
                </span>
                <span className={won ? "q-emphasis" : "q-body-sm"} style={{ color: won ? ink : inkMuted, width: 120, flexShrink: 0 }}>
                  {body}
                </span>
                <span className="q-caption" style={{ color: inkSubtle, minWidth: 0 }}>
                  {r.entry.by ? `${r.entry.by} · ${dt(r.entry.when)}` : ""}
                  {r.entry.reason ? ` · «${r.entry.reason}»` : ""}
                  {r.entry.by === "Ilhom Toshmatov" ? " · account disabled" : ""}
                </span>
                {won ? <span className="q-caption" style={{ marginLeft: "auto", color: ink }}>wins</span> : null}
              </div>
            );
          })}
        </div>
      ) : null}
    </div>
  );
}

/** The bulk bar. An action is offered only when it is valid for every selected
 *  row; a mixed selection disables it and says how many cannot, rather than
 *  acting on the valid subset and reporting one cheerful toast. */
function BulkBar({ count, actions, onClear }) {
  if (!count) return null;
  return (
    <div
      style={{
        display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap", padding: 12,
        background: "var(--q-info-tint)", border: `1px solid ${hairline}`, borderTop: "none",
      }}
    >
      <span className="q-emphasis" style={{ color: ink }}>{count} selected</span>
      {actions.map((a) => (
        <div key={a.label} style={{ display: "flex", flexDirection: "column", gap: 2 }}>
          <Button size="sm" variant={a.invalid ? "tertiary" : "secondary"} disabled={Boolean(a.invalid)}>{a.label}</Button>
          {a.invalid ? <span className="q-caption" style={{ color: "var(--q-error-text)" }}>{a.invalid}</span> : null}
        </div>
      ))}
      <Button size="sm" variant="ghost" onClick={onClear} style={{ marginLeft: "auto" }}>Clear selection</Button>
    </div>
  );
}

/** A screen this prototype did not build, saying what backs it rather than
 *  rendering a form over tables that do not exist. */
function NotPrototyped({ screen }) {
  return (
    <Card>
      <div className="q-subhead" style={{ color: ink }}>{screen.label}</div>
      <div className="q-body-sm" style={{ color: inkMuted, marginTop: 8, maxWidth: 620 }}>{screen.purpose}</div>
      <div style={{ marginTop: 16, borderTop: `1px solid ${hairline}`, paddingTop: 16, display: "grid", gap: 12, maxWidth: 620 }}>
        <Field label="Editing level" value={screen.scope} />
        <Field label="Not prototyped here" value={screen.adr} />
      </div>
      <div className="q-caption" style={{ color: inkMuted, marginTop: 16, maxWidth: 620 }}>
        Four views were built to depth instead of fourteen to a sketch. The spec's own rule is that a
        settings screen earns its place by being opened during setup or during an incident; the four
        built here are the ones opened during an incident.
      </div>
    </Card>
  );
}

/* ── screen: home ──────────────────────────────────────────────────────────*/

function Home({ go, scope }) {
  const [query, setQuery] = useState("");
  const searchRef = useRef(null);

  /* `/` opens find-a-setting from anywhere in settings. One control does more
   * for findability than any navigation redesign, and it exists only because
   * ADR 0030 made the key registry code-owned and enumerable. */
  useEffect(() => {
    const onKey = (e) => {
      if (e.key === "/" && document.activeElement?.tagName !== "INPUT") {
        e.preventDefault();
        searchRef.current?.focus();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const ready = useMemo(() => [...READINESS].sort((a, b) => a.severity - b.severity || b.count - a.count), []);

  const hits = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return [];
    return CONFIG_KEYS.filter((k) => `${k.label} ${k.code} ${k.where}`.toLowerCase().includes(q)).slice(0, 7);
  }, [query]);

  /* Index numbers and readiness numbers come off the same fixtures, so they
   * cannot disagree — the Togora report's complaint about two screens quoting
   * different counts for one thing. */
  const activeChannels = SALES_CHANNELS.filter((c) => c.status === "ACTIVE");
  const counts = {
    locations: `${LOCATIONS.length} total · ${LOCATIONS.filter((l) => l.mode === "FORCE_CLOSED").length} closed by hand`,
    channels: `${activeChannels.length} active · ${activeChannels.filter((c) => !Object.values(CHANNEL_PAYMENTS[c.id]).includes("ON")).length} with no payment method`,
    integrations: `${INSTALLATIONS.length} installations · ${INSTALLATIONS.filter((i) => i.connection !== "SUCCEEDED").length} not confirmed`,
    fiscal: "143 of 1 204 positions unclassified",
  };
  const pct = Math.round(((1204 - 143) / 1204) * 100);

  return (
    <>
      <Card style={{ marginBottom: 24 }}>
        <div style={{ display: "flex", alignItems: "baseline", gap: 12 }}>
          <span className="q-subhead" style={{ color: ink }}>Can this restaurant trade</span>
          <span className="q-caption" style={{ color: inkSubtle }}>
            blocking first, then expiring, then advisory · largest count first within each
          </span>
        </div>

        {!ready.length ? (
          <div style={{ padding: 24, marginTop: 12, background: "var(--q-success-tint)", border: `1px solid ${hairline}` }}>
            <div className="q-body" style={{ color: "var(--q-success-text)" }}>Everything is configured for taking orders</div>
          </div>
        ) : (
          <div style={{ marginTop: 12, border: `1px solid ${hairline}` }}>
            {ready.map((r) => {
              const sev = SEV[r.severity];
              return (
                <div
                  key={r.id}
                  style={{
                    borderBottom: `1px solid ${hairline}`, borderLeft: `3px solid ${sev.rule}`,
                    background: sev.tint, padding: "12px 16px",
                  }}
                >
                  <div style={{ display: "flex", gap: 12, alignItems: "baseline", flexWrap: "wrap" }}>
                    <span className="q-caption" style={{ color: sev.text, width: 64, flexShrink: 0 }}>{SEVERITY_LABEL[r.severity]}</span>
                    <span className="q-body-sm" style={{ color: ink, minWidth: 0 }}>{r.title}</span>
                    <button
                      type="button" onClick={() => go(r.to)} className="q-emphasis q-tnum"
                      style={{ marginLeft: "auto", background: "transparent", border: "none", color: blue, cursor: "pointer", padding: 0 }}
                    >
                      {r.count}
                    </button>
                  </div>
                  <div className="q-caption" style={{ color: sev.text, marginTop: 4 }}>
                    {r.scope}{r.why ? ` — ${r.why}` : ""}
                  </div>
                  <div className="q-caption" style={{ ...mono, color: inkSubtle, marginTop: 2 }}>
                    {r.detail}{r.adr ? ` · ${r.adr}` : ""}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </Card>

      <Card style={{ marginBottom: 24 }}>
        <div className="q-subhead" style={{ color: ink }}>Find a setting</div>
        <div className="q-caption" style={{ color: inkMuted, margin: "4px 0 12px" }}>
          Search the key registry — code, description, owning module and the levels it may be set at.
          Press <span style={mono}>/</span> from anywhere in settings.
        </div>
        <input
          ref={searchRef} value={query} onChange={(e) => setQuery(e.target.value)}
          placeholder="late, quiet hours, ИКПУ, ordering.…" className="q-body-sm"
          style={{
            height: 40, width: "100%", maxWidth: 520, padding: "0 12px", background: canvas, color: ink,
            border: `1px solid ${hairline}`, borderRadius: "var(--q-radius)", outline: "none",
          }}
          onFocus={(e) => { e.target.style.border = `2px solid ${blue}`; }}
          onBlur={(e) => { e.target.style.border = `1px solid ${hairline}`; }}
        />
        {hits.length ? (
          <div style={{ marginTop: 8, border: `1px solid ${hairline}`, maxWidth: 760 }}>
            {hits.map((k) => {
              const t = trace(k, scope.brandId, scope.locationId);
              const where = originLabel(t.winner?.level, scope.brandName, scope.locationName);
              return (
                <button
                  key={k.code} type="button" onClick={() => go(k.screen)}
                  style={{
                    display: "flex", gap: 16, alignItems: "baseline", width: "100%", textAlign: "left",
                    padding: "10px 12px", background: canvas, border: "none",
                    borderBottom: `1px solid ${hairline}`, cursor: "pointer",
                  }}
                >
                  <span className="q-body-sm" style={{ color: ink, flex: "1 1 200px", minWidth: 0 }}>{k.label}</span>
                  <span className="q-caption" style={{ color: inkMuted, flex: "0 0 200px" }}>{k.where}</span>
                  <span className="q-caption" style={{ color: inkSubtle, flex: "0 0 220px" }}>
                    {k.type === "readonly" ? `read-only · ${k.readonlyValue}`
                      : !t.winner ? `not set · settable at ${k.settableAt.length} levels`
                        : t.cleared ? `cleared at ${where}`
                          : t.winner.level === "PLATFORM" ? `Qoida default (${fmt(k, t.value)})`
                            : `set at ${where} (${fmt(k, t.value)})`}
                  </span>
                </button>
              );
            })}
          </div>
        ) : null}
      </Card>

      <Card style={{ marginBottom: 24 }}>
        <div className="q-subhead" style={{ color: ink }}>Fiscal classification coverage</div>
        <div className="q-caption" style={{ color: inkMuted, margin: "4px 0 12px", maxWidth: 700 }}>
          Priceable nodes carrying an ИКПУ code. Blocking once ADR 0038 lands, advisory today because
          V0021 left the columns nullable on purpose so operators could start before the wall arrives.
        </div>
        <div style={{ display: "flex", alignItems: "baseline", gap: 12 }}>
          <span className="q-data-lg" style={{ color: ink }}>{pct}%</span>
          <span className="q-caption" style={{ color: inkMuted }}>1 061 of 1 204 · 143 left, the delivery fee among them</span>
        </div>
        <div style={{ display: "flex", height: 8, marginTop: 12, border: `1px solid ${hairline}` }}>
          <div style={{ width: `${pct}%`, background: ink }} />
          <div style={{ width: `${100 - pct}%`, background: surface1 }} />
        </div>
      </Card>

      <div className="q-subhead" style={{ color: ink, marginBottom: 12 }}>All settings</div>
      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(300px, 1fr))", gap: 16 }}>
        {SETTINGS_NAV.map((g) => (
          <Card key={g.id} style={{ padding: 16 }}>
            <div className="q-caption" style={{ color: inkSubtle, marginBottom: 8 }}>{g.label}</div>
            {g.screens.map((s) => (
              <button
                key={s.id} type="button" onClick={() => go(s.id)}
                style={{
                  display: "block", width: "100%", textAlign: "left", padding: "8px 0",
                  background: "transparent", border: "none", borderTop: `1px solid ${hairline}`, cursor: "pointer",
                }}
              >
                <span className="q-body-sm" style={{ color: s.built ? blue : ink }}>{s.label}</span>
                {!s.built ? <span className="q-caption" style={{ color: inkSubtle }}> · not prototyped</span> : null}
                <span className="q-caption" style={{ display: "block", color: inkMuted, marginTop: 2 }}>{s.purpose}</span>
                {counts[s.id] ? (
                  <span className="q-caption" style={{ display: "block", color: inkSubtle, marginTop: 2 }}>{counts[s.id]}</span>
                ) : null}
              </button>
            ))}
          </Card>
        ))}
      </div>
    </>
  );
}

/* ── screen: locations ─────────────────────────────────────────────────────*/

const scheduleName = (id) => SCHEDULES.find((s) => s.id === id)?.name;

/** Severity weight. Alphabetical is the wrong default because it puts the shut
 *  branch in the middle of the list. A force-open override sits at 2 with the
 *  exception-day closures: both are a human decision currently overriding the
 *  timetable, and both want an eye on them before an ordinary open branch does. */
function locationSeverity(l) {
  if (l.status === "DRAFT" || l.status === "SUSPENDED") return 4;
  if (l.mode === "FORCE_CLOSED") return l.effectiveUntil ? 1 : 0;
  if (l.mode === "FORCE_OPEN" || !l.scheduleOpen) return 2;
  return 3;
}

const nowState = (l) =>
  l.mode === "FORCE_CLOSED" ? { label: "Closed by hand", tone: "failed" }
    : l.mode === "FORCE_OPEN" ? { label: "Open by hand", tone: "pending" }
      : !l.scheduleOpen ? { label: "Closed on schedule", tone: "neutral" }
        : { label: "Open", tone: "active" };

const LOCATION_COLUMNS = [
  { key: "name", label: "Branch", width: "minmax(220px, 2fr)" },
  { key: "status", label: "Status", width: "120px" },
  { key: "now", label: "Now", width: "160px" },
  { key: "hours", label: "Hours", width: "minmax(160px, 1fr)" },
  { key: "prep", label: "Prep", width: "80px", align: "right" },
  { key: "load", label: "Load", width: "90px", align: "right" },
  { key: "channels", label: "Channels", width: "90px", align: "right" },
  { key: "fiscal", label: "Legal entity", width: "130px" },
];

/* A dialog, never a toggle. The database refuses a reasonless override, so a
 * bare switch would be a control that fails half the time it is used. */
function CloseDialog({ location, onClose }) {
  const radioRow = (name, checked, title, note) => (
    <label key={title} style={{ display: "flex", gap: 12, padding: 12, borderBottom: `1px solid ${hairline}`, cursor: "pointer" }}>
      <input type="radio" name={name} defaultChecked={checked} style={{ accentColor: blue, marginTop: 2 }} />
      <span style={{ minWidth: 0 }}>
        <span className="q-body-sm" style={{ color: ink, display: "block" }}>{title}</span>
        <span className="q-caption" style={{ color: inkMuted }}>{note}</span>
      </span>
    </label>
  );

  return (
    <Drawer title={`Close ${location.name}`} onClose={onClose}>
      <div className="q-body-sm" style={{ color: inkMuted, marginBottom: 16 }}>
        A closure needs a reason. The database refuses a reasonless override
        (<span style={mono}>ck_location_service_reason</span>), so this is a dialog and never a bare switch.
      </div>

      <div className="q-caption" style={{ color: inkSubtle, marginBottom: 8 }}>Reason</div>
      <div style={{ border: `1px solid ${hairline}`, marginBottom: 24 }}>
        {[...CLOSE_REASONS].sort((a, b) => a.order - b.order).map((r) =>
          radioRow("reason", r.code === location.reasonCode, r.internal, `Customer sees: «${r.customer}»`))}
      </div>

      <div className="q-caption" style={{ color: inkSubtle, marginBottom: 8 }}>For how long</div>
      <div style={{ border: `1px solid ${hairline}`, marginBottom: 16 }}>
        {radioRow("until", true, "Until end of day", "effective_until = 23:59 local")}
        {radioRow("until", false, "For 60 minutes", "effective_until = now + 60 min")}
        {radioRow("until", false, "Until reopened by hand", "effective_until stays null")}
      </div>

      <div style={{ borderLeft: "3px solid var(--q-warning)", background: "var(--q-warning-tint)", padding: 12, marginBottom: 24 }}>
        <div className="q-caption" style={{ color: "var(--q-warning-text)" }}>
          «Until reopened by hand» leaves no expiry. That is exactly how Bunyodkor has been shut since
          18.08 — the fryer broke on Tuesday and the branch is still closed on Saturday. Pick a time
          unless you genuinely mean indefinitely.
        </div>
      </div>

      <div style={{ display: "flex", gap: 8 }}>
        <Button onClick={onClose}>Close the branch</Button>
        <Button variant="ghost" onClick={onClose}>Cancel</Button>
      </div>
    </Drawer>
  );
}

function Locations({ scope }) {
  const [tab, setTab] = useState("all");
  const [stateFilter, setStateFilter] = useState("any");
  const [channelFilter, setChannelFilter] = useState("any");
  const [search, setSearch] = useState("");
  const [selected, setSelected] = useState([]);
  const [closing, setClosing] = useState(null);

  const inBrand = LOCATIONS.filter((l) => l.brandId === scope.brandId);

  /* Counts are computed before filtering, so the operator sees where the work is
   * rather than seeing zero because they are standing in the wrong tab. */
  const tabs = [
    { id: "all", label: "All", count: inBrand.length },
    { id: "ACTIVE", label: "Active", count: inBrand.filter((l) => l.status === "ACTIVE").length },
    { id: "DRAFT", label: "Draft", count: inBrand.filter((l) => l.status === "DRAFT").length },
    { id: "SUSPENDED", label: "Suspended", count: inBrand.filter((l) => l.status === "SUSPENDED").length },
  ];

  const rows = useMemo(() => {
    const q = search.trim().toLowerCase();
    const matchesState = (l) =>
      stateFilter === "any" ? true
        : stateFilter === "forced-closed" ? l.mode === "FORCE_CLOSED"
          : stateFilter === "forced-open" ? l.mode === "FORCE_OPEN"
            : stateFilter === "closed" ? l.mode === "FOLLOW_SCHEDULE" && !l.scheduleOpen
              : l.mode === "FOLLOW_SCHEDULE" && l.scheduleOpen;
    return inBrand
      .filter((l) => (tab === "all" ? true : l.status === tab))
      .filter(matchesState)
      .filter((l) => (channelFilter === "any" ? true : l.channelIds.includes(channelFilter)))
      .filter((l) => !q || `${l.name} ${l.code} ${l.address}`.toLowerCase().includes(q))
      .sort((a, b) => locationSeverity(a) - locationSeverity(b) || a.name.localeCompare(b.name));
  }, [inBrand, tab, stateFilter, channelFilter, search]);

  /* A bulk action is offered only when valid for every selected row. Acting on
   * the valid subset and reporting one cheerful toast is how a manager comes to
   * believe fourteen branches reopened when twelve did. */
  const chosen = LOCATIONS.filter((l) => selected.includes(l.id));
  const noOverride = chosen.filter((l) => l.mode === "FOLLOW_SCHEDULE").length;
  const alreadyShut = chosen.filter((l) => l.mode === "FORCE_CLOSED").length;
  const bulk = [
    { label: "Close selected…", invalid: alreadyShut ? `${alreadyShut} of ${chosen.length} selected are already closed by hand` : null },
    { label: "Clear override", invalid: noOverride ? `${noOverride} of ${chosen.length} selected have no override to clear` : null },
    { label: "Bind a schedule…", invalid: null },
  ];

  const listRows = rows.map((l) => {
    const state = nowState(l);
    const unbound = l.bindings.filter((b) => !b.scheduleId);
    const caption = [
      l.mode === "FORCE_CLOSED"
        ? `${l.note} · closed by ${l.overrideBy} at ${dt(l.overrideSince)} · ${l.effectiveUntil ? `until ${dt(l.effectiveUntil)}` : "no expiry set — nobody will reopen this automatically"}`
        : null,
      l.mode === "FORCE_OPEN" ? `${l.note} · opened by ${l.overrideBy} · until ${dt(l.effectiveUntil)}` : null,
      l.exceptionToday ? `Schedule exception today — ${l.exceptionToday}` : null,
      unbound.length ? `No schedule bound for ${unbound.map((b) => b.mode).join(", ")} — the resolver refuses this mode` : null,
      !l.channelIds.length && l.status === "ACTIVE" ? "Active but bound to no sales channel — the resolver returns CHANNEL_NOT_ENABLED" : null,
      l.status !== "ACTIVE" ? l.note : null,
      l.timezone !== TENANT.timezone ? `Timezone ${l.timezone} differs from the company default` : null,
    ].filter(Boolean).join(" · ");

    return {
      id: l.id, severity: locationSeverity(l), caption, selectLabel: l.name,
      cells: {
        name: (
          <div style={{ minWidth: 0 }}>
            <div style={{ color: ink, overflowWrap: "anywhere" }}>{l.name}</div>
            <div className="q-caption" style={{ ...mono, color: inkSubtle }}>{l.code}</div>
          </div>
        ),
        status: (
          <StatusPill tone={l.status === "ACTIVE" ? "active" : l.status === "SUSPENDED" ? "suspended" : "neutral"}>
            {l.status.toLowerCase()}
          </StatusPill>
        ),
        now: <StatusPill tone={state.tone}>{state.label}</StatusPill>,
        hours: (
          <span className="q-caption" style={{ color: inkMuted }}>
            {l.bindings.length
              ? l.bindings.map((b) => `${b.mode.toLowerCase()} → ${scheduleName(b.scheduleId) || "none"}`).join(", ")
              : "no bindings"}
          </span>
        ),
        prep: <span className="q-tnum">{l.prepMinutes} min</span>,
        load: <span className="q-tnum">{l.maxConcurrent ? `${l.holds} / ${l.maxConcurrent}` : `${l.holds} / ∞`}</span>,
        channels: <span className="q-tnum">{l.channelIds.length}</span>,
        fiscal: <span className="q-caption" style={{ color: "var(--q-warning-text)" }}>not built · 0038</span>,
      },
      actions: (
        <>
          <Button size="sm" variant="ghost" onClick={() => setClosing(l)}>
            {l.mode === "FORCE_CLOSED" ? "Change closure…" : "Close now…"}
          </Button>
          {l.mode !== "FOLLOW_SCHEDULE" ? <Button size="sm" variant="ghost">Clear override</Button> : null}
          <Button size="sm" variant="ghost">Change history</Button>
        </>
      ),
    };
  });

  const openNow = inBrand.filter((l) => nowState(l).label.startsWith("Open")).length;
  const shut = inBrand.filter((l) => l.mode === "FORCE_CLOSED");

  return (
    <>
      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(180px, 1fr))", gap: 16, marginBottom: 24 }}>
        <KpiTile label="Branches in this brand" value={inBrand.length} meta={`${inBrand.filter((l) => l.status === "ACTIVE").length} active`} />
        <KpiTile label="Open right now" value={openNow} meta="schedule plus overrides" />
        <KpiTile label="Closed by hand" value={shut.length} meta={`${shut.filter((l) => !l.effectiveUntil).length} with no expiry`} />
        <KpiTile label="Selling nowhere" value={inBrand.filter((l) => l.status === "ACTIVE" && !l.channelIds.length).length} meta="active, no channel bound" />
      </div>

      <Tabs tabs={tabs} active={tab} onChange={setTab} />

      <FilterBar>
        <SearchInput value={search} onChange={setSearch} placeholder="Name, code or address" />
        <Select
          label="State now" value={stateFilter} onChange={setStateFilter}
          options={[
            { value: "any", label: "Any" }, { value: "open", label: "Open" },
            { value: "closed", label: "Closed on schedule" },
            { value: "forced-closed", label: "Closed by hand" },
            { value: "forced-open", label: "Open by hand" },
          ]}
        />
        <Select
          label="Channel" value={channelFilter} onChange={setChannelFilter}
          options={[{ value: "any", label: "Any" }, ...SALES_CHANNELS.map((c) => ({ value: c.id, label: c.name }))]}
        />
        <span className="q-caption" style={{ marginLeft: "auto", color: inkSubtle }}>
          Sorted by what needs a person, not by name
        </span>
      </FilterBar>

      <BulkBar count={selected.length} actions={bulk} onClear={() => setSelected([])} />

      <SeverityList
        columns={LOCATION_COLUMNS} rows={listRows} selected={selected} showActions
        onToggle={(id) => setSelected((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id]))}
        empty={(
          <EmptyState
            title="No branches yet"
            description="Branches are provisioned by Qoida — legal entity, residency and metering are settled at creation."
            action={<Button>Request a branch</Button>}
          />
        )}
      />

      <div className="q-caption" style={{ color: inkMuted, marginTop: 12, maxWidth: 720 }}>
        Branch detail is six tabs in the spec — general, hours, load and preparation, fiscal data,
        channels, notifications — and is not prototyped. The one thing worth saying without it: the
        «Tungi yetkazib berish» schedule closes at 03:00, earlier than it opens, and the schedule grid
        must draw that window crossing midnight rather than as an empty range.
      </div>

      {closing ? <CloseDialog location={closing} onClose={() => setClosing(null)} /> : null}
    </>
  );
}

/* ── screen: sales channels ────────────────────────────────────────────────*/

function channelSeverity(c, pay, modes) {
  if (c.status === "ARCHIVED") return 4;
  if (c.status === "INACTIVE") return 3;
  if (!Object.values(pay[c.id]).includes("ON") || !Object.values(modes[c.id]).includes("ON")) return 0;
  return LOCATIONS.some((l) => l.channelIds.includes(c.id)) ? 2 : 1;
}

/** The capability matrix. Cell text carries the state, not the fill — a grid an
 *  operator has to read by colour is a grid half of them cannot read. */
function MatrixGrid({ title, note, columns, channels, values, onToggle, onBulkColumn, onBulkRow }) {
  const [why, setWhy] = useState(null);
  const grid = `minmax(220px, 1fr) repeat(${columns.length}, 120px) 150px`;
  const link = { background: "transparent", border: "none", color: blue, cursor: "pointer", textAlign: "left", padding: 0 };

  const cell = (chId, code) => {
    const v = values[chId][code];
    const blocked = v === "BLOCKED";
    const on = v === "ON";
    return (
      <button
        key={code} type="button" disabled={blocked} className="q-caption"
        onClick={() => (blocked ? null : onToggle(chId, code))}
        onFocus={() => blocked && setWhy(CELL_BLOCKED_REASON[`${chId}:${code}`])}
        onMouseEnter={() => blocked && setWhy(CELL_BLOCKED_REASON[`${chId}:${code}`])}
        style={{
          height: 32, border: `1px solid ${hairline}`, cursor: blocked ? "not-allowed" : "pointer",
          background: on ? ink : blocked ? "var(--q-surface-2)" : canvas,
          color: on ? "var(--q-inverse-ink)" : inkSubtle,
        }}
      >
        {on ? "on" : blocked ? "n/a" : "off"}
      </button>
    );
  };

  return (
    <Card style={{ marginBottom: 24, padding: 0 }}>
      <div style={{ padding: 16, borderBottom: `1px solid ${hairline}` }}>
        <div className="q-subhead" style={{ color: ink }}>{title}</div>
        <div className="q-caption" style={{ color: inkMuted, marginTop: 4, maxWidth: 760 }}>{note}</div>
      </div>

      <div style={{ overflowX: "auto" }}>
        <div style={{ minWidth: 820 }}>
          <div
            className="q-caption"
            style={{
              display: "grid", gridTemplateColumns: grid, gap: 8, padding: "10px 16px",
              background: surface1, color: inkMuted, borderBottom: `1px solid ${hairline}`,
            }}
          >
            <span>Channel</span>
            {columns.map((c) => {
              const blockedHere = channels.filter((ch) => values[ch.id][c.code] === "BLOCKED").length;
              return (
                <button key={c.code} type="button" onClick={() => onBulkColumn(c.code)} className="q-caption" style={link}>
                  {c.label}
                  <span style={{ display: "block", color: inkSubtle }}>
                    {blockedHere ? `enable the rest (${channels.length - blockedHere})` : "enable the column"}
                  </span>
                </button>
              );
            })}
            <span />
          </div>

          {channels.map((ch) => {
            /* The same rule as every other bulk control here: an action is
             * offered only when valid for every cell it would touch, and where
             * it is not it says how many it can reach. */
            const blockedInRow = columns.filter((c) => values[ch.id][c.code] === "BLOCKED").length;
            return (
              <div
                key={ch.id}
                style={{
                  display: "grid", gridTemplateColumns: grid, gap: 8, padding: "8px 16px",
                  alignItems: "center", borderBottom: `1px solid ${hairline}`,
                }}
              >
                <div style={{ minWidth: 0 }}>
                  <div className="q-body-sm" style={{ color: ink, overflowWrap: "anywhere" }}>{ch.name}</div>
                  <div className="q-caption" style={{ ...mono, color: inkSubtle }}>{ch.type}</div>
                </div>
                {columns.map((c) => cell(ch.id, c.code))}
                <button type="button" onClick={() => onBulkRow(ch.id)} className="q-caption" style={link}>
                  {blockedInRow ? `enable the rest (${columns.length - blockedInRow})` : "enable the row"}
                </button>
              </div>
            );
          })}
        </div>
      </div>

      <div
        className="q-caption"
        style={{
          padding: 12, borderTop: `1px solid ${hairline}`,
          color: why ? "var(--q-warning-text)" : inkSubtle,
          background: why ? "var(--q-warning-tint)" : canvas,
        }}
      >
        {why || "A hatched cell cannot be enabled at all. Focus or hover one to see why."}
      </div>
    </Card>
  );
}

const CHANNEL_COLUMNS = [
  { key: "name", label: "Channel", width: "minmax(220px, 2fr)" },
  { key: "type", label: "Type", width: "120px" },
  { key: "status", label: "Status", width: "110px" },
  { key: "locations", label: "Branches", width: "90px", align: "right" },
  { key: "payments", label: "Payments", width: "90px", align: "right" },
  { key: "modes", label: "Fulfilment", width: "minmax(160px, 1fr)" },
  { key: "pricing", label: "Prices from", width: "130px" },
  { key: "install", label: "Connection", width: "140px" },
];

function Channels() {
  const [typeFilter, setTypeFilter] = useState("any");
  const [statusTab, setStatusTab] = useState("all");
  const [onlyProblems, setOnlyProblems] = useState(false);
  const [pay, setPay] = useState(CHANNEL_PAYMENTS);
  const [modes, setModes] = useState(CHANNEL_MODES);
  const [confirm, setConfirm] = useState(null);

  const rows = useMemo(() => SALES_CHANNELS
    .filter((c) => (typeFilter === "any" ? true : c.type === typeFilter))
    .filter((c) => (statusTab === "all" ? true : c.status === statusTab))
    .filter((c) => (!onlyProblems ? true : channelSeverity(c, pay, modes) <= 1))
    .sort((a, b) => channelSeverity(a, pay, modes) - channelSeverity(b, pay, modes) || a.name.localeCompare(b.name)),
  [typeFilter, statusTab, onlyProblems, pay, modes]);

  /* Turning off the last enabled payment method on an active channel silently
   * stops sales, so it is confirmed by naming the consequence. */
  const togglePay = (chId, code) => {
    const ch = SALES_CHANNELS.find((c) => c.id === chId);
    const enabled = Object.values(pay[chId]).filter((v) => v === "ON").length;
    if (pay[chId][code] === "ON" && enabled === 1 && ch.status === "ACTIVE") {
      setConfirm({ chId, code, name: ch.name });
      return;
    }
    setPay((p) => ({ ...p, [chId]: { ...p[chId], [code]: p[chId][code] === "ON" ? "OFF" : "ON" } }));
  };

  const toggleMode = (chId, code) =>
    setModes((m) => ({ ...m, [chId]: { ...m[chId], [code]: m[chId][code] === "ON" ? "OFF" : "ON" } }));

  const bulkColumn = (setter) => (code) => setter((s) => {
    const next = { ...s };
    Object.keys(next).forEach((chId) => {
      if (next[chId][code] !== "BLOCKED") next[chId] = { ...next[chId], [code]: "ON" };
    });
    return next;
  });

  const bulkRow = (setter) => (chId) => setter((s) => {
    const row = { ...s[chId] };
    Object.keys(row).forEach((k) => { if (row[k] !== "BLOCKED") row[k] = "ON"; });
    return { ...s, [chId]: row };
  });

  const listRows = rows.map((c) => {
    const sev = channelSeverity(c, pay, modes);
    const enabledPay = Object.values(pay[c.id]).filter((v) => v === "ON").length;
    const enabledModes = FULFILMENT_MODES.filter((m) => modes[c.id][m.code] === "ON");
    const inst = INSTALLATIONS.find((i) => i.id === c.installationId);
    const caption = [
      sev === 0 && !enabledPay ? "Active with no enabled payment method — an empty matrix means «sells nothing», not «sells everything»" : null,
      sev === 0 && !enabledModes.length ? "Active with no enabled fulfilment mode — nothing can be ordered through it" : null,
      sev === 1 ? "Active but bound to no branch — nothing to sell from" : null,
      inst && inst.connection !== "SUCCEEDED" ? `${inst.name}: ${inst.connection.toLowerCase()}${inst.evidence ? ` — ${inst.evidence}` : ""}` : null,
      c.status === "ARCHIVED" ? "Archived, never deleted — every order carries its channel forever" : null,
    ].filter(Boolean).join(" · ");

    return {
      id: c.id, severity: sev, caption,
      cells: {
        name: (
          <div style={{ minWidth: 0 }}>
            <div style={{ color: ink, overflowWrap: "anywhere" }}>{c.name}</div>
            <div className="q-caption" style={{ ...mono, color: inkSubtle }}>{c.code}</div>
          </div>
        ),
        type: <span className="q-caption" style={{ ...mono, color: inkMuted }}>{c.type}</span>,
        status: (
          <StatusPill tone={c.status === "ACTIVE" ? "active" : c.status === "INACTIVE" ? "pending" : "neutral"}>
            {c.status.toLowerCase()}
          </StatusPill>
        ),
        locations: <span className="q-tnum">{LOCATIONS.filter((l) => l.channelIds.includes(c.id)).length}</span>,
        payments: <span className="q-tnum">{enabledPay}</span>,
        modes: (
          <span className="q-caption" style={{ color: inkMuted }}>
            {enabledModes.length ? enabledModes.map((m) => m.label).join(", ") : "none"}
          </span>
        ),
        pricing: (
          <span className="q-caption" style={{ color: inkMuted }}>
            {c.externallyPriced ? "partner sets price"
              : c.pricePlane ? SALES_CHANNELS.find((x) => x.id === c.pricePlane)?.name : "own prices"}
          </span>
        ),
        install: inst
          ? <StatusPill tone={inst.connection === "SUCCEEDED" ? "healthy" : inst.connection === "FAILED" ? "failed" : "pending"}>{inst.name}</StatusPill>
          : <span className="q-caption" style={{ color: inkSubtle }}>—</span>,
      },
    };
  });

  const byStatus = (s) => SALES_CHANNELS.filter((c) => c.status === s).length;

  return (
    <>
      <Card style={{ marginBottom: 24 }}>
        <div className="q-body-sm" style={{ color: inkMuted, maxWidth: 780 }}>
          Channels are tenant-owned, not brand property — ADR 0036 states it and enforces it by keying
          the child tables on <span style={mono}>(tenant_id, id)</span>, so the brand picker above does
          not change this screen. Dine-in is a fulfilment mode and never a channel type: a QR-table
          order and a waiter-entered order are both DINE_IN arriving through different channels.
        </div>
      </Card>

      <Tabs
        tabs={[
          { id: "all", label: "All", count: SALES_CHANNELS.length },
          { id: "ACTIVE", label: "Active", count: byStatus("ACTIVE") },
          { id: "INACTIVE", label: "Inactive", count: byStatus("INACTIVE") },
          { id: "ARCHIVED", label: "Archived", count: byStatus("ARCHIVED") },
        ]}
        active={statusTab} onChange={setStatusTab}
      />

      <FilterBar>
        <Select
          label="Type" value={typeFilter} onChange={setTypeFilter}
          options={[
            { value: "any", label: "Any" },
            ...[...new Set(SALES_CHANNELS.map((c) => c.type))].map((t) => ({
              value: t, label: `${t} (${SALES_CHANNELS.filter((c) => c.type === t).length})`,
            })),
          ]}
        />
        <label className="q-body-sm" style={{ display: "inline-flex", gap: 8, alignItems: "center", color: ink, cursor: "pointer" }}>
          <input type="checkbox" checked={onlyProblems} onChange={(e) => setOnlyProblems(e.target.checked)} style={{ accentColor: blue }} />
          Only channels with problems
        </label>
        <Button size="sm" style={{ marginLeft: "auto" }}>Add a channel</Button>
      </FilterBar>

      <SeverityList columns={CHANNEL_COLUMNS} rows={listRows} />

      <div className="q-caption" style={{ color: inkMuted, margin: "12px 0 24px", maxWidth: 780 }}>
        No delete. The database has no delete path for a channel that has ever sold anything — orders
        snapshot <span style={mono}>channel_code_snapshot</span> — and offering a control that always
        fails is worse than not offering it. Channels archive.
      </div>

      <MatrixGrid
        title="Payment methods × channel"
        note="Columns are the code-owned provisional set. Today tenant.channel_payment_methods.payment_method_code is a bare varchar(32) with no foreign key, so a channel can enable a payment method that names nothing — ADR 0038 owns the registry that closes it, and 10.6 is where it will live."
        columns={PAYMENT_METHOD_COLUMNS} channels={SALES_CHANNELS} values={pay}
        onToggle={togglePay} onBulkColumn={bulkColumn(setPay)} onBulkRow={bulkRow(setPay)}
      />

      <MatrixGrid
        title="Fulfilment modes × channel"
        note="tenant.channel_fulfillment_modes.enabled. Two matrices, not four: item suppression belongs to the menu editor where the person choosing what to hide is already standing, and price per channel is a price book, for which a tick-box grid is the wrong control."
        columns={FULFILMENT_MODES} channels={SALES_CHANNELS} values={modes}
        onToggle={toggleMode} onBulkColumn={bulkColumn(setModes)} onBulkRow={bulkRow(setModes)}
      />

      {confirm ? (
        <Drawer title="Turn off the last payment method?" onClose={() => setConfirm(null)}>
          <div className="q-body" style={{ color: ink, marginBottom: 16 }}>
            «{confirm.name}» would have no payment methods left. Placing an order through it becomes
            impossible, and nothing on the storefront says why.
          </div>
          <div style={{ display: "flex", gap: 8 }}>
            <Button
              variant="danger"
              onClick={() => {
                setPay((p) => ({ ...p, [confirm.chId]: { ...p[confirm.chId], [confirm.code]: "OFF" } }));
                setConfirm(null);
              }}
            >
              Turn it off anyway
            </Button>
            <Button variant="ghost" onClick={() => setConfirm(null)}>Keep it enabled</Button>
          </div>
        </Drawer>
      ) : null}
    </>
  );
}

/* ── screen: order policy ──────────────────────────────────────────────────*/

const POLICY_LABELS = {
  mode: "Acceptance mode", approvalChannel: "Who confirms",
  approvalTimeoutSeconds: "Confirmation timeout", timeoutAction: "On timeout",
  rejectionReasonRequired: "Rejection reason required",
  notifyCustomerWhilePending: "Tell the customer while pending",
};

const policyValue = (k, v) =>
  k === "approvalTimeoutSeconds" ? `${v / 60} min` : typeof v === "boolean" ? (v ? "Yes" : "No") : v;

/** A policy, not a setting — a versioned document, edited as a draft and
 *  activated through a diff. The screen must never blur the two mechanisms. */
function PolicyCard() {
  const [showDiff, setShowDiff] = useState(false);
  const p = ACCEPTANCE_POLICY;
  const changed = Object.keys(p.document).filter((k) => p.document[k] !== p.pending.document[k]);

  return (
    <Card style={{ marginBottom: 24 }}>
      <div className="q-subhead" style={{ color: ink }}>Order acceptance</div>
      <div className="q-caption" style={{ color: inkMuted, marginTop: 4, maxWidth: 700 }}>
        A versioned policy document, not a scalar setting. A version is never edited in place and
        never deleted, because orders pin acceptance_policy_id and _version and must still resolve
        years later.
      </div>

      <div style={{ marginTop: 16, border: `1px solid ${hairline}`, borderLeft: `3px solid ${ink}`, padding: 12 }}>
        <div className="q-body-sm" style={{ color: ink }}>
          Version {p.activeVersion} active · since {dt(p.activeSince)} · {p.activeBy}
        </div>
        <div className="q-caption" style={{ color: inkMuted, marginTop: 2 }}>
          «{p.activeReason}» · in force for {p.scopeLabel}
        </div>
      </div>

      {p.pending ? (
        <div style={{ marginTop: 8, borderLeft: "3px solid var(--q-warning)", background: "var(--q-warning-tint)", padding: 12 }}>
          <div className="q-body-sm" style={{ color: "var(--q-warning-text)" }}>
            Version {p.pending.version} awaiting approval · requested by {p.pending.requestedBy} at {dt(p.pending.requestedAt)}
          </div>
          <div className="q-caption" style={{ color: "var(--q-warning-text)", marginTop: 2 }}>
            «{p.pending.reason}» · {p.pending.approverHint}
          </div>
          <div style={{ marginTop: 8 }}>
            <Button size="sm" variant="tertiary" onClick={() => setShowDiff(!showDiff)}>
              {showDiff ? "Hide the diff" : `Review the diff (${changed.length} fields)`}
            </Button>
          </div>
        </div>
      ) : null}

      {/* The record's invariants as affordances, not errors: AUTO_CONFIRM
          collapses the other four fields rather than letting them be filled in
          and then rejecting the save. */}
      <div style={{ marginTop: 16 }}>
        {Object.keys(p.document)
          .filter((k) => !(p.document.mode === "AUTO_CONFIRM" && k !== "mode"))
          .map((k) => {
            const diff = showDiff && changed.includes(k);
            return (
              <div key={k} style={{ display: "flex", gap: 16, alignItems: "baseline", padding: "10px 0", borderBottom: `1px solid ${hairline}` }}>
                <span className="q-body-sm" style={{ color: ink, flex: "1 1 auto" }}>{POLICY_LABELS[k]}</span>
                <span
                  className={diff ? "q-body-sm" : "q-emphasis"}
                  style={{ color: diff ? inkSubtle : ink, textDecoration: diff ? "line-through" : "none" }}
                >
                  {policyValue(k, p.document[k])}
                </span>
                {diff ? (
                  <span className="q-emphasis" style={{ color: "var(--q-warning-text)" }}>→ {policyValue(k, p.pending.document[k])}</span>
                ) : null}
              </div>
            );
          })}
      </div>

      <div style={{ display: "flex", gap: 8, marginTop: 16 }}>
        <Button size="sm">Edit as a draft</Button>
        <Button size="sm" variant="ghost">Version history ({p.history.length})</Button>
      </div>

      <div className="q-caption" style={{ color: inkMuted, marginTop: 16, maxWidth: 700 }}>
        Activation is confirmed by naming the object and the blast radius — «new orders at Chilonzor
        will be confirmed by hand; orders already accepted do not change». Above the ADR 0027 risk
        threshold it becomes an approval request instead, which is what version 8 is: the requester
        may never approve their own.
      </div>

      <div style={{ marginTop: 16, borderTop: `1px solid ${hairline}`, paddingTop: 12 }}>
        <div className="q-caption" style={{ color: "var(--q-warning-text)" }}>
          Two fields the document does not carry and every tenant wants:
        </div>
        {p.missing.map((m) => (
          <div key={m.field} className="q-caption" style={{ color: inkMuted, marginTop: 6 }}>
            <span style={mono}>{m.field}</span> — {m.label}. {m.why} Owner: {m.adr}.
          </div>
        ))}
      </div>
    </Card>
  );
}

function OrderPolicy({ scope, onSwitchScope }) {
  const editLevel = scope.locationId ? "LOCATION" : "BRAND";
  const pageKeys = CONFIG_KEYS.filter((k) => k.screen === "order-policy");
  const cardKeys = (card) => pageKeys.filter((k) => k.card === card);

  /* The same fixtures the order board reads. A threshold on this page and a
   * lateness overlay on that one must never be able to disagree. */
  const late = ORDERS.filter((o) => !["DELIVERED", "CANCELLED"].includes(o.status) && o.lateBy);
  const worst = Math.max(0, ...late.map((o) => o.lateBy));

  const fieldProps = {
    editLevel, brandId: scope.brandId, locationId: scope.locationId,
    brandName: scope.brandName, locationName: scope.locationName, onSwitchScope,
  };

  const Section = ({ title, blurb, children }) => (
    <Card style={{ marginBottom: 24 }}>
      <div className="q-subhead" style={{ color: ink }}>{title}</div>
      {blurb ? <div className="q-caption" style={{ color: inkMuted, marginTop: 4, maxWidth: 700 }}>{blurb}</div> : null}
      <div style={{ marginTop: 12 }}>{children}</div>
    </Card>
  );

  const echoRow = (label, value, chip) => (
    <div style={{ display: "flex", gap: 16, alignItems: "baseline", padding: "12px 0", borderTop: `1px solid ${hairline}` }}>
      <span className="q-body-sm" style={{ color: ink, flex: 1 }}>{label}</span>
      <span className="q-body-sm" style={{ color: inkMuted }}>{value}</span>
      <Chip tone="locked">{chip}</Chip>
    </div>
  );

  return (
    <div style={{ display: "grid", gridTemplateColumns: "minmax(0, 1fr) 300px", gap: 24, alignItems: "start" }}>
      <div style={{ minWidth: 0 }}>
        <PolicyCard />

        <Section
          title="Timings and SLA"
          blurb={`Lateness is a computed overlay on a status and never a status of its own. This card sets the threshold; nothing here creates a state. Right now ${late.length} open orders carry a lateness overlay and the worst is ${worst} min over.`}
        >
          {cardKeys("sla").map((k) => <InheritedField key={k.code} keyDef={k} {...fieldProps} />)}
          <div className="q-caption" style={{ color: inkMuted, marginTop: 12, maxWidth: 700 }}>
            The late threshold and the average and maximum order times are settable at branch level on
            purpose. A mall food-court branch and a highway branch do not have the same honest promise,
            and forcing one number makes the whole late indicator noise.
          </div>
        </Section>

        <Section
          title="Automation"
          blurb="Auto-dispatch, provider cascade, batching radius and unpaid-order timeout live in Delivery 3.8 as one provider-agnostic rule engine, not as five near-identical copies inside five provider pages — which is why Delever cannot express provider fallback at all."
        >
          <div style={{ display: "flex", gap: 16, alignItems: "baseline", padding: "10px 0", borderTop: `1px solid ${hairline}` }}>
            <span className="q-body-sm" style={{ color: ink, flex: 1 }}>Rules in force for this scope</span>
            <span className="q-body-sm" style={{ color: inkMuted }}>3 rules · auto-dispatch on from 19:00</span>
            <Button size="sm" variant="ghost">Open dispatch rules</Button>
          </div>
        </Section>

        <Section title="Conditions">
          {cardKeys("conditions").map((k) => <InheritedField key={k.code} keyDef={k} {...fieldProps} />)}
          {echoRow(
            "Concurrent order limit",
            scope.locationId ? (LOCATIONS.find((l) => l.id === scope.locationId)?.maxConcurrent ?? "no limit") : "per branch",
            "Edited on the branch",
          )}
          <div className="q-caption" style={{ color: "var(--q-warning-text)", marginTop: 12 }}>
            «Who accepts first — courier or branch» is deliberately absent. It is design-forcing, not
            configuration: it reorders the lifecycle, and ADR 0019's state machine must carry it as a
            parameter or refuse it. It will not appear here as a dropdown.
          </div>
        </Section>

        <Section
          title="Operator-entered orders"
          blurb="What a call-centre operator may do in Orders 1.3. Operator entry is its own sales channel — CALL_CENTRE is a system_type — so its payment methods and fulfilment modes are edited in the channel matrix and only echoed here."
        >
          {cardKeys("operator").map((k) => <InheritedField key={k.code} keyDef={k} {...fieldProps} />)}
          {echoRow(
            "Payment methods available",
            Object.entries(CHANNEL_PAYMENTS["ch-call"]).filter(([, v]) => v === "ON").map(([c]) => c).join(", "),
            "Edited on the channel",
          )}
        </Section>
      </div>

      {/* Resolved-for panel. The same data as the per-field chips, collected —
          and the thing a manager screenshots and sends to a franchisee. */}
      <div style={{ position: "sticky", top: 72 }}>
        <Card style={{ padding: 16 }}>
          <div className="q-caption" style={{ color: inkSubtle }}>In force for</div>
          <div className="q-body-sm" style={{ color: ink, margin: "2px 0 12px" }}>
            {scope.locationName ? `Branch «${scope.locationName}»` : `Brand «${scope.brandName}» — all branches`}
          </div>
          {pageKeys.map((k) => {
            const t = trace(k, scope.brandId, scope.locationId);
            return (
              <div key={k.code} style={{ padding: "8px 0", borderTop: `1px solid ${hairline}` }}>
                <div className="q-caption" style={{ color: inkMuted }}>{k.label}</div>
                <div style={{ display: "flex", gap: 8, alignItems: "baseline" }}>
                  <span className="q-body-sm" style={{ color: ink }}>
                    {k.type === "readonly" ? k.readonlyValue : t.cleared ? "cleared" : fmt(k, t.value)}
                  </span>
                  <span className="q-caption" style={{ color: inkSubtle, marginLeft: "auto" }}>
                    {t.winner ? originLabel(t.winner.level, scope.brandName, scope.locationName) : "—"}
                  </span>
                </div>
              </div>
            );
          })}
        </Card>
      </div>
    </div>
  );
}

/* ── the shell ─────────────────────────────────────────────────────────────*/

/** The scope bar. One sticky row, always the first thing read, and the level
 *  readout is text rather than a control on purpose: it is what an operator
 *  reads to avoid the worst settings mistake there is — changing something for
 *  thirty branches while believing they changed it for one. */
function ScopeBar({ scope, setScope, screen }) {
  const tenantScoped = screen === "channels";
  const level = tenantScoped ? "COMPANY" : scope.locationId ? "BRANCH" : "BRAND";

  return (
    <div
      style={{
        display: "flex", gap: 16, alignItems: "center", flexWrap: "wrap", padding: 12,
        background: canvas, border: `1px solid ${hairline}`,
        position: "sticky", top: 48, zIndex: 5, marginBottom: 24,
      }}
    >
      {BRANDS.length > 1 ? (
        <Select
          label="Brand" value={scope.brandId}
          onChange={(v) => setScope({ brandId: v, locationId: null })}
          options={BRANDS.map((b) => ({ value: b.id, label: b.name }))}
        />
      ) : null}

      {tenantScoped ? (
        <Chip tone="locked">Channels are set for the whole company</Chip>
      ) : (
        <Select
          label="Branch" value={scope.locationId || "all"}
          onChange={(v) => setScope({ ...scope, locationId: v === "all" ? null : v })}
          options={[
            { value: "all", label: "All branches" },
            ...LOCATIONS.filter((l) => l.brandId === scope.brandId).map((l) => ({
              value: l.id,
              label: l.status === "ACTIVE" ? l.name : `${l.name} — ${l.status.toLowerCase()}`,
            })),
          ]}
        />
      )}

      <div style={{ marginLeft: "auto", display: "flex", gap: 8, alignItems: "center" }}>
        <span className="q-caption" style={{ color: inkSubtle }}>Editing level</span>
        <span className="q-emphasis" style={{ color: ink }}>{level}</span>
      </div>
    </div>
  );
}

/* A grouped rail, not an alphabetical list. The headings are nouns a restaurant
 * manager uses, which is the whole difference between this and a junk drawer
 * with square corners. The dot marks a screen this prototype did not build. */
function Rail({ screen, go }) {
  const item = (id, label, built, on) => (
    <button
      key={id} type="button" onClick={() => go(id)} className={on ? "q-emphasis" : "q-body-sm"}
      style={{
        display: "flex", alignItems: "center", gap: 6, width: "100%", textAlign: "left",
        padding: "6px 12px", background: on ? surface1 : "transparent", border: "none",
        borderLeft: on ? `3px solid ${ink}` : "3px solid transparent",
        color: built ? ink : inkMuted, cursor: "pointer",
      }}
    >
      {label}
      {!built ? (
        <span
          aria-label="not prototyped"
          style={{ width: 4, height: 4, borderRadius: "50%", background: inkSubtle, marginLeft: "auto", flexShrink: 0 }}
        />
      ) : null}
    </button>
  );

  return (
    <nav style={{ width: 200, flexShrink: 0, position: "sticky", top: 72 }}>
      <div style={{ marginBottom: 8 }}>{item("home", "Overview", true, screen === "home")}</div>
      {SETTINGS_NAV.map((g) => (
        <div key={g.id} style={{ marginBottom: 12 }}>
          <div className="q-caption" style={{ color: inkSubtle, padding: "0 12px 4px 15px" }}>{g.label}</div>
          {g.screens.map((s) => item(s.id, s.label, s.built, s.id === screen))}
        </div>
      ))}
    </nav>
  );
}

export default function Settings({ group, setGroup }) {
  const screen = group || "home";
  const go = setGroup || (() => {});

  /* The spec puts the scope in the URL query so it survives a drill-down and a
   * paste into a chat. This prototype has no router, so it is held here and the
   * two pickers are its only writers. */
  const [scope, setScope] = useState({ brandId: "br-osh", locationId: "loc-chilonzor" });

  const fullScope = {
    ...scope,
    brandName: BRANDS.find((b) => b.id === scope.brandId)?.name,
    locationName: LOCATIONS.find((l) => l.id === scope.locationId)?.name,
  };

  const current = SETTINGS_NAV.flatMap((g) => g.screens).find((s) => s.id === screen);

  const body =
    screen === "home" ? <Home go={go} scope={fullScope} />
      : screen === "locations" ? <Locations scope={fullScope} />
        : screen === "channels" ? <Channels />
          : screen === "order-policy" ? (
            <OrderPolicy
              scope={fullScope}
              onSwitchScope={(level) => level === "BRAND" && setScope({ ...scope, locationId: null })}
            />
          ) : current ? <NotPrototyped screen={current} />
            : <EmptyState title="Unknown settings screen" />;

  return (
    <div style={{ display: "flex", gap: 24, alignItems: "flex-start", minWidth: 0 }}>
      <Rail screen={screen} go={go} />

      <div style={{ flex: 1, minWidth: 0 }}>
        <SectionHeader
          title={screen === "home" ? "Settings" : current?.label || "Settings"}
          description={screen === "home"
            ? "Registries and policies the rest of the console resolves against. Promotions, menus, zones and courier rates are authoring surfaces and live where they are authored."
            : current?.purpose}
          right={screen !== "home" ? <Button variant="ghost" onClick={() => go("home")}>Back to overview</Button> : null}
        />
        <ScopeBar scope={fullScope} setScope={setScope} screen={screen} />
        {body}
      </div>
    </div>
  );
}

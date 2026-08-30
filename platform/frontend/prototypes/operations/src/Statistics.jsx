/* Statistics — every number a manager reads about the past.
 *
 * The reader is a branch manager between services, standing up, with a phone
 * ringing. Four decisions follow from that and shape everything below:
 *
 * 1. **The overview is one scroll, not eight tabs.** Delever splits its
 *    dashboard across eight; a tab is a filing cabinet and this screen is a
 *    glance. Tabs also destroy the only thing that makes a dashboard useful,
 *    which is that two unrelated numbers are visible at the same moment —
 *    cancellations rising *while* prep time rises is a different story from
 *    either alone.
 *
 * 2. **Every number carries its provenance.** The `?` beside a figure opens the
 *    metric definition, its source table, whether that table exists, and
 *    whether finance has signed the definition. A provisional metric renders an
 *    amber rule beside its number. A figure whose source you cannot see is a
 *    figure nobody trusts, and two surfaces disagreeing about average check is
 *    unrecoverable — the merchant starts checking every number by hand.
 *
 * 3. **Unbuilt is a state, not an absence.** Where the backend has no data the
 *    view renders its real chrome with the blocking ADR named, rather than a
 *    plausible chart over invented numbers. A missing report must be legibly
 *    unbuilt, not apparently broken.
 *
 * 4. **Lateness is an overlay on a status, never a status.** It is derived from
 *    a stored promise and the current status, never written down: a `late`
 *    column would need a job to maintain it, would be wrong between runs of
 *    that job, and would let two readers disagree about one order.
 *
 * Charts are divs. A chart library in a prototype buys a tooltip nobody asked
 * for and a rounded corner somebody has to remove.
 */

import { useEffect, useMemo, useState } from "react";
import {
  Button, Card, Drawer, SectionHeader, StatusPill,
  uzs, dt, day, ink, inkMuted, inkSubtle, hairline, canvas, surface1, blue,
} from "./components";
import {
  REPORT_NOW, FRESHNESS, TENANT, METRICS, TILES, GAUGES, SLA_BUCKETS,
  SLA_MEDIAN_MINUTES, CHANNEL_MIX, FULFILMENT_MIX, FUNNEL, DROPOFFS,
  CANCELLATION_REASONS, BRANCH_ROWS, ORDER_FACTS, LATE_SUMMARY,
  AGGREGATOR_CHANNELS, LIVENESS, AGGREGATOR_ORDERS, EXPORT_QUOTA, EXPORTS,
  NOT_PROTOTYPED,
} from "./Statistics.data";

/* ── local helpers ─────────────────────────────────────────────────────────*/

const AMBER = "var(--q-warning)";
const AMBER_TINT = "var(--q-warning-tint)";
const AMBER_TEXT = "var(--q-warning-text)";
const RED = "var(--q-error)";
const RED_TINT = "var(--q-error-tint)";
const RED_TEXT = "var(--q-error-text)";

const nf = (n) => String(n).replace(/\B(?=(\d{3})+(?!\d))/g, " ");
const pct1 = (x) => `${x.toFixed(1).replace(".", ",")}%`;
const hhmm = (iso) => dt(iso).slice(6);

/* Durations: mm:ss under an hour, h:mm above. A kitchen argues in minutes and
 * seconds; a stuck order is argued about in hours. */
const dur = (sec) => {
  if (sec == null) return "—";
  const m = Math.floor(sec / 60);
  if (m < 60) return `${m}:${String(sec % 60).padStart(2, "0")}`;
  return `${Math.floor(m / 60)}:${String(m % 60).padStart(2, "0")} h`;
};

const hoursSince = (iso) => (new Date(REPORT_NOW) - new Date(iso)) / 3_600_000;

/* Three channels at once, with strict precedence — tint, a 3px left rule, and a
 * caption carrying the actual reason text. Normal rows carry a transparent rule
 * so nothing shifts by 3px when a row goes bad. */
const SEV = {
  incident: { tint: RED_TINT, rule: RED, text: RED_TEXT },
  warning: { tint: AMBER_TINT, rule: AMBER, text: AMBER_TEXT },
  none: { tint: "transparent", rule: "transparent", text: inkMuted },
};

const loc = (id) => TENANT.locations.find((l) => l.id === id);
const chan = (id) => TENANT.channels.find((c) => c.id === id);
const entity = (id) => TENANT.legalEntities.find((e) => e.id === id);

/* ── small primitives ──────────────────────────────────────────────────────*/

function Block({ title, description, right, children, id }) {
  return (
    <section id={id} style={{ marginTop: 32 }}>
      <div style={{ display: "flex", alignItems: "flex-end", gap: 16, marginBottom: 12 }}>
        <div style={{ minWidth: 0 }}>
          <h2 className="q-subhead" style={{ margin: 0, color: ink }}>{title}</h2>
          {description ? (
            <p className="q-body-sm" style={{ margin: "2px 0 0", color: inkMuted, maxWidth: 720 }}>{description}</p>
          ) : null}
        </div>
        {right ? <div style={{ marginLeft: "auto", flexShrink: 0 }}>{right}</div> : null}
      </div>
      {children}
    </section>
  );
}

/* The provenance affordance. A button and not a tooltip: a tooltip is not
 * keyboard reachable, and this is the control a manager is pointed at when they
 * say the average check is wrong. */
function Why({ metric, onOpen }) {
  if (!metric) return null;
  return (
    <button
      type="button"
      onClick={(e) => { e.stopPropagation(); onOpen(metric); }}
      title={`Definition of ${metric}`}
      className="q-caption"
      style={{
        width: 18, height: 18, lineHeight: "16px", padding: 0, flexShrink: 0,
        background: "transparent", color: inkMuted, cursor: "pointer",
        border: `1px solid ${hairline}`, borderRadius: 0,
      }}
    >
      ?
    </button>
  );
}

/* An unbuilt surface keeps its frame and states the blocking decision. */
function Unbuilt({ label, children }) {
  return (
    <div style={{ border: `1px solid ${hairline}`, background: surface1, padding: 16, minWidth: 0 }}>
      <div className="q-emphasis" style={{ color: inkMuted }}>{label}</div>
      <div className="q-caption" style={{ color: inkSubtle, marginTop: 6, lineHeight: 1.5 }}>{children}</div>
    </div>
  );
}

function Band({ tone, title, children, action }) {
  const c = tone === "error" ? { bg: RED_TINT, rule: RED, fg: RED_TEXT } : { bg: AMBER_TINT, rule: AMBER, fg: AMBER_TEXT };
  return (
    <div style={{ display: "flex", gap: 12, alignItems: "flex-start", background: c.bg, borderLeft: `3px solid ${c.rule}`, padding: "10px 14px", marginBottom: 12 }}>
      <div style={{ minWidth: 0 }}>
        <div className="q-emphasis" style={{ color: c.fg }}>{title}</div>
        {children ? <div className="q-caption" style={{ color: c.fg, marginTop: 2, lineHeight: 1.5 }}>{children}</div> : null}
      </div>
      {action ? <div style={{ marginLeft: "auto", flexShrink: 0 }}>{action}</div> : null}
    </div>
  );
}

function Sparkline({ values }) {
  const peak = Math.max(...values, 1);
  return (
    <div style={{ display: "flex", alignItems: "flex-end", gap: 1, height: 24, marginTop: 10 }}>
      {values.map((v, i) => (
        <div
          key={i}
          title={`${String(i).padStart(2, "0")}:00 — ${v}`}
          style={{
            flex: 1, height: `${Math.max((v / peak) * 100, v ? 6 : 2)}%`,
            background: v ? (i === 19 ? ink : "var(--q-surface-2)") : "var(--q-surface-1)",
          }}
        />
      ))}
    </div>
  );
}

function Delta({ pct, worseWhenUp }) {
  if (pct == null) {
    return <span className="q-caption" style={{ color: inkSubtle }}>no comparison — baseline undefined</span>;
  }
  const up = pct > 0;
  const bad = worseWhenUp ? up : !up;
  return (
    <span className="q-caption q-tnum" style={{ color: bad ? RED_TEXT : "var(--q-success-text)" }}>
      {up ? "+" : "−"}{Math.abs(pct).toFixed(1).replace(".", ",")}% vs same weekday last week
    </span>
  );
}

/* One table for every list on the screen. Severity is a row property, never a
 * column, so a row that needs a human reads the same on all four tabs. */
function SevTable({ columns, rows, onRowClick, totals }) {
  return (
    <div style={{ border: `1px solid ${hairline}`, background: canvas, overflowX: "auto" }}>
      <table style={{ width: "100%", borderCollapse: "collapse" }}>
        <thead>
          <tr>
            {columns.map((c) => (
              <th key={c.key} className="q-caption"
                style={{
                  textAlign: c.align || "left", padding: "10px 16px", background: surface1,
                  color: inkMuted, fontWeight: 600, borderBottom: `1px solid ${hairline}`,
                  whiteSpace: "nowrap", width: c.width,
                }}>
                <span style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
                  {c.label}{c.why}
                </span>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => {
            const sev = SEV[r._sev || "none"];
            return (
              <tr key={r._id}
                onClick={onRowClick ? () => onRowClick(r) : undefined}
                style={{
                  cursor: onRowClick ? "pointer" : "default",
                  background: r._sev ? sev.tint : canvas,
                  borderBottom: `1px solid ${hairline}`,
                }}
                onMouseEnter={(e) => { if (!r._sev) e.currentTarget.style.background = surface1; }}
                onMouseLeave={(e) => { if (!r._sev) e.currentTarget.style.background = canvas; }}
              >
                {columns.map((c, i) => (
                  <td key={c.key} className={c.align === "right" ? "q-body-sm q-tnum" : "q-body-sm"}
                    style={{
                      padding: "10px 16px", textAlign: c.align || "left", color: ink,
                      verticalAlign: "top", borderLeft: i === 0 ? `3px solid ${sev.rule}` : undefined,
                      fontFamily: c.mono ? "var(--q-font-mono)" : undefined,
                    }}>
                    {c.render(r)}
                    {i === 0 && r._reason ? (
                      <div className="q-caption" style={{ color: sev.text, marginTop: 3 }}>{r._reason}</div>
                    ) : null}
                  </td>
                ))}
              </tr>
            );
          })}
        </tbody>
        {totals ? (
          <tfoot>
            <tr style={{ background: surface1, borderTop: `1px solid ${hairline}` }}>
              {columns.map((c, i) => (
                <td key={c.key} className={c.align === "right" ? "q-emphasis q-tnum" : "q-emphasis"}
                  style={{ padding: "10px 16px", textAlign: c.align || "left", color: ink, borderLeft: i === 0 ? "3px solid transparent" : undefined }}>
                  {totals[c.key] ?? ""}
                </td>
              ))}
            </tr>
          </tfoot>
        ) : null}
      </table>
    </div>
  );
}

/* ── the filter bar ────────────────────────────────────────────────────────
 * Two rows with deliberately different weight, because they are two different
 * axes and not one long wrap. Row 1 is the period, dark-filled. Row 2 is the
 * slice, amber-outlined and smaller. Blue is spent on the primary action only.
 */

const PERIODS = [
  { id: "today", label: "Today", days: 1 },
  { id: "yesterday", label: "Yesterday", days: 1 },
  { id: "7d", label: "7 days", days: 7 },
  { id: "month", label: "Month", days: 31 },
  { id: "custom", label: "Period…", days: 14 },
];

function Pill({ on, onClick, children, disabled, title }) {
  return (
    <button type="button" onClick={onClick} disabled={disabled} title={title} className="q-body-sm"
      style={{
        height: 32, padding: "0 12px", borderRadius: 0, cursor: disabled ? "not-allowed" : "pointer",
        background: on ? ink : canvas, color: on ? "var(--q-inverse-ink)" : disabled ? inkSubtle : ink,
        border: `1px solid ${on ? ink : hairline}`, opacity: disabled ? 0.5 : 1, whiteSpace: "nowrap",
      }}>
      {children}
    </button>
  );
}

function Seg({ options, value, onChange, axis = "period", dirty }) {
  return (
    <div style={{ display: "inline-flex", border: `1px solid ${dirty ? AMBER : hairline}` }}>
      {options.map((o) => {
        const on = o.id === value;
        return (
          <button key={o.id} type="button" disabled={o.disabled} title={o.title}
            onClick={() => onChange(o.id)} className="q-body-sm"
            style={{
              height: 30, padding: "0 10px", border: "none", borderRadius: 0,
              borderRight: `1px solid ${hairline}`, cursor: o.disabled ? "not-allowed" : "pointer",
              background: on ? (axis === "period" ? ink : AMBER_TINT) : canvas,
              color: on ? (axis === "period" ? "var(--q-inverse-ink)" : AMBER_TEXT) : o.disabled ? inkSubtle : inkMuted,
            }}>
            {o.label}
          </button>
        );
      })}
    </div>
  );
}

/* A control in a non-default state carries its axis colour on the hairline, so
 * "this view is filtered" is visible without reading the values. The spec asks
 * for 2px there; the console's border rule is one hairline everywhere, so the
 * signal moves to hue rather than to weight. */
function SliceSelect({ label, value, onChange, children, dirty, locked, lockNote }) {
  return (
    <label style={{ display: "inline-flex", alignItems: "center", gap: 6 }} title={locked ? lockNote : undefined}>
      <span className="q-caption" style={{ color: inkSubtle }}>{label}</span>
      <select value={value} disabled={locked} onChange={(e) => onChange(e.target.value)} className="q-body-sm"
        style={{
          height: 30, padding: "0 6px", background: locked ? surface1 : canvas,
          color: locked ? inkSubtle : ink, borderRadius: 0,
          border: `1px solid ${dirty ? AMBER : hairline}`,
          cursor: locked ? "not-allowed" : "pointer",
        }}>
        {children}
      </select>
    </label>
  );
}

function FilterBar({ range, setRange, gran, setGran, branch, setBranch, channel, setChannel,
  mode, setMode, ent, setEnt, onProvenance }) {
  const days = PERIODS.find((p) => p.id === range)?.days ?? 1;
  const visibleChannels = TENANT.channels.filter((c) => !c.archived || c.ordersInPeriod > 0);

  return (
    <div style={{ border: `1px solid ${hairline}`, background: canvas, position: "sticky", top: 48, zIndex: 9 }}>
      {/* row 1 — the period axis */}
      <div style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap", padding: "10px 12px" }}>
        {PERIODS.map((p) => (
          <Pill key={p.id} on={range === p.id} onClick={() => setRange(p.id)}>{p.label}</Pill>
        ))}
        {range === "custom" ? (
          <span style={{ display: "inline-flex", gap: 6, alignItems: "center" }}>
            <input type="date" defaultValue="2026-08-08" className="q-body-sm"
              style={{ height: 32, border: `1px solid ${hairline}`, borderRadius: 0, padding: "0 6px", background: canvas, color: ink }} />
            <span className="q-caption" style={{ color: inkSubtle }}>—</span>
            <input type="date" defaultValue="2026-08-21" className="q-body-sm"
              style={{ height: 32, border: `1px solid ${hairline}`, borderRadius: 0, padding: "0 6px", background: canvas, color: ink }} />
          </span>
        ) : null}

        <span style={{ width: 1, height: 24, background: hairline, margin: "0 4px" }} />

        <Seg
          value={gran} onChange={setGran}
          options={[
            { id: "hour", label: "Hour", disabled: days > 3, title: days > 3 ? "Hourly buckets need a range of 3 days or less" : undefined },
            { id: "day", label: "Day" },
            { id: "week", label: "Week" },
            { id: "month", label: "Month" },
          ]}
        />

        <button type="button" onClick={() => onProvenance("business_day")} className="q-caption"
          style={{ height: 32, padding: "0 10px", background: surface1, color: inkMuted, border: `1px solid ${hairline}`, borderRadius: 0, cursor: "pointer", fontFamily: "var(--q-font-mono)" }}>
          Operating day {TENANT.businessDayStart}→{TENANT.businessDayStart}
        </button>

        <span style={{ marginLeft: "auto", display: "inline-flex", gap: 4 }}>
          <Pill onClick={() => {}} title="Shift the range back by its own length">←</Pill>
          <Pill onClick={() => {}} title="Shift the range forward by its own length">→</Pill>
        </span>
      </div>

      {/* row 2 — the slice axis */}
      <div style={{ display: "flex", gap: 12, alignItems: "center", flexWrap: "wrap", padding: "8px 12px", borderTop: `1px solid ${hairline}`, background: surface1 }}>
        <SliceSelect label="Branch" value={branch} onChange={setBranch} dirty={branch !== "all"}>
          <option value="all">All branches ({TENANT.locations.length})</option>
          {TENANT.locations.map((l) => (
            <option key={l.id} value={l.id}>{l.name}{l.state === "FORCE_CLOSED" ? " — force-closed" : ""}</option>
          ))}
        </SliceSelect>

        <SliceSelect label="Channel" value={channel} onChange={setChannel} dirty={channel !== "all"}>
          <option value="all">All channels ({visibleChannels.length})</option>
          <optgroup label="Own">
            {visibleChannels.filter((c) => c.systemType === "OWN").map((c) => (
              <option key={c.id} value={c.id}>{c.name}{c.archived ? " (archived)" : ""} — {c.ordersInPeriod}</option>
            ))}
          </optgroup>
          <optgroup label="Aggregators">
            {visibleChannels.filter((c) => c.systemType === "AGGREGATOR").map((c) => (
              <option key={c.id} value={c.id}>{c.name} — {c.ordersInPeriod}</option>
            ))}
          </optgroup>
        </SliceSelect>

        <Seg axis="slice" value={mode} onChange={setMode} dirty={mode !== "all"}
          options={[
            { id: "all", label: "All" }, { id: "DELIVERY", label: "Delivery" },
            { id: "PICKUP", label: "Pickup" }, { id: "DINE_IN", label: "Dine-in" },
          ]} />

        <SliceSelect label="Legal entity" value={ent} onChange={setEnt} dirty={ent !== "all"}>
          <option value="all">All entities ({TENANT.legalEntities.length})</option>
          {TENANT.legalEntities.map((e) => <option key={e.id} value={e.id}>{e.name}</option>)}
        </SliceSelect>

        <SliceSelect label="Payment type" value="locked" onChange={() => {}} locked
          lockNote="Arrives with payments (ADR 0013/0046)">
          <option value="locked">Locked — arrives with payments</option>
        </SliceSelect>

        <span className="q-caption" style={{ color: inkSubtle, marginLeft: "auto" }}>
          Counts are computed before the filter, so they do not collapse as you narrow
        </span>
      </div>
    </div>
  );
}

/* ── overview bands ────────────────────────────────────────────────────────*/

function Tile({ tile, ent, onProvenance }) {
  const def = METRICS[tile.metric];
  const provisional = def && !def.signed;
  const split = tile.byEntity && ent === "all" && TENANT.legalEntities.length > 1;
  const single = tile.byEntity && !split ? tile.byEntity.find((b) => b.entityId === ent) : null;

  return (
    <Card style={{ padding: 16, minWidth: 0 }}>
      <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
        <span className="q-caption" style={{ color: inkMuted }}>{tile.label}</span>
        <Why metric={tile.metric} onOpen={onProvenance} />
      </div>

      <div style={{ borderLeft: `3px solid ${provisional ? AMBER : "transparent"}`, paddingLeft: 8, marginTop: 6 }}>
        {split ? (
          <>
            {tile.byEntity.map((b) => (
              <div key={b.entityId} style={{ display: "flex", gap: 8, alignItems: "baseline", minWidth: 0 }}>
                <span className="q-caption" style={{ color: inkSubtle, minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                  {entity(b.entityId).name}
                </span>
                <span className="q-emphasis q-tnum" style={{ marginLeft: "auto", color: ink, fontFamily: "var(--q-font-mono)", whiteSpace: "nowrap" }}>
                  {uzs(b.value)}
                </span>
              </div>
            ))}
            <div className="q-caption" style={{ color: AMBER_TEXT, marginTop: 4 }}>
              No combined total — two legal entities (ADR 0038)
            </div>
          </>
        ) : (
          <div className="q-data-lg" style={{ color: ink, whiteSpace: "nowrap", fontFamily: tile.money ? "var(--q-font-mono)" : undefined }}>
            {/* NULL is not zero. A money tile with no row for the selected
                entity prints «—», never «0 so'm». */}
            {tile.money ? (single ? uzs(single.value) : "—") : nf(tile.value)}
          </div>
        )}
        {tile.secondary ? (
          <div className="q-caption" style={{ color: inkMuted, marginTop: 2 }}>{tile.secondary}</div>
        ) : null}
      </div>

      <div style={{ marginTop: 8 }}><Delta pct={tile.deltaPct} worseWhenUp={tile.deltaWorseWhenUp} /></div>
      <Sparkline values={tile.sparkline} />
      <div className="q-caption" style={{ color: inkSubtle, marginTop: 6 }}>→ {tile.linkTo}</div>
    </Card>
  );
}

/* A horizontal bullet gauge, not a dial. A dial is a decoration that occupies
 * the space where the target would go. */
function Gauge({ g, onProvenance }) {
  if (g.unbuilt) {
    return <Unbuilt label={g.label}>{g.unbuilt}</Unbuilt>;
  }
  const scale = Math.max(g.medianMinutes, g.targetMinutes) * 1.4;
  return (
    <Card style={{ padding: 16, minWidth: 0 }}>
      <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
        <span className="q-caption" style={{ color: inkMuted }}>{g.label}</span>
        <Why metric={g.metric} onOpen={onProvenance} />
        <span className="q-caption q-tnum" style={{ marginLeft: "auto", color: g.medianMinutes > g.targetMinutes ? AMBER_TEXT : inkMuted }}>
          median {g.medianMinutes} min · target {g.targetMinutes}
        </span>
      </div>
      <div style={{ position: "relative", height: 12, background: surface1, marginTop: 10 }}>
        <div style={{ position: "absolute", inset: 0, width: `${(g.medianMinutes / scale) * 100}%`, background: g.medianMinutes > g.targetMinutes ? AMBER : ink }} />
        <div style={{ position: "absolute", top: -3, bottom: -3, left: `${(g.targetMinutes / scale) * 100}%`, width: 2, background: ink }} />
      </div>
      <div className="q-caption" style={{ color: inkMuted, marginTop: 6 }}>
        mean {g.meanMinutes} min · within target {g.withinPct}%
      </div>
      <div className="q-caption" style={{ color: inkSubtle, marginTop: 4, lineHeight: 1.4 }}>{g.note}</div>
    </Card>
  );
}

function BucketStrip({ onProvenance }) {
  const total = SLA_BUCKETS.reduce((s, b) => s + b.count, 0);
  const shares = SLA_BUCKETS.map((b) => (b.count / total) * 100);
  const sum = shares.reduce((s, x) => s + x, 0);
  /* Six ramp steps keyed to the bucket, not to the value, so a manager reads the
   * shape of the row without reading the numbers. */
  const ramp = ["var(--q-success)", "var(--q-success)", AMBER, AMBER, RED, RED];
  const opacity = [1, 0.55, 0.55, 0.85, 0.6, 1];

  return (
    <Card style={{ padding: 16 }}>
      <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 10 }}>
        <span className="q-caption" style={{ color: inkMuted }}>Order time distribution</span>
        <Why metric="sla_bucket_set.v1" onOpen={onProvenance} />
        <span className="q-caption q-tnum" style={{ marginLeft: "auto", color: inkMuted }}>
          median {SLA_MEDIAN_MINUTES} min · {nf(total)} orders
        </span>
      </div>
      <div style={{ display: "flex", height: 28, border: `1px solid ${hairline}` }}>
        {SLA_BUCKETS.map((b, i) => (
          <div key={b.id} title={`${b.label} — ${b.count}`}
            style={{ width: `${shares[i]}%`, background: ramp[i], opacity: opacity[i], borderRight: i < 5 ? `1px solid ${canvas}` : undefined }} />
        ))}
      </div>
      <div style={{ display: "grid", gridTemplateColumns: "repeat(6, minmax(0, 1fr))", gap: 8, marginTop: 10 }}>
        {SLA_BUCKETS.map((b, i) => (
          <div key={b.id} style={{ minWidth: 0 }}>
            <div className="q-caption" style={{ color: inkMuted, whiteSpace: "nowrap" }}>{b.label}</div>
            <div className="q-emphasis q-tnum" style={{ color: ink }}>{b.count}</div>
            <div className="q-caption q-tnum" style={{ color: inkSubtle }}>{pct1(shares[i])}</div>
          </div>
        ))}
      </div>
      <div className="q-caption" style={{ color: inkMuted, marginTop: 10, lineHeight: 1.5 }}>
        Intervals: <span style={{ fontFamily: "var(--q-font-mono)" }}>sla_bucket_set.v1</span> — half-open,
        non-overlapping, exhaustive, so the shares sum to <span className="q-tnum">{pct1(sum)}</span>.
        Delever's documented branch buckets are «до 30, до 35, 30–40, 40–50, 35–60, свыше 60»: two adjacent
        columns count the same order twice and the percentages cannot add up to anything.
        Cut here over created→closed, which is built. The delivery-leg cut needs ADR 0042.
      </div>
    </Card>
  );
}

function MixBars({ measure, setMeasure, onProvenance }) {
  const rows = [...CHANNEL_MIX].sort((a, b) => (measure === "count" ? b.count - a.count : b.revenue - a.revenue));
  const total = rows.reduce((s, r) => s + (measure === "count" ? r.count : r.revenue), 0);
  const peak = Math.max(...rows.map((r) => (measure === "count" ? r.count : r.revenue)));

  return (
    <Card style={{ padding: 16, minWidth: 0 }}>
      <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 12 }}>
        <span className="q-caption" style={{ color: inkMuted }}>Channels</span>
        <Why metric="channel_mix.count.v1" onOpen={onProvenance} />
        <span style={{ marginLeft: "auto" }}>
          <Seg axis="slice" value={measure} onChange={setMeasure}
            options={[{ id: "count", label: "Count" }, { id: "revenue", label: "Revenue" }]} />
        </span>
      </div>
      {rows.map((r) => {
        const c = chan(r.channelId);
        const v = measure === "count" ? r.count : r.revenue;
        return (
          <div key={r.channelId} style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 6, minWidth: 0 }}>
            <span className="q-caption" style={{ color: ink, width: 116, flexShrink: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
              {c.name}{c.archived ? " ·arch" : ""}
            </span>
            <span style={{ flex: 1, height: 10, background: surface1, minWidth: 40 }}>
              <span style={{ display: "block", height: "100%", width: `${(v / peak) * 100}%`, background: c.systemType === "AGGREGATOR" ? "var(--q-surface-2)" : ink }} />
            </span>
            <span className="q-caption q-tnum" style={{ color: inkMuted, width: 96, textAlign: "right", flexShrink: 0, fontFamily: "var(--q-font-mono)" }}>
              {measure === "count" ? v : nf(v)}
            </span>
            <span className="q-caption q-tnum" style={{ color: inkSubtle, width: 44, textAlign: "right", flexShrink: 0 }}>
              {pct1((v / total) * 100)}
            </span>
          </div>
        );
      })}
      <div className="q-caption" style={{ color: inkSubtle, marginTop: 8 }}>
        Bars, not a pie: eight channels in a pie is unreadable and pies cannot be compared week to week.
      </div>
    </Card>
  );
}

function Funnel({ onDropoff }) {
  const top = FUNNEL[0].count;
  return (
    <Card style={{ padding: 16, minWidth: 0 }}>
      <div className="q-caption" style={{ color: inkMuted, marginBottom: 12 }}>
        Funnel by final status — the code-owned twelve from{" "}
        <span style={{ fontFamily: "var(--q-font-mono)" }}>ck_order_status</span>, never a tenant vocabulary
      </div>
      {FUNNEL.map((s) => {
        const drops = DROPOFFS.filter((d) => d.afterStage === s.id);
        return (
          <div key={s.id} style={{ marginBottom: 8 }}>
            <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
              <span className="q-caption" style={{ color: ink, width: 96, flexShrink: 0, fontFamily: "var(--q-font-mono)" }}>{s.label}</span>
              <span style={{ flex: 1, height: 14, background: surface1, minWidth: 40 }}>
                <span style={{ display: "block", height: "100%", width: `${(s.count / top) * 100}%`, background: ink }} />
              </span>
              <span className="q-emphasis q-tnum" style={{ color: ink, width: 44, textAlign: "right" }}>{s.count}</span>
            </div>
            {drops.length ? (
              <div style={{ display: "flex", gap: 6, flexWrap: "wrap", margin: "6px 0 0 106px" }}>
                {drops.map((d) => (
                  <button key={d.id} type="button" onClick={() => onDropoff(d)} className="q-caption"
                    style={{
                      padding: "2px 8px", background: RED_TINT, color: RED_TEXT, cursor: "pointer",
                      border: "none", borderLeft: `3px solid ${RED}`, borderRadius: 0,
                    }}>
                    {d.label} {d.count} →
                  </button>
                ))}
              </div>
            ) : null}
          </div>
        );
      })}
    </Card>
  );
}

/* ── the tabs ──────────────────────────────────────────────────────────────*/

function Overview({ ent, onProvenance, onDropoff }) {
  const [measure, setMeasure] = useState("count");
  const fulTotal = FULFILMENT_MIX.reduce((s, f) => s + f.count, 0);

  const branchRows = [...BRANCH_ROWS].sort((a, b) => b.revenue - a.revenue).map((r) => ({
    ...r, _id: r.id, _sev: r.severity, _reason: r.reason,
  }));

  return (
    <>
      <Block title="Today" description="Default period is today, not month-to-date. At 19:34 on a Friday nobody is asking about the 4th.">
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(232px, 1fr))", gap: 12 }}>
          {TILES.map((t) => <Tile key={t.id} tile={t} ent={ent} onProvenance={onProvenance} />)}
        </div>
      </Block>

      <Block title="Timing against target" description="Median on the face, mean below. One catastrophic two-hour order moves a mean and misrepresents the shift.">
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(280px, 1fr))", gap: 12, marginBottom: 12 }}>
          {GAUGES.map((g) => <Gauge key={g.id} g={g} onProvenance={onProvenance} />)}
        </div>
        <BucketStrip onProvenance={onProvenance} />
      </Block>

      <Block title="Mix">
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(300px, 1fr))", gap: 12 }}>
          <MixBars measure={measure} setMeasure={setMeasure} onProvenance={onProvenance} />
          <div style={{ display: "flex", flexDirection: "column", gap: 12, minWidth: 0 }}>
            <Card style={{ padding: 16 }}>
              <div className="q-caption" style={{ color: inkMuted, marginBottom: 12 }}>Fulfilment</div>
              <div style={{ display: "flex", height: 28, border: `1px solid ${hairline}` }}>
                {FULFILMENT_MIX.map((f, i) => (
                  <div key={f.id} title={`${f.label} — ${f.count}`}
                    style={{ width: `${(f.count / fulTotal) * 100}%`, background: [ink, "var(--q-surface-2)", surface1][i], borderRight: i < 2 ? `1px solid ${canvas}` : undefined }} />
                ))}
              </div>
              <div style={{ display: "flex", gap: 16, marginTop: 10, flexWrap: "wrap" }}>
                {FULFILMENT_MIX.map((f, i) => (
                  <span key={f.id} className="q-caption" style={{ color: inkMuted, display: "inline-flex", alignItems: "center", gap: 6 }}>
                    <span style={{ width: 8, height: 8, background: [ink, "var(--q-surface-2)", surface1][i], border: `1px solid ${hairline}` }} />
                    {f.label} <span className="q-tnum" style={{ color: ink }}>{f.count}</span>
                    <span className="q-tnum" style={{ color: inkSubtle }}>{pct1((f.count / fulTotal) * 100)}</span>
                  </span>
                ))}
              </div>
            </Card>
            <Unbuilt label="Payment mix">
              ordering.orders has payment_status_projection and no payment method column at all;
              tenant.channel_payment_methods is configuration, not a record of what was paid.
              Locked rather than hidden — a manager who cannot find the payment chart assumes it lives
              somewhere else. Arrives with ADR 0013/0046 (fact_order_tender).
            </Unbuilt>
          </div>
        </div>
      </Block>

      <Block title="Where orders stop" description="A cancellation before production and four cooked dishes binned at the pass are not the same event.">
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(340px, 1fr))", gap: 12 }}>
          <Funnel onDropoff={onDropoff} />
          <div style={{ minWidth: 0 }}>
            <SevTable
              columns={[
                { key: "name", label: "Branch", render: (r) => loc(r.id).name },
                { key: "orders", label: "Orders", align: "right", render: (r) => nf(r.orders) },
                { key: "revenue", label: "Revenue", align: "right", why: <Why metric="revenue.gross.v1" onOpen={onProvenance} />, render: (r) => <span style={{ fontFamily: "var(--q-font-mono)" }}>{uzs(r.revenue)}</span> },
                { key: "check", label: "Average check", align: "right", render: (r) => <span style={{ fontFamily: "var(--q-font-mono)" }}>{uzs(r.check)}</span> },
                { key: "prep", label: "Prep", align: "right", render: (r) => `${r.prepMedian} min` },
                { key: "cancel", label: "Cancelled", align: "right", render: (r) => pct1(r.cancelPct) },
                { key: "within", label: "In target", align: "right", render: (r) => `${r.withinPct}%` },
              ]}
              rows={branchRows}
              totals={{
                name: "All branches", orders: nf(branchRows.reduce((s, r) => s + r.orders, 0)),
                revenue: "—", check: "—", prep: "—", cancel: "—", within: "—",
              }}
            />
            <div className="q-caption" style={{ color: inkMuted, marginTop: 8, lineHeight: 1.5 }}>
              Sorted by revenue, not alphabetically — a leaderboard is read for its top and its bottom.
              The money totals are «—» on purpose: these branches belong to two legal entities and a
              combined figure reconciles to neither filing (ADR 0038). Pick an entity above for a total.
            </div>
          </div>
        </div>
      </Block>

      <Block title="Not prototyped here" description="The honest list, which is also the backlog. A missing report has to be legibly unbuilt rather than apparently broken.">
        <div style={{ border: `1px solid ${hairline}`, background: canvas }}>
          {NOT_PROTOTYPED.map((n) => (
            <div key={n.view} style={{ display: "flex", gap: 16, padding: "10px 16px", borderBottom: `1px solid ${hairline}`, alignItems: "flex-start" }}>
              <span className="q-emphasis" style={{ color: ink, width: 200, flexShrink: 0 }}>{n.view}</span>
              <span className="q-caption" style={{ color: inkMuted, lineHeight: 1.5, minWidth: 0 }}>{n.why}</span>
              <span className="q-caption" style={{ color: inkSubtle, marginLeft: "auto", flexShrink: 0, fontFamily: "var(--q-font-mono)" }}>{n.adr}</span>
            </div>
          ))}
        </div>
      </Block>
    </>
  );
}

/* Severity for a stage row: over an hour is an incident, over the resolved band
 * is a warning, and the incident suppresses the warning's caption rather than
 * printing both. */
const stageSeverity = (o) => {
  if (o.totalSec > 3_600) return { _sev: "incident", _reason: "critical delay — over an hour end to end" };
  if (o.lateMinutes) return { _sev: "warning", _reason: `+${o.lateMinutes} min over the resolved band (${o.promiseMinutes} min)` };
  return { _sev: null, _reason: null };
};

function Stages({ onProvenance, onOrder, search, setSearch }) {
  const rows = useMemo(() => {
    const q = search.trim().toLowerCase();
    return ORDER_FACTS
      .filter((o) => !q || o.id.toLowerCase().includes(q) || (o.customer || "").toLowerCase().includes(q))
      /* Longest first. The slow ones are the point of the table. */
      .sort((a, b) => b.totalSec - a.totalSec)
      .map((o) => ({ ...o, _id: o.id, ...stageSeverity(o) }));
  }, [search]);

  return (
    <>
      <div style={{ display: "flex", gap: 12, alignItems: "center", marginBottom: 12, flexWrap: "wrap" }}>
        <input value={search} onChange={(e) => setSearch(e.target.value)}
          placeholder="Order number or per-provider external id" className="q-body-sm"
          style={{ height: 32, minWidth: 320, padding: "0 8px", background: canvas, color: ink, border: "none", borderBottom: `1px solid ${hairline}`, outline: "none" }}
          onFocus={(e) => { e.target.style.borderBottom = `2px solid ${blue}`; }}
          onBlur={(e) => { e.target.style.borderBottom = `1px solid ${hairline}`; }} />
        <span className="q-caption" style={{ color: inkSubtle }}>
          A customer quoting a Yandex order number has to be findable, so the box searches both
        </span>
      </div>

      <SevTable
        onRowClick={onOrder}
        columns={[
          { key: "id", label: "Order", mono: true, render: (o) => o.id },
          { key: "branch", label: "Branch", render: (o) => loc(o.locationId).name },
          { key: "channel", label: "Channel", render: (o) => (
            <span>{chan(o.channelId).name}
              {o.operatorMachine ? <span className="q-caption" style={{ color: inkSubtle, display: "block" }}>machine · {o.operator}</span> : null}
            </span>
          ) },
          { key: "accept", label: "Operator accepted", align: "right", render: (o) => dur(o.acceptSec) },
          { key: "branchSec", label: "Branch accepted", align: "right", render: (o) => dur(o.branchSec) },
          { key: "prep", label: "Prepared", align: "right", why: <Why metric="prep_time.median.v1" onOpen={onProvenance} />, render: (o) => dur(o.prepSec) },
          { key: "courier", label: "Courier en route", align: "right", render: () => <span style={{ color: inkSubtle }}>—</span> },
          { key: "total", label: "Total", align: "right", why: <Why metric="sla_bucket_set.v1" onOpen={onProvenance} />, render: (o) => <span style={{ fontWeight: 600 }}>{dur(o.totalSec)}</span> },
        ]}
        rows={rows}
      />
      <div className="q-caption" style={{ color: inkMuted, marginTop: 8, lineHeight: 1.5 }}>
        «Courier en route» is «—» and never 0 across every row: ADR 0042 does not exist, and a zero there
        would read as an instant delivery. NULL and zero are different answers.
        Open a row for the two clocks.
      </div>
    </>
  );
}

function Late({ onProvenance, onOrder }) {
  const rows = ORDER_FACTS
    .filter((o) => o.lateMinutes)
    /* Severity, not time. The queue exists for the worst case; within a
     * severity the oldest order is the one someone is still waiting on. */
    .sort((a, b) => b.lateMinutes - a.lateMinutes || new Date(a.createdAt) - new Date(b.createdAt))
    .map((o) => ({
      ...o, _id: o.id,
      _sev: o.lateMinutes > 30 ? "incident" : o.lateMinutes >= 10 ? "warning" : null,
      _reason: o.lateMinutes > 30 ? `+${o.lateMinutes} min — the customer called before we did`
        : o.lateMinutes >= 10 ? `+${o.lateMinutes} min over the resolved band` : null,
    }));

  return (
    <>
      <Band tone="warning" title="These minutes cover the kitchen only. The road is not in them yet"
        action={<Button size="sm" variant="tertiary" onClick={() => onProvenance("orders.late.v1")}>Read the definition</Button>}>
        ordering.orders.promised_at is decided once at checkout and never recomputed, so widening a
        preparation band next month cannot retroactively make last month's late orders punctual — the
        defect this column exists to prevent. Each promise also records what produced it, so a branch
        quoting the platform fallback rather than its own bands is visible rather than merely late.
        What is still missing is travel: promise_travel_minutes is null on every delivery order until
        ADR 0037's zone model lands, so a delivery promise here is a kitchen promise and the figures
        below understate lateness on delivery.
      </Band>

      <div style={{ display: "flex", gap: 24, padding: "10px 16px", border: `1px solid ${hairline}`, background: canvas, marginBottom: 12, flexWrap: "wrap", alignItems: "center" }}>
        <span className="q-body-sm" style={{ color: ink }}>
          For the period: <span className="q-tnum">{LATE_SUMMARY.count}</span> late of{" "}
          <span className="q-tnum">{LATE_SUMMARY.total}</span>, median{" "}
          <span className="q-tnum">+{LATE_SUMMARY.medianMinutes} min</span>, worst{" "}
          <span className="q-tnum">+{LATE_SUMMARY.worstMinutes} min</span>
        </span>
        <Why metric="orders.late.v1" onOpen={onProvenance} />
      </div>

      <SevTable
        onRowClick={onOrder}
        columns={[
          { key: "id", label: "Order", mono: true, render: (o) => o.id },
          { key: "late", label: "Minutes late", align: "right", why: <Why metric="orders.late.v1" onOpen={onProvenance} />, render: (o) => <span className="q-emphasis">+{o.lateMinutes}</span> },
          { key: "branch", label: "Branch", render: (o) => loc(o.locationId).name },
          { key: "mode", label: "Type", render: (o) => o.fulfilment.toLowerCase().replace("_", " ") },
          { key: "channel", label: "Channel", render: (o) => chan(o.channelId).name },
          { key: "total", label: "Order total", align: "right", render: (o) => <span style={{ fontFamily: "var(--q-font-mono)" }}>{uzs(o.total)}</span> },
          { key: "items", label: "Products", render: (o) => <span style={{ display: "block", maxWidth: 280, color: inkMuted }}>{o.itemsSummary}</span> },
          { key: "created", label: "Created", align: "right", mono: true, render: (o) => dt(o.createdAt) },
          { key: "reason", label: "Reason", render: (o) => o.reasonText || <span style={{ color: inkSubtle }}>—</span> },
        ]}
        rows={rows}
      />
    </>
  );
}

function Aggregators({ onOrder }) {
  /* max(created_at) group by (location, channel) over a small set — cheap on
   * PostgreSQL today, and the one genuinely operational thing on an otherwise
   * historical screen. */
  const cellState = (l, iso) => {
    if (l.state === "FORCE_CLOSED") return { sev: "none", note: "branch closed 16:20 — thresholds suppressed" };
    if (!iso) return { sev: "none", note: "no order on this channel yet" };
    const h = hoursSince(iso);
    if (h > 12) return { sev: "incident", note: `${Math.floor(h)} h ago — check the integration` };
    if (h > 4) return { sev: "warning", note: `${Math.floor(h)} h ago` };
    return { sev: "none", note: `${Math.round(h * 60)} min ago` };
  };

  return (
    <>
      <Block title="Channel liveness" description="A dead aggregator channel loses money in silence and is invisible everywhere else in the console. Two-second read.">
        <div style={{ border: `1px solid ${hairline}`, background: canvas, overflowX: "auto" }}>
          <table style={{ width: "100%", borderCollapse: "collapse" }}>
            <thead>
              <tr>
                <th className="q-caption" style={{ textAlign: "left", padding: "10px 16px", background: surface1, color: inkMuted, borderBottom: `1px solid ${hairline}` }}>Branch</th>
                {AGGREGATOR_CHANNELS.map((c) => (
                  <th key={c} className="q-caption" style={{ textAlign: "left", padding: "10px 16px", background: surface1, color: inkMuted, borderBottom: `1px solid ${hairline}`, whiteSpace: "nowrap" }}>
                    {chan(c).name}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {LIVENESS.map((row) => {
                const l = loc(row.locationId);
                return (
                  <tr key={row.locationId} style={{ borderBottom: `1px solid ${hairline}` }}>
                    <td className="q-body-sm" style={{ padding: "10px 16px", color: ink, verticalAlign: "top" }}>
                      {l.name}
                      {l.state === "FORCE_CLOSED" ? (
                        <div className="q-caption" style={{ color: AMBER_TEXT, marginTop: 3 }}>Force-closed 16:20 — {l.closedReason}</div>
                      ) : null}
                    </td>
                    {AGGREGATOR_CHANNELS.map((c) => {
                      const iso = row.cells[c];
                      const s = cellState(l, iso);
                      const sev = SEV[s.sev];
                      return (
                        <td key={c} style={{ padding: 0, verticalAlign: "top" }}>
                          <div style={{ background: sev.tint, borderLeft: `3px solid ${sev.rule}`, padding: "10px 13px", height: "100%" }}>
                            <div className="q-body-sm q-tnum" style={{ color: iso ? ink : inkSubtle, fontFamily: "var(--q-font-mono)" }}>
                              {iso ? dt(iso) : "—"}
                            </div>
                            <div className="q-caption" style={{ color: sev.text, marginTop: 3 }}>{s.note}</div>
                          </div>
                        </td>
                      );
                    })}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        <div className="q-caption" style={{ color: inkMuted, marginTop: 8, lineHeight: 1.5 }}>
          Amber past 4 hours during service, red past 12. Suppressed for a closed branch — an amber cell
          for a branch that shut at 16:20 trains people to ignore amber cells. Clicking a cell goes to
          health &amp; errors (10.8).
        </div>
      </Block>

      <Block title="Aggregator orders">
        <SevTable
          onRowClick={(r) => {
            const fact = ORDER_FACTS.find((o) => o.id === r.id);
            if (fact) onOrder(fact);
          }}
          columns={[
            { key: "id", label: "Order", mono: true, render: (r) => r.id },
            { key: "agg", label: "Aggregator", render: (r) => chan(r.channelId).name },
            { key: "ext", label: "External id", mono: true, render: (r) => r.externalId },
            { key: "branch", label: "Branch", render: (r) => loc(r.locationId).name },
            { key: "total", label: "Total", align: "right", render: (r) => <span style={{ fontFamily: "var(--q-font-mono)" }}>{uzs(r.total)}</span> },
            { key: "comm", label: "Commission", align: "right", render: () => <span style={{ color: inkSubtle }}>—</span> },
            { key: "status", label: "Status", render: (r) => (
              <StatusPill tone={r.status === "COMPLETED" ? "healthy" : "failed"}>
                {r.status === "COMPLETED" ? "Completed" : "Rejected"}
              </StatusPill>
            ) },
            { key: "at", label: "Date", align: "right", mono: true, render: (r) => dt(r.at) },
          ]}
          rows={AGGREGATOR_ORDERS
            .slice()
            .sort((a, b) => new Date(b.at) - new Date(a.at))
            .map((r) => ({
              ...r, _id: r.id,
              _sev: r.status === "REJECTED" ? "warning" : null,
              _reason: r.status === "REJECTED" ? `reason_code «${r.reasonCode}» — a raw varchar until ADR 0039` : null,
            }))}
        />
        <div className="q-caption" style={{ color: inkMuted, marginTop: 8, lineHeight: 1.5 }}>
          Commission renders «—» and never 0 until ADR 0040 supplies it, and revenue.net.v1 does not
          subtract it. A zero there is a claim we have not earned.
        </div>
      </Block>
    </>
  );
}

/* ── the export centre ─────────────────────────────────────────────────────*/

const EXPORT_TONE = {
  QUEUED: "neutral", RUNNING: "info", READY: "healthy", EXPIRED: "pending", FAILED: "failed",
};

function Exports({ onExportDialog }) {
  const [sel, setSel] = useState([]);
  const rows = [...EXPORTS].sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt));
  const chosen = rows.filter((r) => sel.includes(r.id));

  /* A bulk action is offered only when it is valid for every selected row.
   * Acting on the valid subset silently is how a manager learns not to trust
   * the console. */
  const badCancel = chosen.filter((r) => r.status !== "QUEUED");
  const badRepeat = chosen.filter((r) => r.status === "QUEUED" || r.status === "RUNNING");

  const toggle = (id) => setSel((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id]));

  return (
    <>
      <div style={{ display: "flex", alignItems: "center", gap: 16, padding: "10px 16px", border: `1px solid ${hairline}`, background: canvas, marginBottom: 12, flexWrap: "wrap" }}>
        <span className="q-body-sm" style={{ color: ink }}>
          Exported today: <span className="q-tnum">{nf(EXPORT_QUOTA.usedRows)}</span> of{" "}
          <span className="q-tnum">{nf(EXPORT_QUOTA.capRows)}</span> rows
        </span>
        <span style={{ width: 160, height: 6, background: surface1 }}>
          <span style={{ display: "block", height: "100%", width: `${(EXPORT_QUOTA.usedRows / EXPORT_QUOTA.capRows) * 100}%`, background: ink }} />
        </span>
        <span style={{ marginLeft: "auto" }}>
          <Button size="sm" onClick={onExportDialog}>Create export</Button>
        </span>
      </div>

      {chosen.length ? (
        <div style={{ display: "flex", alignItems: "center", gap: 12, padding: "10px 16px", border: `1px solid ${hairline}`, borderBottom: "none", background: "var(--q-info-tint)", flexWrap: "wrap" }}>
          <span className="q-body-sm" style={{ color: ink }}>{chosen.length} selected</span>
          <Button size="sm" variant="tertiary" disabled={badCancel.length > 0}
            onClick={() => setSel([])}>
            {badCancel.length ? `${badCancel.length} of ${chosen.length} selected cannot be cancelled` : "Cancel queued"}
          </Button>
          <Button size="sm" variant="tertiary" disabled={badRepeat.length > 0} onClick={() => setSel([])}>
            {badRepeat.length ? `${badRepeat.length} of ${chosen.length} selected cannot be repeated` : "Repeat"}
          </Button>
          <span className="q-caption" style={{ color: inkMuted, minWidth: 0 }}>
            {badCancel.length ? "Only a queued export can be cancelled — a running job has already spent the quota." : null}
          </span>
          <button type="button" onClick={() => setSel([])} className="q-caption"
            style={{ marginLeft: "auto", background: "transparent", border: "none", color: blue, cursor: "pointer" }}>
            Clear
          </button>
        </div>
      ) : null}

      <SevTable
        columns={[
          {
            key: "sel", label: "", width: 40, render: (r) => (
              <input type="checkbox" checked={sel.includes(r.id)} onChange={() => toggle(r.id)}
                onClick={(e) => e.stopPropagation()} style={{ accentColor: blue }} />
            ),
          },
          { key: "report", label: "Report", render: (r) => (
            <span>
              {r.report}
              {r.pii ? <span className="q-caption" style={{ color: RED_TEXT, marginLeft: 8, background: RED_TINT, padding: "1px 6px" }}>PII</span> : null}
            </span>
          ) },
          { key: "filters", label: "Filters", render: (r) => <span style={{ color: inkMuted }}>{r.filters}</span> },
          { key: "cols", label: "Columns", align: "right", render: (r) => r.columns },
          { key: "rows", label: "Rows", align: "right", render: (r) => (r.rows == null ? <span style={{ color: inkSubtle }}>—</span> : nf(r.rows)) },
          { key: "by", label: "Requested by", render: (r) => (
            <span>
              {r.requestedBy}
              {r.requesterDisabled ? <span className="q-caption" style={{ color: AMBER_TEXT, display: "block" }}>{r.requesterDisabled}</span> : null}
            </span>
          ) },
          { key: "created", label: "Created", align: "right", mono: true, render: (r) => dt(r.createdAt) },
          { key: "expires", label: "Expires", align: "right", mono: true, render: (r) => (r.expiresAt ? dt(r.expiresAt) : <span style={{ color: inkSubtle }}>—</span>) },
          { key: "status", label: "Status", render: (r) => <StatusPill tone={EXPORT_TONE[r.status]}>{r.status.toLowerCase()}</StatusPill> },
          { key: "act", label: "", align: "right", render: (r) => (
            r.status === "READY"
              ? <Button size="sm" variant="ghost" onClick={() => {}}>Download</Button>
              : <span className="q-caption" style={{ color: inkSubtle }}>{r.status === "QUEUED" ? "cancellable" : "—"}</span>
          ) },
        ]}
        rows={rows.map((r) => ({
          ...r, _id: r.id,
          _sev: r.status === "FAILED" ? "incident" : r.requesterDisabled ? "warning" : null,
          _reason: r.status === "FAILED" ? `failed · correlation ${r.correlationId}`
            : r.requesterDisabled ? "the account is gone, the audit row is not — this is the question audit exists to answer" : null,
        }))}
      />
      <div className="q-caption" style={{ color: inkMuted, marginTop: 8, lineHeight: 1.5 }}>
        The download button disappears when the presigned URL expires rather than returning a 403.
        The row stays, so «who exported the customer base last Tuesday» is answerable after the account
        is disabled. Every row here is also an ADR 0027 BUSINESS audit fact carrying the row count.
      </div>
    </>
  );
}

/* ── panels ────────────────────────────────────────────────────────────────*/

function ProvenancePanel({ metricId, onClose }) {
  if (metricId === "business_day") {
    return (
      <Drawer title="Operating day" onClose={onClose} width={420}>
        <div className="q-body-sm" style={{ color: ink, lineHeight: 1.6 }}>
          Every figure in this section is bucketed by the tenant's operating day, currently{" "}
          <b>{TENANT.businessDayStart}→{TENANT.businessDayStart} {TENANT.timezone}</b>.
        </div>
        <div className="q-caption" style={{ color: inkMuted, marginTop: 12, lineHeight: 1.6 }}>
          Delever's operating window defaults to 09:00→09:00. A restaurant closing at 02:00 that sees
          those orders on the next date concludes the report is broken, so this is an onboarding answer
          and not a default. Changing it later needs an ADR 0027 approval and a full recut of history.
          A range spanning two boundary regimes is refused rather than silently mixed.
        </div>
      </Drawer>
    );
  }
  const m = METRICS[metricId];
  if (!m) return null;
  const rows = [
    ["Definition", m.definition], ["Includes", m.includes], ["Excludes", m.excludes],
    ["Refunds", m.refunds], ["Grain", m.grain], ["Source", m.source], ["Rounding", m.rounding],
    ["Operating day", `${TENANT.businessDayStart} ${TENANT.timezone}`],
    ["Data as of", `${dt(FRESHNESS.dataAsOf)} · closed through ${FRESHNESS.closedThrough.slice(8)}.${FRESHNESS.closedThrough.slice(5, 7)}`],
    ["Effective from", m.effectiveFrom ? day(`${m.effectiveFrom}T00:00:00`) : "—"],
  ];
  return (
    <Drawer title={m.name} onClose={onClose} width={420}>
      <div className="q-caption" style={{ color: inkMuted, fontFamily: "var(--q-font-mono)", marginBottom: 16 }}>{metricId}</div>
      {rows.map(([k, v]) => (
        <div key={k} style={{ display: "flex", gap: 12, padding: "8px 0", borderBottom: `1px solid ${hairline}` }}>
          <span className="q-caption" style={{ color: inkSubtle, width: 108, flexShrink: 0 }}>{k}</span>
          <span className="q-body-sm" style={{ color: ink, minWidth: 0, lineHeight: 1.5 }}>{v}</span>
        </div>
      ))}
      <div style={{ marginTop: 16, background: m.signed ? "var(--q-success-tint)" : AMBER_TINT, borderLeft: `3px solid ${m.signed ? "var(--q-success)" : AMBER}`, padding: "10px 12px" }}>
        <div className="q-emphasis" style={{ color: m.signed ? "var(--q-success-text)" : AMBER_TEXT }}>
          {m.signed ? "Signed by finance" : "Provisional definition — not signed by finance"}
        </div>
        <div className="q-caption" style={{ color: m.signed ? "var(--q-success-text)" : AMBER_TEXT, marginTop: 4, lineHeight: 1.5 }}>
          {m.signed
            ? "Version 1 semantics are fixed. A change ships as v2 with a recut, never as an edit."
            : "ADR 0043 ships metric semantics as version 1 and marks them provisional until finance signs them. A provisional metric renders its number with an amber rule."}
        </div>
      </div>
      {m.open ? (
        <div style={{ marginTop: 12, background: surface1, borderLeft: `3px solid ${inkSubtle}`, padding: "10px 12px" }}>
          <div className="q-caption" style={{ color: inkMuted, lineHeight: 1.6 }}>{m.open}</div>
        </div>
      ) : null}
    </Drawer>
  );
}

/* The two clocks. This is the most valuable widget in the section: production
 * and delivery are separate clocks that can disagree, which is exactly the
 * difference between «the kitchen was late» and «the courier was late». One
 * linear list of timestamps destroys that distinction, and that is what Delever
 * ships. */
function ClockStrip({ title, steps }) {
  return (
    <div style={{ minWidth: 0 }}>
      <div className="q-caption" style={{ color: inkMuted, marginBottom: 10 }}>{title}</div>
      {steps.map((s, i) => {
        const tone = s.severity === "incident" ? RED : s.unbuilt ? "transparent" : s.at ? ink : inkSubtle;
        return (
          <div key={i} style={{ display: "flex", gap: 10 }}>
            <div style={{ display: "flex", flexDirection: "column", alignItems: "center", flexShrink: 0 }}>
              <span style={{ width: 9, height: 9, borderRadius: "50%", marginTop: 5, background: tone, border: s.unbuilt ? `1px dashed ${inkSubtle}` : "none" }} />
              {i < steps.length - 1 ? <span style={{ width: 1, flex: 1, background: hairline, minHeight: 22 }} /> : null}
            </div>
            <div style={{ paddingBottom: 14, minWidth: 0 }}>
              <div className="q-body-sm" style={{ color: s.unbuilt ? inkSubtle : ink }}>{s.label}</div>
              {/* the timestamp printed as visible text under every completed
                  stage, not hidden behind a hover */}
              <div className="q-caption q-tnum" style={{ color: s.severity === "incident" ? RED_TEXT : inkSubtle, fontFamily: "var(--q-font-mono)" }}>
                {s.at ? hhmm(s.at) : "—"}{s.actor ? ` · ${s.actor}` : ""}
              </div>
              {s.unbuilt ? <div className="q-caption" style={{ color: inkSubtle, marginTop: 2 }}>not recorded · {s.unbuilt}</div> : null}
              {s.note ? <div className="q-caption" style={{ color: inkSubtle, marginTop: 2 }}>{s.note}</div> : null}
            </div>
          </div>
        );
      })}
    </div>
  );
}

function OrderPanel({ order, onClose }) {
  const sev = stageSeverity(order);
  return (
    <Drawer title={order.id} onClose={onClose} width={620}>
      {sev._sev ? (
        <Band tone={sev._sev === "incident" ? "error" : "warning"} title={sev._reason}>
          Measured against tenant.preparation_bands as resolved today, not against a stored promise.
        </Band>
      ) : null}

      <div style={{ display: "grid", gridTemplateColumns: "repeat(3, minmax(0, 1fr))", gap: 16, marginBottom: 20 }}>
        {[
          ["Branch", loc(order.locationId).name], ["Channel", chan(order.channelId).name],
          ["Type", order.fulfilment.toLowerCase().replace("_", " ")],
          ["Total", uzs(order.total)], ["Operator", order.operator + (order.operatorMachine ? " · machine" : "")],
          ["Courier", order.courier || "—"],
        ].map(([k, v]) => (
          <div key={k} style={{ minWidth: 0 }}>
            <div className="q-caption" style={{ color: inkSubtle, marginBottom: 3 }}>{k}</div>
            <div className="q-body-sm" style={{ color: ink, wordBreak: "break-word" }}>{v}</div>
          </div>
        ))}
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 24, paddingTop: 16, borderTop: `1px solid ${hairline}` }}>
        <ClockStrip title="Kitchen clock" steps={order.kitchen} />
        <ClockStrip title="Logistics clock" steps={order.logistics} />
      </div>

      <div className="q-caption" style={{ color: inkMuted, lineHeight: 1.6, marginTop: 8 }}>
        Two independent strips on purpose. A single linear bar cannot show that the pass was clear at
        18:58 while the order sat unassigned — which is the whole argument a restaurant has after a bad
        evening. The dashed dots are stages nothing records yet.
      </div>

      <div style={{ marginTop: 20, paddingTop: 16, borderTop: `1px solid ${hairline}`, display: "flex", gap: 8, alignItems: "center" }}>
        <Button onClick={onClose}>Open order →</Button>
        <span className="q-caption" style={{ color: inkSubtle }}>
          Customer {order.customer || "—"} · {order.phone ? `${order.phone.slice(0, 8)} *** ** ${order.phone.slice(-2)}` : "—"} — masked; revealing writes an audit fact
        </span>
      </div>
    </Drawer>
  );
}

function CancellationPanel({ drop, onClose }) {
  const total = CANCELLATION_REASONS.reduce((s, r) => s + r.count, 0);
  return (
    <Drawer title={`${drop.label} · ${drop.count}`} onClose={onClose} width={520}>
      <SevTable
        columns={[
          { key: "code", label: "Reason code", mono: true, render: (r) => r.code },
          { key: "label", label: "As shown", render: (r) => (
            <span>{r.label}{r.detail ? <span className="q-caption" style={{ color: inkSubtle, display: "block" }}>{r.detail}</span> : null}</span>
          ) },
          { key: "count", label: "Count", align: "right", render: (r) => r.count },
          { key: "share", label: "Share", align: "right", render: (r) => pct1((r.count / total) * 100) },
          { key: "stock", label: "Stock", render: () => <span style={{ color: inkSubtle }}>—</span> },
          { key: "liab", label: "Liable", render: () => <span style={{ color: inkSubtle }}>—</span> },
        ]}
        rows={CANCELLATION_REASONS.map((r) => ({ ...r, _id: r.code }))}
      />
      <div className="q-caption" style={{ color: inkMuted, marginTop: 12, lineHeight: 1.6 }}>
        The reason codes are printed exactly as stored. order_state_history.reason_code is a
        varchar(64), unvalidated and unlocalised, and the casing drift above is what four months of three
        operators looks like.
      </div>
      <div style={{ marginTop: 12 }}>
        <Unbuilt label="What it cost">
          stock_disposition (RELEASE · RETURN_TO_STOCK · WRITE_OFF · NO_EFFECT) and liability_party
          (TENANT · CUSTOMER · COURIER_PARTNER · PLATFORM) need ADR 0039. Until then a release before
          production and four cooked dishes binned at the pass report identically — which is exactly the
          Delever behaviour this panel exists to replace, so the columns render «—» rather than 0.
        </Unbuilt>
      </div>
    </Drawer>
  );
}

function ExportDialog({ view, onClose }) {
  const [format, setFormat] = useState("xlsx");
  return (
    <Drawer title="Create export" onClose={onClose} width={560}>
      <div className="q-caption" style={{ color: inkSubtle, marginBottom: 4 }}>What</div>
      <div className="q-body-sm" style={{ color: ink, marginBottom: 20 }}>
        {view} · 21.08 · all branches · all channels
      </div>

      <div className="q-caption" style={{ color: inkSubtle, marginBottom: 8 }}>Columns</div>
      {["Order", "Branch", "Channel", "Operator accepted", "Branch accepted", "Prepared", "Total"].map((c) => (
        <label key={c} style={{ display: "flex", gap: 8, alignItems: "center", padding: "5px 0" }}>
          <input type="checkbox" defaultChecked style={{ accentColor: blue }} />
          <span className="q-body-sm" style={{ color: ink }}>{c}</span>
        </label>
      ))}
      <div style={{ marginTop: 12, borderLeft: `3px solid ${RED}`, background: RED_TINT, padding: "8px 12px" }}>
        <div className="q-emphasis" style={{ color: RED_TEXT }}>Personal data</div>
        {["Customer name", "Phone", "Address"].map((c) => (
          <label key={c} style={{ display: "flex", gap: 8, alignItems: "center", padding: "5px 0", opacity: 0.65 }}>
            <input type="checkbox" disabled />
            <span className="q-body-sm" style={{ color: RED_TEXT }}>{c}</span>
          </label>
        ))}
        <div className="q-caption" style={{ color: RED_TEXT, lineHeight: 1.5 }}>
          Disabled up front: this principal has no customer.pii.export. Requesting one is rejected naming
          the column, never silently narrowed — so the rejection is not a surprise after a five-minute job.
        </div>
      </div>

      <div className="q-caption" style={{ color: inkSubtle, margin: "20px 0 8px" }}>Format</div>
      <Seg axis="slice" value={format} onChange={setFormat}
        options={[{ id: "xlsx", label: "Excel (.xlsx)" }, { id: "csv", label: "CSV (UTF-8)" }]} />
      <div className="q-caption" style={{ color: inkMuted, marginTop: 6 }}>
        Excel by default — this market opens exports in Excel and a raw CSV with Cyrillic is a support ticket.
      </div>

      <div style={{ display: "flex", gap: 8, marginTop: 24, paddingTop: 16, borderTop: `1px solid ${hairline}` }}>
        <span style={{ marginLeft: "auto" }} />
        <Button variant="ghost" onClick={onClose}>Cancel</Button>
        <Button onClick={onClose}>Create export</Button>
      </div>
    </Drawer>
  );
}

/* ── the screen ────────────────────────────────────────────────────────────*/

const TABS = [
  { id: "overview", label: "Overview" },
  { id: "stages", label: "Stage durations", count: ORDER_FACTS.length },
  { id: "late", label: "Late", count: LATE_SUMMARY.count },
  { id: "aggregators", label: "Aggregators", count: AGGREGATOR_ORDERS.length },
  { id: "exports", label: "Exports", count: EXPORTS.length },
];

export default function Statistics({ tab, setTab, range, setRange }) {
  /* The two shared axes are hoisted to the shell so a manager who set
   * «Chilonzor, delivery, yesterday» keeps it across every view. The fallbacks
   * let the screen render standalone. */
  const [tabLocal, setTabLocal] = useState("overview");
  const [rangeLocal, setRangeLocal] = useState("today");
  const activeTab = tab ?? tabLocal;
  const changeTab = setTab ?? setTabLocal;
  const activeRange = range ?? rangeLocal;
  const changeRange = setRange ?? setRangeLocal;

  const [gran, setGran] = useState("hour");
  const [branch, setBranch] = useState("all");
  const [channel, setChannel] = useState("all");
  const [mode, setMode] = useState("all");
  const [ent, setEnt] = useState("all");
  const [search, setSearch] = useState("");
  const [panel, setPanel] = useState(null);

  /* Switching the period never silently re-buckets: hourly buckets over a month
   * is a chart nobody can read, so the granularity clamps and says so. */
  const days = PERIODS.find((p) => p.id === activeRange)?.days ?? 1;
  useEffect(() => { if (days > 3 && gran === "hour") setGran("day"); }, [days, gran]);

  /* This console is used standing up. */
  useEffect(() => {
    const onKey = (e) => {
      if (e.target.matches?.("input, select, textarea")) return;
      if (e.key === "Escape") return setPanel(null);
      if (e.key === "d") changeRange("today");
      if (e.key === "w") changeRange("7d");
      if (e.key === "m") changeRange("month");
      if (e.key === "e") setPanel({ kind: "export" });
      if (e.key === "/") { e.preventDefault(); document.querySelector("input[placeholder^='Order number']")?.focus(); }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  const openMetric = (id) => setPanel({ kind: "metric", id });
  const openOrder = (o) => setPanel({ kind: "order", order: o });

  return (
    <div style={{ minWidth: 0 }}>
      <SectionHeader
        title="Reports"
        description="Every number about the past. What is happening right now lives on the live board — reading a day-grain fact for a live counter makes it both stale and expensive."
        right={<Button onClick={() => setPanel({ kind: "export" })}>Export</Button>}
      />

      <FilterBar
        range={activeRange} setRange={changeRange} gran={gran} setGran={setGran}
        branch={branch} setBranch={setBranch} channel={channel} setChannel={setChannel}
        mode={mode} setMode={setMode} ent={ent} setEnt={setEnt} onProvenance={openMetric}
      />

      <div style={{ display: "flex", alignItems: "baseline", gap: 12, padding: "10px 0", flexWrap: "wrap" }}>
        <span className="q-caption q-tnum" style={{ color: inkMuted, fontFamily: "var(--q-font-mono)" }}>
          Data as of {dt(FRESHNESS.dataAsOf)} · closed through 20.08 · partial day, through {hhmm(REPORT_NOW)}
        </span>
        <span className="q-caption" style={{ color: inkSubtle }}>
          Deltas compare the same elapsed fraction of the comparison period — half a day against a whole
          one is the most common dashboard lie
        </span>
        {days > 3 && gran === "day" ? (
          <span className="q-caption" style={{ color: AMBER_TEXT }}>Granularity clamped to day: hourly needs 3 days or less</span>
        ) : null}
      </div>

      <Band tone="warning" title={`The ${FRESHNESS.settlingDay} business day is inside its settle window`}>
        Figures for today are recalculated after the close job and may move. Nothing here is final until
        the day closes.
      </Band>
      <Band tone="error" title={`Recut for ${FRESHNESS.divergence.day} disagreed with the stored total`}>
        {FRESHNESS.divergence.metric} · reported to support as{" "}
        <span style={{ fontFamily: "var(--q-font-mono)" }}>{FRESHNESS.divergence.ticket}</span>. ADR 0043
        alerts on divergence rather than overwriting: you may already have acted on the earlier number,
        so you are told rather than corrected behind your back.
      </Band>

      <div style={{ display: "flex", borderBottom: `1px solid ${hairline}`, marginBottom: 20 }}>
        {TABS.map((t) => {
          const on = t.id === activeTab;
          return (
            <button key={t.id} type="button" onClick={() => changeTab(t.id)} className="q-body-sm"
              style={{
                padding: "12px 16px", background: "transparent", border: "none", cursor: "pointer",
                borderBottom: on ? `2px solid ${blue}` : "2px solid transparent",
                color: on ? ink : inkMuted, marginBottom: -1,
              }}>
              {t.label}
              {t.count !== undefined ? <span className="q-tnum" style={{ color: inkSubtle, marginLeft: 8 }}>{t.count}</span> : null}
            </button>
          );
        })}
      </div>

      {activeTab === "overview" ? <Overview ent={ent} onProvenance={openMetric} onDropoff={(d) => setPanel({ kind: "cancel", drop: d })} /> : null}
      {activeTab === "stages" ? <Stages onProvenance={openMetric} onOrder={openOrder} search={search} setSearch={setSearch} /> : null}
      {activeTab === "late" ? <Late onProvenance={openMetric} onOrder={openOrder} /> : null}
      {activeTab === "aggregators" ? <Aggregators onOrder={openOrder} /> : null}
      {activeTab === "exports" ? <Exports onExportDialog={() => setPanel({ kind: "export" })} /> : null}

      {panel?.kind === "metric" ? <ProvenancePanel metricId={panel.id} onClose={() => setPanel(null)} /> : null}
      {panel?.kind === "order" ? <OrderPanel order={panel.order} onClose={() => setPanel(null)} /> : null}
      {panel?.kind === "cancel" ? <CancellationPanel drop={panel.drop} onClose={() => setPanel(null)} /> : null}
      {panel?.kind === "export" ? <ExportDialog view={TABS.find((t) => t.id === activeTab)?.label} onClose={() => setPanel(null)} /> : null}
    </div>
  );
}

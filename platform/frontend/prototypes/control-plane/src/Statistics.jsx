/* Statistics — how the platform is doing.
 *
 * The one screen in this console whose subject is a number rather than a
 * customer. It answers three questions in order: is volume growing, who is
 * carrying it, and what is the business worth this month.
 *
 * Two honesty rules govern every figure here, because a statistics screen is
 * the easiest place in a prototype to tell a comfortable lie:
 *
 *   1. The range selector offers 7, 30 and 90 days because the product will.
 *      DAILY holds fourteen days. A 30- or 90-day range therefore draws the
 *      fourteen days that exist and says so, in the coverage band and in the
 *      "days present" field — it does not extrapolate, pad with zeroes, or
 *      quietly relabel a 14-day total as a 30-day one.
 *   2. The headline band comes from KPIS, whose windows are fixed at 30 and 90
 *      days. Those tiles do not move when the range changes, and they are
 *      labelled with their own window so nobody reads them as the range total.
 *      Summing the bars will not reproduce them, and the caption says as much.
 *
 * The chart is divs. A prototype that pulls in a charting library stops being a
 * prototype of the design system and starts being a prototype of the library.
 */

import {
  Card, SectionHeader, DataTable, StatusPill, FieldGrid,
  KpiTile, uzs, day,
  ink, inkMuted, inkSubtle, hairline, canvas,
} from "./components";
import { DAILY, TENANT_LEAGUE, TENANTS, KPIS } from "./data";

/* ── local primitives ──────────────────────────────────────────────────────
 * Four things the shared set does not have. They stay in this file: a range
 * switcher, a chart bar, a diverging change bar and a section band are this
 * screen's arrangements, and components.jsx belongs to every screen.
 */

/** Carbon content switcher. Selection is ink, not blue — blue is for actions. */
function RangeSelector({ value, onChange, options }) {
  return (
    <div
      role="group"
      aria-label="Reporting range"
      style={{ display: "inline-flex", border: `1px solid ${hairline}`, background: canvas }}
    >
      {options.map((o, i) => {
        const on = o.id === value;
        return (
          <button
            key={o.id}
            type="button"
            aria-pressed={on}
            onClick={() => onChange(o.id)}
            className="q-body-sm"
            style={{
              height: 32, padding: "0 14px", border: "none",
              borderLeft: i ? `1px solid ${hairline}` : "none",
              borderRadius: "var(--q-radius)",
              background: on ? ink : "transparent",
              color: on ? "var(--q-inverse-ink)" : inkMuted,
              cursor: "pointer", whiteSpace: "nowrap",
              transition: "background var(--q-dur-base) var(--q-ease-productive)",
            }}
          >
            {o.label}
          </button>
        );
      })}
    </div>
  );
}

/** One chart bar. Weekend days are ink, weekdays subtle — the shape of the
 *  week has to survive a greyscale print and a colour-blind reader. */
function Bar({ ratio, strong }) {
  return (
    <span style={{ display: "block", height: 12, background: "var(--q-surface-1)" }}>
      <span
        style={{
          display: "block", height: "100%",
          width: `${Math.max(ratio * 100, 1).toFixed(1)}%`,
          background: strong ? ink : inkSubtle,
        }}
      />
    </span>
  );
}

/** Period-over-period change, drawn from a centre line so a fall reads as a
 *  fall at a glance and not as a smaller version of a rise. */
function ChangeBar({ pct, scale }) {
  const half = Math.min(Math.abs(pct) / scale, 1) * 50;
  return (
    <span
      style={{
        display: "block", position: "relative", width: 88, height: 8,
        background: "var(--q-surface-1)",
      }}
    >
      <span style={{ position: "absolute", left: "50%", top: 0, bottom: 0, width: 1, background: "var(--q-surface-2)" }} />
      <span
        style={{
          position: "absolute", top: 0, bottom: 0,
          left: pct < 0 ? `${50 - half}%` : "50%",
          width: `${half}%`,
          background: pct < 0 ? "var(--q-error)" : "var(--q-success)",
        }}
      />
    </span>
  );
}

/** A titled band with a scanning line of context and a footnote. */
function Block({ title, meta, right, note, children }) {
  return (
    <section style={{ marginBottom: 32 }}>
      <div style={{ display: "flex", alignItems: "baseline", gap: 12, marginBottom: 12, flexWrap: "wrap" }}>
        <h2 className="q-subhead" style={{ margin: 0, color: ink }}>{title}</h2>
        {meta ? <span className="q-body-sm" style={{ color: inkMuted }}>{meta}</span> : null}
        {right ? <div style={{ marginLeft: "auto" }}>{right}</div> : null}
      </div>
      {children}
      {note ? (
        <div className="q-caption" style={{ color: inkSubtle, marginTop: 8, maxWidth: 780 }}>{note}</div>
      ) : null}
    </section>
  );
}

/* ── formatting and derivation ─────────────────────────────────────────────*/

/** Plain integers, grouped the way uzs() groups som. Not money, so not uzs(). */
const num = (n) => String(Math.round(n)).replace(/\B(?=(\d{3})+(?!\d))/g, " ");

const signed = (pct) => `${pct > 0 ? "+" : pct < 0 ? "−" : ""}${Math.abs(pct)}%`;

const DOW = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
/** Parsed at local midnight on purpose: a bare "2026-08-08" parses as UTC and
 *  slides a day backwards west of Greenwich, which silently moves a weekend. */
const at = (date) => new Date(`${date}T00:00:00`);
const dow = (date) => DOW[at(date).getDay()];
const isWeekend = (date) => [0, 6].includes(at(date).getDay());

const RANGES = [
  { id: "7d", label: "7 days", days: 7 },
  { id: "30d", label: "30 days", days: 30 },
  { id: "90d", label: "90 days", days: 90 },
];

const HEALTH_TONE = { healthy: "healthy", "at-risk": "degraded", "unknown": "neutral", closed: "neutral" };

const mean = (xs) => (xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : 0);

const ABSENT = [
  ["Anything before 08.08.2026", "DAILY holds fourteen days. The 30- and 90-day ranges are wired and honest about the hole rather than removed, because the range control is part of what this screen is meant to test."],
  ["Hourly profile", "The fixture is a daily roll-up, so the lunch and dinner peaks a restaurant platform actually runs on cannot be drawn."],
  ["Breakdown by city, plan or channel", "DAILY carries no dimensions — only orders, GMV and a tenant count per day."],
  ["Range-relative change", "The league table's period-over-period figure arrives pre-computed against a fixed 30 days. Recomputing it per range needs order-level history the fixture does not hold."],
  ["Retention and cohorts", "No customer-level or order-level data exists here, and a cohort chart invented from a roll-up would be fiction."],
];

export default function Statistics({ range, setRange }) {
  const spec = RANGES.find((r) => r.id === range) ?? RANGES[1];

  /* DAILY is ascending and ends at the present day, so the range is its tail. */
  const rows = DAILY.slice(Math.max(DAILY.length - spec.days, 0));
  const present = rows.length;
  const missing = spec.days - present;

  const maxOrders = Math.max(...rows.map((r) => r.orders));
  const maxGmv = Math.max(...rows.map((r) => r.gmvMinor));

  const totalOrders = rows.reduce((a, r) => a + r.orders, 0);
  const totalGmv = rows.reduce((a, r) => a + r.gmvMinor, 0);

  const busiest = rows.reduce((a, r) => (r.orders > a.orders ? r : a), rows[0]);
  const quietest = rows.reduce((a, r) => (r.orders < a.orders ? r : a), rows[0]);

  const weekendAvg = mean(rows.filter((r) => isWeekend(r.date)).map((r) => r.orders));
  const weekdayAvg = mean(rows.filter((r) => !isWeekend(r.date)).map((r) => r.orders));
  const uplift = weekdayAvg ? Math.round(((weekendAvg - weekdayAvg) / weekdayAvg) * 100) : 0;

  const tenantsLow = Math.min(...rows.map((r) => r.activeTenants));
  const tenantsHigh = Math.max(...rows.map((r) => r.activeTenants));

  /* The league sums to the KPIS 30-day totals exactly, so shares are real. */
  const leagueGmv = TENANT_LEAGUE.reduce((a, r) => a + r.gmvMinor, 0);
  const changeScale = Math.max(...TENANT_LEAGUE.map((r) => Math.abs(r.changePct)));
  const worst = TENANT_LEAGUE.reduce((a, r) => (r.changePct < a.changePct ? r : a), TENANT_LEAGUE[0]);
  const worstTenant = TENANTS.find((t) => t.id === worst.tenantId);

  const league = TENANT_LEAGUE.map((r, i) => {
    const t = TENANTS.find((x) => x.id === r.tenantId);
    return { ...r, id: r.tenantId, rank: i + 1, plan: t?.plan, city: t?.city, health: t?.health, note: t?.healthNote };
  });

  const tnum = (s) => <span className="q-tnum">{s}</span>;

  return (
    <div>
      <SectionHeader
        title="Statistics"
        description="Volume, value and who is carrying them. Figures are platform-wide across every trading tenant, in Asia/Tashkent days."
        right={
          <div style={{ textAlign: "right" }}>
            <RangeSelector value={spec.id} onChange={setRange} options={RANGES} />
            <div className="q-caption" style={{ color: inkSubtle, marginTop: 6 }}>
              Ending {day(`${DAILY[DAILY.length - 1].date}T00:00:00`)}
            </div>
          </div>
        }
      />

      {/* ── coverage ──────────────────────────────────────────────────────
        * Stated before any number is read, not footnoted after. A reader who
        * learns the range is short only at the bottom has already believed it. */}
      <div
        style={{
          display: "flex", alignItems: "flex-start", gap: 16, padding: "12px 16px",
          marginBottom: 24, background: canvas,
          border: `1px solid ${hairline}`,
          borderLeft: missing > 0 ? "3px solid var(--q-warning)" : `3px solid var(--q-success)`,
        }}
      >
        <div style={{ flexShrink: 0, paddingTop: 1 }}>
          <StatusPill tone={missing > 0 ? "pending" : "healthy"}>
            {present} of {spec.days} days
          </StatusPill>
        </div>
        <div className="q-body-sm" style={{ color: ink, minWidth: 0 }}>
          {missing > 0 ? (
            <>
              The fixture holds {DAILY.length} days, {day(`${DAILY[0].date}T00:00:00`)} to{" "}
              {day(`${DAILY[DAILY.length - 1].date}T00:00:00`)}. The remaining {num(missing)} days of this
              range have no data behind them and are not drawn, padded or estimated. Everything on this screen
              except the headline band at the foot therefore describes {present} days, not {spec.days}.
            </>
          ) : (
            <>
              Every day in this range has data. Ranges longer than {DAILY.length} days will show a gap —
              the fixture starts at {day(`${DAILY[0].date}T00:00:00`)}.
            </>
          )}
        </div>
      </div>

      {/* ── the selected range, derived ───────────────────────────────────*/}
      <Block
        title="Selected range"
        meta={`${day(`${rows[0].date}T00:00:00`)} – ${day(`${rows[rows.length - 1].date}T00:00:00`)}`}
        note="Every figure in this band is summed from the days drawn below, so the chart and the band can never disagree. They are not the same thing as the 30-day headline figures further down."
      >
        <Card style={{ padding: 20 }}>
          <FieldGrid
            columns={4}
            fields={[
              { label: "Days present", value: tnum(`${present} of ${spec.days}`) },
              { label: "Orders", value: tnum(num(totalOrders)) },
              { label: "GMV", value: tnum(uzs(totalGmv)) },
              { label: "Average basket", value: tnum(uzs(Math.round(totalGmv / totalOrders))) },
              { label: "Busiest day", value: tnum(`${num(busiest.orders)} · ${dow(busiest.date)} ${day(`${busiest.date}T00:00:00`)}`) },
              { label: "Quietest day", value: tnum(`${num(quietest.orders)} · ${dow(quietest.date)} ${day(`${quietest.date}T00:00:00`)}`) },
              { label: "Weekend average", value: tnum(`${num(weekendAvg)} orders a day`) },
              { label: "Weekday average", value: tnum(`${num(weekdayAvg)} orders a day`) },
            ]}
          />
        </Card>
      </Block>

      {/* ── the chart ─────────────────────────────────────────────────────
        * A row per day, a width percentage per bar, the value in tabular
        * figures beside it. Legible beats clever, and a table of bars can be
        * read aloud down a phone line, which a canvas cannot. */}
      <Block
        title="Orders and GMV by day"
        meta={`${present} days · weekends in ink`}
        note={`Bars are scaled to the tallest day in the range: ${num(maxOrders)} orders and ${uzs(maxGmv)}. Weekend days average ${num(weekendAvg)} orders against ${num(weekdayAvg)} on weekdays, ${signed(uplift)} — a restaurant platform's week, and the reason a Monday comparison against a Sunday means nothing.`}
      >
        <Card padded={false}>
          {/* header */}
          <div
            className="q-caption"
            style={{
              display: "flex", alignItems: "center", gap: 16, padding: "10px 16px",
              background: "var(--q-surface-1)", color: inkMuted,
              borderBottom: `1px solid ${hairline}`, fontWeight: 600,
            }}
          >
            <span style={{ width: 132, flexShrink: 0 }}>Day</span>
            <span style={{ flex: 1, minWidth: 80 }}>Orders against the peak day</span>
            <span style={{ width: 72, flexShrink: 0, textAlign: "right" }}>Orders</span>
            <span style={{ flex: 1, minWidth: 80 }}>GMV against the peak day</span>
            <span style={{ width: 148, flexShrink: 0, textAlign: "right" }}>GMV</span>
            <span style={{ width: 64, flexShrink: 0, textAlign: "right" }}>Trading</span>
          </div>

          {rows.map((r) => {
            const wknd = isWeekend(r.date);
            return (
              <div
                key={r.date}
                style={{
                  display: "flex", alignItems: "center", gap: 16,
                  padding: "8px 16px", borderBottom: `1px solid ${hairline}`,
                }}
              >
                <span
                  className={wknd ? "q-emphasis q-tnum" : "q-body-sm q-tnum"}
                  style={{ width: 132, flexShrink: 0, color: wknd ? ink : inkMuted }}
                >
                  {dow(r.date)} {day(`${r.date}T00:00:00`)}
                </span>

                <span style={{ flex: 1, minWidth: 80 }}>
                  <Bar ratio={r.orders / maxOrders} strong={wknd} />
                </span>
                <span className="q-body-sm q-tnum" style={{ width: 72, flexShrink: 0, textAlign: "right", color: ink }}>
                  {num(r.orders)}
                </span>

                <span style={{ flex: 1, minWidth: 80 }}>
                  <Bar ratio={r.gmvMinor / maxGmv} strong={wknd} />
                </span>
                <span className="q-body-sm q-tnum" style={{ width: 148, flexShrink: 0, textAlign: "right", color: ink }}>
                  {uzs(r.gmvMinor)}
                </span>

                <span className="q-body-sm q-tnum" style={{ width: 64, flexShrink: 0, textAlign: "right", color: inkMuted }}>
                  {r.activeTenants}
                </span>
              </div>
            );
          })}

          {/* legend, inside the frame so it travels with the chart */}
          <div
            className="q-caption"
            style={{ display: "flex", alignItems: "center", gap: 20, padding: "10px 16px", color: inkSubtle, flexWrap: "wrap" }}
          >
            <span style={{ display: "inline-flex", alignItems: "center", gap: 8 }}>
              <span style={{ width: 16, height: 8, background: ink }} /> Saturday and Sunday
            </span>
            <span style={{ display: "inline-flex", alignItems: "center", gap: 8 }}>
              <span style={{ width: 16, height: 8, background: inkSubtle }} /> Monday to Friday
            </span>
            <span>
              Trading: tenants with an open account that day. It falls from {tenantsHigh} to {tenantsLow} in
              this range, which is the Shirinliklar suspension.
            </span>
          </div>
        </Card>
      </Block>

      {/* ── the league ────────────────────────────────────────────────────*/}
      <Block
        title="Tenants by volume"
        meta="Last 30 days, against the 30 days before it"
        note="Orders and GMV here sum exactly to the last-30-day headline figures below, because these three are every tenant that traded. The change figure is stored against a fixed 30-day comparison and does not follow the range selector."
      >
        {/* The one thing on this screen nobody may scroll past. It is stated in
          * words above the table as well as coloured inside it, because a red
          * cell is a decoration until someone tells you what it costs. */}
        <div
          style={{
            display: "flex", gap: 12, alignItems: "flex-start",
            padding: "12px 16px", background: "var(--q-error-tint)",
            border: `1px solid ${hairline}`, borderLeft: "3px solid var(--q-error)",
            marginBottom: 12,
          }}
        >
          <div style={{ flexShrink: 0, paddingTop: 1 }}>
            <StatusPill tone="failed">{signed(worst.changePct)}</StatusPill>
          </div>
          <div style={{ minWidth: 0 }}>
            <div className="q-emphasis" style={{ color: "var(--q-error-text)" }}>
              {worst.tenant} has lost {Math.abs(worst.changePct)}% of its order volume
            </div>
            <div className="q-body-sm" style={{ color: ink, marginTop: 2 }}>
              {worstTenant?.healthNote ?? "Order volume is down against the previous 30 days"}. It is the only
              tenant in the league losing volume, it went live on {day(worstTenant?.joinedAt)}, and it is on the{" "}
              {worst.plan ?? worstTenant?.plan} plan — a fall this size in a first quarter is a churn risk, not a
              quiet month.
            </div>
          </div>
        </div>

        <DataTable
          columns={[
            {
              key: "rank", label: "#", align: "right",
              render: (v) => <span style={{ color: inkSubtle }}>{v}</span>,
            },
            {
              key: "tenant", label: "Tenant",
              render: (v, r) => (
                <div style={{ minWidth: 0 }}>
                  <div className="q-emphasis" style={{ color: ink }}>{v}</div>
                  <div className="q-caption" style={{ color: inkSubtle, marginTop: 2 }}>
                    {r.plan} · {r.city}
                  </div>
                </div>
              ),
            },
            { key: "orders", label: "Orders", align: "right", render: (v) => num(v) },
            {
              key: "gmvMinor", label: "Share of GMV",
              render: (v) => {
                const pct = (v / leagueGmv) * 100;
                return (
                  <span style={{ display: "inline-flex", alignItems: "center", gap: 8 }}>
                    <span style={{ width: 88, height: 8, background: "var(--q-surface-1)", flexShrink: 0 }}>
                      <span style={{ display: "block", width: `${pct.toFixed(1)}%`, height: "100%", background: ink }} />
                    </span>
                    <span className="q-tnum" style={{ color: inkMuted }}>{pct.toFixed(1)}%</span>
                  </span>
                );
              },
            },
            { key: "gmv", label: "GMV", align: "right", render: (_v, r) => uzs(r.gmvMinor) },
            { key: "avgBasketMinor", label: "Average basket", align: "right", render: (v) => uzs(v) },
            {
              key: "changePct", label: "Change vs previous 30 days", align: "right",
              render: (v) => (
                <span style={{ display: "inline-flex", flexDirection: "column", alignItems: "flex-end", gap: 6 }}>
                  <StatusPill tone={v <= -20 ? "failed" : v < 0 ? "degraded" : "healthy"}>{signed(v)}</StatusPill>
                  <ChangeBar pct={v} scale={changeScale} />
                </span>
              ),
            },
            {
              key: "health", label: "Health",
              render: (v, r) => (
                <div style={{ minWidth: 0 }}>
                  <StatusPill tone={HEALTH_TONE[v] ?? "neutral"}>{v === "at-risk" ? "At risk" : "Healthy"}</StatusPill>
                  {r.note ? (
                    <div className="q-caption" style={{ color: "var(--q-error-text)", marginTop: 4, maxWidth: 220 }}>
                      {r.note}
                    </div>
                  ) : null}
                </div>
              ),
            },
          ]}
          rows={league}
        />
      </Block>

      {/* ── headline ──────────────────────────────────────────────────────
        * Last, not first. These are fixed-window scalars from KPIS; putting
        * them under the range control would imply the control moves them. */}
      <Block
        title="Headline figures"
        meta="Fixed windows — these do not follow the range selector"
        note="Orders and GMV here cover a full 30 days and will not equal the sum of the bars above, which cover the days the fixture holds. MRR is the recurring figure from the platform ledger rather than a re-derivation of the subscription list; arrears is the sum of the two overdue Shirinliklar invoices."
      >
        <div style={{ display: "grid", gridTemplateColumns: "repeat(4, minmax(0, 1fr))", gap: 16 }}>
          <KpiTile label="Tenants live" value={KPIS.tenantsLive} meta="Trading today" />
          <KpiTile label="Tenants onboarding" value={KPIS.tenantsOnboarding} meta="Not yet contributing volume" />
          <KpiTile label="Tenants at risk" value={KPIS.tenantsAtRisk} meta="One suspended, one losing volume" />
          <KpiTile label="Churned, last 90 days" value={KPIS.churnedLast90} meta="Tuz, closed 30.07.2026" />
          <KpiTile label="MRR" value={uzs(KPIS.mrrMinor)} meta="Recurring, this month" />
          <KpiTile label="Arrears" value={uzs(KPIS.arrearsMinor)} meta="Two invoices, 62 and 93 days overdue" />
          <KpiTile label="Orders, last 30 days" value={num(KPIS.ordersLast30)} meta="Across three trading tenants" />
          <KpiTile
            label="GMV, last 30 days"
            value={uzs(KPIS.gmvLast30Minor)}
            meta={`Average basket ${uzs(Math.round(KPIS.gmvLast30Minor / KPIS.ordersLast30))}`}
          />
        </div>
      </Block>

      {/* ── what is not here ──────────────────────────────────────────────*/}
      <Block
        title="Not in this prototype"
        meta="Named rather than mocked"
        note="A convincing chart for something nobody instrumented gets believed, quoted in a meeting, and then built into a plan. These stay listed until there is data behind them."
      >
        <Card padded={false}>
          {ABSENT.map(([label, why]) => (
            <div
              key={label}
              style={{
                display: "flex", gap: 16, alignItems: "flex-start",
                padding: "12px 16px", borderBottom: `1px solid ${hairline}`,
              }}
            >
              <div style={{ minWidth: 0, flex: 1 }}>
                <div className="q-body-sm" style={{ color: ink }}>{label}</div>
                <div className="q-caption" style={{ color: inkSubtle, marginTop: 2 }}>{why}</div>
              </div>
              <div style={{ flexShrink: 0, paddingTop: 1 }}>
                <StatusPill tone="neutral">No data</StatusPill>
              </div>
            </div>
          ))}
        </Card>
      </Block>
    </div>
  );
}

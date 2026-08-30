/* Today — the glance screen.
 *
 * The reader is a manager who has looked up from something else for four
 * seconds. That budget dictates every decision here:
 *
 *   · Nothing scrolls. A dashboard that hides its bad news below the fold has
 *     already failed the person it was built for.
 *   · Lateness is stated three times in the same row — a dot on the order
 *     number, the promise time in error ink, and a pill saying how many minutes
 *     over. One glance from any column lands on it.
 *   · The metrics band is a band, not a wall of cards. Money tiles get two grid
 *     columns because "7 412 000 so'm" at display size does not fit in one, and
 *     a wrapped number is a number nobody reads.
 *
 * The queue is the point of the screen; the numbers above it are context for
 * the queue, which is why the table gets the room and the tiles get 16px.
 */

import { TODAY, HOURLY, ORDERS, COURIERS, NOW } from "./data";
import {
  Card, SectionHeader, DataTable, StatusPill, Button,
  uzs, dt, day, ink, inkMuted, inkSubtle, hairline,
} from "./components";

/* ── local primitives ──────────────────────────────────────────────────────
 * KpiTile exists in components.jsx but has no notion of tone, and a late count
 * that renders in the same ink as the cancelled count is not a warning. Rather
 * than edit the shared file — other screens depend on it — this screen carries
 * its own tile, identical apart from the tone and the column span.
 */
function Metric({ label, value, meta, tone, span = 1 }) {
  const valueColor =
    tone === "error" ? "var(--q-error-text)" :
    tone === "warning" ? "var(--q-warning-text)" : ink;

  return (
    <Card style={{ padding: 16, gridColumn: `span ${span}`, minWidth: 0 }}>
      <div className="q-caption" style={{ color: inkMuted, whiteSpace: "nowrap" }}>{label}</div>
      <div className="q-data-lg" style={{ color: valueColor, marginTop: 4, whiteSpace: "nowrap" }}>
        {value}
      </div>
      {meta ? (
        <div className="q-caption" style={{ color: inkSubtle, marginTop: 2, whiteSpace: "nowrap" }}>
          {meta}
        </div>
      ) : null}
    </Card>
  );
}

/* Bars are divs. A chart library in a prototype buys a tooltip nobody asked for
 * and a rounded corner somebody has to remove. */
function HourlyBars({ rows, currentHour }) {
  const peak = Math.max(...rows.map((r) => r.orders));

  return (
    <div style={{ display: "flex", alignItems: "flex-end", gap: 12, height: 108 }}>
      {rows.map((r) => {
        const now = r.hour === currentHour;
        return (
          <div
            key={r.hour}
            style={{
              flex: 1, minWidth: 0, maxWidth: 72,
              display: "flex", flexDirection: "column", justifyContent: "flex-end", height: "100%",
            }}
          >
            <div
              className="q-caption q-tnum"
              style={{ color: now ? ink : inkSubtle, textAlign: "center", marginBottom: 4 }}
            >
              {r.orders}
            </div>
            <div
              style={{
                /* The current hour is the darkest bar, not the blue one. Blue is
                   spent on the primary action, and a chart is not an action. */
                height: `${Math.round((r.orders / peak) * 100)}%`,
                background: now ? ink : "var(--q-surface-2)",
                minHeight: 2,
              }}
            />
          </div>
        );
      })}
    </div>
  );
}

function HourlyAxis({ rows, currentHour }) {
  return (
    <div style={{ display: "flex", gap: 12, borderTop: `1px solid ${hairline}`, paddingTop: 6 }}>
      {rows.map((r) => (
        <div
          key={r.hour}
          className="q-caption q-tnum"
          style={{
            flex: 1, minWidth: 0, maxWidth: 72, textAlign: "center",
            color: r.hour === currentHour ? ink : inkSubtle,
          }}
        >
          {r.hour}
        </div>
      ))}
    </div>
  );
}

/* ── status vocabulary ─────────────────────────────────────────────────────
 * Sentence case, and a tone per state rather than a tone per lateness. An order
 * that is PREPARING and 41 minutes over is still preparing; the lateness is
 * carried by its own column, which is exactly why the fixture models it that way.
 */
const STATUS = {
  PLACED:     { tone: "info",    label: "Placed" },
  ACCEPTED:   { tone: "info",    label: "Accepted" },
  PREPARING:  { tone: "pending", label: "Preparing" },
  READY:      { tone: "healthy", label: "Ready" },
  DISPATCHED: { tone: "info",    label: "Dispatched" },
};

const sentence = (s) => s.charAt(0) + s.slice(1).toLowerCase();

const OPEN = ORDERS
  .filter((o) => !["DELIVERED", "CANCELLED"].includes(o.status))
  .sort((a, b) => new Date(a.promisedAt || NOW) - new Date(b.promisedAt || NOW));

const courierName = (id) => COURIERS.find((c) => c.id === id)?.name || null;

export default function Today({ onNavigate, onOpenOrder }) {
  const peakHour = HOURLY.reduce((a, b) => (b.orders > a.orders ? b : a));
  const currentHour = "19";
  const worstLate = Math.max(0, ...OPEN.map((o) => o.lateBy || 0));
  const cancelShare = ((TODAY.cancelledCount / TODAY.ordersTotal) * 100).toFixed(1);

  const columns = [
    {
      key: "shortId", label: "Order",
      render: (v, row) => (
        <span style={{ display: "inline-flex", alignItems: "center", gap: 8 }}>
          {row.lateBy ? (
            <span
              style={{
                width: 6, height: 6, borderRadius: "50%",
                background: "var(--q-error)", flexShrink: 0,
              }}
            />
          ) : (
            <span style={{ width: 6, flexShrink: 0 }} />
          )}
          <span className="q-emphasis q-tnum">{v}</span>
        </span>
      ),
    },
    {
      key: "promisedAt", label: "Promised",
      render: (v, row) => (
        <span
          className={row.lateBy ? "q-emphasis q-tnum" : "q-tnum"}
          style={{ color: row.lateBy ? "var(--q-error-text)" : ink, whiteSpace: "nowrap" }}
        >
          {v ? dt(v) : "—"}
        </span>
      ),
    },
    {
      key: "lateBy", label: "Late",
      render: (v) =>
        v ? (
          <StatusPill tone="failed">{v} min over</StatusPill>
        ) : (
          <span style={{ color: inkSubtle }}>On promise</span>
        ),
    },
    {
      key: "status", label: "Status",
      render: (v) => <StatusPill tone={STATUS[v].tone}>{STATUS[v].label}</StatusPill>,
    },
    { key: "customerName", label: "Customer" },
    {
      key: "addressLine", label: "Destination",
      render: (v, row) => (
        <span
          style={{
            display: "block", maxWidth: 260, overflow: "hidden",
            textOverflow: "ellipsis", whiteSpace: "nowrap",
            color: v ? ink : inkMuted,
          }}
          title={v || "Pickup at counter"}
        >
          {v || "Pickup at counter"}
        </span>
      ),
    },
    {
      key: "lines", label: "Items", align: "right",
      render: (v) => v.reduce((n, l) => n + l.qty, 0),
    },
    { key: "totalMinor", label: "Total", align: "right", render: (v) => uzs(v) },
    {
      key: "payment", label: "Payment",
      render: (v, row) => (
        <span style={{ whiteSpace: "nowrap" }}>
          {sentence(v)}
          <span style={{ color: row.paid ? inkSubtle : "var(--q-warning-text)" }}>
            {row.paid ? " · paid" : " · unpaid"}
          </span>
        </span>
      ),
    },
    {
      key: "courierId", label: "Courier",
      render: (v, row) => {
        if (row.fulfilment === "PICKUP") return <span style={{ color: inkSubtle }}>—</span>;
        const name = courierName(v);
        return name || <span style={{ color: "var(--q-warning-text)" }}>Unassigned</span>;
      },
    },
  ];

  return (
    <div>
      <SectionHeader
        title="Today"
        description={`${TODAY.brand} · ${TODAY.location} · ${day(NOW)}. Service in progress.`}
        right={
          <div style={{ display: "flex", gap: 8 }}>
            <Button variant="ghost" size="sm" onClick={() => onNavigate("kitchen")}>
              Kitchen board
            </Button>
            <Button variant="tertiary" size="sm" onClick={() => onNavigate("orders")}>
              All orders
            </Button>
          </div>
        }
      />

      {/* ── the band ────────────────────────────────────────────────────────
          Nine columns, not seven tiles: the two money figures need double width
          at 28px, and giving it to them by span keeps every tile on one row. */}
      <div
        style={{
          display: "grid",
          gridTemplateColumns: "repeat(9, minmax(0, 1fr))",
          gap: 8,
          marginBottom: 16,
        }}
      >
        <Metric label="Orders so far" value={TODAY.ordersTotal} meta={`Since ${HOURLY[0].hour}:00`} />
        <Metric label="Open now" value={TODAY.ordersOpen} meta="In the queue below" />
        <Metric
          label="Late"
          value={TODAY.ordersLate}
          tone={TODAY.ordersLate ? "error" : undefined}
          meta={worstLate ? `Worst ${worstLate} min over` : "All on promise"}
        />
        <Metric label="Revenue" value={uzs(TODAY.revenueMinor)} meta="All channels" span={2} />
        <Metric label="Average basket" value={uzs(TODAY.averageBasketMinor)} meta="Per order" span={2} />
        <Metric label="Average prep" value={`${TODAY.averagePrepMinutes} min`} meta="Fired to ready" />
        <Metric
          label="Cancelled"
          value={TODAY.cancelledCount}
          tone={TODAY.cancelledCount ? "warning" : undefined}
          meta={`${cancelShare}% of orders`}
        />
      </div>

      {/* ── the shape of the evening ───────────────────────────────────────*/}
      <Card style={{ padding: 16, marginBottom: 16 }}>
        <div style={{ display: "flex", alignItems: "baseline", gap: 16, marginBottom: 12 }}>
          <span className="q-emphasis" style={{ color: ink }}>Orders by hour</span>
          <span className="q-caption" style={{ marginLeft: "auto", color: inkMuted }}>
            {peakHour.hour === currentHour
              ? `${currentHour}:00 running · busiest hour so far, ${peakHour.orders} orders`
              : `Peak ${peakHour.hour}:00, ${peakHour.orders} orders · ${currentHour}:00 running`}
          </span>
        </div>
        <HourlyBars rows={HOURLY} currentHour={currentHour} />
        <HourlyAxis rows={HOURLY} currentHour={currentHour} />
      </Card>

      {/* ── the live queue ─────────────────────────────────────────────────*/}
      <div style={{ display: "flex", alignItems: "baseline", gap: 12, marginBottom: 8 }}>
        <span className="q-emphasis" style={{ color: ink }}>Live queue</span>
        <span className="q-caption" style={{ color: inkMuted }}>
          {OPEN.length} open · {OPEN.filter((o) => o.lateBy).length} late · soonest promise first
        </span>
        <span className="q-caption" style={{ marginLeft: "auto", color: inkSubtle }}>
          Select a row to open the order
        </span>
      </div>

      <DataTable
        columns={columns}
        rows={OPEN}
        onRowClick={(row) => onOpenOrder(row.id)}
      />
    </div>
  );
}

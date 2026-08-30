/* Delivery — dispatch, not tracking.
 *
 * The job on this screen is one question asked twenty times an evening: who is
 * carrying what, and which drop has gone wrong. So the screen is ordered by how
 * badly it needs a human:
 *
 *   1. the worst late delivery, with the reassign control inside the alert
 *   2. the couriers, because assignment is impossible without knowing the load
 *   3. every delivery in flight, with assignment as an in-row control
 *
 * Assignment is a native <select> sitting in the row rather than a dialog. Two
 * reasons, both practical: a dispatcher does this mid-phone-call and a modal
 * costs two extra actions, and a native listbox escapes the table's horizontal
 * scroll container, which an absolutely-positioned popover would be clipped by.
 *
 * There is no map. See the note under the deliveries table — a drawn-on-fake
 * map would validate nothing and would imply a courier-position feed that does
 * not exist yet.
 */

import { useState } from "react";
import { COURIERS, ORDERS, NOW } from "./data";
import {
  Button, DataTable, EmptyState, FilterBar, KpiTile, SectionHeader, Select, StatusPill,
  canvas, hairline, ink, inkMuted, inkSubtle, surface1, dt, uzs,
} from "./components";

/* ── local helpers ─────────────────────────────────────────────────────────*/

const ACTIVE_DELIVERY = ["PLACED", "ACCEPTED", "PREPARING", "READY", "DISPATCHED"];
const CAPACITY = 3; /* orders a courier carries before dispatch stops stacking */

const minutesBetween = (a, b) => Math.round((new Date(b) - new Date(a)) / 60000);
const hoursMinutes = (m) => `${Math.floor(m / 60)}h ${String(m % 60).padStart(2, "0")}m`;

const COURIER_STATUS = {
  AVAILABLE:   { tone: "healthy", label: "Available" },
  ON_DELIVERY: { tone: "info",    label: "On delivery" },
  OFF_SHIFT:   { tone: "neutral", label: "Off shift" },
};

const ORDER_STATUS = {
  PLACED:     { tone: "neutral", label: "Placed" },
  ACCEPTED:   { tone: "info",    label: "Accepted" },
  PREPARING:  { tone: "pending", label: "Preparing" },
  READY:      { tone: "healthy", label: "Ready" },
  DISPATCHED: { tone: "info",    label: "Out" },
};

/* A block heading. SectionHeader is the page's, so the sub-blocks need their
 * own quieter one rather than a second page title. */
function Block({ title, description, right, children }) {
  return (
    <section style={{ marginTop: 32 }}>
      <div style={{ display: "flex", alignItems: "flex-end", gap: 16, marginBottom: 12 }}>
        <div style={{ minWidth: 0 }}>
          <h2 className="q-subhead" style={{ margin: 0, color: ink }}>{title}</h2>
          {description ? (
            <p className="q-body-sm" style={{ margin: "2px 0 0", color: inkMuted }}>{description}</p>
          ) : null}
        </div>
        {right ? <div style={{ marginLeft: "auto", flexShrink: 0 }}>{right}</div> : null}
      </div>
      {children}
    </section>
  );
}

/* Load as three squares rather than a number alone — a dispatcher reads "full"
 * faster than they read "3". Plain divs; no chart library in a prototype. */
function LoadBar({ load }) {
  return (
    <div style={{ display: "flex", gap: 2, marginTop: 4, justifyContent: "flex-end" }}>
      {Array.from({ length: CAPACITY }).map((_, i) => (
        <span
          key={i}
          style={{
            width: 12, height: 4,
            background: i < load
              ? (load >= CAPACITY ? "var(--q-warning)" : ink)
              : "var(--q-surface-2)",
          }}
        />
      ))}
    </div>
  );
}

/* The assign control. Grouped by whether the courier can take the job now;
 * off-shift couriers stay in the list but disabled, because "where is Shoxrux"
 * is a question the list should answer rather than dodge. */
function CourierSelect({ value, onChange, load, onCancel }) {
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 8 }} onClick={(e) => e.stopPropagation()}>
      <select
        value={value || ""}
        className="q-body-sm"
        onChange={(e) => onChange(e.target.value)}
        style={{
          height: 32, minWidth: 200, padding: "0 8px", background: canvas, color: ink,
          border: `1px solid ${hairline}`, borderRadius: "var(--q-radius)",
        }}
      >
        <option value="">Assign a courier</option>
        <optgroup label="Available now">
          {COURIERS.filter((c) => c.status === "AVAILABLE").map((c) => (
            <option key={c.id} value={c.id}>
              {c.name} — {c.vehicle.toLowerCase()}, {c.todayDeliveries} today
            </option>
          ))}
        </optgroup>
        <optgroup label="On shift, already carrying">
          {COURIERS.filter((c) => c.status === "ON_DELIVERY").map((c) => (
            <option key={c.id} value={c.id} disabled={load(c.id) >= CAPACITY}>
              {c.name} — {load(c.id)} of {CAPACITY} on board
            </option>
          ))}
        </optgroup>
        <optgroup label="Cannot be assigned">
          {COURIERS.filter((c) => c.status === "OFF_SHIFT").map((c) => (
            <option key={c.id} value={c.id} disabled>
              {c.name} — off shift
            </option>
          ))}
        </optgroup>
      </select>
      {onCancel ? (
        <button
          type="button"
          onClick={onCancel}
          className="q-caption"
          style={{ background: "transparent", border: "none", color: inkMuted, cursor: "pointer", padding: 0 }}
        >
          Cancel
        </button>
      ) : null}
    </div>
  );
}

/* ── screen ────────────────────────────────────────────────────────────────*/

export default function Delivery({ onOpenOrder }) {
  /* Prototype-local assignment. Fixtures are never mutated; this overlays them,
   * so a reload puts the evening back the way the fixture describes it. */
  const [assigned, setAssigned] = useState({});
  const [changing, setChanging] = useState(null);   /* order id whose cell is in edit mode */
  const [stage, setStage] = useState("all");
  const [courierId, setCourierId] = useState(null); /* row-selected courier filter */

  const courierOf = (o) => (o.id in assigned ? assigned[o.id] : o.courierId);
  const courierById = (id) => COURIERS.find((c) => c.id === id) || null;

  const deliveries = ORDERS
    .filter((o) => o.fulfilment === "DELIVERY" && ACTIVE_DELIVERY.includes(o.status));

  /* Fixture load plus anything assigned here, so the couriers table and the
   * select agree with each other the moment a drop is handed out. */
  const extraFor = (id) =>
    deliveries.filter((o) => o.id in assigned && assigned[o.id] === id && o.courierId !== id).length;
  const loadFor = (id) => (courierById(id)?.activeOrders ?? 0) + extraFor(id);

  const assign = (orderId, value) => {
    setAssigned((prev) => ({ ...prev, [orderId]: value || null }));
    setChanging(null);
  };

  const unassignedCount = deliveries.filter((o) => !courierOf(o) && o.status !== "DISPATCHED").length;
  const outCount = deliveries.filter((o) => o.status === "DISPATCHED").length;
  const availableCount = COURIERS.filter((c) => c.status === "AVAILABLE" && loadFor(c.id) === 0).length;
  const onShiftCount = COURIERS.filter((c) => c.status !== "OFF_SHIFT").length;
  const deliveredToday = COURIERS.reduce((n, c) => n + c.todayDeliveries, 0);

  const late = deliveries.filter((o) => o.lateBy).sort((a, b) => b.lateBy - a.lateBy);
  const worst = late[0] || null;
  const alsoLate = late.slice(1);

  const rows = deliveries
    .filter((o) => (stage === "unassigned" ? !courierOf(o) && o.status !== "DISPATCHED" : true))
    .filter((o) => (stage === "out" ? o.status === "DISPATCHED" : true))
    .filter((o) => (courierId ? courierOf(o) === courierId : true))
    .sort((a, b) =>
      (b.lateBy || 0) - (a.lateBy || 0) ||
      (courierOf(a) ? 1 : 0) - (courierOf(b) ? 1 : 0) ||
      new Date(a.promisedAt) - new Date(b.promisedAt),
    );

  const selectedCourier = courierById(courierId);

  /* ── couriers table ────────────────────────────────────────────────────*/

  const off = (c) => c.status === "OFF_SHIFT";
  const muted = (c, node) => (
    <span style={{ color: off(c) ? inkSubtle : undefined }}>{node}</span>
  );

  const courierColumns = [
    {
      key: "name", label: "Courier",
      render: (v, c) => (
        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <span
            style={{
              width: 8, height: 8, borderRadius: "50%", flexShrink: 0,
              background: off(c) ? "var(--q-surface-2)"
                : c.status === "AVAILABLE" ? "var(--q-success)" : "var(--q-ink-subtle)",
            }}
          />
          <span className={off(c) ? "q-body-sm" : "q-emphasis"} style={{ color: off(c) ? inkSubtle : ink }}>
            {v}
          </span>
        </div>
      ),
    },
    {
      key: "phone", label: "Phone",
      render: (v, c) => (
        <span style={{ fontFamily: "var(--q-font-mono)", color: off(c) ? inkSubtle : ink }}>{v}</span>
      ),
    },
    { key: "vehicle", label: "Vehicle", render: (v, c) => muted(c, v) },
    {
      key: "status", label: "Status",
      render: (v) => <StatusPill tone={COURIER_STATUS[v].tone}>{COURIER_STATUS[v].label}</StatusPill>,
    },
    {
      key: "activeOrders", label: "On board", align: "right",
      render: (_, c) => {
        const load = off(c) ? 0 : loadFor(c.id);
        return (
          <div>
            <span style={{ color: off(c) ? inkSubtle : ink }}>{off(c) ? "—" : load}</span>
            {off(c) ? null : <LoadBar load={load} />}
            {extraFor(c.id) ? (
              <div className="q-caption" style={{ color: inkSubtle, marginTop: 2 }}>
                {extraFor(c.id)} assigned here
              </div>
            ) : null}
          </div>
        );
      },
    },
    {
      key: "shiftStart", label: "Shift start",
      render: (v, c) =>
        v ? (
          <div>
            <div className="q-tnum" style={{ color: ink }}>{dt(v)}</div>
            <div className="q-caption" style={{ color: inkSubtle, marginTop: 2 }}>
              {hoursMinutes(minutesBetween(v, NOW))} on shift
            </div>
          </div>
        ) : (
          <div>
            <div style={{ color: inkSubtle }}>—</div>
            <div className="q-caption" style={{ color: inkSubtle, marginTop: 2 }}>Not rostered today</div>
          </div>
        ),
    },
    {
      key: "todayDeliveries", label: "Delivered today", align: "right",
      render: (v, c) => <span style={{ color: off(c) ? inkSubtle : ink }}>{off(c) ? "—" : v}</span>,
    },
  ];

  /* ── deliveries table ──────────────────────────────────────────────────*/

  const deliveryColumns = [
    {
      key: "shortId", label: "Order",
      render: (v, o) => (
        <div>
          <div className="q-emphasis q-tnum" style={{ color: ink }}>{v}</div>
          <div className="q-caption" style={{ color: inkSubtle, marginTop: 2 }}>{o.channel}</div>
        </div>
      ),
    },
    {
      key: "status", label: "Stage",
      render: (v) => <StatusPill tone={ORDER_STATUS[v].tone}>{ORDER_STATUS[v].label}</StatusPill>,
    },
    {
      key: "lateBy", label: "Timing",
      render: (v, o) =>
        v ? (
          <StatusPill tone="failed">{v} min late</StatusPill>
        ) : (
          <span className="q-caption" style={{ color: inkSubtle }}>
            {Math.max(0, minutesBetween(NOW, o.promisedAt))} min of slack
          </span>
        ),
    },
    {
      key: "customerName", label: "Customer",
      render: (v, o) => (
        <div style={{ maxWidth: 180 }}>
          <div style={{ color: ink }}>{v}</div>
          <div className="q-caption" style={{ color: inkSubtle, marginTop: 2, fontFamily: "var(--q-font-mono)" }}>
            {o.customerPhone}
          </div>
        </div>
      ),
    },
    {
      key: "addressLine", label: "Address",
      render: (v, o) => (
        <div style={{ maxWidth: 220 }}>
          <div style={{ color: ink }}>{v}</div>
          {o.note ? (
            <div className="q-caption" style={{ color: "var(--q-warning-text)", marginTop: 2 }}>{o.note}</div>
          ) : null}
        </div>
      ),
    },
    {
      key: "promisedAt", label: "Promised",
      render: (v, o) => (
        <div>
          <div className="q-tnum" style={{ color: ink }}>{dt(v)}</div>
          <div className="q-caption q-tnum" style={{ color: inkSubtle, marginTop: 2 }}>
            placed {dt(o.placedAt).slice(6)}
          </div>
        </div>
      ),
    },
    {
      key: "courierId", label: "Courier",
      render: (_, o) => {
        const id = courierOf(o);
        const c = courierById(id);
        if (!c || changing === o.id) {
          return (
            <CourierSelect
              value={id}
              load={loadFor}
              onChange={(v) => assign(o.id, v)}
              onCancel={changing === o.id ? () => setChanging(null) : null}
            />
          );
        }
        return (
          <div>
            <div style={{ color: ink }}>{c.name}</div>
            <div className="q-caption" style={{ color: inkSubtle, marginTop: 2 }}>
              {c.vehicle.toLowerCase()}
              {o.id in assigned ? " · assigned just now" : ""}
              {" · "}
              <button
                type="button"
                onClick={(e) => { e.stopPropagation(); setChanging(o.id); }}
                className="q-caption"
                style={{ background: "transparent", border: "none", color: "var(--q-primary)", cursor: "pointer", padding: 0 }}
              >
                Change
              </button>
            </div>
          </div>
        );
      },
    },
    {
      key: "payment", label: "Payment",
      render: (v, o) => (
        <div>
          <div style={{ color: ink }}>{v}</div>
          <div
            className="q-caption"
            style={{ color: o.paid ? inkSubtle : "var(--q-warning-text)", marginTop: 2 }}
          >
            {o.paid ? "Paid" : "Collect on delivery"}
          </div>
        </div>
      ),
    },
    { key: "totalMinor", label: "Total", align: "right", render: (v) => uzs(v) },
  ];

  return (
    <div>
      <SectionHeader
        title="Delivery"
        description="Who is on shift, what they are carrying, and every delivery that is out or still waiting for a courier."
        right={
          <span className="q-caption" style={{ color: inkMuted }}>
            {availableCount} available · {onShiftCount} on shift · {outCount} out
          </span>
        }
      />

      {/* The late drop, ahead of everything else. It carries its own reassign
          control so the fix does not require finding the row first. */}
      {worst ? (
        <div
          style={{
            display: "flex", gap: 24, alignItems: "flex-start", flexWrap: "wrap",
            background: "var(--q-error-tint)", border: `1px solid ${hairline}`,
            borderLeft: "3px solid var(--q-error)", padding: 20,
          }}
        >
          <div style={{ minWidth: 120 }}>
            <div className="q-data-lg" style={{ color: "var(--q-error-text)" }}>{worst.lateBy} min</div>
            <div className="q-caption" style={{ color: "var(--q-error-text)", marginTop: 2 }}>
              past the promise
            </div>
          </div>

          <div style={{ flex: 1, minWidth: 320 }}>
            <div className="q-subhead" style={{ color: ink }}>
              Order {worst.shortId} is late and {courierOf(worst) ? "still with the courier" : "has no courier"}
            </div>
            <div className="q-body-sm" style={{ color: inkMuted, marginTop: 4 }}>
              {worst.lateReason}. Promised {dt(worst.promisedAt)} to {worst.customerName},{" "}
              {worst.addressLine}. {uzs(worst.totalMinor)}, {worst.paid ? "already paid" : "cash to collect"}.
            </div>

            <div style={{ display: "flex", gap: 24, flexWrap: "wrap", marginTop: 12 }}>
              <div>
                <div className="q-caption" style={{ color: inkSubtle }}>Courier</div>
                <div className="q-body-sm" style={{ color: ink, marginTop: 2 }}>
                  {courierById(courierOf(worst))?.name || "None"}
                </div>
              </div>
              <div>
                <div className="q-caption" style={{ color: inkSubtle }}>Courier phone</div>
                <div className="q-body-sm" style={{ color: ink, marginTop: 2, fontFamily: "var(--q-font-mono)" }}>
                  {courierById(courierOf(worst))?.phone || "—"}
                </div>
              </div>
              <div>
                <div className="q-caption" style={{ color: inkSubtle }}>Customer phone</div>
                <div className="q-body-sm" style={{ color: ink, marginTop: 2, fontFamily: "var(--q-font-mono)" }}>
                  {worst.customerPhone}
                </div>
              </div>
              <div>
                <div className="q-caption" style={{ color: inkSubtle }}>
                  {courierOf(worst) ? "Hand over to" : "Assign to"}
                </div>
                <div style={{ marginTop: 2 }}>
                  <CourierSelect value={courierOf(worst)} load={loadFor} onChange={(v) => assign(worst.id, v)} />
                </div>
              </div>
            </div>

            {alsoLate.length ? (
              <div className="q-caption" style={{ color: "var(--q-error-text)", marginTop: 12 }}>
                Also late:{" "}
                {alsoLate.map((o, i) => (
                  <span key={o.id}>
                    {i ? ", " : ""}
                    {o.shortId} by {o.lateBy} min
                    {courierOf(o) ? "" : ", still needs a courier"}
                  </span>
                ))}
              </div>
            ) : null}
          </div>

          <Button onClick={() => onOpenOrder(worst.id)}>Open order {worst.shortId}</Button>
        </div>
      ) : null}

      <Block
        title="Couriers"
        description="Select a row to see only that courier's deliveries. Off-shift couriers stay listed so dispatch knows why they are absent."
      >
        <DataTable
          columns={courierColumns}
          rows={COURIERS}
          selectedId={courierId}
          onRowClick={(c) => setCourierId(courierId === c.id ? null : c.id)}
        />
      </Block>

      <Block
        title="Deliveries in flight"
        description="Rows open the order. Assigning happens in the row, without leaving the queue."
      >
        <FilterBar>
          <Select
            label="Show"
            value={stage}
            onChange={setStage}
            options={[
              { value: "all", label: "Everything active" },
              { value: "unassigned", label: "Needs a courier" },
              { value: "out", label: "Out for delivery" },
            ]}
          />
          <span className="q-caption" style={{ color: inkMuted }}>
            {unassignedCount} waiting on a courier · {outCount} on the road
          </span>
          {selectedCourier ? (
            <span style={{ marginLeft: "auto", display: "inline-flex", alignItems: "center", gap: 8 }}>
              <span className="q-caption" style={{ color: inkMuted }}>
                Filtered to {selectedCourier.name}
              </span>
              <Button variant="ghost" size="sm" onClick={() => setCourierId(null)}>Clear</Button>
            </span>
          ) : null}
        </FilterBar>

        <DataTable
          columns={deliveryColumns}
          rows={rows}
          onRowClick={(o) => onOpenOrder(o.id)}
          empty={
            <EmptyState
              title="No deliveries match this filter"
              description="Change the stage filter, or clear the courier selection above."
            />
          }
        />

        {/* Said plainly rather than drawn badly. */}
        <div
          style={{
            background: surface1, border: `1px solid ${hairline}`, borderTop: "none", padding: "12px 16px",
          }}
        >
          <span className="q-emphasis" style={{ color: ink }}>Live tracking is not prototyped.</span>{" "}
          <span className="q-body-sm" style={{ color: inkMuted }}>
            There is no map, no courier position and no distance-based ETA on this screen. In production
            those come from a feed out of the courier app; here, everything above is derived from the order
            status and the assignment. Treat "out for delivery" as a claim, and phone the courier.
          </span>
        </div>
      </Block>

      <Block title="Delivery today">
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(180px, 1fr))", gap: 16 }}>
          <KpiTile label="Waiting on a courier" value={unassignedCount} meta="Delivery orders, no courier" />
          <KpiTile label="Out for delivery" value={outCount} meta={`${late.length} of them late`} />
          <KpiTile label="Couriers free" value={availableCount} meta={`Of ${onShiftCount} on shift`} />
          <KpiTile label="Delivered today" value={deliveredToday} meta={`Across ${onShiftCount} couriers`} />
          <KpiTile
            label="Longest shift"
            value={hoursMinutes(
              Math.max(...COURIERS.filter((c) => c.shiftStart).map((c) => minutesBetween(c.shiftStart, NOW))),
            )}
            meta="Consider a break"
          />
        </div>
      </Block>
    </div>
  );
}

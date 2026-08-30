/* Operations shell.
 *
 * This console belongs to one restaurant's staff during service. It is used
 * standing up, under time pressure, while a phone is ringing — which is the only
 * fact that matters for its design. Everything here is optimised for speed of
 * the common action, not for completeness of the rare one.
 *
 * Three consequences that shape the whole application:
 *
 * 1. **Taking an order is a first-class destination, not a button on a list.**
 *    It has its own rail entry and its own keyboard entry point, because on a
 *    busy evening it is the single most repeated task in the building.
 *
 * 2. **The queue is always visible.** An operator taking a new order must still
 *    be able to see that order 4819 has gone late. Nothing here is a full-screen
 *    modal that hides the queue behind it.
 *
 * 3. **The rail is grouped by the working day, not by the org chart.** Service
 *    first, then the people doing it, then the things changed on a Tuesday
 *    morning. Eleven flat entries is a list nobody reads.
 *
 * Same prototype pattern as the control plane: state hoisted here, navigation by
 * state, one file per section, and each section's fixtures beside it.
 */

import { useEffect, useState } from "react";
import { MENU_NAV, NAV_ITEMS, TODAY, ORDERS } from "./data";
import { inkMuted, hairline, canvas, blue } from "./components";

import Today from "./Today";
import Orders from "./Orders";
import NewOrder from "./NewOrder";
import Kitchen from "./Kitchen";
import Delivery from "./Delivery";
import Couriers from "./Couriers";
import Customers from "./Customers";
import Staff from "./Staff";
import Statistics from "./Statistics";
import Catalog from "./Catalog";
import Places from "./Places";
import Settings from "./Settings";

const RAIL = 208;
const TOPBAR = 48;

export default function App() {
  const [section, setSection] = useState("today");

  /* Orders */
  const [orderId, setOrderId] = useState(null);
  const [orderFilter, setOrderFilter] = useState("attention");
  const [orderSearch, setOrderSearch] = useState("");

  /* Taking an order. Held here rather than inside NewOrder so a half-built
   * basket survives an operator glancing at the queue and coming back — losing
   * a basket because someone checked whether 4819 shipped is unforgivable. */
  const [draft, setDraft] = useState(null);

  /* Kitchen */
  const [station, setStation] = useState("all");

  /* The sections added in the upgrade. Each keeps its selection and its tab up
   * here for the same reason the draft is here: leaving a courier's record to
   * check the board and coming back to the top of an unfiltered list is the kind
   * of small loss that makes people stop using a screen. */
  const [courierId, setCourierId] = useState(null);
  const [courierTab, setCourierTab] = useState("board");
  const [customerId, setCustomerId] = useState(null);
  const [staffId, setStaffId] = useState(null);
  const [staffTab, setStaffTab] = useState("people");
  const [statsTab, setStatsTab] = useState("overview");
  const [statsRange, setStatsRange] = useState("7d");
  const [catalogView, setCatalogView] = useState(null);
  const [productId, setProductId] = useState(null);
  const [locationId, setLocationId] = useState(null);
  const [placesTab, setPlacesTab] = useState("profile");
  const [settingsGroup, setSettingsGroup] = useState(null);

  const startOrder = () => {
    if (!draft) {
      setDraft({ lines: [], customer: null, address: null, fulfilment: "DELIVERY", payment: "CASH", note: "" });
    }
    setSection("new-order");
  };

  /* One shortcut, because an operator who has to reach for a mouse to start an
   * order will not use the shortcut at all. F2 is the till-key convention every
   * restaurant system in this market already uses. */
  useEffect(() => {
    const onKey = (e) => {
      if (e.key === "F2") {
        e.preventDefault();
        startOrder();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  const lateCount = ORDERS.filter((o) => o.lateBy && !["DELIVERED", "CANCELLED"].includes(o.status)).length;
  const openCount = ORDERS.filter((o) => !["DELIVERED", "CANCELLED"].includes(o.status)).length;

  const body = {
    today: <Today onNavigate={setSection} onOpenOrder={(id) => { setOrderId(id); setSection("orders"); }} />,
    orders: (
      <Orders
        orderId={orderId} setOrderId={setOrderId}
        filter={orderFilter} setFilter={setOrderFilter}
        search={orderSearch} setSearch={setOrderSearch}
        onNewOrder={startOrder}
      />
    ),
    "new-order": (
      <NewOrder
        draft={draft} setDraft={setDraft}
        onDone={() => { setDraft(null); setSection("orders"); }}
        onCancel={() => { setDraft(null); setSection("orders"); }}
      />
    ),
    kitchen: <Kitchen station={station} setStation={setStation} />,
    delivery: <Delivery onOpenOrder={(id) => { setOrderId(id); setSection("orders"); }} />,
    couriers: <Couriers courierId={courierId} setCourierId={setCourierId} tab={courierTab} setTab={setCourierTab} />,
    customers: <Customers customerId={customerId} setCustomerId={setCustomerId} />,
    staff: <Staff staffId={staffId} setStaffId={setStaffId} tab={staffTab} setTab={setStaffTab} />,
    statistics: <Statistics tab={statsTab} setTab={setStatsTab} range={statsRange} setRange={setStatsRange} />,
    catalog: <Catalog view={catalogView} setView={setCatalogView} productId={productId} setProductId={setProductId} />,
    places: <Places locationId={locationId} setLocationId={setLocationId} tab={placesTab} setTab={setPlacesTab} />,
    settings: <Settings group={settingsGroup} setGroup={setSettingsGroup} />,
  }[section];

  /* Sections that manage their own width and padding. Wrapping a board that
   * already scrolls inside a padded main gives it two scrollbars. */
  const flush = section === "kitchen";

  return (
    <div style={{ display: "flex", minHeight: "100vh" }}>
      {/* ── rail ─────────────────────────────────────────────────────────── */}
      <nav
        style={{
          width: RAIL, flexShrink: 0, background: "var(--q-inverse)",
          display: "flex", flexDirection: "column", position: "sticky", top: 0, height: "100vh",
        }}
      >
        <div style={{ height: TOPBAR, display: "flex", alignItems: "center", padding: "0 16px", flexShrink: 0 }}>
          <span className="q-body-em" style={{ color: "#fff" }}>
            horecaos<span style={{ color: blue }}>.</span>
          </span>
        </div>

        {/* The primary action sits above navigation, because it is not a place
            you go — it is the thing you do. */}
        <div style={{ padding: "0 12px 12px" }}>
          <button
            type="button"
            onClick={startOrder}
            className="q-body-sm"
            style={{
              width: "100%", height: 40, background: blue, color: "#fff",
              border: "none", borderRadius: "var(--q-radius)", cursor: "pointer",
              display: "flex", alignItems: "center", justifyContent: "center", gap: 8,
            }}
          >
            New order
            <span className="q-caption" style={{ opacity: 0.75 }}>F2</span>
          </button>
        </div>

        <div style={{ flex: 1, overflowY: "auto", paddingBottom: 8 }}>
          {MENU_NAV.map((group) => (
            <div key={group.group} style={{ marginBottom: 4 }}>
              <div
                className="q-caption"
                style={{
                  padding: "10px 16px 4px", color: "#6f6f6f",
                  textTransform: "uppercase", letterSpacing: "0.32px",
                }}
              >
                {group.group}
              </div>
              {group.items.map((m) => {
                const on = m.id === section || (m.id === "orders" && section === "new-order");
                const badge = m.id === "orders" ? openCount : m.id === "delivery" ? lateCount : null;
                return (
                  <button
                    key={m.id}
                    type="button"
                    onClick={() => setSection(m.id)}
                    className="q-body-sm"
                    style={{
                      display: "flex", alignItems: "center", width: "100%", textAlign: "left",
                      padding: "8px 16px", border: "none", cursor: "pointer",
                      background: on ? "#262626" : "transparent",
                      color: on ? "#fff" : "var(--q-inverse-ink-muted)",
                      borderLeft: on ? `3px solid ${blue}` : "3px solid transparent",
                    }}
                  >
                    {m.label}
                    {badge ? (
                      <span
                        className="q-caption q-tnum"
                        style={{
                          marginLeft: "auto",
                          color: m.id === "delivery" && lateCount ? "var(--q-error)" : "var(--q-inverse-ink-muted)",
                        }}
                      >
                        {badge}
                      </span>
                    ) : null}
                  </button>
                );
              })}
            </div>
          ))}
        </div>

        <div style={{ padding: 16, borderTop: "1px solid #393939", flexShrink: 0 }}>
          <div className="q-body-sm" style={{ color: "#fff" }}>{TODAY.brand}</div>
          <div className="q-caption" style={{ color: "var(--q-inverse-ink-muted)", marginTop: 2 }}>
            {TODAY.location}
          </div>
        </div>
      </nav>

      {/* ── content ──────────────────────────────────────────────────────── */}
      <div style={{ flex: 1, minWidth: 0, display: "flex", flexDirection: "column" }}>
        <header
          style={{
            height: TOPBAR, flexShrink: 0, background: canvas,
            borderBottom: `1px solid ${hairline}`,
            display: "flex", alignItems: "center", padding: "0 24px", gap: 16,
            position: "sticky", top: 0, zIndex: 10,
          }}
        >
          <span className="q-body-sm" style={{ color: inkMuted }}>
            {section === "new-order" ? "New order" : NAV_ITEMS.find((m) => m.id === section)?.label}
          </span>

          {/* Always visible, whatever screen you are on. An operator must never
              have to navigate to discover that something has gone late. */}
          {lateCount ? (
            <button
              type="button"
              onClick={() => { setOrderFilter("late"); setSection("orders"); }}
              className="q-caption"
              style={{
                display: "inline-flex", alignItems: "center", gap: 6, cursor: "pointer",
                padding: "2px 8px", border: "none",
                background: "var(--q-error-tint)", color: "var(--q-error-text)",
              }}
            >
              <span style={{ width: 6, height: 6, borderRadius: "50%", background: "var(--q-error)" }} />
              {lateCount} late
            </button>
          ) : null}

          <span className="q-caption q-tnum" style={{ marginLeft: "auto", color: inkMuted }}>
            19:34 · 21.08
          </span>
        </header>

        <main style={{ flex: 1, padding: flush ? 16 : 24, minWidth: 0 }}>{body}</main>
      </div>
    </div>
  );
}

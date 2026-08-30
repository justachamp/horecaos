/* Customers.
 *
 * The operator's question is never "show me the customer base". It is "the phone
 * is ringing and the caller said 88-46-205" — so search matches a phone by its
 * digits alone, ignoring the +998 and the spacing nobody says out loud, and it
 * matches a name at the same time from the same box. One box, because an
 * operator holding a handset will not choose a search mode first.
 *
 * The list is a table, dense enough to answer the second question — is this
 * someone we know? — without opening anything: lifetime orders, standing, what
 * they have spent during this service, whether an address is on file, and the
 * note that will matter at the door.
 *
 * Selecting a customer opens a detail column beside the list rather than over
 * it. The operator is usually reading the address aloud while the caller
 * confirms it, and losing the search results behind a modal at that moment means
 * searching again. The list narrows to the five columns that identify a person
 * and keeps working; the detail takes the rest.
 *
 * An address is not a line of text. Every delivery in Tashkent turns on the
 * entrance, the floor and the landmark — "Metro yonida" is how a courier
 * actually finds the building — so the structured parts are shown as fields, not
 * flattened into a string. A missing landmark shows as a missing landmark.
 */

import { useEffect, useState } from "react";
import { CUSTOMERS, ORDERS, NOW } from "./data";
import {
  Card, DataTable, EmptyState, Field, FieldGrid, FilterBar, SearchInput,
  SectionHeader, Select, StatusPill,
  uzs, dt, day, ink, inkMuted, inkSubtle, hairline, canvas, surface1, blue,
} from "./components";

/* ── derivations ───────────────────────────────────────────────────────────
 * Everything below is computed from the fixtures. Nothing here is a second
 * source of truth for a customer.
 */

const MS_DAY = 86_400_000;
const daysAgo = (iso) => Math.floor((new Date(NOW) - new Date(iso)) / MS_DAY);

/** +998908846205 → +998 90 884 62 05. The grouping a caller speaks in. */
const phoneFmt = (p) => {
  const d = String(p).replace(/\D/g, "");
  if (d.length !== 12) return p;
  return `+${d.slice(0, 3)} ${d.slice(3, 5)} ${d.slice(5, 8)} ${d.slice(8, 10)} ${d.slice(10)}`;
};

/* Standing is derived, never stored — a "regular" flag that can go stale is
 * worse than no flag. Lapsed is checked first: someone who ordered once, fifty
 * days ago, is not new. Only lapsed carries a tone, so the one row an operator
 * might act on is the only coloured thing in the column. */
function standing(c) {
  const d = daysAgo(c.lastOrderAt);
  if (d > 30) return { label: "Lapsed", tone: "pending", hint: `${d} days since the last order` };
  if (c.ordersCount >= 20) return { label: "Regular", tone: "neutral", hint: `${c.ordersCount} orders placed` };
  if (c.ordersCount <= 1) return { label: "New", tone: "neutral", hint: "First order not yet followed" };
  return { label: "Returning", tone: "neutral", hint: `${c.ordersCount} orders placed` };
}

const ORDER_TONE = {
  PLACED: "neutral", ACCEPTED: "neutral", PREPARING: "pending", READY: "active",
  DISPATCHED: "info", DELIVERED: "healthy", CANCELLED: "failed",
};

const sentence = (s) => s.charAt(0) + s.slice(1).toLowerCase();

/* Every order in the fixtures was placed during this service, so "today" is an
 * honest reading of them — and it is the number an operator actually wants when
 * a regular calls back at nineteen thirty. */
const ordersFor = (id) => ORDERS.filter((o) => o.customerId === id);
const spendFor = (rows) =>
  rows.filter((o) => o.status !== "CANCELLED").reduce((s, o) => s + o.totalMinor, 0);

/* ── local pieces ──────────────────────────────────────────────────────────
 * Not in components.jsx and not general enough to belong there: a titled block
 * inside the detail column, a structured address, a compact empty line for a
 * pane too narrow for EmptyState's 48px, and one-line truncation.
 */

function Pane({ title, meta, children }) {
  return (
    <section style={{ borderTop: `1px solid ${hairline}`, padding: 16 }}>
      <div style={{ display: "flex", alignItems: "baseline", gap: 8, marginBottom: 12 }}>
        <h3 className="q-emphasis" style={{ margin: 0, color: ink }}>{title}</h3>
        {meta ? <span className="q-caption" style={{ color: inkSubtle, marginLeft: "auto" }}>{meta}</span> : null}
      </div>
      {children}
    </section>
  );
}

function Blank({ title, hint }) {
  return (
    <div style={{ border: `1px solid ${hairline}`, background: surface1, padding: 16 }}>
      <div className="q-body-sm" style={{ color: ink }}>{title}</div>
      {hint ? <div className="q-caption" style={{ color: inkMuted, marginTop: 4 }}>{hint}</div> : null}
    </div>
  );
}

function Truncate({ children, width = 240 }) {
  return (
    <span
      title={typeof children === "string" ? children : undefined}
      style={{ display: "block", maxWidth: width, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}
    >
      {children}
    </span>
  );
}

/** An address as a courier needs it: the line, then the three facts that get
 *  them through the door, then the pin. A null landmark reads as "—". */
function AddressBlock({ address }) {
  return (
    <div style={{ border: `1px solid ${hairline}`, background: canvas, padding: 16, marginBottom: 8 }}>
      <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 6 }}>
        <span className="q-emphasis" style={{ color: ink }}>{address.label}</span>
        {address.isDefault ? <StatusPill tone="neutral">Default</StatusPill> : null}
      </div>

      <div className="q-body-sm" style={{ color: ink, marginBottom: 12 }}>{address.line}</div>

      <div style={{ display: "grid", gridTemplateColumns: "repeat(3, minmax(0, 1fr))", gap: 12 }}>
        <Field label="Entrance" value={address.entrance} />
        <Field label="Floor" value={address.floor} />
        <Field label="Landmark" value={address.landmark} />
      </div>

      <div
        className="q-caption q-tnum"
        style={{ color: inkSubtle, marginTop: 12, fontFamily: "var(--q-font-mono)" }}
      >
        {address.lat.toFixed(4)}, {address.lon.toFixed(4)}
      </div>
    </div>
  );
}

/* ── detail ────────────────────────────────────────────────────────────────*/

function CustomerDetail({ customer, onClose }) {
  const rows = ordersFor(customer.id);
  const spend = spendFor(rows);
  const st = standing(customer);
  const since = daysAgo(customer.lastOrderAt);
  const pickupOnly = rows.length > 0 && rows.every((o) => o.fulfilment === "PICKUP");

  return (
    <Card padded={false}>
      <div style={{ padding: 16 }}>
        <div style={{ display: "flex", alignItems: "flex-start", gap: 12 }}>
          <div style={{ minWidth: 0 }}>
            <h2 className="q-subhead" style={{ margin: 0, color: ink }}>{customer.name}</h2>
            <a
              href={`tel:${customer.phone}`}
              className="q-body q-tnum"
              style={{ color: blue, textDecoration: "none", display: "inline-block", marginTop: 2 }}
            >
              {phoneFmt(customer.phone)}
            </a>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="q-body"
            style={{
              marginLeft: "auto", background: "transparent", border: "none",
              color: inkMuted, cursor: "pointer", padding: 4, lineHeight: 1,
            }}
          >
            ✕
          </button>
        </div>

        <div style={{ marginTop: 12 }}>
          <StatusPill tone={st.tone}>{st.label}</StatusPill>
          <span className="q-caption" style={{ color: inkSubtle, marginLeft: 8 }}>{st.hint}</span>
        </div>
      </div>

      <Pane title="Record">
        <FieldGrid
          columns={2}
          fields={[
            { label: "Customer ID", value: customer.id, mono: true },
            { label: "Lifetime orders", value: <span className="q-tnum">{customer.ordersCount}</span> },
            {
              label: "Last order",
              value: (
                <>
                  <span className="q-tnum">{day(customer.lastOrderAt)}</span>
                  <span style={{ color: inkSubtle }}>{` · ${since === 0 ? "today" : `${since} d ago`}`}</span>
                </>
              ),
            },
            {
              label: "This service",
              value: rows.length
                ? <span className="q-tnum">{`${rows.length} order${rows.length > 1 ? "s" : ""} · ${uzs(spend)}`}</span>
                : "No orders today",
            },
          ]}
        />

        {/* The note is operational, not decorative — it is read out at the door
            or dialled before the courier climbs four floors. */}
        {customer.note ? (
          <div style={{ border: `1px solid ${hairline}`, background: surface1, padding: 12, marginTop: 16 }}>
            <div className="q-caption" style={{ color: inkSubtle, marginBottom: 4 }}>Note</div>
            <div className="q-body-sm" style={{ color: ink }}>{customer.note}</div>
          </div>
        ) : null}
      </Pane>

      <Pane
        title="Saved addresses"
        meta={customer.addresses.length ? `${customer.addresses.length} on file` : null}
      >
        {customer.addresses.length ? (
          customer.addresses.map((a) => <AddressBlock key={a.id} address={a} />)
        ) : (
          <Blank
            title="No address on file"
            hint={
              pickupOnly
                ? "Every order so far was collected in person. Take an address when they next ask for delivery."
                : "Take one on the next call — entrance, floor and landmark, not just the street."
            }
          />
        )}
      </Pane>

      <Pane
        title="Orders"
        meta={`${rows.length} today · ${customer.ordersCount} lifetime`}
      >
        <DataTable
          rows={rows}
          selectedId={null}
          empty={<Blank title="Nothing today" hint={`Last seen ${day(customer.lastOrderAt)}.`} />}
          columns={[
            {
              key: "id", label: "Order",
              render: (_v, o) => (
                <div>
                  <div style={{ fontFamily: "var(--q-font-mono)", color: ink }}>{o.id}</div>
                  <div className="q-caption" style={{ color: inkSubtle }}>
                    {sentence(o.fulfilment)} · {o.channel}
                  </div>
                </div>
              ),
            },
            {
              key: "placedAt", label: "Placed",
              render: (v) => <span className="q-tnum">{dt(v)}</span>,
            },
            {
              key: "status", label: "Status",
              render: (v, o) => (
                <div>
                  <StatusPill tone={ORDER_TONE[v]}>{sentence(v)}</StatusPill>
                  {o.lateBy ? (
                    <div className="q-caption q-tnum" style={{ color: "var(--q-error-text)", marginTop: 4 }}>
                      {o.lateBy} min late
                    </div>
                  ) : null}
                </div>
              ),
            },
            {
              key: "totalMinor", label: "Total", align: "right",
              render: (v, o) => (
                <div>
                  <div style={{ color: ink }}>{uzs(v)}</div>
                  <div className="q-caption" style={{ color: o.paid ? inkSubtle : "var(--q-error-text)" }}>
                    {o.payment} · {o.paid ? "paid" : "unpaid"}
                  </div>
                </div>
              ),
            },
          ]}
        />
      </Pane>
    </Card>
  );
}

/* ── screen ────────────────────────────────────────────────────────────────*/

const SORTS = [
  { value: "recent", label: "Last order" },
  { value: "orders", label: "Most orders" },
  { value: "name", label: "Name" },
];

const SEGMENTS = [
  { value: "all", label: "Everyone" },
  { value: "regular", label: "Regulars" },
  { value: "lapsed", label: "Lapsed" },
  { value: "noaddress", label: "No saved address" },
];

export default function Customers({ customerId, setCustomerId }) {
  const [search, setSearch] = useState("");
  const [segment, setSegment] = useState("all");
  const [sort, setSort] = useState("recent");

  const selected = CUSTOMERS.find((c) => c.id === customerId) || null;

  /* Escape closes the detail column. An operator whose hands are on the keyboard
     taking the next call should not have to find the ✕. */
  useEffect(() => {
    if (!selected) return undefined;
    const onKey = (e) => { if (e.key === "Escape") setCustomerId(null); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [selected, setCustomerId]);

  /* One box, two things. A caller says a name or reads out digits; the operator
     types whichever they heard. Digits are compared bare, so "8846" finds
     +998 90 884 62 05 and the leading +998 nobody says is never in the way. */
  const q = search.trim().toLowerCase();
  const qDigits = q.replace(/\D/g, "");

  const matches = (c) => {
    if (!q) return true;
    if (c.name.toLowerCase().includes(q)) return true;
    return qDigits.length >= 2 && c.phone.replace(/\D/g, "").includes(qDigits);
  };

  const inSegment = (c) => {
    const label = standing(c).label;
    if (segment === "regular") return label === "Regular";
    if (segment === "lapsed") return label === "Lapsed";
    if (segment === "noaddress") return c.addresses.length === 0;
    return true;
  };

  const rows = CUSTOMERS.filter((c) => matches(c) && inSegment(c)).sort((a, b) => {
    if (sort === "orders") return b.ordersCount - a.ordersCount;
    if (sort === "name") return a.name.localeCompare(b.name);
    return new Date(b.lastOrderAt) - new Date(a.lastOrderAt);
  });

  const withAddress = CUSTOMERS.filter((c) => c.addresses.length).length;

  /* The detail column costs the list its softer columns rather than its density:
     what is left is what identifies a person on a call. */
  const columns = [
    {
      key: "name", label: "Customer",
      render: (v) => <span className="q-emphasis" style={{ color: ink }}>{v}</span>,
    },
    {
      key: "phone", label: "Phone",
      render: (v) => (
        <span className="q-tnum" style={{ fontFamily: "var(--q-font-mono)", whiteSpace: "nowrap" }}>
          {phoneFmt(v)}
        </span>
      ),
    },
    !selected && {
      key: "standing", label: "Standing",
      render: (_v, c) => {
        const st = standing(c);
        return <StatusPill tone={st.tone}>{st.label}</StatusPill>;
      },
    },
    {
      key: "ordersCount", label: "Orders", align: "right",
      render: (v) => v,
    },
    {
      key: "lastOrderAt", label: "Last order",
      render: (v) => {
        const d = daysAgo(v);
        return (
          <div style={{ whiteSpace: "nowrap" }}>
            <div className="q-tnum">{day(v)}</div>
            <div className="q-caption q-tnum" style={{ color: inkSubtle }}>
              {d === 0 ? "today" : `${d} d ago`}
            </div>
          </div>
        );
      },
    },
    !selected && {
      key: "today", label: "This service", align: "right",
      render: (_v, c) => {
        const os = ordersFor(c.id);
        if (!os.length) return <span style={{ color: inkSubtle }}>—</span>;
        return (
          <div style={{ whiteSpace: "nowrap" }}>
            <div className="q-tnum">{os.length === 1 ? "1 order" : `${os.length} orders`}</div>
            <div className="q-caption q-tnum" style={{ color: inkSubtle }}>{uzs(spendFor(os))}</div>
          </div>
        );
      },
    },
    {
      key: "addresses", label: "Saved addresses",
      render: (v) => {
        if (!v.length) return <span style={{ color: "var(--q-warning-text)" }}>None</span>;
        const def = v.find((a) => a.isDefault) || v[0];
        return (
          <div style={{ whiteSpace: "nowrap" }}>
            <div className="q-tnum">{v.length === 1 ? "1 address" : `${v.length} addresses`}</div>
            <div className="q-caption" style={{ color: inkSubtle }}>
              {def.label}{def.landmark ? ` · ${def.landmark}` : ""}
            </div>
          </div>
        );
      },
    },
    !selected && {
      key: "note", label: "Note",
      render: (v) =>
        v ? <Truncate width={260}>{v}</Truncate> : <span style={{ color: inkSubtle }}>—</span>,
    },
  ].filter(Boolean);

  return (
    <div>
      <SectionHeader
        title="Customers"
        description="Who orders from this branch. Search by name or by any part of the phone number — the digits a caller reads out are enough."
        right={
          <span className="q-caption q-tnum" style={{ color: inkMuted }}>
            {CUSTOMERS.length} customers · {withAddress} with a saved address
          </span>
        }
      />

      <div
        style={{
          display: "grid",
          gridTemplateColumns: selected ? "minmax(0, 1fr) 460px" : "minmax(0, 1fr)",
          gap: 24,
          alignItems: "start",
        }}
      >
        <div style={{ minWidth: 0 }}>
          <FilterBar>
            <SearchInput value={search} onChange={setSearch} placeholder="Name or phone" />
            <Select label="Show" value={segment} onChange={setSegment} options={SEGMENTS} />
            <Select label="Sort by" value={sort} onChange={setSort} options={SORTS} />
            <span className="q-caption q-tnum" style={{ color: inkSubtle, marginLeft: "auto" }}>
              {rows.length} of {CUSTOMERS.length}
            </span>
          </FilterBar>

          <DataTable
            columns={columns}
            rows={rows}
            selectedId={customerId}
            onRowClick={(r) => setCustomerId(r.id === customerId ? null : r.id)}
            empty={
              <EmptyState
                title="No customer matches that"
                description={
                  qDigits.length >= 2
                    ? "Try fewer digits — the last four are usually enough."
                    : "Check the spelling, or search by phone instead."
                }
              />
            }
          />
        </div>

        {selected ? (
          <div style={{ position: "sticky", top: 72 }}>
            <CustomerDetail customer={selected} onClose={() => setCustomerId(null)} />
          </div>
        ) : null}
      </div>
    </div>
  );
}

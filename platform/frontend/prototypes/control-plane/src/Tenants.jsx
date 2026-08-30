/* Tenants — the customer directory, and the customer record behind it.
 *
 * This is the screen an account manager lives on. It answers two questions and
 * they are not the same question:
 *
 *   1. Who are our customers, and which of them is not fine right now?
 *   2. Everything about this one customer.
 *
 * The first question is why the directory carries a reason column. A status
 * chip that says "suspended" and makes you open the record to learn why costs a
 * click on every row of a list whose whole purpose is triage, and an account
 * manager scanning for the account a restaurant owner is shouting about needs
 * "Invoice unpaid for 62 days" on the row. Suspended, onboarding and closed
 * accounts each carry a status rail in the left margin as well, so the shape of
 * the list is readable before any of the text is.
 *
 * Closed accounts stay in the directory in muted ink rather than disappearing.
 * A churned customer is still a customer record — finance bills a final invoice
 * against it, and support gets calls about it for months.
 */

import { useState } from "react";
import {
  StatusPill, Button, Card, Tabs, FilterBar, Select, SearchInput,
  DataTable, FieldGrid, EmptyState, KpiTile, Timeline,
  uzs, dt, day,
  ink, inkMuted, inkSubtle, hairline, canvas, surface1,
} from "./components";
import {
  TENANTS, BRANDS, LOCATIONS, SUBSCRIPTIONS, INVOICES, USAGE, ACTIVITY,
  PLANS, ONBOARDING, TENANT_LEAGUE,
} from "./data";

/* ── local helpers ─────────────────────────────────────────────────────────
 * Four things this screen needs that the shared set does not carry. They stay
 * in this file rather than migrating into components.jsx: three are Tenants
 * arrangements, and the fourth is a table variant nothing else has asked for
 * yet.
 */

/** Date-only fixtures ("2026-09-02") never go through `new Date`, which would
 *  read them as UTC midnight and shift them a day west of Tashkent. */
const dayOnly = (s) => (s ? s.split("-").reverse().join(".") : null);

/** Counts group the same way money does — space-separated, so an orders column
 *  and a GMV column line up on the same digit boundaries. */
const num = (n) => String(n).replace(/\B(?=(\d{3})+(?!\d))/g, " ");

/** A titled band inside the record, matching the Overview block rhythm. */
function Block({ title, meta, action, children, note }) {
  return (
    <section style={{ marginBottom: 32 }}>
      <div style={{ display: "flex", alignItems: "baseline", gap: 12, marginBottom: 12, flexWrap: "wrap" }}>
        <h2 className="q-subhead" style={{ margin: 0, color: ink }}>{title}</h2>
        {meta ? <span className="q-body-sm" style={{ color: inkMuted }}>{meta}</span> : null}
        {action ? <div style={{ marginLeft: "auto" }}>{action}</div> : null}
      </div>
      {children}
      {note ? (
        <div className="q-caption" style={{ color: inkSubtle, marginTop: 8, maxWidth: 760 }}>{note}</div>
      ) : null}
    </section>
  );
}

/** Health is a dot and a word, deliberately not a pill — the row already has a
 *  pill in the status column and two of them read as one control. */
function Health({ value }) {
  const h = HEALTH[value] || HEALTH.unknown;
  if (!h.label) return <span style={{ color: inkSubtle }}>—</span>;
  return (
    <span style={{ display: "inline-flex", alignItems: "center", gap: 6, whiteSpace: "nowrap" }}>
      <span style={{ width: 8, height: 8, borderRadius: "50%", background: h.dot, flexShrink: 0 }} />
      <span style={{ color: "inherit" }}>{h.label}</span>
    </span>
  );
}

/** The directory table. It is the shared DataTable with one thing added — a 3px
 *  status rail in the left margin and a per-row ink, so a suspended row and a
 *  closed row do not read like an active one. Local because no other screen in
 *  the console has a list where the row itself has a state. */
function TenantTable({ columns, rows, onRowClick, selectedId, rowTone }) {
  return (
    <div style={{ border: `1px solid ${hairline}`, borderTop: "none", background: canvas, overflowX: "auto" }}>
      <table style={{ width: "100%", borderCollapse: "collapse" }}>
        <thead>
          <tr>
            <th style={{ width: 3, padding: 0, background: surface1, borderBottom: `1px solid ${hairline}` }}>
              {/* An empty cell collapses to nothing under border-collapse, so the
                  rail needs something 3px wide standing in it. */}
              <div style={{ width: 3 }} />
            </th>
            {columns.map((c) => (
              <th
                key={c.key}
                className="q-caption"
                style={{
                  textAlign: c.align || "left", padding: "10px 12px",
                  background: surface1, color: inkMuted, fontWeight: 600,
                  borderBottom: `1px solid ${hairline}`, whiteSpace: "nowrap",
                }}
              >
                {c.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const tone = (rowTone ? rowTone(row) : null) || {};
            const selected = selectedId === row.id;
            return (
              <tr
                key={row.id}
                onClick={onRowClick ? () => onRowClick(row) : undefined}
                style={{
                  cursor: onRowClick ? "pointer" : "default",
                  background: selected ? "var(--q-info-tint)" : canvas,
                  borderBottom: `1px solid ${hairline}`,
                }}
                onMouseEnter={(e) => { if (!selected) e.currentTarget.style.background = surface1; }}
                onMouseLeave={(e) => { if (!selected) e.currentTarget.style.background = canvas; }}
              >
                <td style={{ width: 3, padding: 0, background: tone.rail || "transparent" }}>
                  <div style={{ width: 3 }} />
                </td>
                {columns.map((c) => (
                  <td
                    key={c.key}
                    className={c.align === "right" ? "q-body-sm q-tnum" : "q-body-sm"}
                    style={{
                      padding: "10px 12px", textAlign: c.align || "left",
                      color: tone.color || ink, height: 44, verticalAlign: "middle",
                      /* Every column holds its line except the one that carries
                       * a sentence; auto table layout otherwise steals width
                       * from the money columns and wraps them mid-number. */
                      whiteSpace: c.wrap ? "normal" : "nowrap",
                      width: c.width, minWidth: c.width,
                    }}
                  >
                    {c.render ? c.render(row[c.key], row) : row[c.key]}
                  </td>
                ))}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

/** The reason band at the top of a record that is not in good standing. */
function StateBanner({ tenant, arrears, onOpenCommercial }) {
  const suspended = tenant.status === "SUSPENDED";
  const closed = tenant.status === "CLOSED";
  if (!suspended && !closed) return null;

  return (
    <div
      style={{
        display: "flex", alignItems: "flex-start", gap: 16, padding: "12px 16px",
        marginBottom: 24,
        background: suspended ? "var(--q-error-tint)" : surface1,
        border: `1px solid ${hairline}`,
        borderLeftWidth: 3,
        borderLeftColor: suspended ? "var(--q-error)" : "var(--q-ink-subtle)",
      }}
    >
      <div style={{ minWidth: 0 }}>
        <div className="q-emphasis" style={{ color: suspended ? "var(--q-error-text)" : ink }}>
          {suspended
            ? `Suspended ${dt(tenant.suspendedAt)} — ${tenant.suspendedReason}`
            : `Closed ${dayOnly(tenant.closedAt?.slice(0, 10))} — ${tenant.closedReason}`}
        </div>
        <div className="q-body-sm" style={{ color: inkMuted, marginTop: 4, maxWidth: 720 }}>
          {suspended
            ? `Ordering is off for every location on this account. ${arrears > 0 ? `${uzs(arrears)} is outstanding across unpaid invoices.` : "No invoice is outstanding, so the suspension is not a billing one."}`
            : "The account is offboarded. It is kept in the directory because finance and support still work against the record."}
        </div>
      </div>
      <div style={{ marginLeft: "auto", flexShrink: 0 }}>
        <Button variant="tertiary" size="sm" onClick={onOpenCommercial}>
          Open commercial
        </Button>
      </div>
    </div>
  );
}

/* ── maps ──────────────────────────────────────────────────────────────────*/

const STATUS = {
  ACTIVE:    { tone: "active",    label: "active",     rail: "transparent" },
  PENDING:   { tone: "pending",   label: "onboarding", rail: "var(--q-warning)" },
  SUSPENDED: { tone: "suspended", label: "suspended",  rail: "var(--q-error)", color: ink },
  CLOSED:    { tone: "neutral",   label: "closed",     rail: "var(--q-ink-subtle)", color: inkMuted },
};

const HEALTH = {
  healthy:   { dot: "var(--q-success)",    label: "healthy" },
  "at-risk": { dot: "var(--q-warning)",    label: "at risk" },
  unknown:   { dot: "var(--q-ink-subtle)", label: "not measured" },
  closed:    { dot: "transparent",         label: null },
};

const SUB_TONE = { ACTIVE: "active", PAST_DUE: "failed", TRIAL: "info", CANCELLED: "neutral" };
const INVOICE_TONE = { PAID: "healthy", SENT: "info", OVERDUE: "failed" };
const CHILD_TONE = { ACTIVE: "active", OPEN: "active", PREPARING: "pending", SUSPENDED: "suspended" };

const FILTERS = [
  { value: "all", label: "All statuses" },
  { value: "ATTENTION", label: "Needs attention" },
  { value: "ACTIVE", label: "Active" },
  { value: "PENDING", label: "Onboarding" },
  { value: "SUSPENDED", label: "Suspended" },
  { value: "CLOSED", label: "Closed" },
];

const SORTS = [
  { value: "attention", label: "Attention first" },
  { value: "gmv", label: "GMV, last 30 days" },
  { value: "name", label: "Name" },
  { value: "joined", label: "Newest first" },
];

const ATTENTION_RANK = { SUSPENDED: 0, PENDING: 1, ACTIVE: 2, CLOSED: 3 };

/* Why this account is not simply "active". This is the column the screen
 * exists for, so it is derived once and used both in the list and the record. */
function reasonFor(t) {
  if (t.status === "SUSPENDED") {
    return { text: `${t.suspendedReason} · ${dt(t.suspendedAt)}`, color: "var(--q-error-text)" };
  }
  if (t.status === "CLOSED") {
    return { text: `${t.closedReason} · closed ${dayOnly(t.closedAt.slice(0, 10))}`, color: inkMuted };
  }
  if (t.status === "PENDING") {
    const target = ONBOARDING.tenantId === t.id ? ONBOARDING.targetLiveDate : null;
    return {
      text: target
        ? `In onboarding · target go-live ${dayOnly(target)}`
        : `In onboarding since ${day(t.joinedAt)}`,
      color: "var(--q-warning-text)",
    };
  }
  if (t.healthNote) return { text: t.healthNote, color: "var(--q-warning-text)" };
  return null;
}

const arrearsFor = (tenantId) =>
  INVOICES.filter((i) => i.tenantId === tenantId && i.status === "OVERDUE")
    .reduce((sum, i) => sum + i.amountMinor, 0);

/* ── screen ────────────────────────────────────────────────────────────────*/

export default function Tenants({ tenantId, setTenantId, tab, setTab, filter, setFilter, search, setSearch }) {
  /* Sort is local: it changes what the list looks like, never what it means,
   * so it does not need to survive a trip into a record and back out. */
  const [sort, setSort] = useState("attention");

  const tenant = TENANTS.find((t) => t.id === tenantId);
  if (tenant) {
    return <TenantRecord tenant={tenant} tab={tab} setTab={setTab} onBack={() => setTenantId(null)} />;
  }

  return (
    <TenantDirectory
      filter={filter} setFilter={setFilter}
      search={search} setSearch={setSearch}
      sort={sort} setSort={setSort}
      onOpen={(t) => { setTenantId(t.id); setTab("summary"); }}
    />
  );
}

/* ── the directory ─────────────────────────────────────────────────────────*/

function TenantDirectory({ filter, setFilter, search, setSearch, sort, setSort, onOpen }) {
  const q = search.trim().toLowerCase();

  const matchesSearch = (t) =>
    !q || [t.displayName, t.legalName, t.slug, t.inn].some((v) => v && String(v).toLowerCase().includes(q));

  const matchesFilter = (t) =>
    filter === "all" ||
    (filter === "ATTENTION"
      ? t.status === "SUSPENDED" || t.health === "at-risk"
      : t.status === filter);

  const rows = TENANTS.filter((t) => matchesSearch(t) && matchesFilter(t)).sort((a, b) => {
    if (sort === "gmv") return b.gmvLast30Minor - a.gmvLast30Minor;
    if (sort === "name") return a.displayName.localeCompare(b.displayName);
    if (sort === "joined") return b.joinedAt.localeCompare(a.joinedAt);
    const rank = ATTENTION_RANK[a.status] - ATTENTION_RANK[b.status];
    if (rank !== 0) return rank;
    const risk = (a.health === "at-risk" ? 0 : 1) - (b.health === "at-risk" ? 0 : 1);
    if (risk !== 0) return risk;
    return b.gmvLast30Minor - a.gmvLast30Minor;
  });

  const counts = TENANTS.reduce((acc, t) => ({ ...acc, [t.status]: (acc[t.status] || 0) + 1 }), {});
  const attention = TENANTS.filter((t) => t.status === "SUSPENDED" || t.health === "at-risk").length;
  const liveLocations = TENANTS.filter((t) => t.status === "ACTIVE").reduce((s, t) => s + t.locations, 0);
  const liveBrands = TENANTS.filter((t) => t.status === "ACTIVE").reduce((s, t) => s + t.brands, 0);
  const orders30 = TENANTS.reduce((s, t) => s + t.ordersLast30, 0);
  const gmv30 = TENANTS.reduce((s, t) => s + t.gmvLast30Minor, 0);

  return (
    <div>
      <div style={{ display: "flex", alignItems: "flex-start", gap: 16, marginBottom: 24 }}>
        <div style={{ minWidth: 0 }}>
          <h1 className="q-title" style={{ margin: 0, color: ink }}>Tenants</h1>
          <p className="q-body-sm" style={{ margin: "4px 0 0", color: inkMuted, maxWidth: 720 }}>
            Every restaurant business on the platform, live or otherwise. An account
            that is suspended, onboarding or closed says so on its own row, with the
            reason — nobody should have to open a record to find out why a customer
            cannot take orders.
          </p>
        </div>
      </div>

      {/* ── band ─────────────────────────────────────────────────────────── */}
      <div
        style={{
          display: "grid", gridTemplateColumns: "repeat(6, minmax(0, 1fr))",
          gap: 16, marginBottom: 32,
        }}
      >
        <KpiTile
          label="In the directory"
          value={TENANTS.length}
          meta={`${counts.ACTIVE || 0} active · ${counts.PENDING || 0} onboarding`}
        />
        <KpiTile
          label="Needs attention"
          value={attention}
          meta={`${counts.SUSPENDED || 0} suspended · ${counts.CLOSED || 0} closed`}
        />
        <KpiTile
          label="Locations live"
          value={liveLocations}
          meta={`across ${liveBrands} brands`}
        />
        <KpiTile
          label="Orders, last 30 days"
          value={num(orders30)}
          meta="every tenant, all channels"
        />
        <div style={{ gridColumn: "span 2" }}>
          <KpiTile
            label="GMV, last 30 days"
            value={uzs(gmv30)}
            meta={`${uzs(arrearsAcrossPlatform())} outstanding in unpaid invoices`}
          />
        </div>
      </div>

      {/* ── filters ──────────────────────────────────────────────────────── */}
      <FilterBar>
        <Select label="Status" value={filter} onChange={setFilter} options={FILTERS} />
        <Select label="Sort" value={sort} onChange={setSort} options={SORTS} />
        <SearchInput
          value={search}
          onChange={setSearch}
          placeholder="Search name, slug or INN"
        />
        <span className="q-caption" style={{ color: inkSubtle, marginLeft: "auto" }}>
          {rows.length === TENANTS.length
            ? `${TENANTS.length} tenants`
            : `${rows.length} of ${TENANTS.length} tenants`}
        </span>
        {filter !== "all" || q ? (
          <Button
            variant="ghost"
            size="sm"
            onClick={() => { setFilter("all"); setSearch(""); }}
          >
            Clear
          </Button>
        ) : null}
      </FilterBar>

      {rows.length === 0 ? (
        <EmptyState
          title="No tenant matches this"
          description={q ? `Nothing in the directory matches “${search.trim()}”.` : "No tenant currently has this status."}
          action={
            <Button variant="tertiary" size="sm" onClick={() => { setFilter("all"); setSearch(""); }}>
              Clear the filters
            </Button>
          }
        />
      ) : (
        <TenantTable
          rows={rows}
          onRowClick={onOpen}
          rowTone={(t) => STATUS[t.status]}
          columns={[
            {
              key: "displayName", label: "Tenant", width: 158,
              render: (v, t) => (
                <div style={{ minWidth: 0 }}>
                  <div className="q-emphasis" style={{ color: "inherit" }}>{v}</div>
                  <div
                    className="q-caption"
                    style={{ color: inkSubtle, fontFamily: "var(--q-font-mono)", marginTop: 2 }}
                  >
                    {t.slug}
                  </div>
                </div>
              ),
            },
            {
              key: "legalName", label: "Legal entity", width: 164,
              render: (v, t) => (
                <div style={{ minWidth: 0 }}>
                  <div style={{ color: "inherit" }}>{v}</div>
                  <div className="q-caption" style={{ color: inkSubtle, marginTop: 2 }}>
                    {t.inn
                      ? <span style={{ fontFamily: "var(--q-font-mono)" }}>INN {t.inn}</span>
                      : "INN not registered"}
                  </div>
                </div>
              ),
            },
            { key: "city", label: "City" },
            /* Status and its reason are one column, not two. A chip that says
             * "suspended" beside a separate column that says why invites the
             * eye to read them apart; stacked, the reason is the second line of
             * the status and cannot be scanned without it. */
            {
              key: "status", label: "Status and reason", width: 228, wrap: true,
              render: (v, t) => {
                const r = reasonFor(t);
                return (
                  <div style={{ minWidth: 0 }}>
                    <StatusPill tone={STATUS[v].tone}>{STATUS[v].label}</StatusPill>
                    <div className="q-caption" style={{ marginTop: 4, color: r ? r.color : inkSubtle }}>
                      {r ? r.text : "Trading normally"}
                    </div>
                  </div>
                );
              },
            },
            { key: "plan", label: "Plan" },
            { key: "brands", label: "Brands", align: "right" },
            { key: "locations", label: "Locations", align: "right" },
            {
              key: "ordersLast30", label: "Orders 30d", align: "right",
              render: (v) => (v === 0 ? <span style={{ color: inkSubtle }}>0</span> : num(v)),
            },
            {
              key: "gmvLast30Minor", label: "GMV 30d", align: "right",
              render: (v) => (v === 0 ? <span style={{ color: inkSubtle }}>—</span> : uzs(v)),
            },
            {
              key: "health", label: "Health",
              render: (v) => <Health value={v} />,
            },
            {
              key: "joinedAt", label: "Joined", align: "right",
              render: (v) => day(v),
            },
          ]}
        />
      )}

      <div className="q-caption" style={{ color: inkSubtle, marginTop: 12, maxWidth: 760 }}>
        Order and GMV figures cover the last thirty days and are zero for any account
        that could not trade in that window. Health is an account-management judgement
        from order volume, not a platform uptime measure.
      </div>
    </div>
  );
}

const arrearsAcrossPlatform = () =>
  INVOICES.filter((i) => i.status === "OVERDUE").reduce((s, i) => s + i.amountMinor, 0);

/* ── the record ────────────────────────────────────────────────────────────*/

function TenantRecord({ tenant, tab, setTab, onBack }) {
  const brands = BRANDS.filter((b) => b.tenantId === tenant.id);
  const locations = LOCATIONS.filter((l) => l.tenantId === tenant.id);
  const invoices = INVOICES.filter((i) => i.tenantId === tenant.id);
  const activity = ACTIVITY.filter((a) => a.tenant === tenant.displayName);
  const arrears = arrearsFor(tenant.id);
  const s = STATUS[tenant.status];

  return (
    <div>
      {/* ── record header ────────────────────────────────────────────────── */}
      <div style={{ marginBottom: 16 }}>
        <Button variant="ghost" size="sm" onClick={onBack} style={{ padding: 0, height: 24 }}>
          Back to all tenants
        </Button>
      </div>

      <div style={{ display: "flex", alignItems: "flex-start", gap: 16, marginBottom: 24 }}>
        <div style={{ minWidth: 0 }}>
          <div style={{ display: "flex", alignItems: "center", gap: 12, flexWrap: "wrap" }}>
            <h1 className="q-title" style={{ margin: 0, color: s.color || ink }}>{tenant.displayName}</h1>
            <StatusPill tone={s.tone}>{s.label}</StatusPill>
          </div>
          <p className="q-body-sm" style={{ margin: "4px 0 0", color: inkMuted }}>
            {tenant.legalName} · {tenant.city} ·{" "}
            <span style={{ fontFamily: "var(--q-font-mono)" }}>{tenant.slug}</span>
          </p>
        </div>
        <div className="q-caption" style={{ marginLeft: "auto", flexShrink: 0, color: inkSubtle, textAlign: "right" }}>
          <div>{tenant.plan} plan</div>
          <div style={{ marginTop: 2 }}>Customer since {day(tenant.joinedAt)}</div>
        </div>
      </div>

      <StateBanner tenant={tenant} arrears={arrears} onOpenCommercial={() => setTab("commercial")} />

      <Tabs
        active={tab}
        onChange={setTab}
        tabs={[
          { id: "summary", label: "Summary" },
          { id: "brands", label: "Brands and locations", count: brands.length + locations.length },
          { id: "commercial", label: "Commercial", count: invoices.length },
          { id: "activity", label: "Activity", count: activity.length },
        ]}
      />

      {tab === "summary" ? <SummaryTab tenant={tenant} /> : null}
      {tab === "brands" ? <BrandsTab tenant={tenant} brands={brands} locations={locations} /> : null}
      {tab === "commercial" ? <CommercialTab tenant={tenant} invoices={invoices} arrears={arrears} /> : null}
      {tab === "activity" ? <ActivityTab tenant={tenant} activity={activity} /> : null}
    </div>
  );
}

/* ── summary ───────────────────────────────────────────────────────────────*/

function SummaryTab({ tenant }) {
  const league = TENANT_LEAGUE.find((l) => l.tenantId === tenant.id);
  const trading = tenant.ordersLast30 > 0;
  const basket = trading ? Math.round(tenant.gmvLast30Minor / tenant.ordersLast30) : null;
  const s = STATUS[tenant.status];
  const reason = reasonFor(tenant);

  return (
    <div>
      <div
        style={{
          display: "grid", gridTemplateColumns: "repeat(5, minmax(0, 1fr))",
          gap: 16, marginBottom: 32,
        }}
      >
        <KpiTile
          label="Orders, last 30 days"
          value={trading ? num(tenant.ordersLast30) : "0"}
          meta={trading ? "across every location" : "not trading in this window"}
        />
        <div style={{ gridColumn: "span 2" }}>
          <KpiTile
            label="GMV, last 30 days"
            value={trading ? uzs(tenant.gmvLast30Minor) : "—"}
            meta={league ? `${league.changePct > 0 ? "+" : ""}${league.changePct}% against the previous 30 days` : "no comparable previous period"}
          />
        </div>
        <KpiTile
          label="Average basket"
          value={basket ? uzs(basket) : "—"}
          meta={trading ? "GMV divided by orders" : "no orders to divide"}
        />
        <KpiTile
          label="Locations"
          value={tenant.locations}
          meta={`under ${tenant.brands} ${tenant.brands === 1 ? "brand" : "brands"}`}
        />
      </div>

      <Block title="Account record" meta="What the contract and the console agree on">
        <Card>
          <FieldGrid
            columns={4}
            fields={[
              { label: "Trading name", value: tenant.displayName },
              { label: "Legal name", value: tenant.legalName },
              { label: "INN", value: tenant.inn ?? "Not registered yet", mono: !!tenant.inn },
              { label: "City", value: tenant.city },
              { label: "Slug", value: tenant.slug, mono: true },
              { label: "Tenant id", value: tenant.id, mono: true },
              { label: "Owner", value: tenant.owner },
              { label: "Owner phone", value: tenant.ownerPhone },
              { label: "Plan", value: tenant.plan },
              { label: "Status", value: <StatusPill tone={s.tone}>{s.label}</StatusPill> },
              { label: "Lifecycle", value: tenant.lifecycle.toLowerCase() },
              { label: "Joined", value: day(tenant.joinedAt) },
            ]}
          />
        </Card>
      </Block>

      <Block
        title="Health"
        meta="An account-management judgement, reviewed every thirty days"
        note="Health is not uptime. It is whether this customer looks like one who will still be here next quarter, which is a question about order volume and unpaid invoices rather than about the platform."
      >
        <Card>
          <FieldGrid
            columns={3}
            fields={[
              { label: "Current health", value: <Health value={tenant.health} /> },
              {
                label: "Change against previous 30 days",
                value: league
                  ? `${league.changePct > 0 ? "+" : ""}${league.changePct}% orders`
                  : "Not measured — no trading history",
              },
              {
                label: "Why",
                value: reason
                  ? <span style={{ color: reason.color }}>{reason.text}</span>
                  : "Nothing outstanding. Trading normally.",
              },
            ]}
          />
        </Card>
      </Block>
    </div>
  );
}

/* ── brands and locations ──────────────────────────────────────────────────*/

function BrandsTab({ tenant, brands, locations }) {
  const brandName = (id) => BRANDS.find((b) => b.id === id)?.name ?? id;
  const recorded = locations.length;

  return (
    <div>
      <Block
        title="Brands"
        meta={`${brands.length} of ${tenant.brands} recorded`}
        note={
          brands.length < tenant.brands
            ? "The directory counts brands from the contract. A brand only appears here once someone has set it up in the console."
            : null
        }
      >
        {brands.length === 0 ? (
          <EmptyState
            title="No brand is set up yet"
            description={`The contract counts ${tenant.brands} ${tenant.brands === 1 ? "brand" : "brands"} for this account, and none of them has been created in the console.`}
          />
        ) : (
          <DataTable
            rows={brands}
            columns={[
              { key: "name", label: "Brand" },
              {
                key: "id", label: "Brand id",
                render: (v) => <span style={{ fontFamily: "var(--q-font-mono)", color: inkMuted }}>{v}</span>,
              },
              { key: "locations", label: "Locations", align: "right" },
              {
                key: "status", label: "Status",
                render: (v) => <StatusPill tone={CHILD_TONE[v] || "neutral"}>{v.toLowerCase()}</StatusPill>,
              },
            ]}
          />
        )}
      </Block>

      <Block
        title="Locations"
        meta={`${recorded} of ${tenant.locations} recorded`}
        note={
          recorded < tenant.locations
            ? `The account is contracted for ${tenant.locations} locations and ${recorded} ${recorded === 1 ? "has" : "have"} a record in the console. The remainder are counted on the contract and billed, but nobody has set them up — which is the commonest reason a bill and a console disagree.`
            : null
        }
      >
        {recorded === 0 ? (
          <EmptyState
            title="No location has been set up"
            description={`${tenant.locations} ${tenant.locations === 1 ? "location is" : "locations are"} counted on the contract, and none of them exists in the console yet.`}
          />
        ) : (
          <DataTable
            rows={locations}
            columns={[
              { key: "name", label: "Location" },
              { key: "brandId", label: "Brand", render: (v) => brandName(v) },
              { key: "city", label: "City" },
              {
                key: "status", label: "Status",
                render: (v) => <StatusPill tone={CHILD_TONE[v] || "neutral"}>{v.toLowerCase()}</StatusPill>,
              },
              {
                key: "openedAt", label: "Opened", align: "right",
                render: (v) => (v ? day(v) : <span style={{ color: inkSubtle }}>not open yet</span>),
              },
            ]}
          />
        )}
      </Block>
    </div>
  );
}

/* ── commercial ────────────────────────────────────────────────────────────*/

function CommercialTab({ tenant, invoices, arrears }) {
  const sub = SUBSCRIPTIONS.find((x) => x.tenantId === tenant.id);
  const plan = sub ? PLANS.find((p) => p.name === sub.plan) : null;
  const usage = USAGE.filter((u) => u.tenantId === tenant.id);

  const extrasMinor = sub && plan ? sub.extraLocations * plan.extraLocationMinor : 0;
  const total = (status) =>
    invoices.filter((i) => i.status === status).reduce((s, i) => s + i.amountMinor, 0);
  const paid = total("PAID");
  const awaiting = total("SENT");

  /* Three different states of money, and an account manager needs them apart:
   * overdue is a problem, sent is not yet a problem, paid is history. */
  const ledger = [
    arrears > 0 ? `${uzs(arrears)} overdue` : null,
    awaiting > 0 ? `${uzs(awaiting)} awaiting payment` : null,
    paid > 0 ? `${uzs(paid)} settled` : null,
  ].filter(Boolean);

  return (
    <div>
      <Block
        title="Subscription"
        meta={sub ? `${sub.plan} · started ${dayOnly(sub.startedAt)}` : "No subscription on file"}
      >
        {!sub ? (
          <EmptyState
            title="No subscription"
            description="This account has never had a plan attached, which should not be possible for a tenant past onboarding."
          />
        ) : (
          <Card>
            <FieldGrid
              columns={4}
              fields={[
                { label: "Plan", value: sub.plan },
                { label: "Status", value: <StatusPill tone={SUB_TONE[sub.status] || "neutral"}>{sub.status.toLowerCase().replace("_", " ")}</StatusPill> },
                { label: "Started", value: dayOnly(sub.startedAt) },
                { label: "Renews", value: sub.renewsAt ? dayOnly(sub.renewsAt) : "Does not renew" },
                { label: "Plan monthly", value: uzs(sub.monthlyMinor) },
                {
                  label: "Locations included",
                  value: plan ? `${plan.locationsIncluded} · ${uzs(plan.extraLocationMinor)} each after` : "—",
                },
                { label: "Extra locations", value: sub.extraLocations },
                { label: "Billed monthly", value: <span className="q-emphasis">{uzs(sub.billedMinor)}</span> },
              ]}
            />
            <div
              className="q-caption"
              style={{ color: inkMuted, marginTop: 20, padding: "8px 12px", background: surface1 }}
            >
              {sub.status === "CANCELLED"
                ? "Cancelled. Nothing is billed against this subscription — the final invoice was settled at offboarding."
                : sub.billedMinor === 0
                  ? `Trial until ${dayOnly(sub.renewsAt)}. Nothing is billed, and the plan price applies from the first renewal.`
                  : `${uzs(sub.monthlyMinor)} plan + ${sub.extraLocations} extra ${sub.extraLocations === 1 ? "location" : "locations"} at ${uzs(plan ? plan.extraLocationMinor : 0)} = ${uzs(sub.monthlyMinor + extrasMinor)}. Commission is zero on every plan; HorecaOS charges for the software, not for the customer's orders.`}
            </div>
          </Card>
        )}
      </Block>

      <Block
        title="Invoices"
        meta={invoices.length ? ledger.join(" · ") : "Nothing invoiced yet"}
      >
        {invoices.length === 0 ? (
          <EmptyState
            title="No invoice has been issued"
            description="Nothing has been billed to this account. A tenant in onboarding is normally invoiced from the go-live date."
          />
        ) : (
          <DataTable
            rows={invoices}
            columns={[
              {
                key: "id", label: "Invoice",
                render: (v) => <span style={{ fontFamily: "var(--q-font-mono)" }}>{v}</span>,
              },
              { key: "issuedAt", label: "Issued", render: (v) => dayOnly(v) },
              { key: "dueAt", label: "Due", render: (v) => dayOnly(v) },
              { key: "amountMinor", label: "Amount", align: "right", render: (v) => uzs(v) },
              {
                key: "status", label: "Status",
                render: (v) => <StatusPill tone={INVOICE_TONE[v] || "neutral"}>{v.toLowerCase()}</StatusPill>,
              },
              {
                key: "method", label: "Method",
                render: (v) => v || <span style={{ color: inkSubtle }}>not paid</span>,
              },
              {
                key: "paidAt", label: "Settled", align: "right",
                render: (v, row) =>
                  v
                    ? dt(v)
                    : row.daysOverdue
                      ? <span style={{ color: "var(--q-error-text)" }}>{row.daysOverdue} days overdue</span>
                      : <span style={{ color: inkSubtle }}>—</span>,
              },
            ]}
          />
        )}
      </Block>

      <Block
        title="Usage"
        meta="August 2026, the month currently being billed"
        note="Usage is what an invoice line has to be defensible against. Location count drives the bill; orders, messages and storage are recorded but not charged for on any current plan."
      >
        {usage.length === 0 ? (
          <EmptyState
            title="No usage recorded for August 2026"
            description={
              tenant.status === "ACTIVE"
                ? "This account is active but has no metered usage this month."
                : "The account could not trade this month, so nothing was metered."
            }
          />
        ) : (
          <DataTable
            rows={usage.map((u) => ({ ...u, id: `${u.tenantId}-${u.month}` }))}
            columns={[
              { key: "month", label: "Month" },
              { key: "locations", label: "Locations billed", align: "right" },
              {
                key: "ordersProcessed", label: "Orders processed", align: "right",
                render: (v) => num(v),
              },
              { key: "gmvMinor", label: "GMV", align: "right", render: (v) => uzs(v) },
              {
                key: "avgBasket", label: "Average basket", align: "right",
                render: (_v, row) => uzs(Math.round(row.gmvMinor / row.ordersProcessed)),
              },
              {
                key: "smsSent", label: "Messages sent", align: "right",
                render: (v) => num(v),
              },
              { key: "storageGb", label: "Storage, GB", align: "right" },
            ]}
          />
        )}
      </Block>
    </div>
  );
}

/* ── activity ──────────────────────────────────────────────────────────────*/

function ActivityTab({ tenant, activity }) {
  const toneFor = (a) =>
    /suspend|clos/i.test(a.action) ? "failed"
      : /invoice|plan/i.test(a.action) ? "info"
        : "neutral";

  return (
    <Block
      title="Activity"
      meta={
        activity.length
          ? `${activity.length} ${activity.length === 1 ? "entry" : "entries"} against this account`
          : "Nothing recorded"
      }
      note="Everything HorecaOS staff did to this customer's account, newest first. Actions the restaurant took in their own console are not here — this log is about us, and it is what an account manager reads before returning an angry call."
    >
      <Card>
        {activity.length === 0 ? (
          <div className="q-body-sm" style={{ color: inkMuted }}>
            No member of staff has touched this account. For{" "}
            {tenant.displayName} that means every change so far came from the
            customer's own console.
          </div>
        ) : (
          <Timeline
            entries={[...activity]
              .sort((a, b) => b.at.localeCompare(a.at))
              .map((a) => ({
                label: `${a.action} — ${a.note}`,
                at: dt(a.at),
                actor: a.actor,
                tone: toneFor(a),
              }))}
          />
        )}
      </Card>
    </Block>
  );
}

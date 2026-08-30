/* Payments — money in from tenants.
 *
 * This is the platform's own receivables ledger: what HorecaOS invoices a
 * restaurant for its subscription, and whether it arrived. It is not the
 * customer payment surface — a diner paying with Payme for a plov is an order
 * concern and belongs to the tenant's own console, not to this one.
 *
 * The screen is built around one fact: two invoices are in arrears, both
 * Shirinliklar, at 62 and 93 days, and together they are the whole 3 200 000
 * so'm the platform is owed. So arrears lead, before the KPI band and before
 * the ledger, and an overdue row is legible from across the desk.
 *
 * The drawer exists because "1 600 000 so'm" is not an answer to "why do I owe
 * this". Every invoice is decomposed into the plan line and the additional
 * location line, priced from PLANS, and then the location count itself is
 * traced to its source — the usage record for that month where one exists, and
 * the tenant record where it does not. An account manager on the phone with an
 * owner can read that top to bottom and defend it.
 */

import { useState } from "react";
import {
  SectionHeader, KpiTile, DataTable, StatusPill, Button, Drawer,
  Tabs, FilterBar, SearchInput, FieldGrid, Timeline, EmptyState,
  uzs, dt, day,
  ink, inkMuted, inkSubtle, hairline, canvas, surface1,
} from "./components";
import { INVOICES, USAGE, KPIS, TENANTS, SUBSCRIPTIONS, PLANS, OFFBOARDING } from "./data";

/* ── the console's today ───────────────────────────────────────────────────
 * The shell prints 21.08.2026 in its top bar, so this screen counts from the
 * same day rather than from the wall clock. A prototype whose relative dates
 * drift as it ages stops demonstrating the state it was authored to show.
 */
const TODAY = "2026-08-21";
const DAY_MS = 86_400_000;
const daysBetween = (from, to) =>
  Math.floor((new Date(to).getTime() - new Date(from).getTime()) / DAY_MS);

/** Counts, grouped like the money is — space-separated, tabular in the cell. */
const num = (n) => String(n).replace(/\B(?=(\d{3})+(?!\d))/g, " ");

/* ── local helpers ─────────────────────────────────────────────────────────
 * Four things this screen needs that the shared set does not have. They stay
 * local: an alarm band, an ageing strip, a titled drawer panel and a line-item
 * ledger are Payments arrangements, not console primitives.
 */

/** An alarm band. Hairline card, error rule down the left, no tinted fill. */
function Alarm({ children }) {
  return (
    <div
      style={{
        background: canvas,
        border: `1px solid ${hairline}`,
        borderLeft: "3px solid var(--q-error)",
        padding: 20,
        marginBottom: 24,
      }}
    >
      {children}
    </div>
  );
}

/** Arrears by age. Four cells, ink on surface-2 — a fill is not a status. */
function AgeingStrip({ buckets }) {
  const max = Math.max(...buckets.map((b) => b.amountMinor), 1);
  return (
    <div style={{ display: "grid", gridTemplateColumns: `repeat(${buckets.length}, minmax(0, 1fr))`, gap: 1, background: hairline }}>
      {buckets.map((b) => {
        const on = b.amountMinor > 0;
        return (
          <div key={b.label} style={{ background: canvas, padding: "10px 12px" }}>
            <div className="q-caption" style={{ color: inkSubtle }}>{b.label}</div>
            <div
              className="q-body-sm q-tnum"
              style={{ color: on ? ink : inkSubtle, marginTop: 4 }}
            >
              {on ? uzs(b.amountMinor) : "—"}
            </div>
            <div style={{ height: 4, background: "var(--q-surface-2)", marginTop: 8 }}>
              <div
                style={{
                  height: "100%",
                  width: `${Math.round((b.amountMinor / max) * 100)}%`,
                  background: on ? "var(--q-error)" : "transparent",
                }}
              />
            </div>
            <div className="q-caption" style={{ color: inkSubtle, marginTop: 6 }}>
              {b.count === 0 ? "No invoices" : b.count === 1 ? "1 invoice" : `${b.count} invoices`}
            </div>
          </div>
        );
      })}
    </div>
  );
}

/** A drawer section: rule, heading, a line of provenance, then the content. */
function Panel({ title, source, children, first }) {
  return (
    <section
      style={{
        borderTop: first ? "none" : `1px solid ${hairline}`,
        paddingTop: first ? 0 : 20,
        marginTop: first ? 0 : 20,
      }}
    >
      <div style={{ display: "flex", alignItems: "baseline", gap: 12, marginBottom: 12 }}>
        <h3 className="q-emphasis" style={{ margin: 0, color: ink }}>{title}</h3>
        {source ? (
          <span className="q-caption" style={{ color: inkSubtle, marginLeft: "auto", textAlign: "right" }}>
            {source}
          </span>
        ) : null}
      </div>
      {children}
    </section>
  );
}

/** The bill, decomposed. Amounts right-aligned and tabular; total on a rule. */
function LineItems({ lines, totalMinor, invoicedMinor }) {
  const diff = totalMinor - invoicedMinor;
  return (
    <div style={{ border: `1px solid ${hairline}` }}>
      {lines.map((l) => (
        <div
          key={l.label}
          style={{
            display: "flex", gap: 16, alignItems: "baseline",
            padding: "10px 12px", borderBottom: `1px solid ${hairline}`,
          }}
        >
          <div style={{ minWidth: 0, flex: 1 }}>
            <div className="q-body-sm" style={{ color: ink }}>{l.label}</div>
            <div className="q-caption" style={{ color: inkSubtle, marginTop: 2 }}>{l.detail}</div>
          </div>
          <div
            className="q-body-sm q-tnum"
            style={{ color: l.amountMinor === 0 ? inkSubtle : ink, flexShrink: 0, whiteSpace: "nowrap" }}
          >
            {uzs(l.amountMinor)}
          </div>
        </div>
      ))}
      <div
        style={{
          display: "flex", gap: 16, alignItems: "baseline",
          padding: "10px 12px", background: surface1,
        }}
      >
        <div className="q-emphasis" style={{ color: ink, flex: 1 }}>Total</div>
        <div className="q-emphasis q-tnum" style={{ color: ink, whiteSpace: "nowrap" }}>
          {uzs(totalMinor)}
        </div>
      </div>
      <div
        style={{
          display: "flex", gap: 12, alignItems: "center",
          padding: "8px 12px", borderTop: `1px solid ${hairline}`,
        }}
      >
        <StatusPill tone={diff === 0 ? "healthy" : "failed"}>
          {diff === 0 ? "Reconciled" : "Does not reconcile"}
        </StatusPill>
        <span className="q-caption" style={{ color: inkMuted }}>
          {diff === 0
            ? "The lines add up to the amount invoiced."
            : `The lines are ${uzs(Math.abs(diff))} ${diff > 0 ? "above" : "below"} the amount invoiced.`}
        </span>
      </div>
    </div>
  );
}

/* ── derivations ───────────────────────────────────────────────────────────*/

const tenantOf = (id) => TENANTS.find((t) => t.id === id);
const subOf = (id) => SUBSCRIPTIONS.find((s) => s.tenantId === id);
const usageOf = (id) => USAGE.find((u) => u.tenantId === id);
const planNamed = (name) => PLANS.find((p) => p.name === name);

const STATUS_TONE = { PAID: "healthy", SENT: "info", OVERDUE: "failed" };
const STATUS_LABEL = { PAID: "Paid", SENT: "Awaiting payment", OVERDUE: "Overdue" };

/** Days between the due date and the payment, for an invoice that was paid. */
const paidLateDays = (inv) =>
  inv.paidAt ? daysBetween(inv.dueAt, inv.paidAt) : null;

/**
 * The basis for one invoice: what it is made of, and where the quantity that
 * drives it came from. The location count is the only quantity that moves the
 * price — every plan takes 0 bps of GMV — so it is the one that has to be
 * traceable. Where a usage record exists for the tenant it is the source; where
 * it does not, the tenant record is, and the drawer says so rather than
 * quietly presenting the fallback as evidence.
 */
function basisFor(inv) {
  const sub = subOf(inv.tenantId);
  const plan = planNamed(sub?.plan);
  const usage = usageOf(inv.tenantId);
  const tenant = tenantOf(inv.tenantId);

  if (!plan) return null;

  const countedLocations = usage ? usage.locations : tenant?.locations ?? 0;
  const extra = Math.max(0, countedLocations - plan.locationsIncluded);

  const lines = [
    {
      label: `${plan.name} plan, one month`,
      detail: `${plan.locationsIncluded} ${plan.locationsIncluded === 1 ? "location" : "locations"} included`,
      amountMinor: plan.monthlyMinor,
    },
  ];
  if (extra > 0) {
    lines.push({
      label: "Additional locations",
      detail: `${extra} × ${uzs(plan.extraLocationMinor)} a month`,
      amountMinor: extra * plan.extraLocationMinor,
    });
  }
  lines.push({
    label: "Order commission",
    detail: `${plan.commissionBps} bps — the ${plan.name} plan takes no share of turnover`,
    amountMinor: 0,
  });

  return {
    plan, sub, usage, tenant, lines, extra, countedLocations,
    totalMinor: lines.reduce((sum, l) => sum + l.amountMinor, 0),
    countSource: usage
      ? { kind: "usage", label: `Usage record, ${usage.month}` }
      : { kind: "tenant", label: "Tenant record" },
  };
}

/** The invoice's own dated facts, and nothing invented around them. */
function timelineFor(inv) {
  const tenant = tenantOf(inv.tenantId);
  const entries = [
    { label: "Issued", at: day(inv.issuedAt), tone: "info" },
    {
      label: inv.issuedAt === inv.dueAt ? "Due on issue" : "Payment due",
      at: day(inv.dueAt),
      tone: inv.status === "OVERDUE" ? "failed" : "neutral",
    },
  ];
  if (inv.status === "PAID") {
    const late = paidLateDays(inv);
    entries.push({
      label: `Payment received by ${inv.method.toLowerCase()}`,
      at: dt(inv.paidAt),
      actor: late > 0 ? `${late} ${late === 1 ? "day" : "days"} after the due date` : "On or before the due date",
      tone: "healthy",
    });
  }
  if (inv.status === "OVERDUE") {
    entries.push({
      label: `Unpaid for ${inv.daysOverdue} days`,
      at: `As at ${day(TODAY)}`,
      tone: "failed",
    });
  }
  if (tenant?.suspendedAt && inv.status === "OVERDUE") {
    entries.push({
      label: "Tenant suspended",
      at: dt(tenant.suspendedAt),
      actor: tenant.suspendedReason,
      tone: "failed",
    });
  }
  const off = OFFBOARDING.find((o) => o.tenantId === inv.tenantId && o.closedAt);
  if (off && inv.status === "PAID") {
    entries.push({ label: "Account closed", at: day(off.closedAt), actor: off.reason, tone: "neutral" });
  }
  return entries;
}

const TABS = [
  { id: "all", label: "All" },
  { id: "overdue", label: "Overdue" },
  { id: "sent", label: "Awaiting payment" },
  { id: "paid", label: "Paid" },
];
const TAB_STATUS = { overdue: "OVERDUE", sent: "SENT", paid: "PAID" };

/* What this ledger does not have, said out loud. A finance person who assumes
 * a refund would appear here and finds it does not has been misled by the
 * prototype, which is worse than the prototype being incomplete. */
const ABSENT = [
  "Refunds and credit notes — no fixture, and no decision on who may issue one.",
  "VAT presentation — the rate is configured at 12% but no invoice here breaks it out.",
  "Provider settlement — what Payme and Click actually remitted, against what they were meant to.",
  "The dunning schedule — reminders are sent by hand today, so there is nothing to show.",
];

export default function Payments({ filter, setFilter, invoiceId, setInvoiceId }) {
  const [query, setQuery] = useState("");

  /* ── the ledger ─────────────────────────────────────────────────────────
   * Overdue first and oldest first, then everything else by due date. The sort
   * is not a preference: an operator opening this screen is looking for the
   * money that has not arrived, and it should never be below the fold.
   */
  const ordered = [...INVOICES].sort((a, b) => {
    const late = (i) => (i.status === "OVERDUE" ? 0 : 1);
    if (late(a) !== late(b)) return late(a) - late(b);
    if (a.status === "OVERDUE") return b.daysOverdue - a.daysOverdue;
    return new Date(a.dueAt) - new Date(b.dueAt);
  });

  const q = query.trim().toLowerCase();
  const rows = ordered.filter((i) => {
    if (filter !== "all" && i.status !== TAB_STATUS[filter]) return false;
    if (!q) return true;
    return (
      i.id.toLowerCase().includes(q) ||
      i.tenant.toLowerCase().includes(q) ||
      (i.method || "").toLowerCase().includes(q)
    );
  });

  const counts = {
    all: INVOICES.length,
    overdue: INVOICES.filter((i) => i.status === "OVERDUE").length,
    sent: INVOICES.filter((i) => i.status === "SENT").length,
    paid: INVOICES.filter((i) => i.status === "PAID").length,
  };

  const overdue = INVOICES.filter((i) => i.status === "OVERDUE");
  const awaiting = INVOICES.filter((i) => i.status === "SENT");
  const paid = INVOICES.filter((i) => i.status === "PAID");
  const sum = (list) => list.reduce((t, i) => t + i.amountMinor, 0);

  const oldest = overdue.reduce((a, b) => (a && a.daysOverdue > b.daysOverdue ? a : b), null);
  const arrearsTenants = [...new Set(overdue.map((i) => i.tenant))];
  const arrearsPlan = subOf(overdue[0]?.tenantId)?.plan;
  const arrearsSuspendedAt = tenantOf(overdue[0]?.tenantId)?.suspendedAt;
  const arrearsSince = overdue.length
    ? overdue.reduce((a, b) => (new Date(a.dueAt) < new Date(b.dueAt) ? a : b)).dueAt
    : null;

  /* KPIS.arrearsMinor is the figure the rest of the console quotes, so it is
   * the figure shown. The derived sum is checked against it rather than
   * replacing it — two numbers for one fact is how a console loses trust. */
  const derivedArrears = sum(overdue);
  const arrearsAgrees = derivedArrears === KPIS.arrearsMinor;

  const buckets = [
    { label: "1–30 days", ...bucket(overdue, 1, 30) },
    { label: "31–60 days", ...bucket(overdue, 31, 60) },
    { label: "61–90 days", ...bucket(overdue, 61, 90) },
    { label: "Over 90 days", ...bucket(overdue, 91, Infinity) },
  ];

  const open = invoiceId ? INVOICES.find((i) => i.id === invoiceId) : null;
  const basis = open ? basisFor(open) : null;

  const columns = [
    {
      key: "id", label: "Invoice",
      render: (v, row) => (
        <span style={{ display: "inline-flex", alignItems: "center", gap: 8 }}>
          <span
            style={{
              width: 3, height: 20, flexShrink: 0,
              background: row.status === "OVERDUE" ? "var(--q-error)" : "transparent",
            }}
          />
          <span style={{ fontFamily: "var(--q-font-mono)", whiteSpace: "nowrap" }}>{v}</span>
        </span>
      ),
    },
    {
      key: "tenant", label: "Tenant",
      render: (v, row) => {
        const t = tenantOf(row.tenantId);
        return (
          <div style={{ minWidth: 0 }}>
            <div style={{ color: ink }}>{v}</div>
            {t?.status === "SUSPENDED" ? (
              <div className="q-caption" style={{ color: "var(--q-error-text)", marginTop: 2 }}>
                Suspended {day(t.suspendedAt)}
              </div>
            ) : t?.status === "CLOSED" ? (
              <div className="q-caption" style={{ color: inkSubtle, marginTop: 2 }}>
                Closed {day(t.closedAt)}
              </div>
            ) : null}
          </div>
        );
      },
    },
    { key: "issuedAt", label: "Issued", render: (v) => <span className="q-tnum">{day(v)}</span> },
    {
      key: "dueAt", label: "Due",
      render: (v, row) => {
        const inDays = daysBetween(TODAY, v);
        return (
          <div style={{ minWidth: 0 }}>
            <div className="q-tnum" style={{ color: row.status === "OVERDUE" ? "var(--q-error-text)" : ink }}>
              {day(v)}
            </div>
            {row.status === "SENT" ? (
              <div className="q-caption" style={{ color: inkSubtle, marginTop: 2 }}>
                {inDays <= 0 ? "Due today" : inDays === 1 ? "Due tomorrow" : `In ${inDays} days`}
              </div>
            ) : null}
          </div>
        );
      },
    },
    {
      key: "daysOverdue", label: "Days late", align: "right",
      render: (v, row) => {
        if (row.status === "OVERDUE") {
          return (
            <span className="q-emphasis q-tnum" style={{ color: "var(--q-error-text)" }}>{v}</span>
          );
        }
        const late = paidLateDays(row);
        if (late && late > 0) return <span className="q-tnum" style={{ color: inkMuted }}>{late}</span>;
        return <span style={{ color: inkSubtle }}>—</span>;
      },
    },
    {
      key: "amountMinor", label: "Amount", align: "right",
      render: (v, row) => (
        <span
          className={row.status === "OVERDUE" ? "q-emphasis q-tnum" : "q-tnum"}
          style={{ color: row.status === "OVERDUE" ? "var(--q-error-text)" : ink, whiteSpace: "nowrap" }}
        >
          {uzs(v)}
        </span>
      ),
    },
    {
      key: "status", label: "Status",
      render: (v) => <StatusPill tone={STATUS_TONE[v]}>{STATUS_LABEL[v]}</StatusPill>,
    },
    {
      key: "method", label: "Method",
      render: (v) => (v ? v : <span style={{ color: inkSubtle }}>Not attempted</span>),
    },
    {
      key: "paidAt", label: "Paid",
      render: (v) => (v ? <span className="q-tnum">{dt(v)}</span> : <span style={{ color: inkSubtle }}>—</span>),
    },
  ];

  return (
    <div>
      <SectionHeader
        title="Payments"
        description={
          "What tenants owe HorecaOS for their subscription, and whether it arrived. " +
          "Customer payments for orders are a tenant concern and are not in this ledger."
        }
      />

      {/* ── arrears, first and loudest ───────────────────────────────────── */}
      {overdue.length ? (
        <Alarm>
          <div style={{ display: "flex", gap: 24, alignItems: "flex-start", flexWrap: "wrap" }}>
            <div style={{ minWidth: 240 }}>
              <div className="q-caption" style={{ color: inkMuted }}>In arrears</div>
              <div className="q-data-lg" style={{ color: "var(--q-error-text)", marginTop: 4 }}>
                {uzs(KPIS.arrearsMinor)}
              </div>
              <div className="q-caption" style={{ color: inkSubtle, marginTop: 4 }}>
                {arrearsAgrees
                  ? `${overdue.length} invoices, all ${arrearsTenants.join(" and ")}`
                  : `Ledger shows ${uzs(derivedArrears)} — the two do not agree`}
              </div>
            </div>
            <div style={{ flex: 1, minWidth: 320 }}>
              <p className="q-body-sm" style={{ margin: 0, color: ink, maxWidth: 560 }}>
                {arrearsTenants.join(" and ")} has been unpaid since {day(arrearsSince)}.{" "}
                {overdue.length} months of the {arrearsPlan} plan are outstanding, at{" "}
                {overdue.map((i) => `${i.daysOverdue} days`).join(" and ")}.{" "}
                {arrearsSuspendedAt
                  ? `The account was suspended on ${day(arrearsSuspendedAt)}, so nothing further is accruing.`
                  : "The account is still live, so a third month will be issued on renewal."}
              </p>
              <div style={{ display: "flex", gap: 8, marginTop: 12, flexWrap: "wrap" }}>
                <Button size="sm" onClick={() => setInvoiceId(oldest.id)}>
                  Open the {oldest.daysOverdue}-day invoice
                </Button>
                <Button size="sm" variant="tertiary" onClick={() => setFilter("overdue")}>
                  Show only overdue
                </Button>
              </div>
            </div>
          </div>
          <div style={{ marginTop: 20 }}>
            <AgeingStrip buckets={buckets} />
          </div>
        </Alarm>
      ) : null}

      {/* ── the money band ───────────────────────────────────────────────── */}
      <div style={{ display: "grid", gridTemplateColumns: "repeat(4, minmax(0, 1fr))", gap: 16, marginBottom: 32 }}>
        <KpiTile
          label="Collected"
          value={uzs(sum(paid))}
          meta={`${paid.length} invoices settled`}
        />
        <KpiTile
          label="Awaiting payment"
          value={uzs(sum(awaiting))}
          meta={
            awaiting.length
              ? `${awaiting[0].tenant}, due ${day(awaiting[0].dueAt)}`
              : "Nothing outstanding"
          }
        />
        <KpiTile
          label="In arrears"
          value={uzs(KPIS.arrearsMinor)}
          meta={oldest ? `Oldest ${oldest.daysOverdue} days` : "Nothing in arrears"}
        />
        <KpiTile
          label="Collection rate"
          value={`${Math.round((sum(paid) / (sum(paid) + KPIS.arrearsMinor)) * 100)}%`}
          meta="Settled against settled plus arrears"
        />
      </div>

      {/* ── the ledger ───────────────────────────────────────────────────── */}
      <Tabs
        tabs={TABS.map((t) => ({ ...t, count: counts[t.id] }))}
        active={filter}
        onChange={setFilter}
      />

      <FilterBar>
        <SearchInput
          value={query}
          onChange={setQuery}
          placeholder="Search invoices and tenants"
        />
        <span className="q-caption" style={{ color: inkSubtle, marginLeft: "auto" }}>
          {rows.length} of {INVOICES.length} invoices · overdue first
        </span>
      </FilterBar>

      <DataTable
        columns={columns}
        rows={rows}
        onRowClick={(row) => setInvoiceId(row.id)}
        selectedId={invoiceId}
        empty={
          <EmptyState
            title="No invoices match this filter"
            description="Clear the search, or move back to all invoices."
            action={<Button size="sm" variant="tertiary" onClick={() => { setQuery(""); setFilter("all"); }}>Show all invoices</Button>}
          />
        }
      />

      <div className="q-caption" style={{ color: inkSubtle, marginTop: 8, maxWidth: 760 }}>
        Days late counts from the due date. Red is an invoice still unpaid today; grey is one
        that was paid, but after its due date. Open a row for what the amount is made of.
      </div>

      {/* ── what this ledger does not carry ──────────────────────────────── */}
      <section style={{ marginTop: 32 }}>
        <h2 className="q-subhead" style={{ margin: "0 0 12px", color: ink }}>Not in this ledger</h2>
        <div style={{ border: `1px solid ${hairline}`, background: canvas }}>
          {ABSENT.map((a) => (
            <div
              key={a}
              style={{
                display: "flex", gap: 12, alignItems: "flex-start",
                padding: "10px 16px", borderBottom: `1px solid ${hairline}`,
              }}
            >
              <div className="q-body-sm" style={{ color: inkMuted, flex: 1, minWidth: 0 }}>{a}</div>
              <div style={{ flexShrink: 0 }}><StatusPill tone="neutral">Not built</StatusPill></div>
            </div>
          ))}
        </div>
      </section>

      {/* ── detail ───────────────────────────────────────────────────────── */}
      {open ? (
        <Drawer title={open.id} onClose={() => setInvoiceId(null)} width={620}>
          {/* Headline: the amount, its state, and how late it is. */}
          <div style={{ display: "flex", gap: 24, alignItems: "flex-start", marginBottom: 20 }}>
            <div style={{ minWidth: 0 }}>
              <div className="q-caption" style={{ color: inkSubtle }}>Amount invoiced</div>
              <div
                className="q-data-lg"
                style={{ color: open.status === "OVERDUE" ? "var(--q-error-text)" : ink, marginTop: 4 }}
              >
                {uzs(open.amountMinor)}
              </div>
            </div>
            <div style={{ marginLeft: "auto", flexShrink: 0, textAlign: "right" }}>
              <StatusPill tone={STATUS_TONE[open.status]}>{STATUS_LABEL[open.status]}</StatusPill>
              {open.status === "OVERDUE" ? (
                <div className="q-emphasis" style={{ color: "var(--q-error-text)", marginTop: 8 }}>
                  {open.daysOverdue} days past due
                </div>
              ) : null}
            </div>
          </div>

          <Panel first title="Invoice">
            <FieldGrid
              columns={3}
              fields={[
                { label: "Tenant", value: open.tenant },
                { label: "Plan", value: basis ? `${basis.plan.name}${basis.sub.status === "ACTIVE" ? "" : ` · ${basis.sub.status.toLowerCase().replace("_", " ")}`}` : "—" },
                { label: "City", value: basis?.tenant?.city },
                { label: "Issued", value: day(open.issuedAt) },
                { label: "Due", value: day(open.dueAt) },
                { label: "Paid", value: open.paidAt ? dt(open.paidAt) : "Not paid" },
                { label: "Method", value: open.method ?? "No payment attempted" },
                { label: "Owner", value: basis?.tenant?.owner },
                { label: "Owner phone", value: basis?.tenant?.ownerPhone },
              ]}
            />
          </Panel>

          {basis ? (
            <>
              <Panel title="What the amount is made of" source={`Priced from the ${basis.plan.name} plan`}>
                <LineItems
                  lines={basis.lines}
                  totalMinor={basis.totalMinor}
                  invoicedMinor={open.amountMinor}
                />
              </Panel>

              <Panel
                title="Where the location count comes from"
                source={basis.countSource.label}
              >
                {basis.usage ? (
                  <>
                    <FieldGrid
                      columns={3}
                      fields={[
                        { label: "Locations counted", value: `${basis.usage.locations} · ${basis.plan.locationsIncluded} included, ${basis.extra} charged extra` },
                        { label: "Orders processed", value: num(basis.usage.ordersProcessed) },
                        { label: "Turnover", value: uzs(basis.usage.gmvMinor) },
                        { label: "SMS sent", value: num(basis.usage.smsSent) },
                        { label: "Storage", value: `${basis.usage.storageGb} GB` },
                        { label: "Usage month", value: basis.usage.month },
                      ]}
                    />
                    <div className="q-caption" style={{ color: inkMuted, marginTop: 12 }}>
                      Only the location count changes what this invoice costs — the {basis.plan.name}{" "}
                      plan takes {basis.plan.commissionBps} bps of turnover, so orders, SMS and
                      storage are recorded for the plan conversation rather than billed.
                    </div>
                  </>
                ) : (
                  <div style={{ border: `1px solid ${hairline}`, padding: 16 }}>
                    <div className="q-body-sm" style={{ color: ink }}>
                      No usage was recorded for {basis.tenant?.displayName} in the month this
                      invoice covers.
                    </div>
                    <div className="q-caption" style={{ color: inkMuted, marginTop: 8 }}>
                      Usage is only retained for the current month, and this invoice is from{" "}
                      {day(open.issuedAt)}. The location count of {basis.countedLocations} is the
                      tenant record's, which is what the subscription was priced on — weaker
                      evidence than a usage record, and named as such rather than presented as one.
                    </div>
                  </div>
                )}
              </Panel>
            </>
          ) : (
            <Panel title="What the amount is made of">
              <EmptyState
                title="No subscription is attached to this tenant"
                description="Without a plan there is nothing to price the invoice against."
              />
            </Panel>
          )}

          <Panel title="History" source="Dated facts only">
            <Timeline entries={timelineFor(open)} />
          </Panel>

          <Panel title="Actions">
            <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
              <Button size="sm" disabled>Record a payment</Button>
              <Button size="sm" variant="tertiary" disabled>Send a reminder</Button>
              <Button size="sm" variant="ghost" disabled>Download the invoice</Button>
            </div>
            <div className="q-caption" style={{ color: inkSubtle, marginTop: 12 }}>
              Disabled on purpose. Recording a payment, dunning and invoice documents have no
              decision behind them yet, and a button that appears to work in a prototype gets
              remembered as a feature that exists.
            </div>
          </Panel>
        </Drawer>
      ) : null}
    </div>
  );
}

/** Arrears in one age band. Kept below the component it serves, as a leaf. */
function bucket(overdue, from, to) {
  const inBand = overdue.filter((i) => i.daysOverdue >= from && i.daysOverdue <= to);
  return { amountMinor: inBand.reduce((t, i) => t + i.amountMinor, 0), count: inBand.length };
}

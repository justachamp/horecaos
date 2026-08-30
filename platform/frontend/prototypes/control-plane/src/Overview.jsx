/* Overview — the control-plane home.
 *
 * This is the commercial board, not an engineering one. The person reading it
 * runs HorecaOS as a business: they want to know which customer needs attention
 * before lunch, and why. So the shape is a KPI band, then three lists of work —
 * customers at risk, customers arriving, and customers who owe money — and only
 * then a fortnight of trade, which is context rather than a task.
 *
 * Every number on the band is derived from the same fixtures the tables below
 * read, so a total and its list can never disagree on screen. Where a KPI exists
 * in KPIS as a headline figure, the second line under it is computed, which is
 * how you catch a board that has quietly gone stale.
 *
 * Navigation is deliberate and asymmetric, see the note at ROW ROUTING below.
 */

import { useState } from "react";
import {
  KpiTile, DataTable, StatusPill, Button, Card, Field,
  uzs, dt, day,
  ink, inkMuted, inkSubtle, hairline, blue,
} from "./components";
import {
  KPIS, TENANTS, ONBOARDING_PIPELINE, INVOICES, SUBSCRIPTIONS,
  OFFBOARDING, DAILY,
} from "./data";

/* ── local helpers ─────────────────────────────────────────────────────────
 * Four small things this screen needs that the shared set does not have. They
 * stay in this file rather than going into components.jsx: a block header is an
 * Overview arrangement, and the bar strip is the only chart in the prototype.
 */

/** The board's "today". The shell header states the same date. */
const TODAY = "2026-08-21";

/** A date-only fixture string parses as UTC and can slip a day west of Tashkent.
 *  Pinning it to local midnight before formatting keeps 22.08 reading as 22.08. */
const atMidnight = (s) => (s && s.length === 10 ? `${s}T00:00:00` : s);
const dayOf = (s) => day(atMidnight(s));

/** Counts, grouped the way uzs() groups money, so a column of orders and a
 *  column of som break at the same places. uzs() is for money only. */
const num = (n) => String(n).replace(/\B(?=(\d{3})+(?!\d))/g, " ");

const daysFromToday = (s) =>
  Math.round(
    (new Date(atMidnight(s)) - new Date(atMidnight(TODAY))) / 86_400_000
  );

/** A titled band: heading, a line of scanning context, and one way onward. */
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

/** An inline link out of a table cell. Used where a row's own click goes
 *  somewhere else, so the cell has to say plainly that it goes elsewhere. */
function TenantLink({ tenantId, children, onOpenTenant }) {
  const known = TENANTS.some((t) => t.id === tenantId);
  if (!known) {
    return (
      <span style={{ color: ink }}>
        {children}
        <span className="q-caption" style={{ color: inkSubtle, marginLeft: 8 }}>no account yet</span>
      </span>
    );
  }
  return (
    <button
      type="button"
      className="q-body-sm"
      onClick={(e) => { e.stopPropagation(); onOpenTenant(tenantId); }}
      style={{
        background: "transparent", border: "none", padding: 0, margin: 0,
        color: blue, cursor: "pointer", textAlign: "left",
        textDecoration: "underline", textUnderlineOffset: 2,
      }}
    >
      {children}
    </button>
  );
}

/** A cell that has to carry a sentence. Tables here are dense; the reason a
 *  customer is at risk is the one column allowed to wrap. */
function Reason({ children }) {
  return (
    <span style={{ color: inkMuted, whiteSpace: "normal", display: "inline-block", maxWidth: 260 }}>
      {children}
    </span>
  );
}

/** Fourteen plain divs. Ink where every live tenant was trading, subtle where
 *  one was not — the suspension is visible in the strip, which is the point. */
function TradeStrip({ rows, onHover, hovered }) {
  const max = Math.max(...rows.map((r) => r.orders));
  const full = Math.max(...rows.map((r) => r.activeTenants));
  return (
    <div>
      <div
        style={{
          display: "flex", alignItems: "flex-end", gap: 4, height: 96,
          borderBottom: `1px solid ${hairline}`,
        }}
        onMouseLeave={() => onHover(null)}
      >
        {rows.map((r, i) => (
          <div
            key={r.date}
            onMouseEnter={() => onHover(i)}
            style={{ flex: 1, minWidth: 0, height: "100%", display: "flex", alignItems: "flex-end", cursor: "default" }}
          >
            <div
              style={{
                width: "100%",
                height: `${Math.max(2, (r.orders / max) * 100)}%`,
                background: r.activeTenants < full ? "var(--q-surface-2)" : ink,
                outline: hovered === i ? `1px solid ${blue}` : "none",
                transition: "opacity var(--q-dur-fast) var(--q-ease-productive)",
                opacity: hovered === null || hovered === i ? 1 : 0.55,
              }}
            />
          </div>
        ))}
      </div>
      <div style={{ display: "flex", gap: 4, marginTop: 6 }}>
        {rows.map((r) => (
          <div
            key={r.date}
            className="q-caption q-tnum"
            style={{ flex: 1, minWidth: 0, textAlign: "center", color: inkSubtle }}
          >
            {dayOf(r.date).slice(0, 2)}
          </div>
        ))}
      </div>
    </div>
  );
}

/* ── derivations ───────────────────────────────────────────────────────────*/

const tenantById = (id) => TENANTS.find((t) => t.id === id);

const TENANT_STATUS_TONE = {
  ACTIVE: "active",
  PENDING: "pending",
  SUSPENDED: "suspended",
  CLOSED: "neutral",
};

const INVOICE_TONE = { PAID: "healthy", SENT: "pending", OVERDUE: "failed" };

export default function Overview({ onNavigate, onOpenTenant }) {
  const [hoverDay, setHoverDay] = useState(null);

  /* Who needs a person today: anything the health signal calls at-risk, plus a
   * suspension, which is at-risk whatever the health field says. */
  const atRisk = TENANTS
    .filter((t) => t.health === "at-risk" || t.status === "SUSPENDED")
    .map((t) => {
      const arrears = INVOICES
        .filter((i) => i.tenantId === t.id && i.status === "OVERDUE")
        .reduce((sum, i) => sum + i.amountMinor, 0);
      const sub = SUBSCRIPTIONS.find((s) => s.tenantId === t.id);
      return {
        ...t,
        arrears,
        renewsAt: sub?.renewsAt ?? null,
        reason: t.suspendedReason || t.healthNote || "Flagged by the health review",
        since: t.suspendedAt ? `Suspended ${dt(t.suspendedAt)}` : null,
      };
    })
    .sort((a, b) => b.arrears - a.arrears);

  const suspendedCount = atRisk.filter((t) => t.status === "SUSPENDED").length;

  /* Onboarding. A stalled run is the only thing on this block worth a heading. */
  const pipeline = ONBOARDING_PIPELINE
    .map((p) => ({ ...p, id: p.tenantId, dueIn: daysFromToday(p.target) }))
    .sort((a, b) => Number(b.stalled) - Number(a.stalled) || a.dueIn - b.dueIn);
  const stalled = pipeline.filter((p) => p.stalled);

  /* Receivables. Overdue first, then what falls due next — an invoice due
   * tomorrow is not yet a problem, but it is the reason to make one call
   * instead of two. */
  const receivable = INVOICES
    .filter((i) => i.status === "OVERDUE" || i.status === "SENT")
    .map((i) => ({
      ...i,
      overdue: i.daysOverdue ?? Math.max(0, -daysFromToday(i.dueAt)),
      dueIn: daysFromToday(i.dueAt),
      plan: tenantById(i.tenantId)?.plan ?? "—",
      tenantStatus: tenantById(i.tenantId)?.status ?? "—",
    }))
    .sort((a, b) => b.overdue - a.overdue || a.dueIn - b.dueIn);

  const overdue = receivable.filter((i) => i.status === "OVERDUE");
  const arrearsTotal = overdue.reduce((s, i) => s + i.amountMinor, 0);
  const oldestOverdue = overdue.reduce((m, i) => Math.max(m, i.overdue), 0);
  const closureRisk = OFFBOARDING.find((o) => o.stage === "AT_RISK");

  /* Subscriptions behind the recurring revenue figure. */
  const activeSubs = SUBSCRIPTIONS.filter((s) => s.status === "ACTIVE").length;
  const trialSubs = SUBSCRIPTIONS.filter((s) => s.status === "TRIAL").length;

  /* The churn the board is allowed to forget about, and should not. */
  const churned = TENANTS.filter((t) => t.status === "CLOSED");

  /* Fourteen days of trade. */
  const orders14 = DAILY.reduce((s, d) => s + d.orders, 0);
  const gmv14 = DAILY.reduce((s, d) => s + d.gmvMinor, 0);
  const basket30 = Math.round(KPIS.gmvLast30Minor / KPIS.ordersLast30);
  const shown = hoverDay === null ? null : DAILY[hoverDay];

  return (
    <div>
      <div style={{ display: "flex", alignItems: "flex-start", gap: 16, marginBottom: 24 }}>
        <div style={{ minWidth: 0 }}>
          <h1 className="q-title" style={{ margin: 0, color: ink }}>Overview</h1>
          <p className="q-body-sm" style={{ margin: "4px 0 0", color: inkMuted, maxWidth: 720 }}>
            How the business is doing, and which customer needs a person today.
            Each list ends in the section that owns the work.
          </p>
        </div>
        <div style={{ marginLeft: "auto", flexShrink: 0 }}>
          <Button variant="primary" size="sm" onClick={() => onNavigate("tenants")}>
            Open the tenant directory
          </Button>
        </div>
      </div>

      {/* ── KPI band ─────────────────────────────────────────────────────────
        * Eight tiles over two rows of six columns. The money tiles take two
        * columns each because a billion som is eighteen characters wide and a
        * figure that wraps is a figure nobody trusts. */}
      <div
        style={{
          display: "grid",
          gridTemplateColumns: "repeat(6, minmax(0, 1fr))",
          gap: 16,
          marginBottom: 32,
        }}
      >
        <KpiTile
          label="Live tenants"
          value={KPIS.tenantsLive}
          meta={`${TENANTS.length} accounts in the directory`}
        />
        <KpiTile
          label="Onboarding"
          value={KPIS.tenantsOnboarding}
          meta={stalled.length ? `${stalled.length} stalled` : "all moving"}
        />
        <KpiTile
          label="At risk"
          value={KPIS.tenantsAtRisk}
          meta={`${suspendedCount} suspended · ${KPIS.tenantsAtRisk - suspendedCount} losing volume`}
        />
        <KpiTile
          label="Closed, last 90 days"
          value={KPIS.churnedLast90}
          meta={churned.length ? `${churned[0].displayName} · ${dayOf(churned[0].closedAt)}` : "none"}
        />
        <div style={{ gridColumn: "span 2" }}>
          <KpiTile
            label="Recurring revenue, monthly"
            value={uzs(KPIS.mrrMinor)}
            meta={`${activeSubs} active subscriptions · ${trialSubs} on trial`}
          />
        </div>

        <div style={{ gridColumn: "span 2" }}>
          <KpiTile
            label="Arrears"
            value={uzs(KPIS.arrearsMinor)}
            meta={`${overdue.length} invoices overdue · oldest ${oldestOverdue} days`}
          />
        </div>
        <div style={{ gridColumn: "span 2" }}>
          <KpiTile
            label="Orders, last 30 days"
            value={num(KPIS.ordersLast30)}
            meta={`across ${KPIS.tenantsLive} trading tenants`}
          />
        </div>
        <div style={{ gridColumn: "span 2" }}>
          <KpiTile
            label="Sales through the platform, last 30 days"
            value={uzs(KPIS.gmvLast30Minor)}
            meta={`average basket ${uzs(basket30)}`}
          />
        </div>
      </div>

      {/* ── at risk ─────────────────────────────────────────────────────────
        * ROW ROUTING. A row that stands for a real account opens that account;
        * a row that stands for a piece of work opens the section that owns the
        * work. So a tenant row here calls onOpenTenant, a pipeline row below
        * opens onboarding with the tenant name left as an explicit link, and an
        * invoice row opens payments. Sending every row to the same place would
        * be tidier and would lose the distinction the operator relies on. */}
      <Block
        title="Tenants at risk"
        meta={`${atRisk.length} accounts · ${uzs(arrearsTotal)} in arrears`}
        action={
          <Button variant="ghost" size="sm" onClick={() => onNavigate("tenants")}>
            All tenants
          </Button>
        }
        note="A row opens the account. The reason is the tenant's own health note or its suspension reason — nothing here is inferred on this screen, so what an account manager reads is what the account record says."
      >
        <DataTable
          columns={[
            {
              key: "displayName", label: "Tenant",
              render: (v, row) => (
                <span>
                  <span className="q-emphasis" style={{ color: ink }}>{v}</span>
                  <span className="q-caption" style={{ color: inkSubtle, marginLeft: 8 }}>{row.city}</span>
                </span>
              ),
            },
            { key: "plan", label: "Plan" },
            {
              key: "status", label: "Status",
              render: (v) => (
                <StatusPill tone={TENANT_STATUS_TONE[v] || "neutral"}>{v.toLowerCase()}</StatusPill>
              ),
            },
            {
              key: "ordersLast30", label: "Orders, 30 days", align: "right",
              render: (v) => (v ? num(v) : <span style={{ color: inkSubtle }}>0</span>),
            },
            {
              key: "gmvLast30Minor", label: "Sales, 30 days", align: "right",
              render: (v) => (v ? uzs(v) : <span style={{ color: inkSubtle }}>—</span>),
            },
            {
              key: "arrears", label: "Arrears", align: "right",
              render: (v) =>
                v ? (
                  <span className="q-emphasis" style={{ color: "var(--q-error-text)" }}>{uzs(v)}</span>
                ) : (
                  <span style={{ color: inkSubtle }}>—</span>
                ),
            },
            {
              key: "renewsAt", label: "Renews",
              render: (v) =>
                !v ? (
                  <span style={{ color: inkSubtle }}>—</span>
                ) : daysFromToday(v) < 0 ? (
                  /* A past renewal date is a subscription that never renewed,
                   * not a renewal. Saying "renews" over it would be a lie. */
                  <span style={{ color: inkMuted }}>lapsed {dayOf(v)}</span>
                ) : (
                  dayOf(v)
                ),
            },
            { key: "owner", label: "Owner" },
            {
              key: "ownerPhone", label: "Phone",
              render: (v) => (
                <span style={{ fontFamily: "var(--q-font-mono)", color: inkMuted, whiteSpace: "nowrap" }}>{v}</span>
              ),
            },
            {
              key: "reason", label: "Why",
              render: (v, row) => (
                <Reason>
                  {v}
                  {row.since ? (
                    <span className="q-caption" style={{ color: inkSubtle, display: "block", marginTop: 2 }}>
                      {row.since}
                    </span>
                  ) : null}
                </Reason>
              ),
            },
          ]}
          rows={atRisk}
          onRowClick={(row) => onOpenTenant(row.id)}
        />
      </Block>

      {/* ── onboarding pipeline ─────────────────────────────────────────────*/}
      <Block
        title="Onboarding pipeline"
        meta={
          stalled.length
            ? `${pipeline.length} in flight · ${stalled.length} stalled`
            : `${pipeline.length} in flight`
        }
        action={
          <Button variant="ghost" size="sm" onClick={() => onNavigate("onboarding")}>
            Open onboarding
          </Button>
        }
        note="A run is stalled when the step in front of it belongs to the restaurant rather than to HorecaOS. Anor has been waiting on its owner for nine days and is four days from a target go-live that will now move; that is a phone call, not a process change."
      >
        <DataTable
          columns={[
            {
              key: "tenant", label: "Tenant",
              render: (v, row) => (
                <TenantLink tenantId={row.tenantId} onOpenTenant={onOpenTenant}>{v}</TenantLink>
              ),
            },
            { key: "city", label: "City" },
            { key: "plan", label: "Plan" },
            { key: "stage", label: "Current step" },
            { key: "daysOpen", label: "Days open", align: "right" },
            {
              key: "target", label: "Target go-live",
              render: (v) => dayOf(v),
            },
            {
              key: "dueIn", label: "Left", align: "right",
              render: (v) =>
                v < 0 ? (
                  <span className="q-emphasis" style={{ color: "var(--q-error-text)" }}>{Math.abs(v)} days late</span>
                ) : (
                  <span style={{ color: inkMuted }}>{v} days</span>
                ),
            },
            { key: "manager", label: "Account manager" },
            {
              key: "stalled", label: "State",
              render: (v) =>
                v ? <StatusPill tone="degraded">stalled</StatusPill> : <StatusPill tone="active">moving</StatusPill>,
            },
            {
              key: "stalledReason", label: "Waiting on",
              render: (v, row) =>
                row.stalled ? <Reason>{v}</Reason> : <span style={{ color: inkSubtle }}>HorecaOS holds the next step</span>,
            },
          ]}
          rows={pipeline}
          onRowClick={() => onNavigate("onboarding")}
        />
      </Block>

      {/* ── receivables ─────────────────────────────────────────────────────*/}
      <Block
        title="Money owed"
        meta={(() => {
          const open = receivable.length - overdue.length;
          return `${uzs(arrearsTotal)} overdue · ${open} ${open === 1 ? "invoice" : "invoices"} awaiting payment`;
        })()}
        action={
          <Button variant="ghost" size="sm" onClick={() => onNavigate("payments")}>
            Open payments
          </Button>
        }
        note={
          closureRisk
            ? `${closureRisk.tenant} is logged against offboarding as "${closureRisk.reason.toLowerCase()}", with a final invoice of ${uzs(closureRisk.finalInvoiceMinor)} unsettled. Two months of unpaid basic subscription is not worth a closure on its own; the decision belongs to finance, and the board's job is to make sure it is a decision rather than a drift.`
            : null
        }
      >
        <DataTable
          columns={[
            {
              key: "id", label: "Invoice",
              render: (v) => <span style={{ fontFamily: "var(--q-font-mono)", color: ink }}>{v}</span>,
            },
            {
              key: "tenant", label: "Tenant",
              render: (v, row) => (
                <TenantLink tenantId={row.tenantId} onOpenTenant={onOpenTenant}>{v}</TenantLink>
              ),
            },
            { key: "plan", label: "Plan" },
            {
              key: "tenantStatus", label: "Account",
              render: (v) => (
                <StatusPill tone={TENANT_STATUS_TONE[v] || "neutral"}>{v.toLowerCase()}</StatusPill>
              ),
            },
            { key: "issuedAt", label: "Issued", render: (v) => dayOf(v) },
            { key: "dueAt", label: "Due", render: (v) => dayOf(v) },
            {
              key: "overdue", label: "Overdue", align: "right",
              render: (v, row) =>
                v > 0 ? (
                  <span className="q-emphasis" style={{ color: "var(--q-error-text)" }}>{v} days</span>
                ) : (
                  <span style={{ color: inkSubtle }}>
                    {row.dueIn === 0
                      ? "due today"
                      : row.dueIn === 1
                        ? "due tomorrow"
                        : `due in ${row.dueIn} days`}
                  </span>
                ),
            },
            {
              key: "amountMinor", label: "Amount", align: "right",
              render: (v) => uzs(v),
            },
            {
              key: "method", label: "Method",
              render: (v) => v || <span style={{ color: inkSubtle }}>not chosen</span>,
            },
            {
              key: "status", label: "Status",
              render: (v) => <StatusPill tone={INVOICE_TONE[v] || "neutral"}>{v.toLowerCase()}</StatusPill>,
            },
          ]}
          rows={receivable}
          onRowClick={() => onNavigate("payments")}
        />
      </Block>

      {/* ── trade ───────────────────────────────────────────────────────────
        * Context rather than work, so it comes last and stays one card high. */}
      <Block
        title="Trade, last 14 days"
        meta={
          shown
            ? `${dayOf(shown.date)} · ${num(shown.orders)} orders · ${uzs(shown.gmvMinor)}`
            : "Hover a day for its figures"
        }
        action={
          <Button variant="ghost" size="sm" onClick={() => onNavigate("statistics")}>
            Open statistics
          </Button>
        }
        note="Bars are order counts. A pale bar is a day when one live tenant was not trading — the two at the right are the days after Shirinliklar was suspended, which is why the platform total fell without any customer losing a single order."
      >
        <Card>
          <div style={{ display: "flex", gap: 24, alignItems: "stretch" }}>
            <div style={{ flex: 1, minWidth: 0 }}>
              <TradeStrip rows={DAILY} hovered={hoverDay} onHover={setHoverDay} />
            </div>
            <div
              style={{
                width: 220, flexShrink: 0, paddingLeft: 24,
                borderLeft: `1px solid ${hairline}`,
                display: "grid", gridTemplateColumns: "1fr", gap: 16, alignContent: "start",
              }}
            >
              <Field label="Orders, 14 days" value={num(orders14)} />
              <Field label="Sales, 14 days" value={uzs(gmv14)} />
              <Field
                label="Best day"
                value={(() => {
                  const best = DAILY.reduce((m, d) => (d.orders > m.orders ? d : m), DAILY[0]);
                  return `${dayOf(best.date)} · ${num(best.orders)}`;
                })()}
              />
              <Field
                label="Tenants trading"
                value={`${DAILY[DAILY.length - 1].activeTenants} now · ${DAILY[0].activeTenants} a fortnight ago`}
              />
            </div>
          </div>
        </Card>
      </Block>

      <div
        className="q-caption"
        style={{ color: inkSubtle, borderTop: `1px solid ${hairline}`, paddingTop: 12 }}
      >
        Prototype board. Every figure comes from fixtures, not from a live platform.
        Timestamps are Asia/Tashkent; the board reads {dayOf(TODAY)} as today.
      </div>
    </div>
  );
}

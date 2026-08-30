/* Subscriptions — what each customer is on, and what it earns.
 *
 * Two tables and two epilogues. The plan table is the price list as the business
 * actually sells it; the subscription table is who sits on which line of it. The
 * two are wired together — clicking a plan filters the book of business below —
 * because the question "who is on Network" is asked far more often than it is
 * worth a separate screen.
 *
 * The arithmetic is the point. Non uyi pays 14 250 000 so'm against a 9 000 000
 * so'm plan, and a table that prints both numbers and leaves the operator to
 * work out why is a table that gets mistrusted. Every row that bills above its
 * plan price shows the sum that produced it: base, plus extra locations at the
 * plan's per-location rate, and how many locations the tenant actually has.
 *
 * PAST_DUE and CANCELLED are not two shades of the same thing. One is a
 * conversation someone has to have this week — it keeps a person's name, a phone
 * number, an age in days, and actions. The other is over: it leaves the live
 * table entirely and settles into a closed ledger below, muted, with no actions
 * on it, because there is nothing left to do to a customer who has gone.
 */

import { useState } from "react";
import {
  SectionHeader, Card, KpiTile, DataTable, StatusPill, Button,
  FilterBar, Select, SearchInput, EmptyState,
  uzs, day,
  ink, inkMuted, inkSubtle, hairline, surface1,
} from "./components";
import { PLANS, SUBSCRIPTIONS, INVOICES, TENANTS, KPIS } from "./data";

/* The console header states 21.08.2026; ages and countdowns are measured from
 * the same instant so nothing on screen disagrees with the top bar. */
const TODAY = new Date("2026-08-21T00:00:00");

const at = (d) => new Date(`${d}T00:00:00`);
const daysFrom = (d) => Math.round((at(d) - TODAY) / 86_400_000);
const monthsBetween = (a, b) => {
  const [x, y] = [at(a), at(b)];
  let m = (y.getFullYear() - x.getFullYear()) * 12 + (y.getMonth() - x.getMonth());
  if (y.getDate() < x.getDate()) m -= 1;
  return m;
};

const STATUS_TONE = { ACTIVE: "active", TRIAL: "info", PAST_DUE: "failed", CANCELLED: "neutral" };
const STATUS_LABEL = { ACTIVE: "Active", TRIAL: "Trial", PAST_DUE: "Past due", CANCELLED: "Cancelled" };

const planOf = (name) => PLANS.find((p) => p.name === name);
const tenantOf = (id) => TENANTS.find((t) => t.id === id);

/* ── local components ──────────────────────────────────────────────────────
 * Four arrangements this screen needs that the shared set does not carry. They
 * stay here: a two-line table cell and a share bar are not yet a contract.
 */

/** A titled band. Heading, a line of context, and one way through. */
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

/** Value over a quieter second line. The second line is where the maths goes. */
function Cell({ main, sub, tone, align = "left", mono }) {
  return (
    <div style={{ textAlign: align, minWidth: 0 }}>
      <div className={mono ? "q-body-sm q-tnum" : "q-body-sm"} style={{ color: ink }}>{main}</div>
      {sub ? (
        <div
          className="q-caption q-tnum"
          style={{ color: tone === "error" ? "var(--q-error-text)" : inkSubtle, marginTop: 2 }}
        >
          {sub}
        </div>
      ) : null}
    </div>
  );
}

/** Square meter, ink on surface-2. A fill is not a primary action, so not blue. */
function ShareMeter({ ratio }) {
  const pct = Math.round(ratio * 100);
  return (
    <span style={{ display: "inline-flex", alignItems: "center", gap: 8, justifyContent: "flex-end" }}>
      <span className="q-tnum" style={{ color: inkMuted, minWidth: 32, textAlign: "right" }}>{pct}%</span>
      <span style={{ width: 64, height: 6, background: "var(--q-surface-2)", flexShrink: 0 }}>
        <span style={{ display: "block", width: `${pct}%`, height: "100%", background: ink }} />
      </span>
    </span>
  );
}

/** A row of the book that needs a phone call. Stripe, name, money, actions. */
function PastDueCase({ sub }) {
  const t = tenantOf(sub.tenantId);
  const open = INVOICES.filter((i) => i.tenantId === sub.tenantId && i.status === "OVERDUE");
  const owed = open.reduce((n, i) => n + i.amountMinor, 0);
  const worst = Math.max(...open.map((i) => i.daysOverdue ?? 0));
  const lapsed = -daysFrom(sub.renewsAt);

  return (
    <Card style={{ borderLeft: "3px solid var(--q-error)", padding: 16 }}>
      <div style={{ display: "flex", gap: 24, alignItems: "flex-start", flexWrap: "wrap" }}>
        <div style={{ minWidth: 200, flex: "1 1 220px" }}>
          <div style={{ display: "flex", alignItems: "center", gap: 8, flexWrap: "wrap" }}>
            <span className="q-emphasis" style={{ color: ink }}>{sub.tenant}</span>
            <StatusPill tone="failed">Past due</StatusPill>
            {t?.status === "SUSPENDED" ? <StatusPill tone="suspended">Suspended</StatusPill> : null}
          </div>
          <div className="q-caption" style={{ color: inkMuted, marginTop: 6 }}>
            {t?.owner} · {t?.ownerPhone}
          </div>
          <div className="q-caption" style={{ color: inkMuted, marginTop: 2 }}>
            {sub.plan} · {t?.city} · {t?.locations} locations
          </div>
        </div>

        <div style={{ display: "flex", gap: 32, flexWrap: "wrap", flex: "2 1 380px" }}>
          <div>
            <div className="q-caption" style={{ color: inkSubtle }}>Owed</div>
            <div className="q-body-sm q-tnum" style={{ color: ink, marginTop: 4 }}>{uzs(owed)}</div>
            <div className="q-caption q-tnum" style={{ color: inkSubtle, marginTop: 2 }}>
              {open.length} invoices, oldest {worst} days
            </div>
          </div>
          <div>
            <div className="q-caption" style={{ color: inkSubtle }}>Renewal lapsed</div>
            <div className="q-body-sm q-tnum" style={{ color: ink, marginTop: 4 }}>{lapsed} days ago</div>
            <div className="q-caption q-tnum" style={{ color: inkSubtle, marginTop: 2 }}>
              Due {day(`${sub.renewsAt}T00:00:00`)}
            </div>
          </div>
          <div style={{ minWidth: 180, flex: "1 1 180px" }}>
            <div className="q-caption" style={{ color: inkSubtle }}>Why it stopped</div>
            <div className="q-body-sm" style={{ color: ink, marginTop: 4 }}>
              {t?.suspendedReason ?? "No reason recorded"}
            </div>
          </div>
        </div>
      </div>

      <div style={{ display: "flex", gap: 8, marginTop: 16, flexWrap: "wrap" }}>
        <Button size="sm" variant="primary">Record a payment</Button>
        <Button size="sm" variant="tertiary">Log a call</Button>
        <Button size="sm" variant="ghost">Open the invoices</Button>
      </div>
    </Card>
  );
}

/* ── screen ────────────────────────────────────────────────────────────────*/

export default function Subscriptions() {
  const [planFilter, setPlanFilter] = useState(null);   // plan id, set by clicking a plan row
  const [status, setStatus] = useState("all");
  const [search, setSearch] = useState("");

  const live = SUBSCRIPTIONS.filter((s) => s.status !== "CANCELLED");
  const cancelled = SUBSCRIPTIONS.filter((s) => s.status === "CANCELLED");
  const pastDue = live.filter((s) => s.status === "PAST_DUE");

  const contracted = live.reduce((n, s) => n + s.billedMinor, 0);
  const activeBilled = live
    .filter((s) => s.status === "ACTIVE")
    .reduce((n, s) => n + s.billedMinor, 0);
  const mrrGap = KPIS.mrrMinor - activeBilled;
  const trialCount = live.filter((s) => s.status === "TRIAL").length;
  const trial = live.find((s) => s.status === "TRIAL");

  /* Plans, with the book of business folded back onto the price list. */
  const planRows = PLANS.map((p) => {
    const on = live.filter((s) => s.plan === p.name);
    const revenue = on.reduce((n, s) => n + s.billedMinor, 0);
    const extras = on.reduce((n, s) => n + s.extraLocations, 0);
    return {
      ...p,
      subscriptions: on.length,
      billing: on.filter((s) => s.billedMinor > 0).length,
      extras,
      revenue,
      share: contracted ? revenue / contracted : 0,
    };
  });

  /* Live subscriptions, filtered. Cancelled never enters here — it has its own
   * ledger below, and mixing the two is how a churned account gets counted. */
  const rows = live.filter((s) => {
    if (planFilter && planOf(s.plan)?.id !== planFilter) return false;
    if (status !== "all" && s.status !== status) return false;
    if (search && !s.tenant.toLowerCase().includes(search.toLowerCase())) return false;
    return true;
  });

  const selectedPlan = planFilter ? PLANS.find((p) => p.id === planFilter) : null;

  /* ── plan columns ────────────────────────────────────────────────────────*/
  const planColumns = [
    {
      key: "name", label: "Plan",
      render: (v, r) => (
        <Cell
          main={v}
          sub={r.commissionBps === 0 ? "No commission on GMV" : `${r.commissionBps / 100}% of GMV`}
        />
      ),
    },
    { key: "monthlyMinor", label: "Monthly price", align: "right", render: (v) => uzs(v) },
    { key: "locationsIncluded", label: "Locations included", align: "right" },
    { key: "extraLocationMinor", label: "Each extra location", align: "right", render: (v) => uzs(v) },
    {
      key: "tenants", label: "Tenants", align: "right",
      render: (v, r) => (
        <Cell
          align="right" mono main={v}
          sub={r.billing === r.subscriptions ? "all billing" : `${r.billing} billing`}
        />
      ),
    },
    {
      key: "extras", label: "Extra locations sold", align: "right",
      render: (v, r) => (v ? <Cell align="right" mono main={v} sub={uzs(v * r.extraLocationMinor)} /> : <span style={{ color: inkSubtle }}>—</span>),
    },
    { key: "revenue", label: "Contracted monthly", align: "right", render: (v) => uzs(v) },
    { key: "share", label: "Share", align: "right", render: (v) => <ShareMeter ratio={v} /> },
  ];

  /* ── subscription columns ────────────────────────────────────────────────*/
  const subColumns = [
    {
      key: "tenant", label: "Tenant",
      render: (v, r) => {
        const t = tenantOf(r.tenantId);
        return <Cell main={v} sub={t ? `${t.city} · ${t.locations} locations` : null} />;
      },
    },
    { key: "plan", label: "Plan" },
    {
      key: "status", label: "Status",
      render: (v) => <StatusPill tone={STATUS_TONE[v]}>{STATUS_LABEL[v]}</StatusPill>,
    },
    {
      key: "startedAt", label: "Started",
      render: (v) => (
        <Cell main={day(`${v}T00:00:00`)} sub={`${monthsBetween(v, "2026-08-21")} months`} mono />
      ),
    },
    {
      key: "renewsAt", label: "Renews", align: "right",
      render: (v, r) => {
        if (!v) return <span style={{ color: inkSubtle }}>—</span>;
        const d = daysFrom(v);
        return (
          <Cell
            align="right" mono
            main={day(`${v}T00:00:00`)}
            sub={d >= 0 ? `in ${d} days` : `lapsed ${-d} days ago`}
            tone={d < 0 ? "error" : undefined}
          />
        );
      },
    },
    {
      key: "monthlyMinor", label: "Plan price", align: "right",
      render: (v, r) =>
        r.status === "TRIAL"
          ? <Cell align="right" main="Free" sub={`then ${uzs(planOf(r.plan)?.monthlyMinor ?? 0)}`} />
          : uzs(v),
    },
    {
      key: "extraLocations", label: "Extra locations", align: "right",
      render: (v, r) => {
        const p = planOf(r.plan);
        const t = tenantOf(r.tenantId);
        if (!v) {
          return (
            <Cell
              align="right" mono main="—"
              sub={t ? `${t.locations} of ${p.locationsIncluded} included` : null}
            />
          );
        }
        return (
          <Cell
            align="right" mono
            main={`${v} × ${uzs(p.extraLocationMinor)}`}
            sub={t ? `${t.locations} locations, ${p.locationsIncluded} included` : null}
          />
        );
      },
    },
    {
      key: "billedMinor", label: "Billed monthly", align: "right",
      render: (v, r) => {
        const p = planOf(r.plan);
        if (r.status === "TRIAL") return <Cell align="right" main={uzs(0)} sub="trial, not invoiced" />;
        if (!r.extraLocations) return <span className="q-emphasis q-tnum">{uzs(v)}</span>;
        return (
          <div style={{ textAlign: "right" }}>
            <div className="q-emphasis q-tnum" style={{ color: ink }}>{uzs(v)}</div>
            <div className="q-caption q-tnum" style={{ color: inkSubtle, marginTop: 2 }}>
              {uzs(r.monthlyMinor)} + {uzs(r.extraLocations * p.extraLocationMinor)}
            </div>
            <div className="q-caption q-tnum" style={{ color: inkSubtle }}>
              {(v / r.monthlyMinor).toFixed(1)}× the plan price
            </div>
          </div>
        );
      },
    },
  ];

  /* ── the closed ledger ───────────────────────────────────────────────────*/
  const cancelledColumns = [
    {
      key: "tenant", label: "Tenant",
      render: (v, r) => {
        const t = tenantOf(r.tenantId);
        return (
          <div>
            <div className="q-body-sm" style={{ color: inkMuted }}>{v}</div>
            <div className="q-caption" style={{ color: inkSubtle, marginTop: 2 }}>{t?.closedReason ?? "—"}</div>
          </div>
        );
      },
    },
    { key: "plan", label: "Plan", render: (v) => <span style={{ color: inkMuted }}>{v}</span> },
    {
      key: "startedAt", label: "Started", align: "right",
      render: (v) => <span className="q-tnum" style={{ color: inkMuted }}>{day(`${v}T00:00:00`)}</span>,
    },
    {
      key: "closed", label: "Closed", align: "right",
      render: (v, r) => {
        const t = tenantOf(r.tenantId);
        return (
          <span className="q-tnum" style={{ color: inkMuted }}>{t?.closedAt ? day(t.closedAt) : "—"}</span>
        );
      },
    },
    {
      key: "lifetime", label: "Lifetime", align: "right",
      render: (v, r) => {
        const t = tenantOf(r.tenantId);
        if (!t?.closedAt) return <span style={{ color: inkSubtle }}>—</span>;
        return (
          <span className="q-tnum" style={{ color: inkMuted }}>
            {monthsBetween(r.startedAt, t.closedAt.slice(0, 10))} months
          </span>
        );
      },
    },
    {
      key: "monthlyMinor", label: "Last plan price", align: "right",
      render: (v) => <span className="q-tnum" style={{ color: inkMuted }}>{uzs(v)}</span>,
    },
    {
      key: "billedMinor", label: "Billing now", align: "right",
      render: () => <span className="q-tnum" style={{ color: inkSubtle }}>{uzs(0)}</span>,
    },
    {
      key: "mrr", label: "In MRR", align: "right",
      render: () => <span className="q-caption" style={{ color: inkSubtle }}>Excluded</span>,
    },
  ];

  return (
    <div>
      <SectionHeader
        title="Subscriptions"
        description="What every customer is on and what it earns. Prices are per month, before VAT, and exclude anything metered — SMS and delivery are billed on usage and appear on the invoice, not here."
        right={<Button variant="tertiary" size="sm">Export the book</Button>}
      />

      {/* ── the money ─────────────────────────────────────────────────────── */}
      <div
        style={{
          display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(200px, 1fr))",
          gap: 12, marginBottom: 8,
        }}
      >
        <KpiTile
          label="Monthly recurring revenue"
          value={uzs(KPIS.mrrMinor)}
          meta="As the platform reports it"
        />
        <KpiTile
          label="Contracted this month"
          value={uzs(contracted)}
          meta={`${live.length} live subscriptions, ${pastDue.length} unpaid`}
        />
        <KpiTile
          label="In arrears"
          value={uzs(KPIS.arrearsMinor)}
          meta={`${pastDue.map((s) => s.tenant).join(", ") || "Nobody"} · oldest 93 days`}
        />
        <KpiTile
          label="On trial"
          value={trialCount}
          meta={trial ? `${trial.tenant} converts ${day(`${trial.renewsAt}T00:00:00`)}` : "None"}
        />
        <KpiTile
          label="Churned in 90 days"
          value={KPIS.churnedLast90}
          meta={cancelled.map((s) => s.tenant).join(", ") || "None"}
        />
      </div>
      <div className="q-caption" style={{ color: inkSubtle, marginBottom: 32, maxWidth: 760 }}>
        Active subscriptions below sum to {uzs(activeBilled)}. The reported MRR is{" "}
        {uzs(mrrGap)} higher, and nothing in this data explains the difference — shown
        rather than quietly reconciled, because a figure that only agrees with itself
        is the one that gets believed wrongly.
      </div>

      {/* ── the price list ────────────────────────────────────────────────── */}
      <Block
        title="Plans"
        meta="Click a plan to filter the book below"
        action={
          selectedPlan ? (
            <Button variant="ghost" size="sm" onClick={() => setPlanFilter(null)}>
              Clear the {selectedPlan.name} filter
            </Button>
          ) : null
        }
        note="Extra locations are charged per location per month beyond what the plan includes, at the plan's own rate — the rate falls as the plan rises, which is why a 41-location tenant is on Network rather than paying Basic forty-one times over."
      >
        <DataTable
          columns={planColumns}
          rows={planRows}
          selectedId={planFilter}
          onRowClick={(r) => setPlanFilter(planFilter === r.id ? null : r.id)}
        />
      </Block>

      {/* ── the conversation to have ──────────────────────────────────────── */}
      {pastDue.length ? (
        <Block
          title="Past due"
          meta="A conversation someone has to have"
          note="A past-due subscription is still a customer. Service may be suspended, but the account, its menu and its history are intact and the row stays in the book above — this block exists so nobody has to notice a red pill to know a call is owed."
        >
          <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
            {pastDue.map((s) => <PastDueCase key={s.id} sub={s} />)}
          </div>
        </Block>
      ) : null}

      {/* ── the book of business ──────────────────────────────────────────── */}
      <Block
        title="The book"
        meta={`${rows.length} of ${live.length} live subscriptions`}
      >
        <FilterBar>
          <SearchInput value={search} onChange={setSearch} placeholder="Search a tenant" />
          <Select
            label="Status"
            value={status}
            onChange={setStatus}
            options={[
              { value: "all", label: "All live" },
              { value: "ACTIVE", label: "Active" },
              { value: "TRIAL", label: "Trial" },
              { value: "PAST_DUE", label: "Past due" },
            ]}
          />
          {selectedPlan ? (
            <span className="q-caption" style={{ color: inkMuted, display: "inline-flex", alignItems: "center", gap: 8 }}>
              Plan: {selectedPlan.name}
              <button
                type="button"
                onClick={() => setPlanFilter(null)}
                className="q-caption"
                aria-label="Clear the plan filter"
                style={{ background: surface1, border: `1px solid ${hairline}`, color: inkMuted, cursor: "pointer", padding: "2px 6px" }}
              >
                Clear
              </button>
            </span>
          ) : null}
          <span className="q-caption" style={{ marginLeft: "auto", color: inkSubtle }}>
            Cancelled subscriptions are not in this table
          </span>
        </FilterBar>
        <DataTable
          columns={subColumns}
          rows={rows}
          empty={
            <EmptyState
              title="No live subscription matches"
              description="Every cancelled account sits in the closed ledger below, and is never returned by this filter."
              action={
                <Button
                  variant="tertiary"
                  size="sm"
                  onClick={() => { setPlanFilter(null); setStatus("all"); setSearch(""); }}
                >
                  Clear the filters
                </Button>
              }
            />
          }
        />
      </Block>

      {/* ── over ──────────────────────────────────────────────────────────── */}
      <Block
        title="Closed"
        meta={`${cancelled.length} cancelled · nothing to do`}
        note="Kept for the record and for the churn figure above, not for work. There is no action on these rows because there is no action left: the contract ended, the final invoice settled, and the data sits under its retention clock until it is deleted."
      >
        <DataTable columns={cancelledColumns} rows={cancelled} />
      </Block>
    </div>
  );
}

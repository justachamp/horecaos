/* Onboarding — control-plane section 3.
 *
 * Bringing a restaurant on, and letting one go. Two tabs, one shape: a dense
 * table of every run, and below it the selected run in full.
 *
 * The owner column is the point of this screen. An onboarding does not fail
 * because a step is hard; it fails because a step is nobody's job, or because it
 * is the restaurant's job and nobody in Qoida is watching it. So every step
 * carries a named owner, the open steps are counted per owner, and the ones that
 * sit outside the account manager are called out rather than averaged away.
 *
 * Offboarding is the same table read backwards: the four things that must be
 * true before an account is closed — invoice settled, export delivered,
 * retention date set, account closed — with the at-risk tenant on the same
 * surface, because "they may leave" and "they are leaving" are one conversation.
 *
 * Only ONBOARDING_PIPELINE, ONBOARDING and OFFBOARDING exist as fixtures. The
 * pipeline holds three runs; the detailed run exists for one of them. Selecting
 * either of the other two says so rather than rendering a convincing lie.
 */

import { useState, useMemo } from "react";
import { ONBOARDING, ONBOARDING_PIPELINE, OFFBOARDING, TENANTS, INVOICES } from "./data";
import {
  ink, inkMuted, inkSubtle, hairline, surface1,
  uzs, dt, day,
  StatusPill, Card, SectionHeader, Button, DataTable, Tabs, FilterBar,
  Select, SearchInput, FieldGrid, KpiTile, EmptyState, Drawer, Timeline,
} from "./components";

/* ── clock ─────────────────────────────────────────────────────────────────
 * The shell states the console day; a screen that computed "days left" from the
 * wall clock would drift away from it by tomorrow. One constant, one truth. */
const TODAY = "2026-08-21";

const daysFrom = (isoDate) =>
  Math.round((Date.parse(`${isoDate.slice(0, 10)}T00:00:00Z`) - Date.parse(`${TODAY}T00:00:00Z`)) / 86400000);

const relDay = (isoDate) => {
  const n = daysFrom(isoDate);
  if (n === 0) return "today";
  if (n > 0) return `in ${n} ${n === 1 ? "day" : "days"}`;
  return `${-n} ${-n === 1 ? "day" : "days"} ago`;
};

/* ── vocabulary ────────────────────────────────────────────────────────────*/

const STEP_STATUS = {
  DONE:    { label: "Done",        tone: "active" },
  ACTIVE:  { label: "In progress", tone: "info" },
  WAITING: { label: "Waiting",     tone: "degraded" },
  PENDING: { label: "Not started", tone: "neutral" },
};

const EXIT_STAGE = {
  COMPLETE: { label: "Closed",  tone: "neutral" },
  AT_RISK:  { label: "At risk", tone: "failed" },
};

/* Who Qoida can chase directly, and who it can only ask. */
const INTERNAL_OWNERS = new Set(["Sales", "Account manager", "Content team", "Operations"]);

const STEP_FILL = {
  DONE: "var(--q-success)",
  ACTIVE: ink,
  WAITING: "var(--q-warning)",
  PENDING: "var(--q-surface-2)",
};

const Mono = ({ children }) => (
  <span style={{ fontFamily: "var(--q-font-mono)" }}>{children}</span>
);

/* ── local primitives ──────────────────────────────────────────────────────
 * Four shapes the shared set does not carry. Local on purpose: components.jsx
 * belongs to every section and a section may not widen it.
 */

/** A heading inside a tab. Not a Card — stacked tables need a light label. */
function Subsection({ title, meta, right, children }) {
  return (
    <div style={{ minWidth: 0 }}>
      <div style={{ display: "flex", alignItems: "baseline", gap: 8, marginBottom: 8 }}>
        <h2 className="q-emphasis" style={{ margin: 0, color: ink }}>{title}</h2>
        {meta ? <span className="q-caption" style={{ color: inkSubtle }}>{meta}</span> : null}
        {right ? <div style={{ marginLeft: "auto" }}>{right}</div> : null}
      </div>
      {children}
    </div>
  );
}

/** A hairline note. Carries a blocker, or an admission about the fixture. */
function Note({ tone = "neutral", pill, children }) {
  return (
    <div
      style={{
        display: "flex", alignItems: "flex-start", gap: 12,
        padding: 12, background: surface1, border: `1px solid ${hairline}`,
      }}
    >
      {pill ? <StatusPill tone={tone}>{pill}</StatusPill> : null}
      <div className="q-body-sm" style={{ color: inkMuted, minWidth: 0 }}>{children}</div>
    </div>
  );
}

/** The run as eleven segments — one per step, coloured by its status. A bar
 *  that is a percentage hides which step is stuck; this one cannot. */
function StepBar({ steps }) {
  return (
    <div style={{ display: "flex", gap: 2 }}>
      {steps.map((s) => (
        <span
          key={s.code}
          title={`${s.label} — ${STEP_STATUS[s.status].label} — ${s.owner}`}
          style={{ flex: 1, height: 6, background: STEP_FILL[s.status] }}
        />
      ))}
    </div>
  );
}

/** Open steps per owner. The screen's argument, in one strip. */
function OwnerLoad({ steps, onPick, picked }) {
  const owners = [];
  steps.forEach((s) => {
    let o = owners.find((x) => x.owner === s.owner);
    if (!o) { o = { owner: s.owner, steps: [] }; owners.push(o); }
    o.steps.push(s);
  });

  return (
    <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(168px, 1fr))", gap: 0 }}>
      {owners.map((o, i) => {
        const done = o.steps.filter((s) => s.status === "DONE").length;
        const blocked = o.steps.some((s) => s.status === "WAITING");
        const external = !INTERNAL_OWNERS.has(o.owner);
        const on = picked === o.owner;
        return (
          <button
            key={o.owner}
            type="button"
            onClick={() => onPick(on ? null : o.owner)}
            style={{
              textAlign: "left", cursor: "pointer", padding: 12,
              background: on ? "var(--q-info-tint)" : "var(--q-canvas)",
              border: `1px solid ${hairline}`,
              borderLeft: i === 0 ? `1px solid ${hairline}` : "none",
            }}
          >
            <div style={{ display: "flex", alignItems: "baseline", gap: 8 }}>
              <span className="q-emphasis" style={{ color: ink }}>{o.owner}</span>
              {external ? (
                <span className="q-caption" style={{ color: inkSubtle }}>outside Qoida</span>
              ) : null}
            </div>
            <div className="q-caption q-tnum" style={{ color: inkMuted, margin: "4px 0 8px" }}>
              {done} of {o.steps.length} done{blocked ? " · waiting" : ""}
            </div>
            <StepBar steps={o.steps} />
          </button>
        );
      })}
    </div>
  );
}

const Stack = ({ gap = 24, children }) => (
  <div style={{ display: "flex", flexDirection: "column", gap }}>{children}</div>
);

/* ── 3.1 pipeline ──────────────────────────────────────────────────────────*/

const pipelineColumns = [
  {
    key: "tenant", label: "Tenant",
    render: (_, r) => (
      <div
        style={{
          minWidth: 0, paddingLeft: 8,
          borderLeft: `2px solid ${r.stalled ? "var(--q-error)" : "transparent"}`,
        }}
      >
        <div className="q-emphasis" style={{ color: ink }}>{r.tenant}</div>
        <div className="q-caption" style={{ color: inkSubtle }}><Mono>{r.tenantId}</Mono></div>
      </div>
    ),
  },
  { key: "city", label: "City" },
  { key: "plan", label: "Plan" },
  {
    key: "stage", label: "Stage",
    render: (v) => <span className="q-emphasis" style={{ color: ink }}>{v}</span>,
  },
  {
    key: "daysOpen", label: "Days open", align: "right",
    render: (v) => <span style={{ color: v > 7 ? "var(--q-error-text)" : ink }}>{v}</span>,
  },
  {
    key: "target", label: "Target go-live",
    render: (v) => (
      <div>
        <div>{day(v)}</div>
        <div className="q-caption" style={{ color: daysFrom(v) < 5 ? "var(--q-error-text)" : inkSubtle }}>
          {relDay(v)}
        </div>
      </div>
    ),
  },
  { key: "manager", label: "Account manager" },
  {
    key: "stalled", label: "Status",
    render: (v, r) => (
      <div style={{ minWidth: 0 }}>
        <StatusPill tone={v ? "failed" : "active"}>{v ? "Stalled" : "On track"}</StatusPill>
        {v ? (
          <div className="q-caption" style={{ color: "var(--q-error-text)", marginTop: 4 }}>
            {r.stalledReason}
          </div>
        ) : null}
      </div>
    ),
  },
];

function Pipeline({ selected, setSelected }) {
  const [search, setSearch] = useState("");
  const [manager, setManager] = useState("all");
  const [only, setOnly] = useState("all");
  const [ownerFilter, setOwnerFilter] = useState(null);

  const managers = useMemo(
    () => ["all", ...Array.from(new Set(ONBOARDING_PIPELINE.map((r) => r.manager)))],
    [],
  );

  const rows = ONBOARDING_PIPELINE
    .filter((r) => (manager === "all" ? true : r.manager === manager))
    .filter((r) => (only === "stalled" ? r.stalled : true))
    .filter((r) => r.tenant.toLowerCase().includes(search.trim().toLowerCase()))
    .map((r) => ({ ...r, id: r.tenantId }));

  const stalled = ONBOARDING_PIPELINE.filter((r) => r.stalled);
  const next = [...ONBOARDING_PIPELINE].sort((a, b) => daysFrom(a.target) - daysFrom(b.target))[0];
  const longest = [...ONBOARDING_PIPELINE].sort((a, b) => b.daysOpen - a.daysOpen)[0];

  const entry = ONBOARDING_PIPELINE.find((r) => r.tenantId === selected);
  const run = ONBOARDING.tenantId === selected ? ONBOARDING : null;

  return (
    <Stack>
      <div style={{ display: "grid", gridTemplateColumns: "repeat(4, minmax(0, 1fr))", gap: 16 }}>
        <KpiTile label="In the pipeline" value={ONBOARDING_PIPELINE.length} meta="Contract signed, not yet live" />
        <KpiTile label="Stalled" value={stalled.length} meta={stalled.map((r) => r.tenant).join(", ") || "None"} />
        <KpiTile label="Next go-live" value={day(next.target)} meta={`${next.tenant} · ${relDay(next.target)}`} />
        <KpiTile label="Longest open" value={`${longest.daysOpen} days`} meta={`${longest.tenant} · ${longest.stage}`} />
      </div>

      <div>
        <FilterBar>
          <SearchInput value={search} onChange={setSearch} placeholder="Search a tenant" />
          <Select
            label="Manager" value={manager} onChange={setManager}
            options={managers.map((m) => ({ value: m, label: m === "all" ? "All managers" : m }))}
          />
          <Select
            label="Show" value={only} onChange={setOnly}
            options={[{ value: "all", label: "Everything" }, { value: "stalled", label: "Stalled only" }]}
          />
          <span className="q-caption" style={{ marginLeft: "auto", color: inkSubtle }}>
            {rows.length} of {ONBOARDING_PIPELINE.length} runs · select a row to open it
          </span>
        </FilterBar>
        <DataTable
          columns={pipelineColumns}
          rows={rows}
          selectedId={selected}
          onRowClick={(r) => setSelected(r.tenantId)}
          empty={<EmptyState title="No run matches that" description="Clear the filters to see the whole pipeline." />}
        />
      </div>

      {run ? (
        <RunDetail run={run} entry={entry} ownerFilter={ownerFilter} setOwnerFilter={setOwnerFilter} />
      ) : (
        <Subsection title={entry ? `${entry.tenant} — detailed run` : "Detailed run"}>
          <EmptyState
            title="This run has no step detail in the prototype"
            description={
              entry
                ? `${entry.tenant} is at the ${entry.stage.toLowerCase()} stage in the pipeline above. Only ${ONBOARDING.tenantName} carries a full step-by-step run in the fixtures, so nothing is shown here rather than something invented.`
                : "Select a run above."
            }
          />
        </Subsection>
      )}
    </Stack>
  );
}

/* ── 3.2 the run itself ────────────────────────────────────────────────────*/

const stepColumns = [
  {
    key: "n", label: "#", align: "right",
    render: (_, r) => <span style={{ color: inkSubtle }}>{r.n}</span>,
  },
  {
    key: "label", label: "Step",
    render: (v, r) => (
      <div style={{ minWidth: 0 }}>
        <div className="q-body-sm" style={{ color: ink }}>{v}</div>
        <div className="q-caption" style={{ color: inkSubtle }}><Mono>{r.code}</Mono></div>
      </div>
    ),
  },
  {
    key: "owner", label: "Owner",
    render: (v, r) => {
      const external = !INTERNAL_OWNERS.has(v);
      return (
        <div style={{ minWidth: 0 }}>
          <div className="q-emphasis" style={{ color: ink }}>{v}</div>
          <div className="q-caption" style={{ color: external && r.status !== "DONE" ? "var(--q-error-text)" : inkSubtle }}>
            {external ? "Outside Qoida — has to be asked, not assigned" : "Qoida"}
          </div>
        </div>
      );
    },
  },
  {
    key: "status", label: "Status",
    render: (v) => <StatusPill tone={STEP_STATUS[v].tone}>{STEP_STATUS[v].label}</StatusPill>,
  },
  {
    key: "waitingOn", label: "Waiting on / note",
    render: (v, r) =>
      v ? (
        <span className="q-body-sm" style={{ color: "var(--q-error-text)" }}>{v}</span>
      ) : r.note ? (
        <span className="q-body-sm" style={{ color: inkMuted }}>{r.note}</span>
      ) : (
        <span style={{ color: inkSubtle }}>—</span>
      ),
  },
  {
    key: "at", label: "Completed",
    render: (v) => (v ? dt(v) : <span style={{ color: inkSubtle }}>—</span>),
  },
];

function RunDetail({ run, entry, ownerFilter, setOwnerFilter }) {
  const steps = run.steps.map((s, i) => ({ ...s, id: s.code, n: i + 1 }));
  const done = steps.filter((s) => s.status === "DONE").length;
  const blocked = steps.filter((s) => s.status === "WAITING");
  const open = steps.filter((s) => s.status !== "DONE");
  const notManager = open.filter((s) => s.owner !== "Account manager");
  const outside = open.filter((s) => !INTERNAL_OWNERS.has(s.owner));
  const unowned = steps.filter((s) => !s.owner);
  const owners = (list) => Array.from(new Set(list.map((s) => s.owner.toLowerCase()))).join(", ");

  const shown = ownerFilter ? steps.filter((s) => s.owner === ownerFilter) : steps;

  return (
    <Stack gap={16}>
      <Subsection
        title={`${run.tenantName} — the run`}
        meta={`${done} of ${steps.length} steps done`}
        right={
          entry?.stalled ? <StatusPill tone="failed">Stalled</StatusPill> : <StatusPill tone="active">On track</StatusPill>
        }
      >
        <Card style={{ padding: 16 }}>
          <StepBar steps={steps} />
          <div style={{ marginTop: 16 }}>
            <FieldGrid
              columns={4}
              fields={[
                { label: "Account manager", value: run.accountManager },
                { label: "Started", value: dt(run.startedAt) },
                { label: "Target go-live", value: `${day(run.targetLiveDate)} · ${relDay(run.targetLiveDate)}` },
                { label: "Current stage", value: entry ? entry.stage : "—" },
              ]}
            />
          </div>
        </Card>
      </Subsection>

      {blocked.length ? (
        <Note tone="degraded" pill="Blocked">
          Step {steps.indexOf(blocked[0]) + 1}, <strong style={{ color: ink, fontWeight: 600 }}>{blocked[0].label}</strong>,
          belongs to {blocked[0].owner.toLowerCase() === "restaurant" ? "the restaurant" : blocked[0].owner.toLowerCase()} and
          is waiting — {blocked[0].waitingOn}. Nothing after it can be scheduled, and the target go-live is{" "}
          {relDay(run.targetLiveDate)}.
        </Note>
      ) : null}

      <Note tone={unowned.length ? "failed" : "active"} pill={unowned.length ? "Unowned" : "Owners"}>
        {unowned.length
          ? `${unowned.length} steps have no named owner. A step that is nobody's job does not get done.`
          : `Every step has a named owner. ${notManager.length} of the ${open.length} open steps are not the account manager's — ${owners(notManager)} — and ${outside.length} of those sit outside Qoida altogether, where the only lever is a phone call.`}
      </Note>

      <Subsection
        title="Open work by owner"
        meta={ownerFilter ? `filtered to ${ownerFilter.toLowerCase()}` : "select an owner to filter the steps"}
      >
        <OwnerLoad steps={steps} picked={ownerFilter} onPick={setOwnerFilter} />
      </Subsection>

      <Subsection title="Steps" meta={`${shown.length} shown`}>
        <DataTable columns={stepColumns} rows={shown} />
      </Subsection>
    </Stack>
  );
}

/* ── 3.3 offboarding ───────────────────────────────────────────────────────*/

const tenantOf = (id) => TENANTS.find((t) => t.id === id);
const invoicesOf = (id) => INVOICES.filter((i) => i.tenantId === id);

const exitColumns = [
  {
    key: "tenant", label: "Tenant",
    render: (v, r) => (
      <div
        style={{
          minWidth: 0, paddingLeft: 8,
          borderLeft: `2px solid ${r.stage === "AT_RISK" ? "var(--q-error)" : "transparent"}`,
        }}
      >
        <div className="q-emphasis" style={{ color: ink }}>{v}</div>
        <div className="q-caption" style={{ color: inkSubtle }}><Mono>{r.tenantId}</Mono></div>
      </div>
    ),
  },
  { key: "city", label: "City" },
  { key: "plan", label: "Plan" },
  {
    key: "stage", label: "Stage",
    render: (v) => <StatusPill tone={EXIT_STAGE[v].tone}>{EXIT_STAGE[v].label}</StatusPill>,
  },
  {
    key: "reason", label: "Reason",
    render: (v) => <span style={{ color: inkMuted }}>{v}</span>,
  },
  {
    key: "requestedAt", label: "Requested",
    render: (v) => (v ? day(v) : <span style={{ color: inkSubtle }}>Not requested</span>),
  },
  {
    key: "finalInvoiceMinor", label: "Final invoice", align: "right",
    render: (v, r) => (
      <div>
        <div style={{ color: r.finalInvoiceSettled ? ink : "var(--q-error-text)" }}>{uzs(v)}</div>
        <div className="q-caption" style={{ color: r.finalInvoiceSettled ? inkSubtle : "var(--q-error-text)" }}>
          {r.finalInvoiceSettled ? "Settled" : "Unsettled"}
        </div>
      </div>
    ),
  },
  {
    key: "dataRetentionUntil", label: "Data retained until",
    render: (v) =>
      v ? (
        <div>
          <div>{day(v)}</div>
          <div className="q-caption" style={{ color: inkSubtle }}>{relDay(v)}</div>
        </div>
      ) : (
        <span style={{ color: inkSubtle }}>Clock starts at closure</span>
      ),
  },
  {
    key: "exportDelivered", label: "Export",
    render: (v) => <StatusPill tone={v ? "active" : "degraded"}>{v ? "Delivered" : "Not delivered"}</StatusPill>,
  },
];

const closureColumns = [
  {
    key: "label", label: "Closure step",
    render: (v) => <span className="q-body-sm" style={{ color: ink }}>{v}</span>,
  },
  {
    key: "owner", label: "Owner",
    render: (v) => <span className="q-emphasis" style={{ color: ink }}>{v}</span>,
  },
  {
    key: "state", label: "Status",
    render: (v) => <StatusPill tone={STEP_STATUS[v].tone}>{STEP_STATUS[v].label}</StatusPill>,
  },
  {
    key: "detail", label: "Detail",
    render: (v, r) => (
      <span className="q-body-sm" style={{ color: r.state === "WAITING" ? "var(--q-error-text)" : inkMuted }}>{v}</span>
    ),
  },
];

/* The four fields the fixture carries, read as the sequence they actually are.
 * Closure is not one event; it is an invoice, an export, a retention clock and
 * a switch, and any of the four can be the one nobody did. */
const closureSteps = (r) => [
  {
    id: "requested", label: "Closure requested by the owner", owner: "Restaurant",
    state: r.requestedAt ? "DONE" : r.stage === "AT_RISK" ? "PENDING" : "WAITING",
    detail: r.requestedAt ? dt(r.requestedAt) : "Suspended for non-payment, no closure request — this is still a conversation, not a decision",
  },
  {
    id: "invoice", label: "Final invoice settled", owner: "Finance",
    state: r.finalInvoiceSettled ? "DONE" : "WAITING",
    detail: r.finalInvoiceSettled
      ? `${uzs(r.finalInvoiceMinor)} paid`
      : `${uzs(r.finalInvoiceMinor)} outstanding — an account cannot be closed with money owed on it`,
  },
  {
    id: "export", label: "Data export delivered to the owner", owner: "Support agent",
    state: r.exportDelivered ? "DONE" : r.stage === "AT_RISK" ? "PENDING" : "WAITING",
    detail: r.exportDelivered
      ? "Menu, orders and customers, delivered before the retention clock starts"
      : "Not prepared. It is owed at closure and the export cannot be produced after deletion",
  },
  {
    id: "retention", label: "Retention date set", owner: "Platform administrator",
    state: r.dataRetentionUntil ? "DONE" : "PENDING",
    detail: r.dataRetentionUntil
      ? `Deleted on ${day(r.dataRetentionUntil)} · ${relDay(r.dataRetentionUntil)}`
      : "Twelve months from closure. Nothing is set while the account is only suspended",
  },
  {
    id: "closed", label: "Account closed", owner: "Account manager",
    state: r.closedAt ? "DONE" : "PENDING",
    detail: r.closedAt ? dt(r.closedAt) : "Still suspended and reversible",
  },
];

function Offboarding({ selected, setSelected }) {
  const rows = [...OFFBOARDING]
    .sort((a, b) => (a.stage === "AT_RISK" ? -1 : 0) - (b.stage === "AT_RISK" ? -1 : 0))
    .map((r) => {
      const t = tenantOf(r.tenantId);
      return { ...r, id: r.tenantId, city: t?.city ?? "—", plan: t?.plan ?? "—" };
    });

  const record = rows.find((r) => r.id === selected) || rows[0];
  const tenant = tenantOf(record.tenantId);
  const steps = closureSteps(record);
  const outstanding = steps.filter((s) => s.state !== "DONE");
  const unpaid = invoicesOf(record.tenantId).filter((i) => i.status === "OVERDUE");
  const unpaidTotal = unpaid.reduce((a, i) => a + i.amountMinor, 0);

  const atRisk = OFFBOARDING.filter((r) => r.stage === "AT_RISK");
  const unsettled = OFFBOARDING.filter((r) => !r.finalInvoiceSettled);
  const unsettledTotal = unsettled.reduce((a, r) => a + r.finalInvoiceMinor, 0);
  const exportsOwed = OFFBOARDING.filter((r) => !r.exportDelivered).length;

  const timeline = [
    tenant?.suspendedAt
      ? { label: `Suspended — ${tenant.suspendedReason.toLowerCase()}`, at: dt(tenant.suspendedAt), actor: "Aziza Karimova", tone: "failed" }
      : null,
    record.requestedAt ? { label: "Closure requested", at: dt(record.requestedAt), actor: record.reason, tone: "pending" } : null,
    record.closedAt ? { label: "Account closed", at: dt(record.closedAt), actor: "Account manager", tone: "neutral" } : null,
    record.exportDelivered ? { label: "Data export delivered", at: day(record.closedAt || TODAY), actor: "Support", tone: "active" } : null,
    record.dataRetentionUntil
      ? { label: "Data deleted (scheduled)", at: `${day(record.dataRetentionUntil)} · ${relDay(record.dataRetentionUntil)}`, actor: "Scheduled", tone: "info" }
      : null,
  ].filter(Boolean);

  return (
    <Stack>
      <div style={{ display: "grid", gridTemplateColumns: "repeat(5, minmax(0, 1fr))", gap: 16 }}>
        <KpiTile label="Leaving or gone" value={OFFBOARDING.length} meta="Last 90 days" />
        <KpiTile label="At risk" value={atRisk.length} meta={atRisk.map((r) => r.tenant).join(", ") || "None"} />
        <div style={{ gridColumn: "span 2" }}>
          <KpiTile label="Final invoices unsettled" value={uzs(unsettledTotal)} meta={`${unsettled.length} account${unsettled.length === 1 ? "" : "s"}`} />
        </div>
        <KpiTile label="Exports owed" value={exportsOwed} meta="Owed before deletion" />
      </div>

      <Subsection title="Leaving and at risk" meta="Select a row to open the closure sequence">
        <DataTable
          columns={exitColumns}
          rows={rows}
          selectedId={record.id}
          onRowClick={(r) => setSelected(r.id)}
        />
      </Subsection>

      {record.stage === "AT_RISK" ? (
        <Note tone="failed" pill="Not yet a closure">
          {record.tenant} is suspended, not closed. {tenant?.suspendedReason ? `${tenant.suspendedReason}.` : ""}{" "}
          {unpaid.length} invoice{unpaid.length === 1 ? " is" : "s are"} overdue, {uzs(unpaidTotal)} in total, the oldest by{" "}
          {Math.max(...unpaid.map((i) => i.daysOverdue || 0))} days. Reversible until the owner asks to close or the account manager gives up on it.
        </Note>
      ) : null}

      <div style={{ display: "grid", gridTemplateColumns: "minmax(0, 2fr) minmax(240px, 1fr)", gap: 16, alignItems: "start" }}>
        <Subsection
          title={`${record.tenant} — closure sequence`}
          meta={outstanding.length ? `${outstanding.length} outstanding` : "complete"}
        >
          <DataTable columns={closureColumns} rows={steps} />
        </Subsection>

        <Subsection title="Account" meta={record.tenantId}>
          <Card style={{ padding: 16 }}>
            <FieldGrid
              columns={1}
              fields={[
                { label: "Owner", value: tenant?.owner },
                { label: "Contact", value: tenant?.ownerPhone, mono: true },
                { label: "Joined", value: tenant ? day(tenant.joinedAt) : "—" },
                { label: "Reason", value: record.reason },
              ]}
            />
            <div style={{ borderTop: `1px solid ${hairline}`, margin: "16px 0" }} />
            <div className="q-caption" style={{ color: inkSubtle, marginBottom: 12 }}>Account timeline</div>
            {timeline.length ? (
              <Timeline entries={timeline} />
            ) : (
              <div className="q-body-sm" style={{ color: inkMuted }}>Nothing has been recorded against this account yet.</div>
            )}
          </Card>
        </Subsection>
      </div>

      {unpaid.length ? (
        <Subsection title="Overdue invoices on this account" meta="From payments">
          <DataTable
            columns={[
              { key: "id", label: "Invoice", render: (v) => <Mono>{v}</Mono> },
              { key: "issuedAt", label: "Issued", render: (v) => day(v) },
              { key: "dueAt", label: "Due", render: (v) => day(v) },
              {
                key: "daysOverdue", label: "Overdue by", align: "right",
                render: (v) => <span style={{ color: "var(--q-error-text)" }}>{v} days</span>,
              },
              { key: "amountMinor", label: "Amount", align: "right", render: (v) => uzs(v) },
              { key: "method", label: "Method", render: (v) => v || <span style={{ color: inkSubtle }}>—</span> },
              { key: "status", label: "Status", render: () => <StatusPill tone="failed">Overdue</StatusPill> },
            ]}
            rows={unpaid}
          />
        </Subsection>
      ) : null}
    </Stack>
  );
}

/* ── section ───────────────────────────────────────────────────────────────*/

export default function Onboarding({ tab, setTab }) {
  const [selectedRun, setSelectedRun] = useState(ONBOARDING.tenantId);
  const [selectedExit, setSelectedExit] = useState("t-shirin");
  const [startOpen, setStartOpen] = useState(false);

  return (
    <div style={{ minWidth: 0 }}>
      <SectionHeader
        title="Onboarding"
        description="Every restaurant between a signed contract and a first order, and every one on its way out. A run is only as fast as the step nobody owns."
        right={<Button onClick={() => setStartOpen(true)}>Start an onboarding</Button>}
      />

      <Tabs
        active={tab}
        onChange={setTab}
        tabs={[
          { id: "pipeline", label: "Pipeline", count: ONBOARDING_PIPELINE.length },
          { id: "offboarding", label: "Offboarding", count: OFFBOARDING.length },
        ]}
      />

      {tab === "offboarding" ? (
        <Offboarding selected={selectedExit} setSelected={setSelectedExit} />
      ) : (
        <Pipeline selected={selectedRun} setSelected={setSelectedRun} />
      )}

      {startOpen ? (
        <Drawer title="Start an onboarding" onClose={() => setStartOpen(false)}>
          <Stack gap={16}>
            <Note tone="info" pill="Prototype">
              Creating a run is not wired up here. What it would collect, and the template it would create, are below —
              a blank form that pretended to work would be worse than none.
            </Note>
            <FieldGrid
              columns={2}
              fields={[
                { label: "Tenant", value: "New or existing, by legal name and INN" },
                { label: "Plan", value: "Basic, Growth or Network" },
                { label: "City", value: "From the platform's serviceable cities" },
                { label: "Account manager", value: "Named, and answerable for the run" },
                { label: "Target go-live", value: "A date, not a quarter" },
                { label: "Template", value: `${ONBOARDING.steps.length} steps, each with an owner` },
              ]}
            />
            <div>
              <div className="q-caption" style={{ color: inkSubtle, marginBottom: 8 }}>The standard run</div>
              <div style={{ border: `1px solid ${hairline}` }}>
                {ONBOARDING.steps.map((s, i) => (
                  <div
                    key={s.code}
                    style={{
                      display: "flex", gap: 12, alignItems: "baseline", padding: "8px 12px",
                      borderTop: i === 0 ? "none" : `1px solid ${hairline}`,
                    }}
                  >
                    <span className="q-caption q-tnum" style={{ color: inkSubtle, width: 16, textAlign: "right" }}>{i + 1}</span>
                    <span className="q-body-sm" style={{ color: ink, flex: 1, minWidth: 0 }}>{s.label}</span>
                    <span className="q-caption" style={{ color: INTERNAL_OWNERS.has(s.owner) ? inkMuted : "var(--q-error-text)" }}>
                      {s.owner}
                    </span>
                  </div>
                ))}
              </div>
            </div>
            <div style={{ display: "flex", gap: 8 }}>
              <Button disabled>Create the run</Button>
              <Button variant="ghost" onClick={() => setStartOpen(false)}>Cancel</Button>
            </div>
          </Stack>
        </Drawer>
      ) : null}
    </div>
  );
}

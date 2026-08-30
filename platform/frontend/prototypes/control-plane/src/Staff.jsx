/* Staff and access — HorecaOS's own people, and what they did.
 *
 * Two tables that are really one subject. The top table is who holds an account
 * and how far it reaches; the bottom one is what those accounts actually did to
 * customer data. Reading the second without the first is gossip; reading the
 * first without the second is an org chart.
 *
 * The screen's argument is the personal-data row. A support agent opened a
 * customer's contact details, and that is fine — you cannot answer "where is my
 * order" without a phone number. The control is not a prohibition, it is the
 * record: the action is named, attributed to a person, tied to a tenant, and
 * carries the ticket it was done for. So the ticket gets its own visual weight
 * in the note, sitting beside a "personal data — recorded" cell, rather than
 * being buried as free text or hidden behind an access-denied screen.
 *
 * A disabled account stays in the table. Deleting it would delete the only
 * explanation of who took the actions still listed underneath.
 */

import { useState } from "react";
import {
  DataTable, StatusPill, Button, KpiTile, FilterBar, Select, EmptyState,
  dt, day,
  ink, inkMuted, inkSubtle, hairline, surface1,
} from "./components";
import { STAFF, ACTIVITY } from "./data";

/* The console's pinned clock. The shell dates the session 21.08.2026; a
 * prototype that reads the wall clock ages its own fixtures out from under it. */
const NOW = "2026-08-21T14:10:00";

/* Mirrors the signed-in name in the rail. Marking your own row is worth the one
 * duplicated string: the first question anyone asks a staff list is "which is me". */
const SIGNED_IN = "Aziza Karimova";

/* An action counts as personal-data access when it touches a customer's own
 * details rather than the tenant's configuration. Derived from the action text
 * rather than a flag, because the fixture describes actions in words and the
 * classification should be visible in the words. */
const PERSONAL_DATA = /contact details|personal data|phone|address/i;
const isPersonalData = (a) => PERSONAL_DATA.test(a.action);

/* A reference an operator would paste into another system: a ticket number, an
 * invoice id. Matched to be emphasised in place — never stripped out of the note.
 * Deliberately not /g: split() finds every occurrence anyway, while a global
 * regex would carry lastIndex between the test() calls below and skip rows. */
const REF = /(#\d+|\b[A-Z]{2,4}-\d{4}-\d{4}\b)/;

const daysAgo = (iso) =>
  Math.round((new Date(NOW) - new Date(iso)) / 86_400_000);

/* ── local pieces ──────────────────────────────────────────────────────────
 * Three arrangements this screen needs that the shared set does not have. They
 * stay local: a band header is a screen-level arrangement, and the other two are
 * about this fixture's shape of string.
 */

/** A titled band — heading, a line of context, and an optional action. */
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

/** A note with its references set in mono, in place. "Support ticket #4821"
 *  keeps its sentence; the part you would act on stops being prose. */
function NoteText({ value, dim }) {
  if (!value) return <span style={{ color: inkSubtle }}>—</span>;
  const parts = value.split(REF);
  return (
    <span style={{ color: dim ? inkSubtle : inkMuted }}>
      {parts.map((p, i) =>
        REF.test(p) && i % 2 === 1 ? (
          <span
            key={i}
            className="q-emphasis"
            style={{ fontFamily: "var(--q-font-mono)", color: dim ? inkMuted : ink }}
          >
            {p}
          </span>
        ) : (
          <span key={i}>{p}</span>
        )
      )}
    </span>
  );
}

/** "All (read)" is two facts: how many tenants, and how deep. Split them so the
 *  reach column can be scanned down for "All" without reading every qualifier. */
function ScopeCell({ value, dim }) {
  const m = value.match(/^(.*?)\s*\((.*)\)$/);
  const scope = m ? m[1] : value;
  const qualifier = m ? m[2] : null;
  return (
    <span style={{ display: "inline-flex", alignItems: "baseline", gap: 6 }}>
      <span style={{ color: dim ? inkSubtle : ink }}>{scope}</span>
      {qualifier ? (
        <span className="q-caption" style={{ color: inkSubtle }}>{qualifier}</span>
      ) : null}
    </span>
  );
}

/* ── screen ────────────────────────────────────────────────────────────────*/

export default function Staff({}) {
  /* One filter, two ways in: clicking a person's row, or the select. Keeping it
   * in a single piece of state is what makes the two tables read as one screen —
   * the staff row you picked stays visibly selected while you read what they did. */
  const [actor, setActor] = useState("all");
  const [onlyPersonal, setOnlyPersonal] = useState(false);

  const staff = [...STAFF].sort((a, b) => b.lastActive.localeCompare(a.lastActive));
  const activity = [...ACTIVITY].sort((a, b) => b.at.localeCompare(a.at));

  const selected = STAFF.find((s) => s.name === actor) || null;
  const shown = activity.filter(
    (a) => (actor === "all" || a.actor === actor) && (!onlyPersonal || isPersonalData(a))
  );

  const activeCount = STAFF.filter((s) => s.status === "ACTIVE").length;
  const disabledCount = STAFF.length - activeCount;
  const reachAll = STAFF.filter((s) => s.status === "ACTIVE" && s.tenants.startsWith("All")).length;
  const personalCount = activity.filter(isPersonalData).length;
  const withTicket = activity.filter((a) => isPersonalData(a) && REF.test(a.note || "")).length;
  const oldest = activity[activity.length - 1];
  const newest = activity[0];

  /* Role by name, so the activity table can say what an actor was allowed to be
   * doing without the reader scrolling back up. */
  const roleOf = (name) => STAFF.find((s) => s.name === name)?.role ?? "—";
  const isDisabled = (name) => STAFF.find((s) => s.name === name)?.status === "DISABLED";

  const toggleActor = (row) => setActor((cur) => (cur === row.name ? "all" : row.name));

  return (
    <div>
      <div style={{ display: "flex", alignItems: "flex-start", gap: 16, marginBottom: 24 }}>
        <div style={{ minWidth: 0 }}>
          <h1 className="q-title" style={{ margin: 0, color: ink }}>Staff and access</h1>
          <p className="q-body-sm" style={{ margin: "4px 0 0", color: inkMuted, maxWidth: 720 }}>
            Everyone at HorecaOS who can open a customer's account, how far their access
            reaches, and what they have done with it. Customers' own staff are not
            here — they are managed inside their tenant.
          </p>
        </div>
        <div className="q-caption" style={{ marginLeft: "auto", flexShrink: 0, color: inkSubtle, textAlign: "right" }}>
          <div>Asia/Tashkent</div>
          <div style={{ marginTop: 2 }}>{day(NOW)}</div>
        </div>
      </div>

      {/* ── band ─────────────────────────────────────────────────────────── */}
      <div style={{ display: "grid", gridTemplateColumns: "repeat(4, minmax(0, 1fr))", gap: 16, marginBottom: 32 }}>
        <KpiTile
          label="Accounts"
          value={STAFF.length}
          meta={`${activeCount} active · ${disabledCount} disabled`}
        />
        <KpiTile
          label="Reach every tenant"
          value={reachAll}
          meta={`of ${activeCount} active accounts`}
        />
        <KpiTile
          label="Actions recorded"
          value={activity.length}
          meta={`${day(oldest.at)} – ${day(newest.at)}`}
        />
        <KpiTile
          label="Personal data opened"
          value={personalCount}
          meta={withTicket === personalCount ? "each against a ticket" : `${withTicket} against a ticket`}
        />
      </div>

      {/* ── staff ────────────────────────────────────────────────────────── */}
      <Block
        title="People"
        meta={`${STAFF.length} accounts · newest activity first`}
        action={
          selected ? (
            <Button variant="ghost" size="sm" onClick={() => setActor("all")}>
              Clear selection
            </Button>
          ) : null
        }
        note="Select a person to read only their actions below. A disabled account keeps its row: it is the only thing that explains the actions already recorded against it, and its access is settled by the status column, not by removal."
      >
        <DataTable
          columns={[
            {
              key: "name", label: "Name",
              render: (v, row) => {
                const off = row.status === "DISABLED";
                return (
                  <span style={{ display: "inline-flex", alignItems: "baseline", gap: 8 }}>
                    <span className={off ? "q-body-sm" : "q-emphasis"} style={{ color: off ? inkSubtle : ink }}>
                      {v}
                    </span>
                    {v === SIGNED_IN ? (
                      <span className="q-caption" style={{ color: inkSubtle }}>you</span>
                    ) : null}
                  </span>
                );
              },
            },
            {
              key: "email", label: "Email",
              render: (v, row) => (
                <span
                  style={{
                    fontFamily: "var(--q-font-mono)",
                    color: row.status === "DISABLED" ? inkSubtle : inkMuted,
                  }}
                >
                  {v}
                </span>
              ),
            },
            {
              key: "role", label: "Role",
              render: (v, row) => (
                <span style={{ color: row.status === "DISABLED" ? inkSubtle : ink }}>{v}</span>
              ),
            },
            {
              key: "tenants", label: "Tenants reachable",
              render: (v, row) => <ScopeCell value={v} dim={row.status === "DISABLED"} />,
            },
            {
              key: "acted", label: "Recorded actions", align: "right",
              render: (_v, row) => {
                const n = activity.filter((a) => a.actor === row.name).length;
                return n === 0 ? <span style={{ color: inkSubtle }}>0</span> : n;
              },
            },
            {
              key: "lastActive", label: "Last active", align: "right",
              render: (v, row) => {
                const d = daysAgo(v);
                const off = row.status === "DISABLED";
                return (
                  <span style={{ color: off ? inkSubtle : ink, whiteSpace: "nowrap" }}>
                    {dt(v)}
                    {d >= 7 ? (
                      <span className="q-caption" style={{ color: inkSubtle, marginLeft: 8 }}>
                        {d} days ago
                      </span>
                    ) : null}
                  </span>
                );
              },
            },
            {
              key: "status", label: "Status",
              render: (v) =>
                v === "ACTIVE" ? (
                  <StatusPill tone="active">active</StatusPill>
                ) : (
                  <StatusPill tone="neutral">disabled</StatusPill>
                ),
            },
          ]}
          rows={staff}
          selectedId={selected?.id}
          onRowClick={toggleActor}
        />
      </Block>

      {/* ── activity ─────────────────────────────────────────────────────── */}
      <Block
        title="Activity"
        meta={
          /* The count has to answer for the filter. A heading that keeps saying
           * "5 actions" over a one-row table teaches an operator to distrust it. */
          actor !== "all" || onlyPersonal
            ? [
                `${shown.length} of ${activity.length} actions`,
                selected?.name,
                onlyPersonal ? "personal data only" : null,
              ].filter(Boolean).join(" · ")
            : `${activity.length} actions · every tenant`
        }
      >
        <FilterBar>
          <Select
            label="Staff"
            value={actor}
            onChange={setActor}
            options={[
              { value: "all", label: "Everyone" },
              ...STAFF.map((s) => ({
                value: s.name,
                label: s.status === "DISABLED" ? `${s.name} — disabled` : s.name,
              })),
            ]}
          />
          <Button
            variant={onlyPersonal ? "secondary" : "tertiary"}
            size="sm"
            onClick={() => setOnlyPersonal((v) => !v)}
          >
            Personal data only
          </Button>
          <span className="q-caption" style={{ color: inkSubtle, marginLeft: "auto" }}>
            Written by the platform, not editable from this console
          </span>
        </FilterBar>

        <DataTable
          columns={[
            {
              key: "at", label: "Time",
              render: (v) => <span className="q-tnum" style={{ whiteSpace: "nowrap" }}>{dt(v)}</span>,
            },
            {
              key: "actor", label: "Staff",
              render: (v) => (
                <span style={{ color: isDisabled(v) ? inkMuted : ink, whiteSpace: "nowrap" }}>{v}</span>
              ),
            },
            {
              key: "role", label: "Role",
              render: (_v, row) => <span style={{ color: inkMuted }}>{roleOf(row.actor)}</span>,
            },
            { key: "action", label: "Action" },
            {
              key: "tenant", label: "Tenant",
              render: (v) => v || <span style={{ color: inkSubtle }}>Platform-wide</span>,
            },
            {
              key: "personal", label: "Personal data",
              render: (_v, row) =>
                !isPersonalData(row) ? (
                  <span style={{ color: inkSubtle }}>—</span>
                ) : REF.test(row.note || "") ? (
                  <StatusPill tone="info">recorded</StatusPill>
                ) : (
                  /* Never fires on the current fixture, and it is here so that the
                   * day it does, the gap is a status rather than a silence. */
                  <StatusPill tone="degraded">no reference</StatusPill>
                ),
            },
            {
              key: "note", label: "Note",
              render: (v, row) => <NoteText value={v} dim={isDisabled(row.actor)} />,
            },
          ]}
          rows={shown}
          empty={
            <EmptyState
              title={
                selected
                  ? `Nothing recorded for ${selected.name}`
                  : "Nothing recorded in this window"
              }
              description={
                selected
                  ? selected.status === "DISABLED"
                    ? `The account was disabled and last signed in on ${day(selected.lastActive)}. An account with no recorded actions is kept, not removed.`
                    : "This account holds access but has taken no recorded action in this window."
                  : "No action matches the current filter."
              }
              action={
                <Button variant="tertiary" size="sm" onClick={() => { setActor("all"); setOnlyPersonal(false); }}>
                  Show everything
                </Button>
              }
            />
          }
        />

        <div
          className="q-body-sm"
          style={{ color: inkMuted, marginTop: 12, background: surface1, padding: "12px 16px", maxWidth: 760 }}
        >
          Opening a customer's contact details is a normal part of support — nobody
          can answer "where is my order" without a phone number. So the platform does
          not forbid it and does not hide it. It names the person, the tenant, and the
          ticket the lookup was done for, and puts all three in one row that the
          customer's account manager can read.
        </div>
      </Block>

      <div className="q-caption" style={{ color: inkSubtle, borderTop: `1px solid ${hairline}`, paddingTop: 12 }}>
        Prototype screen. Accounts and activity come from fixtures. Retention, export
        of the record, and who outside HorecaOS may read it are not designed yet.
      </div>
    </div>
  );
}

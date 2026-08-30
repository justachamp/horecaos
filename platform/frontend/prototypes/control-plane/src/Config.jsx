/* Platform configuration — what an administrator sets once for everyone.
 *
 * Every value on this screen is inherited, not chosen: a tenant cannot pick its
 * own VAT rate, its own city list, or its own courier. That makes this the most
 * dangerous screen in the control plane and the one with the least on it, which
 * is exactly the combination that produces a careless change.
 *
 * So the screen is built around blast radius rather than around fields. Every
 * tab states who inherits it before it states what it is, every row that can be
 * changed carries the count it would move, and nothing mutates without a
 * confirmation that names the tenants, locations and cities involved. The
 * confirmation is computed from the same fixtures the tables read, so it cannot
 * drift from the truth the way a hand-written warning string does.
 *
 * The screen also refuses to present reference data as inert. Three rows here
 * are live problems dressed as settings: English is 74% translated and every
 * missing key falls through to O'zbekcha; Andijon is switched off while a
 * restaurant is being onboarded into it; and two active cities with paying
 * tenants have no courier partner at all. Each reads as work, not as a fact.
 */

import { useState } from "react";
import {
  Tabs, DataTable, StatusPill, Button, Card, FieldGrid,
  uzs, day,
  ink, inkMuted, inkSubtle, hairline, canvas, surface1,
} from "./components";
import { CONFIG, TENANTS, SUBSCRIPTIONS, ONBOARDING_PIPELINE } from "./data";

/* ── local pieces ──────────────────────────────────────────────────────────
 * Four things the shared set does not have. They stay local: a centred confirm
 * is only needed where a change is irreversible for everyone at once, and the
 * coverage matrix exists on this screen alone.
 */

/** Titled band inside a tab. Heading, one line of context, one way through. */
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

/** Square meter, ink on surface-2. A fill is not a primary action, so not blue. */
function Meter({ pct, width = 72 }) {
  return (
    <span style={{ display: "inline-flex", alignItems: "center", gap: 8 }}>
      <span style={{ width, height: 6, background: "var(--q-surface-2)", flexShrink: 0 }}>
        <span style={{ display: "block", width: `${pct}%`, height: "100%", background: ink }} />
      </span>
      <span className="q-tnum" style={{ color: inkMuted }}>{pct}%</span>
    </span>
  );
}

/** A standing line of work, not an alert. Hairline box, ink rule, one action. */
function ActionNote({ title, body, action }) {
  return (
    <div
      style={{
        display: "flex", alignItems: "flex-start", gap: 16, padding: 16,
        background: canvas, border: `1px solid ${hairline}`, borderLeft: `3px solid ${ink}`,
        marginBottom: 16,
      }}
    >
      <div style={{ minWidth: 0, flex: 1 }}>
        <div className="q-emphasis" style={{ color: ink }}>{title}</div>
        <div className="q-body-sm" style={{ color: inkMuted, marginTop: 4, maxWidth: 720 }}>{body}</div>
      </div>
      {action ? <div style={{ flexShrink: 0 }}>{action}</div> : null}
    </div>
  );
}

/** Who inherits the tab you are looking at. Stated before anything is set. */
function InheritedBy({ items, note }) {
  return (
    <div
      style={{
        display: "flex", alignItems: "center", gap: 24, flexWrap: "wrap",
        padding: "12px 16px", background: surface1, border: `1px solid ${hairline}`,
        marginBottom: 24,
      }}
    >
      <span className="q-caption" style={{ color: inkMuted, flexShrink: 0 }}>Inherited by</span>
      {items.map((i) => (
        <span key={i.label} style={{ display: "inline-flex", alignItems: "baseline", gap: 6 }}>
          <span className="q-emphasis q-tnum" style={{ color: ink }}>{i.value}</span>
          <span className="q-caption" style={{ color: inkMuted }}>{i.label}</span>
        </span>
      ))}
      {note ? (
        <span className="q-caption" style={{ color: inkSubtle, marginLeft: "auto", textAlign: "right" }}>
          {note}
        </span>
      ) : null}
    </div>
  );
}

/**
 * Centred confirm. Deliberately not the shared Drawer: a drawer is for reading a
 * record, and this is a stop. It never says "are you sure" — it lists what moves.
 */
function ConfirmDialog({ change, onCancel, onConfirm }) {
  if (!change) return null;
  return (
    <div
      onClick={onCancel}
      style={{
        position: "fixed", inset: 0, background: "rgba(22,22,22,0.5)",
        display: "flex", alignItems: "center", justifyContent: "center", padding: 24, zIndex: 60,
      }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        style={{
          width: 520, maxWidth: "100%", background: canvas,
          border: `1px solid ${hairline}`, display: "flex", flexDirection: "column",
        }}
      >
        <div style={{ padding: "16px 24px", borderBottom: `1px solid ${hairline}` }}>
          <div className="q-subhead" style={{ color: ink }}>{change.title}</div>
        </div>

        <div style={{ padding: 24 }}>
          <p className="q-body-sm" style={{ margin: 0, color: inkMuted }}>{change.summary}</p>

          <div className="q-caption" style={{ color: inkSubtle, margin: "20px 0 8px" }}>
            Blast radius
          </div>
          <div style={{ border: `1px solid ${hairline}` }}>
            {change.radius.map((r, i) => (
              <div
                key={r.label}
                style={{
                  display: "flex", alignItems: "baseline", gap: 16, padding: "8px 12px",
                  borderTop: i === 0 ? "none" : `1px solid ${hairline}`,
                }}
              >
                <span className="q-body-sm" style={{ color: ink, flex: 1, minWidth: 0 }}>{r.label}</span>
                <span className="q-emphasis q-tnum" style={{ color: ink, flexShrink: 0 }}>{r.value}</span>
              </div>
            ))}
          </div>

          {change.effect ? (
            <p className="q-body-sm" style={{ margin: "16px 0 0", color: ink }}>{change.effect}</p>
          ) : null}
          {change.reversible ? (
            <p className="q-caption" style={{ margin: "8px 0 0", color: inkSubtle }}>{change.reversible}</p>
          ) : null}
        </div>

        <div
          style={{
            display: "flex", gap: 8, justifyContent: "flex-end",
            padding: "12px 24px", borderTop: `1px solid ${hairline}`,
          }}
        >
          <Button variant="ghost" onClick={onCancel}>Cancel</Button>
          <Button variant={change.danger ? "danger" : "primary"} onClick={onConfirm}>
            {change.confirmLabel}
          </Button>
        </div>
      </div>
    </div>
  );
}

/** Covered / not covered mark for the city × courier matrix. Squares, never ticks. */
function Mark({ on }) {
  return on
    ? <span style={{ display: "inline-block", width: 8, height: 8, background: ink }} />
    : <span className="q-body-sm" style={{ color: inkSubtle }}>—</span>;
}

/* ── derivations ───────────────────────────────────────────────────────────
 * Every number the screen quotes is computed from the tenant fixtures rather
 * than typed, so a warning cannot claim a radius the tables contradict.
 */

const INHERITING = TENANTS.filter((t) => t.status !== "CLOSED");
const LOCATION_COUNT = INHERITING.reduce((n, t) => n + t.locations, 0);
const BILLABLE = SUBSCRIPTIONS.filter((s) => s.status !== "CANCELLED");

const tenantsInCity = (name) => INHERITING.filter((t) => t.city === name);
const locationsInCity = (name) => tenantsInCity(name).reduce((n, t) => n + t.locations, 0);
const onboardingInCity = (name) => (ONBOARDING_PIPELINE || []).filter((p) => p.city === name);

const nextRenewal = [...BILLABLE]
  .filter((s) => s.renewsAt)
  .sort((a, b) => a.renewsAt.localeCompare(b.renewsAt))[0];

const pct = (bps) => (bps / 100).toFixed(2).replace(/\.00$/, "");
const PROVIDER_TONE = { ACTIVE: "active", PLANNED: "neutral", DISABLED: "suspended" };

/* ── screen ────────────────────────────────────────────────────────────────*/

export default function Config({ tab, setTab }) {
  /* Local state, so a confirmed change visibly moves the derived warnings on the
   * screen instead of pretending. Nothing is written back to the fixtures. */
  const [defaultLocale, setDefaultLocale] = useState(
    CONFIG.locales.find((l) => l.isDefault)?.code ?? CONFIG.locales[0].code,
  );
  const [cityActive, setCityActive] = useState(
    () => Object.fromEntries(CONFIG.cities.map((c) => [c.code, c.active])),
  );
  const [providerStatus, setProviderStatus] = useState(
    () => Object.fromEntries(CONFIG.paymentProviders.map((p) => [p.code, p.status])),
  );
  const [change, setChange] = useState(null);

  const activeCities = CONFIG.cities.filter((c) => cityActive[c.code]);
  const coveredCities = new Set(CONFIG.deliveryPartners.flatMap((p) => p.cities));
  const uncovered = activeCities.filter(
    (c) => !coveredCities.has(c.name) && tenantsInCity(c.name).length > 0,
  );

  const apply = () => {
    if (change?.commit) change.commit();
    setChange(null);
  };

  const tabs = [
    { id: "general", label: "General" },
    { id: "cities", label: "Cities", count: CONFIG.cities.length },
    { id: "payments", label: "Payments", count: CONFIG.paymentProviders.length },
    { id: "delivery", label: "Delivery", count: CONFIG.deliveryPartners.length },
  ];

  /* ── general ─────────────────────────────────────────────────────────────*/

  const localeRows = CONFIG.locales.map((l) => ({ ...l, id: l.code }));
  const incomplete = CONFIG.locales.filter((l) => l.coverage < 100);
  const fallbackName = CONFIG.locales.find((l) => l.code === defaultLocale)?.label ?? "—";

  const localeColumns = [
    {
      key: "label", label: "Locale",
      render: (v, r) => (
        <span style={{ display: "inline-flex", alignItems: "center", gap: 8 }}>
          <span style={{ color: ink }}>{v}</span>
          {r.code === defaultLocale ? <StatusPill tone="info">Default</StatusPill> : null}
        </span>
      ),
    },
    {
      key: "code", label: "Code",
      render: (v) => <span style={{ fontFamily: "var(--q-font-mono)", color: inkMuted }}>{v}</span>,
    },
    { key: "coverage", label: "Translated", render: (v) => <Meter pct={v} /> },
    {
      key: "gap", label: "State",
      render: (_v, r) =>
        r.coverage === 100
          ? <StatusPill tone="healthy">Complete</StatusPill>
          : <StatusPill tone="pending">{100 - r.coverage}% missing</StatusPill>,
    },
    {
      key: "consequence", label: "What a customer sees",
      render: (_v, r) =>
        r.coverage === 100
          ? <span style={{ color: inkSubtle }}>Every string in {r.label}</span>
          : <span style={{ color: ink }}>Untranslated screens fall back to {fallbackName}</span>,
    },
    {
      key: "action", label: "", align: "right",
      render: (_v, r) => {
        if (r.coverage < 100) {
          return <Button size="sm" variant="tertiary" onClick={() => {}}>Open translation queue</Button>;
        }
        if (r.code === defaultLocale) return <span style={{ color: inkSubtle }}>—</span>;
        return (
          <Button
            size="sm"
            variant="ghost"
            onClick={() =>
              setChange({
                title: `Make ${r.label} the default locale`,
                summary:
                  `${r.label} becomes the language every console, receipt and customer notification ` +
                  `starts in, and the language every untranslated string falls back to.`,
                radius: [
                  { label: "Tenants switched", value: INHERITING.length },
                  { label: "Locations affected", value: LOCATION_COUNT },
                  { label: "Locales falling back to it", value: incomplete.length },
                ],
                effect:
                  "Staff who chose their own language keep it. Everyone who never chose one moves.",
                reversible: "Reversible from this screen at any time.",
                confirmLabel: `Set ${r.label} as default`,
                commit: () => setDefaultLocale(r.code),
              })
            }
          >
            Set as default
          </Button>
        );
      },
    },
  ];

  const general = (
    <div>
      <InheritedBy
        items={[
          { value: INHERITING.length, label: "tenants" },
          { value: LOCATION_COUNT, label: "locations" },
          { value: BILLABLE.length, label: "subscriptions" },
          { value: activeCities.length, label: "active cities" },
        ]}
        note="No tenant can override these."
      />

      <Block
        title="Languages"
        meta={`${CONFIG.locales.length} enabled · default ${fallbackName}`}
        note={
          "Coverage is measured against the O'zbekcha catalogue. A missing key is not blank — " +
          "it renders in the default locale, so an English-speaking manager reads a half-translated screen " +
          "rather than an obviously broken one, which is why this reads as work rather than as a number."
        }
      >
        {incomplete.length > 0 ? (
          <ActionNote
            title={
              `${incomplete.map((l) => l.label).join(", ")} ` +
              `${incomplete.length > 1 ? "are" : "is"} not finished`
            }
            body={
              incomplete
                .map(
                  (l) =>
                    `${l.label} is ${l.coverage}% translated, so ${100 - l.coverage}% of the console, ` +
                    `receipts and notifications drop back to ${fallbackName}.`,
                )
                .join(" ") +
              ` It is already offered in the language picker to all ${INHERITING.length} tenants, ` +
              `which is why this is work rather than a setting.`
            }
            action={<Button size="sm" variant="primary" onClick={() => {}}>Open translation queue</Button>}
          />
        ) : null}
        <DataTable columns={localeColumns} rows={localeRows} />
      </Block>

      <Block
        title="Currency and formatting"
        meta="One currency, platform-wide"
        note="HorecaOS is single-currency. A second currency is a data-model change, not a setting, so it is not offered here."
      >
        <Card>
          <FieldGrid
            columns={4}
            fields={[
              { label: "Currency", value: `${CONFIG.currency.code} · ${CONFIG.currency.symbol}` },
              { label: "Decimal places", value: String(CONFIG.currency.decimals) },
              { label: "Thousands grouping", value: CONFIG.currency.grouping === "space" ? "Space" : CONFIG.currency.grouping },
              { label: "Rendered as", value: uzs(1_884_900_000), mono: false },
            ]}
          />
        </Card>
      </Block>

      <Block
        title="Tax"
        meta={`VAT ${pct(CONFIG.vatRateBps)}% · ${CONFIG.vatRateBps} bps`}
        note={
          nextRenewal
            ? `A change applies to invoices issued from the moment it is saved. Invoices already issued are not reprinted. ` +
              `The next invoice affected is ${nextRenewal.tenant} on ${day(nextRenewal.renewsAt)}.`
            : "A change applies to invoices issued from the moment it is saved."
        }
        action={
          <Button
            size="sm"
            variant="tertiary"
            onClick={() =>
              setChange({
                title: "Change the platform VAT rate",
                summary:
                  `VAT is applied to every subscription invoice and every fiscal receipt on the platform. ` +
                  `It is a legal figure, not a commercial one.`,
                radius: [
                  { label: "Tenants re-rated", value: INHERITING.length },
                  { label: "Live subscriptions re-priced", value: BILLABLE.length },
                  { label: "Locations issuing receipts", value: LOCATION_COUNT },
                  { label: "Next invoice affected", value: nextRenewal ? day(nextRenewal.renewsAt) : "—" },
                ],
                effect: "Invoices already issued keep the rate they were issued under.",
                reversible: "Changing it back does not re-issue anything in between.",
                confirmLabel: "Continue",
              })
            }
          >
            Change rate
          </Button>
        }
      >
        <Card>
          <FieldGrid
            columns={4}
            fields={[
              { label: "VAT rate", value: `${pct(CONFIG.vatRateBps)}%` },
              { label: "Stored as", value: `${CONFIG.vatRateBps} basis points`, mono: true },
              { label: "On a 100 000 so'm order", value: uzs((100_000 * CONFIG.vatRateBps) / 10_000) },
              { label: "Applied to", value: "Subscription invoices and fiscal receipts" },
            ]}
          />
        </Card>
      </Block>

      <Block
        title="Support"
        meta="Shown to every tenant in the console footer and in customer notifications"
      >
        <Card>
          <FieldGrid
            columns={3}
            fields={[
              { label: "Support hours", value: CONFIG.supportHours },
              { label: "Time zone", value: "Asia/Tashkent (UTC+5)" },
              { label: "Shown to", value: `${INHERITING.length} tenants and their customers` },
            ]}
          />
        </Card>
      </Block>
    </div>
  );

  /* ── cities ──────────────────────────────────────────────────────────────*/

  const cityRows = CONFIG.cities.map((c) => {
    const live = tenantsInCity(c.name);
    const pipeline = onboardingInCity(c.name);
    return {
      ...c,
      id: c.code,
      on: cityActive[c.code],
      liveTenants: live.length,
      locations: locationsInCity(c.name),
      onboarding: pipeline.length,
      pipelineNames: pipeline.map((p) => p.tenant).join(", "),
      couriers: CONFIG.deliveryPartners.filter((p) => p.cities.includes(c.name)).length,
    };
  });

  const blockedOnboarding = cityRows.filter((c) => !c.on && c.onboarding > 0);

  const cityColumns = [
    {
      key: "code", label: "Code",
      render: (v) => <span style={{ fontFamily: "var(--q-font-mono)", color: inkMuted }}>{v}</span>,
    },
    { key: "name", label: "City" },
    {
      key: "on", label: "Serviceability",
      render: (v) => (v ? <StatusPill tone="active">Active</StatusPill> : <StatusPill tone="neutral">Inactive</StatusPill>),
    },
    { key: "tenants", label: "Tenants", align: "right" },
    { key: "liveTenants", label: "Live", align: "right" },
    { key: "locations", label: "Locations", align: "right" },
    {
      key: "onboarding", label: "Onboarding", align: "right",
      render: (v, r) =>
        v === 0
          ? <span style={{ color: inkSubtle }}>0</span>
          : <span style={{ color: r.on ? ink : "var(--q-error-text)" }}>{v}</span>,
    },
    {
      key: "couriers", label: "Couriers", align: "right",
      render: (v, r) =>
        v === 0 && r.on && r.liveTenants > 0
          ? <span style={{ color: "var(--q-warning-text)" }}>0</span>
          : v,
    },
    {
      key: "action", label: "", align: "right",
      render: (_v, r) => (
        <Button
          size="sm"
          variant={r.on ? "ghost" : "tertiary"}
          onClick={() =>
            setChange({
              danger: r.on,
              title: r.on ? `Stop serving ${r.name}` : `Start serving ${r.name}`,
              summary: r.on
                ? `${r.name} disappears from the city list everywhere. No tenant can be created there, no ` +
                  `location can open there, and delivery zones drawn inside it stop accepting orders.`
                : `${r.name} becomes selectable everywhere a city is chosen: new tenants, new locations, ` +
                  `delivery zones and courier coverage.`,
              radius: [
                { label: "Tenants in the city", value: r.tenants },
                { label: "Live tenants", value: r.liveTenants },
                { label: "Locations", value: r.locations },
                { label: "Onboarding in progress", value: r.onboarding },
                { label: "Courier partners covering it", value: r.couriers },
              ],
              effect: r.on
                ? r.liveTenants > 0
                  ? `${r.liveTenants} live tenant${r.liveTenants > 1 ? "s" : ""} stop taking orders immediately.`
                  : "No live tenant is trading there, so nothing stops taking orders today."
                : r.couriers === 0
                  ? "No courier partner covers this city yet, so tenants there must run their own couriers."
                  : "Existing tenants are unaffected; the city simply becomes selectable.",
              reversible: "Reversible from this screen. It does not delete anything.",
              confirmLabel: r.on ? `Deactivate ${r.name}` : `Activate ${r.name}`,
              commit: () => setCityActive((s) => ({ ...s, [r.code]: !r.on })),
            })
          }
        >
          {r.on ? "Deactivate" : "Activate"}
        </Button>
      ),
    },
  ];

  const cities = (
    <div>
      <InheritedBy
        items={[
          { value: activeCities.length, label: "active cities" },
          { value: CONFIG.cities.length - activeCities.length, label: "inactive" },
          { value: INHERITING.length, label: "tenants placed" },
          { value: LOCATION_COUNT, label: "locations" },
        ]}
        note="The city list is the only place a tenant address can come from."
      />

      {blockedOnboarding.map((c) => (
        <ActionNote
          key={c.code}
          title={`${c.name} is inactive, and ${c.pipelineNames} is being onboarded into it`}
          body={
            `An onboarding tenant in an inactive city cannot open a location, draw a delivery zone or go live. ` +
            `Either activate ${c.name} or move the account to a city HorecaOS serves.`
          }
          action={
            <Button
              size="sm"
              variant="primary"
              onClick={() =>
                setChange({
                  title: `Start serving ${c.name}`,
                  summary:
                    `${c.name} becomes selectable everywhere a city is chosen, which unblocks ${c.pipelineNames}.`,
                  radius: [
                    { label: "Onboarding unblocked", value: c.onboarding },
                    { label: "Tenants in the city", value: c.tenants },
                    { label: "Courier partners covering it", value: c.couriers },
                  ],
                  effect:
                    c.couriers === 0
                      ? "No courier partner covers it, so the tenant delivers with its own couriers until one does."
                      : "Courier coverage already exists.",
                  reversible: "Reversible from this screen.",
                  confirmLabel: `Activate ${c.name}`,
                  commit: () => setCityActive((s) => ({ ...s, [c.code]: true })),
                })
              }
            >
              Activate {c.name}
            </Button>
          }
        />
      ))}

      <Block
        title="Where HorecaOS operates"
        meta={`${activeCities.length} of ${CONFIG.cities.length} active`}
        note={
          "Tenants, locations, delivery zones and courier coverage all resolve against this list. " +
          "Deactivating a city does not delete the tenants inside it — it stops them trading, which is worse " +
          "and easier to do by accident."
        }
      >
        <DataTable columns={cityColumns} rows={cityRows} />
      </Block>
    </div>
  );

  /* ── payments ────────────────────────────────────────────────────────────*/

  const providerRows = CONFIG.paymentProviders.map((p) => ({
    ...p,
    id: p.code,
    status: providerStatus[p.code],
  }));

  const providerColumns = [
    { key: "name", label: "Provider" },
    {
      key: "code", label: "Code",
      render: (v) => <span style={{ fontFamily: "var(--q-font-mono)", color: inkMuted }}>{v}</span>,
    },
    {
      key: "status", label: "Status",
      render: (v) => (
        <StatusPill tone={PROVIDER_TONE[v] || "neutral"}>
          {v === "ACTIVE" ? "Available" : v === "PLANNED" ? "Planned" : "Withdrawn"}
        </StatusPill>
      ),
    },
    {
      key: "feeBps", label: "Fee", align: "right",
      render: (v) => (v === null || v === undefined ? <span style={{ color: inkSubtle }}>—</span> : `${v} bps`),
    },
    {
      key: "feePct", label: "Rate", align: "right",
      render: (_v, r) => (r.feeBps === null || r.feeBps === undefined ? <span style={{ color: inkSubtle }}>—</span> : `${pct(r.feeBps)}%`),
    },
    {
      key: "cost", label: "Cost per 1 000 000 so'm", align: "right",
      render: (_v, r) =>
        r.feeBps === null || r.feeBps === undefined
          ? <span style={{ color: inkSubtle }}>Not negotiated</span>
          : uzs((1_000_000 * r.feeBps) / 10_000),
    },
    { key: "tenants", label: "Tenants using", align: "right" },
    {
      key: "adoption", label: "Adoption", align: "right",
      render: (_v, r) => (
        <span style={{ display: "inline-flex", alignItems: "center", justifyContent: "flex-end", gap: 8 }}>
          <Meter pct={Math.round((r.tenants / INHERITING.length) * 100)} width={56} />
        </span>
      ),
    },
    {
      key: "action", label: "", align: "right",
      render: (_v, r) =>
        r.status === "PLANNED" ? (
          <Button size="sm" variant="ghost" disabled>Not connected</Button>
        ) : (
          <Button
            size="sm"
            variant="ghost"
            onClick={() =>
              setChange({
                danger: true,
                title: `Withdraw ${r.name} from every tenant`,
                summary:
                  `${r.name} disappears from checkout for every tenant that offers it. Customers mid-checkout ` +
                  `on ${r.name} lose the option at the moment it is saved.`,
                radius: [
                  { label: "Tenants offering it", value: r.tenants },
                  { label: "Share of tenants", value: `${Math.round((r.tenants / INHERITING.length) * 100)}%` },
                  { label: "Locations", value: LOCATION_COUNT },
                  { label: "Fee removed", value: r.feeBps ? `${r.feeBps} bps` : "—" },
                ],
                effect:
                  r.code === "CASH"
                    ? "Cash is the only method with no provider dependency. Withdrawing it leaves tenants with no offline fallback."
                    : `Tenants keep their settlement history; only new orders are affected.`,
                reversible: "Re-enabling it does not restore tenant-level configuration automatically.",
                confirmLabel: `Withdraw ${r.name}`,
                commit: () => setProviderStatus((s) => ({ ...s, [r.code]: "DISABLED" })),
              })
            }
          >
            Withdraw
          </Button>
        ),
    },
  ];

  const planned = providerRows.filter((p) => p.status === "PLANNED");

  const payments = (
    <div>
      <InheritedBy
        items={[
          { value: providerRows.filter((p) => p.status === "ACTIVE").length, label: "available" },
          { value: planned.length, label: "planned" },
          { value: INHERITING.length, label: "tenants" },
          { value: LOCATION_COUNT, label: "locations" },
        ]}
        note="A tenant may only offer what is available here."
      />

      {planned.length > 0 ? (
        <ActionNote
          title={`${planned.map((p) => p.name).join(", ")} is planned, not connected`}
          body={
            "A planned provider is visible to HorecaOS and invisible to tenants: no fee is negotiated, no contract is " +
            "signed, and no tenant can select it at checkout. It sits here so it is not promised twice."
          }
          action={<Button size="sm" variant="tertiary" onClick={() => {}}>Open the integration</Button>}
        />
      ) : null}

      <Block
        title="Payment providers"
        meta={`${providerRows.filter((p) => p.status === "ACTIVE").length} available to tenants`}
        note={
          "The fee is what HorecaOS is charged by the provider and what a tenant is quoted, in basis points, so a " +
          "170 bps difference reads as money rather than as a number. This list is what a tenant's customers pay " +
          "with — it is not how HorecaOS bills the tenant."
        }
      >
        <DataTable columns={providerColumns} rows={providerRows} />
      </Block>
    </div>
  );

  /* ── delivery ────────────────────────────────────────────────────────────*/

  const partnerRows = CONFIG.deliveryPartners.map((p) => ({
    ...p,
    id: p.code,
    cityCount: p.cities.length,
    tenantsReachable: p.cities.reduce((n, c) => n + tenantsInCity(c).length, 0),
    locationsReachable: p.cities.reduce((n, c) => n + locationsInCity(c), 0),
  }));

  const partnerColumns = [
    { key: "name", label: "Partner" },
    {
      key: "code", label: "Code",
      render: (v) => <span style={{ fontFamily: "var(--q-font-mono)", color: inkMuted }}>{v}</span>,
    },
    {
      key: "status", label: "Status",
      render: (v) => <StatusPill tone={v === "ACTIVE" ? "active" : "neutral"}>{v === "ACTIVE" ? "Active" : "Planned"}</StatusPill>,
    },
    { key: "tenants", label: "Tenants using", align: "right" },
    { key: "cityCount", label: "Cities", align: "right" },
    {
      key: "cities", label: "Covers",
      render: (v) => <span style={{ color: ink }}>{v.join(", ")}</span>,
    },
    { key: "locationsReachable", label: "Locations reachable", align: "right" },
    {
      key: "coverage", label: "Of active cities", align: "right",
      render: (_v, r) => <Meter pct={Math.round((r.cityCount / Math.max(activeCities.length, 1)) * 100)} width={56} />,
    },
  ];

  const matrixColumns = [
    { key: "name", label: "City" },
    {
      key: "state", label: "Serviceability",
      render: (_v, r) => (r.on ? <StatusPill tone="active">Active</StatusPill> : <StatusPill tone="neutral">Inactive</StatusPill>),
    },
    ...CONFIG.deliveryPartners.map((p) => ({
      key: p.code, label: p.name, align: "center",
      render: (v) => <Mark on={v} />,
    })),
    { key: "liveTenants", label: "Live tenants", align: "right" },
    { key: "locations", label: "Locations", align: "right" },
    {
      key: "gap", label: "Result",
      render: (_v, r) => {
        if (!r.on) return <span style={{ color: inkSubtle }}>Not served</span>;
        if (r.partners > 0) return <span style={{ color: inkMuted }}>{r.partners} partner{r.partners > 1 ? "s" : ""}</span>;
        if (r.liveTenants === 0) return <span style={{ color: inkSubtle }}>No tenants yet</span>;
        return <span style={{ color: "var(--q-warning-text)" }}>Tenants deliver with their own couriers</span>;
      },
    },
  ];

  const matrixRows = cityRows.map((c) => ({
    ...c,
    partners: CONFIG.deliveryPartners.filter((p) => p.cities.includes(c.name)).length,
    ...Object.fromEntries(
      CONFIG.deliveryPartners.map((p) => [p.code, p.cities.includes(c.name)]),
    ),
  }));

  const delivery = (
    <div>
      <InheritedBy
        items={[
          { value: CONFIG.deliveryPartners.length, label: "partners" },
          { value: coveredCities.size, label: "cities covered" },
          { value: activeCities.length, label: "active cities" },
          { value: uncovered.length, label: "cities with no partner" },
        ]}
        note="A tenant may only pick a courier that covers its city."
      />

      {uncovered.length > 0 ? (
        <ActionNote
          title={
            uncovered.length > 1
              ? `${uncovered.length} active cities with tenants in them have no courier partner`
              : `${uncovered[0].name} has tenants in it and no courier partner`
          }
          body={
            `${uncovered.map((c) => c.name).join(" and ")} carry ` +
            `${uncovered.reduce((n, c) => n + tenantsInCity(c.name).length, 0)} tenants and ` +
            `${uncovered.reduce((n, c) => n + locationsInCity(c.name), 0)} locations between them. ` +
            `Those tenants deliver with their own couriers, so HorecaOS has no delivery telemetry for them and ` +
            `cannot quote a delivery fee at checkout.`
          }
          action={<Button size="sm" variant="primary" onClick={() => {}}>Add a partner</Button>}
        />
      ) : null}

      <Block
        title="Courier partners"
        meta={`${partnerRows.filter((p) => p.status === "ACTIVE").length} active`}
        note="Reachable counts are derived from the cities each partner covers and the tenants placed in them."
      >
        <DataTable columns={partnerColumns} rows={partnerRows} />
      </Block>

      <Block
        title="Coverage by city"
        meta={`${CONFIG.deliveryPartners.length} partners × ${CONFIG.cities.length} cities`}
        note={
          "The matrix is the argument for the next partner: the empty rows with tenants in them are where HorecaOS " +
          "is selling a delivery platform that does not deliver."
        }
      >
        <DataTable columns={matrixColumns} rows={matrixRows} />
      </Block>
    </div>
  );

  /* ── shell ───────────────────────────────────────────────────────────────*/

  return (
    <div>
      <div style={{ display: "flex", alignItems: "flex-start", gap: 16, marginBottom: 24 }}>
        <div style={{ minWidth: 0 }}>
          <h1 className="q-title" style={{ margin: 0, color: ink }}>Platform configuration</h1>
          <p className="q-body-sm" style={{ margin: "4px 0 0", color: inkMuted, maxWidth: 760 }}>
            Reference data every tenant inherits and none can override. There is no draft and no staging:
            a change saved here is live for {INHERITING.length} tenants and {LOCATION_COUNT} locations at once,
            so every change on this screen states what it moves before it moves it.
          </p>
        </div>
        <div style={{ marginLeft: "auto", flexShrink: 0, textAlign: "right" }}>
          <div className="q-caption" style={{ color: inkSubtle }}>Scope</div>
          <div className="q-body-sm" style={{ color: ink }}>All tenants</div>
        </div>
      </div>

      <Tabs tabs={tabs} active={tab} onChange={setTab} />

      {tab === "general" ? general : null}
      {tab === "cities" ? cities : null}
      {tab === "payments" ? payments : null}
      {tab === "delivery" ? delivery : null}

      <ConfirmDialog change={change} onCancel={() => setChange(null)} onConfirm={apply} />
    </div>
  );
}

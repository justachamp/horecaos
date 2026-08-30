/* Control-plane shell.
 *
 * This console belongs to the people who run Qoida as a business — account
 * managers, finance, support, and whoever configures the platform. Its subject
 * is the customer: who they are, how they were brought on, what they pay, what
 * they use, and when they leave.
 *
 * Engineering surfaces are deliberately absent. Provider installations, message
 * queues, dead letters, and migration tooling are real and necessary, and they
 * belong to whoever operates the platform rather than to whoever sells it. An
 * account manager chasing an unpaid invoice should not have to scroll past a
 * dead-letter queue to find it.
 *
 * State is hoisted here and navigation is by state rather than a router, which
 * is the right shape for a prototype: a prototype that needs a router has
 * started becoming an application.
 */

import { useState } from "react";
import { MENU } from "./data";
import { ink, inkMuted, hairline, canvas, blue } from "./components";

import Overview from "./Overview";
import Tenants from "./Tenants";
import Onboarding from "./Onboarding";
import Subscriptions from "./Subscriptions";
import Payments from "./Payments";
import Statistics from "./Statistics";
import Config from "./Config";
import Staff from "./Staff";

const RAIL = 256;
const TOPBAR = 48;

export default function App() {
  const [section, setSection] = useState("overview");

  /* Tenants */
  const [tenantId, setTenantId] = useState(null);
  const [tenantTab, setTenantTab] = useState("summary");
  const [tenantFilter, setTenantFilter] = useState("all");
  const [tenantSearch, setTenantSearch] = useState("");

  /* Onboarding */
  const [onboardingTab, setOnboardingTab] = useState("pipeline");

  /* Payments */
  const [invoiceFilter, setInvoiceFilter] = useState("all");
  const [invoiceId, setInvoiceId] = useState(null);

  /* Statistics */
  const [statsRange, setStatsRange] = useState("30d");

  /* Configuration */
  const [configTab, setConfigTab] = useState("general");

  const body = {
    overview: <Overview onNavigate={setSection} onOpenTenant={(id) => { setTenantId(id); setSection("tenants"); }} />,
    tenants: (
      <Tenants
        tenantId={tenantId} setTenantId={setTenantId}
        tab={tenantTab} setTab={setTenantTab}
        filter={tenantFilter} setFilter={setTenantFilter}
        search={tenantSearch} setSearch={setTenantSearch}
      />
    ),
    onboarding: <Onboarding tab={onboardingTab} setTab={setOnboardingTab} />,
    subscriptions: <Subscriptions />,
    payments: (
      <Payments
        filter={invoiceFilter} setFilter={setInvoiceFilter}
        invoiceId={invoiceId} setInvoiceId={setInvoiceId}
      />
    ),
    statistics: <Statistics range={statsRange} setRange={setStatsRange} />,
    config: <Config tab={configTab} setTab={setConfigTab} />,
    staff: <Staff />,
  }[section];

  return (
    <div style={{ display: "flex", minHeight: "100vh" }}>
      {/* ── rail ─────────────────────────────────────────────────────────── */}
      <nav
        style={{
          width: RAIL, flexShrink: 0, background: "var(--q-inverse)",
          display: "flex", flexDirection: "column", position: "sticky",
          top: 0, height: "100vh",
        }}
      >
        <div style={{ height: TOPBAR, display: "flex", alignItems: "center", padding: "0 16px", flexShrink: 0 }}>
          {/* No logo was provided; the wordmark stands in, per the design system. */}
          <span className="q-body-em" style={{ color: "#fff", letterSpacing: "0.16px" }}>
            qoida<span style={{ color: blue }}>.</span>
          </span>
          <span className="q-caption" style={{ color: "var(--q-inverse-ink-muted)", marginLeft: 8 }}>
            control plane
          </span>
        </div>

        <div style={{ flex: 1, overflowY: "auto", paddingTop: 8 }}>
          {MENU.map((m) => {
            const on = m.id === section;
            return (
              <button
                key={m.id}
                type="button"
                onClick={() => setSection(m.id)}
                className="q-body-sm"
                style={{
                  display: "block", width: "100%", textAlign: "left",
                  padding: "10px 16px", border: "none", cursor: "pointer",
                  background: on ? "#262626" : "transparent",
                  color: on ? "#fff" : "var(--q-inverse-ink-muted)",
                  borderLeft: on ? `3px solid ${blue}` : "3px solid transparent",
                  transition: "background var(--q-dur-base) var(--q-ease-productive)",
                }}
              >
                {m.label}
              </button>
            );
          })}
        </div>

        <div style={{ padding: 16, borderTop: "1px solid #393939", flexShrink: 0 }}>
          <div className="q-caption" style={{ color: "var(--q-inverse-ink-muted)" }}>Signed in as</div>
          <div className="q-body-sm" style={{ color: "#fff" }}>Aziza Karimova</div>
          <div className="q-caption" style={{ color: "var(--q-inverse-ink-muted)", marginTop: 2 }}>
            Platform administrator
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
            {MENU.find((m) => m.id === section)?.label}
          </span>
          <span className="q-caption" style={{ marginLeft: "auto", color: inkMuted }}>
            Asia/Tashkent · 21.08.2026
          </span>
        </header>

        <main style={{ flex: 1, padding: 24, minWidth: 0 }}>{body}</main>
      </div>
    </div>
  );
}

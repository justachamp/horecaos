package uz.horecaos.platform.notifications.api;

/**
 * Raises one {@link ControlPlaneAlert} (ADR 0058).
 *
 * <p><strong>Honest about what this build ships.</strong> ADR 0058's own
 * checklist lists "control-plane subscriptions (onboarding, approvals,
 * drift, bands, subscriptions)" as a separate, not-started item from
 * "trigger listeners per event class outside ordering" — this build is the
 * latter. Building the former — a real Telegram binding/subscription model
 * for a platform-owned (not tenant-owned) chat — turned out to need either
 * a synthetic "platform tenant" row polluting every tenant listing/billing
 * surface, or a genuinely tenant-less binding/notification schema cutting
 * across {@code integration.bindings} and {@code notifications.notifications}'s
 * {@code tenant_id NOT NULL} columns (both enforced at the database level,
 * correctly, per the tenant-isolation discipline this platform holds
 * everywhere else) — a change too wide to make safely alongside six other
 * modules' trigger listeners in one build.
 *
 * <p>So v1 of this port is the same shape {@code OnboardingScheduler}
 * already uses for control-plane visibility: a structured WARN log line and
 * a Micrometer counter tagged by {@code eventClass}, which {@code
 * infra/observability/horecaos-probe.sh} — already the platform's real
 * control-plane alerting channel, per that class's own Javadoc — can be
 * extended to watch the same way it watches {@code
 * horecaos_onboarding_runs_stalled_age_seconds} today. Every caller below
 * is written against this interface alone, so replacing the implementation
 * with a real Telegram send is a one-file change once that schema work
 * lands, exactly the seam {@link
 * uz.horecaos.platform.notifications.application.TelegramOperationsEntitlementGate}
 * documents for its own provisional key.
 */
public interface ControlPlaneAlertPort {

    void raise(ControlPlaneAlert alert);
}

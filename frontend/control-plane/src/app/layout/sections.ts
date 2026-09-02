import { Capability } from '../core/auth/capability';
import { MessageKey } from '../core/i18n/messages.en';

/**
 * The map of this console.
 *
 * Every section the surface will have is declared, because the capability
 * each one needs is a decision about the API and belongs beside the others
 * rather than being re-derived when somebody builds the screen. Only the ones
 * with a `route` exist; the rail renders those and nothing else, since a
 * navigation entry that leads nowhere is worse than an absent one.
 *
 * Provider *installations* — connecting Click, Payme, or Telegram, deciding
 * which merchant account a restaurant settles under — moved to the
 * `operations` app's own Settings section per ADR 0065's 2026-09-02
 * amendment: it is tenant self-service, not platform administration, and
 * control-plane never runs a shadow console for a merchant's own business.
 * What stays here is the platform's own engineering and administration view
 * of providers and installations across every tenant (IA §3) — a different
 * audience asking a different question of the same underlying data.
 */
export interface Section {
  readonly id: string;
  readonly labelKey: MessageKey;

  /** Absent until the section has a screen. */
  readonly route?: string;

  /**
   * What the principal must hold to reach it, from the server's registry.
   * Absent means every signed-in operator may see it.
   */
  readonly capability?: Capability;
}

export const SECTIONS: readonly Section[] = [
  { id: 'overview', labelKey: 'nav.overview', route: '/' },
  { id: 'tenants', labelKey: 'nav.tenants', route: '/tenants', capability: 'TENANT_READ' },
  { id: 'onboarding', labelKey: 'nav.onboarding', capability: 'TENANT_ONBOARDING_MANAGE' },
  { id: 'subscriptions', labelKey: 'nav.subscriptions', capability: 'COMMERCIAL_SUBSCRIPTION_MANAGE' },
  { id: 'payments', labelKey: 'nav.payments', capability: 'PAYMENT_READ' },
  { id: 'statistics', labelKey: 'nav.statistics', capability: 'REPORTING_READ' },
  { id: 'configuration', labelKey: 'nav.configuration', capability: 'PLATFORM_ADMIN' },
  { id: 'staff', labelKey: 'nav.staff', capability: 'IAM_GRANT_MANAGE' },
];

/** The sections that have a screen, in rail order. */
export const ROUTED_SECTIONS: readonly Section[] = SECTIONS.filter(
  (section) => section.route !== undefined,
);

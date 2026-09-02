import { Capability } from '../core/auth/capability';
import { MessageKey } from '../core/i18n/messages.en';

/**
 * The map of this console.
 *
 * All eight sections the surface will have are declared, because the capability
 * each one needs is a decision about the API and belongs beside the others
 * rather than being re-derived when somebody builds the screen. Only the ones
 * with a `route` exist; the rail renders those and nothing else, since a
 * navigation entry that leads nowhere is worse than an absent one.
 *
 * Engineering surfaces — dead letters, migration tooling, POS synchronisation
 * runs — are deliberately not here. They are real and they belong to whoever
 * operates the platform, not to whoever sells it. An account manager chasing
 * an unpaid invoice should not scroll past a dead-letter queue.
 *
 * Provider installations are the one exception, and ADR 0065 is explicit about
 * why: connecting Click, Payme or Telegram is tenant *administration* — the
 * owner or admin deciding which merchant account this restaurant settles
 * under — not engineering diagnostics, and it is exactly the surface a tenant
 * self-serves from once nobody with infrastructure access has to write the
 * credential into the vault by hand for them.
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
  {
    id: 'integrations',
    labelKey: 'nav.integrations',
    route: '/integrations',
    capability: 'INTEGRATION_INSTALLATION_MANAGE',
  },
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

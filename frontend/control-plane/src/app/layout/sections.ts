import { Capability } from '../core/auth/capability';
import { MessageKey } from '../core/i18n/messages.en';

/**
 * The map of this console, built from
 * `docs/frontend-information-architecture.md` PART 1 in the platform
 * repository -- the authority for what this app is and is not.
 *
 * Every P-tier row of that document (the first single-location pilot's 23
 * control-plane screens) has a route here. A screen the backend cannot
 * support yet still gets a route -- it renders the "not built" state rather
 * than being hidden, because a grey rail item invites "is this broken?" while
 * an absent one invites nothing. 2-tier and 3-tier rows (wave 2 and wave 3)
 * are deliberately absent: a navigation entry that leads nowhere is worse
 * than one that does not exist yet, and unlike the P-tier gaps above, nobody
 * is waiting on these this wave.
 *
 * `group` is a rail section heading (IA §1-10), shown once above the first
 * routed item that carries it; items with the same `group` value render
 * together.
 */
export interface Section {
  readonly id: string;
  readonly labelKey: MessageKey;

  /** Absent until the section has a screen. */
  readonly route?: string;

  /** The IA part heading this section renders under, when it has a route. */
  readonly group?: MessageKey;

  /**
   * What the principal must hold to reach it, from the server's registry.
   * Absent means every signed-in operator may see it.
   */
  readonly capability?: Capability;
}

export const SECTIONS: readonly Section[] = [
  { id: 'overview', labelKey: 'nav.overview', route: '/' },

  // IA §2 Tenants
  {
    id: 'tenants',
    labelKey: 'nav.tenants',
    route: '/tenants',
    group: 'nav.group.tenants',
    capability: 'TENANT_READ',
  },

  // IA §3 Providers
  {
    id: 'providers',
    labelKey: 'nav.providers',
    route: '/providers',
    group: 'nav.group.providers',
    capability: 'INTEGRATION_INSTALLATION_MANAGE',
  },
  {
    id: 'providerCapabilities',
    labelKey: 'nav.providerCapabilities',
    route: '/providers/capabilities',
    group: 'nav.group.providers',
    capability: 'POS_SYNC_READ',
  },
  {
    id: 'installations',
    labelKey: 'nav.installations',
    route: '/providers/installations',
    group: 'nav.group.providers',
    capability: 'INTEGRATION_INSTALLATION_MANAGE',
  },

  // IA §4 Integration operations
  {
    id: 'messageFlow',
    labelKey: 'nav.messageFlow',
    route: '/integration-ops/message-flow',
    group: 'nav.group.integrationOps',
    capability: 'INTEGRATION_FAILURE_READ',
  },
  {
    id: 'deadLetters',
    labelKey: 'nav.deadLetters',
    route: '/integration-ops/dead-letters',
    group: 'nav.group.integrationOps',
    capability: 'INTEGRATION_FAILURE_READ',
  },

  // IA §5 Commerce
  {
    id: 'entitlements',
    labelKey: 'nav.entitlements',
    route: '/commerce/entitlements',
    group: 'nav.group.commerce',
    capability: 'COMMERCIAL_PLAN_READ',
  },

  // IA §6 Compliance & fiscal
  {
    id: 'fiscalization',
    labelKey: 'nav.fiscalization',
    route: '/compliance/fiscalization',
    group: 'nav.group.compliance',
    capability: 'FISCAL_DOCUMENT_READ',
  },
  {
    id: 'fiscalReference',
    labelKey: 'nav.fiscalReference',
    route: '/compliance/fiscal-reference',
    group: 'nav.group.compliance',
    capability: 'CATALOG_READ',
  },

  // IA §7 Access & security
  {
    id: 'staff',
    labelKey: 'nav.staff',
    route: '/access/staff',
    group: 'nav.group.access',
    capability: 'IAM_GRANT_MANAGE',
  },
  {
    id: 'capabilityRegistry',
    labelKey: 'nav.capabilityRegistry',
    route: '/access/capabilities',
    group: 'nav.group.access',
    capability: 'PLATFORM_ADMIN',
  },
  {
    id: 'secrets',
    labelKey: 'nav.secrets',
    route: '/access/secrets',
    group: 'nav.group.access',
    capability: 'INTEGRATION_INSTALLATION_MANAGE',
  },
  {
    id: 'auditLog',
    labelKey: 'nav.auditLog',
    route: '/access/audit-log',
    group: 'nav.group.access',
    capability: 'AUDIT_READ',
  },

  // IA §8 Platform configuration
  {
    id: 'featureFlags',
    labelKey: 'nav.featureFlags',
    route: '/platform-config/feature-flags',
    group: 'nav.group.platformConfig',
    capability: 'PLATFORM_ADMIN',
  },
  {
    id: 'referenceData',
    labelKey: 'nav.referenceData',
    route: '/platform-config/reference-data',
    group: 'nav.group.platformConfig',
    capability: 'PLATFORM_ADMIN',
  },

  // IA §9 Migration & cutover
  {
    id: 'migrationRuns',
    labelKey: 'nav.migrationRuns',
    route: '/migration/runs',
    group: 'nav.group.migration',
    capability: 'MIGRATION_READ',
  },

  // IA §10 Support
  {
    id: 'globalLookup',
    labelKey: 'nav.globalLookup',
    route: '/support/lookup',
    group: 'nav.group.support',
    capability: 'TENANT_READ',
  },
];

/** The sections that have a screen, in rail order. */
export const ROUTED_SECTIONS: readonly Section[] = SECTIONS.filter(
  (section) => section.route !== undefined,
);

import { Routes } from '@angular/router';

import { authGuard, requiresCapability } from './core/auth/guards';
import { ConsoleShell } from './layout/console-shell';
import { Overview } from './features/overview/overview';

/**
 * The console's whole map, one entry per `docs/frontend-information-architecture.md`
 * PART 1 P-tier screen (wave 28's full build) and 2-tier screen (wave 36),
 * plus `/login` and `/denied`, which are states rather than IA rows.
 *
 * `/login` sits outside the shell and outside {@link authGuard} for the same
 * reason it exists at all (ADR 0062): guarding the page that signs somebody
 * in would refuse to render it to exactly the visitor it is for.
 *
 * Tenant sub-screens (2.2-2.6, 2.8) are not in the rail (see `sections.ts`) —
 * they are reached by drilling into one tenant from 2.1 — but they are real,
 * separately guarded routes here, flat alongside `tenants` in the same style
 * every other route in this file already uses. A 2-tier screen the backend
 * genuinely lacks (see each component's own doc comment) still gets a route
 * here for the same reason 2.8 and 8.1 do: it renders the "not built" state
 * rather than an absent link.
 */
export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./features/auth/sign-in-page').then((m) => m.SignInPage),
  },
  {
    path: '',
    component: ConsoleShell,
    canActivate: [authGuard],
    children: [
      { path: '', component: Overview },

      // IA §1 Overview
      {
        path: 'alerts',
        canActivate: [requiresCapability('PLATFORM_ADMIN')],
        loadComponent: () =>
          import('./features/overview/alerts-incidents').then((m) => m.AlertsIncidents),
      },

      // IA §2 Tenants
      {
        path: 'tenants',
        canActivate: [requiresCapability('TENANT_READ')],
        loadComponent: () =>
          import('./features/tenants/tenant-directory').then((m) => m.TenantDirectory),
      },
      {
        path: 'tenants/:tenantId',
        canActivate: [requiresCapability('TENANT_READ')],
        loadComponent: () => import('./features/tenants/tenant-detail').then((m) => m.TenantDetail),
      },
      {
        path: 'tenants/:tenantId/brands',
        canActivate: [requiresCapability('BRAND_READ')],
        loadComponent: () =>
          import('./features/tenants/tenant-brands').then((m) => m.TenantBrands),
      },
      {
        path: 'tenants/:tenantId/legal-entities',
        canActivate: [requiresCapability('LEGAL_ENTITY_READ')],
        loadComponent: () =>
          import('./features/tenants/tenant-legal-entities').then((m) => m.TenantLegalEntities),
      },
      {
        path: 'tenants/:tenantId/onboarding',
        canActivate: [requiresCapability('TENANT_ONBOARDING_MANAGE')],
        loadComponent: () =>
          import('./features/tenants/tenant-onboarding').then((m) => m.TenantOnboarding),
      },
      {
        path: 'tenants/:tenantId/identity',
        canActivate: [requiresCapability('TENANT_READ')],
        loadComponent: () =>
          import('./features/tenants/tenant-identity').then((m) => m.TenantIdentity),
      },
      {
        path: 'tenants/:tenantId/impersonation',
        canActivate: [requiresCapability('TENANT_READ')],
        loadComponent: () =>
          import('./features/tenants/tenant-impersonation').then((m) => m.TenantImpersonation),
      },
      {
        path: 'tenants/configuration',
        canActivate: [requiresCapability('PLATFORM_ADMIN')],
        loadComponent: () =>
          import('./features/tenants/configuration-policy').then((m) => m.ConfigurationPolicy),
      },

      // IA §3 Providers
      {
        path: 'providers',
        canActivate: [requiresCapability('INTEGRATION_INSTALLATION_MANAGE')],
        loadComponent: () =>
          import('./features/providers/provider-registry').then((m) => m.ProviderRegistry),
      },
      {
        path: 'providers/capabilities',
        canActivate: [requiresCapability('POS_SYNC_READ')],
        loadComponent: () =>
          import('./features/providers/capability-matrix').then((m) => m.CapabilityMatrix),
      },
      {
        path: 'providers/installations',
        canActivate: [requiresCapability('INTEGRATION_INSTALLATION_MANAGE')],
        loadComponent: () =>
          import('./features/providers/installations-explorer').then((m) => m.InstallationsExplorer),
      },
      {
        path: 'providers/contracts',
        canActivate: [requiresCapability('PLATFORM_ADMIN')],
        loadComponent: () =>
          import('./features/providers/contracts-versions').then((m) => m.ContractsVersions),
      },
      {
        path: 'providers/sandbox',
        canActivate: [requiresCapability('INTEGRATION_INSTALLATION_MANAGE')],
        loadComponent: () =>
          import('./features/providers/sandbox-contract-tests').then((m) => m.SandboxContractTests),
      },

      // IA §4 Integration operations
      {
        path: 'integration-ops/message-flow',
        canActivate: [requiresCapability('INTEGRATION_FAILURE_READ')],
        loadComponent: () =>
          import('./features/integration-ops/message-flow').then((m) => m.MessageFlow),
      },
      {
        path: 'integration-ops/dead-letters',
        canActivate: [requiresCapability('INTEGRATION_FAILURE_READ')],
        loadComponent: () =>
          import('./features/integration-ops/dead-letters').then((m) => m.DeadLetters),
      },
      {
        path: 'integration-ops/webhook-deliveries',
        canActivate: [requiresCapability('INTEGRATION_FAILURE_READ')],
        loadComponent: () =>
          import('./features/integration-ops/webhook-deliveries').then((m) => m.WebhookDeliveries),
      },
      {
        path: 'integration-ops/error-taxonomy',
        canActivate: [requiresCapability('INTEGRATION_FAILURE_READ')],
        loadComponent: () =>
          import('./features/integration-ops/error-taxonomy').then((m) => m.ErrorTaxonomy),
      },

      // IA §5 Commerce
      {
        path: 'commerce/entitlements',
        canActivate: [requiresCapability('COMMERCIAL_PLAN_READ')],
        loadComponent: () =>
          import('./features/commerce/entitlements').then((m) => m.Entitlements),
      },
      {
        path: 'commerce/plans',
        canActivate: [requiresCapability('COMMERCIAL_PLAN_READ')],
        loadComponent: () =>
          import('./features/commerce/plan-catalog').then((m) => m.PlanCatalog),
      },
      {
        path: 'commerce/modules',
        canActivate: [requiresCapability('COMMERCIAL_PLAN_READ')],
        loadComponent: () =>
          import('./features/commerce/module-catalog').then((m) => m.ModuleCatalog),
      },
      {
        path: 'commerce/usage',
        canActivate: [requiresCapability('COMMERCIAL_USAGE_READ')],
        loadComponent: () =>
          import('./features/commerce/usage-metering').then((m) => m.UsageMetering),
      },
      {
        path: 'commerce/invoices',
        canActivate: [requiresCapability('COMMERCIAL_PLAN_READ')],
        loadComponent: () =>
          import('./features/commerce/invoices-wallet').then((m) => m.InvoicesWallet),
      },

      // IA §6 Compliance & fiscal
      {
        path: 'compliance/fiscalization',
        canActivate: [requiresCapability('FISCAL_DOCUMENT_READ')],
        loadComponent: () =>
          import('./features/compliance/fiscalization').then((m) => m.Fiscalization),
      },
      {
        path: 'compliance/fiscal-reference',
        canActivate: [requiresCapability('CATALOG_READ')],
        loadComponent: () =>
          import('./features/compliance/fiscal-reference').then((m) => m.FiscalReference),
      },
      {
        path: 'compliance/residency',
        canActivate: [requiresCapability('PLATFORM_ADMIN')],
        loadComponent: () =>
          import('./features/compliance/residency-hosting').then((m) => m.ResidencyHosting),
      },
      {
        path: 'compliance/pii-classification',
        canActivate: [requiresCapability('PLATFORM_ADMIN')],
        loadComponent: () =>
          import('./features/compliance/pii-classification').then((m) => m.PiiClassification),
      },
      {
        path: 'compliance/approvals',
        canActivate: [requiresCapability('APPROVAL_DECIDE')],
        loadComponent: () =>
          import('./features/compliance/platform-approvals').then((m) => m.PlatformApprovals),
      },

      // IA §7 Access & security
      {
        path: 'access/staff',
        canActivate: [requiresCapability('IAM_GRANT_MANAGE')],
        loadComponent: () => import('./features/access/staff').then((m) => m.Staff),
      },
      {
        path: 'access/capabilities',
        canActivate: [requiresCapability('PLATFORM_ADMIN')],
        loadComponent: () =>
          import('./features/access/capability-registry').then((m) => m.CapabilityRegistry),
      },
      {
        path: 'access/secrets',
        canActivate: [requiresCapability('INTEGRATION_INSTALLATION_MANAGE')],
        loadComponent: () => import('./features/access/secrets').then((m) => m.Secrets),
      },
      {
        path: 'access/audit-log',
        canActivate: [requiresCapability('AUDIT_READ')],
        loadComponent: () => import('./features/access/audit-log').then((m) => m.AuditLog),
      },
      {
        path: 'access/debugger',
        canActivate: [requiresCapability('PLATFORM_ADMIN')],
        loadComponent: () =>
          import('./features/access/access-debugger').then((m) => m.AccessDebugger),
      },

      // IA §8 Platform configuration
      {
        path: 'platform-config/feature-flags',
        canActivate: [requiresCapability('PLATFORM_ADMIN')],
        loadComponent: () =>
          import('./features/platform-config/feature-flags').then((m) => m.FeatureFlags),
      },
      {
        path: 'platform-config/reference-data',
        canActivate: [requiresCapability('PLATFORM_ADMIN')],
        loadComponent: () =>
          import('./features/platform-config/reference-data').then((m) => m.ReferenceDataScreen),
      },
      {
        path: 'platform-config/business-types',
        canActivate: [requiresCapability('PLATFORM_ADMIN')],
        loadComponent: () =>
          import('./features/platform-config/business-types').then((m) => m.BusinessTypes),
      },
      {
        path: 'platform-config/notification-providers',
        canActivate: [requiresCapability('PLATFORM_ADMIN')],
        loadComponent: () =>
          import('./features/platform-config/notification-providers').then((m) => m.NotificationProviders),
      },
      {
        path: 'platform-config/policy-defaults',
        canActivate: [requiresCapability('PLATFORM_ADMIN')],
        loadComponent: () =>
          import('./features/platform-config/policy-defaults').then((m) => m.PolicyDefaults),
      },

      // IA §9 Migration & cutover
      {
        path: 'migration/runs',
        canActivate: [requiresCapability('MIGRATION_READ')],
        loadComponent: () =>
          import('./features/migration/migration-runs').then((m) => m.MigrationRuns),
      },
      {
        path: 'migration/id-mapping',
        canActivate: [requiresCapability('MIGRATION_READ')],
        loadComponent: () =>
          import('./features/migration/id-mapping-explorer').then((m) => m.IdMappingExplorer),
      },
      {
        path: 'migration/dual-run-comparison',
        canActivate: [requiresCapability('MIGRATION_READ')],
        loadComponent: () =>
          import('./features/migration/dual-run-comparison').then((m) => m.DualRunComparison),
      },
      {
        path: 'migration/cutover-checklist',
        canActivate: [requiresCapability('MIGRATION_READ')],
        loadComponent: () =>
          import('./features/migration/cutover-checklist').then((m) => m.CutoverChecklist),
      },

      // IA §10 Support
      {
        path: 'support/lookup',
        canActivate: [requiresCapability('TENANT_READ')],
        loadComponent: () =>
          import('./features/support/global-lookup').then((m) => m.GlobalLookup),
      },
      {
        path: 'support/issue-queue',
        canActivate: [requiresCapability('TENANT_READ')],
        loadComponent: () =>
          import('./features/support/tenant-issue-queue').then((m) => m.TenantIssueQueue),
      },

      {
        path: 'denied',
        loadComponent: () => import('./features/states/access-denied').then((m) => m.AccessDenied),
      },
      // An unknown path inside the console goes to the overview rather than to
      // a 404 screen: every URL here is one the console itself produced, so a
      // miss means a stale bookmark, and the overview is where that person
      // wanted to start anyway.
      { path: '**', redirectTo: '' },
    ],
  },
];

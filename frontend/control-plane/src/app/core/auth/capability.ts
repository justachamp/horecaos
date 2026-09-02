/**
 * The capabilities this console asks about (ADR 0025).
 *
 * The server owns the registry — `uz.horecaos.platform.iam.api.Capability` — and
 * serialises each value as its enum name, so these strings are the wire
 * values. Only the ones this application's navigation and guards reference are
 * listed; the registry has around ninety, and copying all of them would create
 * a second catalogue to keep in step for no benefit.
 *
 * A capability check in a client is a usability affordance and nothing more.
 * The API is the enforcement point: every mutation is authorized again on the
 * server, and hiding a control the caller could in fact use is a nuisance
 * while showing one they cannot is a 403 they did not deserve.
 */
export type Capability =
  | 'TENANT_READ'
  | 'TENANT_WRITE'
  | 'TENANT_ONBOARDING_MANAGE'
  | 'BRAND_READ'
  | 'BRAND_WRITE'
  | 'LOCATION_READ'
  | 'LOCATION_WRITE'
  | 'LEGAL_ENTITY_READ'
  | 'LEGAL_ENTITY_MANAGE'
  | 'COMMERCIAL_PLAN_READ'
  | 'COMMERCIAL_SUBSCRIPTION_MANAGE'
  | 'COMMERCIAL_OVERRIDE_APPROVE'
  | 'COMMERCIAL_USAGE_READ'
  | 'COMMERCIAL_USAGE_ADJUST'
  | 'PAYMENT_READ'
  | 'REPORTING_READ'
  | 'AUDIT_READ'
  | 'IAM_GRANT_MANAGE'
  | 'APPROVAL_DECIDE'
  | 'MIGRATION_READ'
  // The platform's own, cross-tenant view of providers and installations (IA
  // §3) -- not the tenant self-service connect/rotate flow, which ADR 0065's
  // 2026-09-02 amendment moved to the operations app's Settings section.
  | 'INTEGRATION_INSTALLATION_MANAGE'
  | 'INTEGRATION_FAILURE_READ'
  | 'INTEGRATION_FAILURE_RETRY'
  | 'INTEGRATION_FAILURE_RESOLVE'
  | 'POS_SYNC_READ'
  | 'CATALOG_READ'
  | 'FISCAL_DOCUMENT_READ'
  | 'FISCAL_DOCUMENT_RESOLVE'
  | 'PLATFORM_ADMIN';

/** One scope a principal holds. Mirrors CapabilityView.ScopeGrant. */
export interface ScopeGrant {
  readonly scope: {
    readonly type: 'PLATFORM' | 'TENANT' | 'BRAND' | 'LOCATION';
    readonly tenantId: string | null;
    readonly brandId: string | null;
    readonly locationId: string | null;
  };
  readonly roleCode: string;
  readonly capabilities: readonly string[];
}

/**
 * `GET /api/v1/session/context`. Mirrors uz.horecaos.platform.iam.api.CapabilityView.
 *
 * `capabilities` may contain values this client does not know, because ADR
 * 0031 allows new enum values within a major version. Unknown values are kept
 * rather than dropped: this console does not enumerate them, it asks whether
 * one it knows about is present.
 */
export interface SessionContext {
  readonly subject: string;
  readonly activeTenantId: string | null;
  readonly capabilities: readonly string[];
  readonly scopes: readonly ScopeGrant[];
  readonly contextVersion: number;
}

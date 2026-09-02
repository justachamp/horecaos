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
  | 'LOCATION_READ'
  | 'COMMERCIAL_SUBSCRIPTION_MANAGE'
  | 'COMMERCIAL_OVERRIDE_APPROVE'
  | 'PAYMENT_READ'
  | 'REPORTING_READ'
  | 'AUDIT_READ'
  | 'IAM_GRANT_MANAGE'
  | 'MIGRATION_READ'
  // ADR 0065: the Integrations section. Installations (any provider category)
  // are gated on the first; a merchant binding's own row -- the one that
  // decides which legal entity a payment settles under -- needs the second as
  // well, the same split the server's own Capability javadoc draws.
  | 'INTEGRATION_INSTALLATION_MANAGE'
  | 'INTEGRATION_BINDING_ACTIVATE'
  | 'PAYMENT_MERCHANT_BINDING_MANAGE'
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

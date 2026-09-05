/**
 * Where the Staff section's endpoints live (operations IA §9.1, ADR 0025).
 *
 * All three are `GrantController`/`TelegramStaffLinkCodeController` methods —
 * two on the `control-plane` OpenAPI surface (ADR 0057's own grouping is
 * path-prefix-based and predates any frontend importing a generated client,
 * so a tenant-administration read living under `/control-plane/tenants/**`
 * and being called from *this* app is already the established shape:
 * `settings-paths.ts`'s own doc comment says the same thing about the
 * pre-existing legal-entity, sales-channel and order-acceptance-policy
 * reads) — and one already on `/api/v1/tenants/**` (`operations`).
 */
const CONTROL_PLANE = '/api/v1/control-plane';
const OPERATIONS = '/api/v1';

export const staffPaths = {
  /** `GrantController.list`/`.grant` — active-only unless `includeInactive` is passed as a query param. */
  grants(tenantId: string): string {
    return `${CONTROL_PLANE}/tenants/${enc(tenantId)}/grants`;
  },

  grant(tenantId: string, grantId: string): string {
    return `${this.grants(tenantId)}/${enc(grantId)}`;
  },

  /** `GrantController.roles` — the eight tenant-visible `PlatformRole` bundles. */
  roles(tenantId: string): string {
    return `${CONTROL_PLANE}/tenants/${enc(tenantId)}/roles`;
  },

  /** `TelegramStaffLinkCodeController.listLinks` — already on the operations surface. */
  telegramStaffLinks(tenantId: string): string {
    return `${OPERATIONS}/tenants/${enc(tenantId)}/staff/telegram/links`;
  },

  /** `OperationsBrandController.list` — reused from `settings-paths.ts`'s own tree; see this file's doc. */
  brands(tenantId: string): string {
    return `${OPERATIONS}/operations/tenants/${enc(tenantId)}/brands`;
  },

  /** `OperationsBrandController.locations` — one brand's branches. */
  brandLocations(tenantId: string, brandId: string): string {
    return `${this.brands(tenantId)}/${enc(brandId)}/locations`;
  },

  /**
   * `AuditController.operationsSearch`/`.operationsDetail` — 9.3's activity
   * log, added this wave on the `/api/v1/operations/**` prefix (the
   * pre-existing control-plane route this wave mirrors was never reachable
   * from this app's OpenAPI group; ADR 0057).
   */
  auditEvents(tenantId: string): string {
    return `${OPERATIONS}/operations/tenants/${enc(tenantId)}/audit-events`;
  },

  auditEvent(tenantId: string, eventId: string): string {
    return `${this.auditEvents(tenantId)}/${enc(eventId)}`;
  },

  /**
   * `ApprovalRequestController.operationsPending`/`.operationsDecide` — 9.4's
   * approvals worklist (wave 45), added on the `/api/v1/operations/**` prefix
   * for the same reason `auditEvents` was: the pre-existing control-plane
   * route (`ApprovalRequestController.pending`/`.decide`) serves HorecaOS
   * staff working a tenant on its behalf and was never reachable from this
   * app's OpenAPI group (ADR 0057). Both mappings call the same
   * `ApprovalDecisionService`, so nothing about who may decide what differs.
   */
  approvalRequests(tenantId: string): string {
    return `${OPERATIONS}/operations/tenants/${enc(tenantId)}/approval-requests`;
  },

  approvalDecision(tenantId: string, requestId: string): string {
    return `${this.approvalRequests(tenantId)}/${enc(requestId)}/decision`;
  },
} as const;

function enc(value: string): string {
  return encodeURIComponent(value);
}

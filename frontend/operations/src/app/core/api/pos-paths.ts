/**
 * Where POS catalog synchronization and its mapping pane live on the platform.
 *
 * `PosSyncRunController` and `PosMappingController` are mapped under
 * `/api/v1/control-plane/tenants/{tenantId}/**` and belong to the
 * `control-plane` OpenAPI surface group — the same pre-existing mismatch
 * `catalog-paths.ts` documents for catalog authoring, and for the same reason:
 * a merchant's own POS import and mapping work living under `control-plane`
 * is an architectural leftover this wave did not introduce. Tenant-scoped
 * only — neither controller takes a brand or a location path segment, so this
 * file needs only `tenantId` where `catalog-paths.ts` needs a full brand
 * scope.
 *
 * `OrderPosExportController` (wave P42, gap map row `1.2i`) is the one
 * exception: it is the operations-plane sibling read/command over an order's
 * own export, correctly on `/api/v1/operations/**`, and it lives here rather
 * than in `operations-paths.ts` because it is still POS domain — the same
 * `pos_order_exports` table `syncRuns`/`mappings` above read, seen from the
 * order it belongs to instead of the binding it was sent through.
 */

const CONTROL_PLANE = '/api/v1/control-plane';
const OPERATIONS = '/api/v1/operations';

/** The one identifier every path here needs (ADR 0025, tenant scope). */
export interface TenantScope {
  readonly tenantId: string;
}

function tenant(scope: TenantScope): string {
  return `/tenants/${encodeURIComponent(scope.tenantId)}`;
}

export const posPaths = {
  // ---------------------------------------------------------- sync runs

  /** A binding's run history (`GET`, `bindingId` query param) — same path for `POST` (start). */
  syncRuns(scope: TenantScope): string {
    return `${CONTROL_PLANE}${tenant(scope)}/pos-sync-runs`;
  },

  /** One run's full detail. */
  syncRun(scope: TenantScope, runId: string): string {
    return `${posPaths.syncRuns(scope)}/${encodeURIComponent(runId)}`;
  },

  /** The run's difference report. */
  syncRunDifferences(scope: TenantScope, runId: string): string {
    return `${posPaths.syncRun(scope, runId)}/differences`;
  },

  /** The run's per-item apply outcomes. */
  syncRunApplyItems(scope: TenantScope, runId: string): string {
    return `${posPaths.syncRun(scope, runId)}/apply-items`;
  },

  /** Record a review decision on one recommended-REVIEW difference. */
  syncRunReviewDecisions(scope: TenantScope, runId: string): string {
    return `${posPaths.syncRun(scope, runId)}/review-decisions`;
  },

  /** Execute everything an approved run can apply. */
  syncRunApply(scope: TenantScope, runId: string): string {
    return `${posPaths.syncRun(scope, runId)}/apply`;
  },

  /** Continue an interrupted run. */
  syncRunResume(scope: TenantScope, runId: string): string {
    return `${posPaths.syncRun(scope, runId)}/resume`;
  },

  // ---------------------------------------------------------- mappings

  /** A binding's mappings for one entity type (`GET`, `bindingId`/`entityType`/`status` query params) — same path for `POST` (create). */
  mappings(scope: TenantScope): string {
    return `${CONTROL_PLANE}${tenant(scope)}/pos-mappings`;
  },

  /** The provider's own not-yet-mapped candidates (`bindingId`/`entityType` query params). */
  mappingsUnmapped(scope: TenantScope): string {
    return `${posPaths.mappings(scope)}/unmapped`;
  },

  /** Retire one mapping. */
  mappingRetire(scope: TenantScope, mappingId: string): string {
    return `${posPaths.mappings(scope)}/${encodeURIComponent(mappingId)}/retire`;
  },

  /** Match every unambiguous name pair for one binding and entity type. */
  mappingsBulkAutoMatch(scope: TenantScope): string {
    return `${posPaths.mappings(scope)}/bulk-auto-match`;
  },

  // ---------------------------------------------------------- order export (operations plane)

  /**
   * `OrderPosExportController.forOrder` (wave P42, gap map row `1.2i`) — one
   * order's export to the branch's till, and whether the affordance applies
   * at all (`posCapable`).
   */
  orderPosExport(scope: TenantScope, orderId: string): string {
    return `${OPERATIONS}${tenant(scope)}/orders/${encodeURIComponent(orderId)}/pos-export`;
  },

  /** Push a pending export, or retry one the state machine still permits sending. Mutation: key and reason required. */
  orderPosExportPush(scope: TenantScope, orderId: string): string {
    return `${posPaths.orderPosExport(scope, orderId)}/push`;
  },
};

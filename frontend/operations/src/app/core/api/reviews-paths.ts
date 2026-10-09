import { BrandScope } from './catalog-paths';

/**
 * Where a brand's own order reviews live (`OperationsReviewController`,
 * ADR 0071, operations §5.4 Reviews).
 *
 * `/api/v1/operations/**` — new this wave, no legacy shape to inherit, the
 * same reasoning `loyalty-paths.ts` and `operations-paths.ts` already give
 * for their own `OPERATIONS`-native additions. Brand-scoped rather than
 * location-scoped: the screen filters *within* a brand's reviews (including
 * by location), the same shape `marketing-paths.ts`'s audience reads use, and
 * `review.read` is itself a `BRAND`-scope capability (ADR 0071).
 */
const OPERATIONS = '/api/v1/operations';

function tenantBrand(scope: BrandScope): string {
  return `/tenants/${encodeURIComponent(scope.tenantId)}/brands/${encodeURIComponent(scope.brandId)}`;
}

function enc(value: string): string {
  return encodeURIComponent(value);
}

/**
 * The service-recovery worklist and the comment moderation queue (ADR 0159,
 * `OperationsRecoveryCaseController` and `OperationsReviewModerationController`), beside the
 * review list above.
 *
 * The worklist has two readings: the brand's (`review`-style brand scope, what a manager
 * watches) and one branch's (location scope, what the person working a case holds). A case is
 * always *worked* at its own branch -- the drawer, the transitions, the notes and the links
 * all carry the case's `locationId` -- because `recovery.case.manage` is a location-scope
 * capability for everything except the brand list.
 */
export const reviewPaths = {
  list(scope: BrandScope): string {
    return `${OPERATIONS}${tenantBrand(scope)}/reviews`;
  },

  summary(scope: BrandScope): string {
    return `${this.list(scope)}/summary`;
  },

  recoveryCases(scope: BrandScope): string {
    return `${OPERATIONS}${tenantBrand(scope)}/recovery-cases`;
  },

  recoverySummary(scope: BrandScope): string {
    return `${this.recoveryCases(scope)}/summary`;
  },

  locationRecoveryCases(scope: BrandScope, locationId: string): string {
    return `${OPERATIONS}${tenantBrand(scope)}/locations/${enc(locationId)}/recovery-cases`;
  },

  recoveryCase(scope: BrandScope, locationId: string, caseId: string): string {
    return `${this.locationRecoveryCases(scope, locationId)}/${enc(caseId)}`;
  },

  recoveryTransitions(scope: BrandScope, locationId: string, caseId: string): string {
    return `${this.recoveryCase(scope, locationId, caseId)}/transitions`;
  },

  recoveryNotes(scope: BrandScope, locationId: string, caseId: string): string {
    return `${this.recoveryCase(scope, locationId, caseId)}/notes`;
  },

  recoveryCompensations(scope: BrandScope, locationId: string, caseId: string): string {
    return `${this.recoveryCase(scope, locationId, caseId)}/compensations`;
  },

  moderationQueue(scope: BrandScope): string {
    return `${this.list(scope)}/moderation-queue`;
  },

  moderationDecision(scope: BrandScope, reviewId: string): string {
    return `${this.list(scope)}/${enc(reviewId)}/moderation-decisions`;
  },
} as const;

/**
 * Feedback settings (ADR 0159, `OperationsFeedbackSettingsController`): the tag library, when a
 * customer is asked to rate an order, and which reviews open a recovery case. Tenant-scoped, with
 * a brand route for each policy.
 */
export const feedbackSettingsPaths = {
  tags(tenantId: string): string {
    return `${OPERATIONS}/tenants/${enc(tenantId)}/feedback/tags`;
  },

  tag(tenantId: string, tagId: string): string {
    return `${this.tags(tenantId)}/${enc(tagId)}`;
  },

  starterPack(tenantId: string): string {
    return `${this.tags(tenantId)}/starter-pack`;
  },

  promptPolicy(tenantId: string): string {
    return `${OPERATIONS}/tenants/${enc(tenantId)}/feedback/prompt-policy`;
  },

  brandPromptPolicy(scope: BrandScope): string {
    return `${OPERATIONS}${tenantBrand(scope)}/feedback/prompt-policy`;
  },

  recoveryPolicy(tenantId: string): string {
    return `${OPERATIONS}/tenants/${enc(tenantId)}/feedback/recovery-policy`;
  },

  brandRecoveryPolicy(scope: BrandScope): string {
    return `${OPERATIONS}${tenantBrand(scope)}/feedback/recovery-policy`;
  },
} as const;

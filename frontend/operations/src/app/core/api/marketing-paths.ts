import { BrandScope } from './catalog-paths';

/**
 * Where the Marketing section's endpoints live (`OperationsMarketingController`,
 * ADR 0044).
 *
 * Brand-scoped throughout — every call here takes a bare {@link BrandScope},
 * never a full `LocationScope`, because the frequency cap is per brand, quiet
 * hours are in the brand's timezone, and two brands under one tenant are two
 * businesses to the customer. This controller was never moved onto the ADR
 * 0031 `/api/v1/operations` prefix, so it sits beside `customers`/`orders` on
 * the legacy `/api/v1/tenants` one — see `operations-paths.ts`'s own doc for
 * the split this application tracks in one place per surface.
 */
const TENANT = '/api/v1/tenants';

function enc(value: string): string {
  return encodeURIComponent(value);
}

function base(scope: BrandScope): string {
  return `${TENANT}/${enc(scope.tenantId)}/brands/${enc(scope.brandId)}/marketing`;
}

export const marketingPaths = {
  audiences(scope: BrandScope): string {
    return `${base(scope)}/audiences`;
  },

  audience(scope: BrandScope, audienceId: string): string {
    return `${this.audiences(scope)}/${enc(audienceId)}`;
  },

  audiencePredicates(scope: BrandScope, audienceId: string): string {
    return `${this.audience(scope, audienceId)}/predicates`;
  },

  audienceSnapshots(scope: BrandScope, audienceId: string): string {
    return `${this.audience(scope, audienceId)}/snapshots`;
  },

  /**
   * `OperationsMarketingController.export` — deliberately not nested under
   * one audience's own snapshot path, mirroring the controller's own
   * `/audiences/snapshots/{snapshotId}/exports` mapping: a snapshot id is
   * already globally unique, so the audience id in the URL would be
   * redundant rather than a scoping check.
   */
  audienceSnapshotExports(scope: BrandScope, snapshotId: string): string {
    return `${base(scope)}/audiences/snapshots/${enc(snapshotId)}/exports`;
  },

  campaigns(scope: BrandScope): string {
    return `${base(scope)}/campaigns`;
  },

  campaign(scope: BrandScope, campaignId: string): string {
    return `${this.campaigns(scope)}/${enc(campaignId)}`;
  },

  campaignEstimates(scope: BrandScope, campaignId: string): string {
    return `${this.campaign(scope, campaignId)}/estimates`;
  },

  campaignSubmissions(scope: BrandScope, campaignId: string): string {
    return `${this.campaign(scope, campaignId)}/submissions`;
  },

  campaignApprovals(scope: BrandScope, campaignId: string): string {
    return `${this.campaign(scope, campaignId)}/approvals`;
  },

  campaignLaunches(scope: BrandScope, campaignId: string): string {
    return `${this.campaign(scope, campaignId)}/launches`;
  },

  campaignHalts(scope: BrandScope, campaignId: string): string {
    return `${this.campaign(scope, campaignId)}/halts`;
  },

  campaignResumptions(scope: BrandScope, campaignId: string): string {
    return `${this.campaign(scope, campaignId)}/resumptions`;
  },

  /** Row 6.4: re-arms a halted scheduled send for a new future moment. */
  campaignReschedules(scope: BrandScope, campaignId: string): string {
    return `${this.campaign(scope, campaignId)}/reschedules`;
  },

  campaignRecipients(scope: BrandScope, campaignId: string): string {
    return `${this.campaign(scope, campaignId)}/recipients`;
  },

  /** Row 7.9b: the store-level `recipientCounts` rollup, exposed for the first time this wave. */
  campaignRecipientCounts(scope: BrandScope, campaignId: string): string {
    return `${this.campaignRecipients(scope, campaignId)}/counts`;
  },

  suppressions(scope: BrandScope): string {
    return `${base(scope)}/suppressions`;
  },

  suppressionLifts(scope: BrandScope, suppressionId: string): string {
    return `${this.suppressions(scope)}/${enc(suppressionId)}/lifts`;
  },

  /** `OperationsMarketingController.listChannels` — every channel, and whether it is actually wired (T18). */
  channels(scope: BrandScope): string {
    return `${base(scope)}/channels`;
  },

  /** `CourierBroadcastController` — a dispatcher's own operational SMS blast (T18, operations 6.4b). */
  courierBroadcasts(scope: BrandScope): string {
    return `${base(scope)}/courier-broadcasts`;
  },

  courierBroadcastSends(scope: BrandScope, broadcastId: string): string {
    return `${this.courierBroadcasts(scope)}/${enc(broadcastId)}/sends`;
  },

  /** `AttributionLinkController` — trackable acquisition links (T18, ADR 0044, operations 6.6a). */
  attributionLinks(scope: BrandScope): string {
    return `${base(scope)}/attribution-links`;
  },

  attributionLinkArchives(scope: BrandScope, linkId: string): string {
    return `${this.attributionLinks(scope)}/${enc(linkId)}/archives`;
  },

  /** `AutomationRuleController` — unattended triggers (gap-map row 6.5, ADR 0044). */
  automations(scope: BrandScope): string {
    return `${base(scope)}/automations`;
  },

  automation(scope: BrandScope, ruleId: string): string {
    return `${this.automations(scope)}/${enc(ruleId)}`;
  },

  automationActivations(scope: BrandScope, ruleId: string): string {
    return `${this.automation(scope, ruleId)}/activations`;
  },

  automationDeactivations(scope: BrandScope, ruleId: string): string {
    return `${this.automation(scope, ruleId)}/deactivations`;
  },

  automationReorder(scope: BrandScope): string {
    return `${this.automations(scope)}/reorder`;
  },

  automationRuns(scope: BrandScope, ruleId: string): string {
    return `${this.automation(scope, ruleId)}/runs`;
  },
} as const;

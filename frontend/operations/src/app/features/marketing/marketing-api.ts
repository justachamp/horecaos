import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { BrandScope } from '../../core/api/catalog-paths';
import { command } from '../../core/api/idempotency';
import { marketingPaths } from '../../core/api/marketing-paths';

/**
 * Mirrors `NotificationTemplateController.TemplateResponse` — duplicated
 * rather than imported from `features/settings/notifications/notifications-api.ts`,
 * which this wave does not touch. `NotificationsApi.list` takes a full
 * `LocationScope` for a controller its own path never actually reads a
 * `locationId` from (`settings-paths.ts`'s own `notificationTemplates`
 * builds the URL from `tenantId`/`brandId` alone); this feature is
 * brand-scoped throughout; so the campaign create form reads the same
 * endpoint through its own {@link BrandScope}-typed call instead of forcing
 * a location dependency onto a screen that has none.
 */
export interface MarketingTemplateView {
  readonly id: string;
  readonly brandId: string | null;
  readonly templateKey: string;
  readonly notificationClass: string;
  readonly channel: string;
  readonly consentPurpose: string | null;
  readonly status: string;
  readonly activeVersion: number | null;
  readonly version: number;
}

/** Mirrors `OperationsMarketingController.AudiencePredicateRequest`/`...Response`. */
export interface AudiencePredicate {
  readonly type: string;
  readonly operator: string;
  readonly numericLow?: number | null;
  readonly numericHigh?: number | null;
  readonly dateLow?: string | null;
  readonly dateHigh?: string | null;
  readonly textValues?: readonly string[] | null;
  readonly audienceId?: string | null;
}

/** Mirrors `OperationsMarketingController.AudienceSummaryResponse`. */
export interface AudienceSummary {
  readonly audienceId: string;
  readonly name: string;
  readonly description: string | null;
  readonly status: string;
  readonly definitionVersion: number;
  readonly createdBy: string;
  readonly createdAt: string;
  readonly updatedAt: string;
}

/** Mirrors `OperationsMarketingController.AudienceDetailResponse`. */
export interface AudienceDetail extends AudienceSummary {
  readonly predicates: readonly AudiencePredicate[];
}

export interface DefineAudienceRequest {
  readonly name: string;
  readonly description?: string | null;
  readonly predicates: readonly AudiencePredicate[];
}

export interface RedefineAudienceRequest {
  readonly predicates: readonly AudiencePredicate[];
}

/** Mirrors `OperationsMarketingController.SnapshotResponse`. */
export interface SnapshotResult {
  readonly snapshotId: string;
  readonly candidates: number;
  readonly members: number;
  readonly excluded: number;
}

/** Mirrors `OperationsMarketingController.CreateCampaignRequest`. */
export interface CreateCampaignRequest {
  readonly name: string;
  readonly channel: string;
  readonly consentPurpose: string;
  readonly audienceId: string;
  readonly templateKey: string;
  readonly recipientCap: number;
  readonly costCeilingMinor?: number | null;
  readonly currency: string;
  readonly benefitOfferId?: string | null;
  readonly loyaltyAccrualRuleId?: string | null;
}

/** Mirrors `OperationsMarketingController.CampaignResponse` — the whole lifecycle state. */
export interface CampaignView {
  readonly campaignId: string;
  readonly name: string;
  readonly channel: string;
  readonly consentPurpose: string;
  readonly status: string;
  readonly audienceId: string;
  readonly snapshotId: string | null;
  readonly templateKey: string;
  readonly timezone: string;
  readonly recipientCap: number;
  readonly estimatedRecipients: number | null;
  readonly estimatedCostLowMinor: number | null;
  readonly estimatedCostHighMinor: number | null;
  readonly estimatedDeliverySeconds: number | null;
  readonly costCeilingMinor: number | null;
  readonly reservedCostMinor: number;
  readonly spentCostMinor: number;
  readonly reservedRecipients: number;
  readonly currency: string | null;
  readonly benefitOfferId: string | null;
  readonly loyaltyAccrualRuleId: string | null;
  readonly createdBy: string;
  readonly approvedBy: string | null;
  readonly blockedCount: number;
  readonly pausedAt: string | null;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly version: number;
}

/** Mirrors `OperationsMarketingController.EstimateResponse`. */
export interface EstimateResult {
  readonly snapshotId: string;
  readonly members: number;
  readonly candidates: number;
  readonly costLowMinor: number | null;
  readonly costHighMinor: number | null;
  readonly currency: string;
  readonly estimatedDeliverySeconds: number | null;
}

/** Mirrors `OperationsMarketingController.ResumeResponse`. */
export interface ResumeResult {
  readonly suppressedDuringPause: number;
}

/** Mirrors `OperationsMarketingController.RecipientResponse`. */
export interface RecipientView {
  readonly customerAccountId: string;
  readonly status: string;
  readonly notificationId: string | null;
  readonly refusalReason: string | null;
  readonly deferredUntil: string | null;
  readonly terminalStatus: string | null;
}

/** Mirrors `OperationsMarketingController.SuppressionListItemResponse`. */
export interface SuppressionView {
  readonly suppressionId: string;
  readonly brandId: string | null;
  readonly customerAccountId: string;
  readonly channel: string | null;
  readonly reason: string;
  readonly appliedByType: string;
  readonly statedReason: string | null;
  readonly appliedAt: string;
  readonly expiresAt: string | null;
  readonly liftedAt: string | null;
}

export interface SuppressionRequest {
  readonly customerAccountId: string;
  readonly channel?: string | null;
  readonly reason: string;
  readonly statedReason?: string | null;
}

/**
 * The Marketing section (`frontend-information-architecture.md` §6.4 Campaigns —
 * audiences, campaigns, and suppression, ADR 0044).
 *
 * One client for the whole `OperationsMarketingController` surface: audience
 * targeting is folded into Campaigns rather than a separate IA row (§6.4's own
 * "RFM targeting" bullet), and suppression is the consent-adjacent fact the same
 * row names ("consent and suppression enforced in audience selection").
 */
@Injectable({ providedIn: 'root' })
export class MarketingApi {
  private readonly api = inject(ApiClient);

  /**
   * MARKETING-class notification templates for the create-campaign form's
   * template picker — `NotificationTemplateController.list`, read directly
   * rather than through `NotificationsApi` (see {@link MarketingTemplateView}'s
   * own doc). Best-effort by design: a campaign author who does not also hold
   * `notification.template.author` still gets a working form — the caller
   * falls back to typing the template key by hand on a 403.
   */
  async listTemplates(scope: BrandScope): Promise<readonly MarketingTemplateView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly MarketingTemplateView[]>(
        `/api/v1/tenants/${encodeURIComponent(scope.tenantId)}/brands/${encodeURIComponent(scope.brandId)}/notification-templates`,
      ),
    );
    return result.value ?? [];
  }

  // -------------------------------------------------------------- audiences

  async listAudiences(scope: BrandScope): Promise<readonly AudienceSummary[]> {
    const result = await firstValueFrom(
      this.api.get<readonly AudienceSummary[]>(marketingPaths.audiences(scope)),
    );
    return result.value ?? [];
  }

  async getAudience(scope: BrandScope, audienceId: string): Promise<AudienceDetail> {
    const result = await firstValueFrom(
      this.api.get<AudienceDetail>(marketingPaths.audience(scope, audienceId)),
    );
    return result.value;
  }

  async defineAudience(scope: BrandScope, request: DefineAudienceRequest): Promise<AudienceDetail> {
    return firstValueFrom(
      this.api.post<DefineAudienceRequest, AudienceDetail>(
        marketingPaths.audiences(scope),
        command(request),
      ),
    );
  }

  async redefineAudience(
    scope: BrandScope,
    audienceId: string,
    request: RedefineAudienceRequest,
  ): Promise<AudienceDetail> {
    return firstValueFrom(
      this.api.put<RedefineAudienceRequest, AudienceDetail>(
        marketingPaths.audiencePredicates(scope, audienceId),
        command(request),
      ),
    );
  }

  // -------------------------------------------------------------- campaigns

  async listCampaigns(scope: BrandScope): Promise<readonly CampaignView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly CampaignView[]>(marketingPaths.campaigns(scope)),
    );
    return result.value ?? [];
  }

  async getCampaign(scope: BrandScope, campaignId: string): Promise<CampaignView> {
    const result = await firstValueFrom(
      this.api.get<CampaignView>(marketingPaths.campaign(scope, campaignId)),
    );
    return result.value;
  }

  async createCampaign(scope: BrandScope, request: CreateCampaignRequest): Promise<CampaignView> {
    return firstValueFrom(
      this.api.post<CreateCampaignRequest, CampaignView>(
        marketingPaths.campaigns(scope),
        command(request),
      ),
    );
  }

  /** Snapshots the audience and prices the send. Only legal while the campaign is DRAFT. */
  async estimate(scope: BrandScope, campaignId: string): Promise<EstimateResult> {
    return firstValueFrom(
      this.api.post<null, EstimateResult>(
        marketingPaths.campaignEstimates(scope, campaignId),
        command(null),
      ),
    );
  }

  async submit(scope: BrandScope, campaignId: string): Promise<void> {
    await firstValueFrom(
      this.api.post<null, void>(
        marketingPaths.campaignSubmissions(scope, campaignId),
        command(null),
      ),
    );
  }

  async approve(scope: BrandScope, campaignId: string, reason: string): Promise<void> {
    await firstValueFrom(
      this.api.post<{ reason: string }, void>(
        marketingPaths.campaignApprovals(scope, campaignId),
        command({ reason }),
      ),
    );
  }

  async launch(scope: BrandScope, campaignId: string): Promise<void> {
    await firstValueFrom(
      this.api.post<null, void>(marketingPaths.campaignLaunches(scope, campaignId), command(null)),
    );
  }

  async halt(scope: BrandScope, campaignId: string, reason: string): Promise<void> {
    await firstValueFrom(
      this.api.post<{ reason: string }, void>(
        marketingPaths.campaignHalts(scope, campaignId),
        command({ reason }),
      ),
    );
  }

  async resume(scope: BrandScope, campaignId: string, reason: string): Promise<ResumeResult> {
    return firstValueFrom(
      this.api.post<{ reason: string }, ResumeResult>(
        marketingPaths.campaignResumptions(scope, campaignId),
        command({ reason }),
      ),
    );
  }

  async recipients(
    scope: BrandScope,
    campaignId: string,
    limit = 200,
  ): Promise<readonly RecipientView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly RecipientView[]>(marketingPaths.campaignRecipients(scope, campaignId), {
        params: { limit },
      }),
    );
    return result.value ?? [];
  }

  // ----------------------------------------------------------- suppression

  async listSuppressions(
    scope: BrandScope,
    activeOnly = true,
  ): Promise<readonly SuppressionView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly SuppressionView[]>(marketingPaths.suppressions(scope), {
        params: { activeOnly },
      }),
    );
    return result.value ?? [];
  }

  async suppress(scope: BrandScope, request: SuppressionRequest): Promise<void> {
    await firstValueFrom(
      this.api.post<SuppressionRequest, void>(marketingPaths.suppressions(scope), command(request)),
    );
  }

  async liftSuppression(
    scope: BrandScope,
    suppressionId: string,
    reason: string,
  ): Promise<{ lifted: boolean }> {
    return firstValueFrom(
      this.api.post<{ reason: string }, { lifted: boolean }>(
        marketingPaths.suppressionLifts(scope, suppressionId),
        command({ reason }),
      ),
    );
  }
}

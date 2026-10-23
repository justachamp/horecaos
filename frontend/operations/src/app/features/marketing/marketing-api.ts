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
  /** When `launch` should arm SCHEDULED instead of sending immediately (T18). */
  readonly scheduledAt?: string | null;
}

/** Mirrors `OperationsMarketingController.ChannelResponse` — the isWired fix row 6.4 asked for (T18). */
export interface ChannelView {
  readonly channel: string;
  readonly carriesMarginalCost: boolean;
  readonly isWired: boolean;
  /**
   * Why this channel cannot carry this brand's marketing, as a stable code
   * (`SMS_PURPOSE_NOT_PERMITTED`, `NO_PROVIDER_BINDING`, `NO_DELIVERY_ADAPTER`, ...), or
   * null when it is wired (ADR 0146 Decision 8). A console shows a sentence for it, never
   * the bare code; see `channel-wiring.ts`.
   */
  readonly notWiredReason: string | null;
  /**
   * Whether this channel's provider reports anything after the hand-over for this brand: a delivery
   * receipt (ADR 0182). False means a delivered figure is not zero but unknown, and a screen says
   * so; SMS turns true when the brand's bound gateway has an enabled receipt source.
   */
  readonly reportsDelivery?: boolean;
  /** Whether it reports an open or a click. False means a read figure is unknown, not zero. */
  readonly reportsRead?: boolean;
}

export type CampaignKind = 'BROADCAST' | 'SCENARIO';

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
  /** When a launch call arms SENDING for, or null for "immediately" (T18). */
  readonly scheduledAt: string | null;
  /**
   * Row 6.4: why a scheduled send did not go out (cleared automatically by a
   * later {@link MarketingApi.reschedule}), or a pause/halt reason — null on a
   * campaign that never halted. `scheduledAt === null && status === 'SCHEDULED'
   * && haltedReason !== null` is exactly the shape the "re-schedule" affordance
   * targets: `CampaignScheduledSendScheduler` disarmed a due send whose channel
   * was still unwired, rather than retrying it forever.
   */
  readonly haltedReason: string | null;
  /** Whether `channel` has a real ADR 0020 delivery path for this brand today (T18, ADR 0146). */
  readonly isWired: boolean;
  /** Why not, as a stable code; null when wired. See {@link ChannelView.notWiredReason}. */
  readonly notWiredReason: string | null;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly version: number;
  /** `BROADCAST` (the one-off send) or `SCENARIO` (ADR 0112, per-guest steps). */
  readonly kind: CampaignKind;
  /** A scenario's withheld share, in percent; null for none (and for every broadcast). */
  readonly controlGroupPercent: number | null;
  /** The scenario version this one replaces, or null. */
  readonly supersedesCampaignId: string | null;
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
  /**
   * What the delivery path knows about the message (ADR 0146, ADR 0182): `DELIVERED`, `READ`,
   * `FAILED`, `REJECTED`, `NO_RECEIPT`, `HANDED_TO_OPERATOR`, `WITHHELD` or `PENDING`. Evidence,
   * not a promise: a gateway that accepted a message and reported nothing is
   * `HANDED_TO_OPERATOR`, which is neither delivered nor failed; `WITHHELD` is a message that was
   * never sent, and `deliveryReason` says why. Null for a recipient with no message.
   */
  readonly deliveryState: string | null;
  /** The gateway's own last word, when it sent one. */
  readonly receiptState: string | null;
  /** Segments the gateway billed, when it said. */
  readonly segmentsBilled: number | null;
  /**
   * A bounded code that says why a message was withheld, failed or rejected
   * (`TEMPLATE_AWAITING_PROVIDER`, `CONSENT_WITHHELD`, `RECEIVER_UNREACHABLE`...), never a
   * provider's text, a number or an address.
   */
  readonly deliveryReason?: string | null;
}

/**
 * What became of a campaign's messages, counted from their own evidence when asked (ADR 0182).
 * Mirrors `OperationsMarketingController.DeliveryCountsResponse`.
 */
export interface DeliveryCountsView {
  readonly total: number;
  /** Confirmed delivered by the channel; includes `read`. */
  readonly delivered: number;
  /** Delivered messages the channel also reported opened or clicked. */
  readonly read: number;
  readonly failed: number;
  readonly rejected: number;
  /** Accepted by the gateway or relay and not reported on since. */
  readonly handedToOperator: number;
  /** Accepted, a receipt was expected, and none came in the window. */
  readonly noReceipt: number;
  readonly pending: number;
  /** Never sent; `withheldByReason` says why. */
  readonly withheld: number;
  readonly withheldByReason: Readonly<Record<string, number>>;
  /** What the gateway says it billed across the campaign. */
  readonly segmentsBilled: number;
  /** When false, `delivered` is unknown for this channel and brand, not zero. */
  readonly reportsDelivery: boolean;
  /** When false, `read` is unknown for this channel, not zero. */
  readonly reportsRead: boolean;
}

/** One campaign's delivery counts, for comparing campaigns. Mirrors `OperationsMarketingController.CampaignDeliveryResponse`. */
export interface CampaignDeliveryView {
  readonly campaignId: string;
  readonly channel: string;
  readonly delivery: DeliveryCountsView;
}

/** Row 7.9b/6.4. Mirrors `OperationsMarketingController.RecipientCountsResponse`. */
export interface RecipientCountsView {
  readonly pending: number;
  readonly queued: number;
  readonly deferred: number;
  readonly refused: number;
  readonly total: number;
  /**
   * Row 6.4: `refused`'s own breakdown by `RefusalReason` name (e.g.
   * `SUPPRESSED`) — the campaign history/statistics view's "suppressed" count,
   * nameable on its own without a terminal-status projection this wave does
   * not add (see `OperationsMarketingController.recipientCounts`'s own doc:
   * "delivered" vs "failed" has no data source yet).
   */
  readonly refusedByReason: Readonly<Record<string, number>>;
  /** What became of the messages that were created (ADR 0182). */
  readonly delivery: DeliveryCountsView;
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

/** Mirrors `CourierBroadcastController.CourierBroadcastResponse` (T18, operations 6.4b). */
export interface CourierBroadcastView {
  readonly broadcastId: string;
  readonly channel: string;
  readonly targetKind: string;
  readonly targetGroupId: string | null;
  /** Free text, present only on a broadcast drafted before ADR 0182; a new one names a wording. */
  readonly message: string | null;
  /** The approved wording (an active SMS template of the class `COURIER_DISPATCH`) this broadcast sends. */
  readonly templateKey: string | null;
  /** The values the dispatcher gave the wording's variables. */
  readonly variables: Readonly<Record<string, string>>;
  readonly status: string;
  /** Couriers messaged; those with no number are in the per-courier results, not in this count. */
  readonly recipientCount: number;
  readonly refusalReason: string | null;
  readonly createdBy: string;
  readonly createdAt: string;
  readonly sentAt: string | null;
}

export interface DraftCourierBroadcastRequest {
  readonly targetKind: string;
  readonly targetGroupId?: string | null;
  readonly templateKey: string;
  readonly variables: Readonly<Record<string, string>>;
}

/** One wording a dispatcher may broadcast. Mirrors `CourierBroadcastController.TemplateResponse`. */
export interface CourierTemplateView {
  readonly templateKey: string;
  /** Each needs a value when the broadcast is drafted. */
  readonly variables: readonly string[];
  /** The wording per language tag, placeholders unexpanded. */
  readonly bodiesByLocale: Readonly<Record<string, string>>;
  /** True while the SMS gateway has not approved the wording (ADR 0091). */
  readonly awaitingGateway: boolean;
}

/**
 * Whether a courier SMS can leave for this brand and, if not, the stable code why
 * (`NO_PROVIDER_BINDING`, `SMS_PURPOSE_NOT_PERMITTED`, `COURIER_CONTACTS_NOT_WIRED`...); read before
 * drafting. Mirrors `CourierBroadcastController.ReadinessResponse`.
 */
export interface CourierReadinessView {
  readonly isWired: boolean;
  readonly reason: string | null;
  readonly templates: readonly CourierTemplateView[];
}

/**
 * What became of one courier a broadcast targeted, by their non-personal reference (K-014): the
 * message's delivery state, or `NO_CONTACT` for a courier with no number. Mirrors
 * `CourierBroadcastController.CourierResultResponse`.
 */
export interface CourierResultView {
  readonly courierReference: string;
  readonly state: string;
  readonly reason: string | null;
  readonly receiptState: string | null;
  readonly segmentsBilled: number | null;
}

/** Mirrors `AttributionLinkController.AttributionLinkResponse` (T18, ADR 0044, operations 6.6a). */
export interface AttributionLinkView {
  readonly linkId: string;
  readonly label: string;
  readonly token: string;
  readonly ownerNote: string | null;
  readonly channel: string;
  readonly destinationType: string;
  readonly destinationId: string | null;
  readonly status: string;
  readonly validFrom: string;
  readonly validUntil: string | null;
  readonly clickCount: number;
  readonly createdBy: string;
  readonly createdAt: string;
  /** The link as it is shared, built on the server (ADR 0183); null with {@link unavailable} when it cannot be built. */
  readonly url: string | null;
  /** What is missing when `url` is null: a stable code (`WEB_HOSTNAME_NOT_VERIFIED`, `TELEGRAM_MINI_APP_NOT_SET_UP`, ...). */
  readonly unavailable: string | null;
}

export interface MintAttributionLinkRequest {
  readonly label: string;
  readonly ownerNote?: string | null;
  readonly channel: string;
  readonly destinationType: string;
  readonly destinationId?: string | null;
  readonly validUntil?: string | null;
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

  /**
   * Every channel a campaign may target, and whether it can actually
   * deliver (T18). The create form disables an unwired option instead of
   * letting an operator spend a four-eyes approval on a campaign that dies
   * inside the expansion scheduler with an exception nobody sees.
   */
  async listChannels(scope: BrandScope): Promise<readonly ChannelView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly ChannelView[]>(marketingPaths.channels(scope)),
    );
    return result.value ?? [];
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

  /**
   * Row 6.4: re-arms a campaign `CampaignScheduledSendScheduler` halted for a
   * new future moment, without a fresh approval — `status` stays `SCHEDULED`
   * throughout. Refused (`RESOURCE_CONFLICT`) when the campaign is not
   * currently a halted scheduled send.
   */
  async reschedule(scope: BrandScope, campaignId: string, scheduledAt: string): Promise<void> {
    await firstValueFrom(
      this.api.post<{ scheduledAt: string }, void>(
        marketingPaths.campaignReschedules(scope, campaignId),
        command({ scheduledAt }),
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

  /** Row 7.9b: how many recipients ended each way, without paging the full list. */
  /**
   * What became of the messages of each of the brand's recent campaigns (row 7.9b, ADR 0182), in one
   * read: the list a marketer compares campaigns by.
   */
  async campaignDeliveryCounts(scope: BrandScope): Promise<readonly CampaignDeliveryView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly CampaignDeliveryView[]>(marketingPaths.campaignDeliveryCounts(scope)),
    );
    return result.value ?? [];
  }

  async recipientCounts(scope: BrandScope, campaignId: string): Promise<RecipientCountsView> {
    return (
      await firstValueFrom(
        this.api.get<RecipientCountsView>(
          marketingPaths.campaignRecipientCounts(scope, campaignId),
        ),
      )
    ).value;
  }

  /**
   * Row 6.4: the campaign detail pane's own "who received this" export —
   * every member of the campaign's own audience snapshot, as pseudonymous
   * account ids. Mirrors `SegmentsApi.exportSnapshot` exactly (the same
   * `OperationsMarketingController.export` endpoint, itself the audited
   * fact); this wave gives the campaign pane its own call site rather than
   * sending a marketer hunting for the matching snapshot id on the Segments
   * screen.
   */
  async exportSnapshot(
    scope: BrandScope,
    snapshotId: string,
    purpose: string,
  ): Promise<readonly string[]> {
    const result = await firstValueFrom(
      this.api.post<{ readonly purpose: string; readonly limit: number | null }, readonly string[]>(
        marketingPaths.audienceSnapshotExports(scope, snapshotId),
        command({ purpose, limit: null }),
      ),
    );
    return result ?? [];
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

  // ------------------------------------------------------- courier broadcasts

  async listCourierBroadcasts(scope: BrandScope): Promise<readonly CourierBroadcastView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly CourierBroadcastView[]>(marketingPaths.courierBroadcasts(scope)),
    );
    return result.value ?? [];
  }

  async draftCourierBroadcast(
    scope: BrandScope,
    request: DraftCourierBroadcastRequest,
  ): Promise<CourierBroadcastView> {
    return firstValueFrom(
      this.api.post<DraftCourierBroadcastRequest, CourierBroadcastView>(
        marketingPaths.courierBroadcasts(scope),
        command(request),
      ),
    );
  }

  async courierReadiness(scope: BrandScope): Promise<CourierReadinessView> {
    return firstValueFrom(
      this.api.get<CourierReadinessView>(marketingPaths.courierBroadcastReadiness(scope)),
    ).then((result) => result.value ?? { isWired: false, reason: null, templates: [] });
  }

  async courierBroadcastRecipients(
    scope: BrandScope,
    broadcastId: string,
  ): Promise<readonly CourierResultView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly CourierResultView[]>(
        marketingPaths.courierBroadcastRecipients(scope, broadcastId),
      ),
    );
    return result.value ?? [];
  }

  async sendCourierBroadcast(
    scope: BrandScope,
    broadcastId: string,
  ): Promise<CourierBroadcastView> {
    return firstValueFrom(
      this.api.post<null, CourierBroadcastView>(
        marketingPaths.courierBroadcastSends(scope, broadcastId),
        command(null),
      ),
    );
  }

  // -------------------------------------------------------- attribution links

  async listAttributionLinks(scope: BrandScope): Promise<readonly AttributionLinkView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly AttributionLinkView[]>(marketingPaths.attributionLinks(scope)),
    );
    return result.value ?? [];
  }

  async mintAttributionLink(
    scope: BrandScope,
    request: MintAttributionLinkRequest,
  ): Promise<AttributionLinkView> {
    return firstValueFrom(
      this.api.post<MintAttributionLinkRequest, AttributionLinkView>(
        marketingPaths.attributionLinks(scope),
        command(request),
      ),
    );
  }

  async archiveAttributionLink(scope: BrandScope, linkId: string): Promise<void> {
    await firstValueFrom(
      this.api.post<null, void>(
        marketingPaths.attributionLinkArchives(scope, linkId),
        command(null),
      ),
    );
  }
}

import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { BrandScope } from '../../../core/api/catalog-paths';
import { command } from '../../../core/api/idempotency';
import { marketingPaths } from '../../../core/api/marketing-paths';

/** Mirrors the `ck_offer_status` CHECK. A published version is never edited: a change is a new version. */
export type OfferStatus = 'DRAFT' | 'PUBLISHED' | 'SUPERSEDED' | 'RETIRED';

/**
 * Mirrors `OfferController.OfferRequest`. Deliberately nothing here states a benefit: an offer
 * names the one pricing promotion or the one loyalty accrual rule that already exists, and what it
 * is worth stays pricing's and loyalty's (ADR 0112).
 *
 * Every optional field is `null`, never absent, so Jackson 3 never meets a missing primitive.
 */
export interface OfferRequest {
  readonly displayName: string;
  readonly pricingPromotionId: string | null;
  readonly loyaltyAccrualRuleId: string | null;
  readonly validFrom: string;
  readonly validUntil: string | null;
  readonly audienceId: string | null;
  readonly allowedChannels: readonly string[];
  readonly templateKey: string;
  readonly templateVersion: number | null;
  readonly bannerImageReference: string | null;
}

/** Mirrors `OfferController.OfferResponse`. `version` is the row version, sent back as `If-Match`. */
export interface OfferView {
  readonly offerId: string;
  readonly lineageId: string;
  readonly versionNumber: number;
  readonly status: OfferStatus;
  readonly displayName: string;
  readonly pricingPromotionId: string | null;
  readonly loyaltyAccrualRuleId: string | null;
  readonly validFrom: string;
  readonly validUntil: string | null;
  readonly audienceId: string | null;
  readonly allowedChannels: readonly string[];
  readonly templateKey: string;
  readonly templateVersion: number | null;
  readonly bannerImageReference: string | null;
  readonly createdBy: string;
  readonly publishedBy: string | null;
  readonly publishedAt: string | null;
  readonly version: number;
  readonly createdAt: string;
  readonly updatedAt: string;
}

/**
 * Offers (ADR 0112): versioned references to something pricing or loyalty already owns.
 *
 * Reading needs `campaign.author`, because the scenario editor selects from offers and a marketer
 * who cannot manage them must still see them to choose; writing needs
 * `marketing.offer.manage`. The server enforces both, so a 403 on a write is shown, not hidden.
 */
@Injectable({ providedIn: 'root' })
export class OffersApi {
  private readonly api = inject(ApiClient);

  async list(scope: BrandScope): Promise<readonly OfferView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly OfferView[]>(marketingPaths.offers(scope)),
    );
    return result.value ?? [];
  }

  /** Version 1 of a new lineage, as a draft. */
  async create(scope: BrandScope, request: OfferRequest): Promise<OfferView> {
    return firstValueFrom(
      this.api.post<OfferRequest, OfferView>(marketingPaths.offers(scope), command(request)),
    );
  }

  /** Rewrites a draft in place; refused for any other status. */
  async rewriteDraft(
    scope: BrandScope,
    offerId: string,
    request: OfferRequest,
    expectedVersion: number,
  ): Promise<OfferView> {
    return firstValueFrom(
      this.api.put<OfferRequest, OfferView>(
        marketingPaths.offer(scope, offerId),
        command(request),
        {
          expectedVersion,
        },
      ),
    );
  }

  /** A new draft in the same lineage; the published version stays in force until it is published. */
  async newVersion(scope: BrandScope, offerId: string, request: OfferRequest): Promise<OfferView> {
    return firstValueFrom(
      this.api.post<OfferRequest, OfferView>(
        marketingPaths.offerVersions(scope, offerId),
        command(request),
      ),
    );
  }

  /** Puts a draft in force and supersedes the lineage's previously published version. */
  async publish(scope: BrandScope, offerId: string, expectedVersion: number): Promise<OfferView> {
    return firstValueFrom(
      this.api.post<null, OfferView>(
        marketingPaths.offerPublications(scope, offerId),
        command(null),
        {
          expectedVersion,
        },
      ),
    );
  }

  /** Every scenario that already names it stops offering it at the guest's next step. */
  async retire(
    scope: BrandScope,
    offerId: string,
    reason: string,
    expectedVersion: number,
  ): Promise<OfferView> {
    return firstValueFrom(
      this.api.post<{ readonly reason: string }, OfferView>(
        marketingPaths.offerRetirements(scope, offerId),
        command({ reason }),
        { expectedVersion },
      ),
    );
  }
}

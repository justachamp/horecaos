import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { reportsPaths } from '../../../core/api/reports-paths';
import { ProvenanceResponse } from '../../reports/reporting-api';

/** `TagCategory.java`: what a tag is about. COURIER is a topic and never a person (ADR 0159, open input 8). */
export type ReviewTagCategory = 'FOOD' | 'SERVICE' | 'COURIER' | 'DELIVERY_TIME' | 'OTHER';

export const REVIEW_TAG_CATEGORIES: readonly ReviewTagCategory[] = [
  'FOOD',
  'SERVICE',
  'COURIER',
  'DELIVERY_TIME',
  'OTHER',
];

/**
 * Mirrors `ReviewReportController.ReviewSummaryRowResponse`: one branch over the closed business days in a range.
 * `ratingSum` and `reviews` are what a total is computed from (the sum and the count are summed, then divided,
 * never the mean of means).
 */
export interface ReviewSummaryRow {
  readonly brandId: string;
  readonly locationId: string;
  readonly reviews: number;
  readonly ratingSum: number;
  /** One decimal, half up; null when the branch received no review. */
  readonly average: number | null;
  readonly withComment: number;
  /** The count at one, two, three, four and five stars. */
  readonly distribution: readonly number[];
}

/** Mirrors `ReviewReportController.TagRowResponse`. */
export interface ReviewTagRow {
  readonly brandId: string;
  readonly locationId: string;
  readonly category: ReviewTagCategory;
  readonly sentiment: 'POSITIVE' | 'NEGATIVE';
  readonly tags: number;
}

export interface ReviewSummary {
  readonly rows: readonly ReviewSummaryRow[];
  readonly tags: readonly ReviewTagRow[];
  readonly provenance: ProvenanceResponse;
}

/** Mirrors `ReviewReportController.RecoveryRowResponse`. Times are whole seconds; the rate is in basis points. */
export interface RecoveryRow {
  readonly brandId: string;
  readonly locationId: string;
  readonly opened: number;
  readonly closed: number;
  readonly firstResponseMeanSeconds: number | null;
  readonly firstResponseP90Seconds: number | null;
  readonly closeMeanSeconds: number | null;
  readonly closeP90Seconds: number | null;
  readonly breached: number;
  readonly breachRateBasisPoints: number | null;
  readonly compensations: number;
}

export interface RecoveryOutcomeRow {
  readonly brandId: string;
  readonly locationId: string;
  readonly outcomeCode: string;
  readonly cases: number;
}

/** Minor units of `currency`, as attached to the cases opened in the range. Plain amounts, not a registered metric. */
export interface RecoveryRemedyRow {
  readonly brandId: string;
  readonly locationId: string;
  readonly currency: string;
  readonly amountMinor: number;
}

export interface RecoverySummary {
  readonly rows: readonly RecoveryRow[];
  readonly outcomes: readonly RecoveryOutcomeRow[];
  readonly remedies: readonly RecoveryRemedyRow[];
  readonly provenance: ProvenanceResponse;
}

/**
 * The review quality report (ADR 0159, rows 5.4 and 5.5): `GET .../reporting/reviews/summary` and
 * `/recovery`, both `reporting.read` at the tenant. Read from the day-close facts, so they lag by up to a
 * business day, and they carry no customer, no comment and no tag wording.
 */
@Injectable({ providedIn: 'root' })
export class ReviewReportApi {
  private readonly api = inject(ApiClient);

  async summary(
    tenantId: string,
    range: { readonly from: string; readonly to: string },
    brandId?: string,
  ): Promise<ReviewSummary> {
    const result = await firstValueFrom(
      this.api.get<ReviewSummary>(reportsPaths.reviewSummary(tenantId), {
        params: { from: range.from, to: range.to, brandId },
      }),
    );
    return result.value;
  }

  async recovery(
    tenantId: string,
    range: { readonly from: string; readonly to: string },
    brandId?: string,
  ): Promise<RecoverySummary> {
    const result = await firstValueFrom(
      this.api.get<RecoverySummary>(reportsPaths.reviewRecovery(tenantId), {
        params: { from: range.from, to: range.to, brandId },
      }),
    );
    return result.value;
  }
}

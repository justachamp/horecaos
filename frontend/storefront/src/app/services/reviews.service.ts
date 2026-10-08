import { Injectable, inject } from '@angular/core';

import { ApiClient } from '../core/api/api-client';
import { APP_CONFIG } from '../core/config/app-config';
import { newIdempotencyKey } from '../core/api/idempotency';
import type { Page } from '../core/api/page';

/**
 * ADR 0071's customer reviews as this storefront consumes them, `Reviews` on
 * `StorefrontReviewController`, and (ADR 0159) the tags a customer taps with a rating, the box that
 * lets their words appear on the restaurant's page, and the restaurant's public rating.
 *
 * Rules from the OpenAPI description shape everything here:
 *
 * - **One review per order, ever.** `POST .../orders/{orderId}/review` is
 *   refused with `RESOURCE_NOT_FOUND` when the order is not the caller's own,
 *   `UNPROCESSABLE_STATE` when it has not reached `COMPLETED` yet, and
 *   `RESOURCE_CONFLICT` when it already has one. A submitted review cannot be edited or
 *   withdrawn; only the *public display of its comment* can be taken back.
 * - **The rating is a single 1-5 integer plus an optional comment**, now with up to five tags that
 *   match it. Tags and the consent box are ignored by the server while the tenant's feedback
 *   settings are off, so this client sends them only when it has been offered them.
 * - **`myReviews` is cursor-paginated** (ADR 0031) and answers newest first.
 * - **The public reads are anonymous.** They carry no bearer token: a customer's identity is
 *   neither needed nor wanted by a page everybody can read.
 */
@Injectable({ providedIn: 'root' })
export class ReviewsService {
  private readonly api = inject(ApiClient);
  private readonly config = inject(APP_CONFIG);

  private get brandPath(): string {
    return `/storefront/tenants/${this.config.tenantId}/brands/${this.config.brandId}`;
  }

  /**
   * The caller's own reviews, newest first.
   *
   * One page only: a screen deciding whether *a particular* order still needs
   * rating reads a handful of the most recent reviews, not a full history.
   */
  async myReviews(limit = 20): Promise<Page<ReviewResponse>> {
    return this.api.list<ReviewResponse>(`${this.brandPath}/reviews`, { limit });
  }

  /**
   * Rates a completed order, once.
   *
   * @param comment sent only when non-empty -- an empty string is not the same
   *        request as "no comment" to a server that stores whatever it is given.
   * @param options the tags tapped and whether the box was ticked; each is left out of the
   *        request when empty or unticked, which is also what the server defaults to.
   */
  async submit(
    orderId: string,
    rating: number,
    comment?: string,
    options: SubmitOptions = {},
  ): Promise<ReviewResponse> {
    const trimmed = comment?.trim();
    const tagIds = options.tagIds ?? [];
    return this.api.mutate<ReviewResponse>('POST', `${this.brandPath}/orders/${orderId}/review`, {
      body: {
        rating,
        comment: trimmed ? trimmed : undefined,
        tagIds: tagIds.length > 0 ? [...tagIds] : undefined,
        publicConsent: options.publicConsent ? true : undefined,
      },
      idempotencyKey: newIdempotencyKey(),
    });
  }

  /** The tags a customer may tap and whether the public-display box means anything, or `enabled: false`. */
  async feedbackConfig(): Promise<FeedbackConfig> {
    return this.api.get<FeedbackConfig>(`${this.brandPath}/feedback/config`, { anonymous: true });
  }

  /** Completed orders the caller has not rated, newest first, inside the brand's prompt window. Empty while feedback settings are off. */
  async pending(): Promise<readonly PendingReview[]> {
    return this.api.get<readonly PendingReview[]>(`${this.brandPath}/reviews/pending`);
  }

  /** Takes a comment off the restaurant's public page. The review, its rating and the staff's view of it stay. */
  async withdrawPublicDisplay(reviewId: string): Promise<PublicDisplay> {
    return this.api.mutate<PublicDisplay>(
      'POST',
      `${this.brandPath}/reviews/${reviewId}/public-display/withdrawal`,
      { idempotencyKey: newIdempotencyKey() },
    );
  }

  /** The brand's public rating, or `ready: false` with nothing else while it shows none. */
  async publicSummary(): Promise<PublicSummary> {
    return this.api.get<PublicSummary>(`${this.brandPath}/reviews/public/summary`, {
      anonymous: true,
    });
  }

  /** Published comments, newest first. The cursor is opaque. */
  async publicComments(cursor?: string | null, limit = 10): Promise<PublicCommentPage> {
    return this.api.get<PublicCommentPage>(`${this.brandPath}/reviews/public`, {
      anonymous: true,
      query: { cursor: cursor ?? undefined, limit },
    });
  }
}

export interface SubmitOptions {
  readonly tagIds?: readonly string[];
  readonly publicConsent?: boolean;
}

/** `PublicDisplayResponse`: where a comment stands for the public page, shown to the customer who wrote it. */
export interface PublicDisplay {
  readonly state: 'NOT_SHARED' | 'PENDING' | 'PUBLISHED' | 'HIDDEN';
  readonly reasonCode: string | null;
  readonly withdrawable: boolean;
}

/** A tag, with its wording in every locale. */
export interface ReviewTag {
  readonly id: string;
  readonly code: string;
  readonly category: string;
  readonly sentiment: 'POSITIVE' | 'NEGATIVE';
  readonly labels: Readonly<Record<string, string>>;
  readonly iconAssetId?: string | null;
}

/** `StorefrontReviewController.ReviewResponse`, transcribed from the OpenAPI schema. */
export interface ReviewResponse {
  readonly id: string;
  readonly orderId: string;
  readonly rating: number;
  readonly comment: string | null;
  readonly submittedAt: string;
  /** Absent from a response that predates ADR 0159; read as none. */
  readonly tags?: readonly ReviewTag[];
  readonly publicDisplay?: PublicDisplay;
}

export interface OfferedTag {
  readonly id: string;
  readonly code: string;
  readonly category: string;
  readonly labels: Readonly<Record<string, string>>;
  readonly iconUrl: string | null;
}

/** `StorefrontPublicReviewController.FeedbackConfigResponse`. */
export interface FeedbackConfig {
  readonly enabled: boolean;
  readonly maxTagsPerReview: number;
  readonly publicComments: boolean;
  readonly consentTextKey: string | null;
  readonly positive: readonly OfferedTag[];
  readonly negative: readonly OfferedTag[];
}

/** `StorefrontReviewController.PendingReviewResponse`. */
export interface PendingReview {
  readonly orderId: string;
  readonly publicOrderNumber: string;
  readonly locationId: string;
  readonly placedAt: string;
}

/** `PublicReviewService.PublicSummary`: the five-bar distribution's first index is one star. */
export interface PublicSummary {
  readonly visibility: 'NONE' | 'RATING_ONLY' | 'RATING_AND_COMMENTS';
  readonly ready: boolean;
  readonly count: number | null;
  readonly average: number | null;
  readonly distribution: readonly number[] | null;
}

export interface PublicTag {
  readonly code: string;
  readonly category: string;
  readonly sentiment: 'POSITIVE' | 'NEGATIVE';
  readonly labels: Readonly<Record<string, string>>;
  readonly iconUrl: string | null;
}

/** One published comment: a rating, words, tags, a day and a branch, and nothing that names anyone. */
export interface PublicComment {
  readonly rating: number;
  readonly comment: string;
  readonly tags: readonly PublicTag[];
  readonly date: string;
  readonly branchName: string | null;
}

export interface PublicCommentPage {
  readonly visibility: 'NONE' | 'RATING_ONLY' | 'RATING_AND_COMMENTS';
  readonly items: readonly PublicComment[];
  readonly nextCursor: string | null;
}

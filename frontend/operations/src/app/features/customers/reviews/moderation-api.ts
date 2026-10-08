import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { BrandScope } from '../../../core/api/catalog-paths';
import { command } from '../../../core/api/idempotency';
import { CursorState } from '../../../core/api/page';
import { reviewPaths } from '../../../core/api/reviews-paths';

/** `CommentState.java`: where a review's comment stands for the public page. */
export type CommentState = 'NOT_SHARED' | 'PENDING' | 'PUBLISHED' | 'HIDDEN';

/** The three states the queue lists; a comment never shared is not in it. */
export type QueueState = Exclude<CommentState, 'NOT_SHARED'>;

export const QUEUE_STATES: readonly QueueState[] = ['PENDING', 'PUBLISHED', 'HIDDEN'];

/**
 * The five reasons a moderator may give for hiding a comment (`ModerationReason.java`). "Negative",
 * "unfair" and "I disagree" are not among them, and the form offers no free text (ADR 0159, Decision 7).
 */
export type ModerationReason =
  | 'ABUSIVE_LANGUAGE'
  | 'PERSONAL_DATA_OF_ANOTHER'
  | 'NAMES_AN_INDIVIDUAL'
  | 'SPAM_OR_UNRELATED'
  | 'LEGAL_REQUEST';

export const MODERATION_REASONS: readonly ModerationReason[] = [
  'ABUSIVE_LANGUAGE',
  'PERSONAL_DATA_OF_ANOTHER',
  'NAMES_AN_INDIVIDUAL',
  'SPAM_OR_UNRELATED',
  'LEGAL_REQUEST',
];

export interface QueueTag {
  readonly id: string;
  readonly code: string;
  readonly category: string;
  readonly sentiment: 'POSITIVE' | 'NEGATIVE';
  readonly labels: Readonly<Record<string, string>>;
}

/** Mirrors `OperationsReviewModerationController.QueueEntry`. */
export interface QueueEntry {
  readonly reviewId: string;
  readonly locationId: string;
  readonly rating: number;
  readonly comment: string | null;
  readonly submittedAt: string;
  readonly state: CommentState;
  readonly reasonCode: string | null;
  readonly version: number;
  readonly updatedAt: string;
  readonly tags: readonly QueueTag[];
}

export interface QueuePage {
  readonly items: readonly QueueEntry[];
  readonly nextCursor: string | null;
  readonly counts: Readonly<Partial<Record<CommentState, number>>>;
}

/** Mirrors `OperationsReviewModerationController.DecisionResponse`: a state, a code and a version, never the words. */
export interface Decision {
  readonly reviewId: string;
  readonly state: CommentState;
  readonly reasonCode: string | null;
  readonly version: number;
}

/**
 * Moderation of the comments customers agreed to show publicly (ADR 0159, Decision 7). A decision
 * moves a comment between publication states and nothing else: never a rating, never the average.
 */
@Injectable({ providedIn: 'root' })
export class ModerationApi {
  private readonly api = inject(ApiClient);

  async queue(scope: BrandScope, state: QueueState, page: CursorState): Promise<QueuePage> {
    const result = await firstValueFrom(
      this.api.get<QueuePage>(reviewPaths.moderationQueue(scope), {
        params: {
          state,
          limit: page.limit,
          cursor: page.cursor ?? undefined,
        },
      }),
    );
    return result.value;
  }

  publish(scope: BrandScope, reviewId: string, expectedVersion: number): Promise<Decision> {
    return firstValueFrom(
      this.api.post<{ readonly to: 'PUBLISHED' }, Decision>(
        reviewPaths.moderationDecision(scope, reviewId),
        command({ to: 'PUBLISHED' }),
        { expectedVersion },
      ),
    );
  }

  hide(
    scope: BrandScope,
    reviewId: string,
    reason: ModerationReason,
    expectedVersion: number,
  ): Promise<Decision> {
    return firstValueFrom(
      this.api.post<{ readonly to: 'HIDDEN'; readonly reasonCode: ModerationReason }, Decision>(
        reviewPaths.moderationDecision(scope, reviewId),
        command({ to: 'HIDDEN', reasonCode: reason }),
        { expectedVersion },
      ),
    );
  }
}

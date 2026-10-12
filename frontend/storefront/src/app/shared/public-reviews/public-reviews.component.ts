import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { LangService } from '../../services/lang.service';
import {
  type PublicComment,
  type PublicSummary,
  type PublicTag,
  ReviewsService,
} from '../../services/reviews.service';
import { tagLabel } from '../../utils/tag-label';
import { TranslatePipe } from '../translate/translate.pipe';

const PAGE_SIZE = 10;

/**
 * What the restaurant lets everybody see of its reviews (ADR 0159, Decision 5): its average rating, how
 * the ratings split across the five stars, and -- when it has chosen to -- the comments its customers
 * agreed to show and a moderator approved.
 *
 * <p>The most public surface of the reviews feature, built to say nothing it was not told. Until the
 * platform reports a summary as ready the whole block renders nothing: no heading over an empty frame,
 * no "no reviews yet". A comment carries a rating, words, tags, a day and a branch; the platform sends
 * no name, order number or time of day, so there is none to render. A failure to load hides the block
 * and not the page it sits on.
 */
@Component({
  selector: 'app-public-reviews',
  standalone: true,
  imports: [TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './public-reviews.component.html',
})
export class PublicReviewsComponent {
  private readonly reviews = inject(ReviewsService);
  private readonly lang = inject(LangService);

  protected readonly summary = signal<PublicSummary | null>(null);
  protected readonly comments = signal<readonly PublicComment[]>([]);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly loadingMore = signal(false);

  /** Five rows, five stars first, each with its share of all ratings as a percentage. */
  protected readonly bars = computed(() => {
    const current = this.summary();
    const distribution = current?.distribution;
    const total = current?.count ?? 0;
    if (!current?.ready || !distribution || total <= 0) {
      return [];
    }
    return [5, 4, 3, 2, 1].map((stars) => {
      const count = distribution[stars - 1] ?? 0;
      return { stars, count, percent: Math.round((count / total) * 100) };
    });
  });

  protected readonly average = computed(() => {
    const value = this.summary()?.average;
    return value === null || value === undefined ? null : value.toFixed(1);
  });

  protected readonly showsComments = computed(
    () => this.summary()?.visibility === 'RATING_AND_COMMENTS',
  );

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    try {
      const summary = await this.reviews.publicSummary();
      this.summary.set(summary);
      if (summary.ready && summary.visibility === 'RATING_AND_COMMENTS') {
        await this.loadComments(null);
      }
    } catch {
      this.summary.set(null);
    }
  }

  protected async loadMore(): Promise<void> {
    if (this.nextCursor() === null || this.loadingMore()) {
      return;
    }
    this.loadingMore.set(true);
    try {
      await this.loadComments(this.nextCursor());
    } finally {
      this.loadingMore.set(false);
    }
  }

  private async loadComments(cursor: string | null): Promise<void> {
    try {
      const page = await this.reviews.publicComments(cursor, PAGE_SIZE);
      this.comments.update((current) =>
        cursor === null ? page.items : [...current, ...page.items],
      );
      this.nextCursor.set(page.nextCursor);
    } catch {
      // The rating stands without the comments: a failed page of comments is not a reason to hide it.
    }
  }

  protected stars(rating: number): readonly number[] {
    return Array.from({ length: rating }, (_, index) => index);
  }

  protected wording(tag: PublicTag): string {
    return tagLabel(tag, this.lang.langId());
  }

  /** The day a comment was written, in the visitor's language; the platform sends a date and never a time. */
  protected day(isoDate: string): string {
    const parsed = new Date(`${isoDate}T00:00:00`);
    if (Number.isNaN(parsed.getTime())) {
      return isoDate;
    }
    return new Intl.DateTimeFormat(platformLocale(this.lang.langId()), {
      dateStyle: 'medium',
    }).format(parsed);
  }
}

function platformLocale(langId: string): string {
  return langId === 'uz' ? 'uz-Latn' : langId;
}

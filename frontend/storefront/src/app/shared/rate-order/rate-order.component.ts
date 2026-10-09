import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';

import { HorecaOSApiError, messageKeyFor } from '../../core/api/problem-details';
import { LangService } from '../../services/lang.service';
import {
  type FeedbackConfig,
  type OfferedTag,
  type PublicDisplay,
  type ReviewResponse,
  ReviewsService,
} from '../../services/reviews.service';
import { tagLabel } from '../../utils/tag-label';
import { TranslatePipe } from '../translate/translate.pipe';

/** The reasons a comment can be off the page that this client has a sentence for. */
const HIDDEN_REASON_CODES: ReadonlySet<string> = new Set([
  'ABUSIVE_LANGUAGE',
  'PERSONAL_DATA_OF_ANOTHER',
  'NAMES_AN_INDIVIDUAL',
  'SPAM_OR_UNRELATED',
  'LEGAL_REQUEST',
  'CUSTOMER_WITHDREW',
  'CUSTOMER_ERASED',
]);

type Phase = 'loading' | 'hidden' | 'form' | 'reviewed';

/**
 * Rating one finished order from its detail screen (ADR 0071, ADR 0159): the stars, the tags that fit
 * them, an optional comment, and the box that lets the words appear on the restaurant's page without the
 * customer's name -- and, once the order has a review, where that review's comment stands for the public
 * page and a way to take it off.
 *
 * <p>This storefront had no review surface before ADR 0159, so the component decides nothing by guessing:
 * it shows the form only when the platform lists the order among those still awaiting a review (which it
 * does only while the restaurant's feedback settings are on, inside the restaurant's prompt window), shows
 * the review when the customer already has one for this order, and shows nothing otherwise, including when
 * any of the reads fails. The box starts unticked and is sent only with words to show.
 */
@Component({
  selector: 'app-rate-order',
  standalone: true,
  imports: [TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './rate-order.component.html',
})
export class RateOrderComponent {
  private readonly reviews = inject(ReviewsService);
  private readonly lang = inject(LangService);

  readonly orderId = input.required<string>();
  /** The number the customer knows the order by, for the sentence above the stars. */
  readonly orderNumber = input<string | number>('');

  protected readonly phase = signal<Phase>('loading');
  protected readonly feedback = signal<FeedbackConfig | null>(null);
  protected readonly review = signal<ReviewResponse | null>(null);

  protected readonly rating = signal(0);
  protected readonly comment = signal('');
  protected readonly selectedTagIds = signal<readonly string[]>([]);
  protected readonly publicConsent = signal(false);
  protected readonly submitting = signal(false);
  protected readonly withdrawing = signal(false);
  protected readonly errorKey = signal<string | null>(null);

  protected readonly values = [1, 2, 3, 4, 5] as const;

  protected readonly offeredTags = computed<readonly OfferedTag[]>(() => {
    const config = this.feedback();
    const rating = this.rating();
    if (!config?.enabled || rating < 1) {
      return [];
    }
    if (rating >= 4) {
      return config.positive;
    }
    return rating <= 2 ? config.negative : [...config.negative, ...config.positive];
  });

  protected readonly maxTags = computed(() => this.feedback()?.maxTagsPerReview ?? 5);

  /** The box means something only when the restaurant shows comments and the customer wrote one. */
  protected readonly showConsent = computed(
    () => this.feedback()?.publicComments === true && this.comment().trim() !== '',
  );

  protected readonly stateKey = computed(() => displayStateKey(this.review()?.publicDisplay));
  protected readonly reasonKey = computed(() => displayReasonKey(this.review()?.publicDisplay));

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    try {
      const config = await this.reviews.feedbackConfig();
      this.feedback.set(config);
      if (!config.enabled) {
        this.phase.set('hidden');
        return;
      }
      const [pending, mine] = await Promise.all([
        this.reviews.pending(),
        this.reviews.myReviews(20),
      ]);
      const existing = mine.items.find((item) => item.orderId === this.orderId());
      if (existing) {
        this.review.set(existing);
        this.phase.set('reviewed');
      } else {
        this.phase.set(pending.some((item) => item.orderId === this.orderId()) ? 'form' : 'hidden');
      }
    } catch {
      // Best-effort: a screen about an order is not failed by an optional rating prompt.
      this.phase.set('hidden');
    }
  }

  protected setRating(value: number): void {
    this.rating.set(value);
    // A tag that does not fit the new rating is dropped, so a 1-star review never carries a praise tag.
    const fitting = new Set(this.offeredTags().map((tag) => tag.id));
    this.selectedTagIds.update((ids) => ids.filter((id) => fitting.has(id)));
  }

  protected isSelected(tag: OfferedTag): boolean {
    return this.selectedTagIds().includes(tag.id);
  }

  protected toggleTag(tag: OfferedTag): void {
    this.selectedTagIds.update((ids) => {
      if (ids.includes(tag.id)) {
        return ids.filter((id) => id !== tag.id);
      }
      return ids.length >= this.maxTags() ? ids : [...ids, tag.id];
    });
  }

  protected wording(tag: {
    readonly code: string;
    readonly labels: Readonly<Record<string, string>>;
  }): string {
    return tagLabel(tag, this.lang.langId());
  }

  protected stars(rating: number): readonly number[] {
    return Array.from({ length: rating }, (_, index) => index);
  }

  protected async submit(): Promise<void> {
    if (this.rating() < 1 || this.submitting()) {
      return;
    }
    this.submitting.set(true);
    this.errorKey.set(null);
    try {
      const tagIds = this.offeredTags().length > 0 ? this.selectedTagIds() : [];
      const consent = this.showConsent() && this.publicConsent();
      const submitted =
        tagIds.length > 0 || consent
          ? await this.reviews.submit(this.orderId(), this.rating(), this.comment(), {
              tagIds,
              publicConsent: consent,
            })
          : await this.reviews.submit(this.orderId(), this.rating(), this.comment());
      this.review.set(submitted);
      this.phase.set('reviewed');
    } catch (failure) {
      this.errorKey.set(
        failure instanceof HorecaOSApiError ? messageKeyFor(failure) : 'errors.generic',
      );
    } finally {
      this.submitting.set(false);
    }
  }

  protected async withdraw(): Promise<void> {
    const current = this.review();
    if (current === null || this.withdrawing()) {
      return;
    }
    this.withdrawing.set(true);
    this.errorKey.set(null);
    try {
      const display = await this.reviews.withdrawPublicDisplay(current.id);
      this.review.set({ ...current, publicDisplay: display });
    } catch (failure) {
      this.errorKey.set(
        failure instanceof HorecaOSApiError ? messageKeyFor(failure) : 'errors.generic',
      );
    } finally {
      this.withdrawing.set(false);
    }
  }
}

function displayStateKey(display: PublicDisplay | undefined): string | null {
  switch (display?.state) {
    case 'PENDING':
      return 'rateOrder.publicState.PENDING';
    case 'PUBLISHED':
      return 'rateOrder.publicState.PUBLISHED';
    case 'HIDDEN':
      return 'rateOrder.publicState.HIDDEN';
    default:
      return null;
  }
}

function displayReasonKey(display: PublicDisplay | undefined): string | null {
  const code = display?.state === 'HIDDEN' ? display.reasonCode : null;
  return code && HIDDEN_REASON_CODES.has(code) ? `rateOrder.hiddenReason.${code}` : null;
}

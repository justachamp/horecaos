import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';

import { HorecaOSApiError, messageKeyFor } from '../../../core/api/problem-details';
import { IconComponent } from '../../../shared/icon/icon.component';
import { LangService } from '../../../services/lang.service';
import {
  type PublicDisplay,
  type ReviewResponse,
  ReviewsService,
} from '../../../services/reviews.service';
import { TranslatePipe } from '../../../shared/translate/translate.pipe';
import { tagLabel } from '../../../utils/tag-label';

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

/**
 * The customer's own reviews and where each comment stands for the restaurant's public page
 * (ADR 0159, Decisions 6 and 7): waiting, on the page, or off it and why, in the customer's words.
 * A comment can be taken off the page at any time; that is all it can do. The rating, the comment the
 * restaurant's own staff read and any case about it are not the customer's to take back, and the
 * button says what it takes off.
 *
 * <p>The section shows nothing at all for a customer with no review, and reads its list again
 * whenever {@link refresh} changes, which the profile bumps after a rating is submitted.
 */
@Component({
  selector: 'app-my-reviews',
  standalone: true,
  imports: [IconComponent, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './my-reviews.component.html',
  styleUrl: './my-reviews.component.scss',
})
export class MyReviewsComponent {
  private readonly reviews = inject(ReviewsService);
  private readonly lang = inject(LangService);

  /** A counter the page bumps to ask for a fresh read. */
  readonly refresh = input(0);

  protected readonly list = signal<readonly ReviewResponse[]>([]);
  protected readonly withdrawing = signal<string | null>(null);
  protected readonly errorKey = signal<string | null>(null);

  constructor() {
    effect(() => {
      this.refresh();
      untracked(() => void this.load());
    });
  }

  private async load(): Promise<void> {
    try {
      this.list.set((await this.reviews.myReviews(10)).items);
    } catch {
      this.list.set([]);
    }
  }

  protected stars(rating: number): readonly number[] {
    return Array.from({ length: rating }, (_, index) => index);
  }

  protected wording(tag: {
    readonly code: string;
    readonly labels: Readonly<Record<string, string>>;
  }): string {
    return tagLabel(tag, this.lang.langId());
  }

  /** The translation key for where a comment stands, or null for one that was never shared. */
  protected stateKey(display: PublicDisplay | undefined): string | null {
    switch (display?.state) {
      case 'PENDING':
        return 'profile.publicState.PENDING';
      case 'PUBLISHED':
        return 'profile.publicState.PUBLISHED';
      case 'HIDDEN':
        return 'profile.publicState.HIDDEN';
      default:
        return null;
    }
  }

  /** Why a comment is off the page, in the customer's words; null for a code this client has no sentence for. */
  protected reasonKey(display: PublicDisplay | undefined): string | null {
    const code = display?.state === 'HIDDEN' ? display.reasonCode : null;
    return code && HIDDEN_REASON_CODES.has(code) ? `profile.hiddenReason.${code}` : null;
  }

  protected async withdraw(review: ReviewResponse): Promise<void> {
    if (this.withdrawing() !== null) {
      return;
    }
    this.withdrawing.set(review.id);
    this.errorKey.set(null);
    try {
      const display = await this.reviews.withdrawPublicDisplay(review.id);
      this.list.update((current) =>
        current.map((item) => (item.id === review.id ? { ...item, publicDisplay: display } : item)),
      );
    } catch (failure) {
      this.errorKey.set(
        failure instanceof HorecaOSApiError ? messageKeyFor(failure) : 'errors.generic',
      );
    } finally {
      this.withdrawing.set(null);
    }
  }
}

import {
  ChangeDetectionStrategy,
  Component,
  type OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { CustomerOtp } from '../../core/session/customer-otp';
import { CustomerProfileService } from '../../services/customer-profile.service';
import { HorecaOSApiError, messageKeyFor } from '../../core/api/problem-details';
import { ConsentBoxComponent } from '../../shared/consent-box/consent-box.component';
import { IconComponent } from '../../shared/icon/icon.component';
import { LangService } from '../../services/lang.service';
import { OrdersService } from '../../services/orders.service';
import {
  type FeedbackConfig,
  type OfferedTag,
  type PublicDisplay,
  ReviewsService,
} from '../../services/reviews.service';
import { MyReviewsComponent } from './my-reviews/my-reviews.component';
import { TagPickerComponent } from '../../shared/tag-picker/tag-picker.component';
import { TranslatePipe } from '../../shared/translate/translate.pipe';
import { TranslateService } from '../../services/translate.service';

type LoadState = 'loading' | 'ready' | 'error';

/**
 * Profil: the customer's name, the language switch, a link to their referral
 * code (ADR 0067), sign-out, and rating an order that has been completed and
 * never rated.
 *
 * <h2>What the design shows that this screen does not build</h2>
 *
 * **The customer's phone number.** `CustomerApi`/`GET /me` reports contact
 * points by kind and verification state and *never by value* (ADR 0029) --
 * see its own class comment. There is no call this client can make that gets
 * the digits back, so the identity block shows the name only.
 *
 * **The rest of the settings list** (addresses, payment methods, an
 * order-history shortcut, notifications, support). None of these were in
 * this wave's scope and none has a screen behind it yet; showing the row
 * without one would be exactly the "control that does nothing" this
 * storefront exists to avoid -- which is why `openReferral` below links to a
 * real screen (`ReferralComponent`) rather than joining this list.
 *
 * **The three-way food/service/delivery star breakdown.** `SubmitReviewRequest`
 * carries one `rating` (1-5) and one `comment` -- there is no per-category
 * field on the wire to split a rating into. This screen asks for one rating,
 * honestly matching what `POST .../orders/{orderId}/review` actually accepts.
 *
 * <h2>Where the rating prompt comes from</h2>
 *
 * When the restaurant has switched its feedback settings on (ADR 0159), the platform names the
 * orders still awaiting a review itself (`GET .../reviews/pending`, inside the restaurant's own
 * prompt window), and offers the tags a customer may tap with a rating and whether the restaurant
 * shows comments publicly. Otherwise -- the settings are off, or the config could not be read --
 * this falls back to what it always did: it reads a handful of the customer's most recent orders,
 * keeps the newest `COMPLETED` one, and cross-checks it against `ReviewsService.myReviews` to skip
 * anything already rated. Best-effort either way, like the delivery-fee preview elsewhere in this
 * app: a failure here hides the prompt rather than failing the whole profile screen over it.
 *
 * <h2>Tags, the box and the customer's own reviews</h2>
 *
 * The tags offered follow the rating (praise for 4 and 5, complaints for 1 and 2, both for 3), at
 * most as many as the platform allows. The box that lets the words of a review appear on the
 * restaurant's page, without the customer's name, is shown only when the restaurant shows comments
 * and there are words to show, and it is never ticked for the customer. Below the prompt the
 * customer sees where each of their own comments stands -- waiting, on the page, or off it and why
 * -- and can take one off the page at any time; that changes nothing about the rating the
 * restaurant keeps.
 */
@Component({
  selector: 'app-profile',
  standalone: true,
  imports: [
    ConsentBoxComponent,
    IconComponent,
    MyReviewsComponent,
    TagPickerComponent,
    TranslatePipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './profile.component.html',
  styleUrl: './profile.component.scss',
})
export class ProfileComponent implements OnInit {
  private readonly profileService = inject(CustomerProfileService);
  protected readonly lang = inject(LangService);
  private readonly translate = inject(TranslateService);
  private readonly customerOtp = inject(CustomerOtp);
  private readonly orders = inject(OrdersService);
  private readonly reviews = inject(ReviewsService);
  private readonly router = inject(Router);

  protected readonly state = signal<LoadState>('loading');
  /** The languages this brand serves that the platform has live and this build can draw (ADR 0149). */
  protected readonly languages = this.lang.offered;

  protected readonly editing = signal(false);
  protected readonly draftFirstName = signal('');
  protected readonly draftLastName = signal('');
  protected readonly savingName = signal(false);

  protected readonly ratableOrder = signal<RatableOrder | null>(null);
  protected readonly feedback = signal<FeedbackConfig | null>(null);
  protected readonly selectedTagIds = signal<readonly string[]>([]);
  protected readonly publicConsent = signal(false);
  protected readonly submittedDisplay = signal<PublicDisplay | null>(null);
  /** Bumped after a rating is submitted so the customer's own reviews are read again. */
  protected readonly reviewsRefresh = signal(0);
  protected readonly rating = signal(0);
  protected readonly ratingComment = signal('');
  protected readonly submittingRating = signal(false);
  protected readonly ratingSubmitted = signal(false);
  protected readonly ratingErrorKey = signal<string | null>(null);

  protected readonly displayName = computed(
    () => this.profileService.profile()?.displayName?.trim() || null,
  );
  protected readonly initial = computed(() => (this.displayName() ?? '?').charAt(0).toUpperCase());

  /** The tags that fit the rating picked so far: praise for 4 and 5, complaints for 1 and 2, both for 3. */
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

  /** The box means something only when the restaurant shows comments and the customer wrote one. */
  protected readonly showConsent = computed(
    () => this.feedback()?.publicComments === true && this.ratingComment().trim() !== '',
  );

  protected readonly maxTags = computed(() => this.feedback()?.maxTagsPerReview ?? 5);

  async ngOnInit(): Promise<void> {
    this.state.set('loading');
    try {
      await this.profileService.load();
      this.state.set('ready');
    } catch {
      this.state.set('error');
      return;
    }
    void this.loadRatablePrompt();
  }

  /** Best-effort: a failure here hides the prompt, never breaks the screen. */
  private async loadRatablePrompt(): Promise<void> {
    const config = await this.readFeedbackConfig();
    this.feedback.set(config);
    try {
      if (config?.enabled) {
        const pending = await this.reviews.pending();
        const next = pending[0];
        this.ratableOrder.set(
          next ? { id: next.orderId, label: next.publicOrderNumber || next.orderId } : null,
        );
      } else {
        const [recentOrders, reviewPage] = await Promise.all([
          firstValueFrom(this.orders.getOrders([], 10)),
          this.reviews.myReviews(),
        ]);
        const reviewed = new Set(reviewPage.items.map((review) => review.orderId));
        const candidate = recentOrders.find(
          (order) => order.status?.id === 'COMPLETED' && !reviewed.has(String(order.id)),
        );
        this.ratableOrder.set(
          candidate
            ? { id: String(candidate.id), label: String(candidate.order_number ?? candidate.id) }
            : null,
        );
      }
    } catch {
      this.ratableOrder.set(null);
    }
  }

  /** The config is optional: failing to read it is the same as the restaurant not having it on. */
  private async readFeedbackConfig(): Promise<FeedbackConfig | null> {
    try {
      return await this.reviews.feedbackConfig();
    } catch {
      return null;
    }
  }

  protected setLang(id: string): void {
    this.translate.setLang(id);
  }

  protected async openReferral(): Promise<void> {
    await this.router.navigate(['/referral']);
  }

  protected startEdit(): void {
    this.draftFirstName.set(this.profileService.firstName());
    this.draftLastName.set(this.profileService.lastName());
    this.editing.set(true);
  }

  protected cancelEdit(): void {
    this.editing.set(false);
  }

  protected async saveName(): Promise<void> {
    this.savingName.set(true);
    try {
      await this.profileService.update({
        firstName: this.draftFirstName(),
        lastName: this.draftLastName(),
      });
      this.editing.set(false);
    } catch {
      // The form stays open with what the customer typed; nothing here
      // pretends the save happened.
    } finally {
      this.savingName.set(false);
    }
  }

  protected async signOut(): Promise<void> {
    await this.customerOtp.signOut();
    await this.router.navigate(['/home']);
  }

  protected setRating(value: number): void {
    this.rating.set(value);
    // A tag that does not fit the new rating is dropped, so a 1-star review never carries a praise tag.
    const fitting = new Set(this.offeredTags().map((tag) => tag.id));
    this.selectedTagIds.update((ids) => ids.filter((id) => fitting.has(id)));
  }

  protected toggleTag(tag: OfferedTag): void {
    this.selectedTagIds.update((ids) => {
      if (ids.includes(tag.id)) {
        return ids.filter((id) => id !== tag.id);
      }
      return ids.length >= this.maxTags() ? ids : [...ids, tag.id];
    });
  }

  protected async submitRating(): Promise<void> {
    const order = this.ratableOrder();
    if (!order || this.rating() < 1) {
      return;
    }
    this.submittingRating.set(true);
    this.ratingErrorKey.set(null);
    try {
      const tagIds = this.offeredTags().length > 0 ? this.selectedTagIds() : [];
      const consent = this.showConsent() && this.publicConsent();
      const submitted =
        tagIds.length > 0 || consent
          ? await this.reviews.submit(order.id, this.rating(), this.ratingComment(), {
              tagIds,
              publicConsent: consent,
            })
          : await this.reviews.submit(order.id, this.rating(), this.ratingComment());
      this.submittedDisplay.set(submitted.publicDisplay ?? null);
      this.ratingSubmitted.set(true);
      this.reviewsRefresh.update((count) => count + 1);
    } catch (failure) {
      this.ratingErrorKey.set(
        failure instanceof HorecaOSApiError ? messageKeyFor(failure) : 'errors.generic',
      );
    } finally {
      this.submittingRating.set(false);
    }
  }
}

/** The order the prompt is about: its id for the request and the number the customer knows it by. */
interface RatableOrder {
  readonly id: string;
  readonly label: string;
}

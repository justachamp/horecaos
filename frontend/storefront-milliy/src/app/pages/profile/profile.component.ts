import { ChangeDetectionStrategy, Component, type OnInit, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { CustomerOtp } from '../../core/session/customer-otp';
import { CustomerProfileService } from '../../services/customer-profile.service';
import { HorecaOSApiError, messageKeyFor } from '../../core/api/problem-details';
import { IconComponent } from '../../shared/icon/icon.component';
import { LANG_LABELS, LangService } from '../../services/lang.service';
import { type ApiOrder, OrdersService } from '../../services/orders.service';
import { ReviewsService } from '../../services/reviews.service';
import { TranslatePipe } from '../../shared/translate/translate.pipe';
import { TranslateService } from '../../services/translate.service';

type LoadState = 'loading' | 'ready' | 'error';

/**
 * Profil: the customer's name, the language switch, sign-out, and rating an
 * order that has been completed and never rated.
 *
 * <h2>What the design shows that this screen does not build</h2>
 *
 * **The customer's phone number.** `CustomerApi`/`GET /me` reports contact
 * points by kind and verification state and *never by value* (ADR 0029) --
 * see its own class comment. There is no call this client can make that gets
 * the digits back, so the identity block shows the name only.
 *
 * **The settings list** (addresses, payment methods, an order-history
 * shortcut, notifications, support). None of these were in this wave's scope
 * and none has a screen behind it yet; showing the row without one would be
 * exactly the "control that does nothing" this storefront exists to avoid.
 *
 * **The three-way food/service/delivery star breakdown.** `SubmitReviewRequest`
 * carries one `rating` (1-5) and one `comment` -- there is no per-category
 * field on the wire to split a rating into. This screen asks for one rating,
 * honestly matching what `POST .../orders/{orderId}/review` actually accepts.
 *
 * <h2>Where the rating prompt comes from</h2>
 *
 * There is no endpoint that names "the order still awaiting a review" --
 * this reads a handful of the customer's most recent orders, keeps the
 * newest `COMPLETED` one, and cross-checks it against `ReviewsService.myReviews`
 * to skip anything already rated. Best-effort, like the delivery-fee preview
 * elsewhere in this app: a failure here hides the prompt rather than failing
 * the whole profile screen over it.
 */
@Component({
  selector: 'app-profile',
  standalone: true,
  imports: [IconComponent, TranslatePipe],
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
  protected readonly languages = Object.keys(LANG_LABELS);

  protected readonly editing = signal(false);
  protected readonly draftFirstName = signal('');
  protected readonly draftLastName = signal('');
  protected readonly savingName = signal(false);

  protected readonly ratableOrder = signal<ApiOrder | null>(null);
  protected readonly rating = signal(0);
  protected readonly ratingComment = signal('');
  protected readonly submittingRating = signal(false);
  protected readonly ratingSubmitted = signal(false);
  protected readonly ratingErrorKey = signal<string | null>(null);

  protected readonly displayName = computed(
    () => this.profileService.profile()?.displayName?.trim() || null,
  );
  protected readonly initial = computed(() => (this.displayName() ?? '?').charAt(0).toUpperCase());

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
    try {
      const [recentOrders, reviewPage] = await Promise.all([
        firstValueFrom(this.orders.getOrders([], 10)),
        this.reviews.myReviews(),
      ]);
      const reviewed = new Set(reviewPage.items.map((review) => review.orderId));
      const candidate = recentOrders.find(
        (order) => order.status?.id === 'COMPLETED' && !reviewed.has(String(order.id)),
      );
      this.ratableOrder.set(candidate ?? null);
    } catch {
      this.ratableOrder.set(null);
    }
  }

  protected setLang(id: string): void {
    this.translate.setLang(id);
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
  }

  protected async submitRating(): Promise<void> {
    const order = this.ratableOrder();
    if (!order || this.rating() < 1) {
      return;
    }
    this.submittingRating.set(true);
    this.ratingErrorKey.set(null);
    try {
      await this.reviews.submit(String(order.id), this.rating(), this.ratingComment());
      this.ratingSubmitted.set(true);
    } catch (failure) {
      this.ratingErrorKey.set(
        failure instanceof HorecaOSApiError ? messageKeyFor(failure) : 'errors.generic',
      );
    } finally {
      this.submittingRating.set(false);
    }
  }
}

import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';

import { formatMoney } from '../../core/money/money';
import { TranslateService } from '../../services/translate.service';
import { type CartPointsOffer, PointsService } from '../../services/points.service';
import { TranslatePipe } from '../translate/translate.pipe';

/**
 * What the points control hands the checkout (ADR 0185): how many points the customer means to
 * spend, and whether what they typed is something the platform offered.
 */
export interface PointsSelection {
  /** Whole som to spend; 0 when the customer is not using points, or the amount is not one on offer. */
  readonly redeemMinor: number;
  /** True while points are switched on and the amount is not one the platform offered. */
  readonly blocked: boolean;
}

/** Nothing spent, nothing in the way: how every checkout starts. */
export const NO_POINTS: PointsSelection = { redeemMinor: 0, blocked: false };

/**
 * «Pay with points» on the checkout (ADR 0046, ADR 0185).
 *
 * The customer chooses how many of their points to put towards this order; the rest is paid the
 * way they chose. It asks the platform what may be spent -- the maximum, the refusal and the
 * order total are the platform's, never worked out here -- whenever the cart is priced again, and
 * hides itself when there is nothing to offer. It spends nothing: the amount goes out with the
 * checkout and is taken inside that transaction, never afterwards.
 */
@Component({
  selector: 'app-points-redemption',
  standalone: true,
  imports: [TranslatePipe],
  templateUrl: './points-redemption.component.html',
  styleUrl: './points-redemption.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PointsRedemptionComponent {
  private readonly points = inject(PointsService);
  private readonly translate = inject(TranslateService);

  /** The cart being checked out; null once it has become an order. */
  readonly cartId = input<string | null>(null);
  /** Changes whenever the cart is priced again: the offer is re-read against the new quote. */
  readonly quoteId = input<string | null>(null);

  readonly selectionChange = output<PointsSelection>();

  protected readonly offer = signal<CartPointsOffer | null>(null);
  protected readonly enabled = signal(false);
  protected readonly amountMinor = signal(0);
  /** The most on offer fell below what was typed, so the amount was lowered. */
  protected readonly adjusted = signal(false);

  private sequence = 0;

  protected readonly maximumMinor = computed(() => {
    const offer = this.offer();
    return offer !== null && offer.priced && offer.offer.redeemable
      ? offer.offer.maximum.amountMinor
      : 0;
  });
  protected readonly redeemable = computed(() => this.maximumMinor() > 0);
  protected readonly blockedByPromotion = computed(
    () => this.offer()?.offer.refusal === 'REDEMPTION_BLOCKED_BY_PROMOTION',
  );
  protected readonly visible = computed(() => this.redeemable() || this.blockedByPromotion());
  protected readonly amountValid = computed(
    () => this.amountMinor() >= 1 && this.amountMinor() <= this.maximumMinor(),
  );

  /** What is left to pay in money, once the points are out of the total. Null until there is an amount to take out. */
  protected readonly cashToPayText = computed(() => {
    const offer = this.offer();
    if (offer === null || !this.enabled() || !this.amountValid()) {
      return null;
    }
    return this.format({
      amountMinor: offer.orderTotal.amountMinor - this.amountMinor(),
      currency: offer.orderTotal.currency,
    });
  });
  protected readonly availableText = computed(() => {
    const offer = this.offer();
    return offer === null ? '' : this.format(offer.offer.available);
  });
  protected readonly maximumText = computed(() => {
    const offer = this.offer();
    return offer === null ? '' : this.format(offer.offer.maximum);
  });

  readonly selection = computed<PointsSelection>(() => {
    if (!this.enabled()) {
      return NO_POINTS;
    }
    const valid = this.amountValid();
    return { redeemMinor: valid ? this.amountMinor() : 0, blocked: !valid };
  });

  constructor() {
    effect(() => {
      const cartId = this.cartId();
      this.quoteId();
      this.sequence += 1;
      const sequence = this.sequence;
      if (cartId === null) {
        // The basket became an order (or there is none): nothing to offer, nothing selected.
        untracked(() => {
          this.offer.set(null);
          this.enabled.set(false);
          this.amountMinor.set(0);
        });
        return;
      }
      void this.load(sequence, cartId);
    });
    effect(() => this.selectionChange.emit(this.selection()));
  }

  private async load(sequence: number, cartId: string): Promise<void> {
    try {
      const offer = await this.points.redemptionOffer(cartId);
      if (sequence !== this.sequence) {
        return;
      }
      this.offer.set(offer);
      this.reconcile();
    } catch {
      if (sequence !== this.sequence) {
        return;
      }
      // Points are a convenience on top of placing the order: with nothing to show, the order is
      // placed without them.
      this.offer.set(null);
      this.enabled.set(false);
    }
  }

  /** A new offer can allow less than was typed, or nothing at all. */
  private reconcile(): void {
    if (!this.enabled()) {
      return;
    }
    if (!this.redeemable()) {
      this.enabled.set(false);
      this.amountMinor.set(0);
      return;
    }
    if (this.amountMinor() > this.maximumMinor()) {
      this.amountMinor.set(this.maximumMinor());
      this.adjusted.set(true);
    }
  }

  protected toggle(): void {
    const on = !this.enabled();
    this.enabled.set(on);
    this.adjusted.set(false);
    // Switching on starts from the most the platform allows: the customer lowers it, not raises it.
    this.amountMinor.set(on ? this.maximumMinor() : 0);
  }

  protected onAmountInput(text: string): void {
    this.adjusted.set(false);
    const digits = text.replace(/\D/g, '');
    this.amountMinor.set(digits === '' ? 0 : Number(digits));
  }

  protected useMaximum(): void {
    this.adjusted.set(false);
    this.amountMinor.set(this.maximumMinor());
  }

  private format(value: { amountMinor: number; currency: string }): string {
    return formatMoney(value, this.translate.get('common.currency'));
  }
}

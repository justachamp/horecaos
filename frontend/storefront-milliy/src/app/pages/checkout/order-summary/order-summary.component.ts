import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import type { HiddenChargeRow } from '../../../services/ui-cart.service';
import { TranslatePipe } from '../../../shared/translate/translate.pipe';
import type { PromotionNote, PromotionRow } from '../../../services/ui-cart.service';

/**
 * The money block on the checkout screen: subtotal, delivery, discount, total.
 *
 * It formats nothing and computes nothing. Every figure arrives already
 * formatted from `UiCartService`, which read it from the platform's pricing
 * answer (ADR 0072); a figure worked out here could disagree with what the
 * platform will charge.
 */
@Component({
  selector: 'app-order-summary',
  standalone: true,
  imports: [TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './order-summary.component.html',
  styleUrl: './order-summary.component.scss',
})
export class OrderSummaryComponent {
  readonly subtotal = input.required<string>();
  readonly deliveryFee = input.required<string>();
  /** Why the delivery line reads as a dash rather than a price, or null when it resolved. */
  readonly unresolvedMessage = input<string | null>(null);
  /**
   * The discounts, one line per kind with the platform's own amount (ADR 0140).
   * Their sum is the discount; a code line carries the customer's own code.
   */
  readonly discountRows = input<readonly PromotionRow[]>([]);
  /** Benefits already inside the delivery price or the goods, as captions. */
  readonly notes = input<readonly PromotionNote[]>([]);
  readonly total = input.required<string>();
  /**
   * ADR 0136: what the server added by itself -- a delivery box the customer never chose --
   * itemised, each already inside the figures above. Empty when nothing was added.
   */
  readonly hiddenCharges = input<readonly HiddenChargeRow[]>([]);
  /**
   * ADR 0137: the basket holds an item sold by weight, so the total is an estimate at its
   * nominal weight and the final weight and amount are determined at handover.
   */
  readonly provisional = input(false);
  /**
   * X.1 (ADR 0185): the points the customer is putting towards this order, already formatted, or
   * null when none are spent. The rows below the total are the platform's own figures minus
   * that, and appear only when it is there.
   */
  readonly pointsSpent = input<string | null>(null);
  /** What is still paid in money once the points are out of the total; null with `pointsSpent`. */
  readonly cashToPay = input<string | null>(null);
}

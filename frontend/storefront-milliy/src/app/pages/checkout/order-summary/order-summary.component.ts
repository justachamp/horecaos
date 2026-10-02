import { ChangeDetectionStrategy, Component, input } from '@angular/core';

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
}

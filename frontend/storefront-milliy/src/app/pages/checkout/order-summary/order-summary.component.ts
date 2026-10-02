import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import type { HiddenChargeRow } from '../../../services/ui-cart.service';
import { TranslatePipe } from '../../../shared/translate/translate.pipe';

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
  readonly hasDiscount = input(false);
  readonly discount = input('');
  readonly total = input.required<string>();
  /**
   * ADR 0136: what the server added by itself -- a delivery box the customer never chose --
   * itemised, each already inside the figures above. Empty when nothing was added.
   */
  readonly hiddenCharges = input<readonly HiddenChargeRow[]>([]);
}

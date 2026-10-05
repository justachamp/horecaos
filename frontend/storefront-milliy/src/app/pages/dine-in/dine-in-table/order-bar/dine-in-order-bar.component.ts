import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { TranslatePipe } from '../../../../shared/translate/translate.pipe';

/** One charge the platform added to the basket by itself, already formatted for the screen. */
export interface HiddenChargeLine {
  readonly optionId: string;
  readonly label: string;
  readonly amount: string;
}

/** The payment codes this deployment has a label for; any other shows its own code. */
const PAYMENT_LABEL_KEYS: Readonly<Record<string, string>> = {
  CASH: 'cart.cash',
  CLICK: 'cart.click',
  PAYME: 'cart.payme',
};

/**
 * The bar that rides above the tab bar on the dine-in table once the guest has something in the
 * table's basket: the count and the platform's total, any charge the server added by itself, the
 * payment methods on offer, why the basket could not be priced or ordered, and the call to
 * action.
 *
 * It decides nothing and sends nothing. `DineInTableComponent` owns the basket, the quote and the
 * checkout; this takes what to show as inputs and says what the guest asked for as outputs, so a
 * region with a stylesheet of its own stays out of the page's. The host is `display: contents`,
 * which keeps the box tree exactly as it was when this markup lived in the page.
 */
@Component({
  selector: 'app-dine-in-order-bar',
  standalone: true,
  imports: [TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './dine-in-order-bar.component.html',
  styleUrl: './dine-in-order-bar.component.scss',
})
export class DineInOrderBarComponent {
  /** Plates in the basket. */
  readonly cartCount = input.required<number>();
  /** The platform's total, or a dash while it holds no price -- never a zero. */
  readonly total = input.required<string>();
  /** ADR 0136: what the server added to the basket by itself, each already inside the total. */
  readonly hiddenCharges = input<readonly HiddenChargeLine[]>([]);
  readonly paymentOptions = input<readonly string[]>([]);
  readonly selectedPayment = input<string | null>(null);
  /** Why the basket could not be priced, as a translation key; null when it could. */
  readonly priceRefusalKey = input<string | null>(null);
  /** Why the last checkout was refused, as a translation key; null when nothing went wrong. */
  readonly checkoutErrorKey = input<string | null>(null);
  /** The basket is being changed; nothing can be sent until that settles. */
  readonly updating = input(false);
  readonly checkingOut = input(false);
  /** The platform has priced the basket, so there is a quote to order against. */
  readonly priced = input(false);

  readonly paymentChosen = output<string>();
  readonly clearRequested = output<void>();
  readonly checkoutRequested = output<void>();

  protected paymentLabelKey(code: string): string | null {
    return PAYMENT_LABEL_KEYS[code] ?? null;
  }
}

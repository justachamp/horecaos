import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';

import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { OrderDeliveryResponse } from './order-detail';
import { MoneyReconciliation } from './order-money';
import { deliveryExceptionReasonLabel } from './order-outcome-labels';
import { OrderSummaryResponse } from './order-summary';

/**
 * The order detail pane's money section (§3.5, §1.3): the order's own figures in
 * the fixed row order, the delivery figures beside them (row 1.2n) and the open
 * delivery exceptions (rows 1.2f/1.2g).
 *
 * Presentation only. The reconciliation (does the total equal the lines?) is
 * worked out by the pane from the lines it already holds, and the delivery plan
 * is the pane's read; this component formats them and never adds a figure the
 * server did not send. It is its own component so the section's rules do not
 * count against the pane's component-style budget.
 */
@Component({
  selector: 'q-order-detail-money',
  imports: [TPipe],
  templateUrl: './order-detail-money.html',
  styleUrl: './order-detail-money.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderDetailMoney {
  private readonly i18n = inject(I18n);

  /** Null until the order has loaded, in which case nothing is shown. */
  readonly moneyReconciliation = input.required<MoneyReconciliation | null>();
  readonly summary = input.required<OrderSummaryResponse>();
  readonly taxMinor = input.required<number>();
  readonly delivery = input.required<OrderDeliveryResponse | null>();

  protected formatMoneyMinor(amountMinor: number, currency: string): string {
    return formatMoney({ amountMinor, currency }, this.i18n.locale(), { withUnit: true });
  }

  /** The margin: the customer's fee minus what the provider billed — negative whenever `fulfillment.delivery_cost_subsidies` recorded a gap, because that row is only ever written for a loss. */
  protected deliveryMarginMinor(delivery: OrderDeliveryResponse): number | null {
    return delivery.providerCostMinor == null
      ? null
      : delivery.customerDeliveryFeeMinor - delivery.providerCostMinor;
  }

  /** The delivery-exception band's own reason label (gap map rows 1.2f/1.2g). */
  protected deliveryExceptionReasonLabel(value: string): string {
    return deliveryExceptionReasonLabel(value, (key, values) => this.i18n.t(key, values));
  }
}

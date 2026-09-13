import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { TPipe } from '../../core/i18n/t.pipe';
import { MoneyInput } from './money-input';
import { PercentInput } from './percent-input';

/** The choice a discount, surcharge or cap is expressed in. */
export type MoneyOrPercentKind = 'AMOUNT' | 'PERCENT';

/**
 * A value that is either a fixed som amount or a percentage — never both at
 * once (ADR 0101, row `X.10`).
 *
 * `pricing.promotions`' discount shapes and `pricing.delivery_tariffs`'
 * discount rows both carry exactly this choice already — `DiscountRequest.kind`
 * on the tariff side, `DiscountShape`'s `PERCENTAGE_OFF_ORDER` /
 * `FIXED_AMOUNT_OFF_ORDER` on the promo-code side — and today each screen
 * hand-rolls its own `<select>` plus a conditionally-shown number field. This
 * is that pattern, once: a two-way kind toggle in front of whichever of
 * {@link MoneyInput} or {@link PercentInput} the kind selects, so the amount
 * and the percentage are never both live at the same time and never both
 * submitted.
 */
@Component({
  selector: 'q-money-or-percent',
  imports: [TPipe, MoneyInput, PercentInput],
  templateUrl: './money-or-percent.html',
  styleUrl: './money-or-percent.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MoneyOrPercent {
  readonly kind = input<MoneyOrPercentKind>('AMOUNT');
  readonly amountMinor = input<number>(0);
  readonly basisPoints = input<number>(0);

  readonly kindChange = output<MoneyOrPercentKind>();
  readonly amountMinorChange = output<number>();
  readonly basisPointsChange = output<number>();

  protected selectKind(kind: MoneyOrPercentKind): void {
    if (kind !== this.kind()) {
      this.kindChange.emit(kind);
    }
  }
}

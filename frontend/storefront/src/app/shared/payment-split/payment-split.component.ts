import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';

import { formatMoney } from '../../core/money/money';
import { TranslateService } from '../../services/translate.service';
import type { PaymentSplit, PaymentTender, TenderStatus } from '../../services/points.service';
import { TranslatePipe } from '../translate/translate.pipe';

/** The words for a tender's status; the platform's own vocabulary, translated, nothing invented. */
const STATUS_KEYS: Readonly<Record<TenderStatus, string>> = {
  PLANNED: 'split.status.PLANNED',
  RESERVED: 'split.status.RESERVED',
  SETTLED: 'split.status.SETTLED',
  RELEASED: 'split.status.RELEASED',
  REVERSED: 'split.status.REVERSED',
  FAILED: 'split.status.FAILED',
};

/** The methods this storefront has a name for; any other is shown by its code rather than hidden. */
const METHOD_KEYS: Readonly<Record<string, string>> = {
  CASH: 'cart.cash',
  CLICK: 'cart.click',
  PAYME: 'cart.payme',
};

export interface TenderRow {
  readonly key: string;
  readonly label: string;
  readonly amount: string;
  readonly statusKey: string;
}

/**
 * How an order is being paid when more than one thing is paying for it (ADR 0046, ADR 0185): the
 * points the customer spent and the money that settles the rest, each with where it stands.
 *
 * Shown only for an order that really is split. An order paid entirely in one way has nothing to
 * add to the total the page already shows, and a line saying so would only repeat it.
 *
 * Presentation only: the tenders, their amounts and their statuses are the platform's.
 */
@Component({
  selector: 'app-payment-split',
  standalone: true,
  imports: [TranslatePipe],
  templateUrl: './payment-split.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PaymentSplitComponent {
  private readonly translate = inject(TranslateService);

  readonly split = input<PaymentSplit | null>(null);

  protected readonly rows = computed<readonly TenderRow[]>(() => {
    const split = this.split();
    if (split === null || !isSplit(split.tenders)) {
      return [];
    }
    return split.tenders.map((tender, index) => ({
      key: `${index}`,
      label: this.labelOf(tender),
      amount: formatMoney(tender.amount, this.translate.get('common.currency')),
      statusKey: STATUS_KEYS[tender.status] ?? 'split.status.PLANNED',
    }));
  });

  private labelOf(tender: PaymentTender): string {
    if (tender.kind === 'POINTS') {
      return this.translate.get('split.points');
    }
    const key = tender.methodCode ? METHOD_KEYS[tender.methodCode] : undefined;
    return key ? this.translate.get(key) : (tender.methodCode ?? this.translate.get('split.money'));
  }
}

/** More than one tender, or any points: the cases where the split says something the total does not. */
function isSplit(tenders: readonly PaymentTender[]): boolean {
  return tenders.length > 1 || tenders.some((tender) => tender.kind === 'POINTS');
}

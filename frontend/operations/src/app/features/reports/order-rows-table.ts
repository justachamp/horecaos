import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';

import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { formatDate, formatTime } from '../../core/format/datetime';
import { formatMoney } from '../../core/format/money';
import { orderStatusLabel } from '../orders/order-status';
import { REPORTS_PLACEHOLDER_TIME_ZONE } from './reports-filter-state';
import { formatCount, formatSecondsDuration, formatSignedMinutes } from './report-formatting';
import { OrderRowResponse } from './reporting-api';

/** Which columns one of 7.2's three order-grain tabs shows. */
export type OrderTableColumn =
  | 'orderId'
  | 'businessDate'
  | 'channel'
  | 'fulfilment'
  | 'status'
  | 'confirm'
  | 'ready'
  | 'total'
  | 'late'
  | 'gross'
  | 'discount'
  | 'deliveryFee'
  | 'net'
  | 'items'
  | 'occurredAt';

const SEVERITY_RED_SECONDS = 60 * 60;
const SEVERITY_AMBER_SECONDS = 30 * 60;

/**
 * The order-grain table behind 7.2's «Этапы», «Заказы» and «Опоздания» tabs —
 * one component, because all three are the same shape (a row per order from
 * `GET .../reporting/orders`) with a different column set and sort, not three
 * different tables (statistics.md §2.2: "these really are six different
 * tables over the same rows").
 *
 * **Severity is the SLA buckets' own >60/>30-minute cut** (`sla_bucket_set.v1`),
 * not a comparison against this order's own promised duration: the endpoint
 * this table reads has no per-order promise to compare against (that lives on
 * `ordering.orders`, which the reporting read role cannot reach), so re-using
 * the platform's own fixed bucket boundaries is the closest honest severity
 * signal available rather than inventing a new one.
 */
@Component({
  selector: 'q-order-rows-table',
  imports: [TPipe],
  templateUrl: './order-rows-table.html',
  styleUrl: './order-rows-table.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderRowsTable {
  private readonly i18n = inject(I18n);

  readonly rows = input.required<readonly OrderRowResponse[]>();
  readonly columns = input.required<readonly OrderTableColumn[]>();
  readonly emptyMessageKey = input<MessageKey>('reports.empty.period');

  protected hasColumn(column: OrderTableColumn): boolean {
    return this.columns().includes(column);
  }

  protected shortId(orderId: string): string {
    return orderId.slice(0, 8);
  }

  protected statusLabel(status: string): string {
    return orderStatusLabel(status, (key) => this.i18n.t(key));
  }

  protected fulfilmentLabel(type: string): string {
    switch (type) {
      case 'DELIVERY':
        return this.i18n.t('orders.fulfillmentMode.DELIVERY');
      case 'PICKUP':
        return this.i18n.t('orders.fulfillmentMode.PICKUP');
      default:
        return this.i18n.t('orders.fulfillmentMode.DINE_IN');
    }
  }

  protected money(amountSom: number): string {
    return formatMoney({ amountMinor: amountSom, currency: 'UZS' }, this.i18n.locale());
  }

  protected duration(seconds: number | null): string {
    return seconds === null ? '—' : formatSecondsDuration(seconds);
  }

  protected lateness(seconds: number | null): string {
    return seconds === null
      ? '—'
      : formatSignedMinutes(seconds, this.i18n.t('reports.unit.minutes'));
  }

  protected count(value: number): string {
    return formatCount(value);
  }

  protected date(iso: string): string {
    return formatDate(new Date(iso), REPORTS_PLACEHOLDER_TIME_ZONE);
  }

  protected time(iso: string): string {
    return formatTime(new Date(iso), REPORTS_PLACEHOLDER_TIME_ZONE);
  }

  protected severity(row: OrderRowResponse): 'normal' | 'amber' | 'red' {
    const seconds = row.secondsTotal;
    if (seconds === null) {
      return 'normal';
    }
    if (seconds >= SEVERITY_RED_SECONDS) {
      return 'red';
    }
    if (seconds >= SEVERITY_AMBER_SECONDS) {
      return 'amber';
    }
    return 'normal';
  }
}

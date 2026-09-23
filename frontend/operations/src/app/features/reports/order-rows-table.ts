import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';

import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { formatDate, formatTime } from '../../core/format/datetime';
import { formatMoney } from '../../core/format/money';
import { orderStatusLabel } from '../orders/order-status';
import { REPORTS_PLACEHOLDER_TIME_ZONE } from './reports-filter-state';
import { formatCount, formatSecondsDuration, formatSignedMinutes } from './report-formatting';
import { CrmLogRowResponse } from './order-crm-log-api';
import { OrderRowResponse } from './reporting-api';

/** Which columns one of 7.2's three order-grain tabs shows. */
export type OrderTableColumn =
  | 'orderId'
  | 'businessDate'
  | 'branch'
  | 'channel'
  | 'fulfilment'
  | 'preorder'
  | 'status'
  | 'confirm'
  | 'ready'
  | 'accept'
  | 'cooking'
  | 'total'
  | 'late'
  | 'gross'
  | 'discount'
  | 'deliveryFee'
  | 'net'
  | 'items'
  | 'occurredAt'
  | 'customer'
  | 'operator'
  | 'courier';

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
 *
 * **Wave P27.** `orderId` now prints the public order number
 * (`row.publicOrderNumber`) when the fact carries one, falling back to the
 * eight-character UUID fragment only for a row closed before that column
 * existed — never both. `accept`/`cooking` split what `ready` used to
 * conflate: `secondsToAccept` is CONFIRMED -> PREPARING (the branch-
 * acceptance wait) and `secondsPreparing` is PREPARING -> READY (actual
 * cooking, narrower than `ready`/`secondsToReady`). `branch` needs a
 * `locationId` -> name lookup the caller supplies ({@link locationNames}) —
 * this table has no location list of its own to fetch one from.
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
  /** Wave P27 (7.2a): locationId -> display name, for the `branch` column. Empty when the caller has none. */
  readonly locationNames = input<ReadonlyMap<string, string>>(new Map());
  /**
   * Wave 9 w4-reports-distance-crm (7.2a): orderId -> the CRM half of this
   * same row (`GET /orders/crm-log`), joined here rather than in SQL —
   * `order-reports-page.ts`'s own doc explains why. Empty when the caller has
   * not fetched it (every tab but «Заказы»), in which case `customer`/
   * `operator`/`courier` render `—` rather than an error.
   */
  readonly crmByOrderId = input<ReadonlyMap<string, CrmLogRowResponse>>(new Map());

  protected hasColumn(column: OrderTableColumn): boolean {
    return this.columns().includes(column);
  }

  private crmRow(row: OrderRowResponse): CrmLogRowResponse | undefined {
    return this.crmByOrderId().get(row.orderId);
  }

  /**
   * «Клиент»: the account's or guest's name in full — never masked (orders.md
   * §1.5, §3.7). A `GUEST` order has no account and is always labelled
   * "Guest". An `ACCOUNT` order with no `customerName` (the snapshot row
   * predates the snapshot feature, or was otherwise never written) falls back
   * to "Customer account", never "Guest" — collapsing the two would mislead
   * an operator into treating a real account holder as anonymous.
   */
  protected customerLabel(row: OrderRowResponse): string {
    const crm = this.crmRow(row);
    if (!crm) {
      return '—';
    }
    if (crm.customerType === 'GUEST') {
      return this.i18n.t('reports.orders.column.customer.guest');
    }
    return crm.customerName ?? this.i18n.t('reports.orders.column.customer.account');
  }

  /** The masked phone beside the name — already masked server-side, never the plaintext. */
  protected customerPhone(row: OrderRowResponse): string {
    return this.crmRow(row)?.customerPhone ?? '—';
  }

  /**
   * «Оператор»: a staff Keycloak subject, or `channel:CODE` for a machine
   * channel (`OperatorAttribution`'s own scheme, reimplemented on the
   * `ordering` side for the CRM log). No staff name until the staff-identity
   * ADR lands, the same limitation `operator-leaderboard`'s own column states.
   */
  protected operatorLabel(row: OrderRowResponse): string {
    const operator = this.crmRow(row)?.operatorPrincipalId;
    if (!operator) {
      return '—';
    }
    return operator.startsWith('channel:') ? operator.slice('channel:'.length) : operator;
  }

  /** «Курьер»: the non-PII handle ("K-014"), never a decrypted name — see `OrderCrmLogController`'s own doc. */
  protected courierLabel(row: OrderRowResponse): string {
    return this.crmRow(row)?.courierDisplayReference ?? '—';
  }

  /** Wave P27 (7.2a): the public order number when the fact carries one — never eight characters of a UUID again. */
  protected orderNumber(row: OrderRowResponse): string {
    return row.publicOrderNumber ?? row.orderId.slice(0, 8);
  }

  protected branchName(row: OrderRowResponse): string {
    return this.locationNames().get(row.locationId) ?? row.locationId;
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

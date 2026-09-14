import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';

import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { FilterBar } from '../../shared/ui/filter-bar';
import {
  PaymentMethodView,
  PaymentMethodsApi,
} from '../settings/payment-methods/payment-methods-api';
import { PeriodPreset, ReportsFilterState } from './reports-filter-state';

/**
 * The shared global filter bar (statistics.md §1.1), row 1 only for this wave
 * — see {@link ReportsFilterState}'s own doc for what row 2 (branch, legal
 * entity, custom date range, granularity) is deliberately short of, and why.
 *
 * **Thin wrapper, not the real thing.** Wave P07 lifted this component's own
 * chrome (the row, the padding, the border) out into `shared/ui/filter-bar`
 * as `q-filter-bar` — orders.md §2.4 requires the order board and the order
 * reports to share one filter component, and this file is what keeps Reports
 * compiling against the old `q-reports-filter-bar` selector while that
 * extraction lands. Wave P27 re-points every report screen at `q-filter-bar`
 * directly and deletes this file; do not add a new consumer of
 * `q-reports-filter-bar` in the meantime, and do not extract the bar a second
 * time — it is already extracted.
 *
 * **Тип оплаты is unlocked (P39).** It used to render a locked notice naming
 * `fact_order_tender` as the reason — ADR 0043's own status line listed it as
 * not built. Now it reads the tenant's `payments.payment_methods` registry
 * (ADR 0038, seeded by wave P33) the same way the settings screen does, and
 * toggles `ReportsFilterState.paymentMethodCodes` — a multiselect, because a
 * manager reconciling cash legitimately wants "cash and card, not online"
 * rather than one method at a time. The control lives inside this wrapper's
 * own `primary`-slotted content, not as a second `q-filter-bar` extraction.
 */
@Component({
  selector: 'q-reports-filter-bar',
  imports: [TPipe, FilterBar],
  templateUrl: './reports-filter-bar.html',
  styleUrl: './reports-filter-bar.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReportsFilterBar implements OnInit {
  protected readonly state = inject(ReportsFilterState);
  private readonly location = inject(CurrentLocation);
  private readonly paymentMethodsApi = inject(PaymentMethodsApi);
  private readonly i18n = inject(I18n);

  protected readonly periods: readonly {
    readonly id: PeriodPreset;
    readonly labelKey: MessageKey;
  }[] = [
    { id: 'today', labelKey: 'reports.filter.period.today' },
    { id: 'yesterday', labelKey: 'reports.filter.period.yesterday' },
    { id: '7d', labelKey: 'reports.filter.period.7d' },
    { id: 'month', labelKey: 'reports.filter.period.month' },
  ];

  protected readonly fulfilmentTypes: readonly {
    readonly id: 'ALL' | 'DELIVERY' | 'PICKUP' | 'DINE_IN';
    readonly labelKey: MessageKey;
  }[] = [
    { id: 'ALL', labelKey: 'reports.filter.fulfilment.all' },
    { id: 'DELIVERY', labelKey: 'reports.filter.fulfilment.delivery' },
    { id: 'PICKUP', labelKey: 'reports.filter.fulfilment.pickup' },
    { id: 'DINE_IN', labelKey: 'reports.filter.fulfilment.dineIn' },
  ];

  protected readonly paymentMethods = signal<readonly PaymentMethodView[]>([]);

  ngOnInit(): void {
    void this.loadPaymentMethods();
  }

  protected selectPeriod(period: PeriodPreset): void {
    this.state.setPeriod(period);
  }

  protected selectFulfilmentType(type: 'ALL' | 'DELIVERY' | 'PICKUP' | 'DINE_IN'): void {
    this.state.setFulfilmentType(type);
  }

  protected paymentMethodLabel(method: PaymentMethodView): string {
    return method.localizedNames[this.i18n.locale()] ?? method.displayName;
  }

  protected paymentMethodActive(code: string): boolean {
    return this.state.paymentMethodCodes().includes(code);
  }

  protected togglePaymentMethod(code: string): void {
    const current = this.state.paymentMethodCodes();
    this.state.setPaymentMethodCodes(
      current.includes(code) ? current.filter((existing) => existing !== code) : [...current, code],
    );
  }

  private async loadPaymentMethods(): Promise<void> {
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    const methods = await this.paymentMethodsApi
      .list(scope)
      .catch(() => [] as readonly PaymentMethodView[]);
    this.paymentMethods.set(
      methods
        .filter((method) => method.status === 'ACTIVE')
        .sort((a, b) => a.sortOrder - b.sortOrder),
    );
  }
}

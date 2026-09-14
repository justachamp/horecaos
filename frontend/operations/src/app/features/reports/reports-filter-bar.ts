import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { FilterBar } from '../../shared/ui/filter-bar';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
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
 * **Тип оплаты renders locked, not hidden** (§1.1): `fact_order_tender`
 * (ADR 0046) is one of the fact families ADR 0043's own status line names as
 * not built, so a manager who goes looking for the payment filter is told why
 * it is missing rather than concluding it does not exist anywhere.
 */
@Component({
  selector: 'q-reports-filter-bar',
  imports: [TPipe, FilterBar],
  templateUrl: './reports-filter-bar.html',
  styleUrl: './reports-filter-bar.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReportsFilterBar {
  protected readonly state = inject(ReportsFilterState);

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

  protected selectPeriod(period: PeriodPreset): void {
    this.state.setPeriod(period);
  }

  protected selectFulfilmentType(type: 'ALL' | 'DELIVERY' | 'PICKUP' | 'DINE_IN'): void {
    this.state.setFulfilmentType(type);
  }
}

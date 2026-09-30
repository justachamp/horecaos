import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { TPipe } from '../../core/i18n/t.pipe';
import { ConnectionState } from '../../core/realtime/realtime-client';
import { ConnectionStateBanner } from '../../shared/ui/connection-state-banner';
import { StaleIndicator } from '../../shared/ui/stale-indicator';
import { OrderTabDefinition, OrderTabId } from './order-tabs';
import { TabCounts } from './order-counts';

/**
 * The strip between the filters and the table: the seven tabs with their counts
 * (orders.md §2.3) on the left, the connection banner, the "updated at" stamp,
 * the staleness marker and the refresh button on the right.
 *
 * Presentation only. Which tab is active, the counts, the connection state and
 * what a refresh does belong to the queue (`OrderQueue`), which binds the values
 * here and reacts to the two requests this strip raises. It is its own component
 * so the strip's rules do not count against the queue's component-style budget.
 */
@Component({
  selector: 'q-order-queue-toolbar',
  imports: [ConnectionStateBanner, StaleIndicator, TPipe],
  templateUrl: './order-queue-toolbar.html',
  styleUrl: './order-queue-toolbar.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderQueueToolbar {
  readonly tabs = input.required<readonly OrderTabDefinition[]>();
  readonly activeTab = input.required<OrderTabId>();
  readonly tabCounts = input.required<TabCounts>();
  readonly connectionState = input.required<ConnectionState>();
  /** The formatted time of the last refresh, or null before the first one. */
  readonly updatedStamp = input.required<string | null>();
  readonly refreshing = input.required<boolean>();
  readonly lastUpdatedAt = input.required<Date | null>();

  readonly tabSelected = output<OrderTabId>();
  readonly refreshRequested = output<void>();

  protected tabCount(tab: OrderTabId): number {
    return this.tabCounts()[tab];
  }

  protected selectTab(tab: OrderTabId): void {
    this.tabSelected.emit(tab);
  }

  protected manualRefresh(): void {
    this.refreshRequested.emit();
  }
}

import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { TPipe } from '../../core/i18n/t.pipe';
import { KitchenTabDefinition, KitchenTabId } from './kitchen-ticket';

/**
 * The strip above the kitchen board: the fulfilment tabs with their counts, the
 * counter-sale shortcut and the open/closed service toggle.
 *
 * Presentation only. Which tab is active, the counts and the service state
 * belong to the queue (`KitchenQueuePage`), which binds them here and reacts to
 * the three requests this strip raises. It is its own component so the strip's
 * rules do not count against the queue's component-style budget.
 */
@Component({
  selector: 'q-kitchen-queue-toolbar',
  imports: [TPipe],
  templateUrl: './kitchen-queue-toolbar.html',
  styleUrl: './kitchen-queue-toolbar.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class KitchenQueueToolbar {
  readonly tabs = input.required<readonly KitchenTabDefinition[]>();
  readonly activeTab = input.required<KitchenTabId>();
  /** Per tab: the number of tickets, or null while the board has not been read. */
  readonly tabCounts = input.required<Readonly<Record<KitchenTabId, number | null>>>();
  /** False until the service summary has loaded, in which case the toggle is not offered. */
  readonly canToggleService = input.required<boolean>();
  readonly serviceClosed = input.required<boolean>();
  readonly serviceModeLabel = input.required<string>();
  readonly togglingService = input.required<boolean>();

  readonly tabSelected = output<KitchenTabId>();
  readonly counterSaleRequested = output<void>();
  readonly serviceToggleRequested = output<void>();

  protected tabCount(tab: KitchenTabId): number | null {
    return this.tabCounts()[tab];
  }

  protected selectTab(tab: KitchenTabId): void {
    this.tabSelected.emit(tab);
  }
}

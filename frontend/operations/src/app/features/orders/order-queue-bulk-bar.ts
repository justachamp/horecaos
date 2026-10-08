import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { TPipe } from '../../core/i18n/t.pipe';

/**
 * The selection bar that replaces the filter row while orders are selected
 * (orders.md §2.10).
 *
 * Presentation only. What is selected, which bulk actions that selection allows
 * and what a click does are the queue's (`OrderQueue`): it computes the values
 * bound here from the selected orders and reacts to the three requests this bar
 * raises. It is its own component so the bar's rules do not count against the
 * queue's component-style budget.
 */
@Component({
  selector: 'q-order-queue-bulk-bar',
  imports: [TPipe],
  templateUrl: './order-queue-bulk-bar.html',
  styleUrl: './order-queue-bulk-bar.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderQueueBulkBar {
  readonly selectionCount = input.required<number>();
  /** The status every selected order can advance to together, or null when there is none. */
  readonly bulkAdvanceTargetStatus = input.required<string | null>();
  readonly bulkAdvanceLabel = input.required<string>();
  readonly canBulkCancel = input.required<boolean>();
  /** Why cancel is unavailable for this selection, or null when it is available or nothing is selected. */
  readonly bulkCancelUnavailableMessage = input.required<string | null>();
  /**
   * ADR 0172: «Назначить курьера» is offered exactly when every selected row's `actions[]` carries
   * `ASSIGN_COURIER` and the session holds both `order.bulk-action` and `delivery.manual_assign`
   * at the location — a prediction, the server decides.
   */
  readonly canBulkAssign = input(false);
  /** Why assigning is unavailable for this selection, or null when it is available or nothing is selected. */
  readonly bulkAssignUnavailableMessage = input<string | null>(null);
  readonly bulkBusy = input.required<boolean>();

  readonly advanceRequested = output<void>();
  readonly cancelRequested = output<void>();
  readonly assignRequested = output<void>();
  readonly clearRequested = output<void>();

  protected onBulkAdvanceClick(): void {
    this.advanceRequested.emit();
  }

  protected onBulkCancelClick(): void {
    this.cancelRequested.emit();
  }

  protected onBulkAssignClick(): void {
    this.assignRequested.emit();
  }

  protected clearSelection(): void {
    this.clearRequested.emit();
  }
}

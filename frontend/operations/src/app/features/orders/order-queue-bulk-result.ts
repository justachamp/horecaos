import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { TPipe } from '../../core/i18n/t.pipe';
import { BulkActionResponse } from './order-bulk-actions-api';

/**
 * The outcome of a bulk action (orders.md §2.10): "197 applied · 3 problems",
 * the named list of the orders that failed, and Retry the failed ones.
 *
 * A panel rather than a toast because a count plus a named list plus a retry
 * does not fit one. Presentation only: the queue owns the result, resends the
 * failed items and supplies the two labellers, because both read state the queue
 * holds (its rows, the locale). It is its own component so the panel's rules do
 * not count against the queue's component-style budget.
 */
@Component({
  selector: 'q-order-queue-bulk-result',
  imports: [TPipe],
  templateUrl: './order-queue-bulk-result.html',
  styleUrl: './order-queue-bulk-result.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderQueueBulkResult {
  readonly result = input.required<BulkActionResponse>();
  readonly bulkBusy = input.required<boolean>();
  /** The order number to show for a failed item. */
  readonly itemLabel = input.required<(orderId: string) => string>();
  /** The words for a failed item's problem code. */
  readonly problemLabel = input.required<(code: string | null | undefined) => string>();

  readonly retryRequested = output<void>();
  readonly dismissRequested = output<void>();

  protected bulkResultItemLabel(orderId: string): string {
    return this.itemLabel()(orderId);
  }

  protected bulkProblemLabel(code: string | null | undefined): string {
    return this.problemLabel()(code);
  }

  protected retryFailedBulkItems(): void {
    this.retryRequested.emit();
  }

  protected dismissBulkResult(): void {
    this.dismissRequested.emit();
  }
}

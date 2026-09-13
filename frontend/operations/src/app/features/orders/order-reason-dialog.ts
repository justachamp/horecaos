import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';

import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { Modal } from '../../shared/ui/modal';

export interface OrderReasonSubmission {
  readonly reasonCode: string;
  readonly note?: string;
}

/**
 * The free-text reason dialog: a reason code plus, for cancellation, an
 * optional note (`DecisionRequest.reasonCode`, `CancelRequest.reasonCode`/
 * `note` — `OperationsOrderController`).
 *
 * **The registry this comment used to say "does not exist yet" now does**
 * (`ordering.order_outcome_reasons`, ADR 0039, `ReferenceDataApi` — wave
 * P31/P37). `OrderRejectReasonDialog` moved onto it for `Отклонить` back in
 * wave 24, and `q-order-outcome-reason-dialog` (wave P09, orders.md §4.5)
 * is the picker `Отменить` uses from the order detail pane now, past
 * `CONFIRMED` included. This dialog survives only as `order-queue.ts`'s
 * quick reasonless cancel for a still-open order — `CancelRequest.reasonCode`
 * with no `reasonId` — where ADR 0019 never asked for a registry reason in
 * the first place; it is not a placeholder for the registry, it is the path
 * that genuinely has none to pick from.
 */
@Component({
  selector: 'q-order-reason-dialog',
  imports: [TPipe, Modal],
  templateUrl: './order-reason-dialog.html',
  styleUrl: './order-reason-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderReasonDialog {
  readonly titleKey = input.required<MessageKey>();
  readonly confirmLabelKey = input.required<MessageKey>();
  /** True for `Отменить` (`CancelRequest.note`); false for `Отклонить`, which has no note field. */
  readonly noteEnabled = input(false);
  /**
   * Every consumer today closes the dialog on any settlement and reports a
   * failure through its own notice band instead (`order-queue.ts`,
   * `order-detail-pane.ts`) — see `mutationErrorNotice` in `order-errors.ts`
   * — so there is deliberately no in-dialog error state here to keep in sync
   * with that behaviour.
   */
  readonly busy = input(false);

  readonly confirm = output<OrderReasonSubmission>();
  readonly dismiss = output<void>();

  protected readonly reasonCode = signal('');
  protected readonly note = signal('');
  private readonly touched = signal(false);

  protected readonly reasonMissing = computed(
    () => this.touched() && this.reasonCode().trim() === '',
  );

  protected setReasonCode(value: string): void {
    this.reasonCode.set(value);
  }

  protected setNote(value: string): void {
    this.note.set(value);
  }

  protected submit(): void {
    this.touched.set(true);
    const reasonCode = this.reasonCode().trim();
    if (!reasonCode) {
      return;
    }
    const note = this.note().trim();
    this.confirm.emit({ reasonCode, note: this.noteEnabled() && note ? note : undefined });
  }

  protected close(): void {
    this.reasonCode.set('');
    this.note.set('');
    this.touched.set(false);
    this.dismiss.emit();
  }
}

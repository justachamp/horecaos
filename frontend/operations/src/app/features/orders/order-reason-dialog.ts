import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';

import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';

export interface OrderReasonSubmission {
  readonly reasonCode: string;
  readonly note?: string;
}

/**
 * The one dialog shape `Отклонить` and `Отменить` both need this wave: a
 * free-text reason code plus, for cancellation, an optional note
 * (`DecisionRequest.reasonCode`, `CancelRequest.reasonCode`/`note` —
 * `OperationsOrderController`). Shared rather than duplicated in the queue and
 * the detail pane, since both need to open the same dialog for the same two
 * actions.
 *
 * **Free text, not a picker, and that is a scope line, not an oversight.**
 * `docs/operations-spec/orders.md` §4.5 wants a searchable registry
 * (`ordering.order_outcome_reasons`) with internal/customer-facing text pairs;
 * that table and its endpoint do not exist yet (§11: ADR 0039). Building a
 * picker against data that is not there would mean fabricating a reason list
 * this client invented, which is worse than a plain text field that is
 * visibly a placeholder for the real registry.
 */
@Component({
  selector: 'q-order-reason-dialog',
  imports: [TPipe],
  templateUrl: './order-reason-dialog.html',
  styleUrl: './order-reason-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderReasonDialog {
  readonly titleKey = input.required<MessageKey>();
  readonly confirmLabelKey = input.required<MessageKey>();
  /** True for `Отменить` (`CancelRequest.note`); false for `Отклонить`, which has no note field. */
  readonly noteEnabled = input(false);
  readonly busy = input(false);
  /** The message key for the last submission's failure, if any — shown inside the dialog rather than closing it. */
  readonly errorKey = input<MessageKey | null>(null);

  readonly confirm = output<OrderReasonSubmission>();
  readonly dismiss = output<void>();

  protected readonly reasonCode = signal('');
  protected readonly note = signal('');
  private readonly touched = signal(false);

  protected readonly reasonMissing = computed(() => this.touched() && this.reasonCode().trim() === '');

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

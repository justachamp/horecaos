import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  input,
  output,
  signal,
} from '@angular/core';

import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { Modal } from '../../shared/ui/modal';

/**
 * One free-text note dialog, shared by `SET_KITCHEN_NOTE`, `SET_COURIER_NOTE`
 * and `SET_INTERNAL_NOTE` (orders.md §3.6, §4.4; ADR 0039, the last two ADR
 * 0113) — the three amendment commands whose entire shape is "a textarea, no
 * reprice, no reservation, no payment, no fiscal consequence, no POS
 * consequence". `order-detail-pane.ts` tells the three apart by which
 * `OrderAmendmentsApi` method it calls on {@link confirm}, not by a prop on
 * this component: the dialog itself has no opinion about which channel it is
 * editing.
 *
 * Modelled on `OrderReasonDialog`, minus the reason field neither of these
 * three commands has.
 */
@Component({
  selector: 'q-order-note-dialog',
  imports: [TPipe, Modal],
  templateUrl: './order-note-dialog.html',
  styleUrl: './order-note-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderNoteDialog {
  readonly titleKey = input.required<MessageKey>();
  readonly initialValue = input('');
  readonly busy = input(false);

  readonly confirm = output<string>();
  readonly dismiss = output<void>();

  protected readonly note = signal(this.initialValue());
  protected readonly charCount = computed(() => this.note().length);
  private lastSeededValue = this.initialValue();

  constructor() {
    // `initialValue` arrives via `componentRef.setInput` (§3.6's dialog is
    // opened, never templated), which lands after this field initializer
    // already seeded `note` from the default — the same delayed-input shape
    // `q-time-input`/`q-color-input`/`q-money-input` all resync for. This is
    // that resync: it fires once the real value is visible and never again
    // once the operator's own edit has moved `note` away from the seed.
    effect(() => {
      const current = this.initialValue();
      if (current === this.lastSeededValue) {
        return;
      }
      this.lastSeededValue = current;
      this.note.set(current);
    });
  }

  protected setNote(value: string): void {
    this.note.set(value.slice(0, 1000));
  }

  protected submit(): void {
    this.confirm.emit(this.note().trim());
  }
}

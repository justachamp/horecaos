import { ChangeDetectionStrategy, Component, effect, input, output, signal } from '@angular/core';

import { TPipe } from '../../core/i18n/t.pipe';
import { MoneyInput } from '../../shared/ui/money-input';
import { Modal } from '../../shared/ui/modal';

/**
 * `Сдача с` (orders.md §3.5, §4.4; ADR 0039, `SET_CASH_TENDERED`) — the amount
 * the customer is expected to hand over, never a payment transaction. A later
 * amendment that pushes the total above this figure raises
 * `CASH_TENDERED_INSUFFICIENT`, which `order-detail-pane.ts` renders as an
 * acknowledgeable notice after this dialog closes, not as a refusal here — the
 * customer can simply hand over more.
 */
@Component({
  selector: 'q-order-cash-tendered-dialog',
  imports: [TPipe, Modal, MoneyInput],
  templateUrl: './order-cash-tendered-dialog.html',
  styleUrl: './order-cash-tendered-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderCashTenderedDialog {
  readonly initialValueMinor = input(0);
  readonly currency = input.required<string>();
  readonly busy = input(false);

  readonly confirm = output<number>();
  readonly dismiss = output<void>();

  protected readonly amountMinor = signal(this.initialValueMinor());
  private lastSeededValue = this.initialValueMinor();

  constructor() {
    // Same delayed-`componentRef.setInput` resync `order-note-dialog.ts` and
    // `q-money-input` itself both carry — see either's doc for why the field
    // initializer alone is not enough.
    effect(() => {
      const current = this.initialValueMinor();
      if (current === this.lastSeededValue) {
        return;
      }
      this.lastSeededValue = current;
      this.amountMinor.set(current);
    });
  }

  protected submit(): void {
    this.confirm.emit(this.amountMinor());
  }
}

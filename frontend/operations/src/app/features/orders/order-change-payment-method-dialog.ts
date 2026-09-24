import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  input,
  output,
  signal,
} from '@angular/core';

import { TPipe } from '../../core/i18n/t.pipe';
import { Modal } from '../../shared/ui/modal';

/**
 * `CHANGE_PAYMENT_METHOD` (ADR 0039, wave 10, rows `1.2c`/`2.1d`) — shared by
 * `order-detail-pane.ts` (row `1.2c`) and `kitchen-queue-page.ts`'s own
 * «Изменить оплату» ticket action (row `2.1d`), so the KDS gets the identical
 * picker rather than a second one.
 *
 * {@link methods} is the caller's own read of the order's channel matrix
 * (`SalesChannelsApi.matrices`, the same source `new-order-page.ts`'s own
 * `loadPaymentMethods` reads) — this dialog has no opinion about which
 * methods exist, only about presenting whichever ones the caller resolved.
 * `CASH` at either end applies directly; anything else this build cannot
 * settle comes back from the server as `PAYMENT_METHOD_CHANGE_REQUIRES_VOID_REFUND`,
 * a plain refusal the caller's own error band renders — this dialog does not
 * predict it, because no read exists that would let it without guessing.
 */
@Component({
  selector: 'q-order-change-payment-method-dialog',
  imports: [TPipe, Modal],
  templateUrl: './order-change-payment-method-dialog.html',
  styleUrl: './order-change-payment-method-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderChangePaymentMethodDialog {
  readonly methods = input.required<readonly string[]>();
  readonly busy = input(false);

  readonly confirm = output<string>();
  readonly dismiss = output<void>();

  protected readonly selected = signal<string | null>(null);
  private lastSeededMethods: readonly string[] = [];

  protected readonly canSubmit = computed(() => this.selected() !== null);

  constructor() {
    effect(() => {
      const methods = this.methods();
      if (methods === this.lastSeededMethods) {
        return;
      }
      this.lastSeededMethods = methods;
      if (this.selected() === null || !methods.includes(this.selected() as string)) {
        this.selected.set(methods[0] ?? null);
      }
    });
  }

  protected select(code: string): void {
    this.selected.set(code);
  }

  protected submit(): void {
    const code = this.selected();
    if (code) {
      this.confirm.emit(code);
    }
  }
}

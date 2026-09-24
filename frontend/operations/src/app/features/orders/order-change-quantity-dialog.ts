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
import { OrderLine } from './order-detail';

export interface QuantitySubmission {
  readonly orderLineId: string;
  readonly quantity: number;
}

/**
 * `CHANGE_LINE_QUANTITY` (ADR 0039, wave 10, row `1.2c`) — increase only.
 * `OrderAmendmentService#repriceFor` refuses `quantity <= line.quantity()`
 * with `QUANTITY_DECREASE_NOT_SUPPORTED` (no ADR 0017 return/write-off
 * primitive exists yet), so this dialog picks a live line from the order's
 * own lines (already loaded — no new read) and only ever offers a quantity
 * strictly above its current one; the stepper's own `min` enforces it
 * client-side, the server enforces it for real.
 *
 * Reprices, so `order-detail-pane.ts` always proposes this with
 * `applyImmediately: false` and follows it with the priced-delta
 * confirmation step (`q-order-amendment-confirm-dialog`) — never assumes it
 * applied.
 */
@Component({
  selector: 'q-order-change-quantity-dialog',
  imports: [TPipe, Modal],
  templateUrl: './order-change-quantity-dialog.html',
  styleUrl: './order-change-quantity-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderChangeQuantityDialog {
  readonly lines = input.required<readonly OrderLine[]>();
  readonly busy = input(false);

  readonly confirm = output<QuantitySubmission>();
  readonly dismiss = output<void>();

  protected readonly selectedLineId = signal<string | null>(null);
  protected readonly quantity = signal(1);
  private lastSeededLines: readonly OrderLine[] = [];

  protected readonly selectedLine = computed(
    () => this.lines().find((line) => line.lineId === this.selectedLineId()) ?? null,
  );

  protected readonly minQuantity = computed(() => (this.selectedLine()?.quantity ?? 0) + 1);

  protected readonly canSubmit = computed(
    () => this.selectedLine() !== null && this.quantity() >= this.minQuantity(),
  );

  constructor() {
    effect(() => {
      const lines = this.lines();
      if (lines === this.lastSeededLines) {
        return;
      }
      this.lastSeededLines = lines;
      const first = lines[0] ?? null;
      this.selectedLineId.set(first?.lineId ?? null);
      this.quantity.set((first?.quantity ?? 0) + 1);
    });
  }

  protected selectLine(lineId: string): void {
    this.selectedLineId.set(lineId);
    const line = this.lines().find((candidate) => candidate.lineId === lineId);
    this.quantity.set((line?.quantity ?? 0) + 1);
  }

  protected setQuantity(value: string): void {
    const parsed = Number.parseInt(value, 10);
    if (Number.isFinite(parsed)) {
      this.quantity.set(parsed);
    }
  }

  protected submit(): void {
    const line = this.selectedLine();
    if (!line || !this.canSubmit()) {
      return;
    }
    this.confirm.emit({ orderLineId: line.lineId, quantity: this.quantity() });
  }
}

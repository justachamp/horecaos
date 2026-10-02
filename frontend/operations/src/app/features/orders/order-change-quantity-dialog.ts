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

/** One thing the operator can raise: a line, or a whole combo counted in combos (ADR 0136). */
export interface QuantityChoice {
  /** The line the amendment is sent against: for a combo, its first component line. */
  readonly lineId: string;
  readonly name: string;
  /** The count the operator reads and raises: the line's quantity, or the number of combos. */
  readonly current: number;
  /** Units on the sent line for one step of {@link current}: one, or a combo's units on that component. */
  readonly unitsPerStep: number;
  readonly isCombo: boolean;
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

  /**
   * What the operator can raise: each ordinary line, and each combo once (ADR 0136). A combo is
   * several lines of one purchase and its quantity is a count of whole combos — the platform refuses
   * a fraction of one — so it is offered as a single choice, counted in combos, and the quantity
   * sent is that count times the units one combo puts on the component line it is sent against.
   */
  protected readonly choices = computed<readonly QuantityChoice[]>(() => {
    const choices: QuantityChoice[] = [];
    const seen = new Set<string>();
    for (const line of this.lines()) {
      const combo = line.combo;
      if (!combo) {
        choices.push({
          lineId: line.lineId,
          name: line.productName,
          current: line.quantity,
          unitsPerStep: 1,
          isCombo: false,
        });
        continue;
      }
      if (seen.has(combo.selectionId)) {
        continue;
      }
      seen.add(combo.selectionId);
      choices.push({
        lineId: line.lineId,
        name: combo.name,
        current: combo.quantity,
        // The component's units for one combo; a line that is not a multiple of the combo count
        // (it cannot be, from checkout) falls back to one so the stepper still moves.
        unitsPerStep:
          combo.quantity > 0 && line.quantity % combo.quantity === 0
            ? line.quantity / combo.quantity
            : 1,
        isCombo: true,
      });
    }
    return choices;
  });

  protected readonly selectedChoice = computed(
    () => this.choices().find((choice) => choice.lineId === this.selectedLineId()) ?? null,
  );

  protected readonly minQuantity = computed(() => (this.selectedChoice()?.current ?? 0) + 1);

  protected readonly canSubmit = computed(
    () => this.selectedChoice() !== null && this.quantity() >= this.minQuantity(),
  );

  constructor() {
    effect(() => {
      const lines = this.lines();
      if (lines === this.lastSeededLines) {
        return;
      }
      this.lastSeededLines = lines;
      const first = this.choices()[0] ?? null;
      this.selectedLineId.set(first?.lineId ?? null);
      this.quantity.set((first?.current ?? 0) + 1);
    });
  }

  protected selectLine(lineId: string): void {
    this.selectedLineId.set(lineId);
    const choice = this.choices().find((candidate) => candidate.lineId === lineId);
    this.quantity.set((choice?.current ?? 0) + 1);
  }

  protected setQuantity(value: string): void {
    const parsed = Number.parseInt(value, 10);
    if (Number.isFinite(parsed)) {
      this.quantity.set(parsed);
    }
  }

  protected submit(): void {
    const choice = this.selectedChoice();
    if (!choice || !this.canSubmit()) {
      return;
    }
    this.confirm.emit({
      orderLineId: choice.lineId,
      quantity: this.quantity() * choice.unitsPerStep,
    });
  }
}

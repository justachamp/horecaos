import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import {
  MAX_LINE_QUANTITY,
  formatQuantity,
  isOrderableQuantity,
  nextQuantityAbove,
} from '../../core/format/quantity';
import { I18n } from '../../core/i18n/i18n';
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
  /**
   * The step the quantity moves by (ADR 0137): the published portion size of a splittable dish,
   * one for everything else, and always one for a combo, which is a count of whole combos.
   */
  readonly step: number;
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
  private readonly i18n = inject(I18n);

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
          step: line.portionSize && line.portionSize > 0 ? line.portionSize : 1,
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
        step: 1,
        isCombo: true,
      });
    }
    return choices;
  });

  protected readonly selectedChoice = computed(
    () => this.choices().find((choice) => choice.lineId === this.selectedLineId()) ?? null,
  );

  /**
   * The smallest quantity strictly above the line's that the dish can be ordered in (ADR 0137).
   * A splittable dish moves by its published portion: `1` goes to `1,5` and `1,5` to `2`. A dish
   * with no portion step takes whole units, so a line that holds `0.5` goes to `1`. A combo's
   * quantity is a count of combos, always whole.
   */
  protected readonly minQuantity = computed(() => {
    const choice = this.selectedChoice();
    return choice ? nextQuantityAbove(choice.current, choice.step) : 1;
  });

  /** Fractions are offered only for a dish that publishes a portion; the rest keep whole units. */
  protected readonly step = computed(() => this.selectedChoice()?.step ?? 1);

  protected readonly splittable = computed(() => this.step() !== 1);

  /** The quantity is above the line's, a whole number of portions, and within what a line holds. */
  protected readonly quantityProblem = computed<'notAPortion' | null>(() => {
    const quantity = this.quantity();
    return Number.isFinite(quantity) &&
      quantity >= this.minQuantity() &&
      quantity <= MAX_LINE_QUANTITY &&
      !isOrderableQuantity(quantity, this.step())
      ? 'notAPortion'
      : null;
  });

  protected readonly canSubmit = computed(
    () =>
      this.selectedChoice() !== null &&
      this.quantity() >= this.minQuantity() &&
      isOrderableQuantity(this.quantity(), this.step()),
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
      this.quantity.set(first ? nextQuantityAbove(first.current, first.step) : 1);
    });
  }

  protected selectLine(lineId: string): void {
    this.selectedLineId.set(lineId);
    const choice = this.choices().find((candidate) => candidate.lineId === lineId);
    this.quantity.set(choice ? nextQuantityAbove(choice.current, choice.step) : 1);
  }

  protected quantityText(quantity: number): string {
    return formatQuantity(quantity, this.i18n.locale());
  }

  protected setQuantity(value: string): void {
    // A number input hands back a dot whatever the keyboard typed. A dish with no portion step
    // keeps reading whole units only, exactly as before quantities were decimal.
    const parsed = this.splittable()
      ? value.trim() === ''
        ? Number.NaN
        : Number(value)
      : Number.parseInt(value, 10);
    if (Number.isFinite(parsed)) {
      this.quantity.set(parsed);
    }
  }

  protected stepText(): string {
    return this.quantityText(this.step());
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

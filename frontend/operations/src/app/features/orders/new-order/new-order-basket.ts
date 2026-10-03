import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';

import { formatWeight } from '../../../core/format/quantity';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { NumberStepper } from '../../../shared/ui/number-stepper';
import { BasketLine } from './new-order-total';

/** A basket line with the two summaries the screen worded for it. */
export interface BasketLineView extends BasketLine {
  /** `LARGE, EXTRA_SHOT×3` — the modifiers the line carries. */
  readonly modifierText: string;
  /** The checked comment presets, in the console's own language. */
  readonly presetText: string;
  /** `Burger, Cola×2` — what a combo line will become on the order; empty on every other line. */
  readonly comboText?: string;
}

/**
 * The basket on the New order screen (§5.5): each line with its quantity
 * stepper, remove, modifier and preset summaries, an unavailability warning and
 * the kitchen note.
 *
 * Presentation only. The lines, their wording and every change belong to the
 * screen (`NewOrderPage`), which re-prices from what it holds. It is its own
 * component so the basket's rules do not count against the screen's
 * component-style budget.
 */
@Component({
  selector: 'q-new-order-basket',
  imports: [NumberStepper, TPipe],
  templateUrl: './new-order-basket.html',
  styleUrl: './new-order-basket.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NewOrderBasket {
  private readonly i18n = inject(I18n);

  readonly lines = input.required<readonly BasketLineView[]>();

  readonly quantityChanged = output<{ lineKey: string; quantity: number }>();
  readonly noteChanged = output<{ lineKey: string; note: string }>();
  readonly removeRequested = output<string>();

  /** The estimated weight of a line sold by weight — every unit at its nominal weight. */
  protected estimatedWeight(line: BasketLineView): string | null {
    return line.catchweight
      ? formatWeight(line.quantity * line.catchweight.nominalGramsPerUnit, this.i18n.locale())
      : null;
  }
}

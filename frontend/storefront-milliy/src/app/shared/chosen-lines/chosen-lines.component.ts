import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { TranslatePipe } from '../translate/translate.pipe';

/** One basket line that carries the guest's own choices, ready to show. */
export interface ChosenLine {
  /** The platform's key for the line: the variant and its exact selection. */
  readonly lineKey: string;
  readonly name: string;
  /** The portion's name and price, when the dish has several. */
  readonly portion: string | null;
  /** What the guest chose, by name, in the dish's own order. */
  readonly options: readonly string[];
  readonly quantity: number;
  /** False when the dish cannot be bought now (or is off the menu): the line can only be lowered. */
  readonly available: boolean;
}

/**
 * The dishes in a table's basket that were ordered with options.
 *
 * A dish's card steppers count only its plain portion (the line whose key is the
 * variant's own), so a line with options -- "Osh, extra meat, no onion" -- has no
 * card to be changed from. This is where the guest sees what they chose and takes
 * it back or changes how many. A line the menu can no longer sell stays here, with
 * a way out only: the platform refuses to price a basket holding an unavailable line,
 * so a line the guest could not remove would stop the whole table ordering.
 *
 * Presentational: it reports the new quantity it was asked for (`quantityChange`,
 * zero removes the line) and the screen writes the basket.
 */
@Component({
  selector: 'app-chosen-lines',
  standalone: true,
  imports: [TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './chosen-lines.component.html',
  styleUrl: './chosen-lines.component.scss',
})
export class ChosenLinesComponent {
  readonly lines = input.required<readonly ChosenLine[]>();
  /** A write to the basket is in flight: the controls wait rather than stack. */
  readonly busy = input(false);
  readonly quantityChange = output<{ lineKey: string; quantity: number }>();

  protected request(line: ChosenLine, quantity: number): void {
    if (!this.busy()) {
      this.quantityChange.emit({ lineKey: line.lineKey, quantity: Math.max(0, quantity) });
    }
  }
}

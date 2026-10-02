import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';

import { formatMoney } from '../../core/format/money';
import { formatQuantity, formatWeight } from '../../core/format/quantity';
import { I18n } from '../../core/i18n/i18n';
import { presetLabelFor } from '../../core/i18n/locale-labels';
import { TPipe } from '../../core/i18n/t.pipe';
import { OrderLine, OrderLineCommentPreset } from './order-detail';

/**
 * The lines table on the order detail pane (§3.4, "Состав"): number, name with
 * its variant, modifiers, comment presets and note, quantity, amount.
 *
 * Presentation only. The lines are the order's snapshot; a line's note is hidden
 * until an operator reveals it (an audited read the pane makes), so the revealed
 * notes and the one being fetched are inputs and the reveal is a request. It is
 * its own component so the table's rules do not count against the pane's
 * component-style budget.
 */
@Component({
  selector: 'q-order-detail-lines',
  imports: [TPipe],
  templateUrl: './order-detail-lines.html',
  styleUrl: './order-detail-lines.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderDetailLines {
  private readonly i18n = inject(I18n);

  readonly lines = input.required<readonly OrderLine[]>();
  readonly currency = input.required<string>();
  /** Per line id: the revealed note, or null when it was revealed and is genuinely empty. Absent = never revealed. */
  readonly revealedNotes = input.required<ReadonlyMap<string, string | null>>();
  /** The line whose note is being fetched right now, if any. */
  readonly revealingNoteFor = input.required<string | null>();

  readonly noteRevealRequested = output<string>();

  protected lineName(line: OrderLine): string {
    return line.productName;
  }

  /** Row 2.1b/10.12: a preset's wording in the console's own language, read from the `labels` map with the triple's columns as the floor (`presetLabelFor`). */
  protected presetLabel(preset: OrderLineCommentPreset): string {
    return presetLabelFor(preset, this.i18n.locale());
  }

  protected revealedNote(lineId: string): string | null | undefined {
    // undefined = never revealed this load; null = revealed and genuinely empty.
    return this.revealedNotes().get(lineId);
  }

  protected isRevealingNote(lineId: string): boolean {
    return this.revealingNoteFor() === lineId;
  }

  /** `0,5`, `2` — never `2.000`. */
  protected quantityText(quantity: number): string {
    return formatQuantity(quantity, this.i18n.locale());
  }

  /** The estimated weight of the whole line: every unit at its nominal weight. */
  protected estimatedWeight(line: OrderLine): string {
    return formatWeight(
      line.quantity * (line.catchweight?.nominalGramsPerUnit ?? 0),
      this.i18n.locale(),
    );
  }

  protected weightText(grams: number): string {
    return formatWeight(grams, this.i18n.locale());
  }

  protected formatMoneyMinor(amountMinor: number, currency: string): string {
    return formatMoney({ amountMinor, currency }, this.i18n.locale(), { withUnit: true });
  }
}

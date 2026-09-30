import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';

import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
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

  protected presetLabel(preset: OrderLineCommentPreset): string {
    switch (this.i18n.locale()) {
      case 'ru':
        return preset.labelRu;
      case 'uz-Latn':
        return preset.labelUz;
      default:
        return preset.labelEn;
    }
  }

  protected revealedNote(lineId: string): string | null | undefined {
    // undefined = never revealed this load; null = revealed and genuinely empty.
    return this.revealedNotes().get(lineId);
  }

  protected isRevealingNote(lineId: string): boolean {
    return this.revealingNoteFor() === lineId;
  }

  protected formatMoneyMinor(amountMinor: number, currency: string): string {
    return formatMoney({ amountMinor, currency }, this.i18n.locale(), { withUnit: true });
  }
}

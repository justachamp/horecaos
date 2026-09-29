import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';

import { I18n } from '../../../core/i18n/i18n';

/**
 * One physical table an order was seated at — `OrderTablesPort.TableRef` on the
 * wire (`dinein.api`, ADR 0047). Codes and display names only: no guest, no
 * party size, no bill (ADR 0029).
 */
export interface OrderTableRef {
  readonly tableId: string;
  /** The stable code printed on the table's QR card, e.g. `T7`. */
  readonly code: string;
  readonly displayName: string;
}

/**
 * The seated party an order belongs to — `OrderTablesPort.OrderTable` on the
 * wire, carried as `table` on the order board row, the order detail summary and
 * the kitchen ticket. More than one table is a party pushed together for a
 * large group, in the order the tables were joined.
 */
export interface OrderTableView {
  readonly sessionId: string;
  readonly tables: readonly OrderTableRef[];
}

/**
 * The codes of a table view, joined for a chip: `T7` or `T7 + T8`. Null for an
 * order with no table (a delivery, a pickup, or a DINE_IN order nobody seated),
 * so a caller can `@if` on the result.
 */
export function orderTableCodes(table: OrderTableView | null | undefined): string | null {
  if (!table || table.tables.length === 0) {
    return null;
  }
  return table.tables.map((ref) => ref.code).join(' + ');
}

/**
 * The table beside an order — `q-order-table-chip` (batch 14, gap map rows
 * `1.1`/`2.1`'s dine-in visibility).
 *
 * Renders nothing at all for an order with no table, rather than an empty
 * pill: the board's Type cell, the detail header and the kitchen ticket all
 * embed this unconditionally, and a delivery order must look exactly as it did
 * before. The table's display names (which can be longer than a code) sit in
 * the `title`, for a hover; the visible text is the short code, because that is
 * what a runner reads off the card on the table.
 *
 * Text only — the console's flat-square palette has no icon glyphs.
 */
@Component({
  selector: 'q-order-table-chip',
  templateUrl: './order-table-chip.html',
  styleUrl: './order-table-chip.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderTableChip {
  private readonly i18n = inject(I18n);

  readonly table = input<OrderTableView | null | undefined>(null);

  protected readonly label = computed(() => {
    const codes = orderTableCodes(this.table());
    return codes === null ? null : this.i18n.t('orders.table.chip', { tables: codes });
  });

  protected readonly names = computed(() =>
    (this.table()?.tables ?? []).map((ref) => ref.displayName).join(', '),
  );
}

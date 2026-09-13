import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';

import { TPipe } from '../../../core/i18n/t.pipe';
import {
  MatrixBulkToggleEvent,
  MatrixCell,
  MatrixCellChange,
  MatrixCellState,
  MatrixHeader,
} from './matrix-grid-types';

interface Position {
  readonly ri: number;
  readonly ci: number;
}

/**
 * `X.7` — an editable cross-tab: channels × payment methods, channels ×
 * fulfilment modes, or any other row-by-column boolean plane. Turning cash
 * off for six channels was "six trips through a row picker" before this;
 * a row or column header toggles every eligible cell in it at once, and a
 * shift-click applies one cell's target state across a rectangular range.
 *
 * The grid computes and emits the *desired* change; it never writes
 * anything itself. `UNAVAILABLE` cells (a payment method a channel cannot
 * fiscalise) render hatched and refuse every gesture that would touch them
 * — a click, a row toggle, a column toggle, and a range that crosses one.
 */
@Component({
  selector: 'q-matrix-grid',
  imports: [TPipe],
  templateUrl: './matrix-grid.html',
  styleUrl: './matrix-grid.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MatrixGrid {
  readonly rowHeaders = input.required<readonly MatrixHeader[]>();
  readonly columnHeaders = input.required<readonly MatrixHeader[]>();
  readonly cells = input.required<readonly MatrixCell[]>();
  /** Refuses every gesture without hiding the grid — for a host page mid-save. */
  readonly disabled = input(false);

  readonly toggle = output<MatrixBulkToggleEvent>();

  protected readonly selectionAnchor = signal<Position | null>(null);

  protected readonly gridTemplateColumns = computed(
    () =>
      `160px ${this.columnHeaders()
        .map(() => 'minmax(64px, 1fr)')
        .join(' ')}`,
  );

  private readonly cellIndex = computed(() => {
    const map = new Map<string, MatrixCellState>();
    for (const cell of this.cells()) {
      map.set(this.key(cell.rowId, cell.colId), cell.state);
    }
    return map;
  });

  protected stateOf(rowId: string, colId: string): MatrixCellState {
    return this.cellIndex().get(this.key(rowId, colId)) ?? 'OFF';
  }

  protected isSelected(ri: number, ci: number): boolean {
    const anchor = this.selectionAnchor();
    return anchor !== null && anchor.ri === ri && anchor.ci === ci;
  }

  protected trackById = (_: number, item: MatrixHeader): string => item.id;

  protected onCellClick(ri: number, ci: number, event: MouseEvent): void {
    if (this.disabled()) {
      return;
    }
    const anchor = this.selectionAnchor();
    if (event.shiftKey && anchor) {
      // The focus cell may itself be unavailable — that excludes it from the
      // range (see applyRange), it does not refuse the whole gesture.
      this.applyRange(anchor, { ri, ci });
      return;
    }
    const row = this.rowHeaders()[ri];
    const col = this.columnHeaders()[ci];
    if (this.stateOf(row.id, col.id) === 'UNAVAILABLE') {
      return;
    }
    const nextState = this.stateOf(row.id, col.id) === 'ON' ? 'OFF' : 'ON';
    this.selectionAnchor.set({ ri, ci });
    this.toggle.emit({ changes: [{ rowId: row.id, colId: col.id, nextState }] });
  }

  protected toggleRow(row: MatrixHeader): void {
    if (this.disabled()) {
      return;
    }
    const eligible = this.columnHeaders().filter(
      (col) => this.stateOf(row.id, col.id) !== 'UNAVAILABLE',
    );
    if (eligible.length === 0) {
      return;
    }
    const nextState = eligible.some((col) => this.stateOf(row.id, col.id) === 'OFF') ? 'ON' : 'OFF';
    this.toggle.emit({
      changes: eligible.map((col) => ({ rowId: row.id, colId: col.id, nextState })),
    });
  }

  protected toggleColumn(col: MatrixHeader): void {
    if (this.disabled()) {
      return;
    }
    const eligible = this.rowHeaders().filter(
      (row) => this.stateOf(row.id, col.id) !== 'UNAVAILABLE',
    );
    if (eligible.length === 0) {
      return;
    }
    const nextState = eligible.some((row) => this.stateOf(row.id, col.id) === 'OFF') ? 'ON' : 'OFF';
    this.toggle.emit({
      changes: eligible.map((row) => ({ rowId: row.id, colId: col.id, nextState })),
    });
  }

  /**
   * The anchor cell's own target state (what a lone click on it would
   * become) is what the whole rectangle is set to — the same "propagate the
   * first cell's value" idea `q-data-grid`'s fill-down uses, applied to a
   * two-dimensional range instead of a column.
   */
  private applyRange(anchor: Position, focus: Position): void {
    const anchorRow = this.rowHeaders()[anchor.ri];
    const anchorCol = this.columnHeaders()[anchor.ci];
    if (this.stateOf(anchorRow.id, anchorCol.id) === 'UNAVAILABLE') {
      return;
    }
    const nextState = this.stateOf(anchorRow.id, anchorCol.id) === 'ON' ? 'OFF' : 'ON';
    const riTop = Math.min(anchor.ri, focus.ri);
    const riBottom = Math.max(anchor.ri, focus.ri);
    const ciLeft = Math.min(anchor.ci, focus.ci);
    const ciRight = Math.max(anchor.ci, focus.ci);

    const changes: MatrixCellChange[] = [];
    for (let ri = riTop; ri <= riBottom; ri++) {
      for (let ci = ciLeft; ci <= ciRight; ci++) {
        const row = this.rowHeaders()[ri];
        const col = this.columnHeaders()[ci];
        if (this.stateOf(row.id, col.id) === 'UNAVAILABLE') {
          continue; // A range that crosses an unavailable cell skips it, not the whole gesture.
        }
        changes.push({ rowId: row.id, colId: col.id, nextState });
      }
    }
    this.selectionAnchor.set(focus);
    if (changes.length > 0) {
      this.toggle.emit({ changes });
    }
  }

  private key(rowId: string, colId: string): string {
    return `${rowId}::${colId}`;
  }
}

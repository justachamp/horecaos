import { NgTemplateOutlet } from '@angular/common';
import { ScrollingModule } from '@angular/cdk/scrolling';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { TPipe } from '../../../core/i18n/t.pipe';
import {
  DataGridBatchResult,
  DataGridColumn,
  DataGridRowEdit,
  DataGridSaveFn,
} from './data-grid-types';

interface Cell {
  readonly ri: number;
  readonly ci: number;
}

/** Rows at or under this count render as a plain list; the CDK viewport only earns its keep past it. */
const VIRTUALIZE_THRESHOLD = 150;
const ROW_HEIGHT_PX = 36;
/** Generous on purpose: a jsdom test viewport reports zero height, and a large buffer still renders a small fixture's rows. */
const VIRTUAL_MIN_BUFFER_PX = 800;
const VIRTUAL_MAX_BUFFER_PX = 1200;

const KEY_PART_SEPARATOR = ':';

/**
 * `X.6` — a spreadsheet-shaped edit surface for bulk price, availability and
 * fiscal-code changes: inline cell edit, fill-down over a selected range,
 * cell-to-cell keyboard navigation, virtualization past {@link VIRTUALIZE_THRESHOLD}
 * rows, and a batched save that reports one outcome per row.
 *
 * An ARIA `grid`, not a `<table>` — `cdk-virtual-scroll-viewport` cannot sit
 * between `<thead>`/`<tbody>` without the browser hoisting it out of the
 * table during parsing, and a spreadsheet-like surface (Sheets, Excel
 * Online) is exactly the case the ARIA grid pattern exists for.
 *
 * The save itself is delegated entirely to {@link saveFn} — this component
 * mints no request and knows no capability. Model it on the platform's own
 * bulk-write contract: `POST .../orders/bulk-actions`
 * (`OperationsOrderController.java:736`) applies N independent commands,
 * never one all-or-nothing transaction, and always answers with a per-row
 * outcome. A row whose outcome is `FAILED` keeps its pending edit and its
 * problem code so the operator can see and retry it; a row that is
 * `APPLIED` has its pending edit cleared — one bad row in a batch of two
 * hundred never rolls back or hides the other hundred and ninety-nine.
 */
@Component({
  selector: 'q-data-grid',
  imports: [NgTemplateOutlet, ScrollingModule, TPipe],
  templateUrl: './data-grid.html',
  styleUrl: './data-grid.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DataGrid<T> {
  private readonly destroyRef = inject(DestroyRef);

  readonly columns = input.required<readonly DataGridColumn<T>[]>();
  readonly rows = input.required<readonly T[]>();
  readonly rowId = input.required<(row: T) => string>();
  readonly saveFn = input<DataGridSaveFn | null>(null);

  readonly saved = output<DataGridBatchResult>();

  protected readonly ROW_HEIGHT = ROW_HEIGHT_PX;
  protected readonly VIRTUAL_MIN_BUFFER = VIRTUAL_MIN_BUFFER_PX;
  protected readonly VIRTUAL_MAX_BUFFER = VIRTUAL_MAX_BUFFER_PX;

  protected readonly pending = signal<ReadonlyMap<string, string>>(new Map());
  protected readonly rowErrors = signal<ReadonlyMap<string, string | null>>(new Map());
  protected readonly activeCell = signal<Cell | null>(null);
  protected readonly selectionAnchor = signal<Cell | null>(null);
  protected readonly editingCell = signal<Cell | null>(null);
  protected readonly editValue = signal('');
  protected readonly saving = signal(false);

  protected readonly virtualized = computed(() => this.rows().length > VIRTUALIZE_THRESHOLD);
  protected readonly dirtyCount = computed(() => this.pendingRowEdits().length);
  protected readonly gridTemplateColumns = computed(() =>
    this.columns()
      .map(() => 'minmax(120px, 1fr)')
      .join(' '),
  );

  protected rowIdOf = (row: T): string => this.rowId()(row);
  protected trackByRowId = (_: number, row: T): string => this.rowIdOf(row);

  // ------------------------------------------------------------ cell state

  protected isActive(ri: number, ci: number): boolean {
    const active = this.activeCell();
    return active?.ri === ri && active.ci === ci;
  }

  protected isEditing(ri: number, ci: number): boolean {
    const editing = this.editingCell();
    return editing?.ri === ri && editing.ci === ci;
  }

  protected isInSelection(ri: number, ci: number): boolean {
    const anchor = this.selectionAnchor();
    const active = this.activeCell();
    if (!anchor || !active || anchor.ci !== ci || active.ci !== ci) {
      return false;
    }
    const top = Math.min(anchor.ri, active.ri);
    const bottom = Math.max(anchor.ri, active.ri);
    return ri >= top && ri <= bottom;
  }

  protected isDirtyCell(row: T, col: DataGridColumn<T>): boolean {
    return this.pending().has(this.cellKey(this.rowIdOf(row), col.key));
  }

  protected rowError(row: T): string | null {
    return this.rowErrors().get(this.rowIdOf(row)) ?? null;
  }

  protected displayValue(row: T, col: DataGridColumn<T>): string {
    const pendingValue = this.pending().get(this.cellKey(this.rowIdOf(row), col.key));
    return pendingValue ?? col.getValue(row);
  }

  // ------------------------------------------------------------ interaction

  protected onCellMouseDown(ri: number, ci: number, event: MouseEvent): void {
    if (event.shiftKey) {
      if (!this.selectionAnchor()) {
        this.selectionAnchor.set(this.activeCell() ?? { ri, ci });
      }
      this.activeCell.set({ ri, ci });
      return;
    }
    if (this.editingCell()) {
      this.commitEdit();
    }
    this.activeCell.set({ ri, ci });
    this.selectionAnchor.set({ ri, ci });
  }

  protected onCellDblClick(ri: number, ci: number): void {
    this.startEdit(ri, ci);
  }

  protected startEdit(ri: number, ci: number): void {
    const col = this.columns()[ci];
    const row = this.rows()[ri];
    if (!col?.editable || !row) {
      return;
    }
    this.activeCell.set({ ri, ci });
    this.editingCell.set({ ri, ci });
    this.editValue.set(this.displayValue(row, col));
  }

  protected onEditInput(value: string): void {
    this.editValue.set(value);
  }

  protected commitEdit(): void {
    const cell = this.editingCell();
    if (!cell) {
      return;
    }
    const row = this.rows()[cell.ri];
    const col = this.columns()[cell.ci];
    if (!row || !col) {
      this.editingCell.set(null);
      return;
    }
    const next = new Map(this.pending());
    next.set(this.cellKey(this.rowIdOf(row), col.key), this.editValue());
    this.pending.set(next);
    this.editingCell.set(null);
  }

  protected cancelEdit(): void {
    this.editingCell.set(null);
  }

  protected onKeydown(event: KeyboardEvent): void {
    const active = this.activeCell();
    if (!active) {
      return;
    }
    const editing = this.editingCell() !== null;

    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        if (editing) {
          this.commitEdit();
        }
        this.moveActive(active, 1, 0, event.shiftKey);
        return;
      case 'ArrowUp':
        event.preventDefault();
        if (editing) {
          this.commitEdit();
        }
        this.moveActive(active, -1, 0, event.shiftKey);
        return;
      case 'ArrowLeft':
        if (editing) {
          return; // Let the caret move inside the input.
        }
        event.preventDefault();
        this.moveActive(active, 0, -1, event.shiftKey);
        return;
      case 'ArrowRight':
        if (editing) {
          return;
        }
        event.preventDefault();
        this.moveActive(active, 0, 1, event.shiftKey);
        return;
      case 'Enter':
        event.preventDefault();
        if (editing) {
          this.commitEdit();
          this.moveActive(active, 1, 0, false);
        } else {
          this.startEdit(active.ri, active.ci);
        }
        return;
      case 'Escape':
        if (editing) {
          event.preventDefault();
          this.cancelEdit();
        }
        return;
      default:
        if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'd') {
          event.preventDefault();
          this.fillDown();
        }
    }
  }

  private moveActive(from: Cell, dr: number, dc: number, extend: boolean): void {
    const maxRow = this.rows().length - 1;
    const maxCol = this.columns().length - 1;
    const next: Cell = {
      ri: clamp(from.ri + dr, 0, maxRow),
      ci: clamp(from.ci + dc, 0, maxCol),
    };
    this.activeCell.set(next);
    if (!extend) {
      this.selectionAnchor.set(next);
    } else if (!this.selectionAnchor()) {
      this.selectionAnchor.set(from);
    }
  }

  /**
   * Copies the topmost selected row's value (pending, if it has one, else
   * its source value) down through the rest of the selected range, all
   * within one column — a spreadsheet fill-down, and the bulk-repricing
   * motion `X.6`'s own justification names ("re-price a menu in one pass").
   */
  protected fillDown(): void {
    const anchor = this.selectionAnchor();
    const active = this.activeCell();
    if (!anchor || !active || anchor.ci !== active.ci) {
      return;
    }
    const ci = anchor.ci;
    const col = this.columns()[ci];
    if (!col?.editable) {
      return;
    }
    const top = Math.min(anchor.ri, active.ri);
    const bottom = Math.max(anchor.ri, active.ri);
    if (top === bottom) {
      return;
    }
    const sourceRow = this.rows()[top];
    const sourceValue = this.displayValue(sourceRow, col);
    const next = new Map(this.pending());
    for (let ri = top + 1; ri <= bottom; ri++) {
      const row = this.rows()[ri];
      next.set(this.cellKey(this.rowIdOf(row), col.key), sourceValue);
    }
    this.pending.set(next);
  }

  // ----------------------------------------------------------- batched save

  protected save(): void {
    const fn = this.saveFn();
    const edits = this.pendingRowEdits();
    if (!fn || edits.length === 0) {
      return;
    }
    this.saving.set(true);
    fn(edits)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          const appliedIds = new Set(
            result.items.filter((item) => item.status === 'APPLIED').map((item) => item.rowId),
          );
          const failed = new Map(
            result.items
              .filter((item) => item.status === 'FAILED')
              .map((item) => [item.rowId, item.problemCode ?? null] as const),
          );
          this.pending.update((current) => {
            const next = new Map(current);
            for (const key of [...next.keys()]) {
              if (appliedIds.has(this.rowIdFromKey(key))) {
                next.delete(key);
              }
            }
            return next;
          });
          this.rowErrors.set(failed);
          this.saving.set(false);
          this.saved.emit(result);
        },
        error: () => {
          this.saving.set(false);
        },
      });
  }

  protected discard(): void {
    this.pending.set(new Map());
    this.rowErrors.set(new Map());
    this.editingCell.set(null);
  }

  private pendingRowEdits(): readonly DataGridRowEdit[] {
    const byRow = new Map<string, Record<string, string>>();
    for (const [key, value] of this.pending()) {
      const [rowId, colKey] = this.splitKey(key);
      const changes = byRow.get(rowId) ?? {};
      changes[colKey] = value;
      byRow.set(rowId, changes);
    }
    return [...byRow.entries()].map(([rowId, changes]) => ({ rowId, changes }));
  }

  private cellKey(rowId: string, colKey: string): string {
    return `${rowId}${KEY_PART_SEPARATOR}${colKey}`;
  }

  private splitKey(key: string): readonly [string, string] {
    const separatorIndex = key.indexOf(KEY_PART_SEPARATOR);
    return [key.slice(0, separatorIndex), key.slice(separatorIndex + 1)];
  }

  private rowIdFromKey(key: string): string {
    return this.splitKey(key)[0];
  }
}

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value));
}

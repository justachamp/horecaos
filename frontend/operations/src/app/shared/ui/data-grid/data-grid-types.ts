import { Observable } from 'rxjs';

/** One editable-or-not column. Values are strings end to end — money/number columns parse on save, like every form field in this app. */
export interface DataGridColumn<T> {
  readonly key: string;
  readonly header: string;
  readonly editable?: boolean;
  readonly numeric?: boolean;
  readonly getValue: (row: T) => string;
}

/** Every cell a batch touches for one row, keyed by column. */
export interface DataGridRowEdit {
  readonly rowId: string;
  readonly changes: Readonly<Record<string, string>>;
}

export type DataGridRowStatus = 'APPLIED' | 'FAILED';

/** Mirrors `OperationsOrderController.BulkActionItemResponse` — the shape every batched grid save should follow. */
export interface DataGridRowOutcome {
  readonly rowId: string;
  readonly status: DataGridRowStatus;
  readonly problemCode?: string | null;
}

export interface DataGridBatchResult {
  readonly items: readonly DataGridRowOutcome[];
}

/**
 * The host page's own bulk-write call — same discipline as
 * `POST .../orders/bulk-actions`: N independent commands, never one
 * all-or-nothing transaction, so one already-changed row cannot fail every
 * other row's save. The grid has no opinion on which endpoint or capability
 * that is; it only reports what came back, per row.
 */
export type DataGridSaveFn = (edits: readonly DataGridRowEdit[]) => Observable<DataGridBatchResult>;

export type MatrixCellState = 'ON' | 'OFF' | 'UNAVAILABLE';

export interface MatrixHeader {
  readonly id: string;
  readonly label: string;
}

export interface MatrixCell {
  readonly rowId: string;
  readonly colId: string;
  readonly state: MatrixCellState;
}

/** One cell's desired next state — the grid computes this; the host page is the one that writes it. */
export interface MatrixCellChange {
  readonly rowId: string;
  readonly colId: string;
  readonly nextState: 'ON' | 'OFF';
}

/**
 * Everything one gesture (a single click, a row/column toggle, or a
 * shift-range apply) wants to change, as one event — a single cell's click
 * is a `changes` array of length one, so the host page needs only one
 * handler.
 */
export interface MatrixBulkToggleEvent {
  readonly changes: readonly MatrixCellChange[];
}

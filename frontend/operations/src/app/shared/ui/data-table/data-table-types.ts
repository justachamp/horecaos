/** One column of a {@link DataTable}. Cell content is supplied by a `qCell` template, not here. */
export interface DataTableColumn {
  readonly key: string;
  readonly header: string;
  /** Column-chooser default. A column with `hideable: false` never appears in the chooser. */
  readonly hideable?: boolean;
  readonly numeric?: boolean;
}

/** One entry of the row action menu (`P01`'s `q-action-menu` is the eventual host; this is the contract it will read). */
export interface RowAction<T> {
  readonly id: string;
  readonly label: string;
  readonly disabled?: (row: T) => boolean;
  /** Rendered in a destructive tone (the archive/cancel/delete slot). */
  readonly destructive?: boolean;
}

/** One entry of the bulk-action bar shown while a selection is non-empty. */
export interface BulkAction {
  readonly id: string;
  readonly label: string;
  readonly destructive?: boolean;
}

export interface RowActionEvent<T> {
  readonly actionId: string;
  readonly row: T;
}

export interface BulkActionEvent {
  readonly actionId: string;
  readonly rowIds: readonly string[];
}

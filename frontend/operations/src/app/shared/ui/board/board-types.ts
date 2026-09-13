/**
 * One column of a {@link Board} — `q-board-column`'s own input shape,
 * kept as plain data so a host page can build the list from whatever its
 * domain calls a column (a courier, a table section, a shift) without this
 * file knowing which.
 *
 * `current`/`capacity` are the column's own load, shown in its header —
 * `activeAssignments`/`concurrencyCeiling` for a courier column, both
 * `undefined` for a pool column with no ceiling (dispatch's "Unassigned").
 * `capacity: null` means "no ceiling, but still count the load" (shown as
 * bare `current`); `capacity` left `undefined` hides the badge entirely.
 */
export interface BoardColumnDef<TData = unknown> {
  readonly columnId: string;
  readonly titleText: string;
  readonly current?: number;
  readonly capacity?: number | null;
  readonly data?: TData;
}

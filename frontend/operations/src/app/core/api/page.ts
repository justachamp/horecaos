/**
 * Cursor pagination (ADR 0031), mirroring `uz.qoida.platform.web.api.Page`.
 *
 * There is no total and no page number, and there will not be one. Offsets skip
 * and duplicate rows in a collection that changes while you read it, and in an
 * order feed a skipped row is an order nobody cooked.
 */
export interface Page<T> {
  readonly items: readonly T[];
  /** `null` means the end of the collection, not "unknown". */
  readonly nextCursor: string | null;
}

/** The server's documented default and ceiling for `limit`. */
export const PAGE_DEFAULT_LIMIT = 50;
export const PAGE_MAXIMUM_LIMIT = 200;

/**
 * A position in a cursor iteration.
 *
 * Cursors are opaque and signed, and they encode the sort key *and* the filter
 * set. Changing a filter mid-iteration therefore fails rather than returning
 * incoherent pages — which means a client must drop its cursor whenever it
 * changes a filter. {@link resetOnFilterChange} is the shape that makes that
 * hard to forget.
 */
export interface CursorState {
  readonly cursor: string | null;
  readonly limit: number;
}

export function firstPage(limit: number = PAGE_DEFAULT_LIMIT): CursorState {
  if (limit < 1 || limit > PAGE_MAXIMUM_LIMIT) {
    throw new RangeError(`limit must be between 1 and ${PAGE_MAXIMUM_LIMIT}, got ${limit}`);
  }
  return { cursor: null, limit };
}

export function nextPage<T>(state: CursorState, page: Page<T>): CursorState | null {
  return page.nextCursor === null ? null : { ...state, cursor: page.nextCursor };
}

/**
 * Drops the cursor while keeping the page size. Call this from every filter
 * change; continuing with a cursor minted under different filters is the one
 * misuse the signature is designed to make visible at the call site.
 */
export function resetOnFilterChange(state: CursorState): CursorState {
  return { ...state, cursor: null };
}

/** The query parameters for a cursor request, omitting a null cursor entirely. */
export function pageParams(state: CursorState): Record<string, string> {
  return state.cursor === null
    ? { limit: String(state.limit) }
    : { cursor: state.cursor, limit: String(state.limit) };
}

/**
 * Cursor pagination (ADR 0031).
 *
 * There is no total and no page number, and adding either client-side is not
 * possible: the server does not compute a total over a mutable collection
 * because it costs a second scan and is stale by the time it arrives. Screens
 * are designed around continuation — "load more", not "page 7 of 92".
 *
 * A null `nextCursor` means the end. An empty `items` array with a non-null
 * cursor is legal and means "nothing matched this page, keep going": a filter
 * applied after the index scan can empty a page without ending the collection.
 */
export interface Page<T> {
  readonly items: readonly T[];
  readonly nextCursor: string | null;
}

/** Mirrors uz.qoida.platform.web.api.Page. The server clamps to the maximum. */
export const DEFAULT_PAGE_LIMIT = 50;
export const MAXIMUM_PAGE_LIMIT = 200;

export interface PageQuery {
  /**
   * Opaque and signed. Never constructed, parsed, or stored by a client: it
   * encodes the sort key and the filter set, so a cursor carried across a
   * filter change is rejected rather than silently returning incoherent pages.
   */
  readonly cursor?: string | null;
  readonly limit?: number;
}

export function isLastPage<T>(page: Page<T>): boolean {
  return page.nextCursor === null;
}

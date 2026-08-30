/**
 * A cursor-paginated response (ADR 0031).
 *
 * There is no total and no page number, and there will not be one: the server
 * refuses to compute a total over a mutable collection. A screen built around
 * "page 7 of 92" cannot be built against this API, which is the point.
 */
export interface Page<T> {
  readonly items: readonly T[];
  /** `null` means the end of the collection. */
  readonly nextCursor: string | null;
}

export interface PageRequest {
  readonly cursor?: string | null;
  readonly limit?: number;
}

/**
 * Walks every page of a collection.
 *
 * A cursor encodes the sort key and the filter set, so the filters must not
 * change between calls — the server rejects a cursor whose filters moved rather
 * than returning an incoherent page.
 *
 * @param maxPages a stop so a server bug cannot spin a phone's battery flat.
 */
export async function collectPages<T>(
  fetchPage: (cursor: string | null) => Promise<Page<T>>,
  maxPages = 50,
): Promise<T[]> {
  const collected: T[] = [];
  let cursor: string | null = null;

  for (let page = 0; page < maxPages; page++) {
    const result: Page<T> = await fetchPage(cursor);
    collected.push(...result.items);
    if (!result.nextCursor) {
      return collected;
    }
    cursor = result.nextCursor;
  }

  throw new Error(`Cursor pagination did not terminate within ${maxPages} pages`);
}

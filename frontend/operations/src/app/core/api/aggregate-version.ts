/**
 * Optimistic concurrency over HTTP (ADR 0031), mirroring
 * `uz.qoida.platform.web.api.AggregateVersion`.
 *
 * The server renders a version as a **weak** ETag — `W/"7"` — because two
 * responses at the same version are semantically equivalent without being
 * byte-identical. A client that compares ETag strings rather than parsing the
 * version out of them will eventually be defeated by a field-ordering change,
 * so this module always goes through the integer.
 *
 * Why this matters on this console specifically: two operators can be looking at
 * the same order on two screens. Sending the version with the mutation is what
 * makes the second one lose loudly with STALE_VERSION, instead of both of them
 * winning and the order ending up in whichever state arrived last.
 */

/** Renders a version as the weak validator the server expects in `If-Match`. */
export function toETag(version: number): string {
  if (!Number.isInteger(version) || version < 0) {
    throw new RangeError(`Version must be a non-negative integer, got ${version}`);
  }
  return `W/"${version}"`;
}

/**
 * Reads the version out of an `ETag` header value.
 *
 * Returns null when the header is absent. Throws when it is present but
 * unparseable, for the same reason the server rejects a malformed `If-Match`:
 * treating an unreadable validator as no validator silently disables the check
 * it exists to perform.
 */
export function parseETag(header: string | null | undefined): number | null {
  if (header === null || header === undefined || header.trim() === '') {
    return null;
  }
  let value = header.trim();
  if (value.startsWith('W/')) {
    value = value.slice(2);
  }
  value = value.replaceAll('"', '').trim();

  // Deliberately not Number(): Number('') is 0 and Number(' 7 ') is 7, both of
  // which would turn a malformed header into a plausible-looking version.
  if (!/^\d+$/.test(value)) {
    throw new Error(`Unparseable ETag: ${header}`);
  }
  return Number.parseInt(value, 10);
}

/**
 * An aggregate read together with the version it was read at.
 *
 * Carrying the two together is the point. A component that holds only the body
 * has to find the version again at write time, and the version it finds is the
 * one from whichever read happened most recently — which is exactly the race
 * this mechanism exists to catch.
 */
export interface Versioned<T> {
  readonly value: T;
  /** Null when the endpoint does not version its representation. */
  readonly version: number | null;
}

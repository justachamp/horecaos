/**
 * Client-generated idempotency keys (ADR 0031).
 *
 * The key must be **stable across retries of one logical operation** and
 * different between two operations that merely look alike. That is why the
 * server refuses to derive it from a hash of the request: two legitimately
 * different carts can normalise to the same bytes, and a retry with a trivial
 * difference would create a second order.
 *
 * So: one key per user intent, generated when the intent is formed, reused for
 * every retry of it. `ApiClient.mutate` generates one per call and accepts one
 * explicitly for the case where a person presses "try again".
 */
export function newIdempotencyKey(): string {
  const cryptoApi = globalThis.crypto;
  if (cryptoApi?.randomUUID) {
    return cryptoApi.randomUUID();
  }
  // getRandomValues is present in every WebView that matters; randomUUID is not
  // (it needs a secure context, and some in-app browsers do not report one).
  const bytes = new Uint8Array(16);
  cryptoApi.getRandomValues(bytes);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/** A correlation identifier the platform echoes into logs, traces and the outbox. */
export function newCorrelationId(): string {
  return newIdempotencyKey();
}

/**
 * Client-generated idempotency keys (ADR 0031).
 *
 * One key per user intent, generated when the intent is formed and reused for every retry of it:
 * a courier whose connection drops after tapping "picked up" and who taps again must replay the
 * first answer, not perform the step twice. `ApiClient.post` generates one per call and accepts
 * one explicitly for the case where the same intent is being retried.
 */
export function newIdempotencyKey(): string {
  const cryptoApi = globalThis.crypto;
  if (typeof cryptoApi.randomUUID === 'function') {
    return cryptoApi.randomUUID();
  }
  // randomUUID needs a secure context and some in-app browsers do not report one.
  const bytes = new Uint8Array(16);
  cryptoApi.getRandomValues(bytes);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/**
 * A correlation identifier the platform echoes into logs and traces. A random UUID: it must be
 * traceable and must not identify a person, so it is never derived from a courier or a device
 * (ADR 0029).
 */
export function newCorrelationId(): string {
  return newIdempotencyKey();
}

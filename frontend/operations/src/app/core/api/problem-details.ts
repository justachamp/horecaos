/**
 * RFC 9457 Problem Details, in the one shape every Qoida surface returns
 * (ADR 0031).
 *
 * The server builds these in `uz.qoida.platform.web.api.ApiProblem`. The fields
 * below are the ones it actually sets, not the ones RFC 9457 permits.
 */
export interface ProblemDetails {
  /** Absolute URI naming the problem kind. Human documentation, not a branch. */
  readonly type?: string;
  /** Short human summary. Written for a developer, and not translated. */
  readonly title?: string;
  readonly status: number;
  /**
   * Developer-facing explanation. Never rendered to an operator: it is English,
   * untranslated, and written for whoever reads the response.
   */
  readonly detail?: string;
  readonly instance?: string;
  /** The stable identifier clients branch on. See {@link ApiErrorCode}. */
  readonly code?: string;
  /** Echoed from `X-Correlation-Id`; safe to show, safe to log, carries no PII. */
  readonly correlationId?: string;
  readonly errors?: readonly ProblemFieldError[];
  /**
   * Codes carry extra properties: STALE_VERSION carries `expected` and `actual`,
   * a refused transition carries `from` and `to`. Typed loosely because the set
   * is per-code and adding one is an additive, non-breaking server change.
   */
  readonly [property: string]: unknown;
}

/** A field-level validation failure with a stable code, not a prose message. */
export interface ProblemFieldError {
  readonly field: string;
  readonly code: string;
  readonly message?: string;
}

/**
 * The server's error code registry, mirrored from
 * `uz.qoida.platform.web.api.ErrorCode`.
 *
 * This is a `const` object rather than a TypeScript `enum` so that an unknown
 * code arriving from a newer server is still a plain string this client can
 * carry, log and display. ADR 0031 makes new enum values an additive change; a
 * client that throws on one it has not seen turns an additive server release
 * into an outage.
 */
export const ApiErrorCode = {
  VALIDATION_FAILED: 'VALIDATION_FAILED',
  INVALID_REQUEST: 'INVALID_REQUEST',
  MALFORMED_BODY: 'MALFORMED_BODY',
  IDEMPOTENCY_KEY_REQUIRED: 'IDEMPOTENCY_KEY_REQUIRED',
  UNAUTHENTICATED: 'UNAUTHENTICATED',
  INSUFFICIENT_CAPABILITY: 'INSUFFICIENT_CAPABILITY',
  ENTITLEMENT_REQUIRED: 'ENTITLEMENT_REQUIRED',
  TENANT_ACCESS_DENIED: 'TENANT_ACCESS_DENIED',
  RESOURCE_NOT_FOUND: 'RESOURCE_NOT_FOUND',
  RESOURCE_CONFLICT: 'RESOURCE_CONFLICT',
  STALE_VERSION: 'STALE_VERSION',
  IDEMPOTENCY_KEY_REUSED: 'IDEMPOTENCY_KEY_REUSED',
  IDEMPOTENCY_KEY_IN_PROGRESS: 'IDEMPOTENCY_KEY_IN_PROGRESS',
  PRICE_CHANGED: 'PRICE_CHANGED',
  UNSUPPORTED_MEDIA_TYPE: 'UNSUPPORTED_MEDIA_TYPE',
  RATE_LIMIT_EXCEEDED: 'RATE_LIMIT_EXCEEDED',
  INTERNAL_ERROR: 'INTERNAL_ERROR',
  /** Not from the server. The request never reached it. */
  NETWORK_UNREACHABLE: 'NETWORK_UNREACHABLE',
} as const;

export type ApiErrorCode = (typeof ApiErrorCode)[keyof typeof ApiErrorCode];

export const PROBLEM_JSON = 'application/problem+json';

/**
 * Every failed API call surfaces as one of these, whatever went wrong.
 *
 * A caller that wants to react to a specific failure branches on `code`. A
 * caller that only wants to tell the operator something reaches for the message
 * key its own feature owns — never `problem.detail`, which is English prose
 * aimed at a developer.
 */
export class ApiError extends Error {
  constructor(
    readonly code: ApiErrorCode | string,
    readonly status: number,
    readonly problem: ProblemDetails | null,
    readonly correlationId: string | null,
  ) {
    // The message is for a stack trace and a developer console. It deliberately
    // carries only the code, the status and the correlation id: `detail` may name
    // a resource the operator supplied, and an Error message ends up in logs and
    // error reporting, where ADR 0029 says customer data may not go.
    super(`${code} (HTTP ${status})${correlationId ? ` correlationId=${correlationId}` : ''}`);
    this.name = 'ApiError';
  }

  get fieldErrors(): readonly ProblemFieldError[] {
    return this.problem?.errors ?? [];
  }

  /**
   * The versions reported by a STALE_VERSION conflict: someone else changed the
   * aggregate between the read and the write. `expected` is what this client
   * sent, `actual` is where the aggregate really is.
   */
  get staleVersion(): { expected: number; actual: number } | null {
    if (this.code !== ApiErrorCode.STALE_VERSION || !this.problem) {
      return null;
    }
    const expected = this.problem['expected'];
    const actual = this.problem['actual'];
    return typeof expected === 'number' && typeof actual === 'number' ? { expected, actual } : null;
  }

  /**
   * Whether replaying the exact same request — same `Idempotency-Key` included —
   * could plausibly succeed. Distinguishing this from a permanent failure is what
   * lets a retry button exist without inviting an operator to click through a
   * validation error forever.
   */
  get isRetryable(): boolean {
    return (
      this.code === ApiErrorCode.NETWORK_UNREACHABLE ||
      this.code === ApiErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS ||
      this.code === ApiErrorCode.RATE_LIMIT_EXCEEDED ||
      this.status >= 500
    );
  }
}

/** Narrowing guard for a parsed response body that is a Problem Details. */
export function isProblemDetails(body: unknown): body is ProblemDetails {
  return (
    typeof body === 'object' &&
    body !== null &&
    typeof (body as { status?: unknown }).status === 'number'
  );
}

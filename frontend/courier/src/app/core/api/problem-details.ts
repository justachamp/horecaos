import { HttpErrorResponse } from '@angular/common/http';

/**
 * The stable error vocabulary of the platform (ADR 0031): clients branch on `code`, never on the
 * title or the detail, and a code they do not know is carried, not a parse failure. `NETWORK` and
 * `SESSION_EXPIRED` are this app's own, for a request that never arrived and a session that ended.
 */
export type ErrorCode =
  | 'VALIDATION_FAILED'
  | 'INVALID_REQUEST'
  | 'MALFORMED_BODY'
  | 'UNAUTHENTICATED'
  | 'INSUFFICIENT_CAPABILITY'
  | 'TENANT_ACCESS_DENIED'
  | 'RESOURCE_NOT_FOUND'
  | 'RESOURCE_CONFLICT'
  | 'STALE_VERSION'
  | 'UNPROCESSABLE_STATE'
  | 'RATE_LIMIT_EXCEEDED'
  | 'INTERNAL_ERROR'
  | 'NETWORK'
  | 'SESSION_EXPIRED'
  | (string & {});

/** RFC 9457, plus the extensions the platform always sets. */
export interface ProblemDetails {
  readonly status?: number;
  readonly code?: ErrorCode;
  readonly detail?: string;
  readonly correlationId?: string;
  /** A refusal that names which rule refused it (`TOO_FAR_FROM_PICKUP`, `NO_OPEN_SHIFT`, ...). */
  readonly reason?: string;
}

export function isProblemDetails(body: unknown): body is ProblemDetails {
  return typeof body === 'object' && body !== null && ('code' in body || 'status' in body);
}

/**
 * Every failure a caller sees, whatever produced it. A request that never arrived is normalised
 * into the same shape as a server refusal so no call site has to tell the two apart before it
 * can read a code.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: ErrorCode;
  readonly detail: string;
  readonly reason: string | null;
  readonly correlationId: string | null;

  constructor(init: {
    status: number;
    code: ErrorCode;
    detail: string;
    reason?: string | null;
    correlationId?: string | null;
  }) {
    // The detail is developer-facing and carries no personal data (ADR 0031); it is never the
    // string shown to a courier.
    super(`${init.code} (${init.status}): ${init.detail}`);
    this.name = 'ApiError';
    this.status = init.status;
    this.code = init.code;
    this.detail = init.detail;
    this.reason = init.reason ?? null;
    this.correlationId = init.correlationId ?? null;
  }

  get isNetwork(): boolean {
    return this.code === 'NETWORK';
  }

  /** The platform did not accept the caller as anybody: the session is over. */
  get isUnauthenticated(): boolean {
    return (
      this.status === 401 || this.code === 'UNAUTHENTICATED' || this.code === 'SESSION_EXPIRED'
    );
  }

  get isNotFound(): boolean {
    return this.status === 404;
  }
}

export function toApiError(failure: unknown): ApiError {
  if (failure instanceof ApiError) {
    return failure;
  }
  if (failure instanceof HttpErrorResponse) {
    if (failure.status === 0) {
      return new ApiError({
        status: 0,
        code: 'NETWORK',
        detail: 'the request did not reach the server',
      });
    }
    const body: unknown = failure.error;
    if (isProblemDetails(body)) {
      return new ApiError({
        status: failure.status,
        code: body.code ?? 'INTERNAL_ERROR',
        detail: body.detail ?? failure.statusText,
        reason: typeof body.reason === 'string' ? body.reason : null,
        correlationId: body.correlationId ?? null,
      });
    }
    return new ApiError({
      status: failure.status,
      code: 'INTERNAL_ERROR',
      detail: failure.statusText,
    });
  }
  return new ApiError({
    status: 0,
    code: 'INTERNAL_ERROR',
    detail: failure instanceof Error ? failure.message : 'unknown failure',
  });
}

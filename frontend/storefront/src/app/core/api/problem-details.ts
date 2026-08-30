import { HttpErrorResponse } from '@angular/common/http';

/**
 * The stable error vocabulary from `uz.qoida.platform.web.api.ErrorCode`.
 *
 * ADR 0031 says clients branch on `code`, never on `title` or `detail`, and that
 * new codes are an additive change a client must tolerate. The `(string & {})`
 * arm is what makes an unrecognised code a value this client can carry and
 * report rather than a parse failure.
 */
export type ErrorCode =
  | 'VALIDATION_FAILED'
  | 'INVALID_REQUEST'
  | 'MALFORMED_BODY'
  | 'IDEMPOTENCY_KEY_REQUIRED'
  | 'UNAUTHENTICATED'
  | 'INSUFFICIENT_CAPABILITY'
  | 'ENTITLEMENT_REQUIRED'
  | 'TENANT_ACCESS_DENIED'
  | 'RESOURCE_NOT_FOUND'
  | 'RESOURCE_CONFLICT'
  | 'STALE_VERSION'
  | 'IDEMPOTENCY_KEY_REUSED'
  | 'IDEMPOTENCY_KEY_IN_PROGRESS'
  | 'PRICE_CHANGED'
  | 'UNSUPPORTED_MEDIA_TYPE'
  | 'RATE_LIMIT_EXCEEDED'
  | 'INTERNAL_ERROR'
  // eslint-disable-next-line @typescript-eslint/ban-types
  | (string & {});

/** A field-level failure: a stable code, not prose. */
export interface FieldError {
  readonly field: string;
  readonly code: string;
  readonly message?: string;
}

/** RFC 9457, plus the Qoida extensions ApiProblem always sets. */
export interface ProblemDetails {
  readonly type?: string;
  readonly title?: string;
  readonly status?: number;
  readonly detail?: string;
  readonly instance?: string;
  readonly code?: ErrorCode;
  readonly correlationId?: string;
  readonly errors?: readonly FieldError[];
  /** STALE_VERSION carries the version the server actually holds. */
  readonly currentVersion?: number;
  readonly expectedVersion?: number;
  /** Checkout rejections carry a business reason beside the code. */
  readonly reason?: string;
}

export function isProblemDetails(body: unknown): body is ProblemDetails {
  return (
    typeof body === 'object' &&
    body !== null &&
    ('code' in body || 'title' in body) &&
    'status' in body
  );
}

/**
 * Every failure a caller sees, whatever produced it.
 *
 * A transport failure is normalised into the same shape as a server rejection so
 * no call site has to distinguish "the server said no" from "the request never
 * arrived" before it can read a code.
 */
export class QoidaApiError extends Error {
  readonly status: number;
  readonly code: ErrorCode;
  readonly detail: string;
  readonly correlationId?: string;
  readonly fieldErrors: readonly FieldError[];
  readonly problem?: ProblemDetails;
  readonly retryAfterSeconds?: number;

  constructor(init: {
    status: number;
    code: ErrorCode;
    detail: string;
    correlationId?: string;
    fieldErrors?: readonly FieldError[];
    problem?: ProblemDetails;
    retryAfterSeconds?: number;
  }) {
    // `detail` is developer-facing and, per ADR 0031, carries no PII. It is
    // still never the string shown to a customer: see `messageKeyFor`.
    super(`${init.code} (${init.status}): ${init.detail}`);
    this.name = 'QoidaApiError';
    this.status = init.status;
    this.code = init.code;
    this.detail = init.detail;
    this.correlationId = init.correlationId;
    this.fieldErrors = init.fieldErrors ?? [];
    this.problem = init.problem;
    this.retryAfterSeconds = init.retryAfterSeconds;
  }

  /** True when the aggregate moved under the caller and a reload is the fix. */
  get isStaleVersion(): boolean {
    return this.code === 'STALE_VERSION';
  }

  /** True when the same idempotency key is already in flight; retry, do not resend. */
  get isIdempotencyInProgress(): boolean {
    return this.code === 'IDEMPOTENCY_KEY_IN_PROGRESS';
  }
}

/**
 * True when the platform did not accept the caller as anybody.
 *
 * The realistic failure on a long-lived screen: a customer opens the app, walks
 * away, comes back, and the token that was valid when the screen mounted has
 * expired. **This must never be shown as an empty list.** "You have no saved
 * addresses" and "we could not tell who you are" look identical if a screen
 * treats every failure as nothing-to-show, and the first one is a lie that
 * invites the customer to type their address in again.
 *
 * Both arms are needed. The filter chain rejects an unauthenticated call before
 * a handler runs, so the body is not always an ADR 0031 problem document with a
 * `code` in it — and a bare 401 normalises to `INTERNAL_ERROR` at status 401.
 */
export function isUnauthenticated(failure: unknown): boolean {
  return (
    failure instanceof QoidaApiError &&
    (failure.status === 401 ||
      failure.code === 'UNAUTHENTICATED' ||
      failure.code === 'SESSION_EXPIRED')
  );
}

/**
 * True when the caller *was* somebody and the session they were holding has
 * ended — expired on its own, or revoked from another device.
 *
 * Narrower than {@link isUnauthenticated} and deliberately so. Both answer 401,
 * so a client that branches on the status alone cannot tell them apart, and the
 * two want opposite handling: a stranger is shown a first-time sign-in, while
 * somebody whose token died mid-basket is shown that they were signed out and
 * given the same screen back. The platform added `SESSION_EXPIRED` to make the
 * distinction available (ADR 0051); reading only the status throws it away.
 *
 * The code is only ever produced against a presented customer token, so this is
 * also the one failure that proves the bearer this tab is holding is dead —
 * which is what {@link Session.expire} acts on.
 */
export function isSessionExpired(failure: unknown): boolean {
  return failure instanceof QoidaApiError && failure.code === 'SESSION_EXPIRED';
}

/**
 * True when the platform says there is no such resource *for this caller*.
 *
 * On an ownership-authorised surface this is deliberately overloaded: somebody
 * else's resource, an archived one, and one that never existed are the same
 * answer. A client that renders them apart re-creates the leak the server went
 * out of its way to close.
 */
export function isNotFound(failure: unknown): boolean {
  return failure instanceof QoidaApiError && failure.status === 404;
}

export function toQoidaApiError(response: HttpErrorResponse): QoidaApiError {
  const retryAfter = Number(response.headers?.get('Retry-After'));
  const retryAfterSeconds = Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter : undefined;

  if (isProblemDetails(response.error)) {
    const problem = response.error;
    return new QoidaApiError({
      status: problem.status ?? response.status,
      code: problem.code ?? 'INTERNAL_ERROR',
      detail: problem.detail ?? problem.title ?? response.statusText,
      correlationId: problem.correlationId,
      fieldErrors: problem.errors ?? [],
      problem,
      retryAfterSeconds,
    });
  }

  // Status 0 is the browser refusing to tell us why: offline, DNS, CORS, or a
  // WebView that killed the request. Guessing between them would be fiction.
  if (response.status === 0) {
    return new QoidaApiError({
      status: 0,
      code: 'NETWORK_UNREACHABLE',
      detail: 'The request did not reach the platform.',
      retryAfterSeconds,
    });
  }

  return new QoidaApiError({
    status: response.status,
    code: 'INTERNAL_ERROR',
    detail: response.statusText || 'Unrecognised error response.',
    retryAfterSeconds,
  });
}

/**
 * The customer-facing message key for a code.
 *
 * Deliberately narrow: most codes are developer errors a customer can do nothing
 * about, and they all collapse to one honest sentence rather than leaking a
 * server `detail` into the interface. The returned value is a dot-notation key
 * for `TranslateService.get`, never a sentence — the wording lives in `i18n/`
 * with every other string a customer reads.
 */
export function messageKeyFor(error: QoidaApiError): string {
  switch (error.code) {
    case 'STALE_VERSION':
      return 'errors.staleVersion';
    case 'PRICE_CHANGED':
      return 'errors.priceChanged';
    case 'INSUFFICIENT_CAPABILITY':
    case 'TENANT_ACCESS_DENIED':
      return 'errors.insufficientCapability';
    case 'ENTITLEMENT_REQUIRED':
      return 'errors.entitlementRequired';
    case 'RESOURCE_NOT_FOUND':
      return 'errors.notFound';
    case 'RATE_LIMIT_EXCEEDED':
      return 'errors.rateLimited';
    case 'NETWORK_UNREACHABLE':
      return 'errors.offline';
    default:
      return 'errors.generic';
  }
}

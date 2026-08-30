/**
 * RFC 9457 Problem Details, in the one shape every Qoida surface returns
 * (ADR 0031).
 *
 * The server's `ApiProblem` builds these and its `ErrorCode` enum is the
 * vocabulary. Clients branch on `code` and never on `title` or `detail`:
 * titles get reworded and details are written for a developer reading a
 * response, not for a user reading a screen.
 */

/**
 * The server's error codes, as of the platform release this client is written
 * against. Mirrors uz.qoida.platform.web.api.ErrorCode.
 */
export type KnownErrorCode =
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
  | 'INTERNAL_ERROR';

/** Failures that happen before a Problem Details body ever exists. */
export type TransportErrorCode = 'NETWORK_UNREACHABLE' | 'UNRECOGNISED_ERROR_RESPONSE';

/**
 * ADR 0031 allows new enum values within a major version and requires clients
 * to tolerate them, so this is deliberately not a closed union at runtime. The
 * literals still give a switch its exhaustiveness and its autocompletion.
 */
export type ErrorCode = KnownErrorCode | TransportErrorCode | (string & {});

/** A field-level validation failure, with a stable code rather than prose. */
export interface ProblemFieldError {
  readonly field: string;
  readonly code: string;
  readonly message?: string;
}

export interface Problem {
  readonly type?: string;
  readonly title?: string;
  readonly status: number;
  readonly detail?: string;
  readonly instance?: string;
  readonly code: ErrorCode;
  readonly correlationId?: string;
  readonly errors?: readonly ProblemFieldError[];
  /** Codes carry their own extras — STALE_VERSION reports the current version. */
  readonly [extra: string]: unknown;
}

/**
 * The single error type every call in this application rejects with.
 *
 * `message` exists because `Error` demands one and a stack trace with an empty
 * message is unreadable. It is never shown to a user: user-facing text is
 * looked up from `code` in the message catalogue, so it is translated and so
 * a server `detail` — written for a developer — never reaches a screen.
 */
export class ApiError extends Error {
  constructor(
    readonly problem: Problem,
    /** True when the server replayed a stored response for a repeated key. */
    readonly replayed = false,
  ) {
    super(`${problem.code} (${problem.status})`);
    this.name = 'ApiError';
  }

  get code(): ErrorCode {
    return this.problem.code;
  }

  get status(): number {
    return this.problem.status;
  }

  /**
   * The correlation identifier to quote in a support conversation. Safe to
   * show and safe to log: it identifies a request, not a person (ADR 0029).
   */
  get correlationId(): string | undefined {
    return this.problem.correlationId;
  }

  get fieldErrors(): readonly ProblemFieldError[] {
    return this.problem.errors ?? [];
  }
}

const PROBLEM_MEDIA_TYPE = 'application/problem+json';

function isProblemBody(body: unknown): body is Problem {
  return (
    typeof body === 'object' &&
    body !== null &&
    typeof (body as { code?: unknown }).code === 'string' &&
    typeof (body as { status?: unknown }).status === 'number'
  );
}

/**
 * Turns whatever came back into a Problem.
 *
 * A gateway timeout page, an HTML error from a proxy, or a CORS failure all
 * arrive here too. They become `UNRECOGNISED_ERROR_RESPONSE` and
 * `NETWORK_UNREACHABLE` rather than being coerced into a fake server code,
 * because "the API said no" and "nothing answered" need different handling and
 * a client that conflates them retries the wrong one.
 */
export function toProblem(status: number, contentType: string | null, body: unknown, url: string): Problem {
  if (status === 0) {
    return {
      status: 0,
      code: 'NETWORK_UNREACHABLE',
      title: 'The platform could not be reached',
      instance: url,
    };
  }

  const declaresProblem = (contentType ?? '').includes(PROBLEM_MEDIA_TYPE);
  if (declaresProblem && isProblemBody(body)) {
    return body;
  }

  // Some infrastructure strips the media type. Accept a body that is shaped
  // like a Problem regardless, because rejecting a well-formed one over a
  // header a proxy rewrote would lose the error code the client needs.
  if (isProblemBody(body)) {
    return body;
  }

  return {
    status,
    code: 'UNRECOGNISED_ERROR_RESPONSE',
    title: 'Unrecognised error response',
    instance: url,
  };
}

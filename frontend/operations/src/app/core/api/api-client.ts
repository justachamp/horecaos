import { HttpClient, HttpErrorResponse, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, throwError } from 'rxjs';

import { environment } from '../../../environments/environment';
import { Versioned, parseETag, toETag } from './aggregate-version';
import { Command, IdempotencyKey } from './idempotency';
import { CursorState, Page, pageParams } from './page';
import { ApiError, ApiErrorCode, ProblemDetails, isProblemDetails } from './problem-details';

/** Query parameter values the platform actually accepts. Arrays repeat the key. */
export type QueryParams = Readonly<
  Record<string, string | number | boolean | readonly string[] | undefined>
>;

export interface GetOptions {
  readonly params?: QueryParams;
}

export interface MutateOptions {
  /**
   * The version the caller read the aggregate at, sent as `If-Match`.
   *
   * Required by every mutation of a versioned aggregate. Omitting it is not a
   * lenient shortcut — the server rejects the request with INVALID_REQUEST
   * rather than letting a caller opt out of the concurrency check.
   */
  readonly expectedVersion?: number;
  readonly params?: QueryParams;
}

/**
 * The one place this application talks to the platform (ADR 0031).
 *
 * It exists so that idempotency, optimistic concurrency, cursor pagination and
 * Problem Details are decided once instead of once per feature. A feature that
 * reaches for `HttpClient` directly has opted out of all four, and the failure
 * is invisible until an operator double-approves an order in production.
 *
 * Bearer tokens and correlation ids are *not* added here — they are interceptors,
 * because they apply to every request including ones this class does not make.
 */
@Injectable({ providedIn: 'root' })
export class ApiClient {
  private readonly http = inject(HttpClient);

  /**
   * A single resource, with the version it was read at.
   *
   * The version comes back beside the body rather than being thrown away,
   * because the caller will need it to write and the only correct version to
   * write with is the one that came with the body it showed the operator.
   */
  get<T>(path: string, options: GetOptions = {}): Observable<Versioned<T>> {
    return this.http
      .get<T>(this.url(path), { params: toHttpParams(options.params), observe: 'response' })
      .pipe(
        map((response) => ({
          value: response.body as T,
          version: parseETag(response.headers.get('ETag')),
        })),
        catchError(toApiError),
      );
  }

  /** A cursor page. Pass the state through {@link pageParams}, never a raw offset. */
  page<T>(path: string, state: CursorState, filters: QueryParams = {}): Observable<Page<T>> {
    return this.http
      .get<Page<T>>(this.url(path), {
        params: toHttpParams({ ...filters, ...pageParams(state) }),
      })
      .pipe(catchError(toApiError));
  }

  /**
   * A mutation carrying an operator intent.
   *
   * The {@link Command} is what supplies the idempotency key, and taking it
   * rather than a bare body is the design: there is no overload that mutates
   * without one, so a mutation cannot be written that forgets it.
   */
  post<TRequest, TResponse>(
    path: string,
    intent: Command<TRequest>,
    options: MutateOptions = {},
  ): Observable<TResponse> {
    return this.send<TRequest, TResponse>('POST', path, intent, options).pipe(
      map((response) => response.body as TResponse),
    );
  }

  patch<TRequest, TResponse>(
    path: string,
    intent: Command<TRequest>,
    options: MutateOptions = {},
  ): Observable<TResponse> {
    return this.send<TRequest, TResponse>('PATCH', path, intent, options).pipe(
      map((response) => response.body as TResponse),
    );
  }

  /** Catalog authoring's verb of choice for "set this field" (`CatalogAuthoringController`, `PriceAuthoringController`). */
  put<TRequest, TResponse>(
    path: string,
    intent: Command<TRequest>,
    options: MutateOptions = {},
  ): Observable<TResponse> {
    return this.send<TRequest, TResponse>('PUT', path, intent, options).pipe(
      map((response) => response.body as TResponse),
    );
  }

  /**
   * The full response, for callers that need the headers — the new `ETag` after
   * a write, or `Idempotency-Replayed` to tell "we did it" from "someone already
   * had".
   */
  send<TRequest, TResponse>(
    method: 'POST' | 'PATCH' | 'PUT' | 'DELETE',
    path: string,
    intent: Command<TRequest>,
    options: MutateOptions = {},
  ): Observable<HttpResponse<TResponse>> {
    return this.http
      .request<TResponse>(method, this.url(path), {
        body: intent.body,
        headers: mutationHeaders(intent.key, options.expectedVersion),
        params: toHttpParams(options.params),
        observe: 'response',
      })
      .pipe(catchError(toApiError));
  }

  private url(path: string): string {
    if (!path.startsWith('/')) {
      throw new Error(`API paths are absolute and start with "/", got "${path}"`);
    }
    return `${environment.apiBaseUrl}${path}`;
  }
}

function mutationHeaders(
  key: IdempotencyKey,
  expectedVersion: number | undefined,
): Record<string, string> {
  const headers: Record<string, string> = { 'Idempotency-Key': key };
  if (expectedVersion !== undefined) {
    headers['If-Match'] = toETag(expectedVersion);
  }
  return headers;
}

function toHttpParams(params: QueryParams | undefined): Record<string, string | readonly string[]> {
  if (!params) {
    return {};
  }
  const out: Record<string, string | readonly string[]> = {};
  for (const [key, value] of Object.entries(params)) {
    // Absent and null mean different things on PATCH bodies (ADR 0031); in a
    // query string there is no such distinction, so undefined is simply dropped
    // rather than serialised as the string "undefined".
    if (value === undefined) {
      continue;
    }
    out[key] = Array.isArray(value) ? value : String(value);
  }
  return out;
}

/**
 * Turns anything Angular throws into one {@link ApiError}.
 *
 * Every branch below is a real shape the platform produces: a Problem Details
 * body, an error body that is not one (a proxy's HTML 502 page), and status 0,
 * which is what a browser reports for a request that never left — DNS failure,
 * TLS failure, CORS refusal, or the tablet's wifi dropping mid-service.
 */
function toApiError(error: unknown): Observable<never> {
  if (!(error instanceof HttpErrorResponse)) {
    return throwError(() => error);
  }

  const correlationId = error.headers?.get('X-Correlation-Id') ?? null;

  if (error.status === 0) {
    return throwError(() => new ApiError(ApiErrorCode.NETWORK_UNREACHABLE, 0, null, correlationId));
  }

  const body = parseErrorBody(error.error);
  if (isProblemDetails(body)) {
    return throwError(
      () =>
        new ApiError(
          typeof body.code === 'string' ? body.code : ApiErrorCode.INTERNAL_ERROR,
          error.status,
          body,
          body.correlationId ?? correlationId,
        ),
    );
  }

  // A non-Problem-Details error response means something between this client and
  // the application answered — a gateway, a proxy, a WAF. Reporting it as
  // INTERNAL_ERROR with the real status is more honest than inventing a code.
  return throwError(
    () => new ApiError(ApiErrorCode.INTERNAL_ERROR, error.status, null, correlationId),
  );
}

function parseErrorBody(body: unknown): ProblemDetails | unknown {
  // `HttpClient` hands back a string when the response was not parseable as the
  // declared type, which happens for `application/problem+json` on some proxies
  // that rewrite the content type.
  if (typeof body === 'string') {
    try {
      return JSON.parse(body);
    } catch {
      return body;
    }
  }
  return body;
}

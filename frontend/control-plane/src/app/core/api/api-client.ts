import { HttpClient, HttpContext, HttpContextToken, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { APP_CONFIG } from '../config/app-config';
import { Page, PageQuery } from './page';

/**
 * The HTTP client for the platform API, honouring ADR 0031.
 *
 * Four conventions are enforced here rather than left to callers, because a
 * convention a caller has to remember is a convention that is sometimes
 * forgotten and the forgetting is invisible until production:
 *
 *   - every mutation carries an `Idempotency-Key`;
 *   - a mutation of a versioned aggregate carries `If-Match`;
 *   - errors arrive as `ApiError` carrying a Problem Details body;
 *   - collections are cursor-paginated.
 *
 * Surface prefixes are ADR 0031's: `/api/v1/control-plane/**` for tenant
 * administration and `/api/v1/platform-admin/**` for Qoida staff at global
 * scope. This console is the only client of both.
 */

/** Marks a request as belonging to the platform API. Set by this client. */
export const PLATFORM_API_REQUEST = new HttpContextToken<boolean>(() => false);

export interface QueryParameters {
  readonly [name: string]: string | number | boolean | null | undefined;
}

export interface ReadOptions {
  readonly query?: QueryParameters;
}

export interface MutationOptions {
  /**
   * The idempotency key. Generated per call when absent.
   *
   * Pass the same key when retrying the same logical operation, and a
   * different key for a genuinely new one. This is the whole contract: a
   * fresh key on a retry creates a duplicate, and a stale key on a new
   * request is rejected with `IDEMPOTENCY_KEY_REUSED`. RxJS `retry()`
   * re-issues the request object unchanged, so a key set here survives it.
   */
  readonly idempotencyKey?: string;

  /**
   * The aggregate version the caller believes it is changing, sent as
   * `If-Match`. Omitting it on a versioned aggregate makes the server refuse
   * the request rather than accept a blind write.
   */
  readonly expectedVersion?: number;

  readonly query?: QueryParameters;
}

/** A response body together with the aggregate version its ETag carried. */
export interface Versioned<T> {
  readonly body: T;
  readonly version: number | null;
}

/**
 * A fresh idempotency key.
 *
 * A UUID rather than a hash of the request: two legitimately different carts
 * normalise to the same hash, and a retry with a trivial difference then
 * creates a duplicate. ADR 0019 and ADR 0031 both rejected the hash for this.
 */
export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}

/** The weak validator format the server writes and parses. */
export function toETag(version: number): string {
  return `W/"${version}"`;
}

export function versionFromETag(etag: string | null): number | null {
  if (etag === null) {
    return null;
  }
  const digits = etag.replace(/^W\//, '').replace(/"/g, '').trim();
  const version = Number(digits);
  return Number.isInteger(version) ? version : null;
}

@Injectable({ providedIn: 'root' })
export class ApiClient {
  private readonly http = inject(HttpClient);
  private readonly config = inject(APP_CONFIG);

  /** GET a single resource. */
  get<T>(path: string, options: ReadOptions = {}): Observable<T> {
    return this.http.get<T>(this.url(path), {
      params: toParams(options.query),
      context: platformApi(),
    });
  }

  /**
   * GET a versioned aggregate, keeping the ETag.
   *
   * The version is needed to mutate it afterwards, and a caller that has to
   * remember to read a header will eventually not.
   */
  getVersioned<T>(path: string, options: ReadOptions = {}): Observable<Versioned<T>> {
    return this.http
      .get<T>(this.url(path), {
        params: toParams(options.query),
        context: platformApi(),
        observe: 'response',
      })
      .pipe(
        map((response) => ({
          body: response.body as T,
          version: versionFromETag(response.headers.get('ETag')),
        })),
      );
  }

  /** GET one page of a cursor-paginated collection. */
  getPage<T>(path: string, page: PageQuery = {}, options: ReadOptions = {}): Observable<Page<T>> {
    return this.http.get<Page<T>>(this.url(path), {
      params: toParams({
        ...options.query,
        cursor: page.cursor ?? undefined,
        limit: page.limit,
      }),
      context: platformApi(),
    });
  }

  post<T>(path: string, body: unknown, options: MutationOptions = {}): Observable<T> {
    return this.mutate<T>('POST', path, body, options);
  }

  put<T>(path: string, body: unknown, options: MutationOptions = {}): Observable<T> {
    return this.mutate<T>('PUT', path, body, options);
  }

  patch<T>(path: string, body: unknown, options: MutationOptions = {}): Observable<T> {
    return this.mutate<T>('PATCH', path, body, options);
  }

  /**
   * DELETE, with a body, because every revocation on this surface carries a
   * reason and the server requires it.
   */
  delete<T>(path: string, body: unknown, options: MutationOptions = {}): Observable<T> {
    return this.mutate<T>('DELETE', path, body, options);
  }

  private mutate<T>(
    method: 'POST' | 'PUT' | 'PATCH' | 'DELETE',
    path: string,
    body: unknown,
    options: MutationOptions,
  ): Observable<T> {
    return this.http.request<T>(method, this.url(path), {
      body,
      headers: mutationHeaders(options),
      params: toParams(options.query),
      context: platformApi(),
      responseType: 'json',
    });
  }

  private url(path: string): string {
    if (!path.startsWith('/api/')) {
      // Catches the two mistakes that produce an unauthenticated request to
      // somewhere unexpected: a relative path, and an absolute URL to another
      // host pasted from a curl command.
      throw new Error(`API paths start with /api/, got "${path}"`);
    }
    return `${this.config.apiBaseUrl}${path}`;
  }
}

function platformApi(): HttpContext {
  return new HttpContext().set(PLATFORM_API_REQUEST, true);
}

function mutationHeaders(options: MutationOptions): HttpHeaders {
  let headers = new HttpHeaders().set(
    'Idempotency-Key',
    options.idempotencyKey ?? newIdempotencyKey(),
  );

  if (options.expectedVersion !== undefined) {
    headers = headers.set('If-Match', toETag(options.expectedVersion));
  }
  return headers;
}

function toParams(query: QueryParameters | undefined): HttpParams {
  let params = new HttpParams();
  if (query === undefined) {
    return params;
  }

  for (const [name, value] of Object.entries(query)) {
    // Absent and null both mean "do not send this filter". The absent-versus-
    // null distinction ADR 0031 draws applies to PATCH bodies, not to query
    // strings, where there is no way to express a null.
    if (value !== null && value !== undefined) {
      params = params.set(name, String(value));
    }
  }
  return params;
}

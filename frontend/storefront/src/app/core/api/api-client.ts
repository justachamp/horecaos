import {
  HttpClient,
  HttpContext,
  HttpContextToken,
  HttpHeaders,
  HttpParams,
} from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { APP_CONFIG } from '../config/app-config';
import { newIdempotencyKey } from './idempotency';
import type { Page, PageRequest } from './page';

/**
 * Marks a request as going to the HorecaOS platform, and therefore as somewhere a
 * bearer token may be sent.
 *
 * Default false, so a destination has to opt in. The token is a bearer
 * credential: whoever receives it can spend it, and a header attached by
 * default reaches every host this application ever calls — a map tile server,
 * an image CDN, Keycloak's own token endpoint — without anybody deciding that
 * it should. Only `ApiClient` sets this, which is what makes "the platform" a
 * list of one.
 *
 * A context flag rather than a URL prefix match on `apiBaseUrl`: a prefix match
 * is one misconfigured base URL away from handing the token to somebody else's
 * host, and it fails silently when it does.
 */
export const PLATFORM_API_REQUEST = new HttpContextToken<boolean>(() => false);

/**
 * Marks a request the interceptor must not attach a bearer token to.
 *
 * The published menu is deliberately unauthenticated — it is what a customer
 * browses before they have an account — and sending a token to it would make an
 * anonymous read look like an identified one in the platform's audit trail.
 */
export const ANONYMOUS = new HttpContextToken<boolean>(() => false);

/**
 * Marks a request whose failure is the caller's business alone.
 *
 * The error interceptor turns every platform failure into a toast. That is right for a read the
 * customer asked for, and wrong for one they did not: the home screen's content document and the
 * anonymous counters are best effort by contract (ADR 0157), a failure of either is meant to leave
 * the page exactly as it was, and a "something went wrong" over a menu that is fine would be the
 * page reporting a problem it does not have. The caller still receives the rejection.
 */
export const QUIET_FAILURE = new HttpContextToken<boolean>(() => false);

export type QueryValue = string | number | boolean | null | undefined;

export interface ReadOptions {
  readonly query?: Readonly<Record<string, QueryValue>>;
  /** Sent as `If-None-Match`; a 304 is surfaced as a null body by the caller. */
  readonly etag?: string;
  readonly anonymous?: boolean;
  /** See {@link QUIET_FAILURE}: a failure is returned to the caller and never shown as a toast. */
  readonly quiet?: boolean;
  /**
   * Extra headers this one call needs beyond the platform's own conventions
   * (`Accept`, `X-Correlation-Id`) and the bearer the interceptors already
   * attach. The dine-in QR flow is the one caller today: the guest's
   * table-scoped token (`X-Dine-In-Token`) authorises those reads instead of
   * -- and alongside -- `anonymous`, since there is no Keycloak-style
   * principal for the bearer interceptor to find.
   */
  readonly headers?: Readonly<Record<string, string>>;
}

export interface MutateOptions<B> {
  readonly body?: B;
  /**
   * ADR 0031's optimistic concurrency. Rendered as a weak `If-Match` validator
   * to match `AggregateVersion.toETag`, which the server parses back to a long.
   */
  readonly expectedVersion?: number;
  /**
   * Supply this when a person pressed "try again" so the platform replays the
   * stored response instead of performing the effect a second time. Omit it and
   * one is generated, which is correct for a first attempt and wrong for a retry.
   */
  readonly idempotencyKey?: string;
  /**
   * True for a write-shaped call that still has no principal -- the storefront
   * delivery-fee preview (`POST .../delivery-fee`) is the one today: it is
   * `POST` only to keep the point it takes out of a URL (ADR 0029), performs
   * no write, and is unauthenticated by design like the menu beside it.
   * Default false, matching every genuine mutation, which must never look
   * anonymous in the platform's audit trail.
   */
  readonly anonymous?: boolean;
  /** See {@link ReadOptions.headers} -- the same one caller, the same reason. */
  readonly headers?: Readonly<Record<string, string>>;
}

/**
 * The ADR 0031 client.
 *
 * Four conventions are enforced here rather than remembered at each call site,
 * because ADR 0031 predicts exactly which one gets forgotten during integration
 * work: the idempotency key.
 */
@Injectable({ providedIn: 'root' })
export class ApiClient {
  private readonly http = inject(HttpClient);
  private readonly config = inject(APP_CONFIG);

  /** @param path relative to `apiBaseUrl`, beginning with a slash. */
  get<T>(path: string, options: ReadOptions = {}): Promise<T> {
    let headers = new HttpHeaders();
    if (options.etag) {
      headers = headers.set('If-None-Match', options.etag);
    }
    for (const [name, value] of Object.entries(options.headers ?? {})) {
      headers = headers.set(name, value);
    }
    return firstValueFrom(
      this.http.get<T>(this.url(path), {
        headers,
        params: toParams(options.query),
        context: new HttpContext()
          .set(PLATFORM_API_REQUEST, true)
          .set(ANONYMOUS, options.anonymous ?? false)
          .set(QUIET_FAILURE, options.quiet ?? false),
      }),
    );
  }

  /**
   * A cursor page. `limit` is clamped server-side to the endpoint's documented
   * maximum; sending a larger one is not an error, it is simply ignored.
   */
  list<T>(path: string, page: PageRequest = {}, options: ReadOptions = {}): Promise<Page<T>> {
    return this.get<Page<T>>(path, {
      ...options,
      query: {
        ...options.query,
        cursor: page.cursor ?? undefined,
        limit: page.limit,
      },
    });
  }

  /**
   * Any effectful request.
   *
   * `DELETE` is included: removing a cart line is an effect with a version, and
   * ADR 0031 draws its line at "creates a resource or causes an external
   * effect", not at the HTTP verb.
   */
  mutate<T, B = unknown>(
    method: 'POST' | 'PUT' | 'PATCH' | 'DELETE',
    path: string,
    options: MutateOptions<B> = {},
  ): Promise<T> {
    let headers = new HttpHeaders().set(
      'Idempotency-Key',
      options.idempotencyKey ?? newIdempotencyKey(),
    );

    if (options.expectedVersion !== undefined) {
      headers = headers.set('If-Match', weakETag(options.expectedVersion));
    }
    for (const [name, value] of Object.entries(options.headers ?? {})) {
      headers = headers.set(name, value);
    }

    return firstValueFrom(
      this.http.request<T>(method, this.url(path), {
        body: options.body,
        headers,
        context: new HttpContext()
          .set(PLATFORM_API_REQUEST, true)
          .set(ANONYMOUS, options.anonymous ?? false),
      }),
    );
  }

  /**
   * An anonymous counter: a write-shaped call that is neither an effect to replay nor something that
   * may identify the sender (the storefront's content counters, ADR 0157 Decision 10).
   *
   * It is the one `POST` that carries no `Idempotency-Key`, on purpose. A key exists so a retried
   * request is not performed twice; a counter is not retried, and a client-generated token on a
   * request the platform promises never to attribute is one more thing that must not be there. No
   * bearer goes with it either, and its failure is the caller's to swallow, never a toast. The body is
   * optional: the attribution-link click (ADR 0167) has none, because the path already says what was
   * counted.
   */
  report<B = never>(path: string, body?: B): Promise<void> {
    return firstValueFrom(
      this.http.post<void>(this.url(path), body ?? null, {
        context: new HttpContext()
          .set(PLATFORM_API_REQUEST, true)
          .set(ANONYMOUS, true)
          .set(QUIET_FAILURE, true),
      }),
    );
  }

  private url(path: string): string {
    return `${this.config.apiBaseUrl}${path}`;
  }
}

/**
 * The server renders a **weak** validator, because two responses at one version
 * are semantically equivalent without being byte-identical. Sending a strong one
 * back would be parsed anyway, but it would misdescribe what is being compared.
 */
export function weakETag(version: number): string {
  return `W/"${version}"`;
}

/** Reads the version back out of an ETag the server sent. */
export function versionFromETag(etag: string | null): number | null {
  if (!etag) {
    return null;
  }
  const match = /^(?:W\/)?"?(\d+)"?$/.exec(etag.trim());
  return match ? Number(match[1]) : null;
}

function toParams(query: Readonly<Record<string, QueryValue>> | undefined): HttpParams {
  let params = new HttpParams();
  if (!query) {
    return params;
  }
  for (const [key, value] of Object.entries(query)) {
    // An absent filter and an explicitly empty one are different requests; only
    // the absent one is dropped.
    if (value !== undefined && value !== null) {
      params = params.set(key, String(value));
    }
  }
  return params;
}

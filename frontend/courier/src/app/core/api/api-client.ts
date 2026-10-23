import { HttpClient, HttpContext, HttpContextToken, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { APP_CONFIG } from '../config/app-config';
import { newIdempotencyKey } from './idempotency';

/**
 * Marks a request as going to the HorecaOS platform, and therefore as somewhere a bearer token may
 * be sent.
 *
 * Default false, so a destination has to opt in: the token is a bearer credential, and a header
 * attached by default would reach every host the app ever calls. Only {@link ApiClient} sets
 * this, which is what makes "the platform" a list of one.
 */
export const PLATFORM_API_REQUEST = new HttpContextToken<boolean>(() => false);

export type QueryValue = string | number | boolean | null | undefined;

export interface MutateOptions<B> {
  readonly body?: B;
  /** ADR 0031's optimistic concurrency, sent as a weak `If-Match` validator. */
  readonly expectedVersion?: number;
  /** Supply when a person is retrying the same intent so the platform replays the stored answer. */
  readonly idempotencyKey?: string;
}

/**
 * The ADR 0031 client. The two conventions a feature would otherwise forget are enforced here:
 * every mutation carries an `Idempotency-Key`, and an aggregate version travels as `If-Match`.
 */
@Injectable({ providedIn: 'root' })
export class ApiClient {
  private readonly http = inject(HttpClient);
  private readonly config = inject(APP_CONFIG);

  /** @param path relative to `apiBaseUrl`, beginning with a slash */
  get<T>(path: string, query?: Readonly<Record<string, QueryValue>>): Promise<T> {
    return firstValueFrom(
      this.http.get<T>(this.url(path), {
        context: this.context(),
        params: this.params(query),
      }),
    );
  }

  /** A mutation. The body is never logged and the key is generated unless a retry supplies one. */
  post<T, B = unknown>(path: string, options: MutateOptions<B> = {}): Promise<T> {
    let headers = new HttpHeaders({
      'Idempotency-Key': options.idempotencyKey ?? newIdempotencyKey(),
    });
    if (options.expectedVersion !== undefined) {
      headers = headers.set('If-Match', `W/"${options.expectedVersion}"`);
    }
    return firstValueFrom(
      this.http.post<T>(this.url(path), options.body ?? {}, {
        context: this.context(),
        headers,
      }),
    );
  }

  private url(path: string): string {
    return `${this.config.apiBaseUrl}${path}`;
  }

  private context(): HttpContext {
    return new HttpContext().set(PLATFORM_API_REQUEST, true);
  }

  private params(query?: Readonly<Record<string, QueryValue>>): Record<string, string> {
    const params: Record<string, string> = {};
    for (const [name, value] of Object.entries(query ?? {})) {
      if (value !== null && value !== undefined) {
        params[name] = String(value);
      }
    }
    return params;
  }
}

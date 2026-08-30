import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Observable } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../environments/environment';
import { ApiClient } from './api-client';
import { ApiError, ApiErrorCode, PROBLEM_JSON } from './problem-details';
import { command } from './idempotency';
import { Page, firstPage } from './page';

/**
 * These tests exist to prove the four ADR 0031 conventions are actually applied
 * rather than merely documented: Problem Details on errors, an idempotency key
 * on every mutation, an expected version for optimistic concurrency, and cursor
 * pagination.
 */
describe('ApiClient', () => {
  let client: ApiClient;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), ApiClient],
    });
    client = TestBed.inject(ApiClient);
    http = TestBed.inject(HttpTestingController);
  });

  describe('mutations', () => {
    it('sends the intent’s idempotency key and the expected version', () => {
      const intent = command({ reasonCode: 'KITCHEN_READY' });

      client
        .post('/api/v1/tenants/t/brands/b/locations/l/orders/o/state-actions', intent, {
          expectedVersion: 7,
        })
        .subscribe();

      const request = http.expectOne(
        url('/api/v1/tenants/t/brands/b/locations/l/orders/o/state-actions'),
      );
      expect(request.request.headers.get('Idempotency-Key')).toBe(intent.key);
      // A weak validator, matching what the server renders in its ETag.
      expect(request.request.headers.get('If-Match')).toBe('W/"7"');
      request.flush({});
    });

    it('reuses one key across retries of the same intent', () => {
      // The property the whole mechanism rests on. A key minted inside the retry
      // makes each attempt a new intent, which is how one approval becomes two.
      const intent = command({ reasonCode: 'KITCHEN_READY' });
      const path = '/api/v1/tenants/t/brands/b/locations/l/orders/o/state-actions';

      client.post(path, intent, { expectedVersion: 7 }).subscribe({ error: () => undefined });
      const first = http.expectOne(url(path));
      first.flush({}, { status: 503, statusText: 'Service Unavailable' });

      client.post(path, intent, { expectedVersion: 7 }).subscribe({ error: () => undefined });
      const second = http.expectOne(url(path));

      expect(second.request.headers.get('Idempotency-Key')).toBe(
        first.request.headers.get('Idempotency-Key'),
      );
      second.flush({});
    });

    it('omits If-Match when no version is supplied, so the server can refuse', () => {
      // Not a convenience. The client never fakes a version it did not read; the
      // server rejects the request with INVALID_REQUEST and the mistake is loud.
      client.post('/api/v1/x', command({})).subscribe();
      const request = http.expectOne(url('/api/v1/x'));
      expect(request.request.headers.has('If-Match')).toBe(false);
      request.flush({});
    });
  });

  describe('reads', () => {
    it('returns the version the body was read at', async () => {
      const result = client.get<{ id: string }>('/api/v1/orders/o');
      const promise = firstValue(result);

      http.expectOne(url('/api/v1/orders/o')).flush({ id: 'o' }, { headers: { ETag: 'W/"12"' } });

      await expect(promise).resolves.toEqual({ value: { id: 'o' }, version: 12 });
    });

    it('reports a null version for an unversioned representation', async () => {
      const promise = firstValue(client.get<unknown>('/api/v1/orders'));
      http.expectOne(url('/api/v1/orders')).flush([]);
      await expect(promise).resolves.toMatchObject({ version: null });
    });
  });

  describe('cursor pagination', () => {
    it('sends limit alone on the first page and cursor after that', async () => {
      const promise = firstValue(client.page<string>('/api/v1/orders', firstPage(25)));
      const first = http.expectOne((request) => request.url === url('/api/v1/orders'));
      expect(first.request.params.get('limit')).toBe('25');
      expect(first.request.params.has('cursor')).toBe(false);
      first.flush({ items: ['a'], nextCursor: 'opaque-signed-cursor' } satisfies Page<string>);

      const page = await promise;
      expect(page.nextCursor).toBe('opaque-signed-cursor');

      client.page<string>('/api/v1/orders', { cursor: page.nextCursor, limit: 25 }).subscribe();
      const second = http.expectOne((request) => request.url === url('/api/v1/orders'));
      expect(second.request.params.get('cursor')).toBe('opaque-signed-cursor');
      second.flush({ items: [], nextCursor: null });
    });
  });

  describe('errors', () => {
    it('maps a Problem Details body onto its stable code', async () => {
      const promise = firstValue(client.get('/api/v1/orders/o'));

      http.expectOne(url('/api/v1/orders/o')).flush(
        {
          type: 'https://docs.qoida.uz/problems/insufficient-capability',
          title: 'Insufficient capability',
          status: 403,
          detail: 'Requires order.approve at LOCATION scope.',
          code: 'INSUFFICIENT_CAPABILITY',
          correlationId: '01J8ABCDEF',
        },
        { status: 403, statusText: 'Forbidden', headers: { 'Content-Type': PROBLEM_JSON } },
      );

      const error = await promise.catch((thrown: unknown) => thrown);
      expect(error).toBeInstanceOf(ApiError);
      expect((error as ApiError).code).toBe(ApiErrorCode.INSUFFICIENT_CAPABILITY);
      expect((error as ApiError).correlationId).toBe('01J8ABCDEF');
      expect((error as ApiError).isRetryable).toBe(false);
    });

    it('exposes both versions from a STALE_VERSION conflict', async () => {
      const promise = firstValue(
        client.post('/api/v1/orders/o/state-actions', command({}), { expectedVersion: 7 }),
      );

      http
        .expectOne(url('/api/v1/orders/o/state-actions'))
        .flush(
          { status: 409, code: 'STALE_VERSION', expected: 7, actual: 9 },
          { status: 409, statusText: 'Conflict' },
        );

      const error = (await promise.catch((thrown: unknown) => thrown)) as ApiError;
      expect(error.staleVersion).toEqual({ expected: 7, actual: 9 });
    });

    it('carries field errors through', async () => {
      const promise = firstValue(client.post('/api/v1/orders', command({})));
      http.expectOne(url('/api/v1/orders')).flush(
        {
          status: 400,
          code: 'VALIDATION_FAILED',
          errors: [{ field: 'lines[0].quantity', code: 'MUST_BE_POSITIVE' }],
        },
        { status: 400, statusText: 'Bad Request' },
      );

      const error = (await promise.catch((thrown: unknown) => thrown)) as ApiError;
      expect(error.fieldErrors).toEqual([{ field: 'lines[0].quantity', code: 'MUST_BE_POSITIVE' }]);
    });

    it('reports a request that never left as NETWORK_UNREACHABLE, not as a 500', async () => {
      // Status 0 is what a browser gives for DNS, TLS, CORS, or the tablet's
      // wifi dropping. Telling that apart from a server error is the difference
      // between "check the connection" and "the platform is broken".
      const promise = firstValue(client.get('/api/v1/orders'));
      http
        .expectOne(url('/api/v1/orders'))
        .error(new ProgressEvent('error'), { status: 0, statusText: 'Unknown Error' });

      const error = (await promise.catch((thrown: unknown) => thrown)) as ApiError;
      expect(error.code).toBe(ApiErrorCode.NETWORK_UNREACHABLE);
      expect(error.isRetryable).toBe(true);
    });

    it('does not pretend a proxy’s HTML error page is Problem Details', async () => {
      const promise = firstValue(client.get('/api/v1/orders'));
      http
        .expectOne(url('/api/v1/orders'))
        .flush('<html>502 Bad Gateway</html>', { status: 502, statusText: 'Bad Gateway' });

      const error = (await promise.catch((thrown: unknown) => thrown)) as ApiError;
      expect(error.code).toBe(ApiErrorCode.INTERNAL_ERROR);
      expect(error.status).toBe(502);
      expect(error.problem).toBeNull();
    });

    it('keeps the operator’s data out of the Error message', async () => {
      // ADR 0029. An Error message reaches logs and error reporting; a Problem
      // Details `detail` can name something the operator typed.
      const promise = firstValue(client.get('/api/v1/customers/c'));
      http
        .expectOne(url('/api/v1/customers/c'))
        .flush(
          { status: 404, code: 'RESOURCE_NOT_FOUND', detail: 'No customer +998901234567' },
          { status: 404, statusText: 'Not Found' },
        );

      const error = (await promise.catch((thrown: unknown) => thrown)) as ApiError;
      expect(error.message).not.toContain('998901234567');
      expect(error.message).toContain('RESOURCE_NOT_FOUND');
    });
  });

  afterEach(() => http.verify());
});

/**
 * The absolute URL the client will actually request.
 *
 * Written out rather than hard-coded, so these tests keep passing whichever
 * environment file the build substituted — and so the prefixing itself is
 * asserted rather than assumed.
 */
function url(path: string): string {
  return `${environment.apiBaseUrl}${path}`;
}

/** Promise of the first emission, so a test can `await` an observable. */
function firstValue<T>(source: Observable<T>): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    source.subscribe({ next: resolve, error: reject });
  });
}

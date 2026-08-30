import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { AccessTokenSource } from '../auth/access-token-source';
import { APP_CONFIG, AppConfig } from '../config/app-config';
import { ApiClient } from './api-client';
import { bearerTokenInterceptor, correlationIdInterceptor, problemDetailsInterceptor } from './interceptors';
import { Page } from './page';
import { ApiError } from './problem';

const CONFIG: AppConfig = {
  apiBaseUrl: 'https://api.test.qoida.uz',
  issuerUrl: 'https://auth.test.qoida.uz/realms/qoida',
  clientId: 'qoida-control-plane',
  displayTimeZone: 'Asia/Tashkent',
};

class FakeTokenSource extends AccessTokenSource {
  token: string | null = 'token-abc';

  override accessToken(): string | null {
    return this.token;
  }
}

describe('ApiClient', () => {
  let api: ApiClient;
  let http: HttpTestingController;
  let tokens: FakeTokenSource;

  beforeEach(() => {
    tokens = new FakeTokenSource();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(
          withInterceptors([correlationIdInterceptor, bearerTokenInterceptor, problemDetailsInterceptor]),
        ),
        provideHttpClientTesting(),
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: AccessTokenSource, useValue: tokens },
      ],
    });
    api = TestBed.inject(ApiClient);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  describe('addressing', () => {
    it('prefixes the configured API origin', () => {
      api.get('/api/v1/control-plane/tenants/t-1').subscribe();
      http.expectOne('https://api.test.qoida.uz/api/v1/control-plane/tenants/t-1').flush({});
    });

    it('refuses a path that is not an API path', () => {
      // Catches an absolute URL pasted from a curl command, which would send a
      // bearer token to somebody else's host.
      expect(() => api.get('https://evil.example/steal')).toThrow(/API paths start with/);
    });
  });

  describe('idempotency (ADR 0031)', () => {
    it('puts a key on every mutation without the caller asking', () => {
      api.post('/api/v1/control-plane/tenants', { slug: 'osh-markazi' }).subscribe();

      const request = http.expectOne('https://api.test.qoida.uz/api/v1/control-plane/tenants');
      expect(request.request.headers.get('Idempotency-Key')).toMatch(
        /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/,
      );
      request.flush({});
    });

    it('gives two separate calls two separate keys', () => {
      api.post('/api/v1/control-plane/tenants', {}).subscribe();
      api.post('/api/v1/control-plane/tenants', {}).subscribe();

      const [first, second] = http.match('https://api.test.qoida.uz/api/v1/control-plane/tenants');
      expect(first.request.headers.get('Idempotency-Key')).not.toBe(
        second.request.headers.get('Idempotency-Key'),
      );
      first.flush({});
      second.flush({});
    });

    it('uses the caller-supplied key, so a retry is a retry and not a duplicate', () => {
      api
        .post('/api/v1/control-plane/tenants', {}, { idempotencyKey: 'key-held-across-retries' })
        .subscribe();

      const request = http.expectOne('https://api.test.qoida.uz/api/v1/control-plane/tenants');
      expect(request.request.headers.get('Idempotency-Key')).toBe('key-held-across-retries');
      request.flush({});
    });

    it('does not put a key on a read', () => {
      api.get('/api/v1/session/context').subscribe();
      const request = http.expectOne('https://api.test.qoida.uz/api/v1/session/context');
      expect(request.request.headers.has('Idempotency-Key')).toBe(false);
      request.flush({});
    });
  });

  describe('optimistic concurrency', () => {
    it('sends the expected version as a weak validator the server parses', () => {
      api
        .put('/api/v1/control-plane/tenants/t-1/place', {}, { expectedVersion: 7 })
        .subscribe();

      const request = http.expectOne('https://api.test.qoida.uz/api/v1/control-plane/tenants/t-1/place');
      expect(request.request.headers.get('If-Match')).toBe('W/"7"');
      request.flush({});
    });

    it('omits If-Match when the caller has no version, rather than inventing one', () => {
      api.put('/api/v1/control-plane/tenants/t-1/place', {}).subscribe();
      const request = http.expectOne('https://api.test.qoida.uz/api/v1/control-plane/tenants/t-1/place');
      expect(request.request.headers.has('If-Match')).toBe(false);
      request.flush({});
    });

    it('reads the version back off the ETag', async () => {
      const read = api.getVersioned<{ id: string }>('/api/v1/control-plane/tenants/t-1');
      const pending = new Promise<number | null>((resolve) =>
        read.subscribe((response) => resolve(response.version)),
      );

      http
        .expectOne('https://api.test.qoida.uz/api/v1/control-plane/tenants/t-1')
        .flush({ id: 't-1' }, { headers: { ETag: 'W/"12"' } });

      expect(await pending).toBe(12);
    });
  });

  describe('cursor pagination', () => {
    it('sends cursor and limit, and returns items with the next cursor', async () => {
      const pending = new Promise<Page<{ id: string }>>((resolve) =>
        api
          .getPage<{ id: string }>('/api/v1/control-plane/tenants', { cursor: 'opaque', limit: 25 })
          .subscribe(resolve),
      );

      const request = http.expectOne(
        (candidate) => candidate.url === 'https://api.test.qoida.uz/api/v1/control-plane/tenants',
      );
      expect(request.request.params.get('cursor')).toBe('opaque');
      expect(request.request.params.get('limit')).toBe('25');
      request.flush({ items: [{ id: 't-1' }], nextCursor: 'next-opaque' });

      const page = await pending;
      expect(page.items).toHaveLength(1);
      expect(page.nextCursor).toBe('next-opaque');
    });

    it('omits the cursor on the first page instead of sending an empty one', () => {
      api.getPage('/api/v1/control-plane/tenants').subscribe();
      const request = http.expectOne(
        (candidate) => candidate.url === 'https://api.test.qoida.uz/api/v1/control-plane/tenants',
      );
      expect(request.request.params.has('cursor')).toBe(false);
      request.flush({ items: [], nextCursor: null });
    });
  });

  describe('Problem Details', () => {
    it('turns a problem+json body into an ApiError carrying the stable code', async () => {
      const failure = new Promise<ApiError>((resolve) =>
        api.get('/api/v1/control-plane/tenants/t-1').subscribe({ error: resolve }),
      );

      http.expectOne('https://api.test.qoida.uz/api/v1/control-plane/tenants/t-1').flush(
        {
          type: 'https://docs.qoida.uz/problems/insufficient-capability',
          title: 'Insufficient capability',
          status: 403,
          detail: 'Requires tenant.read at TENANT scope.',
          code: 'INSUFFICIENT_CAPABILITY',
          correlationId: '01J8ABCDEF',
        },
        { status: 403, statusText: 'Forbidden', headers: { 'Content-Type': 'application/problem+json' } },
      );

      const error = await failure;
      expect(error).toBeInstanceOf(ApiError);
      expect(error.code).toBe('INSUFFICIENT_CAPABILITY');
      expect(error.status).toBe(403);
      expect(error.correlationId).toBe('01J8ABCDEF');
    });

    it('carries field errors through for a form to place', async () => {
      const failure = new Promise<ApiError>((resolve) =>
        api.post('/api/v1/control-plane/tenants', {}).subscribe({ error: resolve }),
      );

      http.expectOne('https://api.test.qoida.uz/api/v1/control-plane/tenants').flush(
        {
          status: 400,
          code: 'VALIDATION_FAILED',
          errors: [{ field: 'slug', code: 'MUST_MATCH_PATTERN' }],
        },
        { status: 400, statusText: 'Bad Request', headers: { 'Content-Type': 'application/problem+json' } },
      );

      expect((await failure).fieldErrors[0]?.field).toBe('slug');
    });

    it('distinguishes an unreachable platform from a rejection', async () => {
      const failure = new Promise<ApiError>((resolve) =>
        api.get('/api/v1/session/context').subscribe({ error: resolve }),
      );

      http
        .expectOne('https://api.test.qoida.uz/api/v1/session/context')
        .error(new ProgressEvent('error'), { status: 0, statusText: 'Unknown Error' });

      expect((await failure).code).toBe('NETWORK_UNREACHABLE');
    });

    it('does not invent a server code for an HTML error page from a proxy', async () => {
      const failure = new Promise<ApiError>((resolve) =>
        api.get('/api/v1/session/context').subscribe({ error: resolve }),
      );

      http
        .expectOne('https://api.test.qoida.uz/api/v1/session/context')
        .flush('<html>502</html>', {
          status: 502,
          statusText: 'Bad Gateway',
          headers: { 'Content-Type': 'text/html' },
        });

      expect((await failure).code).toBe('UNRECOGNISED_ERROR_RESPONSE');
    });

    it('reports a replayed stored failure as replayed', async () => {
      const failure = new Promise<ApiError>((resolve) =>
        api.post('/api/v1/control-plane/tenants', {}).subscribe({ error: resolve }),
      );

      http.expectOne('https://api.test.qoida.uz/api/v1/control-plane/tenants').flush(
        { status: 409, code: 'RESOURCE_CONFLICT' },
        {
          status: 409,
          statusText: 'Conflict',
          headers: { 'Content-Type': 'application/problem+json', 'Idempotency-Replayed': 'true' },
        },
      );

      expect((await failure).replayed).toBe(true);
    });
  });

  describe('the bearer token', () => {
    it('is attached to a platform request', () => {
      api.get('/api/v1/session/context').subscribe();
      const request = http.expectOne('https://api.test.qoida.uz/api/v1/session/context');
      expect(request.request.headers.get('Authorization')).toBe('Bearer token-abc');
      request.flush({});
    });

    it('is not attached to anything else, even on the same origin', () => {
      // The guarantee is the context flag, not a URL prefix: a request made
      // with a plain HttpClient must never carry the operator's token.
      TestBed.inject(HttpClient).get('https://api.test.qoida.uz/some/other/thing').subscribe();
      const request = http.expectOne('https://api.test.qoida.uz/some/other/thing');
      expect(request.request.headers.has('Authorization')).toBe(false);
      request.flush({});
    });

    it('still sends the request when there is no token, so the server answers 401', () => {
      tokens.token = null;
      api.get('/api/v1/session/context').subscribe();
      const request = http.expectOne('https://api.test.qoida.uz/api/v1/session/context');
      expect(request.request.headers.has('Authorization')).toBe(false);
      request.flush({});
    });
  });

  describe('correlation', () => {
    it('sends a client-generated correlation id on every platform request', () => {
      api.get('/api/v1/session/context').subscribe();
      const request = http.expectOne('https://api.test.qoida.uz/api/v1/session/context');
      expect(request.request.headers.get('X-Correlation-Id')).toMatch(/^[0-9a-f-]{36}$/);
      request.flush({});
    });
  });
});

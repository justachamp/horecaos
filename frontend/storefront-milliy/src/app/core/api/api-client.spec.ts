import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { ApiClient, ANONYMOUS, PLATFORM_API_REQUEST, versionFromETag, weakETag } from './api-client';
import { APP_CONFIG, type AppConfig } from '../config/app-config';

const CONFIG: AppConfig = {
  apiBaseUrl: '/api/v1',
  tenantId: '10000000-0000-0000-0000-000000000001',
  brandId: '10000000-0000-0000-0000-000000000002',
  defaultLocationId: '10000000-0000-0000-0000-000000000003',
  channel: 'STOREFRONT',
  yandexMapsApiKey: '',
  brand: { displayName: 'Test Brand', theme: { accent: '#000000', accentDeep: '#000000' } },
};

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function setUp(): { client: ApiClient; httpMock: HttpTestingController } {
  TestBed.configureTestingModule({
    providers: [provideHttpClientTesting(), { provide: APP_CONFIG, useValue: CONFIG }],
  });
  return {
    client: TestBed.inject(ApiClient),
    httpMock: TestBed.inject(HttpTestingController),
  };
}

describe('weakETag / versionFromETag', () => {
  it('renders a weak validator', () => {
    expect(weakETag(7)).toBe('W/"7"');
  });

  it.each([0, 1, 7, 42, 999_999])('round-trips version %i through weakETag -> versionFromETag', (v) => {
    expect(versionFromETag(weakETag(v))).toBe(v);
  });

  it('also reads a strong (non-weak) quoted validator', () => {
    expect(versionFromETag('"12"')).toBe(12);
  });

  it('returns null for a null etag', () => {
    expect(versionFromETag(null)).toBeNull();
  });

  it('returns null for an unparseable etag', () => {
    expect(versionFromETag('not-an-etag')).toBeNull();
  });
});

describe('ApiClient.mutate', () => {
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
  });

  it('sets a fresh Idempotency-Key when none is supplied', async () => {
    const { client, httpMock } = setUp();

    const promise = client.mutate('POST', '/carts', { body: { a: 1 } });
    const req = httpMock.expectOne('/api/v1/carts');
    const key = req.request.headers.get('Idempotency-Key');

    expect(key).toMatch(UUID_PATTERN);
    req.flush({});
    await promise;
  });

  it('reuses the supplied Idempotency-Key rather than generating one', async () => {
    const { client, httpMock } = setUp();

    const promise = client.mutate('POST', '/carts', {
      body: {},
      idempotencyKey: 'fixed-retry-key',
    });
    const req = httpMock.expectOne('/api/v1/carts');

    expect(req.request.headers.get('Idempotency-Key')).toBe('fixed-retry-key');
    req.flush({});
    await promise;
  });

  it('two calls with no explicit key get two different keys', async () => {
    const { client, httpMock } = setUp();

    const p1 = client.mutate('POST', '/carts', {});
    const req1 = httpMock.expectOne('/api/v1/carts');
    const key1 = req1.request.headers.get('Idempotency-Key');
    req1.flush({});
    await p1;

    const p2 = client.mutate('POST', '/carts', {});
    const req2 = httpMock.expectOne('/api/v1/carts');
    const key2 = req2.request.headers.get('Idempotency-Key');
    req2.flush({});
    await p2;

    expect(key1).not.toBe(key2);
  });

  it('sets If-Match to the weak ETag of expectedVersion when supplied', async () => {
    const { client, httpMock } = setUp();

    const promise = client.mutate('PUT', '/carts/c1/lines/x', {
      body: {},
      expectedVersion: 5,
    });
    const req = httpMock.expectOne('/api/v1/carts/c1/lines/x');

    expect(req.request.headers.get('If-Match')).toBe('W/"5"');
    req.flush({});
    await promise;
  });

  it('omits If-Match entirely when no expectedVersion is supplied', async () => {
    const { client, httpMock } = setUp();

    const promise = client.mutate('POST', '/carts', { body: {} });
    const req = httpMock.expectOne('/api/v1/carts');

    expect(req.request.headers.has('If-Match')).toBe(false);
    req.flush({});
    await promise;
  });

  it('marks the request as a platform request and never anonymous', async () => {
    const { client, httpMock } = setUp();

    const promise = client.mutate('DELETE', '/carts/c1/lines/x', { expectedVersion: 1 });
    const req = httpMock.expectOne('/api/v1/carts/c1/lines/x');

    expect(req.request.context.get(PLATFORM_API_REQUEST)).toBe(true);
    expect(req.request.context.get(ANONYMOUS)).toBe(false);
    req.flush({});
    await promise;
  });

  it('sends the request body and method as given', async () => {
    const { client, httpMock } = setUp();

    const promise = client.mutate('PATCH', '/me', { body: { displayName: 'Aziz' } });
    const req = httpMock.expectOne('/api/v1/me');

    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ displayName: 'Aziz' });
    req.flush({});
    await promise;
  });
});

describe('ApiClient.get', () => {
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
  });

  it('resolves the URL against apiBaseUrl and marks the request anonymous when asked', async () => {
    const { client, httpMock } = setUp();

    const promise = client.get('/menu', { anonymous: true });
    const req = httpMock.expectOne('/api/v1/menu');

    expect(req.request.context.get(PLATFORM_API_REQUEST)).toBe(true);
    expect(req.request.context.get(ANONYMOUS)).toBe(true);
    req.flush({});
    await promise;
  });

  it('defaults anonymous to false', async () => {
    const { client, httpMock } = setUp();

    const promise = client.get('/me');
    const req = httpMock.expectOne('/api/v1/me');

    expect(req.request.context.get(ANONYMOUS)).toBe(false);
    req.flush({});
    await promise;
  });

  it('sends If-None-Match when an etag is supplied, and omits it otherwise', async () => {
    const { client, httpMock } = setUp();

    const withEtag = client.get('/menu', { etag: 'W/"3"' });
    const req1 = httpMock.expectOne('/api/v1/menu');
    expect(req1.request.headers.get('If-None-Match')).toBe('W/"3"');
    req1.flush({});
    await withEtag;

    const withoutEtag = client.get('/menu');
    const req2 = httpMock.expectOne('/api/v1/menu');
    expect(req2.request.headers.has('If-None-Match')).toBe(false);
    req2.flush({});
    await withoutEtag;
  });

  it('drops undefined/null query values but keeps an explicit empty string', async () => {
    const { client, httpMock } = setUp();

    const promise = client.get('/search', { query: { q: '', missing: undefined, present: 'x' } });
    const req = httpMock.expectOne(
      (r) => r.url === '/api/v1/search' && r.params.has('present') && r.params.has('q'),
    );

    expect(req.request.params.has('missing')).toBe(false);
    expect(req.request.params.get('q')).toBe('');
    expect(req.request.params.get('present')).toBe('x');
    req.flush({});
    await promise;
  });
});

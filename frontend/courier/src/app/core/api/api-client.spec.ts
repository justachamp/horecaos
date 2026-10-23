import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { APP_CONFIG } from '../config/app-config';
import { TEST_CONFIG } from '../../../testing/fixtures';
import { ApiClient, PLATFORM_API_REQUEST } from './api-client';

describe('ApiClient', () => {
  let api: ApiClient;
  let backend: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        { provide: APP_CONFIG, useValue: TEST_CONFIG },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    api = TestBed.inject(ApiClient);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  it("reads from under the configured API prefix and marks the request as the platform's", async () => {
    const answer = api.get('/courier/tenants/t/me');

    const request = backend.expectOne('/api/v1/courier/tenants/t/me');
    expect(request.request.context.get(PLATFORM_API_REQUEST)).toBe(true);
    expect(request.request.headers.has('Idempotency-Key')).toBe(false);
    request.flush({ ok: true });
    await expect(answer).resolves.toEqual({ ok: true });
  });

  it('sends only the query values that are there', async () => {
    const answer = api.get('/x', { a: 1, b: null, c: undefined, d: false });

    const request = backend.expectOne((candidate) => candidate.url === '/api/v1/x');
    expect(request.request.params.keys().sort()).toEqual(['a', 'd']);
    request.flush({});
    await answer;
  });

  it('gives every mutation an Idempotency-Key, a fresh one each time', async () => {
    const first = api.post('/x', { body: { a: 1 } });
    const firstKey = backend.expectOne('/api/v1/x');
    const second = api.post('/x', { body: { a: 1 } });
    const secondKey = backend.expectOne('/api/v1/x');

    expect(firstKey.request.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/);
    expect(firstKey.request.headers.get('Idempotency-Key')).not.toBe(
      secondKey.request.headers.get('Idempotency-Key'),
    );
    firstKey.flush({});
    secondKey.flush({});
    await Promise.all([first, second]);
  });

  it('reuses the key of an intent that is being retried, so the platform replays instead of repeating', async () => {
    const answer = api.post('/x', { idempotencyKey: 'key-from-the-first-attempt' });

    const request = backend.expectOne('/api/v1/x');
    expect(request.request.headers.get('Idempotency-Key')).toBe('key-from-the-first-attempt');
    request.flush({});
    await answer;
  });

  it('renders an aggregate version as the weak If-Match validator the platform parses', async () => {
    const answer = api.post('/offers/o/accept', { expectedVersion: 7 });

    const request = backend.expectOne('/api/v1/offers/o/accept');
    expect(request.request.headers.get('If-Match')).toBe('W/"7"');
    request.flush({});
    await answer;
  });

  it('sends no If-Match when no version was named', async () => {
    const answer = api.post('/x');

    const request = backend.expectOne('/api/v1/x');
    expect(request.request.headers.has('If-Match')).toBe(false);
    request.flush({});
    await answer;
  });
});

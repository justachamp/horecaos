import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../environments/environment';
import { ApiClient } from './api-client';
import { GeoLookupApi } from './geo-lookup-api';
import { ApiError, PROBLEM_JSON } from './problem-details';

const SCOPE = { tenantId: 't-1', brandId: 'b-1' };
const BRAND = `${environment.apiBaseUrl}/api/v1/operations/tenants/t-1/brands/b-1/geocode`;

const RESULT = {
  latitude: 41.3,
  longitude: 69.2,
  components: {
    country: 'UZ',
    locality: 'Ташкент',
    district: null,
    street: 'Fixture ko‘chasi',
    house: '12',
    formatted: 'Fixture ko‘chasi, 12',
  },
  providerReference: 'ref',
  confidence: 'HIGH',
  precision: 'HOUSE',
  resolvedAt: '2026-10-07T08:00:00Z',
  provider: 'FAKE',
};

describe('GeoLookupApi (ADR 0145)', () => {
  let api: GeoLookupApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), ApiClient],
    });
    api = TestBed.inject(GeoLookupApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function answer<T>(
    pending: Promise<T>,
    path: string,
    reply: (flush: (body: object, status?: number) => void) => void,
  ): Promise<T> {
    // The request is issued a microtask after the call, so let it be before looking for it.
    await Promise.resolve();
    const request = http.expectOne(`${BRAND}${path}`);
    reply((body, status = 200) =>
      request.flush(body, {
        status,
        statusText: String(status),
        headers: status >= 400 ? { 'Content-Type': PROBLEM_JSON } : {},
      }),
    );
    return pending;
  }

  it('posts the address in the body and never in the URL', async () => {
    const pending = api.resolve(
      { scope: SCOPE, regionId: 'r-1', locale: 'uz-Latn' },
      'Fixture ko‘chasi 12',
    );

    await Promise.resolve();
    const request = http.expectOne(`${BRAND}/resolutions`);
    expect(request.request.method).toBe('POST');
    expect(request.request.urlWithParams).not.toContain('Fixture');
    expect(request.request.body).toEqual({
      text: 'Fixture ko‘chasi 12',
      regionId: 'r-1',
      locale: 'uz-Latn',
    });
    request.flush({ status: 'ANSWERED', reason: null, results: [RESULT] });
    const result = await pending;

    expect(result).toEqual({ status: 'ANSWERED', value: [RESULT] });
  });

  it('sends only what it was given, so the console’s minimal JSON is the whole body', async () => {
    const pending = api.suggest({ scope: SCOPE }, 'amir');

    await Promise.resolve();
    const request = http.expectOne(`${BRAND}/suggestions`);
    expect(request.request.body).toEqual({ text: 'amir' });
    request.flush({ status: 'ANSWERED', reason: null, suggestions: [] });
    expect(await pending).toEqual({ status: 'ANSWERED', value: [] });
  });

  it('calls the branch path when a branch is named, and carries a point to rank toward', async () => {
    const pending = api.suggest({ scope: SCOPE, locationId: 'l-9' }, 'amir', {
      latitude: 41.3,
      longitude: 69.2,
    });

    await Promise.resolve();
    const request = http.expectOne(
      `${environment.apiBaseUrl}/api/v1/operations/tenants/t-1/brands/b-1/locations/l-9/geocode/suggestions`,
    );
    expect(request.request.body).toEqual({
      text: 'amir',
      near: { latitude: 41.3, longitude: 69.2 },
    });
    request.flush({ status: 'ANSWERED', reason: null, suggestions: [] });
    await pending;
  });

  it('reads a reverse answer, including "nothing is there"', async () => {
    const found = api.reverse({ scope: SCOPE }, { latitude: 41.3, longitude: 69.2 });
    expect(
      await answer(found, '/reverse-resolutions', (flush) =>
        flush({ status: 'ANSWERED', reason: null, result: RESULT }),
      ),
    ).toEqual({ status: 'ANSWERED', value: RESULT });

    const nothing = api.reverse({ scope: SCOPE }, { latitude: 41.2, longitude: 69.1 });
    expect(
      await answer(nothing, '/reverse-resolutions', (flush) =>
        flush({ status: 'ANSWERED', reason: null, result: null }),
      ),
    ).toEqual({ status: 'ANSWERED', value: null });
  });

  it('turns the server’s UNAVAILABLE into an answer with its reason, not an exception', async () => {
    const pending = api.resolve({ scope: SCOPE }, 'anything');

    const result = await answer(pending, '/resolutions', (flush) =>
      flush({ status: 'UNAVAILABLE', reason: 'NOT_CONFIGURED', results: [] }),
    );

    expect(result).toEqual({ status: 'UNAVAILABLE', reason: 'NOT_CONFIGURED' });
  });

  it('turns the caller’s own failures into answers too: a rate limit, no region, a request that did not get through', async () => {
    const limited = api.suggest({ scope: SCOPE }, 'amir');
    expect(
      await answer(limited, '/suggestions', (flush) =>
        flush({ status: 429, code: 'RATE_LIMIT_EXCEEDED' }, 429),
      ),
    ).toEqual({ status: 'UNAVAILABLE', reason: 'RATE_LIMITED' });

    const noRegion = api.suggest({ scope: SCOPE }, 'amir');
    expect(
      await answer(noRegion, '/suggestions', (flush) =>
        flush({ status: 422, code: 'UNPROCESSABLE_STATE', reason: 'REGION_REQUIRED' }, 422),
      ),
    ).toEqual({ status: 'UNAVAILABLE', reason: 'NO_REGION' });

    const broken = api.suggest({ scope: SCOPE }, 'amir');
    expect(
      await answer(broken, '/suggestions', (flush) =>
        flush({ status: 500, code: 'INTERNAL_ERROR' }, 500),
      ),
    ).toEqual({ status: 'UNAVAILABLE', reason: 'REQUEST_FAILED' });
  });

  it('does not dress a missing capability up as "try again later"', async () => {
    const pending = api.suggest({ scope: SCOPE }, 'amir');
    const settled = pending.then(
      () => 'resolved',
      (failure: unknown) => failure,
    );

    await answer(Promise.resolve(), '/suggestions', (flush) =>
      flush({ status: 403, code: 'INSUFFICIENT_CAPABILITY' }, 403),
    );

    const failure = await settled;
    expect(failure).toBeInstanceOf(ApiError);
    expect((failure as ApiError).code).toBe('INSUFFICIENT_CAPABILITY');
  });
});

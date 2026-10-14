import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../environments/environment';
import { ApiClient } from '../../core/api/api-client';
import { ApiError, PROBLEM_JSON } from '../../core/api/problem-details';
import { CourierPositionsApi } from './courier-positions-api';

const SCOPE = { tenantId: 't-1', brandId: 'b-1', locationId: 'l-1' };
const BASE = `${environment.apiBaseUrl}/api/v1/tenants/t-1/brands/b-1/locations/l-1/operations/couriers`;

/**
 * The URL the live map really calls (row 3.2). `live-map-page.spec.ts` replaces this service with
 * a stub, so before this file no spec saw the path; it was wrong for a release and nothing failed.
 */
describe('CourierPositionsApi (row 3.2, ADR 0045)', () => {
  let api: CourierPositionsApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), ApiClient],
    });
    api = TestBed.inject(CourierPositionsApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('reads the fleet from the path the controller is mapped at', async () => {
    const pending = api.fleet(SCOPE);
    await Promise.resolve();
    const request = http.expectOne(`${BASE}/positions`);

    expect(request.request.method).toBe('GET');
    request.flush({ pins: [], withoutPin: [] });
    expect(await pending).toEqual({ pins: [], withoutPin: [] });
  });

  it('opens a track with a POST, the purpose in the body and never the URL, with an idempotency key', async () => {
    const pending = api.revealTrack(
      SCOPE,
      'courier-9',
      '2026-10-13T08:00:00Z',
      '2026-10-13T09:00:00Z',
      'A customer disputes the delivery time',
    );
    await Promise.resolve();
    const request = http.expectOne(`${BASE}/courier-9/track-reveals`);

    expect(request.request.method).toBe('POST');
    expect(request.request.url).not.toContain('disputes');
    expect(request.request.body).toEqual({
      from: '2026-10-13T08:00:00Z',
      to: '2026-10-13T09:00:00Z',
      purpose: 'A customer disputes the delivery time',
    });
    expect(request.request.headers.get('Idempotency-Key')).toBeTruthy();

    request.flush({
      courierId: 'courier-9',
      from: '2026-10-13T08:00:00Z',
      to: '2026-10-13T09:00:00Z',
      purpose: 'A customer disputes the delivery time',
      windows: [],
    });
    expect((await pending).windows).toEqual([]);
  });

  it('lets a refusal reach the caller as the error it is', async () => {
    const pending = api.fleet(SCOPE);
    await Promise.resolve();
    http
      .expectOne(`${BASE}/positions`)
      .flush(
        { code: 'INSUFFICIENT_CAPABILITY', status: 403 },
        { status: 403, statusText: 'Forbidden', headers: { 'Content-Type': PROBLEM_JSON } },
      );

    await expect(pending).rejects.toBeInstanceOf(ApiError);
  });
});

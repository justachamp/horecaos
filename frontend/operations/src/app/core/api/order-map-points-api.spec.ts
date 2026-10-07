import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../environments/environment';
import { ApiClient } from './api-client';
import { OrderMapPointsApi, TERMINAL_ORDER_STATUSES } from './order-map-points-api';
import { ApiError, PROBLEM_JSON } from './problem-details';

const SCOPE = { tenantId: 't-1', brandId: 'b-1', locationId: 'l-1' };
const URL = `${environment.apiBaseUrl}/api/v1/tenants/t-1/brands/b-1/locations/l-1/orders/map-point-reveals`;

describe('OrderMapPointsApi (rows 7.10a, 3.1; ADR 0145)', () => {
  let api: OrderMapPointsApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), ApiClient],
    });
    api = TestBed.inject(OrderMapPointsApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('posts the purpose in the body, never in the URL, with an idempotency key (it writes an audit fact)', async () => {
    const pending = api.reveal(SCOPE, 'Operations console: Geography report (row 7.10a)');
    await Promise.resolve();
    const request = http.expectOne(URL);

    expect(request.request.method).toBe('POST');
    expect(request.request.url).not.toContain('Geography');
    expect(request.request.body).toEqual({
      purpose: 'Operations console: Geography report (row 7.10a)',
    });
    expect(request.request.headers.get('Idempotency-Key')).toBeTruthy();

    request.flush({
      windowFrom: '2026-10-07T00:00:00Z',
      windowTo: '2026-10-08T00:00:00Z',
      points: [],
      withoutPoint: 0,
      truncated: false,
    });
    const answer = await pending;
    expect(answer.points).toEqual([]);
  });

  it('lets a refusal reach the caller as the error it is: a missing capability is not an empty map', async () => {
    const pending = api.reveal(SCOPE, 'x');
    await Promise.resolve();
    http
      .expectOne(URL)
      .flush(
        { code: 'INSUFFICIENT_CAPABILITY', status: 403 },
        { status: 403, statusText: 'Forbidden', headers: { 'Content-Type': PROBLEM_JSON } },
      );

    await expect(pending).rejects.toBeInstanceOf(ApiError);
  });

  it('knows which order states are over, the same five the platform does', () => {
    expect([...TERMINAL_ORDER_STATUSES].sort()).toEqual(
      ['CANCELLED', 'COMPLETED', 'EXPIRED', 'PAYMENT_FAILED', 'REJECTED'].sort(),
    );
    expect(TERMINAL_ORDER_STATUSES.has('FULFILLING')).toBe(false);
  });
});

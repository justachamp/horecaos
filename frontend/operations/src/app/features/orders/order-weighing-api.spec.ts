import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../environments/environment';
import { LocationScope } from '../../core/api/operations-paths';
import { OrderWeighingApi } from './order-weighing-api';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };
const URL = `${environment.apiBaseUrl}/api/v1/tenants/t1/brands/b1/locations/l1/orders/o1/lines/line-1/actual-weight`;

describe('OrderWeighingApi (ADR 0137)', () => {
  let api: OrderWeighingApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), OrderWeighingApi],
    });
    api = TestBed.inject(OrderWeighingApi);
    http = TestBed.inject(HttpTestingController);
  });

  it('puts the weighed total of the whole line under the order version, with an Idempotency-Key', async () => {
    const result = firstValueFrom(api.captureActualWeight(SCOPE, 'o1', 'line-1', 1_340, 7));
    const request = http.expectOne(URL);

    expect(request.request.method).toBe('PUT');
    expect(request.request.headers.get('If-Match')).toBe('W/"7"');
    expect(request.request.headers.get('Idempotency-Key')).toBeTruthy();
    expect(request.request.body).toEqual({ actualWeightGrams: 1_340 });
    request.flush({
      orderId: 'o1',
      lineId: 'line-1',
      changed: true,
      actualWeightGrams: 1_340,
      lineFinalAmountMinor: 201_000,
      totalMinor: 201_000,
      deltaTotalMinor: 21_000,
      revision: 2,
      orderVersion: 8,
    });

    expect((await result).orderVersion).toBe(8);
  });

  it('keeps one Idempotency-Key for the same weight on the same line, so a retry is not a second weighing', () => {
    void firstValueFrom(api.captureActualWeight(SCOPE, 'o1', 'line-1', 1_340, 7));
    const first = http.expectOne(URL);
    void firstValueFrom(api.captureActualWeight(SCOPE, 'o1', 'line-1', 1_340, 7));
    const second = http.expectOne(URL);

    expect(second.request.headers.get('Idempotency-Key')).toBe(
      first.request.headers.get('Idempotency-Key'),
    );
  });

  it('a different weight is a new intent with a new key', () => {
    void firstValueFrom(api.captureActualWeight(SCOPE, 'o1', 'line-1', 1_340, 7));
    const first = http.expectOne(URL);
    void firstValueFrom(api.captureActualWeight(SCOPE, 'o1', 'line-1', 1_100, 7));
    const second = http.expectOne(URL);

    expect(second.request.headers.get('Idempotency-Key')).not.toBe(
      first.request.headers.get('Idempotency-Key'),
    );
  });
});

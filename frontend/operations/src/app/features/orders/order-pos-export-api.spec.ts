import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../environments/environment';
import { OrderPosExportApi } from './order-pos-export-api';

const SCOPE = { tenantId: 't1' };
const PUSH_URL = `${environment.apiBaseUrl}/api/v1/operations/tenants/t1/orders/o1/pos-export/push`;

describe('OrderPosExportApi.push', () => {
  let api: OrderPosExportApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), OrderPosExportApi],
    });
    api = TestBed.inject(OrderPosExportApi);
    http = TestBed.inject(HttpTestingController);
  });

  it('posts the reason with an Idempotency-Key and no If-Match — the export attempt is not the order aggregate', () => {
    const resultPromise = api.push(SCOPE, 'o1', 'Operator retry after a provider timeout');
    const request = http.expectOne(PUSH_URL);

    expect(request.request.method).toBe('POST');
    expect(request.request.headers.has('Idempotency-Key')).toBe(true);
    expect(request.request.headers.has('If-Match')).toBe(false);
    expect(request.request.body).toEqual({ reason: 'Operator retry after a provider timeout' });
    request.flush({ status: 'SUCCESS', state: 'SENT', errorCode: null, detail: null });
    return resultPromise;
  });

  // 2026-09-21 audit follow-up (a): a double click on "Push" before the
  // panel shows busy used to mint two independent keys and could send the
  // same order to the POS twice.
  it('reuses the same Idempotency-Key for two still-in-flight pushes of the same order with an unchanged reason', () => {
    const first = api.push(SCOPE, 'o1', 'Manual push');
    const firstRequest = http.expectOne(PUSH_URL);
    const firstKey = firstRequest.request.headers.get('Idempotency-Key');

    // No flush yet -- the second click lands before the first settled.
    const second = api.push(SCOPE, 'o1', 'Manual push');
    const secondRequest = http.expectOne(PUSH_URL);

    expect(secondRequest.request.headers.get('Idempotency-Key')).toBe(firstKey);
    firstRequest.flush({ status: 'SUCCESS', state: 'SENT', errorCode: null, detail: null });
    secondRequest.flush({ status: 'SUCCESS', state: 'SENT', errorCode: null, detail: null });
    return Promise.all([first, second]);
  });

  it('mints a fresh Idempotency-Key once the previous push settled, even with the same reason', () => {
    const first = api.push(SCOPE, 'o1', 'Manual push');
    const firstRequest = http.expectOne(PUSH_URL);
    const firstKey = firstRequest.request.headers.get('Idempotency-Key');
    firstRequest.flush({ status: 'SUCCESS', state: 'SENT', errorCode: null, detail: null });

    return first.then(() => {
      const second = api.push(SCOPE, 'o1', 'Manual push');
      const secondRequest = http.expectOne(PUSH_URL);

      expect(secondRequest.request.headers.get('Idempotency-Key')).not.toBe(firstKey);
      secondRequest.flush({ status: 'SUCCESS', state: 'SENT', errorCode: null, detail: null });
      return second;
    });
  });

  it('mints a fresh Idempotency-Key when the reason changes -- a genuinely new intent', () => {
    const first = api.push(SCOPE, 'o1', 'Manual push');
    const firstRequest = http.expectOne(PUSH_URL);
    const firstKey = firstRequest.request.headers.get('Idempotency-Key');

    const second = api.push(SCOPE, 'o1', 'A different reason entirely');
    const secondRequest = http.expectOne(PUSH_URL);

    expect(secondRequest.request.headers.get('Idempotency-Key')).not.toBe(firstKey);
    firstRequest.flush({ status: 'SUCCESS', state: 'SENT', errorCode: null, detail: null });
    secondRequest.flush({ status: 'SUCCESS', state: 'SENT', errorCode: null, detail: null });
    return Promise.all([first, second]);
  });

  it('holds independent commands per order', () => {
    const forOrderA = api.push(SCOPE, 'order-a', 'Manual push');
    const requestA = http.expectOne(`${environment.apiBaseUrl}/api/v1/operations/tenants/t1/orders/order-a/pos-export/push`);
    const keyA = requestA.request.headers.get('Idempotency-Key');

    const forOrderB = api.push(SCOPE, 'order-b', 'Manual push');
    const requestB = http.expectOne(`${environment.apiBaseUrl}/api/v1/operations/tenants/t1/orders/order-b/pos-export/push`);
    const keyB = requestB.request.headers.get('Idempotency-Key');

    expect(keyA).not.toBe(keyB);
    requestA.flush({ status: 'SUCCESS', state: 'SENT', errorCode: null, detail: null });
    requestB.flush({ status: 'SUCCESS', state: 'SENT', errorCode: null, detail: null });
    return Promise.all([forOrderA, forOrderB]);
  });
});

describe('OrderPosExportApi.forOrder', () => {
  it('reads the export panel view for one order', async () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), OrderPosExportApi],
    });
    const api = TestBed.inject(OrderPosExportApi);
    const http = TestBed.inject(HttpTestingController);

    const resultPromise = api.forOrder(SCOPE, 'o1');
    const request = http.expectOne(`${environment.apiBaseUrl}/api/v1/operations/tenants/t1/orders/o1/pos-export`);
    expect(request.request.method).toBe('GET');
    request.flush({ posCapable: true, export: null });

    await expect(resultPromise).resolves.toEqual({ posCapable: true, export: null });
  });
});

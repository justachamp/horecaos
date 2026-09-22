import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../environments/environment';
import { OrderHandoverApi } from './order-handover-api';

const SCOPE = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };
const VERIFY_URL = `${environment.apiBaseUrl}/api/v1/operations/tenants/t1/marketplace/orders/o1/handover-verifications`;
const BYPASS_URL = `${environment.apiBaseUrl}/api/v1/operations/tenants/t1/marketplace/orders/o1/handover-bypasses`;

function setUp(): { api: OrderHandoverApi; http: HttpTestingController } {
  TestBed.configureTestingModule({
    providers: [provideHttpClient(), provideHttpClientTesting(), OrderHandoverApi],
  });
  return { api: TestBed.inject(OrderHandoverApi), http: TestBed.inject(HttpTestingController) };
}

describe('OrderHandoverApi.verify', () => {
  it('posts the code with an Idempotency-Key and no If-Match', () => {
    const { api, http } = setUp();

    api.verify(SCOPE, 'o1', '1234').subscribe();
    const request = http.expectOne(VERIFY_URL);

    expect(request.request.method).toBe('POST');
    expect(request.request.headers.has('Idempotency-Key')).toBe(true);
    expect(request.request.headers.has('If-Match')).toBe(false);
    expect(request.request.body).toEqual({ code: '1234' });
    request.flush({ verified: true, status: 'VERIFIED', attemptsRemaining: 2 });
  });

  // 2026-09-21 audit follow-up (a): verify() "consumes one attempt whether
  // or not the code matches" (its own doc). A lost-response retry minting a
  // fresh key used to burn a second attempt off the customer's limited
  // budget for a code that may already have verified.
  it('reuses the same Idempotency-Key for two still-in-flight verify() calls with the identical code', () => {
    const { api, http } = setUp();

    api.verify(SCOPE, 'o1', '1234').subscribe();
    const first = http.expectOne(VERIFY_URL);
    const firstKey = first.request.headers.get('Idempotency-Key');

    api.verify(SCOPE, 'o1', '1234').subscribe();
    const second = http.expectOne(VERIFY_URL);

    expect(second.request.headers.get('Idempotency-Key')).toBe(firstKey);
    first.flush({ verified: true, status: 'VERIFIED', attemptsRemaining: 2 });
    second.flush({ verified: true, status: 'VERIFIED', attemptsRemaining: 2 });
  });

  it('mints a fresh Idempotency-Key once a settled attempt is followed by a retyped code -- a real second attempt', () => {
    const { api, http } = setUp();

    api.verify(SCOPE, 'o1', '1234').subscribe();
    const first = http.expectOne(VERIFY_URL);
    const firstKey = first.request.headers.get('Idempotency-Key');
    first.flush({ verified: false, status: 'PENDING', attemptsRemaining: 2 });

    api.verify(SCOPE, 'o1', '1234').subscribe();
    const second = http.expectOne(VERIFY_URL);

    expect(second.request.headers.get('Idempotency-Key')).not.toBe(firstKey);
    second.flush({ verified: false, status: 'PENDING', attemptsRemaining: 1 });
  });

  it('mints a fresh Idempotency-Key when the code changes', () => {
    const { api, http } = setUp();

    api.verify(SCOPE, 'o1', '1234').subscribe();
    const first = http.expectOne(VERIFY_URL);
    const firstKey = first.request.headers.get('Idempotency-Key');

    api.verify(SCOPE, 'o1', '5678').subscribe();
    const second = http.expectOne(VERIFY_URL);

    expect(second.request.headers.get('Idempotency-Key')).not.toBe(firstKey);
    first.flush({ verified: false, status: 'PENDING', attemptsRemaining: 2 });
    second.flush({ verified: true, status: 'VERIFIED', attemptsRemaining: 2 });
  });
});

describe('OrderHandoverApi.bypass', () => {
  it('posts the reason and supervisor with an Idempotency-Key', () => {
    const { api, http } = setUp();

    api.bypass(SCOPE, 'o1', 'CUSTOMER_UNREACHABLE', 'A. Karimov').subscribe();
    const request = http.expectOne(BYPASS_URL);

    expect(request.request.headers.has('Idempotency-Key')).toBe(true);
    expect(request.request.body).toEqual({ reasonCode: 'CUSTOMER_UNREACHABLE', supervisorName: 'A. Karimov' });
    request.flush(null);
  });

  it('reuses the same Idempotency-Key for two still-in-flight bypass() calls with an unchanged body', () => {
    const { api, http } = setUp();

    api.bypass(SCOPE, 'o1', 'CUSTOMER_UNREACHABLE', 'A. Karimov').subscribe();
    const first = http.expectOne(BYPASS_URL);
    const firstKey = first.request.headers.get('Idempotency-Key');

    api.bypass(SCOPE, 'o1', 'CUSTOMER_UNREACHABLE', 'A. Karimov').subscribe();
    const second = http.expectOne(BYPASS_URL);

    expect(second.request.headers.get('Idempotency-Key')).toBe(firstKey);
    first.flush(null);
    second.flush(null);
  });

  it('holds verify and bypass as independent intents for the same order', () => {
    const { api, http } = setUp();

    api.verify(SCOPE, 'o1', '1234').subscribe();
    const verifyRequest = http.expectOne(VERIFY_URL);
    const verifyKey = verifyRequest.request.headers.get('Idempotency-Key');

    api.bypass(SCOPE, 'o1', 'CUSTOMER_UNREACHABLE', 'A. Karimov').subscribe();
    const bypassRequest = http.expectOne(BYPASS_URL);
    const bypassKey = bypassRequest.request.headers.get('Idempotency-Key');

    expect(bypassKey).not.toBe(verifyKey);
    verifyRequest.flush({ verified: true, status: 'VERIFIED', attemptsRemaining: 2 });
    bypassRequest.flush(null);
  });
});

describe('OrderHandoverApi.challenge', () => {
  it('resolves null (not an error) when the order was never issued a challenge', async () => {
    const { api, http } = setUp();

    const resultPromise = api.challenge(SCOPE, 'o1').toPromise();
    const request = http.expectOne(
      `${environment.apiBaseUrl}/api/v1/operations/tenants/t1/marketplace/orders/o1/handover-challenge`,
    );
    request.flush(
      { code: 'RESOURCE_NOT_FOUND', detail: 'No such challenge', status: 404 },
      { status: 404, statusText: 'Not Found' },
    );

    await expect(resultPromise).resolves.toBeNull();
  });
});

import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../environments/environment';
import { OrderActionsApi } from './order-actions-api';

const SCOPE = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };
const BASE = `${environment.apiBaseUrl}/api/v1/tenants/t1/brands/b1/locations/l1/orders/o1`;

describe('OrderActionsApi', () => {
  let api: OrderActionsApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), OrderActionsApi],
    });
    api = TestBed.inject(OrderActionsApi);
    http = TestBed.inject(HttpTestingController);
  });

  it('approves with the decisionId in the body and no If-Match — the decision is CAS by id, not by version', () => {
    api.approve(SCOPE, 'o1', 'decision-1').subscribe();
    const request = http.expectOne(`${BASE}/approval-decisions`);

    expect(request.request.body).toEqual({ decisionId: 'decision-1', action: 'APPROVE' });
    expect(request.request.headers.has('Idempotency-Key')).toBe(true);
    expect(request.request.headers.has('If-Match')).toBe(false);
    request.flush({ orderId: 'o1', status: 'CONFIRMED', version: 2, applied: true, effectiveDecisionId: null, effectiveAction: null });
  });

  it('rejects with the reason code', () => {
    api.reject(SCOPE, 'o1', 'decision-1', 'NO_STOCK').subscribe();
    const request = http.expectOne(`${BASE}/approval-decisions`);

    expect(request.request.body).toEqual({
      decisionId: 'decision-1',
      action: 'REJECT',
      reasonCode: 'NO_STOCK',
    });
    request.flush({});
  });

  it('reuses the same decisionId sent in across two calls when the caller passes the same value', () => {
    api.approve(SCOPE, 'o1', 'decision-1').subscribe();
    const first = http.expectOne(`${BASE}/approval-decisions`);
    first.flush({});

    api.approve(SCOPE, 'o1', 'decision-1').subscribe();
    const second = http.expectOne(`${BASE}/approval-decisions`);
    expect(second.request.body).toEqual({ decisionId: 'decision-1', action: 'APPROVE' });
    second.flush({});
  });

  it('advances with a target status, a synthesised reason, and If-Match carrying the expected version', () => {
    api.advance(SCOPE, 'o1', 'PREPARING', 4).subscribe();
    const request = http.expectOne(`${BASE}/state-actions`);

    expect(request.request.body).toEqual({
      targetStatus: 'PREPARING',
      reasonCode: 'OPERATIONS_ADVANCE_PREPARING',
    });
    expect(request.request.headers.get('If-Match')).toBe('W/"4"');
    request.flush({});
  });

  it('cancels with the reason code, an optional note, and If-Match', () => {
    api.cancel(SCOPE, 'o1', 4, 'CUSTOMER_CHANGED_MIND', 'Called back to cancel').subscribe();
    const request = http.expectOne(`${BASE}/cancellations`);

    expect(request.request.body).toEqual({
      reasonCode: 'CUSTOMER_CHANGED_MIND',
      note: 'Called back to cancel',
    });
    expect(request.request.headers.get('If-Match')).toBe('W/"4"');
    request.flush({});
  });

  it('omits note entirely rather than sending an empty string', () => {
    api.cancel(SCOPE, 'o1', 4, 'CUSTOMER_CHANGED_MIND').subscribe();
    const request = http.expectOne(`${BASE}/cancellations`);

    expect(request.request.body).toEqual({ reasonCode: 'CUSTOMER_CHANGED_MIND' });
    request.flush({});
  });
});

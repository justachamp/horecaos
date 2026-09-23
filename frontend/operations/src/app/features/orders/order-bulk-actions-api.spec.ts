import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../environments/environment';
import { OrderBulkActionsApi } from './order-bulk-actions-api';

const SCOPE = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };
const URL = `${environment.apiBaseUrl}/api/v1/tenants/t1/brands/b1/locations/l1/orders/bulk-actions`;

describe('OrderBulkActionsApi', () => {
  let api: OrderBulkActionsApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), OrderBulkActionsApi],
    });
    api = TestBed.inject(OrderBulkActionsApi);
    http = TestBed.inject(HttpTestingController);
  });

  it('posts to .../orders/bulk-actions with an Idempotency-Key and no If-Match — the aggregate is many orders, not one', () => {
    api
      .submit(SCOPE, {
        actionType: 'CANCEL',
        orders: [{ orderId: 'o1', expectedVersion: 2 }],
        cancelReasonId: 'reason-1',
      })
      .subscribe();

    const request = http.expectOne(URL);
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.has('Idempotency-Key')).toBe(true);
    expect(request.request.headers.has('If-Match')).toBe(false);
    expect(request.request.body).toEqual({
      actionType: 'CANCEL',
      orders: [{ orderId: 'o1', expectedVersion: 2 }],
      cancelReasonId: 'reason-1',
    });
    request.flush({
      bulkOperationId: 'bulk-1',
      actionType: 'CANCEL',
      requestedCount: 1,
      appliedCount: 1,
      failedCount: 0,
      replayed: false,
      items: [{ orderId: 'o1', itemStatus: 'APPLIED', resultingOrderVersion: 3 }],
    });
  });

  it('sends targetStatus and reasonCode for ADVANCE', () => {
    api
      .submit(SCOPE, {
        actionType: 'ADVANCE',
        orders: [
          { orderId: 'o1', expectedVersion: 2 },
          { orderId: 'o2', expectedVersion: 5 },
        ],
        targetStatus: 'PREPARING',
        reasonCode: 'OPERATIONS_ADVANCE_PREPARING',
      })
      .subscribe();

    const request = http.expectOne(URL);
    expect(request.request.body).toEqual({
      actionType: 'ADVANCE',
      orders: [
        { orderId: 'o1', expectedVersion: 2 },
        { orderId: 'o2', expectedVersion: 5 },
      ],
      targetStatus: 'PREPARING',
      reasonCode: 'OPERATIONS_ADVANCE_PREPARING',
    });
    request.flush({
      bulkOperationId: 'bulk-2',
      actionType: 'ADVANCE',
      requestedCount: 2,
      appliedCount: 2,
      failedCount: 0,
      replayed: false,
      items: [],
    });
  });

  it('mints a fresh Idempotency-Key on every call — a retry is a new intent, never a resubmission', () => {
    api
      .submit(SCOPE, { actionType: 'CANCEL', orders: [{ orderId: 'o1', expectedVersion: 2 }] })
      .subscribe();
    const first = http.expectOne(URL);
    const firstKey = first.request.headers.get('Idempotency-Key');
    first.flush({
      bulkOperationId: 'bulk-1',
      actionType: 'CANCEL',
      requestedCount: 1,
      appliedCount: 0,
      failedCount: 1,
      replayed: false,
      items: [{ orderId: 'o1', itemStatus: 'FAILED', itemProblemCode: 'STALE_VERSION' }],
    });

    api
      .submit(SCOPE, { actionType: 'CANCEL', orders: [{ orderId: 'o1', expectedVersion: 3 }] })
      .subscribe();
    const second = http.expectOne(URL);

    expect(second.request.headers.get('Idempotency-Key')).not.toBe(firstKey);
    second.flush({
      bulkOperationId: 'bulk-3',
      actionType: 'CANCEL',
      requestedCount: 1,
      appliedCount: 1,
      failedCount: 0,
      replayed: false,
      items: [{ orderId: 'o1', itemStatus: 'APPLIED', resultingOrderVersion: 4 }],
    });
  });

  // 2026-09-21 audit follow-up (a): a double click on "Apply" before the
  // panel shows busy used to mint two independent keys and could run the
  // same bulk action twice on up to 200 orders. The test above already
  // proves a *narrowed* retry (a different body) still mints fresh, which
  // is what the class's own doc argues for -- this proves the other half.
  it('reuses the same Idempotency-Key for two still-in-flight submissions carrying the identical, un-narrowed request', () => {
    const request = {
      actionType: 'CANCEL' as const,
      orders: [{ orderId: 'o1', expectedVersion: 2 }],
      cancelReasonId: 'reason-1',
    };

    api.submit(SCOPE, request).subscribe();
    const first = http.expectOne(URL);
    const firstKey = first.request.headers.get('Idempotency-Key');

    // No flush yet: the second click lands before the first request settled.
    api.submit(SCOPE, request).subscribe();
    const second = http.expectOne(URL);

    expect(second.request.headers.get('Idempotency-Key')).toBe(firstKey);
    first.flush({
      bulkOperationId: 'bulk-1',
      actionType: 'CANCEL',
      requestedCount: 1,
      appliedCount: 1,
      failedCount: 0,
      replayed: false,
      items: [{ orderId: 'o1', itemStatus: 'APPLIED', resultingOrderVersion: 3 }],
    });
    second.flush({
      bulkOperationId: 'bulk-1',
      actionType: 'CANCEL',
      requestedCount: 1,
      appliedCount: 1,
      failedCount: 0,
      replayed: true,
      items: [{ orderId: 'o1', itemStatus: 'APPLIED', resultingOrderVersion: 3 }],
    });
  });

  it('mints a fresh Idempotency-Key for a later, unrelated submission once the previous one completed', () => {
    const request = {
      actionType: 'CANCEL' as const,
      orders: [{ orderId: 'o1', expectedVersion: 2 }],
      cancelReasonId: 'reason-1',
    };

    api.submit(SCOPE, request).subscribe();
    const first = http.expectOne(URL);
    const firstKey = first.request.headers.get('Idempotency-Key');
    first.flush({
      bulkOperationId: 'bulk-1',
      actionType: 'CANCEL',
      requestedCount: 1,
      appliedCount: 1,
      failedCount: 0,
      replayed: false,
      items: [{ orderId: 'o1', itemStatus: 'APPLIED', resultingOrderVersion: 3 }],
    });

    // Same body, but the operator opened the panel again after the first
    // completed -- a genuinely new action, not a retry of the same click.
    api.submit(SCOPE, request).subscribe();
    const second = http.expectOne(URL);

    expect(second.request.headers.get('Idempotency-Key')).not.toBe(firstKey);
    second.flush({
      bulkOperationId: 'bulk-2',
      actionType: 'CANCEL',
      requestedCount: 1,
      appliedCount: 1,
      failedCount: 0,
      replayed: false,
      items: [{ orderId: 'o1', itemStatus: 'APPLIED', resultingOrderVersion: 3 }],
    });
  });
});

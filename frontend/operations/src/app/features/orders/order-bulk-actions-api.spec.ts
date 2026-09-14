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
});

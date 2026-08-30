import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiClient } from '../../core/api/api-client';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { OrderCountsResponse } from './order-detail';
import { CountableOrder, OrderCounts, zeroTabCounts } from './order-counts';

const NOW = new Date('2026-08-30T12:00:00Z');
const SCOPE = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function order(overrides: Partial<CountableOrder>): CountableOrder {
  return {
    status: 'RECEIVED',
    createdAt: NOW,
    approvalDeadlineAt: null,
    hasBlockedProcess: false,
    ...overrides,
  };
}

function countsResponse(overrides: Partial<OrderCountsResponse> = {}): OrderCountsResponse {
  return {
    newOrders: 0,
    awaitingApproval: 0,
    inKitchen: 0,
    ready: 0,
    fulfilling: 0,
    completed: 0,
    cancelled: 0,
    totalNonTerminal: 0,
    total: 0,
    ...overrides,
  };
}

/** Configures a fresh `OrderCounts` behind a stubbed `ApiClient.get`. */
function configure(get: ReturnType<typeof vi.fn>): OrderCounts {
  TestBed.configureTestingModule({ providers: [{ provide: ApiClient, useValue: { get } }] });
  return TestBed.inject(OrderCounts);
}

describe('OrderCounts: the client-derived fallback (no counts endpoint reachable)', () => {
  const erroring = () => vi.fn().mockReturnValue(throwError(() => new Error('unreachable')));

  it('counts nothing for an empty page', async () => {
    const counts = configure(erroring());
    expect(await counts.forOrders(SCOPE, [], NOW)).toEqual(zeroTabCounts());
  });

  it('counts each order into every tab it belongs to, since attention is not a partition', async () => {
    const counts = configure(erroring());
    const orders = [
      order({ status: 'AWAITING_APPROVAL' }), // attention + new
      order({ status: 'RECEIVED' }), // new only
      order({ status: 'PREPARING' }), // preparing only
      order({ status: 'FULFILLING' }), // delivering only
      order({ status: 'COMPLETED' }), // completed only
      order({ status: 'CANCELLED' }), // cancelled only
    ];

    const result = await counts.forOrders(SCOPE, orders, NOW);

    expect(result.attention).toBe(1);
    expect(result.new).toBe(2);
    expect(result.preparing).toBe(1);
    expect(result.delivering).toBe(1);
    expect(result.completed).toBe(1);
    expect(result.cancelled).toBe(1);
    expect(result.all).toBe(orders.length);
  });

  it('counts a stalled order (no promise, 45+ minutes old) into attention even though its status is not', async () => {
    const counts = configure(erroring());
    const stalled = order({
      status: 'PREPARING',
      createdAt: new Date(NOW.getTime() - 50 * 60 * 1000),
    });

    expect((await counts.forOrders(SCOPE, [stalled], NOW)).attention).toBe(1);
  });

  it('never counts a terminal order into attention, however old', async () => {
    const counts = configure(erroring());
    const oldButDone = order({
      status: 'COMPLETED',
      createdAt: new Date(NOW.getTime() - 500 * 60 * 1000),
    });

    expect((await counts.forOrders(SCOPE, [oldButDone], NOW)).attention).toBe(0);
  });

  it('falls back to full client derivation on a denied capability, not just a network error', async () => {
    const denied = vi
      .fn()
      .mockReturnValue(throwError(() => new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null)));
    const counts = configure(denied);
    const orders = [order({ status: 'FULFILLING' })];

    expect((await counts.forOrders(SCOPE, orders, NOW)).delivering).toBe(1);
  });
});

describe('OrderCounts: consuming GET .../orders/counts', () => {
  it('maps the six status-based tabs straight from the endpoint', async () => {
    const get = vi.fn().mockReturnValue(
      of({
        value: countsResponse({
          newOrders: 4,
          inKitchen: 10,
          ready: 8,
          fulfilling: 12,
          completed: 96,
          cancelled: 7,
          total: 137,
        }),
        version: null,
      }),
    );
    const counts = configure(get);

    const result = await counts.forOrders(SCOPE, [], NOW);

    expect(result.new).toBe(4);
    // preparing = inKitchen + ready — CONFIRMED ∪ PREPARING ∪ READY combined.
    expect(result.preparing).toBe(18);
    expect(result.delivering).toBe(12);
    expect(result.completed).toBe(96);
    expect(result.cancelled).toBe(7);
    expect(result.all).toBe(137);
  });

  it('still derives attention from the loaded orders even when the endpoint succeeds, since the endpoint cannot answer it', async () => {
    const get = vi.fn().mockReturnValue(of({ value: countsResponse(), version: null }));
    const counts = configure(get);
    const orders = [order({ status: 'AWAITING_APPROVAL' }), order({ status: 'PAYMENT_FAILED' })];

    const result = await counts.forOrders(SCOPE, orders, NOW);

    expect(result.attention).toBe(2);
    // The endpoint's own zeroed fields are trusted for everything else.
    expect(result.new).toBe(0);
  });

  it('requests the location-scoped counts path', async () => {
    const get = vi.fn().mockReturnValue(of({ value: countsResponse(), version: null }));
    const counts = configure(get);

    await counts.forOrders(SCOPE, [], NOW);

    expect(get).toHaveBeenCalledWith('/api/v1/tenants/t1/brands/b1/locations/l1/orders/counts');
  });
});

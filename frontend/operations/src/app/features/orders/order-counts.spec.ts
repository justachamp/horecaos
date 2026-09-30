import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiClient } from '../../core/api/api-client';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { PLATFORM_DEFAULT_LATENESS_POLICY } from '../../core/lateness-policy';
import { OrderBrandCountsResponse, OrderCountsResponse } from './order-detail';
import { CountableOrder, OrderCounts, zeroTabCounts } from './order-counts';

const NOW = new Date('2026-08-30T12:00:00Z');
const SCOPE = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };
const POLICY = PLATFORM_DEFAULT_LATENESS_POLICY;

function order(overrides: Partial<CountableOrder>): CountableOrder {
  return {
    status: 'RECEIVED',
    createdAt: NOW,
    approvalDeadlineAt: null,
    fulfillmentMode: 'DELIVERY',
    promisedAt: null,
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
    expect(await counts.forOrders(SCOPE, [], NOW, POLICY)).toEqual(zeroTabCounts());
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

    const result = await counts.forOrders(SCOPE, orders, NOW, POLICY);

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

    expect((await counts.forOrders(SCOPE, [stalled], NOW, POLICY)).attention).toBe(1);
  });

  it('never counts a terminal order into attention, however old', async () => {
    const counts = configure(erroring());
    const oldButDone = order({
      status: 'COMPLETED',
      createdAt: new Date(NOW.getTime() - 500 * 60 * 1000),
    });

    expect((await counts.forOrders(SCOPE, [oldButDone], NOW, POLICY)).attention).toBe(0);
  });

  it('falls back to full client derivation on a denied capability, not just a network error', async () => {
    const denied = vi
      .fn()
      .mockReturnValue(
        throwError(() => new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null)),
      );
    const counts = configure(denied);
    const orders = [order({ status: 'FULFILLING' })];

    expect((await counts.forOrders(SCOPE, orders, NOW, POLICY)).delivering).toBe(1);
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

    const result = await counts.forOrders(SCOPE, [], NOW, POLICY);

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

    const result = await counts.forOrders(SCOPE, orders, NOW, POLICY);

    expect(result.attention).toBe(2);
    // The endpoint's own zeroed fields are trusted for everything else.
    expect(result.new).toBe(0);
  });

  it('requests the location-scoped counts path', async () => {
    const get = vi.fn().mockReturnValue(of({ value: countsResponse(), version: null }));
    const counts = configure(get);

    await counts.forOrders(SCOPE, [], NOW, POLICY);

    expect(get).toHaveBeenCalledWith('/api/v1/tenants/t1/brands/b1/locations/l1/orders/counts');
  });
});

describe('OrderCounts: the brand-wide read (wave 16, «Все филиалы»)', () => {
  it('reads the brand’s totals, from the brand counts endpoint rather than a branch’s', async () => {
    const get = vi.fn().mockReturnValue(
      of({
        value: {
          totals: countsResponse({
            newOrders: 7,
            inKitchen: 4,
            ready: 2,
            fulfilling: 3,
            completed: 20,
            cancelled: 5,
            total: 41,
          }),
        } satisfies OrderBrandCountsResponse,
        version: null,
      }),
    );
    const counts = configure(get);

    const result = await counts.forOrders(SCOPE, [], NOW, POLICY, true);

    expect(get.mock.calls[0][0]).toBe('/api/v1/operations/tenants/t1/brands/b1/orders/counts');
    expect(result).toEqual({
      attention: 0,
      new: 7,
      preparing: 6,
      delivering: 3,
      completed: 20,
      cancelled: 5,
      all: 41,
    });
  });

  it('keeps Внимание derived from the loaded orders, exactly as the branch read does', async () => {
    const get = vi
      .fn()
      .mockReturnValue(of({ value: { totals: countsResponse({ total: 9 }) }, version: null }));
    const counts = configure(get);

    const result = await counts.forOrders(
      SCOPE,
      [order({ status: 'AWAITING_APPROVAL' })],
      NOW,
      POLICY,
      true,
    );

    expect(result.attention).toBe(1);
    expect(result.all).toBe(9);
  });

  it('falls back to deriving every tab from the page when the brand read is refused', async () => {
    const get = vi
      .fn()
      .mockReturnValue(
        throwError(() => new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null)),
      );
    const counts = configure(get);

    const result = await counts.forOrders(
      SCOPE,
      [order({ status: 'PREPARING' }), order({ status: 'COMPLETED' })],
      NOW,
      POLICY,
      true,
    );

    expect(result.preparing).toBe(1);
    expect(result.completed).toBe(1);
    expect(result.all).toBe(2);
  });

  it('does not mistake a branch-shaped answer for the brand’s (no totals key)', async () => {
    const get = vi
      .fn()
      .mockReturnValue(of({ value: countsResponse({ total: 99 }), version: null }));
    const counts = configure(get);

    const result = await counts.forOrders(
      SCOPE,
      [order({ status: 'RECEIVED' })],
      NOW,
      POLICY,
      true,
    );

    expect(
      result.all,
      'derived from the one order, not read from a shape the brand endpoint never sends',
    ).toBe(1);
  });
});

describe('OrderCounts: a policy per order (wave 16)', () => {
  /** A branch that warns an hour ahead of the promise, where the platform default warns five minutes ahead. */
  const WARNS_EARLY = {
    delivery: { atRiskBeforeSeconds: 3600, lateAfterSeconds: 0, noPromiseFallbackSeconds: 2700 },
    pickup: { atRiskBeforeSeconds: 3600, lateAfterSeconds: 0, noPromiseFallbackSeconds: 2700 },
    dineIn: { atRiskBeforeSeconds: 3600, lateAfterSeconds: 0, noPromiseFallbackSeconds: 2700 },
  };

  it('counts Внимание by each order’s own branch policy', async () => {
    const counts = configure(vi.fn().mockReturnValue(throwError(() => new Error('unreachable'))));
    // Promised twenty minutes from now: comfortably normal under the default's five-minute warning,
    // already at risk under a branch that warns an hour ahead.
    const promised = new Date(NOW.getTime() + 20 * 60_000);
    const orders = [
      order({ status: 'PREPARING', promisedAt: promised, locationId: 'default-branch' }),
      order({ status: 'PREPARING', promisedAt: promised, locationId: 'early-branch' }),
    ];

    const result = await counts.forOrders(SCOPE, orders, NOW, (o) =>
      o.locationId === 'early-branch' ? WARNS_EARLY : POLICY,
    );

    expect(result.attention, 'at risk at the early-warning branch only').toBe(1);
  });

  it('a single policy still judges every order alike', async () => {
    const counts = configure(vi.fn().mockReturnValue(throwError(() => new Error('unreachable'))));
    const promised = new Date(NOW.getTime() + 20 * 60_000);
    const orders = [
      order({ status: 'PREPARING', promisedAt: promised, locationId: 'a' }),
      order({ status: 'PREPARING', promisedAt: promised, locationId: 'b' }),
    ];

    expect((await counts.forOrders(SCOPE, orders, NOW, WARNS_EARLY)).attention).toBe(2);
    expect((await counts.forOrders(SCOPE, orders, NOW, POLICY)).attention).toBe(0);
  });
});

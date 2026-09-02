import { TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { ApiClient } from '../../core/api/api-client';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { OrderCountsResponse } from '../orders/order-detail';
import { OrderSummaryResponse } from '../orders/order-summary';
import { LiveBoard } from './live-board';

const SCOPE = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

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

function order(overrides: Partial<OrderSummaryResponse>): OrderSummaryResponse {
  return {
    orderId: 'o1',
    publicOrderNumber: '0001',
    status: 'PREPARING',
    createdAt: new Date().toISOString(),
    totalMinor: 10_000,
    currency: 'UZS',
    ...overrides,
  };
}

const denied = () =>
  throwError(() => new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null));
const broken = () => throwError(() => new ApiError('INTERNAL_ERROR', 500, null, 'corr-1'));

function ok<T>(value: T) {
  return () => of({ value, version: null });
}

/**
 * Routes a stubbed `ApiClient.get` by the first matching path substring, the
 * same shape `order-counts.spec.ts` uses — this file exercises `LiveBoard`'s
 * own composition of several calls, not the wire contract of any one of
 * them. **Order the entries most-specific first**: `.find` returns the first
 * match, and every real path here nests inside `.../locations/{id}/...`, so
 * a generic `/locations` entry has to come last or it swallows everything.
 */
type StubbedGet = () => Observable<{ readonly value: unknown; readonly version: number | null }>;

function configure(byPath: ReadonlyArray<{ match: string; response: StubbedGet }>): LiveBoard {
  const get = vi.fn().mockImplementation((path: string) => {
    const route = byPath.find((candidate) => path.includes(candidate.match));
    if (!route) {
      throw new Error(`unstubbed path: ${path}`);
    }
    return route.response();
  });
  TestBed.configureTestingModule({ providers: [{ provide: ApiClient, useValue: { get } }] });
  return TestBed.inject(LiveBoard);
}

describe('LiveBoard: counters and mixes', () => {
  it('reuses GET .../orders/counts verbatim for the oversized counters', async () => {
    const board = configure([
      {
        match: '/orders/counts',
        response: ok(countsResponse({ totalNonTerminal: 6, cancelled: 2 })),
      },
      { match: '/orders', response: ok([]) },
      { match: '/locations', response: denied },
    ]);

    const snapshot = await board.load(SCOPE);

    expect(snapshot.counts.totalNonTerminal).toBe(6);
    expect(snapshot.counts.cancelled).toBe(2);
  });

  it('computes the source and type mix from the in-progress orders, largest first', async () => {
    const activeOrders = [
      order({ orderId: 'a', channelCode: 'TELEGRAM_BOT', fulfillmentMode: 'DELIVERY' }),
      order({ orderId: 'b', channelCode: 'TELEGRAM_BOT', fulfillmentMode: 'PICKUP' }),
      order({ orderId: 'c', channelCode: 'WEBSITE', fulfillmentMode: 'DELIVERY' }),
    ];
    const board = configure([
      { match: '/orders/counts', response: ok(countsResponse()) },
      { match: '/orders', response: ok(activeOrders) },
      { match: '/locations', response: denied },
    ]);

    const snapshot = await board.load(SCOPE);

    expect(snapshot.sourceMix).toEqual([
      { key: 'TELEGRAM_BOT', count: 2 },
      { key: 'WEBSITE', count: 1 },
    ]);
    expect(snapshot.typeMix).toEqual([
      { key: 'DELIVERY', count: 2 },
      { key: 'PICKUP', count: 1 },
    ]);
  });

  it('buckets a missing channel or fulfilment mode under the em-dash rather than dropping the order', async () => {
    const board = configure([
      { match: '/orders/counts', response: ok(countsResponse()) },
      { match: '/orders', response: ok([order({ channelCode: null, fulfillmentMode: null })]) },
      { match: '/locations', response: denied },
    ]);

    const snapshot = await board.load(SCOPE);

    expect(snapshot.sourceMix).toEqual([{ key: '—', count: 1 }]);
    expect(snapshot.typeMix).toEqual([{ key: '—', count: 1 }]);
  });
});

describe('LiveBoard: the branch band', () => {
  it('reads the full brand roster and one counts call per active branch when BRAND-scope LOCATION_READ is held', async () => {
    const roster = [
      { id: 'l1', displayName: 'Чиланзар', status: 'ACTIVE' },
      { id: 'l2', displayName: 'Юнусабад', status: 'ACTIVE' },
      { id: 'l3', displayName: 'Закрытый', status: 'ARCHIVED' },
    ];
    const board = configure([
      {
        match: '/locations/l1/orders/counts',
        response: ok(countsResponse({ totalNonTerminal: 3 })),
      },
      {
        match: '/locations/l2/orders/counts',
        response: ok(countsResponse({ totalNonTerminal: 9 })),
      },
      { match: '/orders/counts', response: ok(countsResponse()) },
      { match: '/orders', response: ok([]) },
      { match: '/locations', response: ok(roster) },
    ]);

    const snapshot = await board.load(SCOPE);

    expect(snapshot.branchesAvailable).toBe(true);
    expect(snapshot.branchesTotal).toBe(2); // the archived branch never enters the roster
    expect(snapshot.branchesShown).toBe(2);
    // Sorted by active load, busiest first — the leaderboard shape.
    expect(snapshot.branches).toEqual([
      { locationId: 'l2', displayName: 'Юнусабад', inProgress: 9 },
      { locationId: 'l1', displayName: 'Чиланзар', inProgress: 3 },
    ]);
  });

  it("falls back to the operator's own single branch when the brand roster is denied — the common pilot shape", async () => {
    const board = configure([
      { match: '/orders/counts', response: ok(countsResponse({ totalNonTerminal: 4 })) },
      { match: '/orders', response: ok([]) },
      {
        match: '/locations/l1',
        response: ok({ id: 'l1', displayName: 'Own branch', status: 'ACTIVE' }),
      },
      { match: '/locations', response: denied },
    ]);

    const snapshot = await board.load(SCOPE);

    expect(snapshot.branchesAvailable).toBe(true);
    expect(snapshot.branchesShown).toBe(1);
    expect(snapshot.branchesTotal).toBe(1);
    expect(snapshot.branches).toEqual([
      { locationId: 'l1', displayName: 'Own branch', inProgress: 4 },
    ]);
  });

  it('reports branchesAvailable: false, not an empty leaderboard, when even the fallback read fails', async () => {
    const board = configure([
      { match: '/orders/counts', response: ok(countsResponse()) },
      { match: '/orders', response: ok([]) },
      { match: '/locations', response: broken },
    ]);

    const snapshot = await board.load(SCOPE);

    expect(snapshot.branchesAvailable).toBe(false);
    expect(snapshot.branches).toEqual([]);
  });

  it('omits a branch it cannot read the counts of, and reflects that in shown-vs-total rather than guessing', async () => {
    const roster = [
      { id: 'l1', displayName: 'Own branch', status: 'ACTIVE' },
      { id: 'l2', displayName: 'Sibling branch', status: 'ACTIVE' },
    ];
    const board = configure([
      {
        match: '/locations/l1/orders/counts',
        response: ok(countsResponse({ totalNonTerminal: 5 })),
      },
      { match: '/locations/l2/orders/counts', response: denied },
      { match: '/orders/counts', response: ok(countsResponse()) },
      { match: '/orders', response: ok([]) },
      { match: '/locations', response: ok(roster) },
    ]);

    const snapshot = await board.load(SCOPE);

    expect(snapshot.branchesTotal).toBe(2);
    expect(snapshot.branchesShown).toBe(1);
    expect(snapshot.branches).toEqual([
      { locationId: 'l1', displayName: 'Own branch', inProgress: 5 },
    ]);
  });
});

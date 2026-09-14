import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { ApiClient } from '../core/api/api-client';
import { LocationScope } from '../core/api/operations-paths';
import { CurrentLocation } from '../core/auth/current-location';
import { PLATFORM_DEFAULT_LATENESS_POLICY } from '../core/lateness-policy';
import { LatenessPolicyApi } from '../core/lateness-policy-api';
import { RealtimeClient, RealtimeFrame } from '../core/realtime/realtime-client';
import { OrderCountsResponse } from '../features/orders/order-detail';
import { OrderSummaryResponse } from '../features/orders/order-summary';
import { ServiceStatus } from './service-status';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

/** Flushes a promise chain several `await`s deep — the same shape `settings-scope.spec.ts` already uses for its own `effect()`-driven async chain. */
async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

function countsResponse(overrides: Partial<OrderCountsResponse> = {}): OrderCountsResponse {
  return {
    newOrders: 2,
    awaitingApproval: 1,
    inKitchen: 3,
    ready: 1,
    fulfilling: 1,
    completed: 10,
    cancelled: 2,
    totalNonTerminal: 8,
    total: 20,
    ...overrides,
  };
}

function order(overrides: Partial<OrderSummaryResponse> = {}): OrderSummaryResponse {
  return {
    orderId: 'o1',
    publicOrderNumber: 'N-1',
    status: 'CONFIRMED',
    createdAt: '2026-09-14T08:00:00Z',
    fulfillmentMode: 'DELIVERY',
    channelCode: 'WEB',
    currency: 'UZS',
    totalMinor: 45_000,
    ...overrides,
  } as OrderSummaryResponse;
}

describe('ServiceStatus', () => {
  function setUp(
    get: ReturnType<typeof vi.fn>,
    scope: LocationScope | null = SCOPE,
  ): {
    status: ServiceStatus;
    onFrame: ReturnType<typeof vi.fn>;
    frameListeners: Array<(frame: RealtimeFrame) => void>;
  } {
    const frameListeners: Array<(frame: RealtimeFrame) => void> = [];
    const onFrame = vi.fn((listener: (frame: RealtimeFrame) => void) => {
      frameListeners.push(listener);
      return () => undefined;
    });
    TestBed.configureTestingModule({
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(scope),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        {
          provide: LatenessPolicyApi,
          useValue: { resolve: vi.fn().mockResolvedValue(PLATFORM_DEFAULT_LATENESS_POLICY) },
        },
        { provide: RealtimeClient, useValue: { onFrame, state: signal('open') } },
        { provide: ApiClient, useValue: { get } },
      ],
    });
    return { status: TestBed.inject(ServiceStatus), onFrame, frameListeners };
  }

  function apiGet(
    counts: OrderCountsResponse,
    orders: readonly OrderSummaryResponse[],
  ): ReturnType<typeof vi.fn> {
    return vi.fn((path: string) => {
      if (path.endsWith('/counts')) {
        return of({ value: counts, version: null });
      }
      return of({ value: orders, version: null });
    });
  }

  it('start() fetches its own counts independently — the shell no longer waits for Orders to be opened', async () => {
    const get = apiGet(countsResponse({ totalNonTerminal: 8 }), [
      order({ orderId: 'o1', status: 'CONFIRMED' }),
    ]);
    const { status } = setUp(get);

    status.start();
    await flushMicrotasks();

    expect(get).toHaveBeenCalled();
    expect(status.openCount()).toBe(8);
    expect(status.updatedAt()).not.toBeNull();
  });

  it('start() is idempotent — a second call fetches no more than the first did', async () => {
    const get = apiGet(countsResponse(), []);
    const { status } = setUp(get);

    status.start();
    status.start();
    await flushMicrotasks();

    // Two calls per refresh (counts + orders); a second start() must not double it.
    expect(get).toHaveBeenCalledTimes(2);
  });

  it('set() still works for a caller that already fetched (order-queue.ts)', () => {
    const { status } = setUp(vi.fn());

    status.set({ open: 5, late: 2 });

    expect(status.openCount()).toBe(5);
    expect(status.lateCount()).toBe(2);
    expect(status.updatedAt()).not.toBeNull();
  });

  it('does nothing when no location is resolved, rather than calling the API with an undefined scope', async () => {
    const get = vi.fn();
    const { status } = setUp(get, null);

    status.start();
    await flushMicrotasks();

    expect(get).not.toHaveBeenCalled();
  });

  it('a COUNTERS snapshot frame updates openCount immediately, without waiting for the next refresh()', () => {
    const { status, frameListeners } = setUp(vi.fn());
    expect(frameListeners).toHaveLength(1);

    frameListeners[0]({
      kind: 'snapshot',
      channel: 'counters',
      scope: 'LOCATION:l1',
      occurredAt: '2026-09-14T09:00:00Z',
      snapshot: countsResponse({ totalNonTerminal: 11 }),
    });

    expect(status.openCount()).toBe(11);
    expect(status.updatedAt()).not.toBeNull();
  });

  it('a snapshot frame on a different channel is ignored', () => {
    const { status, frameListeners } = setUp(vi.fn());

    frameListeners[0]({
      kind: 'snapshot',
      channel: 'courier_positions',
      scope: 'LOCATION:l1',
      occurredAt: '2026-09-14T09:00:00Z',
      snapshot: { pins: [] },
    });

    expect(status.openCount()).toBe(0);
  });
});

import { TestBed } from '@angular/core/testing';
import { firstValueFrom, of, throwError } from 'rxjs';

import { OrdersService, type OrderResponse, type OrderSummaryResponse } from './orders.service';
import { ApiClient } from '../core/api/api-client';
import { APP_CONFIG, type AppConfig } from '../core/config/app-config';
import { HorecaOSApiError } from '../core/api/problem-details';

const CONFIG: AppConfig = {
  apiBaseUrl: '/api/v1',
  tenantId: '10000000-0000-0000-0000-000000000001',
  brandId: '10000000-0000-0000-0000-000000000002',
  defaultLocationId: '10000000-0000-0000-0000-000000000003',
  channel: 'STOREFRONT',
  yandexMapsApiKey: '',
};

class FakeApiClient {
  get = vi.fn();
  list = vi.fn();
  mutate = vi.fn();
}

function setUp(): { service: OrdersService; api: FakeApiClient } {
  const api = new FakeApiClient();
  TestBed.configureTestingModule({
    providers: [
      { provide: ApiClient, useValue: api },
      { provide: APP_CONFIG, useValue: CONFIG },
    ],
  });
  return { service: TestBed.inject(OrdersService), api };
}

function summary(orderId: string, status: string, version = 1): OrderSummaryResponse {
  return {
    orderId,
    publicOrderNumber: `PN-${orderId}`,
    locationId: 'loc-1',
    fulfillmentMode: 'DELIVERY',
    status,
    paymentStatus: null,
    fulfillmentStatus: null,
    currency: 'UZS',
    totalMinor: 1000,
    promisedAt: null,
    version,
    placedAt: '2026-01-01T00:00:00Z',
  };
}

function orderResponse(orderId: string, version: number, status = 'RECEIVED'): OrderResponse {
  return {
    orderId,
    publicOrderNumber: `PN-${orderId}`,
    status,
    currency: 'UZS',
    subtotalMinor: 1000,
    taxMinor: 0,
    totalMinor: 1000,
    version,
    createdAt: '2026-01-01T00:00:00Z',
    confirmedAt: null,
    lines: [],
    warnings: [],
  };
}

describe('OrdersService.getOrders (status -> tab mapping)', () => {
  // One order per platform status the mapping table names.
  const ALL_STATUSES = [
    'RECEIVED',
    'PAYMENT_AUTHORIZING',
    'AWAITING_APPROVAL',
    'CONFIRMED',
    'PREPARING',
    'READY',
    'FULFILLING',
    'COMPLETED',
    'CANCELLED',
    'REJECTED',
    'EXPIRED',
    'PAYMENT_FAILED',
  ];

  it.each([
    ['new', ['RECEIVED', 'PAYMENT_AUTHORIZING', 'AWAITING_APPROVAL']],
    ['accepted', ['CONFIRMED']],
    ['cooking', ['PREPARING']],
    ['ready', ['READY']],
    ['delivering', ['FULFILLING']],
    ['completed', ['COMPLETED']],
    ['cancelled', ['CANCELLED', 'REJECTED', 'EXPIRED', 'PAYMENT_FAILED']],
  ] as const)('tab "%s" resolves to exactly %j', async (tab, expectedStatuses) => {
    const { service, api } = setUp();
    api.list.mockResolvedValue({
      items: ALL_STATUSES.map((status, i) => summary(`o${i}`, status)),
      nextCursor: null,
    });

    const orders = await firstValueFrom(service.getOrders([tab]));

    const gotStatuses = orders.map((o) => o.status?.id).sort();
    expect(gotStatuses).toEqual([...expectedStatuses].sort());
  });

  it('an empty status list means every order, unfiltered', async () => {
    const { service, api } = setUp();
    api.list.mockResolvedValue({
      items: ALL_STATUSES.map((status, i) => summary(`o${i}`, status)),
      nextCursor: null,
    });

    const orders = await firstValueFrom(service.getOrders([]));

    expect(orders).toHaveLength(ALL_STATUSES.length);
  });

  it('a tab token nobody recognises maps to no statuses, and an empty wanted set reads the same as "no filter" -- everything passes', async () => {
    // `wanted` is built by flat-mapping every requested token through the
    // mapping table; an unmapped token contributes nothing, same as an empty
    // `statuses` array would. `getOrders` cannot tell "asked for nothing in
    // particular" apart from "asked for a tab that doesn't exist" -- both
    // leave `wanted.size === 0`, and that check is what disables filtering.
    const { service, api } = setUp();
    api.list.mockResolvedValue({ items: [summary('o1', 'RECEIVED')], nextCursor: null });

    const orders = await firstValueFrom(service.getOrders(['not-a-real-tab']));

    expect(orders).toHaveLength(1);
  });
});

describe('OrdersService: never fabricates an item count or distance', () => {
  // Regression for the proven "0 ta | km" defect: OrderSummaryResponse carries
  // no line items and no distance (see its Javadoc), so a list row must not
  // invent either one.
  it('leaves items_count and delivery_distance unset on every list row, whatever its status', async () => {
    const { service, api } = setUp();
    api.list.mockResolvedValue({ items: [summary('o1', 'CONFIRMED')], nextCursor: null });

    const [order] = await firstValueFrom(service.getOrders([]));

    expect(order.items_count).toBeUndefined();
    expect(order.delivery_distance).toBeUndefined();
  });

  it('reports the real line count on an order detail read, which does carry lines', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue({
      ...orderResponse('o1', 1),
      lines: [
        {
          lineNumber: 1,
          productName: 'Osh',
          variantName: '',
          quantity: 2,
          unitAmountMinor: 1000,
          finalAmountMinor: 2000,
          modifiers: [],
        },
      ],
    });

    const detail = await firstValueFrom(service.getOrderDetail('o1'));

    expect(detail.items_count).toBe(1);
  });
});

describe('OrdersService: cancel action reflects the real state-machine guard', () => {
  // Mirrors ordering.application.OrderActionsPolicy.canCancelWithoutReason on
  // the platform: cancellable up to and including AWAITING_APPROVAL, refused
  // from CONFIRMED on. Getting this wrong either hides a working cancel
  // button or offers one the platform will refuse with a conflict.
  const CANCELLABLE = ['RECEIVED', 'PAYMENT_AUTHORIZING', 'AWAITING_APPROVAL'];
  const NOT_CANCELLABLE = [
    'CONFIRMED',
    'PREPARING',
    'READY',
    'FULFILLING',
    'COMPLETED',
    'CANCELLED',
    'REJECTED',
    'EXPIRED',
    'PAYMENT_FAILED',
  ];

  it.each(CANCELLABLE)('offers cancel on a list row at status %s', async (status) => {
    const { service, api } = setUp();
    api.list.mockResolvedValue({ items: [summary('o1', status)], nextCursor: null });

    const [order] = await firstValueFrom(service.getOrders([]));

    expect(order.actions).toContain('cancel');
  });

  it.each(NOT_CANCELLABLE)('offers no cancel on a list row at status %s', async (status) => {
    const { service, api } = setUp();
    api.list.mockResolvedValue({ items: [summary('o1', status)], nextCursor: null });

    const [order] = await firstValueFrom(service.getOrders([]));

    expect(order.actions).not.toContain('cancel');
  });

  it.each(CANCELLABLE)('offers cancel on an order detail read at status %s', async (status) => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(orderResponse('o1', 1, status));

    const detail = await firstValueFrom(service.getOrderDetail('o1'));

    expect(detail.actions).toContain('cancel');
  });

  it.each(NOT_CANCELLABLE)('offers no cancel on an order detail read at status %s', async (status) => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(orderResponse('o1', 1, status));

    const detail = await firstValueFrom(service.getOrderDetail('o1'));

    expect(detail.actions).not.toContain('cancel');
  });
});

describe('OrdersService.cancelOrder (single retry)', () => {
  function staleVersion(currentVersion: number): HorecaOSApiError {
    return new HorecaOSApiError({
      status: 409,
      code: 'STALE_VERSION',
      detail: 'moved',
      problem: { status: 409, code: 'STALE_VERSION', currentVersion },
    });
  }

  it('reads the version first when nothing has been read yet, then cancels', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(orderResponse('o1', 4));
    api.mutate.mockResolvedValue({ orderId: 'o1', status: 'CANCELLED', version: 5, applied: true });

    await firstValueFrom(service.cancelOrder('o1'));

    expect(api.get).toHaveBeenCalledTimes(1);
    expect(api.mutate).toHaveBeenCalledTimes(1);
    expect(api.mutate.mock.calls[0][2]?.expectedVersion).toBe(4);
  });

  it('retries exactly once on STALE_VERSION, with the version the platform reported', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(orderResponse('o1', 4));
    api.mutate
      .mockRejectedValueOnce(staleVersion(6))
      .mockResolvedValueOnce({ orderId: 'o1', status: 'CANCELLED', version: 7, applied: true });

    const result = await firstValueFrom(service.cancelOrder('o1'));

    expect(result).toEqual({ orderId: 'o1', status: 'CANCELLED', version: 7, applied: true });
    expect(api.mutate).toHaveBeenCalledTimes(2);
    expect(api.mutate.mock.calls[1][2]?.expectedVersion).toBe(6);
  });

  it('reuses the same idempotency key on the retry -- one intent, one effect', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(orderResponse('o1', 4));
    api.mutate
      .mockRejectedValueOnce(staleVersion(6))
      .mockResolvedValueOnce({ orderId: 'o1', status: 'CANCELLED', version: 7, applied: true });

    await firstValueFrom(service.cancelOrder('o1'));

    const firstKey = api.mutate.mock.calls[0][2]?.idempotencyKey;
    const secondKey = api.mutate.mock.calls[1][2]?.idempotencyKey;
    expect(firstKey).toBeTruthy();
    expect(secondKey).toBe(firstKey);
  });

  it('never loops: a second STALE_VERSION on the retry is thrown, not retried again', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(orderResponse('o1', 4));
    const secondFailure = staleVersion(9);
    api.mutate.mockRejectedValueOnce(staleVersion(6)).mockRejectedValueOnce(secondFailure);

    await expect(firstValueFrom(service.cancelOrder('o1'))).rejects.toBe(secondFailure);
    expect(api.mutate).toHaveBeenCalledTimes(2);
  });

  it('a non-stale-version failure is thrown immediately, with no retry', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(orderResponse('o1', 4));
    const other = new HorecaOSApiError({ status: 409, code: 'RESOURCE_CONFLICT', detail: 'x' });
    api.mutate.mockRejectedValue(other);

    await expect(firstValueFrom(service.cancelOrder('o1'))).rejects.toBe(other);
    expect(api.mutate).toHaveBeenCalledTimes(1);
  });

  it('skips the extra read when a version for this order is already known (from a prior list/detail read)', async () => {
    const { service, api } = setUp();
    api.list.mockResolvedValue({ items: [summary('o1', 'RECEIVED', 3)], nextCursor: null });
    await firstValueFrom(service.getOrders([]));
    api.mutate.mockResolvedValue({ orderId: 'o1', status: 'CANCELLED', version: 4, applied: true });

    await firstValueFrom(service.cancelOrder('o1'));

    expect(api.get).not.toHaveBeenCalled();
    expect(api.mutate.mock.calls[0][2]?.expectedVersion).toBe(3);
  });
});

describe('OrdersService.poll', () => {
  const realHiddenDescriptor = Object.getOwnPropertyDescriptor(Document.prototype, 'hidden');

  function setHidden(hidden: boolean): void {
    Object.defineProperty(document, 'hidden', { configurable: true, value: hidden });
  }

  afterEach(() => {
    vi.useRealTimers();
    if (realHiddenDescriptor) {
      Object.defineProperty(document, 'hidden', realHiddenDescriptor);
    }
  });

  it('calls the source on every tick while the tab is visible', async () => {
    vi.useFakeTimers();
    setHidden(false);
    const { service } = setUp();
    const source = vi.fn(() => of('tick'));

    const emissions: string[] = [];
    const sub = service.poll(1000, source).subscribe((v) => emissions.push(v));

    await vi.advanceTimersByTimeAsync(3000);
    sub.unsubscribe();

    expect(source).toHaveBeenCalledTimes(3);
    expect(emissions).toEqual(['tick', 'tick', 'tick']);
  });

  it('does not call the source at all while document.hidden is true', async () => {
    vi.useFakeTimers();
    setHidden(true);
    const { service } = setUp();
    const source = vi.fn(() => of('tick'));

    const emissions: string[] = [];
    const sub = service.poll(1000, source).subscribe((v) => emissions.push(v));

    await vi.advanceTimersByTimeAsync(5000);
    sub.unsubscribe();

    expect(source).not.toHaveBeenCalled();
    expect(emissions).toEqual([]);
  });

  it('resumes ticking once the tab becomes visible again', async () => {
    vi.useFakeTimers();
    setHidden(true);
    const { service } = setUp();
    const source = vi.fn(() => of('tick'));

    const emissions: string[] = [];
    const sub = service.poll(1000, source).subscribe((v) => emissions.push(v));

    await vi.advanceTimersByTimeAsync(2000);
    expect(source).not.toHaveBeenCalled();

    setHidden(false);
    await vi.advanceTimersByTimeAsync(1000);
    sub.unsubscribe();

    expect(source).toHaveBeenCalledTimes(1);
    expect(emissions).toEqual(['tick']);
  });

  it('swallows a failing tick and keeps polling on the next one, rather than killing the subscription', async () => {
    vi.useFakeTimers();
    setHidden(false);
    const { service } = setUp();
    const source = vi.fn<() => import('rxjs').Observable<string>>(() => of('tick 2 ok'));
    source
      .mockReturnValueOnce(throwError(() => new Error('tick 1 failed')))
      .mockReturnValueOnce(of('tick 2 ok'));

    const emissions: string[] = [];
    const errors: unknown[] = [];
    const sub = service
      .poll(1000, source)
      .subscribe({ next: (v: string) => emissions.push(v), error: (e) => errors.push(e) });

    await vi.advanceTimersByTimeAsync(2000);
    sub.unsubscribe();

    expect(source).toHaveBeenCalledTimes(2);
    expect(errors).toEqual([]);
    expect(emissions).toEqual(['tick 2 ok']);
  });
});

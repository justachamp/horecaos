import { Location } from '@angular/common';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiClient } from '../../core/api/api-client';
import { CurrentLocation } from '../../core/auth/current-location';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { OrderCounts, zeroTabCounts } from './order-counts';
import { OrderQueue } from './order-queue';
import { OrderSummaryResponse } from './order-summary';

const FAKE_SCOPE = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

/**
 * Zoneless change detection schedules its tick partly via
 * `requestAnimationFrame`, which vitest's fake timers model as a ~16ms timer
 * rather than firing instantly. Advancing by exactly 0ms flushes promises but
 * not that frame boundary, so DOM assertions after fake-timer-driven async
 * work use this instead of 0.
 */
const FRAME_MS = 20;

/**
 * These tests stub `ApiClient` and `CurrentLocation` directly rather than
 * going through real HTTP — the queue's contract with the wire (paths,
 * headers, cursor rules) is `api-client.spec.ts`'s job; this file is about
 * what the queue does with a response once it has one.
 *
 * `OrderCounts` is stubbed too, deliberately, even though it is a real,
 * `providedIn: 'root'` class rather than a boundary this file owns:
 * `OrderCounts.forOrders` now makes its own `ApiClient.get` call (the
 * `GET .../orders/counts` seam, `order-counts.spec.ts`), and `getOrders`
 * above is a single mock that answers *any* path with the orders array. If
 * `OrderCounts` were left real it would consume `getOrders`'s call queue
 * (`mockReturnValueOnce`, etc.) for a second, unrelated request every
 * refresh, which is exactly the kind of cross-talk this file's own docstring
 * says it exists to avoid. `OrderCounts`'s own endpoint-consuming behaviour
 * is `order-counts.spec.ts`'s job.
 */
function configure(getOrders: ReturnType<typeof vi.fn>): void {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([{ path: 'orders', component: OrderQueue }]),
      {
        provide: CurrentLocation,
        useValue: {
          scope: signal(FAKE_SCOPE),
          denied: signal(false),
          ensureLoaded: () => Promise.resolve(),
        },
      },
      { provide: ApiClient, useValue: { get: getOrders } },
      { provide: OrderCounts, useValue: { forOrders: () => Promise.resolve(zeroTabCounts()) } },
    ],
  });
  TestBed.inject(I18n).setLocale('en');
}

function ordersResponse(orders: readonly OrderSummaryResponse[]): ReturnType<typeof vi.fn> {
  return vi.fn().mockReturnValue(of({ value: orders, version: null }));
}

function order(overrides: Partial<OrderSummaryResponse>): OrderSummaryResponse {
  return {
    orderId: 'order-1',
    publicOrderNumber: '0001',
    status: 'RECEIVED',
    createdAt: new Date().toISOString(),
    totalMinor: 100_000,
    currency: 'UZS',
    ...overrides,
  };
}

function minutesAgoIso(minutes: number): string {
  return new Date(Date.now() - minutes * 60 * 1000).toISOString();
}

/**
 * Settles the queue's start→ensureLoaded→refresh→fetch promise chain and lets
 * Angular's zoneless change detection run a tick, under real timers. A plain
 * microtask loop is not reliable here: zoneless CD scheduling and rxjs'
 * `firstValueFrom` both interleave macrotasks into the chain, so this waits on
 * a real `setTimeout` rather than counting microtask hops.
 */
async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

function setVisibility(state: 'visible' | 'hidden'): void {
  Object.defineProperty(document, 'visibilityState', { value: state, configurable: true });
  document.dispatchEvent(new Event('visibilitychange'));
}

function resetVisibility(): void {
  Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
}

function rowNumbers(host: HTMLElement): string[] {
  return [...host.querySelectorAll('[data-testid="order-row"]')].map(
    (row) => row.querySelector('.q-mono')?.textContent?.trim() ?? '',
  );
}

afterEach(() => {
  resetVisibility();
});

describe('OrderQueue: status vocabulary', () => {
  it('renders a known status through its i18n label', async () => {
    configure(ordersResponse([order({ status: 'PREPARING' })]));
    const harness = await RouterTestingHarness.create('/orders?tab=preparing');
    await flushMicrotasks();

    const badge = harness.routeNativeElement!.querySelector('.status-badge');
    expect(badge?.textContent?.trim()).toBe('Preparing');
  });

  it('renders every one of the twelve canonical statuses without throwing', async () => {
    const statuses = [
      'RECEIVED',
      'PAYMENT_AUTHORIZING',
      'AWAITING_APPROVAL',
      'PAYMENT_FAILED',
      'CONFIRMED',
      'REJECTED',
      'EXPIRED',
      'PREPARING',
      'READY',
      'FULFILLING',
      'COMPLETED',
      'CANCELLED',
    ];
    configure(ordersResponse(statuses.map((status, i) => order({ orderId: `o${i}`, status }))));
    const harness = await RouterTestingHarness.create('/orders?tab=all');
    await flushMicrotasks();

    const badges = [...harness.routeNativeElement!.querySelectorAll('.status-badge')].map((b) =>
      b.textContent?.trim(),
    );
    expect(badges).toHaveLength(12);
    expect(badges).not.toContain('');
  });

  it('renders an unrecognised status harmlessly, as its own raw value, rather than a blank row', async () => {
    // The assertion this guards: rendering must not throw for a status this
    // client does not know — `await` alone fails the test if it does.
    configure(ordersResponse([order({ status: 'ON_HOLD_FOR_STOCK' })]));
    const harness = await RouterTestingHarness.create('/orders?tab=all');
    await flushMicrotasks();

    const badge = harness.routeNativeElement!.querySelector('.status-badge');
    expect(badge?.textContent?.trim()).toBe('ON_HOLD_FOR_STOCK');
  });
});

describe('OrderQueue: severity ordering', () => {
  it('sorts a severity-ordered tab by severity rank, then created_at ascending — not newest first', async () => {
    const normalOlder = order({
      orderId: 'a',
      publicOrderNumber: '0001',
      status: 'RECEIVED',
      createdAt: minutesAgoIso(5),
    });
    const normalNewer = order({
      orderId: 'b',
      publicOrderNumber: '0002',
      status: 'RECEIVED',
      createdAt: minutesAgoIso(2),
    });
    const stalled = order({
      orderId: 'c',
      publicOrderNumber: '0003',
      status: 'RECEIVED',
      createdAt: minutesAgoIso(50),
    });

    configure(ordersResponse([normalOlder, normalNewer, stalled]));
    const harness = await RouterTestingHarness.create('/orders?tab=new');
    await flushMicrotasks();

    // 0003 has been open 50 minutes with no promise data — flagged, so it leads
    // regardless of its position in the fetched array. Between the two normal
    // rows, the older one (waited longest) comes first.
    expect(rowNumbers(harness.routeNativeElement!)).toEqual(['0003', '0001', '0002']);
  });

  it('keeps a log tab (completed) newest-first instead of severity-ordered', async () => {
    const older = order({
      orderId: 'a',
      publicOrderNumber: '0001',
      status: 'COMPLETED',
      createdAt: minutesAgoIso(10),
    });
    const newer = order({
      orderId: 'b',
      publicOrderNumber: '0002',
      status: 'COMPLETED',
      createdAt: minutesAgoIso(1),
    });

    configure(ordersResponse([older, newer]));
    const harness = await RouterTestingHarness.create('/orders?tab=completed');
    await flushMicrotasks();

    expect(rowNumbers(harness.routeNativeElement!)).toEqual(['0002', '0001']);
  });
});

describe('OrderQueue: tab routing', () => {
  it('opens on Внимание (attention) when no tab is given', async () => {
    configure(ordersResponse([]));
    const harness = await RouterTestingHarness.create('/orders');
    await flushMicrotasks();

    const active = harness.routeNativeElement!.querySelector('[role="tab"].tab--active');
    expect(active?.textContent?.trim()).toBe('Attention');
  });

  it('falls back to attention for an unrecognised ?tab= value rather than showing a blank board', async () => {
    configure(ordersResponse([]));
    const harness = await RouterTestingHarness.create('/orders?tab=urgent');
    await flushMicrotasks();

    const active = harness.routeNativeElement!.querySelector('[role="tab"].tab--active');
    expect(active?.textContent?.trim()).toBe('Attention');
  });

  it('shows only the active tab’s members, driven by the query param', async () => {
    const completed = order({ orderId: 'a', publicOrderNumber: '0001', status: 'COMPLETED' });
    const received = order({ orderId: 'b', publicOrderNumber: '0002', status: 'RECEIVED' });
    configure(ordersResponse([completed, received]));

    const harness = await RouterTestingHarness.create('/orders?tab=completed');
    await flushMicrotasks();
    expect(rowNumbers(harness.routeNativeElement!)).toEqual(['0001']);
  });

  it('updates the URL when a tab is clicked, so the tab is shareable', async () => {
    configure(ordersResponse([]));
    const harness = await RouterTestingHarness.create('/orders');
    await flushMicrotasks();

    const buttons = [...harness.routeNativeElement!.querySelectorAll('[role="tab"]')];
    const newTab = buttons.find((b) => b.textContent?.trim() === 'New');
    (newTab as HTMLElement).click();

    await flushMicrotasks();

    expect(TestBed.inject(Location).path()).toBe('/orders?tab=new');
  });
});

describe('OrderQueue: polling liveness', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('fetches on load and shows the "updated" stamp', async () => {
    setVisibility('visible');
    const getOrders = ordersResponse([]);
    configure(getOrders);

    const harness = await RouterTestingHarness.create('/orders');
    await vi.advanceTimersByTimeAsync(FRAME_MS);

    expect(getOrders).toHaveBeenCalledTimes(1);
    const stamp = harness.routeNativeElement!.querySelector('.order-queue__stamp');
    expect(stamp?.textContent).toContain('updated');
  });

  it('polls again after 10 seconds while the tab stays visible', async () => {
    setVisibility('visible');
    const getOrders = ordersResponse([]);
    configure(getOrders);

    await RouterTestingHarness.create('/orders');
    await vi.advanceTimersByTimeAsync(FRAME_MS);
    expect(getOrders).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(10_000);
    expect(getOrders).toHaveBeenCalledTimes(2);
  });

  it('pauses polling while the tab is hidden, and resumes when it becomes visible again', async () => {
    setVisibility('visible');
    const getOrders = ordersResponse([]);
    configure(getOrders);

    await RouterTestingHarness.create('/orders');
    await vi.advanceTimersByTimeAsync(FRAME_MS);
    expect(getOrders).toHaveBeenCalledTimes(1);

    setVisibility('hidden');
    await vi.advanceTimersByTimeAsync(10_000);
    // A quiet shift and a stalled poller must not look the same — this is the
    // assertion that tells them apart: no fetch happened while hidden.
    expect(getOrders).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(10_000);
    expect(getOrders).toHaveBeenCalledTimes(1);

    setVisibility('visible');
    await vi.advanceTimersByTimeAsync(FRAME_MS);
    expect(getOrders).toHaveBeenCalledTimes(2);
  });
});

describe('OrderQueue: empty, error and denied states (§2.11)', () => {
  it('shows the default empty message for an empty non-attention tab, keeping the table frame', async () => {
    configure(ordersResponse([]));
    const harness = await RouterTestingHarness.create('/orders?tab=completed');
    await flushMicrotasks();

    const host = harness.routeNativeElement!;
    expect(host.querySelector('[data-testid="order-queue-empty"]')?.textContent?.trim()).toBe(
      'No orders yet',
    );
    // The frame — tabs and header row — never disappears for an empty result.
    expect(host.querySelector('[role="tablist"]')).not.toBeNull();
  });

  it('shows the positive "all clear" message for an empty attention tab, not a null result', async () => {
    configure(ordersResponse([]));
    const harness = await RouterTestingHarness.create('/orders?tab=attention');
    await flushMicrotasks();

    expect(
      harness
        .routeNativeElement!.querySelector('[data-testid="order-queue-empty"]')
        ?.textContent?.trim(),
    ).toBe('All clear');
  });

  it('shows the denied state on a 403, naming the gap, with rows withheld — never a raw 403', async () => {
    const denied = new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null);
    configure(vi.fn().mockReturnValue(throwError(() => denied)));

    const harness = await RouterTestingHarness.create('/orders');
    await flushMicrotasks();

    const host = harness.routeNativeElement!;
    expect(host.querySelector('[data-testid="order-queue-denied"]')?.textContent?.trim()).toBe(
      "No access to this branch's orders",
    );
    expect(host.querySelector('table')).toBeNull();
  });

  it('keeps previously loaded rows visible behind an inline error band on a later failed refresh', async () => {
    vi.useFakeTimers();
    try {
      setVisibility('visible');
      const firstOrder = order({ orderId: 'a', publicOrderNumber: '0007', status: 'RECEIVED' });
      const getOrders = vi
        .fn()
        .mockReturnValueOnce(of({ value: [firstOrder], version: null }))
        .mockReturnValueOnce(
          throwError(() => new ApiError(ApiErrorCode.INTERNAL_ERROR, 500, null, '01J8CORR')),
        );
      configure(getOrders);

      const harness = await RouterTestingHarness.create('/orders?tab=new');
      await vi.advanceTimersByTimeAsync(FRAME_MS);
      expect(rowNumbers(harness.routeNativeElement!)).toEqual(['0007']);

      await vi.advanceTimersByTimeAsync(10_000);
      await vi.advanceTimersByTimeAsync(FRAME_MS);

      const host = harness.routeNativeElement!;
      expect(host.querySelector('[role="alert"]')).not.toBeNull();
      expect(host.querySelector('[role="alert"]')?.textContent).toContain('01J8CORR');
      // The row from the successful fetch is still there.
      expect(rowNumbers(host)).toEqual(['0007']);
    } finally {
      vi.useRealTimers();
    }
  });
});

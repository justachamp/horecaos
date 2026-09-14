import { Location } from '@angular/common';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiClient } from '../../core/api/api-client';
import { CurrentLocation } from '../../core/auth/current-location';
import { CurrentTenant } from '../../core/auth/current-tenant';
import { SessionCapabilities } from '../../core/auth/session-capabilities';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { PLATFORM_DEFAULT_LATENESS_POLICY } from '../../core/lateness-policy';
import { LatenessPolicyApi } from '../../core/lateness-policy-api';
import { ReasonResponse, ReferenceDataApi } from '../settings/reference-data/reference-data-api';
import { OrderActionsApi } from './order-actions-api';
import { OrderBulkActionsApi } from './order-bulk-actions-api';
import { OrderCounts, zeroTabCounts } from './order-counts';
import { OrderQueue } from './order-queue';
import { RejectReasonOption } from './order-reject-reason-dialog';
import { RejectReasonsApi } from './order-reject-reasons-api';
import { OrderSummaryResponse } from './order-summary';
import { Toasts } from '../../shared/ui/toast';

const FAKE_CANCEL_REASONS: readonly ReasonResponse[] = [
  {
    id: 'reason-1',
    kind: 'CANCELLATION',
    systemCategory: 'CUSTOMER_CHANGED_MIND',
    internalName: 'Customer changed their mind',
    stockDisposition: 'RELEASE',
    liabilityParty: 'TENANT',
    customerRefund: 'FULL',
    allowedFulfillmentModes: null,
    customerTexts: {},
    status: 'ACTIVE',
    version: 1,
    updatedAt: new Date().toISOString(),
  },
];

/**
 * §2.10's bulk bar: `SessionCapabilities` grants `ORDER_BULK_ACTION`,
 * `OrderBulkActionsApi`/`ReferenceDataApi` are faked directly (not through
 * the shared `getOrders` mock) for the same reason `OrderActionsApi` is in
 * {@link configureWithActions} above — one shared `ApiClient.get` stub
 * cannot answer two unrelated endpoints correctly.
 */
function configureForBulk(
  getOrders: ReturnType<typeof vi.fn>,
  overrides: {
    readonly bulkSubmit?: ReturnType<typeof vi.fn>;
    readonly cancelReasons?: readonly ReasonResponse[];
  } = {},
): void {
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
      { provide: RejectReasonsApi, useValue: stubRejectReasons() },
      {
        provide: LatenessPolicyApi,
        useValue: { resolve: () => Promise.resolve(PLATFORM_DEFAULT_LATENESS_POLICY) },
      },
      {
        provide: SessionCapabilities,
        useValue: { has: (capability: string) => capability === 'ORDER_BULK_ACTION' },
      },
      { provide: OrderBulkActionsApi, useValue: { submit: overrides.bulkSubmit ?? vi.fn() } },
      {
        provide: ReferenceDataApi,
        useValue: { list: () => Promise.resolve(overrides.cancelReasons ?? FAKE_CANCEL_REASONS) },
      },
    ],
  });
  TestBed.inject(I18n).setLocale('en');
}

const FAKE_SCOPE = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

/**
 * The curated list `RejectReasonsApi.list` would fetch from `GET
 * .../orders/reject-reasons` (wave 24) — a small fixture, not the platform's
 * real eight, since these tests are about the queue's own wiring to the
 * picker, not the registry's content (`OrderAmendmentAndOutcomeTests`'s job).
 */
const FAKE_REJECT_REASONS: readonly RejectReasonOption[] = [
  {
    code: 'ITEM_UNAVAILABLE',
    displayOrder: 1,
    requiresNote: false,
    labels: { ru: 'Нет в наличии', 'uz-Latn': 'Mavjud emas', en: 'Item unavailable' },
  },
  {
    code: 'OTHER',
    displayOrder: 8,
    requiresNote: true,
    labels: { ru: 'Другое', 'uz-Latn': 'Boshqa', en: 'Other' },
  },
];

function stubRejectReasons(
  reasons: readonly RejectReasonOption[] = FAKE_REJECT_REASONS,
): Partial<RejectReasonsApi> {
  return { list: () => Promise.resolve(reasons) };
}

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
 * is `order-counts.spec.ts`'s job. `LatenessPolicyApi` (wave P06) is stubbed
 * for the identical reason: `start()` resolves it once via its own
 * `ApiClient.get` call, which would otherwise be the extra, unrelated
 * request every `getOrders`-call-count assertion in this file did not
 * budget for.
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
      { provide: RejectReasonsApi, useValue: stubRejectReasons() },
      {
        provide: LatenessPolicyApi,
        useValue: { resolve: () => Promise.resolve(PLATFORM_DEFAULT_LATENESS_POLICY) },
      },
    ],
  });
  TestBed.inject(I18n).setLocale('en');
}

/**
 * `GET .../orders/board` (wave P04/P07) answers a `Page<OrderSummaryResponse>`
 * body, not a bare array — `ApiClient.get` still wraps it the same way it
 * always has (`{value, version}`), only `value` now carries `{items,
 * nextCursor}`. This is the one seam every existing test in this file goes
 * through, so fixing the shape here is what keeps `configure`/
 * `configureWithActions`'s many callers working unchanged.
 */
function ordersResponse(orders: readonly OrderSummaryResponse[]): ReturnType<typeof vi.fn> {
  return vi.fn().mockReturnValue(of({ value: { items: orders, nextCursor: null }, version: null }));
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

/** §2.8's 300ms search debounce, under real timers — waits it out and then settles the refresh it fires. */
async function flushSearchDebounce(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 320));
  await flushMicrotasks();
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
  // `OrderQueueFilterState` persists to the real jsdom `localStorage`, which
  // is not reset between tests on its own — a filter left over from an
  // earlier test in this file must not leak into a later one that never set
  // it itself.
  localStorage.clear();
});

describe('OrderQueue: status vocabulary', () => {
  it('renders a known status through its i18n label', async () => {
    configure(ordersResponse([order({ status: 'PREPARING' })]));
    const harness = await RouterTestingHarness.create('/orders?tab=preparing');
    await flushMicrotasks();

    const badge = harness.routeNativeElement!.querySelector('[data-testid="q-status-pill-status"]');
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

    const badges = [
      ...harness.routeNativeElement!.querySelectorAll('[data-testid="q-status-pill-status"]'),
    ].map((b) => b.textContent?.trim());
    expect(badges).toHaveLength(12);
    expect(badges).not.toContain('');
  });

  it('renders an unrecognised status harmlessly, as its own raw value, rather than a blank row', async () => {
    // The assertion this guards: rendering must not throw for a status this
    // client does not know — `await` alone fails the test if it does.
    configure(ordersResponse([order({ status: 'ON_HOLD_FOR_STOCK' })]));
    const harness = await RouterTestingHarness.create('/orders?tab=all');
    await flushMicrotasks();

    const badge = harness.routeNativeElement!.querySelector('[data-testid="q-status-pill-status"]');
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
        .mockReturnValueOnce(
          of({ value: { items: [firstOrder], nextCursor: null }, version: null }),
        )
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

/**
 * §2.9 row actions and §4.3's mutation contract. `OrderActionsApi` is
 * overridden per test (not part of `configure()` above) because most of
 * this file never touches it.
 */
function configureWithActions(
  orders: readonly OrderSummaryResponse[],
  actionsApi: Partial<OrderActionsApi>,
  rejectReasonsApi: Partial<RejectReasonsApi> = stubRejectReasons(),
): void {
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
      { provide: ApiClient, useValue: { get: ordersResponse(orders) } },
      { provide: OrderCounts, useValue: { forOrders: () => Promise.resolve(zeroTabCounts()) } },
      { provide: OrderActionsApi, useValue: actionsApi },
      { provide: RejectReasonsApi, useValue: rejectReasonsApi },
      {
        provide: LatenessPolicyApi,
        useValue: { resolve: () => Promise.resolve(PLATFORM_DEFAULT_LATENESS_POLICY) },
      },
    ],
  });
  TestBed.inject(I18n).setLocale('en');
}

describe('OrderQueue: row actions render exactly from actions[] (§2.9, §4.2)', () => {
  it('renders no inline or mutating overflow control for a status whose actions[] is empty', async () => {
    configureWithActions([order({ status: 'COMPLETED', actions: [] })], {});
    const harness = await RouterTestingHarness.create('/orders?tab=completed');
    await flushMicrotasks();

    const cell = harness.routeNativeElement!.querySelector('[data-testid="order-row-actions"]');
    expect(cell?.querySelectorAll('.row-actions__inline')).toHaveLength(0);
    // No server-driven action renders — only the two static read-only items
    // below are in the menu, and neither is a code from actions[].
    for (const code of ['APPROVE', 'REJECT', 'ADVANCE', 'CANCEL', 'AMEND']) {
      expect(cell?.querySelector(`[data-testid="order-row-action-${code}"]`)).toBeNull();
    }
  });

  it('1.1e: a terminal order still offers a read-only overflow menu, never none', async () => {
    configureWithActions([order({ status: 'COMPLETED', actions: [] })], {});
    const harness = await RouterTestingHarness.create('/orders?tab=completed');
    await flushMicrotasks();

    const host = harness.routeNativeElement!;
    const trigger = host.querySelector(
      '[data-testid="order-row-overflow-trigger"]',
    ) as HTMLButtonElement | null;
    // §2.9: "terminal | none [inline] | overflow (read-only items only)" — the
    // trigger itself must not disappear just because actions[] came back empty.
    expect(trigger).not.toBeNull();

    trigger!.click();
    await flushMicrotasks();

    expect(host.querySelector('[data-testid="order-row-overflow-menu"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="order-row-action-OPEN"]')?.textContent?.trim()).toBe(
      'Open',
    );
    expect(
      host.querySelector('[data-testid="order-row-action-COPY_NUMBER"]')?.textContent?.trim(),
    ).toBe('Copy order number');
  });

  it('renders the first two actions inline and the rest in the overflow menu', async () => {
    configureWithActions(
      [
        order({
          status: 'AWAITING_APPROVAL',
          actions: [{ action: 'APPROVE' }, { action: 'REJECT' }, { action: 'CANCEL' }],
        }),
      ],
      {},
    );
    const harness = await RouterTestingHarness.create('/orders?tab=attention');
    await flushMicrotasks();

    const host = harness.routeNativeElement!;
    expect(
      host.querySelector('[data-testid="order-row-action-APPROVE"]')?.textContent?.trim(),
    ).toBe('Accept');
    expect(host.querySelector('[data-testid="order-row-action-REJECT"]')?.textContent?.trim()).toBe(
      'Reject',
    );
    // CANCEL is the third action — overflow only, not inline.
    expect(host.querySelectorAll('.row-actions__inline')).toHaveLength(2);
    expect(host.querySelector('[data-testid="order-row-overflow-trigger"]')).not.toBeNull();
  });

  it('clicking an action does not also open the row (stopPropagation)', async () => {
    const approve = vi.fn().mockReturnValue(
      of({
        orderId: 'order-1',
        status: 'CONFIRMED',
        version: 2,
        applied: true,
        effectiveDecisionId: null,
        effectiveAction: null,
      }),
    );
    configureWithActions(
      [order({ status: 'AWAITING_APPROVAL', actions: [{ action: 'APPROVE' }] })],
      { approve },
    );
    const harness = await RouterTestingHarness.create('/orders?tab=attention');
    await flushMicrotasks();

    (
      harness.routeNativeElement!.querySelector(
        '[data-testid="order-row-action-APPROVE"]',
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(approve).toHaveBeenCalledTimes(1);
    // Still on the board — the row-open navigation never fired.
    expect(TestBed.inject(Location).path()).toBe('/orders?tab=attention');
  });

  it('renders the settling decision on a lost approval race, not a generic failure', async () => {
    const approve = vi.fn().mockReturnValue(
      of({
        orderId: 'order-1',
        status: 'REJECTED',
        version: 2,
        applied: false,
        effectiveDecisionId: 'someone-elses',
        effectiveAction: 'REJECT',
      }),
    );
    configureWithActions(
      [order({ status: 'AWAITING_APPROVAL', actions: [{ action: 'APPROVE' }] })],
      { approve },
    );
    const harness = await RouterTestingHarness.create('/orders?tab=attention');
    await flushMicrotasks();

    (
      harness.routeNativeElement!.querySelector(
        '[data-testid="order-row-action-APPROVE"]',
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    const notice = harness.routeNativeElement!.querySelector('[data-testid="order-queue-notice"]');
    expect(notice?.textContent).toContain('Already rejected');
    expect(notice?.textContent).toContain('another operator');
  });

  it('on STALE_VERSION, re-reads the board and says the order changed — never retries the mutation', async () => {
    const advance = vi
      .fn()
      .mockReturnValue(
        throwError(
          () =>
            new ApiError(
              ApiErrorCode.STALE_VERSION,
              409,
              { status: 409, expected: 2, actual: 3 },
              null,
            ),
        ),
      );
    configureWithActions(
      [
        order({
          orderId: 'order-1',
          status: 'CONFIRMED',
          version: 2,
          actions: [{ action: 'ADVANCE', targetStatus: 'PREPARING' }],
        }),
      ],
      { advance },
    );
    const harness = await RouterTestingHarness.create('/orders?tab=preparing');
    await flushMicrotasks();

    (
      harness.routeNativeElement!.querySelector(
        '[data-testid="order-row-action-ADVANCE"]',
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(advance).toHaveBeenCalledTimes(1);
    const notice = harness.routeNativeElement!.querySelector('[data-testid="order-queue-notice"]');
    expect(notice?.textContent).toContain('changed');
  });

  it('opens the curated reason picker for Reject and submits with the chosen code (wave 24)', async () => {
    const reject = vi.fn().mockReturnValue(
      of({
        orderId: 'order-1',
        status: 'REJECTED',
        version: 2,
        applied: true,
        effectiveDecisionId: null,
        effectiveAction: null,
      }),
    );
    configureWithActions(
      [
        order({
          orderId: 'order-1',
          status: 'AWAITING_APPROVAL',
          actions: [{ action: 'APPROVE' }, { action: 'REJECT' }],
        }),
      ],
      { reject },
    );
    const harness = await RouterTestingHarness.create('/orders?tab=attention');
    await flushMicrotasks();

    const host = harness.routeNativeElement!;
    (host.querySelector('[data-testid="order-row-action-REJECT"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    // The free-text dialog is gone for Reject; a picker built from
    // GET .../orders/reject-reasons opens instead.
    expect(host.querySelector('[data-testid="order-reason-dialog"]')).toBeNull();
    expect(host.querySelector('[data-testid="order-reject-reason-dialog"]')).not.toBeNull();
    expect(
      host.querySelector('[data-testid="order-reject-reason-option-ITEM_UNAVAILABLE"]'),
    ).not.toBeNull();

    (
      host.querySelector(
        '[data-testid="order-reject-reason-option-ITEM_UNAVAILABLE"]',
      ) as HTMLInputElement
    ).dispatchEvent(new Event('change'));
    (
      host.querySelector('[data-testid="order-reject-reason-confirm"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(reject).toHaveBeenCalledWith(
      FAKE_SCOPE,
      'order-1',
      expect.any(String),
      'ITEM_UNAVAILABLE',
      undefined,
    );
  });

  it('OTHER refuses to submit without a note, then submits code and note once one is entered', async () => {
    const reject = vi.fn().mockReturnValue(
      of({
        orderId: 'order-1',
        status: 'REJECTED',
        version: 2,
        applied: true,
        effectiveDecisionId: null,
        effectiveAction: null,
      }),
    );
    configureWithActions(
      [
        order({
          orderId: 'order-1',
          status: 'AWAITING_APPROVAL',
          actions: [{ action: 'REJECT' }],
        }),
      ],
      { reject },
    );
    const harness = await RouterTestingHarness.create('/orders?tab=attention');
    await flushMicrotasks();

    const host = harness.routeNativeElement!;
    (host.querySelector('[data-testid="order-row-action-REJECT"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    (
      host.querySelector('[data-testid="order-reject-reason-option-OTHER"]') as HTMLInputElement
    ).dispatchEvent(new Event('change'));
    (
      host.querySelector('[data-testid="order-reject-reason-confirm"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(reject).not.toHaveBeenCalled();
    expect(host.querySelector('[data-testid="order-reject-reason-note-required"]')).not.toBeNull();

    const noteField = host.querySelector(
      '[data-testid="order-reject-reason-note"]',
    ) as HTMLTextAreaElement;
    noteField.value = 'клиент оскорблял оператора';
    noteField.dispatchEvent(new Event('input'));
    (
      host.querySelector('[data-testid="order-reject-reason-confirm"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(reject).toHaveBeenCalledWith(
      FAKE_SCOPE,
      'order-1',
      expect.any(String),
      'OTHER',
      'клиент оскорблял оператора',
    );
  });
});

/**
 * The order board is the second caller of the `Toasts` service (ADR 0101, row
 * `X.17`); `customers-page.spec.ts` is the first, and `shell.spec.ts` proves
 * the single host that renders what both of them raise.
 *
 * Before this, an action applied from a row changed the row and said nothing:
 * the operator's only confirmation was noticing a status word change in a
 * table of twelve.
 */
describe('OrderQueue: migrated to shared/ui', () => {
  it('announces an applied action, with no order number and no customer data in the sentence', async () => {
    const approve = vi.fn().mockReturnValue(
      of({
        orderId: 'order-1',
        status: 'CONFIRMED',
        version: 2,
        applied: true,
        effectiveDecisionId: null,
        effectiveAction: null,
      }),
    );
    configureWithActions(
      [order({ status: 'AWAITING_APPROVAL', actions: [{ action: 'APPROVE' }] })],
      { approve },
    );
    const toasts = TestBed.inject(Toasts);
    toasts.clear();

    const harness = await RouterTestingHarness.create('/orders?tab=attention');
    await flushMicrotasks();
    (
      harness.routeNativeElement!.querySelector(
        '[data-testid="order-row-action-APPROVE"]',
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    const announced = toasts.visible();
    expect(announced.map((toast) => toast.message)).toEqual(['Order updated']);
    expect(announced[0].tone).toBe('success');
    // ADR 0029: a toast is transient text on a terminal in a dining room.
    expect(announced[0].message).not.toContain('order-1');
    toasts.clear();
  });
});

/**
 * orders.md §2.4, wave P07: the toolbar's own filters, bound to `GET
 * .../orders/board`'s real query parameters and persisted per tab.
 * `boardQueryParams`'s own unit tests (`order-queue-filter-state.spec.ts`)
 * cover every filter's exact mapping in isolation; these prove the wiring
 * from a control on screen through to that same call.
 */
describe('OrderQueue: toolbar filters (orders.md §2.4, wave P07)', () => {
  it('sends the search box’s text as the board’s reference parameter', async () => {
    const getOrders = ordersResponse([]);
    configure(getOrders);
    const harness = await RouterTestingHarness.create('/orders?tab=all');
    await flushMicrotasks();

    const search = harness.routeNativeElement!.querySelector(
      '[data-testid="order-queue-filter-search"]',
    ) as HTMLInputElement;
    search.value = '0911-142';
    search.dispatchEvent(new Event('input'));
    await flushSearchDebounce();

    const lastCall = getOrders.mock.calls.at(-1)!;
    expect(lastCall[1].params.reference).toBe('0911-142');
  });

  it('sends the fulfilment-type select as the board’s fulfillmentMode parameter', async () => {
    const getOrders = ordersResponse([]);
    configure(getOrders);
    const harness = await RouterTestingHarness.create('/orders?tab=all');
    await flushMicrotasks();

    const select = harness.routeNativeElement!.querySelector(
      '[data-testid="order-queue-filter-fulfillmentMode"]',
    ) as HTMLSelectElement;
    select.value = 'DELIVERY';
    select.dispatchEvent(new Event('change'));
    await flushMicrotasks();

    const lastCall = getOrders.mock.calls.at(-1)!;
    expect(lastCall[1].params.fulfillmentMode).toBe('DELIVERY');
  });

  it('sends «Мои заказы» as createdByActorId once the operator’s own subject is known', async () => {
    const getOrders = ordersResponse([]);
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
        {
          provide: CurrentTenant,
          useValue: {
            subject: signal('actor-1'),
            scopes: signal([]),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: ApiClient, useValue: { get: getOrders } },
        { provide: OrderCounts, useValue: { forOrders: () => Promise.resolve(zeroTabCounts()) } },
        { provide: RejectReasonsApi, useValue: stubRejectReasons() },
        {
          provide: LatenessPolicyApi,
          useValue: { resolve: () => Promise.resolve(PLATFORM_DEFAULT_LATENESS_POLICY) },
        },
      ],
    });
    TestBed.inject(I18n).setLocale('en');

    const harness = await RouterTestingHarness.create('/orders?tab=all');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    // «Мои заказы» lives behind §2.4's "⋯ ещё" secondary row.
    (host.querySelector('[data-testid="q-filter-bar-more"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    const toggle = host.querySelector(
      '[data-testid="order-queue-filter-mine"]',
    ) as HTMLInputElement;
    toggle.click();
    await flushMicrotasks();

    const lastCall = getOrders.mock.calls.at(-1)!;
    expect(lastCall[1].params.createdByActorId).toBe('actor-1');
  });

  it('persists a filter set on one tab, and restores it after what a reload does', async () => {
    configure(ordersResponse([]));
    let harness = await RouterTestingHarness.create('/orders?tab=attention');
    await flushMicrotasks();

    let search = harness.routeNativeElement!.querySelector(
      '[data-testid="order-queue-filter-search"]',
    ) as HTMLInputElement;
    search.value = '0911-142';
    search.dispatchEvent(new Event('input'));
    await flushMicrotasks();

    // A fresh `TestBed` is the closest a unit test comes to a full reload:
    // no in-memory state survives, only what `localStorage` remembers.
    // `resetTestingModule` is required, not optional, here — a component was
    // already created above, and `configureTestingModule` refuses to run
    // again without it.
    TestBed.resetTestingModule();
    configure(ordersResponse([]));
    harness = await RouterTestingHarness.create('/orders?tab=attention');
    await flushMicrotasks();

    search = harness.routeNativeElement!.querySelector(
      '[data-testid="order-queue-filter-search"]',
    ) as HTMLInputElement;
    expect(search.value).toBe('0911-142');
  });

  it('keeps a different tab’s filters independent — switching away and back does not carry the search text over', async () => {
    configure(ordersResponse([]));
    const harness = await RouterTestingHarness.create('/orders?tab=attention');
    await flushMicrotasks();

    const search = harness.routeNativeElement!.querySelector(
      '[data-testid="order-queue-filter-search"]',
    ) as HTMLInputElement;
    search.value = '0911-142';
    search.dispatchEvent(new Event('input'));
    await flushMicrotasks();

    const completedTab = [...harness.routeNativeElement!.querySelectorAll('[role="tab"]')].find(
      (button) => button.textContent?.trim() === 'Completed',
    ) as HTMLElement;
    completedTab.click();
    await flushMicrotasks();

    const onCompleted = harness.routeNativeElement!.querySelector(
      '[data-testid="order-queue-filter-search"]',
    ) as HTMLInputElement;
    expect(onCompleted.value).toBe('');

    const attentionTab = [...harness.routeNativeElement!.querySelectorAll('[role="tab"]')].find(
      (button) => button.textContent?.trim() === 'Attention',
    ) as HTMLElement;
    attentionTab.click();
    await flushMicrotasks();

    const backOnAttention = harness.routeNativeElement!.querySelector(
      '[data-testid="order-queue-filter-search"]',
    ) as HTMLInputElement;
    expect(backOnAttention.value).toBe('0911-142');
  });

  it('offers «Сбросить фильтры» only once something is filtering, and clears every field but the period', async () => {
    configure(ordersResponse([]));
    const harness = await RouterTestingHarness.create('/orders?tab=all');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    expect(host.querySelector('[data-testid="q-filter-bar-reset"]')).toBeNull();

    const search = host.querySelector(
      '[data-testid="order-queue-filter-search"]',
    ) as HTMLInputElement;
    search.value = '0911-142';
    search.dispatchEvent(new Event('input'));
    await flushMicrotasks();

    const reset = host.querySelector('[data-testid="q-filter-bar-reset"]') as HTMLButtonElement;
    expect(reset).not.toBeNull();
    reset.click();
    await flushMicrotasks();

    const restored = host.querySelector(
      '[data-testid="order-queue-filter-search"]',
    ) as HTMLInputElement;
    expect(restored.value).toBe('');
  });
});

/**
 * orders.md §2.10, wave P07: selection, the bulk-action bar, and §2.10's
 * result panel. Bulk courier assignment is explicitly out of scope.
 */
describe('OrderQueue: selection and bulk actions (orders.md §2.10, wave P07)', () => {
  it('offers no selection column to an operator without ORDER_BULK_ACTION', async () => {
    configure(ordersResponse([order({ status: 'CONFIRMED', actions: [{ action: 'CANCEL' }] })]));
    const harness = await RouterTestingHarness.create('/orders?tab=preparing');
    await flushMicrotasks();

    expect(
      harness.routeNativeElement!.querySelector('[data-testid="order-row-select"]'),
    ).toBeNull();
    expect(
      harness.routeNativeElement!.querySelector('[data-testid="order-queue-select-page"]'),
    ).toBeNull();
  });

  it('selects a row and shows the bulk bar, replacing the filter row, for an operator who holds ORDER_BULK_ACTION', async () => {
    const getOrders = ordersResponse([
      order({
        orderId: 'a',
        publicOrderNumber: '0001',
        status: 'CONFIRMED',
        actions: [{ action: 'CANCEL' }],
      }),
    ]);
    configureForBulk(getOrders);
    const harness = await RouterTestingHarness.create('/orders?tab=preparing');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    expect(host.querySelector('[data-testid="order-queue-filter-bar"]')).not.toBeNull();

    (host.querySelector('[data-testid="order-row-select"]') as HTMLInputElement).click();
    await flushMicrotasks();

    expect(host.querySelector('[data-testid="order-queue-filter-bar"]')).toBeNull();
    expect(host.querySelector('[data-testid="order-queue-bulk-bar"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="order-queue-bulk-count"]')?.textContent).toContain(
      'Selected 1',
    );
    expect(host.querySelector('[data-testid="order-queue-bulk-cancel"]')).not.toBeNull();
  });

  it('never offers bulk courier assignment — explicitly out of scope', async () => {
    const getOrders = ordersResponse([
      order({
        orderId: 'a',
        status: 'CONFIRMED',
        actions: [{ action: 'CANCEL' }, { action: 'ADVANCE', targetStatus: 'PREPARING' }],
      }),
    ]);
    configureForBulk(getOrders);
    const harness = await RouterTestingHarness.create('/orders?tab=preparing');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    (host.querySelector('[data-testid="order-row-select"]') as HTMLInputElement).click();
    await flushMicrotasks();

    // The bulk bar says explicitly that bulk courier assignment is not
    // offered, and no button in it performs one.
    expect(host.querySelector('[data-testid="order-queue-bulk-courier-note"]')).not.toBeNull();
    const bulkBar = host.querySelector('[data-testid="order-queue-bulk-bar"]')!;
    const buttonLabels = [...bulkBar.querySelectorAll('button')].map((button) =>
      button.textContent?.trim().toLowerCase(),
    );
    expect(buttonLabels.some((label) => label?.includes('courier'))).toBe(false);
  });

  it('offers Отменить only when every selected order can be cancelled, with a reason when it cannot', async () => {
    const getOrders = ordersResponse([
      order({
        orderId: 'a',
        publicOrderNumber: '0001',
        status: 'CONFIRMED',
        actions: [{ action: 'CANCEL' }],
      }),
      order({ orderId: 'b', publicOrderNumber: '0002', status: 'COMPLETED', actions: [] }),
    ]);
    configureForBulk(getOrders);
    const harness = await RouterTestingHarness.create('/orders?tab=all');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    const checkboxes = [
      ...host.querySelectorAll('[data-testid="order-row-select"]'),
    ] as HTMLInputElement[];
    checkboxes[0].click();
    checkboxes[1].click();
    await flushMicrotasks();

    expect(host.querySelector('[data-testid="order-queue-bulk-cancel"]')).toBeNull();
    const notice = host.querySelector('[data-testid="order-queue-bulk-cancel-unavailable"]');
    expect(notice?.textContent).toContain('1 of 2');
  });

  it('selection survives a refresh that still contains the order — a page boundary', async () => {
    vi.useFakeTimers();
    try {
      setVisibility('visible');
      const a = order({
        orderId: 'a',
        publicOrderNumber: '0001',
        status: 'CONFIRMED',
        actions: [{ action: 'CANCEL' }],
      });
      const b = order({
        orderId: 'b',
        publicOrderNumber: '0002',
        status: 'CONFIRMED',
        actions: [{ action: 'CANCEL' }],
      });
      const getOrders = vi
        .fn()
        .mockReturnValue(of({ value: { items: [a, b], nextCursor: null }, version: null }));
      configureForBulk(getOrders);

      const harness = await RouterTestingHarness.create('/orders?tab=preparing');
      await vi.advanceTimersByTimeAsync(FRAME_MS);
      const host = harness.routeNativeElement!;

      const checkboxes = [
        ...host.querySelectorAll('[data-testid="order-row-select"]'),
      ] as HTMLInputElement[];
      checkboxes[0].click();
      checkboxes[1].click();
      await vi.advanceTimersByTimeAsync(FRAME_MS);
      expect(host.querySelector('[data-testid="order-queue-bulk-count"]')?.textContent).toContain(
        'Selected 2',
      );

      // The 10s poll refetches the identical window — both orders are still there.
      await vi.advanceTimersByTimeAsync(10_000);
      await vi.advanceTimersByTimeAsync(FRAME_MS);

      expect(host.querySelector('[data-testid="order-queue-bulk-count"]')?.textContent).toContain(
        'Selected 2',
      );
    } finally {
      vi.useRealTimers();
    }
  });

  it('drops a selected order from the selection once a refresh no longer includes it', async () => {
    vi.useFakeTimers();
    try {
      setVisibility('visible');
      const a = order({
        orderId: 'a',
        publicOrderNumber: '0001',
        status: 'CONFIRMED',
        actions: [{ action: 'CANCEL' }],
      });
      const b = order({
        orderId: 'b',
        publicOrderNumber: '0002',
        status: 'CONFIRMED',
        actions: [{ action: 'CANCEL' }],
      });
      const getOrders = vi
        .fn()
        .mockReturnValueOnce(of({ value: { items: [a, b], nextCursor: null }, version: null }))
        .mockReturnValue(of({ value: { items: [a], nextCursor: null }, version: null }));
      configureForBulk(getOrders);

      const harness = await RouterTestingHarness.create('/orders?tab=preparing');
      await vi.advanceTimersByTimeAsync(FRAME_MS);
      const host = harness.routeNativeElement!;

      const checkboxes = [
        ...host.querySelectorAll('[data-testid="order-row-select"]'),
      ] as HTMLInputElement[];
      checkboxes[0].click();
      checkboxes[1].click();
      await vi.advanceTimersByTimeAsync(FRAME_MS);
      expect(host.querySelector('[data-testid="order-queue-bulk-count"]')?.textContent).toContain(
        'Selected 2',
      );

      // Order "b" leaves the fetched window on the next poll — its selection drops silently.
      await vi.advanceTimersByTimeAsync(10_000);
      await vi.advanceTimersByTimeAsync(FRAME_MS);

      expect(host.querySelector('[data-testid="order-queue-bulk-count"]')?.textContent).toContain(
        'Selected 1',
      );
    } finally {
      vi.useRealTimers();
    }
  });

  it('renders §2.10’s result panel for a partial-failure bulk cancel, then retries only the failed item under a fresh submission', async () => {
    const a = order({
      orderId: 'a',
      publicOrderNumber: '0001',
      status: 'CONFIRMED',
      actions: [{ action: 'CANCEL' }],
    });
    const b = order({
      orderId: 'b',
      publicOrderNumber: '0002',
      status: 'CONFIRMED',
      actions: [{ action: 'CANCEL' }],
    });
    const getOrders = ordersResponse([a, b]);
    const bulkSubmit = vi.fn().mockReturnValue(
      of({
        bulkOperationId: 'bulk-1',
        actionType: 'CANCEL',
        requestedCount: 2,
        appliedCount: 1,
        failedCount: 1,
        replayed: false,
        items: [
          { orderId: 'a', itemStatus: 'APPLIED', resultingOrderVersion: 1 },
          { orderId: 'b', itemStatus: 'FAILED', itemProblemCode: 'STALE_VERSION' },
        ],
      }),
    );
    configureForBulk(getOrders, { bulkSubmit });
    const harness = await RouterTestingHarness.create('/orders?tab=preparing');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    const checkboxes = [
      ...host.querySelectorAll('[data-testid="order-row-select"]'),
    ] as HTMLInputElement[];
    checkboxes[0].click();
    checkboxes[1].click();
    await flushMicrotasks();

    (host.querySelector('[data-testid="order-queue-bulk-cancel"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(host.querySelector('[data-testid="order-outcome-reason-dialog"]')).not.toBeNull();
    (
      host.querySelector('[data-testid="order-outcome-reason-option-reason-1"]') as HTMLInputElement
    ).dispatchEvent(new Event('change'));
    (
      host.querySelector('[data-testid="order-outcome-reason-confirm"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(bulkSubmit).toHaveBeenCalledTimes(1);
    const resultPanel = host.querySelector('[data-testid="order-queue-bulk-result"]');
    expect(resultPanel?.textContent).toContain('1 applied · 1 problems');
    const items = [...host.querySelectorAll('[data-testid="order-queue-bulk-result-item"]')];
    expect(items).toHaveLength(1);
    expect(items[0].textContent).toContain('0002');
    expect(items[0].textContent).toContain('The order changed since it was selected');

    (host.querySelector('[data-testid="order-queue-bulk-retry"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(bulkSubmit).toHaveBeenCalledTimes(2);
    const retryRequest = bulkSubmit.mock.calls[1][1];
    expect(retryRequest.orders).toEqual([{ orderId: 'b', expectedVersion: 0 }]);
    // A retry is a fresh intent: this is a second, distinct call, never the
    // same body resubmitted under the first call's own key.
    expect(retryRequest).not.toBe(bulkSubmit.mock.calls[0][1]);
  });

  it('renders "all applied" without a result list when nothing failed', async () => {
    const a = order({
      orderId: 'a',
      publicOrderNumber: '0001',
      status: 'CONFIRMED',
      actions: [{ action: 'CANCEL' }],
    });
    const getOrders = ordersResponse([a]);
    const bulkSubmit = vi.fn().mockReturnValue(
      of({
        bulkOperationId: 'bulk-2',
        actionType: 'CANCEL',
        requestedCount: 1,
        appliedCount: 1,
        failedCount: 0,
        replayed: false,
        items: [{ orderId: 'a', itemStatus: 'APPLIED', resultingOrderVersion: 1 }],
      }),
    );
    configureForBulk(getOrders, { bulkSubmit });
    const harness = await RouterTestingHarness.create('/orders?tab=preparing');
    await flushMicrotasks();
    const host = harness.routeNativeElement!;

    (host.querySelector('[data-testid="order-row-select"]') as HTMLInputElement).click();
    await flushMicrotasks();
    (host.querySelector('[data-testid="order-queue-bulk-cancel"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    (
      host.querySelector('[data-testid="order-outcome-reason-option-reason-1"]') as HTMLInputElement
    ).dispatchEvent(new Event('change'));
    (
      host.querySelector('[data-testid="order-outcome-reason-confirm"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(host.querySelector('[data-testid="order-queue-bulk-result"]')?.textContent).toContain(
      'All 1 applied',
    );
    expect(host.querySelector('[data-testid="order-queue-bulk-retry"]')).toBeNull();
  });
});

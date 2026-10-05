import { Location } from '@angular/common';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Observable, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { ApiClient } from '../../core/api/api-client';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { CurrentLocation, LocationOption } from '../../core/auth/current-location';
import { SessionCapabilities } from '../../core/auth/session-capabilities';
import { I18n } from '../../core/i18n/i18n';
import { BrandOrderStream } from '../../core/realtime/brand-order-stream';
import { RealtimeClient, RealtimeFrame } from '../../core/realtime/realtime-client';
import { LatenessPolicy, PLATFORM_DEFAULT_LATENESS_POLICY } from '../../core/lateness-policy';
import { LatenessPolicyApi } from '../../core/lateness-policy-api';
import { RejectReasonsApi } from './order-reject-reasons-api';
import { OrderActionsApi } from './order-actions-api';
import { OrderCounts, zeroTabCounts } from './order-counts';
import { OrderQueue } from './order-queue';
import { OrderSummaryResponse } from './order-summary';

/**
 * Wave 16, gap map rows `1.1` and `1.1c`: the queue's «Все филиалы» mode over the
 * brand-scoped board, its Филиал column and filter, the aggregator-binding
 * filter, and the four secondary toggles. `order-queue-filter-state.spec.ts`
 * owns each filter's exact query-parameter mapping in isolation; this file
 * proves the wiring from a control on screen to the call it makes, and that a
 * row on another branch is acted on and opened as that branch's row.
 */

const SCOPE = { tenantId: 't1', brandId: 'b1', locationId: 'l1' } as const;

const BRANCHES: readonly LocationOption[] = [
  { id: 'l1', displayName: 'Chilonzor', status: 'ACTIVE' },
  { id: 'l2', displayName: 'Yunusobod', status: 'ACTIVE' },
];

const BRANCH_BOARD = '/api/v1/tenants/t1/brands/b1/locations/l1/orders/board';
const BRAND_BOARD = '/api/v1/operations/tenants/t1/brands/b1/orders/board';
const BRANCH_BINDINGS = '/api/v1/tenants/t1/brands/b1/locations/l1/orders/marketplace-bindings';
const BRAND_BINDINGS = '/api/v1/operations/tenants/t1/brands/b1/orders/marketplace-bindings';

type GetCall = { readonly path: string; readonly params: Record<string, unknown> };

interface Harnessing {
  readonly calls: GetCall[];
  /** The location id of every lateness-policy read, in order. */
  readonly policyReads: string[];
  readonly selectLocation: ReturnType<typeof vi.fn>;
  readonly forOrders: ReturnType<typeof vi.fn>;
}

function order(overrides: Partial<OrderSummaryResponse>): OrderSummaryResponse {
  return {
    orderId: 'order-1',
    locationId: 'l1',
    publicOrderNumber: '0001',
    status: 'RECEIVED',
    createdAt: new Date().toISOString(),
    totalMinor: 100_000,
    currency: 'UZS',
    feeMinor: 0,
    discountMinor: 0,
    ...overrides,
  };
}

function page(items: readonly OrderSummaryResponse[]): Observable<unknown> {
  return of({ value: { items, nextCursor: null }, version: null });
}

type StreamState = 'connecting' | 'open' | 'reconnecting' | 'unavailable';

/** One stream that opens nothing: a test says what state it is in and delivers frames by hand. */
function fakeStream<State extends StreamState | null>(initial: State) {
  const listeners = new Set<(frame: RealtimeFrame) => void>();
  return {
    state: signal<State | StreamState>(initial),
    onFrame: (listener: (frame: RealtimeFrame) => void) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    /** Hands a frame to every listener, as the stream would. */
    deliver: (frame: RealtimeFrame) => listeners.forEach((listener) => listener(frame)),
  };
}

/**
 * The two streams the board listens on, replaced by fakes that open nothing: the branch stream
 * (`RealtimeClient`) and the brand stream (`BrandOrderStream`), which a test can count the
 * watchers of.
 */
interface FakeRealtime {
  readonly branch: ReturnType<typeof fakeStream<StreamState>>;
  readonly brand: ReturnType<typeof fakeStream<StreamState | null>> & {
    readonly watch: () => () => void;
    /** How many `watch()` calls are currently held. */
    readonly watchers: () => number;
  };
}

function fakeRealtime(): FakeRealtime {
  let watchers = 0;
  return {
    branch: fakeStream<StreamState>('open'),
    brand: {
      ...fakeStream<StreamState | null>(null),
      watch: () => {
        watchers += 1;
        let released = false;
        return () => {
          if (!released) {
            released = true;
            watchers -= 1;
          }
        };
      },
      watchers: () => watchers,
    },
  };
}

function signalFrame(scope: string, channel = 'order_queue'): RealtimeFrame {
  return {
    kind: 'signal',
    channel,
    scope,
    resourceType: 'Order',
    resourceId: 'order-1',
    version: 2,
    occurredAt: '2026-10-05T09:00:00Z',
  };
}

/**
 * Configures the queue for an operator who reads the whole brand (unless
 * `branches` says otherwise). `respond` answers each `ApiClient.get` by path —
 * the one shared stub of the sibling spec answers every path alike, which
 * cannot tell the two boards apart.
 */
function configure(options: {
  readonly respond: (path: string, params: Record<string, unknown>) => Observable<unknown>;
  readonly branches?: readonly LocationOption[];
  readonly bulk?: boolean;
  readonly actions?: Partial<OrderActionsApi>;
  readonly policyFor?: (locationId: string) => LatenessPolicy | null;
  /** Replaces the real streams, which would try to open connections. */
  readonly realtime?: FakeRealtime;
}): Harnessing {
  const calls: GetCall[] = [];
  const policyReads: string[] = [];
  const selectLocation = vi.fn();
  const forOrders = vi.fn().mockResolvedValue(zeroTabCounts());
  TestBed.configureTestingModule({
    providers: [
      provideRouter([
        { path: 'orders', component: OrderQueue },
        { path: 'orders/:id', component: OrderQueue },
      ]),
      {
        provide: CurrentLocation,
        useValue: {
          scope: signal(SCOPE),
          denied: signal(false),
          ensureLoaded: () => Promise.resolve(),
          options: signal(options.branches ?? BRANCHES),
          selectLocation,
        },
      },
      {
        provide: ApiClient,
        useValue: {
          get: (path: string, getOptions?: { params?: Record<string, unknown> }) => {
            const params = getOptions?.params ?? {};
            calls.push({ path, params });
            return options.respond(path, params);
          },
        },
      },
      { provide: OrderCounts, useValue: { forOrders } },
      { provide: RejectReasonsApi, useValue: { list: () => Promise.resolve([]) } },
      {
        provide: LatenessPolicyApi,
        useValue: {
          // `null` from `policyFor` is a read that failed: `read` says so, `resolve` papers over it.
          read: (scope: { locationId: string }) => {
            policyReads.push(scope.locationId);
            return Promise.resolve(
              options.policyFor
                ? options.policyFor(scope.locationId)
                : PLATFORM_DEFAULT_LATENESS_POLICY,
            );
          },
          resolve: (scope: { locationId: string }) =>
            Promise.resolve(
              options.policyFor?.(scope.locationId) ?? PLATFORM_DEFAULT_LATENESS_POLICY,
            ),
        },
      },
      {
        provide: SessionCapabilities,
        useValue: {
          has: (capability: string) => options.bulk === true && capability === 'ORDER_BULK_ACTION',
        },
      },
      { provide: OrderActionsApi, useValue: options.actions ?? {} },
      ...(options.realtime
        ? [
            { provide: RealtimeClient, useValue: options.realtime.branch },
            { provide: BrandOrderStream, useValue: options.realtime.brand },
          ]
        : []),
    ],
  });
  TestBed.inject(I18n).setLocale('en');
  return { calls, policyReads, selectLocation, forOrders };
}

async function settle(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

function boardCalls(h: Harnessing): GetCall[] {
  return h.calls.filter((call) => call.path === BRANCH_BOARD || call.path === BRAND_BOARD);
}

function choose(host: HTMLElement, testId: string, value: string): void {
  const select = host.querySelector(`[data-testid="${testId}"]`) as HTMLSelectElement;
  select.value = value;
  select.dispatchEvent(new Event('change'));
}

function openSecondaryRow(host: HTMLElement): void {
  (host.querySelector('[data-testid="q-filter-bar-more"]') as HTMLButtonElement).click();
}

afterEach(() => {
  localStorage.clear();
});

describe('OrderQueue: «Все филиалы» (gap map row 1.1, wave 16)', () => {
  it('is offered to a principal whose branch roster has two or more branches, and to nobody else', async () => {
    configure({ respond: () => page([]) });
    let harness = await RouterTestingHarness.create('/orders?tab=all');
    await settle();
    expect(
      harness.routeNativeElement!.querySelector('[data-testid="order-queue-filter-branchMode"]'),
    ).not.toBeNull();

    TestBed.resetTestingModule();
    configure({ respond: () => page([]), branches: [] });
    harness = await RouterTestingHarness.create('/orders?tab=all');
    await settle();
    expect(
      harness.routeNativeElement!.querySelector('[data-testid="order-queue-filter-branchMode"]'),
      'a location-scoped operator has no roster and keeps the single-branch board',
    ).toBeNull();
  });

  it('reads the branch board until «Все филиалы» is chosen, then the brand-scoped board', async () => {
    const h = configure({
      respond: (path) =>
        page(
          path === BRAND_BOARD
            ? [
                order({ orderId: 'a', publicOrderNumber: '0001', locationId: 'l1' }),
                order({ orderId: 'b', publicOrderNumber: '0002', locationId: 'l2' }),
              ]
            : [order({ orderId: 'a', publicOrderNumber: '0001', locationId: 'l1' })],
        ),
    });
    const harness = await RouterTestingHarness.create('/orders?tab=all');
    await settle();
    expect(boardCalls(h).at(-1)!.path).toBe(BRANCH_BOARD);

    choose(harness.routeNativeElement!, 'order-queue-filter-branchMode', 'ALL');
    await settle();

    expect(boardCalls(h).at(-1)!.path).toBe(BRAND_BOARD);
    expect(harness.routeNativeElement!.querySelectorAll('[data-testid="order-row"]')).toHaveLength(
      2,
    );
  });

  it('shows the Филиал column with each row’s branch name — and not on the branch board', async () => {
    configure({
      respond: () =>
        page([
          order({ orderId: 'a', publicOrderNumber: '0001', locationId: 'l1' }),
          order({ orderId: 'b', publicOrderNumber: '0002', locationId: 'l2' }),
        ]),
    });
    const harness = await RouterTestingHarness.create('/orders?tab=all');
    await settle();
    const host = harness.routeNativeElement!;
    expect(host.querySelector('[data-testid="order-queue-branch-column"]')).toBeNull();
    expect(host.querySelector('[data-testid="order-row-branch"]')).toBeNull();

    choose(host, 'order-queue-filter-branchMode', 'ALL');
    await settle();

    expect(host.querySelector('[data-testid="order-queue-branch-column"]')?.textContent).toContain(
      'Branch',
    );
    expect(
      [...host.querySelectorAll('[data-testid="order-row-branch"]')].map((cell) =>
        cell.textContent?.trim(),
      ),
    ).toEqual(expect.arrayContaining(['Chilonzor', 'Yunusobod']));
  });

  it('boots straight into the mode from a link, and the mode survives a filter reset', async () => {
    const h = configure({ respond: () => page([]) });
    const harness = await RouterTestingHarness.create('/orders?tab=all&branches=all&late=1');
    await settle();
    expect(boardCalls(h).at(-1)!.path).toBe(BRAND_BOARD);

    // «Сбросить фильтры» clears the toggle but not which board is being read.
    (
      harness.routeNativeElement!.querySelector(
        '[data-testid="q-filter-bar-reset"]',
      ) as HTMLButtonElement
    ).click();
    await settle();

    const last = boardCalls(h).at(-1)!;
    expect(last.path).toBe(BRAND_BOARD);
    expect(last.params['late']).toBeUndefined();
  });

  it('sends the Филиал filter as locationId to the brand board and counts that branch', async () => {
    const h = configure({ respond: () => page([order({ locationId: 'l2' })]) });
    const harness = await RouterTestingHarness.create('/orders?tab=all&branches=all');
    await settle();
    const host = harness.routeNativeElement!;

    expect(boardCalls(h).at(-1)!.params['locationId']).toBeUndefined();
    const brandWideCall = h.forOrders.mock.calls.at(-1)!;
    expect(brandWideCall[4], 'no branch narrowed: the tab badges read the brand’s totals').toBe(
      true,
    );

    choose(host, 'order-queue-filter-branch', 'l2');
    await settle();

    expect(boardCalls(h).at(-1)!.params['locationId']).toBe('l2');
    const narrowedCall = h.forOrders.mock.calls.at(-1)!;
    expect(narrowedCall[0]).toEqual({ ...SCOPE, locationId: 'l2' });
    expect(narrowedCall[4], 'one branch narrowed: that branch’s own counts').toBe(false);
  });

  it('never sends locationId to the branch board, even with a branch filter stored', async () => {
    localStorage.setItem(
      'horecaos.operations.orderQueue.filters.all',
      JSON.stringify({ allBranches: false, locationId: 'l2' }),
    );
    const h = configure({ respond: () => page([]) });
    await RouterTestingHarness.create('/orders?tab=all');
    await settle();

    const last = boardCalls(h).at(-1)!;
    expect(last.path).toBe(BRANCH_BOARD);
    expect(last.params['locationId']).toBeUndefined();
  });

  it('a 403 from the brand board withdraws the mode and reads the branch board instead', async () => {
    const h = configure({
      respond: (path) =>
        path === BRAND_BOARD
          ? throwError(() => new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null))
          : page([order({ orderId: 'a', publicOrderNumber: '0001' })]),
    });
    const harness = await RouterTestingHarness.create('/orders?tab=all&branches=all');
    await settle();
    const host = harness.routeNativeElement!;

    expect(boardCalls(h).map((call) => call.path)).toEqual([BRAND_BOARD, BRANCH_BOARD]);
    expect(host.querySelectorAll('[data-testid="order-row"]')).toHaveLength(1);
    expect(
      host.querySelector('[data-testid="order-queue-filter-branchMode"]'),
      'the refusal is a routing answer: the mode is withdrawn, not shown broken',
    ).toBeNull();
    expect(host.querySelector('[data-testid="order-queue-denied"]')).toBeNull();
  });

  it('withholds the selection column on «Все филиалы», says why, and restores it on one branch', async () => {
    configure({ respond: () => page([order({ orderId: 'a' })]), bulk: true });
    const harness = await RouterTestingHarness.create('/orders?tab=all');
    await settle();
    const host = harness.routeNativeElement!;
    expect(host.querySelector('[data-testid="order-row-select"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="order-queue-bulk-branch-note"]')).toBeNull();

    choose(host, 'order-queue-filter-branchMode', 'ALL');
    await settle();
    expect(host.querySelector('[data-testid="order-row-select"]')).toBeNull();
    expect(host.querySelector('[data-testid="order-queue-bulk-branch-note"]')).not.toBeNull();

    choose(host, 'order-queue-filter-branchMode', 'ONE');
    await settle();
    expect(host.querySelector('[data-testid="order-row-select"]')).not.toBeNull();
  });

  it('acts on a row at the row’s own branch, not the console’s current one', async () => {
    const advance = vi.fn().mockReturnValue(
      of({
        orderId: 'b',
        status: 'PREPARING',
        version: 2,
        applied: true,
        effectiveDecisionId: null,
        effectiveAction: null,
      }),
    );
    configure({
      respond: () =>
        page([
          order({
            orderId: 'b',
            locationId: 'l2',
            status: 'CONFIRMED',
            version: 1,
            actions: [{ action: 'ADVANCE', targetStatus: 'PREPARING' }],
          }),
        ]),
      actions: { advance },
    });
    const harness = await RouterTestingHarness.create('/orders?tab=preparing&branches=all');
    await settle();

    (
      harness.routeNativeElement!.querySelector(
        '[data-testid="order-row-action-ADVANCE"]',
      ) as HTMLButtonElement
    ).click();
    await settle();

    expect(advance).toHaveBeenCalledWith({ ...SCOPE, locationId: 'l2' }, 'b', 'PREPARING', 1);
  });

  it('opening a row of another branch switches the console to that branch first', async () => {
    const h = configure({
      respond: () => page([order({ orderId: 'b', locationId: 'l2', publicOrderNumber: '0002' })]),
    });
    const harness = await RouterTestingHarness.create('/orders?tab=all&branches=all');
    await settle();

    (harness.routeNativeElement!.querySelector('[data-testid="order-row"]') as HTMLElement).click();
    await settle();

    expect(h.selectLocation).toHaveBeenCalledWith('l2');
    expect(TestBed.inject(Location).path()).toContain('/orders/b');
  });

  it('opening a row of the current branch does not touch the branch selection', async () => {
    const h = configure({
      respond: () => page([order({ orderId: 'a', locationId: 'l1' })]),
    });
    const harness = await RouterTestingHarness.create('/orders?tab=all&branches=all');
    await settle();

    (harness.routeNativeElement!.querySelector('[data-testid="order-row"]') as HTMLElement).click();
    await settle();

    expect(h.selectLocation).not.toHaveBeenCalled();
  });

  it('judges each row by its own branch’s lateness policy', async () => {
    const relaxed: LatenessPolicy = {
      delivery: {
        atRiskBeforeSeconds: 300,
        lateAfterSeconds: 7200,
        noPromiseFallbackSeconds: 7200,
      },
      pickup: { atRiskBeforeSeconds: 300, lateAfterSeconds: 7200, noPromiseFallbackSeconds: 7200 },
      dineIn: { atRiskBeforeSeconds: 300, lateAfterSeconds: 7200, noPromiseFallbackSeconds: 7200 },
    };
    const promised = new Date(Date.now() - 30 * 60_000).toISOString();
    configure({
      respond: () =>
        page([
          order({
            orderId: 'strict',
            locationId: 'l1',
            publicOrderNumber: '0001',
            status: 'PREPARING',
            fulfillmentMode: 'DELIVERY',
            promisedAt: promised,
          }),
          order({
            orderId: 'relaxed',
            locationId: 'l2',
            publicOrderNumber: '0002',
            status: 'PREPARING',
            fulfillmentMode: 'DELIVERY',
            promisedAt: promised,
          }),
        ]),
      policyFor: (locationId) => (locationId === 'l2' ? relaxed : PLATFORM_DEFAULT_LATENESS_POLICY),
    });
    const harness = await RouterTestingHarness.create('/orders?tab=all&branches=all');
    await settle();

    const rows = [
      ...harness.routeNativeElement!.querySelectorAll<HTMLElement>('[data-testid="order-row"]'),
    ];
    const late = (number: string) =>
      rows
        .find((row) => row.querySelector('.q-mono')?.textContent?.trim() === number)
        ?.classList.contains('order-row--danger');
    expect(late('0001'), 'thirty minutes past the promise at a branch with no grace').toBe(true);
    expect(late('0002'), 'the same lateness at a branch that allows two hours').toBe(false);
  });

  describe('follows a branch policy edit made after the board opened', () => {
    const RELAXED: LatenessPolicy = {
      delivery: {
        atRiskBeforeSeconds: 300,
        lateAfterSeconds: 7200,
        noPromiseFallbackSeconds: 7200,
      },
      pickup: { atRiskBeforeSeconds: 300, lateAfterSeconds: 7200, noPromiseFallbackSeconds: 7200 },
      dineIn: { atRiskBeforeSeconds: 300, lateAfterSeconds: 7200, noPromiseFallbackSeconds: 7200 },
    };

    function twoBranchBoard(): OrderSummaryResponse[] {
      const promised = new Date(Date.now() - 30 * 60_000).toISOString();
      return [
        order({
          orderId: 'own',
          locationId: 'l1',
          publicOrderNumber: '0001',
          status: 'PREPARING',
          fulfillmentMode: 'DELIVERY',
          promisedAt: promised,
        }),
        order({
          orderId: 'other',
          locationId: 'l2',
          publicOrderNumber: '0002',
          status: 'PREPARING',
          fulfillmentMode: 'DELIVERY',
          promisedAt: promised,
        }),
      ];
    }

    function isLate(host: HTMLElement, number: string): boolean | undefined {
      return [...host.querySelectorAll<HTMLElement>('[data-testid="order-row"]')]
        .find((row) => row.querySelector('.q-mono')?.textContent?.trim() === number)
        ?.classList.contains('order-row--danger');
    }

    it('re-reads every branch’s policy on the poll, the shell branch’s and the others’', async () => {
      // Only the interval and the clock: the harness itself still needs real timeouts.
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] });
      try {
        let own: LatenessPolicy = PLATFORM_DEFAULT_LATENESS_POLICY;
        let other: LatenessPolicy = PLATFORM_DEFAULT_LATENESS_POLICY;
        configure({
          respond: () => page(twoBranchBoard()),
          policyFor: (locationId) => (locationId === 'l2' ? other : own),
        });
        const harness = await RouterTestingHarness.create('/orders?tab=all&branches=all');
        await settle();
        const host = harness.routeNativeElement!;
        expect(isLate(host, '0001')).toBe(true);
        expect(isLate(host, '0002')).toBe(true);

        own = RELAXED;
        other = RELAXED;
        vi.advanceTimersByTime(70_000);
        await settle();

        expect(isLate(host, '0001'), 'the shell branch’s own edit').toBe(false);
        expect(isLate(host, '0002'), 'another branch’s edit').toBe(false);
      } finally {
        vi.useRealTimers();
      }
    });

    it('does not take a branch whose first read failed for a loaded one: the next refresh asks again', async () => {
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] });
      try {
        let other: LatenessPolicy | null = null;
        configure({
          respond: () => page(twoBranchBoard()),
          policyFor: (locationId) =>
            locationId === 'l2' ? other : PLATFORM_DEFAULT_LATENESS_POLICY,
        });
        const harness = await RouterTestingHarness.create('/orders?tab=all&branches=all');
        await settle();
        const host = harness.routeNativeElement!;
        expect(isLate(host, '0002'), 'unread policy: the platform default').toBe(true);

        other = RELAXED;
        vi.advanceTimersByTime(11_000);
        await settle();

        expect(isLate(host, '0002')).toBe(false);
      } finally {
        vi.useRealTimers();
      }
    });

    it('asks again for a branch whose policy could not be read, and holds the answer once it comes', async () => {
      const promised = new Date(Date.now() - 30 * 60_000).toISOString();
      let secondBranchAnswers = false;
      const h = configure({
        respond: () =>
          page([
            order({
              orderId: 'second',
              locationId: 'l2',
              publicOrderNumber: '0002',
              status: 'PREPARING',
              fulfillmentMode: 'DELIVERY',
              promisedAt: promised,
            }),
          ]),
        policyFor: (locationId) =>
          locationId === 'l2'
            ? secondBranchAnswers
              ? RELAXED
              : null
            : PLATFORM_DEFAULT_LATENESS_POLICY,
      });
      const harness = await RouterTestingHarness.create('/orders?tab=all&branches=all');
      await settle();
      const host = harness.routeNativeElement!;
      const refresh = async () => {
        (host.querySelector('.order-queue__refresh') as HTMLButtonElement).click();
        await settle();
      };

      expect(
        h.policyReads.filter((id) => id === 'l2'),
        'asked once, and it failed',
      ).toHaveLength(1);
      expect(isLate(host, '0002'), 'meanwhile judged by the shell’s own policy').toBe(true);

      secondBranchAnswers = true;
      await refresh();

      expect(
        h.policyReads.filter((id) => id === 'l2'),
        'a failure is not remembered: the next refresh asks again',
      ).toHaveLength(2);
      expect(
        isLate(host, '0002'),
        'now the branch’s real policy, the one the server filters by',
      ).toBe(false);

      await refresh();

      expect(
        h.policyReads.filter((id) => id === 'l2'),
        'an answer is held for the tracker’s max age, not re-read on every refresh',
      ).toHaveLength(2);
      expect(
        h.policyReads.filter((id) => id === 'l1'),
        'the shell’s branch is read once inside that window',
      ).toHaveLength(1);
    });

    it('takes the shell’s own branch policy up when its first read failed and a later one answers', async () => {
      const promised = new Date(Date.now() - 30 * 60_000).toISOString();
      let shellBranchAnswers = false;
      configure({
        respond: () =>
          page([
            order({
              orderId: 'first',
              locationId: 'l1',
              publicOrderNumber: '0001',
              status: 'PREPARING',
              fulfillmentMode: 'DELIVERY',
              promisedAt: promised,
            }),
          ]),
        policyFor: () => (shellBranchAnswers ? RELAXED : null),
      });
      const harness = await RouterTestingHarness.create('/orders?tab=all&branches=all');
      await settle();
      const host = harness.routeNativeElement!;

      expect(isLate(host, '0001')).toBe(true);

      shellBranchAnswers = true;
      (host.querySelector('.order-queue__refresh') as HTMLButtonElement).click();
      await settle();

      expect(isLate(host, '0001')).toBe(false);
    });
  });
});

describe('OrderQueue: the four secondary toggles and the aggregator filter (gap map row 1.1c, wave 16)', () => {
  it('sends «Только опаздывающие», «С проблемой» and «Требуется звонок» as board parameters', async () => {
    const h = configure({ respond: () => page([]) });
    const harness = await RouterTestingHarness.create('/orders?tab=all');
    await settle();
    const host = harness.routeNativeElement!;
    openSecondaryRow(host);
    await settle();

    for (const testId of [
      'order-queue-filter-late',
      'order-queue-filter-problem',
      'order-queue-filter-callback',
    ]) {
      (host.querySelector(`[data-testid="${testId}"]`) as HTMLInputElement).click();
      await settle();
    }

    const last = boardCalls(h).at(-1)!;
    expect(last.params['late']).toBe('true');
    expect(last.params['problem']).toBe('true');
    expect(last.params['callbackRequested']).toBe('true');
  });

  it('shows each active toggle as a chip, and removing the chip drops the parameter', async () => {
    const h = configure({ respond: () => page([]) });
    const harness = await RouterTestingHarness.create('/orders?tab=all&late=1&problem=1');
    await settle();
    const host = harness.routeNativeElement!;

    const chips = [...host.querySelectorAll('[data-testid^="q-filter-bar-chip"]')];
    const labels = chips.map((chip) => chip.textContent?.trim() ?? '');
    expect(labels.some((label) => label.includes('Late only'))).toBe(true);
    expect(labels.some((label) => label.includes('With a problem'))).toBe(true);

    (host.querySelector('[data-testid="q-filter-bar-chip-late"]') as HTMLButtonElement).click();
    await settle();

    const last = boardCalls(h).at(-1)!;
    expect(last.params['late']).toBeUndefined();
    expect(last.params['problem']).toBe('true');
  });

  it('sends «Фискализация» as one status, or failed + blocked for the attention shortcut', async () => {
    const h = configure({ respond: () => page([]) });
    const harness = await RouterTestingHarness.create('/orders?tab=all');
    await settle();
    const host = harness.routeNativeElement!;
    openSecondaryRow(host);
    await settle();

    choose(host, 'order-queue-filter-fiscal', 'ISSUED');
    await settle();
    expect(boardCalls(h).at(-1)!.params['fiscalStatus']).toBe('ISSUED');

    choose(host, 'order-queue-filter-fiscal', 'ATTENTION');
    await settle();
    expect(boardCalls(h).at(-1)!.params['fiscalStatus']).toEqual(['FAILED', 'BLOCKED']);
  });

  it('offers the bindings the orders in scope arrived through, and filters by the one picked', async () => {
    const bindings = {
      items: [
        { bindingId: 'bind-1', providerType: 'WOLT', displayName: 'Wolt Chilonzor', orderCount: 4 },
        {
          bindingId: 'bind-2',
          providerType: 'YANDEX_EDA',
          displayName: 'Yandex Eda',
          orderCount: 1,
        },
      ],
    };
    const h = configure({
      respond: (path) =>
        path === BRANCH_BINDINGS ? of({ value: bindings, version: null }) : page([]),
    });
    const harness = await RouterTestingHarness.create('/orders?tab=all');
    await settle();
    const host = harness.routeNativeElement!;
    openSecondaryRow(host);
    await settle();

    const select = host.querySelector(
      '[data-testid="order-queue-filter-binding"]',
    ) as HTMLSelectElement;
    select.dispatchEvent(new Event('focus'));
    await settle();

    expect(h.calls.map((call) => call.path)).toContain(BRANCH_BINDINGS);
    const labels = [...select.querySelectorAll('option')].map((option) =>
      option.textContent?.trim(),
    );
    expect(labels).toEqual(
      expect.arrayContaining(['Wolt Chilonzor · WOLT', 'Yandex Eda · YANDEX_EDA']),
    );

    choose(host, 'order-queue-filter-binding', 'bind-2');
    await settle();
    expect(boardCalls(h).at(-1)!.params['marketplaceBindingId']).toBe('bind-2');
  });

  it('reads the brand-wide binding options on «Все филиалы», narrowed to the branch when one is picked', async () => {
    const h = configure({
      respond: (path) =>
        path === BRAND_BINDINGS ? of({ value: { items: [] }, version: null }) : page([]),
    });
    const harness = await RouterTestingHarness.create('/orders?tab=all&branches=all&branch=l2');
    await settle();
    const host = harness.routeNativeElement!;
    openSecondaryRow(host);
    await settle();

    (
      host.querySelector('[data-testid="order-queue-filter-binding"]') as HTMLSelectElement
    ).dispatchEvent(new Event('focus'));
    await settle();

    const call = h.calls.find((candidate) => candidate.path === BRAND_BINDINGS)!;
    expect(call.params['locationId']).toBe('l2');
  });

  it('names a binding filter that arrived in a link, without waiting for the control to be focused', async () => {
    const h = configure({
      respond: (path) =>
        path === BRANCH_BINDINGS
          ? of({
              value: {
                items: [
                  {
                    bindingId: 'bind-9',
                    providerType: 'WOLT',
                    displayName: 'Wolt Chilonzor',
                    orderCount: 2,
                  },
                ],
              },
              version: null,
            })
          : page([]),
    });
    const harness = await RouterTestingHarness.create('/orders?tab=all&binding=bind-9');
    await settle();

    expect(h.calls.map((call) => call.path)).toContain(BRANCH_BINDINGS);
    const chips = [
      ...harness.routeNativeElement!.querySelectorAll('[data-testid^="q-filter-bar-chip"]'),
    ].map((chip) => chip.textContent?.trim() ?? '');
    expect(chips.some((label) => label.includes('Wolt Chilonzor'))).toBe(true);
  });
});

describe('OrderQueue: «Выставить счёт» (gap map row 1.1e, wave 16)', () => {
  it('ISSUE_INVOICE opens the order — the re-issue form lives in the detail’s payment panel', async () => {
    configure({
      respond: () =>
        page([
          order({
            orderId: 'order-1',
            status: 'PAYMENT_AUTHORIZING',
            paymentStatusProjection: 'PENDING',
            actions: [{ action: 'ISSUE_INVOICE' }],
          }),
        ]),
    });
    const harness = await RouterTestingHarness.create('/orders?tab=new');
    await settle();

    const button = harness.routeNativeElement!.querySelector(
      '[data-testid="order-row-action-ISSUE_INVOICE"]',
    ) as HTMLButtonElement;
    expect(button.textContent?.trim()).toBe('Issue invoice');
    button.click();
    await settle();

    expect(TestBed.inject(Location).path()).toBe('/orders/order-1?tab=new');
  });
});

describe('OrderQueue: the brand-wide stream (gap map row 1.1, wave 18)', () => {
  const BRAND_SCOPE = 'BRAND:b1';
  const BRANCH_SCOPE = 'LOCATION:l1';

  it('listens on the brand stream only while «Все филиалы» is on, and lets go when the operator leaves it', async () => {
    const realtime = fakeRealtime();
    configure({ respond: () => page([]), realtime });
    const harness = await RouterTestingHarness.create('/orders?tab=all');
    await settle();
    expect(realtime.brand.watchers(), 'one branch: nothing to listen to at the brand').toBe(0);

    choose(harness.routeNativeElement!, 'order-queue-filter-branchMode', 'ALL');
    await settle();
    expect(realtime.brand.watchers()).toBe(1);

    choose(harness.routeNativeElement!, 'order-queue-filter-branchMode', 'ONE');
    await settle();
    expect(realtime.brand.watchers(), 'back on one branch, the stream is released').toBe(0);
  });

  it('lets go of the brand stream when the screen is left', async () => {
    const realtime = fakeRealtime();
    configure({ respond: () => page([]), realtime });
    const harness = await RouterTestingHarness.create('/orders?tab=all&branches=all');
    await settle();
    expect(realtime.brand.watchers()).toBe(1);

    harness.fixture.destroy();

    expect(realtime.brand.watchers()).toBe(0);
  });

  it('re-reads the brand board when the brand stream says an order changed, without waiting for the poll', async () => {
    const realtime = fakeRealtime();
    const h = configure({ respond: () => page([]), realtime });
    await RouterTestingHarness.create('/orders?tab=all&branches=all');
    await settle();
    realtime.brand.state.set('open');
    const before = boardCalls(h).length;

    realtime.brand.deliver(signalFrame(BRAND_SCOPE));
    await settle();

    expect(boardCalls(h)).toHaveLength(before + 1);
    expect(boardCalls(h).at(-1)!.path).toBe(BRAND_BOARD);
  });

  it('with the brand stream open, ignores the branch stream: the operator’s own branch is in both, and one change is one read', async () => {
    const realtime = fakeRealtime();
    const h = configure({ respond: () => page([]), realtime });
    await RouterTestingHarness.create('/orders?tab=all&branches=all');
    await settle();
    realtime.brand.state.set('open');
    const before = boardCalls(h).length;

    // The same change, as the two streams deliver it.
    realtime.branch.deliver(signalFrame(BRANCH_SCOPE));
    realtime.brand.deliver(signalFrame(BRAND_SCOPE));
    await settle();

    expect(boardCalls(h)).toHaveLength(before + 1);
  });

  it('with the brand stream down, still takes the branch stream’s frames, as the mode did before the brand stream existed', async () => {
    const realtime = fakeRealtime();
    const h = configure({ respond: () => page([]), realtime });
    await RouterTestingHarness.create('/orders?tab=all&branches=all');
    await settle();
    realtime.brand.state.set('unavailable');
    const before = boardCalls(h).length;

    realtime.branch.deliver(signalFrame(BRANCH_SCOPE));
    await settle();

    expect(boardCalls(h)).toHaveLength(before + 1);
  });

  it('on one branch, a brand frame is not this board’s business', async () => {
    const realtime = fakeRealtime();
    const h = configure({ respond: () => page([]), realtime });
    await RouterTestingHarness.create('/orders?tab=all');
    await settle();
    const before = boardCalls(h).length;

    realtime.brand.deliver(signalFrame(BRAND_SCOPE));
    await settle();
    expect(boardCalls(h)).toHaveLength(before);

    realtime.branch.deliver(signalFrame(BRANCH_SCOPE));
    await settle();
    expect(boardCalls(h)).toHaveLength(before + 1);
  });

  it('a resync re-reads whichever stream sent it', async () => {
    const realtime = fakeRealtime();
    const h = configure({ respond: () => page([]), realtime });
    await RouterTestingHarness.create('/orders?tab=all&branches=all');
    await settle();
    realtime.brand.state.set('open');
    const before = boardCalls(h).length;

    realtime.brand.deliver({ kind: 'resync' });
    await settle();

    expect(boardCalls(h)).toHaveLength(before + 1);
  });

  describe('the poll, which is the fallback', () => {
    it('keeps polling every ten seconds while the brand stream is not open', async () => {
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] });
      try {
        const realtime = fakeRealtime();
        realtime.brand.state.set('reconnecting');
        const h = configure({ respond: () => page([]), realtime });
        await RouterTestingHarness.create('/orders?tab=all&branches=all');
        await settle();
        const before = boardCalls(h).length;

        vi.advanceTimersByTime(10_000);
        await settle();

        expect(boardCalls(h)).toHaveLength(before + 1);
      } finally {
        vi.useRealTimers();
      }
    });

    it('sits out the ten-second poll while the brand stream is open, and polls again once a minute has passed', async () => {
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] });
      try {
        const realtime = fakeRealtime();
        const h = configure({ respond: () => page([]), realtime });
        await RouterTestingHarness.create('/orders?tab=all&branches=all');
        await settle();
        realtime.brand.state.set('open');
        const before = boardCalls(h).length;

        vi.advanceTimersByTime(30_000);
        await settle();
        expect(
          boardCalls(h),
          'the stream is the notification; the poll has nothing to add',
        ).toHaveLength(before);

        vi.advanceTimersByTime(40_000);
        await settle();
        expect(boardCalls(h).length, 'the net under a lost signal').toBeGreaterThan(before);
      } finally {
        vi.useRealTimers();
      }
    });

    it('polls every ten seconds on one branch even with the brand stream open: the stream is not this board’s', async () => {
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] });
      try {
        const realtime = fakeRealtime();
        realtime.brand.state.set('open');
        const h = configure({ respond: () => page([]), realtime });
        await RouterTestingHarness.create('/orders?tab=all');
        await settle();
        const before = boardCalls(h).length;

        vi.advanceTimersByTime(10_000);
        await settle();

        expect(boardCalls(h)).toHaveLength(before + 1);
      } finally {
        vi.useRealTimers();
      }
    });
  });
});

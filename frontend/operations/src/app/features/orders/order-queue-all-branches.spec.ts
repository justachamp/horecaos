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
  readonly policyFor?: (locationId: string) => LatenessPolicy;
}): Harnessing {
  const calls: GetCall[] = [];
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
    ],
  });
  TestBed.inject(I18n).setLocale('en');
  return { calls, selectLocation, forOrders };
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

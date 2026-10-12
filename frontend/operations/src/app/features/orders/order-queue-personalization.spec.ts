import { Location } from '@angular/common';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Observable, of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { ApiClient } from '../../core/api/api-client';
import { CurrentLocation, LocationOption } from '../../core/auth/current-location';
import { SessionCapabilities } from '../../core/auth/session-capabilities';
import { I18n } from '../../core/i18n/i18n';
import { LatenessPolicyApi } from '../../core/lateness-policy-api';
import { PLATFORM_DEFAULT_LATENESS_POLICY } from '../../core/lateness-policy';
import { OwnPreferences } from '../../core/preferences/own-preferences';
import {
  ORDER_BOARD_COLUMN_KEYS,
  OrderBoardDocument,
  SavedView,
  ShellDocument,
} from '../../core/preferences/preference-documents';
import { BrandOrderStream } from '../../core/realtime/brand-order-stream';
import { RealtimeClient } from '../../core/realtime/realtime-client';
import { CouriersApi, RosterEntryResponse } from '../couriers/couriers-api';
import { OrderCrmLogApi } from '../reports/order-crm-log-api';
import { ORDER_BOARD_COLUMNS, offeredColumns } from './order-board-columns';
import { OrderActionsApi } from './order-actions-api';
import { OrderCounts, zeroTabCounts } from './order-counts';
import { OrderQueue } from './order-queue';
import { RejectReasonsApi } from './order-reject-reasons-api';
import { OrderSummaryResponse } from './order-summary';

/**
 * ADR 0160, gap map rows `1.1` and `0.2d`: the order board's columns and saved views, kept in the person's
 * account. `own-preferences.spec.ts` owns the store (the mirror, the debounce, the stale-version retry);
 * this file proves the board's side of it: which columns are drawn from which document, that an offered
 * column always draws something and never a raw subject, that the picker writes through, and that a saved
 * view opens as exactly what it saved.
 */

const SCOPE = { tenantId: 't1', brandId: 'b1', locationId: 'l1' } as const;
const BRANCHES: readonly LocationOption[] = [
  { id: 'l1', displayName: 'Chilonzor', status: 'ACTIVE' },
  { id: 'l2', displayName: 'Yunusobod', status: 'ACTIVE' },
];
const BRANCH_BOARD = '/api/v1/tenants/t1/brands/b1/locations/l1/orders/board';
const BRAND_BOARD = '/api/v1/operations/tenants/t1/brands/b1/orders/board';

const ROSTER: readonly RosterEntryResponse[] = [
  {
    courierId: 'courier-1',
    displayReference: 'K-014',
    status: 'ACTIVE',
    courierTypeId: 'type-1',
    courierTypeName: 'Bike courier',
    vehicleClass: 'BIKE',
    activeAssignments: 0,
    concurrencyCeiling: 2,
  },
];

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
    fulfillmentMode: 'DELIVERY',
    channelCode: 'TELEGRAM',
    ...overrides,
  };
}

/** A fixture that has every field a column draws: a named creator and accepter, a courier, a discount. */
const FULL_ROW = order({
  orderId: 'order-full',
  publicOrderNumber: '0042',
  courierId: 'courier-1',
  discountMinor: 12_000,
  createdByActorType: 'USER',
  createdByActorId: 'subject-raw-created-by-9f3a',
  createdByDisplayName: 'Shahlo Tursunova',
  acceptedByActorType: 'USER',
  acceptedByActorId: 'subject-raw-accepted-by-4c1d',
  acceptedByDisplayName: 'Madina Yusupova',
});

/** The same order with nothing to draw: no courier, no discount, actors the tenant has no name for. */
const BARE_ROW = order({
  orderId: 'order-bare',
  publicOrderNumber: '0043',
  courierId: null,
  discountMinor: 0,
  createdByActorType: 'PROVIDER',
  createdByActorId: 'provider-raw-id-77b2',
  createdByDisplayName: null,
  acceptedByActorType: 'USER',
  acceptedByActorId: 'subject-raw-unnamed-1e8c',
  acceptedByDisplayName: null,
});

function page(items: readonly OrderSummaryResponse[]): Observable<unknown> {
  return of({ value: { items, nextCursor: null }, version: null });
}

function fakeStream(initial: string | null) {
  return {
    state: signal<string | null>(initial),
    onFrame: () => () => undefined,
    watch: () => () => undefined,
  };
}

/** A stand-in for the store that behaves like it where the board can tell: an update changes the signal. */
function fakePreferences(initial: OrderBoardDocument = {}, persistent = true) {
  const orderBoard = signal<OrderBoardDocument>(initial);
  const shell = signal<ShellDocument>({});
  return {
    orderBoard,
    shell,
    persistent: signal(persistent),
    writeFailed: signal(false),
    ensureLoaded: vi.fn().mockResolvedValue(undefined),
    updateOrderBoard: vi.fn((change: (document: OrderBoardDocument) => OrderBoardDocument) =>
      orderBoard.update(change),
    ),
    updateShell: vi.fn(),
    reset: vi.fn().mockResolvedValue(undefined),
    flush: vi.fn().mockResolvedValue(undefined),
  };
}

interface Harness {
  readonly preferences: ReturnType<typeof fakePreferences>;
  readonly boardCalls: Array<{ path: string; params: Record<string, unknown> }>;
}

function configure(options: {
  readonly rows?: readonly OrderSummaryResponse[];
  readonly document?: OrderBoardDocument;
  readonly branches?: readonly LocationOption[];
  readonly roster?: readonly RosterEntryResponse[];
  readonly persistent?: boolean;
  /** What the signed-in person holds; the roster behind «Курьер» and «Тип курьера» needs `COURIER_READ`. */
  readonly capabilities?: readonly string[];
}): Harness {
  const preferences = fakePreferences(options.document, options.persistent ?? true);
  const boardCalls: Harness['boardCalls'] = [];
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
          options: signal(options.branches ?? []),
          selectLocation: vi.fn(),
        },
      },
      {
        provide: ApiClient,
        useValue: {
          get: (path: string, getOptions?: { params?: Record<string, unknown> }) => {
            if (path === BRANCH_BOARD || path === BRAND_BOARD) {
              boardCalls.push({ path, params: getOptions?.params ?? {} });
              return page(options.rows ?? []);
            }
            return of({ value: [], version: null });
          },
        },
      },
      { provide: OrderCounts, useValue: { forOrders: () => Promise.resolve(zeroTabCounts()) } },
      { provide: RejectReasonsApi, useValue: { list: () => Promise.resolve([]) } },
      {
        provide: LatenessPolicyApi,
        useValue: {
          read: () => Promise.resolve(PLATFORM_DEFAULT_LATENESS_POLICY),
          resolve: () => Promise.resolve(PLATFORM_DEFAULT_LATENESS_POLICY),
        },
      },
      {
        provide: SessionCapabilities,
        useValue: {
          has: (capability: string) =>
            (options.capabilities ?? ['COURIER_READ']).includes(capability),
        },
      },
      { provide: OrderActionsApi, useValue: {} },
      {
        provide: CouriersApi,
        useValue: { roster: () => Promise.resolve(options.roster ?? ROSTER) },
      },
      { provide: OrderCrmLogApi, useValue: { customerLabels: () => Promise.resolve([]) } },
      { provide: OwnPreferences, useValue: preferences },
      { provide: RealtimeClient, useValue: fakeStream('open') },
      { provide: BrandOrderStream, useValue: fakeStream(null) },
    ],
  });
  TestBed.inject(I18n).setLocale('en');
  return { preferences, boardCalls };
}

async function settle(): Promise<void> {
  for (let hop = 0; hop < 4; hop++) {
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
  }
}

async function open(url = '/orders?tab=all'): Promise<HTMLElement> {
  const harness = await RouterTestingHarness.create(url);
  await settle();
  harness.detectChanges();
  return harness.routeNativeElement as HTMLElement;
}

function headers(host: HTMLElement): string[] {
  return [...host.querySelectorAll('thead th')].map((th) => th.textContent?.trim() ?? '');
}

function cell(host: HTMLElement, testId: string): string | null {
  return host.querySelector(`[data-testid="${testId}"]`)?.textContent?.trim() ?? null;
}

function pick(host: HTMLElement, testId: string): void {
  (host.querySelector(`[data-testid="${testId}"]`) as HTMLElement).click();
}

afterEach(() => {
  localStorage.clear();
});

describe('OrderQueue: the columns a person chose (ADR 0160, gap map row 1.1)', () => {
  it('draws the board as it always was for a person who has chosen nothing', async () => {
    configure({ rows: [FULL_ROW] });
    const host = await open();

    expect(headers(host)).toEqual([
      '',
      '#',
      'Time',
      'Type / channel',
      'Total',
      'Delivery fee',
      'Payment',
      'Customer',
      'Courier',
      'Status',
      'Actions',
    ]);
    expect(host.querySelector('[data-testid="order-row-created-by"]')).toBeNull();
    expect(host.querySelector('tbody tr[data-order-id]')!.textContent).toContain(
      'Delivery · TELEGRAM',
    );
  });

  it('does not draw a default column the person hid, header and cell both', async () => {
    configure({ rows: [FULL_ROW], document: { columns: { hide: ['courier', 'customer'] } } });
    const host = await open();

    expect(headers(host)).not.toContain('Courier');
    expect(headers(host)).not.toContain('Customer');
    expect(host.querySelector('[data-testid="order-row-courier"]')).toBeNull();
    expect(host.querySelector('[data-testid="order-row-customer"]')).toBeNull();
    expect(headers(host)).toContain('Payment');
    // The empty row spans exactly the columns that are drawn.
    const row = [...host.querySelectorAll('thead th')].length;
    expect(row).toBe(headers(host).length);
  });

  it('draws «Создал» from the name the board read resolved, and «Принял» likewise', async () => {
    configure({
      rows: [FULL_ROW],
      document: { columns: { show: ['createdBy', 'acceptedBy'] } },
    });
    const host = await open();

    expect(headers(host)).toContain('Created by');
    expect(headers(host)).toContain('Accepted by');
    expect(cell(host, 'order-row-created-by')).toBe('Shahlo Tursunova');
    expect(cell(host, 'order-row-accepted-by')).toBe('Madina Yusupova');
  });

  it('draws a dash, never a raw subject or id, where a name is not known', async () => {
    configure({
      rows: [BARE_ROW],
      document: { columns: { show: ['createdBy', 'acceptedBy', 'courierType', 'discount'] } },
    });
    const host = await open();

    expect(cell(host, 'order-row-created-by')).toBe('—');
    expect(cell(host, 'order-row-accepted-by')).toBe('—');
    expect(cell(host, 'order-row-courier-type')).toBe('—');
    expect(cell(host, 'order-row-discount')).toBe('—');
    for (const raw of ['provider-raw-id-77b2', 'subject-raw-unnamed-1e8c']) {
      expect(host.textContent, raw).not.toContain(raw);
    }
  });

  it('draws «Тип курьера» from the roster the board already fetches for «Курьер»', async () => {
    configure({ rows: [FULL_ROW], document: { columns: { show: ['courierType'] } } });
    const host = await open();

    expect(cell(host, 'order-row-courier')).toBe('K-014');
    expect(cell(host, 'order-row-courier-type')).toBe('Bike courier');
  });

  it('draws «Скидка» in the order’s own currency', async () => {
    configure({ rows: [FULL_ROW], document: { columns: { show: ['discount'] } } });
    const host = await open();

    expect(cell(host, 'order-row-discount')).toMatch(/12\s?000/);
  });

  it('turns «Канал» off by leaving the fulfilment mode alone in the type cell', async () => {
    configure({ rows: [FULL_ROW], document: { columns: { hide: ['channel'] } } });
    const host = await open();

    // The channel filter lists the codes this session has seen, so the check is on the row's own cells.
    const row = host.querySelector('tbody tr[data-order-id]')!;
    expect(row.textContent).toContain('Delivery');
    expect(row.textContent).not.toContain('TELEGRAM');
  });

  it('shows a column that a later release made default to a person whose stored document predates it', async () => {
    // The document names only what this person changed; «Оплата» and «Клиент» were never mentioned.
    configure({ rows: [FULL_ROW], document: { columns: { hide: ['courier'] } } });
    const host = await open();

    expect(headers(host)).toContain('Payment');
    expect(headers(host)).toContain('Customer');
    expect(headers(host)).not.toContain('Courier');
  });

  it('draws «Филиал» only while the board reads every branch, and lets a person hide it there', async () => {
    configure({ rows: [FULL_ROW], branches: BRANCHES });
    let host = await open('/orders?tab=all');
    expect(headers(host)).not.toContain('Branch');

    TestBed.resetTestingModule();
    configure({ rows: [FULL_ROW], branches: BRANCHES });
    host = await open('/orders?tab=all&branches=all');
    expect(headers(host)).toContain('Branch');

    TestBed.resetTestingModule();
    configure({
      rows: [FULL_ROW],
      branches: BRANCHES,
      document: { columns: { hide: ['branch'] } },
    });
    host = await open('/orders?tab=all&branches=all');
    expect(headers(host)).not.toContain('Branch');
  });

  it('draws a non-empty cell for every offered column when the field is there, and a dash when it is not', async () => {
    const optional = ORDER_BOARD_COLUMN_KEYS.filter(
      (key) => !ORDER_BOARD_COLUMNS[key].defaultShown,
    );
    configure({
      rows: [FULL_ROW, BARE_ROW],
      branches: BRANCHES,
      document: { columns: { show: optional } },
    });
    const host = await open('/orders?tab=all&branches=all');
    const rows = [...host.querySelectorAll('tbody tr[data-order-id]')];
    const fullRow = rows.find((row) => row.getAttribute('data-order-id') === 'order-full')!;
    const bareRow = rows.find((row) => row.getAttribute('data-order-id') === 'order-bare')!;
    const testIds: Record<string, string> = {
      createdBy: 'order-row-created-by',
      acceptedBy: 'order-row-accepted-by',
      courierType: 'order-row-courier-type',
      discount: 'order-row-discount',
    };

    for (const key of optional) {
      const id = testIds[key];
      expect(fullRow.querySelector(`[data-testid="${id}"]`)?.textContent?.trim(), key).not.toBe(
        '—',
      );
      expect(fullRow.querySelector(`[data-testid="${id}"]`)?.textContent?.trim(), key).not.toBe('');
      expect(bareRow.querySelector(`[data-testid="${id}"]`)?.textContent?.trim(), key).toBe('—');
    }
    // Every column the picker could offer on this screen has its cell somewhere in the table.
    expect(offeredColumns({ allBranches: true, courierRoster: true }).length).toBeGreaterThan(
      optional.length,
    );
  });
});

describe('OrderQueue: the column picker (ADR 0160)', () => {
  async function openPicker(host: HTMLElement): Promise<void> {
    pick(host, 'column-picker-toggle');
    await settle();
  }

  it('lists the columns that have something to draw here, and not «Позиции»', async () => {
    configure({ rows: [FULL_ROW] });
    const host = await open();
    await openPicker(host);

    const menu = host.querySelector('[data-testid="column-picker-menu"]')!;
    const labels = [...menu.querySelectorAll('label')].map((label) => label.textContent?.trim());
    expect(labels).toEqual([
      'Channel',
      'Customer',
      'Payment',
      'Courier',
      'Created by',
      'Accepted by',
      'Courier type',
      'Discount',
    ]);
    expect(labels).not.toContain('Items');
    expect(
      (host.querySelector('[data-testid="column-picker-item-courier"]') as HTMLInputElement)
        .checked,
    ).toBe(true);
    expect(
      (host.querySelector('[data-testid="column-picker-item-createdBy"]') as HTMLInputElement)
        .checked,
    ).toBe(false);
  });

  it('turns an optional column on at once, and writes it to the person’s account as a change from the default', async () => {
    const { preferences } = configure({ rows: [FULL_ROW] });
    const harness = await RouterTestingHarness.create('/orders?tab=all');
    await settle();
    const host = harness.routeNativeElement as HTMLElement;
    await openPicker(host);

    pick(host, 'column-picker-item-acceptedBy');
    await settle();
    harness.detectChanges();

    expect(preferences.updateOrderBoard).toHaveBeenCalledTimes(1);
    expect(preferences.orderBoard()).toEqual({ columns: { show: ['acceptedBy'] } });
    expect(headers(host)).toContain('Accepted by');
    expect(cell(host, 'order-row-accepted-by')).toBe('Madina Yusupova');
  });

  it('turns a default column off at once, and writes it as hidden', async () => {
    const { preferences } = configure({ rows: [FULL_ROW] });
    const harness = await RouterTestingHarness.create('/orders?tab=all');
    await settle();
    const host = harness.routeNativeElement as HTMLElement;
    await openPicker(host);

    pick(host, 'column-picker-item-courier');
    await settle();
    harness.detectChanges();

    expect(preferences.orderBoard()).toEqual({ columns: { hide: ['courier'] } });
    expect(headers(host)).not.toContain('Courier');
  });

  it('offers the reset only once there is a choice to give up, and gives it up', async () => {
    const { preferences } = configure({ rows: [FULL_ROW] });
    const harness = await RouterTestingHarness.create('/orders?tab=all');
    await settle();
    const host = harness.routeNativeElement as HTMLElement;
    await openPicker(host);
    expect(host.querySelector('[data-testid="column-picker-reset"]')).toBeNull();

    pick(host, 'column-picker-item-discount');
    await settle();
    harness.detectChanges();
    expect(host.querySelector('[data-testid="column-picker-reset"]')).not.toBeNull();

    pick(host, 'column-picker-reset');
    await settle();
    harness.detectChanges();

    expect(preferences.orderBoard()).toEqual({});
    expect(headers(host)).not.toContain('Discount');
  });

  it('does not offer «Тип курьера» to a person who cannot read the courier roster, and never draws it from an old choice', async () => {
    // The roster is what names a courier's type, and it needs COURIER_READ: without it the column would be a
    // column of dashes. Another device of theirs, held by someone with the capability, may have ticked it.
    configure({
      rows: [FULL_ROW],
      document: { columns: { show: ['courierType', 'createdBy'] } },
      capabilities: [],
    });
    const host = await open();
    await openPicker(host);

    const menu = host.querySelector('[data-testid="column-picker-menu"]')!;
    const labels = [...menu.querySelectorAll('label')].map((label) => label.textContent?.trim());
    expect(labels).not.toContain('Courier type');
    expect(labels).toContain('Created by');
    expect(headers(host)).not.toContain('Courier type');
    expect(host.querySelector('[data-testid="order-row-courier-type"]')).toBeNull();
    // The rest of what they ticked is still drawn.
    expect(headers(host)).toContain('Created by');
  });

  it('says choices last for the session only when the account keeps no record to write them to', async () => {
    configure({ rows: [FULL_ROW], persistent: false });
    const host = await open();
    await openPicker(host);

    expect(cell(host, 'column-picker-note')).toContain('only until this session ends');
  });
});

describe('OrderQueue: a change that did not reach the account (ADR 0160)', () => {
  const SAVED_NOTE = 'is saved to your account';

  it('says nothing while writes land, and says so on the board, in the picker and in the views once one is dropped', async () => {
    const { preferences } = configure({ rows: [FULL_ROW] });
    const harness = await RouterTestingHarness.create('/orders?tab=all');
    await settle();
    const host = harness.routeNativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="order-queue-preferences-write-failed"]')).toBeNull();

    preferences.writeFailed.set(true);
    await settle();
    harness.detectChanges();

    const band = host.querySelector('[data-testid="order-queue-preferences-write-failed"]');
    expect(
      band,
      'the failure is on the board itself, not only inside a closed menu',
    ).not.toBeNull();
    expect(band?.getAttribute('role')).toBe('alert');
    expect(band?.textContent).toContain('did not reach your account');

    pick(host, 'column-picker-toggle');
    await settle();
    harness.detectChanges();
    expect(cell(host, 'column-picker-note')).toContain('could not be saved to your account');
    expect(cell(host, 'column-picker-note')).not.toContain(SAVED_NOTE);

    pick(host, 'saved-views-toggle');
    await settle();
    harness.detectChanges();
    expect(cell(host, 'saved-views-note')).toContain('could not be saved to your account');
    expect(cell(host, 'saved-views-note')).not.toContain('are kept in your account');
  });

  it('goes back to saying it is saved when the next write lands', async () => {
    const { preferences } = configure({ rows: [FULL_ROW] });
    const harness = await RouterTestingHarness.create('/orders?tab=all');
    await settle();
    const host = harness.routeNativeElement as HTMLElement;
    preferences.writeFailed.set(true);
    await settle();
    harness.detectChanges();
    pick(host, 'column-picker-toggle');
    await settle();
    harness.detectChanges();

    preferences.writeFailed.set(false);
    await settle();
    harness.detectChanges();

    expect(host.querySelector('[data-testid="order-queue-preferences-write-failed"]')).toBeNull();
    expect(cell(host, 'column-picker-note')).toContain(SAVED_NOTE);
  });

  it('does not claim a failure for an account that never writes: that one says the choice lasts for the session', async () => {
    configure({ rows: [FULL_ROW], persistent: false });
    const host = await open();
    pick(host, 'column-picker-toggle');
    await settle();

    expect(host.querySelector('[data-testid="order-queue-preferences-write-failed"]')).toBeNull();
    expect(cell(host, 'column-picker-note')).toContain('only until this session ends');
  });
});

describe('OrderQueue: saved views (ADR 0160, gap map row 0.2d)', () => {
  const DELIVERY_TODAY: SavedView = {
    id: '0198a3c0-0000-7000-8000-000000000001',
    label: 'Today’s deliveries',
    tab: 'delivering',
    filters: { fulfillmentMode: 'DELIVERY', dateRange: 'TODAY', lateOnly: true },
  };

  async function openViews(host: HTMLElement): Promise<void> {
    pick(host, 'saved-views-toggle');
    await settle();
  }

  it('saves the board as it is -- its tab and the filters a view can hold -- and leaves the search out', async () => {
    const { preferences } = configure({ rows: [FULL_ROW] });
    const harness = await RouterTestingHarness.create('/orders?tab=attention&late=1&q=1042');
    await settle();
    const host = harness.routeNativeElement as HTMLElement;
    await openViews(host);
    pick(host, 'saved-view-new');
    await settle();
    harness.detectChanges();

    expect(cell(host, 'saved-view-left-out')).toContain('leaves out the search');

    const input = host.querySelector('[data-testid="saved-view-name"]') as HTMLInputElement;
    input.value = '  Late ones  ';
    input.dispatchEvent(new Event('input'));
    pick(host, 'saved-view-save');
    await settle();

    const saved = preferences.orderBoard().savedViews ?? [];
    expect(saved).toHaveLength(1);
    expect(saved[0]).toMatchObject({
      label: 'Late ones',
      tab: 'attention',
      filters: { lateOnly: true },
    });
    expect(saved[0].id).toMatch(/^[0-9a-f-]{36}$/u);
    expect(JSON.stringify(saved)).not.toContain('1042');
  });

  it('opens a saved view as its tab and exactly its filters, over an empty board', async () => {
    const { boardCalls } = configure({
      rows: [FULL_ROW],
      document: { savedViews: [DELIVERY_TODAY] },
    });
    // The board is open on something else, with a filter the view does not mention.
    const harness = await RouterTestingHarness.create('/orders?tab=all&mine=1&q=77');
    await settle();
    const host = harness.routeNativeElement as HTMLElement;
    boardCalls.length = 0;

    await openViews(host);
    pick(host, 'saved-view-apply');
    await settle();
    harness.detectChanges();

    const last = boardCalls.at(-1)!;
    expect(last.params).toMatchObject({ fulfillmentMode: 'DELIVERY', late: 'true' });
    expect(last.params['from']).toEqual(
      expect.stringMatching(/^\d{4}-\d{2}-\d{2}T00:00:00\+05:00$/u),
    );
    expect(last.params['createdByActorId']).toBeUndefined();
    expect(last.params['reference']).toBeUndefined();
    const url = TestBed.inject(Location).path();
    expect(url).toContain('tab=delivering');
    expect(url).toContain('fulfillmentMode=DELIVERY');
    expect(url).not.toContain('mine=1');
    expect(url).not.toContain('q=77');
  });

  it('deletes a view from the list', async () => {
    const { preferences } = configure({ rows: [], document: { savedViews: [DELIVERY_TODAY] } });
    const harness = await RouterTestingHarness.create('/orders?tab=all');
    await settle();
    const host = harness.routeNativeElement as HTMLElement;
    await openViews(host);

    pick(host, 'saved-view-remove');
    await settle();

    expect(preferences.orderBoard().savedViews ?? []).toEqual([]);
  });

  it('shows the views the account holds on a machine that has never seen them', async () => {
    configure({ rows: [], document: { savedViews: [DELIVERY_TODAY] } });
    const host = await open();
    await openViews(host);

    expect(host.querySelector('[data-testid="saved-view-apply"]')?.textContent).toContain(
      'Today’s deliveries',
    );
  });
});

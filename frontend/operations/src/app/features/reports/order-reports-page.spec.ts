import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { LocationView, LocationsApi } from '../settings/locations/locations-api';
import { ChannelView, SalesChannelsApi } from '../settings/sales-channels/sales-channels-api';
import { OrderReportsPage } from './order-reports-page';
import { ReportsFilterState } from './reports-filter-state';
import {
  OrderListResponse,
  OrderRowResponse,
  QueryParams,
  QueryResponse,
  ReportingApi,
} from './reporting-api';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

/** Reaches past `protected`/`private` to drive the component from a test — same footing every other report-page spec uses. */
type OrderReportsPageInternals = {
  selectTab(tab: 'stages' | 'commercial' | 'daily' | 'summary' | 'late'): void;
  loadMoreCommercial(): Promise<void>;
};

function provenance() {
  return {
    asOf: '2026-09-12T04:00:00Z',
    closedThrough: '2026-09-11',
    lastCloseCompletedAt: '2026-09-11T22:00:00Z',
    businessDayStart: '00:00',
    timezone: 'Asia/Tashkent',
    boundaryVersion: 1,
    metricVersions: [],
    provisionalMetrics: [] as readonly string[],
    openDivergences: 0,
  };
}

function orderRow(overrides: Partial<OrderRowResponse> = {}): OrderRowResponse {
  return {
    orderId: '018f6f4e-0000-7000-8000-000000000001',
    businessDate: '2026-09-10',
    locationId: 'loc-1',
    legalEntityId: null,
    channelCode: 'TELEGRAM',
    fulfilmentType: 'DELIVERY',
    terminalStatus: 'COMPLETED',
    grossRevenueSom: 120_000,
    discountSom: 0,
    deliveryFeeSom: 10_000,
    taxSom: 0,
    netRevenueSom: 120_000,
    itemCount: 3,
    occurredAt: '2026-09-10T08:00:00Z',
    closedAt: '2026-09-10T08:40:00Z',
    secondsToConfirm: 60,
    secondsToReady: 900,
    secondsTotal: 2_400,
    secondsLate: null,
    cancellationReasonCode: null,
    secondsToAccept: 660,
    secondsPreparing: 540,
    publicOrderNumber: '0042',
    isPreorder: false,
    ...overrides,
  };
}

function ordersResponse(rows: readonly OrderRowResponse[], maybeMore = false): OrderListResponse {
  return { rows, maybeMore, provenance: provenance() };
}

/** One `/queries` row behind «Сводка» — wave 8 w7-reports (7.2c)'s own summary-tab fixture. */
function summaryRow(overrides: {
  readonly locationId: string;
  readonly channelCode: string;
  readonly fulfilmentType: string;
  readonly legalEntityId: string | null;
  readonly grossSom: number;
  readonly netSom: number;
  readonly deliveryFeeSom: number;
  readonly orderCount: number;
}): import('./reporting-api').RowResponse {
  return {
    businessDate: '2026-09-10',
    locationId: overrides.locationId,
    channelCode: overrides.channelCode,
    fulfilmentType: overrides.fulfilmentType,
    legalEntityId: overrides.legalEntityId,
    values: {
      'revenue.gross.v1': overrides.grossSom,
      'revenue.net.v1': overrides.netSom,
      'delivery_fee.v1': overrides.deliveryFeeSom,
      'orders.count.v1': overrides.orderCount,
    },
  };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('OrderReportsPage', () => {
  let fixture: ComponentFixture<OrderReportsPage>;
  let ordersSpy: ReturnType<typeof vi.fn>;
  let querySpy: ReturnType<typeof vi.fn>;

  /**
   * `ordersMock`/`queryMock` let a test install its own multi-call sequence
   * (`.mockResolvedValueOnce` chains for cursor paging, say) before the
   * component's own constructor-time `effect()` fires its first fetch.
   */
  async function render(options?: {
    readonly ordersMock?: ReturnType<typeof vi.fn>;
    readonly queryMock?: ReturnType<typeof vi.fn>;
    readonly configure?: (filters: ReportsFilterState) => void;
    readonly initialTab?: 'stages' | 'commercial' | 'daily' | 'summary' | 'late';
    readonly locations?: readonly LocationView[];
    readonly channels?: readonly ChannelView[];
  }): Promise<void> {
    TestBed.resetTestingModule();
    // ReportsFilterState (wave P27) reads its initial state from the URL on
    // construction — reset it so a prior test's filters never leak into the
    // next one's "fresh" instance (jsdom's window.location persists across
    // tests in this file).
    history.replaceState(null, '', '/statistics/orders');

    ordersSpy = options?.ordersMock ?? vi.fn().mockResolvedValue(ordersResponse([orderRow()]));
    querySpy =
      options?.queryMock ??
      vi.fn().mockResolvedValue({ rows: [], provenance: provenance() } satisfies QueryResponse);

    await TestBed.configureTestingModule({
      imports: [OrderReportsPage],
      providers: [
        provideRouter([]),
        ReportsFilterState,
        {
          provide: CurrentLocation,
          useValue: {
            scope: () => SCOPE,
            denied: () => false,
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: ReportingApi, useValue: { orders: ordersSpy, query: querySpy } },
        {
          provide: LocationsApi,
          useValue: { list: vi.fn().mockResolvedValue(options?.locations ?? []) },
        },
        {
          provide: SalesChannelsApi,
          useValue: { list: vi.fn().mockResolvedValue(options?.channels ?? []) },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    options?.configure?.(TestBed.inject(ReportsFilterState));
    fixture = TestBed.createComponent(OrderReportsPage);
    if (options?.initialTab) {
      internals().selectTab(options.initialTab);
    }
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  function internals(): OrderReportsPageInternals {
    return fixture.componentInstance as unknown as OrderReportsPageInternals;
  }

  it('pushes the fulfilment-type filter into the orders() query rather than filtering the page client-side', async () => {
    await render({ configure: (filters) => filters.setFulfilmentType('PICKUP') });
    await flushMicrotasks();

    expect(ordersSpy).toHaveBeenCalledWith(
      SCOPE.tenantId,
      expect.objectContaining({ fulfilmentType: ['PICKUP'] }),
    );
  });

  it('pushes branch, channel and legal-entity filters into orders() for every order-grain tab', async () => {
    await render({
      configure: (filters) => {
        filters.setLocationIds(['loc-9']);
        filters.setChannelCodes(['YANDEX']);
        filters.setLegalEntityIds(['entity-1']);
      },
    });
    await flushMicrotasks();

    expect(ordersSpy).toHaveBeenCalledWith(
      SCOPE.tenantId,
      expect.objectContaining({
        locationId: ['loc-9'],
        channelCode: ['YANDEX'],
        legalEntityId: ['entity-1'],
      }),
    );
  });

  it('re-fetches the active tab when any shared-state axis changes, not only the period', async () => {
    await render();
    ordersSpy.mockClear();

    TestBed.inject(ReportsFilterState).setFulfilmentType('DINE_IN');
    fixture.detectChanges();
    await flushMicrotasks();

    expect(ordersSpy).toHaveBeenCalled();
  });

  it('tracks maybeMore on «Этапы» instead of always discarding it', async () => {
    await render({ ordersMock: vi.fn().mockResolvedValue(ordersResponse([orderRow()], true)) });

    const host = fixture.nativeElement as HTMLElement;
    expect(host.textContent).toContain('Showing the worst rows up to this view');
  });

  it('sets a keyset cursor after loading «Заказы» and pages past it on "Load more"', async () => {
    const first = orderRow({
      orderId: 'order-a',
      occurredAt: '2026-09-10T10:00:00Z',
      publicOrderNumber: '0001',
    });
    const second = orderRow({
      orderId: 'order-b',
      occurredAt: '2026-09-09T10:00:00Z',
      publicOrderNumber: '0002',
    });
    const ordersMock = vi
      .fn()
      .mockResolvedValueOnce(ordersResponse([first], true))
      .mockResolvedValueOnce(ordersResponse([second], false));

    await render({ ordersMock, initialTab: 'commercial' });

    expect(ordersMock).toHaveBeenCalledWith(
      SCOPE.tenantId,
      expect.objectContaining({ sort: 'DATE_DESC' }),
    );

    await internals().loadMoreCommercial();
    fixture.detectChanges();

    expect(ordersMock).toHaveBeenLastCalledWith(
      SCOPE.tenantId,
      expect.objectContaining({ afterOccurredAt: '2026-09-10T10:00:00Z', afterOrderId: 'order-a' }),
    );
    // Both pages are now on screen — the second fetch appended, it did not replace.
    const host = fixture.nativeElement as HTMLElement;
    expect(host.textContent).toContain('0001');
    expect(host.textContent).toContain('0002');
  });

  it('always groups the daily/summary money queries by LEGAL_ENTITY (ADR 0038)', async () => {
    await render({ initialTab: 'daily' });

    const moneyCall = querySpy.mock.calls.find((call: unknown[]) =>
      (call[1] as QueryParams).metric.includes('revenue.gross.v1'),
    );
    expect(moneyCall).toBeDefined();
    expect((moneyCall![1] as QueryParams).groupBy).toContain('LEGAL_ENTITY');
  });

  // ----------------------------------------------------------- wave 8 w7-reports (7.2c): «Сводка»

  const LOCATIONS: readonly LocationView[] = [
    {
      id: 'loc-1',
      tenantId: 't1',
      brandId: 'b1',
      code: 'chilanzar',
      slug: 'chilanzar',
      displayName: 'Chilanzar',
      timezone: 'Asia/Tashkent',
      status: 'ACTIVE',
      addressLine: null,
      district: null,
      city: null,
      landmark: null,
      contactPhone: null,
      latitude: null,
      longitude: null,
      coordinateSource: 'NOT_GEOCODED',
    },
  ];
  const CHANNELS: readonly ChannelView[] = [
    {
      id: 'ch-1',
      code: 'TELEGRAM',
      systemType: 'BOT',
      displayName: 'Telegram',
      status: 'ACTIVE',
      pricePlaneChannelId: null,
      externallyPriced: false,
      guestOrdersAllowed: true,
      providerInstallationId: null,
      version: 1,
      locationCount: 1,
      enabledPaymentMethodCount: 1,
      enabledFulfillmentModes: ['DELIVERY'],
    },
  ];

  it('«Сводка 1» requests delivery_fee.v1 alongside gross/net, and shows the fee-exclusive column reconciling against it', async () => {
    const queryMock = vi.fn().mockResolvedValue({
      rows: [
        summaryRow({
          locationId: 'loc-1',
          channelCode: 'TELEGRAM',
          fulfilmentType: 'DELIVERY',
          legalEntityId: 'entity-a',
          grossSom: 1_000_000,
          netSom: 900_000,
          deliveryFeeSom: 150_000,
          orderCount: 10,
        }),
      ],
      provenance: provenance(),
    } satisfies QueryResponse);

    await render({ queryMock, initialTab: 'summary', locations: LOCATIONS, channels: CHANNELS });

    const summaryCall = querySpy.mock.calls.find((call: unknown[]) =>
      (call[1] as QueryParams).metric.includes('delivery_fee.v1'),
    );
    expect(summaryCall).toBeDefined();

    const host = fixture.nativeElement as HTMLElement;
    const report1 = host.querySelector('.report1-table') as HTMLElement;
    // Сумма (excl. delivery) = 1 000 000 − 150 000 = 850 000.
    expect(report1.textContent).toMatch(/850[\s ]000/);
    // Сумма с учётом доставки = revenue.gross.v1 itself = 1 000 000.
    expect(report1.textContent).toMatch(/1[\s ]000[\s ]000/);
    // Итого = revenue.net.v1 = 900 000.
    expect(report1.textContent).toMatch(/900[\s ]000/);
  });

  it('«Сводка 2» renders the true branch×channel grid, not the retired flat (branch, channel) list', async () => {
    const queryMock = vi.fn().mockResolvedValue({
      rows: [
        summaryRow({
          locationId: 'loc-1',
          channelCode: 'TELEGRAM',
          fulfilmentType: 'DELIVERY',
          legalEntityId: 'entity-a',
          grossSom: 500_000,
          netSom: 480_000,
          deliveryFeeSom: 50_000,
          orderCount: 5,
        }),
      ],
      provenance: provenance(),
    } satisfies QueryResponse);

    await render({ queryMock, initialTab: 'summary', locations: LOCATIONS, channels: CHANNELS });

    const host = fixture.nativeElement as HTMLElement;
    const grid = host.querySelector('[data-testid="summary-grid"]') as HTMLElement;
    expect(grid).not.toBeNull();
    // A real grid: the channel name is a COLUMN header, not a second row cell.
    const headerRow = grid.querySelector('thead tr') as HTMLElement;
    expect(headerRow.textContent).toContain('Telegram');
    const bodyRow = grid.querySelector('tbody tr') as HTMLElement;
    expect(bodyRow.textContent).toContain('Chilanzar');
    expect(bodyRow.textContent).toMatch(/500[\s ]000/);
  });

  it(
    'folds a two-legal-entity tenant’s rows into one correct grid cell and one correct «Сводка 1» ' +
      'row, never throwing and never combining the entities into a stored total (ADR 0038)',
    async () => {
      const queryMock = vi.fn().mockResolvedValue({
        rows: [
          summaryRow({
            locationId: 'loc-1',
            channelCode: 'TELEGRAM',
            fulfilmentType: 'DELIVERY',
            legalEntityId: 'entity-a',
            grossSom: 700_000,
            netSom: 650_000,
            deliveryFeeSom: 100_000,
            orderCount: 7,
          }),
          summaryRow({
            locationId: 'loc-1',
            channelCode: 'TELEGRAM',
            fulfilmentType: 'DELIVERY',
            legalEntityId: 'entity-b',
            grossSom: 300_000,
            netSom: 280_000,
            deliveryFeeSom: 40_000,
            orderCount: 3,
          }),
        ],
        provenance: provenance(),
      } satisfies QueryResponse);

      await render({ queryMock, initialTab: 'summary', locations: LOCATIONS, channels: CHANNELS });

      // The request itself never combines entities server-side — it always
      // names LEGAL_ENTITY, the only thing that keeps the two rows above
      // legal (ADR 0038) rather than one refused CombinedEntityTotalException.
      const summaryCall = querySpy.mock.calls.find((call: unknown[]) =>
        (call[1] as QueryParams).metric.includes('delivery_fee.v1'),
      );
      expect((summaryCall![1] as QueryParams).groupBy).toContain('LEGAL_ENTITY');

      const host = fixture.nativeElement as HTMLElement;

      // The grid folds both entities' rows into the one (branch, channel)
      // cell that both share — 700 000 + 300 000 = 1 000 000 — client-side,
      // the same transparent fold ADR 0038 allows (never a server total).
      const grid = host.querySelector('[data-testid="summary-grid"]') as HTMLElement;
      const bodyRow = grid.querySelector('tbody tr') as HTMLElement;
      expect(bodyRow.textContent).toMatch(/1[\s ]000[\s ]000/);

      // «Сводка 1»'s DELIVERY row reconciles the same way: 10 orders,
      // 860 000 fee-exclusive (1 000 000 gross − 140 000 fee), 930 000 net.
      const report1 = host.querySelector('.report1-table') as HTMLElement;
      expect(report1.textContent).toContain('10');
      expect(report1.textContent).toMatch(/860[\s ]000/);
      expect(report1.textContent).toMatch(/930[\s ]000/);
    },
  );
});

import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { LocationsApi } from '../settings/locations/locations-api';
import { SalesChannelsApi } from '../settings/sales-channels/sales-channels-api';
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
        { provide: LocationsApi, useValue: { list: vi.fn().mockResolvedValue([]) } },
        { provide: SalesChannelsApi, useValue: { list: vi.fn().mockResolvedValue([]) } },
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
});

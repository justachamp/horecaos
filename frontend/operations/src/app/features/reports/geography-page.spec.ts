import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { LocationsApi } from '../settings/locations/locations-api';
import { GeographyPage } from './geography-page';
import {
  BucketResponse,
  DemandHistoryResponse,
  HourDemandResponse,
  OrderListResponse,
  OrderRowResponse,
  ReportingApi,
  SlaResponse,
} from './reporting-api';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function provenance() {
  return {
    asOf: '2026-09-15T04:00:00Z',
    closedThrough: '2026-09-14',
    lastCloseCompletedAt: '2026-09-14T22:00:00Z',
    businessDayStart: '00:00:00',
    timezone: 'Asia/Tashkent',
    boundaryVersion: 1,
    metricVersions: [],
    provisionalMetrics: [],
    openDivergences: 0,
  };
}

function hours(
  byHour: Readonly<Record<number, Partial<Omit<HourDemandResponse, 'hourOfDay'>>>> = {},
): HourDemandResponse[] {
  return Array.from({ length: 24 }, (_unused, hourOfDay) => ({
    hourOfDay,
    ordersByDate: {},
    totalOrders: 0,
    averageOrders: null,
    ...byHour[hourOfDay],
  }));
}

/** Monday (weekday 1) carries real history at hour 18; every other weekday is a thin/empty sample. */
function demandResponse(weekday: number): DemandHistoryResponse {
  const isMonday = weekday === 1;
  return {
    locationId: 'l1',
    weekday,
    requestedSampleSize: 4,
    minimumSampleSize: 3,
    sampleDates: isMonday ? ['2026-08-25', '2026-08-18'] : [],
    holidayDates: [],
    holidayMode: 'INCLUDE',
    hours: hours(
      isMonday
        ? {
            18: {
              ordersByDate: { '2026-08-25': 5, '2026-08-18': 3 },
              totalOrders: 8,
              averageOrders: 4,
            },
          }
        : {},
    ),
    provenance: provenance(),
  };
}

function slaBuckets(overrides: Partial<SlaResponse> = {}): SlaResponse {
  const buckets: BucketResponse[] = [
    {
      businessDate: '2026-09-14',
      locationId: 'l1',
      bucketCode: 'UNDER_30',
      orderCount: 10,
      shareBasisPoints: 5000,
    },
    {
      businessDate: '2026-09-14',
      locationId: 'l1',
      bucketCode: 'OVER_60',
      orderCount: 2,
      shareBasisPoints: 1000,
    },
  ];
  return { buckets, medians: [], provenance: provenance(), ...overrides };
}

function orderRow(overrides: Partial<OrderRowResponse> = {}): OrderRowResponse {
  return {
    orderId: 'o1',
    businessDate: '2026-08-25',
    locationId: 'l1',
    legalEntityId: null,
    channelCode: 'HALL',
    fulfilmentType: 'DELIVERY',
    terminalStatus: 'COMPLETED',
    grossRevenueSom: 50000,
    discountSom: 0,
    deliveryFeeSom: 5000,
    taxSom: 0,
    netRevenueSom: 45000,
    itemCount: 2,
    occurredAt: '2026-08-25T13:15:00Z',
    closedAt: '2026-08-25T13:45:00Z',
    secondsToConfirm: 60,
    secondsToReady: 600,
    secondsTotal: 1800,
    secondsLate: null,
    cancellationReasonCode: null,
    secondsToAccept: 30,
    secondsPreparing: 500,
    publicOrderNumber: '1001',
    isPreorder: false,
    ...overrides,
  };
}

function orderList(rows: readonly OrderRowResponse[], maybeMore = false): OrderListResponse {
  return { rows, maybeMore, provenance: provenance() };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('GeographyPage', () => {
  let fixture: ComponentFixture<GeographyPage>;

  async function render(
    api: Partial<ReportingApi>,
    options: {
      readonly scope?: LocationScope | null;
      readonly denied?: boolean;
      readonly locations?: readonly { id: string; displayName: string }[];
    } = {},
  ): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [GeographyPage],
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(
              'scope' in options ? (options.scope ?? null) : SCOPE,
            ),
            denied: signal(options.denied ?? false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        {
          provide: LocationsApi,
          useValue: { list: vi.fn().mockResolvedValue(options.locations ?? []) },
        },
        {
          provide: ReportingApi,
          useValue: {
            slaBuckets: vi.fn().mockResolvedValue(slaBuckets()),
            demandHistory: vi
              .fn()
              .mockImplementation((_tenantId: string, params: { weekday: number }) =>
                Promise.resolve(demandResponse(params.weekday)),
              ),
            orders: vi.fn().mockResolvedValue(orderList([])),
            ...api,
          },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(GeographyPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('shows the denied state when the location grant is missing', async () => {
    await render({}, { scope: null, denied: true });

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="geography-denied"]'),
    ).not.toBeNull();
  });

  it('names the deferred map rows rather than staying silent about them', async () => {
    await render({});

    expect(
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="geography-map-deferred"]',
      ),
    ).not.toBeNull();
  });

  describe('7.10b the histograms', () => {
    it('renders the duration histogram against the existing SLA buckets, scoped to the selected branch', async () => {
      const slaBucketsFn = vi.fn().mockResolvedValue(slaBuckets());
      await render({ slaBuckets: slaBucketsFn });

      expect(slaBucketsFn).toHaveBeenCalledWith(
        't1',
        expect.objectContaining({ locationId: ['l1'] }),
      );
      const table = (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="geography-duration-histogram"] [data-testid="q-histogram-chart-table"]',
      ) as HTMLElement;
      expect(table).not.toBeNull();
      expect(table.textContent).toContain('10');
    });

    it('names the distance histogram as not yet available rather than rendering nothing — the fact exists, the bucket read does not (widens when T11 adds one)', async () => {
      await render({});

      const host = fixture.nativeElement as HTMLElement;
      const note = host.querySelector('[data-testid="geography-distance-unavailable"]');
      expect(note).not.toBeNull();
      expect(note?.textContent).toContain('fact_delivery');
      // And the duration chart really is there beside it — this is a partial
      // widen, not a screen that gave up on both histograms.
      expect(host.querySelector('[data-testid="geography-duration-histogram"]')).not.toBeNull();
    });

    it('surfaces a load failure and retries on request', async () => {
      const slaBucketsFn = vi
        .fn()
        .mockRejectedValueOnce(new ApiError('INTERNAL', 500, null, 'corr-1'))
        .mockResolvedValueOnce(slaBuckets());
      await render({ slaBuckets: slaBucketsFn });

      const host = fixture.nativeElement as HTMLElement;
      expect(host.querySelector('[data-testid="geography-histogram-retry"]')).not.toBeNull();

      host.querySelector<HTMLButtonElement>('[data-testid="geography-histogram-retry"]')?.click();
      await flushMicrotasks();
      fixture.detectChanges();

      expect(slaBucketsFn).toHaveBeenCalledTimes(2);
      expect(host.querySelector('[data-testid="geography-duration-histogram"]')).not.toBeNull();
    });
  });

  describe('7.10c the cohort grid', () => {
    it('fetches all seven weekdays on load — no opt-in click, unlike the forecast week overview', async () => {
      const demandHistory = vi
        .fn()
        .mockImplementation((_tenantId: string, params: { weekday: number }) =>
          Promise.resolve(demandResponse(params.weekday)),
        );
      await render({ demandHistory });

      expect(demandHistory).toHaveBeenCalledTimes(7);
      expect(
        (fixture.nativeElement as HTMLElement).querySelector('[data-testid="geography-week-grid"]'),
      ).not.toBeNull();
    });

    it('reloads all seven weekdays under a newly selected sample size', async () => {
      const demandHistory = vi
        .fn()
        .mockImplementation((_tenantId: string, params: { weekday: number }) =>
          Promise.resolve(demandResponse(params.weekday)),
        );
      await render({ demandHistory });
      demandHistory.mockClear();

      const select = (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>(
        '[data-testid="geography-sample-size"]',
      )!;
      select.value = '8';
      select.dispatchEvent(new Event('change'));
      await flushMicrotasks();
      fixture.detectChanges();

      expect(demandHistory).toHaveBeenCalledTimes(7);
      expect(demandHistory).toHaveBeenCalledWith('t1', expect.objectContaining({ sampleSize: 8 }));
    });

    it('clicking a cell drills down through /reporting/orders, capped at 300 rows and filtered to that cell', async () => {
      const matching = orderRow({
        orderId: 'o-match',
        publicOrderNumber: 'MATCH01',
        occurredAt: '2026-08-25T13:15:00Z',
      });
      // Same business date, wrong hour (Tashkent local 10:00, not 18:00) — must be filtered out.
      const wrongHour = orderRow({
        orderId: 'o-wrong-hour',
        publicOrderNumber: 'WRONG01',
        occurredAt: '2026-08-25T05:00:00Z',
      });
      const orders = vi.fn().mockResolvedValue(orderList([matching, wrongHour]));
      await render({ orders });

      const host = fixture.nativeElement as HTMLElement;
      // Monday is the grid's first row; hour 18 is the 19th cell in that row (0-indexed 18).
      const cell = host.querySelectorAll('.q-chart__cell')[18];
      cell.dispatchEvent(new Event('click'));
      await flushMicrotasks();
      fixture.detectChanges();

      expect(orders).toHaveBeenCalledWith('t1', {
        from: '2026-08-18',
        to: '2026-08-25',
        locationId: ['l1'],
        sort: 'DATE_DESC',
        limit: 300,
      });

      const panel = host.querySelector('[data-testid="geography-drilldown"]') as HTMLElement;
      expect(panel).not.toBeNull();
      expect(panel.textContent).toContain('MATCH01');
      expect(panel.textContent).not.toContain('WRONG01');
    });

    it('names the drill-down as possibly incomplete when the underlying 300-row read came back full', async () => {
      const matching = orderRow({ orderId: 'o-match', occurredAt: '2026-08-25T13:15:00Z' });
      const orders = vi.fn().mockResolvedValue(orderList([matching], true));
      await render({ orders });

      const host = fixture.nativeElement as HTMLElement;
      const cell = host.querySelectorAll('.q-chart__cell')[18];
      cell.dispatchEvent(new Event('click'));
      await flushMicrotasks();
      fixture.detectChanges();

      expect(host.querySelector('[data-testid="geography-drilldown-truncated"]')).not.toBeNull();
    });

    it('closes the drill-down panel on request', async () => {
      const matching = orderRow({ orderId: 'o-match', occurredAt: '2026-08-25T13:15:00Z' });
      const orders = vi.fn().mockResolvedValue(orderList([matching]));
      await render({ orders });

      const host = fixture.nativeElement as HTMLElement;
      host.querySelectorAll('.q-chart__cell')[18].dispatchEvent(new Event('click'));
      await flushMicrotasks();
      fixture.detectChanges();
      expect(host.querySelector('[data-testid="geography-drilldown"]')).not.toBeNull();

      host.querySelector<HTMLButtonElement>('[data-testid="geography-drilldown-close"]')?.click();
      fixture.detectChanges();
      expect(host.querySelector('[data-testid="geography-drilldown"]')).toBeNull();
    });
  });

  describe('the branch selector', () => {
    it('is hidden for a single-location tenant', async () => {
      await render({}, { locations: [] });

      expect(
        (fixture.nativeElement as HTMLElement).querySelector('[data-testid="geography-branch"]'),
      ).toBeNull();
    });

    it('reloads both sections for the newly selected branch', async () => {
      const slaBucketsFn = vi.fn().mockResolvedValue(slaBuckets());
      const demandHistory = vi
        .fn()
        .mockImplementation((_tenantId: string, params: { weekday: number }) =>
          Promise.resolve(demandResponse(params.weekday)),
        );
      await render(
        { slaBuckets: slaBucketsFn, demandHistory },
        {
          locations: [
            { id: 'l1', displayName: 'Chilonzor' },
            { id: 'l2', displayName: 'Yunusobod' },
          ],
        },
      );
      slaBucketsFn.mockClear();
      demandHistory.mockClear();

      const select = (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>(
        '[data-testid="geography-branch"]',
      )!;
      select.value = 'l2';
      select.dispatchEvent(new Event('change'));
      await flushMicrotasks();
      fixture.detectChanges();

      expect(slaBucketsFn).toHaveBeenCalledWith(
        't1',
        expect.objectContaining({ locationId: ['l2'] }),
      );
      expect(demandHistory).toHaveBeenCalledWith(
        't1',
        expect.objectContaining({ locationId: 'l2' }),
      );
    });
  });
});

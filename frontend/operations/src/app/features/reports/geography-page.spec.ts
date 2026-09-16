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

function bucketRow(locationId: string, orderCount: number): BucketResponse {
  return {
    businessDate: '2026-09-14',
    locationId,
    bucketCode: 'UNDER_30',
    orderCount,
    shareBasisPoints: 10000,
  };
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

    it('clicking a cell drills down through one bounded /reporting/orders call per sampled date, filtered to that cell', async () => {
      const matching = orderRow({
        orderId: 'o-match',
        publicOrderNumber: 'MATCH01',
        businessDate: '2026-08-25',
        occurredAt: '2026-08-25T13:15:00Z',
      });
      // Same business date, wrong hour (Tashkent local 10:00, not 18:00) — must be filtered out.
      const wrongHour = orderRow({
        orderId: 'o-wrong-hour',
        publicOrderNumber: 'WRONG01',
        businessDate: '2026-08-25',
        occurredAt: '2026-08-25T05:00:00Z',
      });
      // The *older* of the two sampled Mondays (demandResponse's own
      // sampleDates: ['2026-08-25', '2026-08-18']) — a single spanning
      // from/to read capped at 300 rows would have this crowded out by
      // whatever the branch did most recently across every weekday; a
      // per-date call cannot lose it.
      const olderMatch = orderRow({
        orderId: 'o-older-match',
        publicOrderNumber: 'OLDER01',
        businessDate: '2026-08-18',
        occurredAt: '2026-08-18T13:15:00Z',
      });
      const orders = vi
        .fn()
        .mockImplementation((_tenantId: string, params: { readonly from: string }) =>
          Promise.resolve(
            params.from === '2026-08-25'
              ? orderList([matching, wrongHour])
              : orderList([olderMatch]),
          ),
        );
      await render({ orders });

      const host = fixture.nativeElement as HTMLElement;
      // Monday is the grid's first row; hour 18 is the 19th cell in that row (0-indexed 18).
      const cell = host.querySelectorAll('.q-chart__cell')[18];
      cell.dispatchEvent(new Event('click'));
      await flushMicrotasks();
      fixture.detectChanges();

      expect(orders).toHaveBeenCalledTimes(2);
      expect(orders).toHaveBeenCalledWith('t1', {
        from: '2026-08-25',
        to: '2026-08-25',
        locationId: ['l1'],
        sort: 'DATE_DESC',
        limit: 100,
      });
      expect(orders).toHaveBeenCalledWith('t1', {
        from: '2026-08-18',
        to: '2026-08-18',
        locationId: ['l1'],
        sort: 'DATE_DESC',
        limit: 100,
      });

      const panel = host.querySelector('[data-testid="geography-drilldown"]') as HTMLElement;
      expect(panel).not.toBeNull();
      expect(panel.textContent).toContain('MATCH01');
      expect(panel.textContent).toContain('OLDER01');
      expect(panel.textContent).not.toContain('WRONG01');
    });

    it('names the drill-down as possibly incomplete when a sampled date’s own read came back full', async () => {
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

    it('computes the cohort grid and its drill-down relative to a non-midnight businessDayStart, not the wall-clock hour', async () => {
      const nonMidnightProvenance = { ...provenance(), businessDayStart: '22:00:00' };
      const demandHistory = vi
        .fn()
        .mockImplementation((_tenantId: string, params: { weekday: number }) => {
          if (params.weekday !== 1) {
            return Promise.resolve({
              locationId: 'l1',
              weekday: params.weekday,
              requestedSampleSize: 4,
              minimumSampleSize: 3,
              sampleDates: [],
              holidayDates: [],
              holidayMode: 'INCLUDE',
              hours: hours(),
              provenance: nonMidnightProvenance,
            });
          }
          return Promise.resolve({
            locationId: 'l1',
            weekday: 1,
            requestedSampleSize: 4,
            minimumSampleSize: 3,
            // A 22:00 business day starting 2026-08-24 runs into the small
            // hours of 2026-08-25 local time — still business date 08-24.
            sampleDates: ['2026-08-24'],
            holidayDates: [],
            holidayMode: 'INCLUDE',
            // Operating hour 2 — wall-clock 00:00-01:00, two hours into a
            // business day that started the evening before at 22:00.
            hours: hours({
              2: { ordersByDate: { '2026-08-24': 3 }, totalOrders: 3, averageOrders: 3 },
            }),
            provenance: nonMidnightProvenance,
          });
        });
      // occurredAt just after local midnight — still inside business date
      // 2026-08-24's operating day, which started the evening before.
      const crossingMidnight = orderRow({
        orderId: 'o-crossing',
        publicOrderNumber: 'CROSS01',
        businessDate: '2026-08-24',
        occurredAt: '2026-08-24T19:30:00Z', // 2026-08-25T00:30 Asia/Tashkent
      });
      const orders = vi.fn().mockResolvedValue(orderList([crossingMidnight]));
      await render({ demandHistory, orders });

      const host = fixture.nativeElement as HTMLElement;
      // Monday is the grid's first row; operating hour 2 is the 3rd cell (0-indexed 2).
      const cell = host.querySelectorAll('.q-chart__cell')[2];
      cell.dispatchEvent(new Event('click'));
      await flushMicrotasks();
      fixture.detectChanges();

      expect(orders).toHaveBeenCalledWith('t1', {
        from: '2026-08-24',
        to: '2026-08-24',
        locationId: ['l1'],
        sort: 'DATE_DESC',
        limit: 100,
      });

      const panel = host.querySelector('[data-testid="geography-drilldown"]') as HTMLElement;
      expect(panel).not.toBeNull();
      // The panel title interpolates hourWindowLabel, and the match below
      // depends on operatingHourOf: a regression that dropped either
      // function's businessDayStart offset (defaulting to a naive
      // wall-clock window/hour) would show/match 02:00-03:00 here instead
      // of the true 22:00-relative window — every other test in this file
      // leaves businessDayStart at '00:00:00', where the two computations
      // coincide and would not catch that regression.
      expect(panel.textContent).toContain('00:00–01:00');
      expect(panel.textContent).not.toContain('02:00–03:00');
      expect(panel.textContent).toContain('CROSS01');
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

    it('discards a slower stale-branch histogram response once a newer branch selection has superseded it', async () => {
      const resolvers = new Map<string, (value: SlaResponse) => void>();
      const slaBucketsFn = vi.fn().mockImplementation(
        (_tenantId: string, params: { readonly locationId: readonly string[] }) =>
          new Promise<SlaResponse>((resolve) => {
            resolvers.set(params.locationId[0], resolve);
          }),
      );
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
      // ngOnInit's own initial load (branch l1, the default location) is in
      // flight, unresolved.
      expect(resolvers.has('l1')).toBe(true);

      const host = fixture.nativeElement as HTMLElement;
      const select = host.querySelector<HTMLSelectElement>('[data-testid="geography-branch"]')!;
      select.value = 'l2';
      select.dispatchEvent(new Event('change'));
      await flushMicrotasks();
      fixture.detectChanges();
      expect(resolvers.has('l2')).toBe(true);

      const table = () =>
        host.querySelector(
          '[data-testid="geography-duration-histogram"] [data-testid="q-histogram-chart-table"]',
        ) as HTMLElement | null;

      // Branch B (l2), selected second, resolves first — the realistic case
      // this finding is about.
      resolvers.get('l2')!(slaBuckets({ buckets: [bucketRow('l2', 42)] }));
      await flushMicrotasks();
      fixture.detectChanges();
      expect(table()?.textContent).toContain('42');

      // Branch A's (l1) slower, now-stale response resolves after — it must
      // not overwrite branch B's data on screen, even though the <select>
      // has shown l2 selected the whole time.
      resolvers.get('l1')!(slaBuckets({ buckets: [bucketRow('l1', 999)] }));
      await flushMicrotasks();
      fixture.detectChanges();

      expect(select.value).toBe('l2');
      expect(table()?.textContent).toContain('42');
      expect(table()?.textContent).not.toContain('999');
    });
  });
});

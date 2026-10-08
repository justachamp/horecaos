import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { OrderMapPointsApi } from '../../core/api/order-map-points-api';
import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { NullMapProvider, provideNullMapProvider } from '../../shared/ui/map/null-map-provider';
import {
  DeliveryZonesApi,
  ZoneOutlineResponse,
  ZoneSummaryResponse,
} from '../delivery/delivery-zones-api';
import { MapRegionService } from '../delivery/map-region';
import { LocationsApi } from '../settings/locations/locations-api';
import { GeographyPage } from './geography-page';
import {
  BucketResponse,
  DemandHistoryResponse,
  DistanceBucketResponse,
  DistanceBucketSetResponse,
  DistanceBucketsResponse,
  HourDemandResponse,
  OrderListResponse,
  OrderRowResponse,
  ReportingApi,
  SlaResponse,
  ZoneDensityResponse,
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

/** Row 7.10b: the distance histogram's own fixture — mirrors `slaBuckets()`'s shape. */
function distanceBucketsResponse(
  overrides: Partial<DistanceBucketsResponse> = {},
): DistanceBucketsResponse {
  const buckets: DistanceBucketResponse[] = [
    { bucketCode: 'UNDER_1KM', deliveryCount: 6 },
    { bucketCode: 'KM1_2', deliveryCount: 0 },
    { bucketCode: 'KM2_3', deliveryCount: 0 },
    { bucketCode: 'KM3_5', deliveryCount: 0 },
    { bucketCode: 'KM5_8', deliveryCount: 0 },
    { bucketCode: 'OVER_8KM', deliveryCount: 1 },
  ];
  return { buckets, provenance: provenance(), ...overrides };
}

function distanceBucketSetResponse(): DistanceBucketSetResponse {
  return {
    version: 1,
    buckets: [
      { code: 'UNDER_1KM', fromMeters: 0, toMetersExclusive: 1_000 },
      { code: 'KM1_2', fromMeters: 1_000, toMetersExclusive: 2_000 },
      { code: 'KM2_3', fromMeters: 2_000, toMetersExclusive: 3_000 },
      { code: 'KM3_5', fromMeters: 3_000, toMetersExclusive: 5_000 },
      { code: 'KM5_8', fromMeters: 5_000, toMetersExclusive: 8_000 },
      { code: 'OVER_8KM', fromMeters: 8_000, toMetersExclusive: null },
    ],
  };
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

// ---------------------------------------------------------------- 7.10 / 7.10a fixtures

function zoneSummary(zoneId: string, code: string, nameEn: string): ZoneSummaryResponse {
  return {
    zoneId,
    role: 'DELIVERY',
    code,
    displayNameRu: nameEn,
    displayNameUz: nameEn,
    displayNameEn: nameEn,
    status: 'ACTIVE',
    activeVersion: 1,
  };
}

function outline(zoneId: string, code: string, latitude: number): ZoneOutlineResponse {
  return {
    zoneId,
    code,
    role: 'DELIVERY',
    version: 1,
    status: 'ACTIVE',
    shapeKind: 'POLYGON',
    polygons: [
      {
        ring: [
          { latitude, longitude: 69.2 },
          { latitude, longitude: 69.3 },
          { latitude: latitude + 0.05, longitude: 69.25 },
        ],
        holes: [],
      },
    ],
  };
}

const ZONES = [
  zoneSummary('z-centre', 'CENTRE', 'City centre'),
  zoneSummary('z-ring', 'RING', 'Ring road'),
  zoneSummary('z-quiet', 'QUIET', 'Quiet quarter'),
];
const OUTLINES = [
  outline('z-centre', 'CENTRE', 41.3),
  outline('z-ring', 'RING', 41.2),
  outline('z-quiet', 'QUIET', 41.4),
];

function density(overrides: Partial<ZoneDensityResponse> = {}): ZoneDensityResponse {
  return {
    zones: [
      { zoneId: 'z-centre', deliveryCount: 40, totalFeeMinor: 480_000, currency: 'UZS' },
      { zoneId: 'z-ring', deliveryCount: 10, totalFeeMinor: 200_000, currency: 'UZS' },
      { zoneId: null, deliveryCount: 5, totalFeeMinor: 100_000, currency: 'UZS' },
    ],
    provenance: provenance(),
    ...overrides,
  };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('GeographyPage', () => {
  let fixture: ComponentFixture<GeographyPage>;
  let mapProvider: NullMapProvider;
  let zonesApi: {
    list: ReturnType<typeof vi.fn>;
    activeOutlines: ReturnType<typeof vi.fn>;
  };
  let orderPoints: { reveal: ReturnType<typeof vi.fn> };

  async function render(
    api: Partial<ReportingApi>,
    options: {
      readonly scope?: LocationScope | null;
      readonly denied?: boolean;
      readonly locations?: readonly { id: string; displayName: string }[];
      /** `false`: the caller may not read delivery zones, which is a separate capability from reports. */
      readonly zonesReadable?: boolean;
      readonly outlines?: readonly ZoneOutlineResponse[];
    } = {},
  ): Promise<void> {
    mapProvider = new NullMapProvider();
    const refused = new ApiError('INSUFFICIENT_CAPABILITY', 403, null, null);
    zonesApi =
      options.zonesReadable === false
        ? {
            list: vi.fn().mockRejectedValue(refused),
            activeOutlines: vi.fn().mockRejectedValue(refused),
          }
        : {
            list: vi.fn().mockResolvedValue(ZONES),
            activeOutlines: vi.fn().mockResolvedValue(options.outlines ?? OUTLINES),
          };
    orderPoints = {
      reveal: vi.fn().mockResolvedValue({
        windowFrom: '2026-10-07T00:00:00Z',
        windowTo: '2026-10-08T00:00:00Z',
        points: [
          {
            orderId: 'o1',
            publicOrderNumber: 'F-1',
            status: 'CONFIRMED',
            createdAt: '2026-10-07T08:00:00Z',
            latitude: 41.31,
            longitude: 69.24,
          },
        ],
        withoutPoint: 0,
        truncated: false,
      }),
    };
    await TestBed.configureTestingModule({
      imports: [GeographyPage],
      providers: [
        provideNullMapProvider(mapProvider),
        { provide: DeliveryZonesApi, useValue: zonesApi },
        { provide: OrderMapPointsApi, useValue: orderPoints },
        {
          provide: MapRegionService,
          useValue: { ensureLoaded: () => Promise.resolve(), primary: () => null },
        },
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
            distanceBuckets: vi.fn().mockResolvedValue(distanceBucketsResponse()),
            distanceBucketSet: vi.fn().mockResolvedValue(distanceBucketSetResponse()),
            demandHistory: vi
              .fn()
              .mockImplementation((_tenantId: string, params: { weekday: number }) =>
                Promise.resolve(demandResponse(params.weekday)),
              ),
            orders: vi.fn().mockResolvedValue(orderList([])),
            zoneDensity: vi.fn().mockResolvedValue(density()),
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

  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const settle = async (): Promise<void> => {
    await flushMicrotasks();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  };

  describe('7.10 order density over the delivery zones (ADR 0145)', () => {
    it('reads deliveries per zone for the selected branch over the recent window', async () => {
      const zoneDensity = vi.fn().mockResolvedValue(density());
      await render({ zoneDensity });
      await settle();

      expect(zoneDensity).toHaveBeenCalledWith(
        't1',
        expect.objectContaining({
          locationId: ['l1'],
          from: expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/),
          to: expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/),
        }),
      );
      expect(host().querySelector('[data-testid="geography-map-deferred"]')).toBeNull();
    });

    it('shades each zone by its share of the busiest one, and keeps a zone nobody ordered from', async () => {
      await render({});
      await settle();

      const areas = mapProvider.map.livePolygons;
      expect(areas).toHaveLength(3);
      const byLabel = new Map(areas.map((area) => [area.label, area.intensity]));
      expect(byLabel.get('City centre: 40 deliveries')).toBe(1);
      expect(byLabel.get('Ring road: 10 deliveries')).toBeCloseTo(0.25, 5);
      expect(byLabel.get('Quiet quarter: 0 deliveries')).toBe(0);
      expect(areas.every((area) => !area.editable)).toBe(true);
    });

    it('lists the zones busiest first and the deliveries no zone covered as a row of their own, last', async () => {
      await render({});
      await settle();

      const rows = [...host().querySelectorAll('[data-testid="zone-density-row"]')];
      expect(rows.map((row) => row.querySelector('td')?.textContent?.trim())).toEqual([
        'City centre',
        'Ring road',
        'Quiet quarter',
        'Outside every drawn zone',
      ]);
      expect(rows[0].textContent).toContain('40');
      expect(rows[0].textContent).toContain('72.7%');
      expect(rows[3].textContent).toContain('5');
      expect(host().querySelector('[data-testid="zone-density-outside"]')?.textContent).toContain(
        '5',
      );
    });

    it('writes the fees as the brand writes money, per currency, never added across currencies', async () => {
      await render({
        zoneDensity: vi.fn().mockResolvedValue(
          density({
            zones: [
              { zoneId: 'z-centre', deliveryCount: 3, totalFeeMinor: 30_000, currency: 'UZS' },
              { zoneId: 'z-centre', deliveryCount: 1, totalFeeMinor: 500, currency: 'USD' },
            ],
          }),
        ),
      });
      await settle();

      const centre = host().querySelector('[data-testid="zone-density-row"]')!;
      expect(centre.textContent).toContain('4');
      expect(centre.textContent).toMatch(/UZS|сўм|so/i);
      expect(centre.textContent).toContain('·');
    });

    it('says what is counted, so it is not mistaken for every order', async () => {
      await render({});
      await settle();

      expect(host().querySelector('[data-testid="zone-density-basis"]')?.textContent).toContain(
        'tariff',
      );
    });

    it('still gives the counts, and says why there are no names or map, when zones cannot be read', async () => {
      await render({}, { zonesReadable: false });
      await settle();

      const rows = [...host().querySelectorAll('[data-testid="zone-density-row"]')];
      expect(rows).toHaveLength(3);
      expect(rows[0].textContent).toContain('40');
      expect(host().querySelector('[data-testid="zone-density-no-zones"]')).not.toBeNull();
      expect(mapProvider.maps).toHaveLength(0);
    });

    it('keeps a zone nobody ordered from in the table, at zero, which is what the view is for finding', async () => {
      await render({ zoneDensity: vi.fn().mockResolvedValue(density({ zones: [] })) });
      await settle();

      const rows = [...host().querySelectorAll('[data-testid="zone-density-row"]')];
      expect(rows).toHaveLength(3);
      expect(rows.every((row) => row.textContent?.includes('0'))).toBe(true);
    });

    it('answers a range with no deliveries and no zones with a sentence, not an empty table', async () => {
      await render(
        { zoneDensity: vi.fn().mockResolvedValue(density({ zones: [] })) },
        { outlines: [], zonesReadable: true },
      );
      await settle();

      expect(host().querySelector('[data-testid="zone-density-empty"]')).not.toBeNull();
      expect(host().querySelector('[data-testid="zone-density-table"]')).toBeNull();
    });

    it('offers a retry when the report cannot be read', async () => {
      const zoneDensity = vi
        .fn()
        .mockRejectedValueOnce(new ApiError('INTERNAL_ERROR', 500, null, null))
        .mockResolvedValue(density());
      await render({ zoneDensity });
      await settle();
      expect(host().querySelector('[data-testid="zone-density-retry"]')).not.toBeNull();

      (host().querySelector('[data-testid="zone-density-retry"]') as HTMLButtonElement).click();
      await settle();

      expect(host().querySelectorAll('[data-testid="zone-density-row"]').length).toBeGreaterThan(0);
    });

    it('reads the other branch when one is picked', async () => {
      const zoneDensity = vi.fn().mockResolvedValue(density());
      await render(
        { zoneDensity },
        {
          locations: [
            { id: 'l1', displayName: 'Chilanzar' },
            { id: 'l2', displayName: 'Yunusobod' },
          ],
        },
      );
      await settle();

      const select = host().querySelector<HTMLSelectElement>('[data-testid="geography-branch"]')!;
      select.value = 'l2';
      select.dispatchEvent(new Event('change'));
      await settle();

      expect(zoneDensity).toHaveBeenLastCalledWith(
        't1',
        expect.objectContaining({ locationId: ['l2'] }),
      );
    });
  });

  describe('7.10a today’s orders as pins (ADR 0145, an audited reveal)', () => {
    it('opens nothing until the button is pressed', async () => {
      await render({});
      await settle();

      expect(orderPoints.reveal).not.toHaveBeenCalled();
      expect(host().querySelector('[data-testid="geography-pins-section"]')).not.toBeNull();
      expect(host().querySelector('[data-testid="order-points-audit"]')).not.toBeNull();
    });

    it('opens the selected branch’s day on request, stating this report as the purpose, and draws one pin per order', async () => {
      await render({});
      await settle();

      (host().querySelector('[data-testid="order-points-reveal"]') as HTMLButtonElement).click();
      await settle();

      expect(orderPoints.reveal).toHaveBeenCalledTimes(1);
      expect(orderPoints.reveal).toHaveBeenCalledWith(
        SCOPE,
        expect.stringContaining('Geography report'),
      );
      const orderPins = mapProvider.map.livePins.filter((pin) => pin.tone === 'order');
      expect(orderPins).toHaveLength(1);
      expect(orderPins[0].label).toContain('F-1');
      expect(orderPins[0].draggable).toBe(false);
    });

    it('opens the other branch’s day when that branch is the one selected', async () => {
      await render(
        {},
        {
          locations: [
            { id: 'l1', displayName: 'Chilanzar' },
            { id: 'l2', displayName: 'Yunusobod' },
          ],
        },
      );
      await settle();
      const select = host().querySelector<HTMLSelectElement>('[data-testid="geography-branch"]')!;
      select.value = 'l2';
      select.dispatchEvent(new Event('change'));
      await settle();

      (host().querySelector('[data-testid="order-points-reveal"]') as HTMLButtonElement).click();
      await settle();

      expect(orderPoints.reveal).toHaveBeenCalledWith(
        { ...SCOPE, locationId: 'l2' },
        expect.any(String),
      );
    });

    it('says one honest sentence to a reader who may not open doorsteps', async () => {
      await render({});
      orderPoints.reveal.mockRejectedValue(
        new ApiError('INSUFFICIENT_CAPABILITY', 403, null, null),
      );
      await settle();

      (host().querySelector('[data-testid="order-points-reveal"]') as HTMLButtonElement).click();
      await settle();

      expect(host().querySelector('[data-testid="order-points-denied"]')).not.toBeNull();
      expect(mapProvider.map?.livePins ?? []).toHaveLength(0);
    });
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

    // Row 7.10b, wave 10 w5-reports-exports: the distance chart used to name
    // the gap on screen (reporting.fact_delivery carried the raw distance but
    // nothing bucketed it). This proves the real read, wired beside the
    // duration chart, not a screen that still gave up on one histogram.
    it('renders the distance histogram against the new bucket read, scoped to the selected branch', async () => {
      const distanceBucketsFn = vi.fn().mockResolvedValue(distanceBucketsResponse());
      await render({ distanceBuckets: distanceBucketsFn });

      expect(distanceBucketsFn).toHaveBeenCalledWith(
        't1',
        expect.objectContaining({ locationId: ['l1'] }),
      );
      const host = fixture.nativeElement as HTMLElement;
      const table = host.querySelector(
        '[data-testid="geography-distance-histogram"] [data-testid="q-histogram-chart-table"]',
      ) as HTMLElement;
      expect(table).not.toBeNull();
      expect(table.textContent).toContain('6');
      // The duration chart is still there beside it.
      expect(host.querySelector('[data-testid="geography-duration-histogram"]')).not.toBeNull();
    });

    it('publishes the distance bucket boundaries as a formula-panel caption, from the bucket-set endpoint', async () => {
      await render({});

      const caption = (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="geography-distance-formula"]',
      );
      expect(caption).not.toBeNull();
      expect(caption?.textContent).toContain('1');
      expect(caption?.textContent).toContain('8');
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

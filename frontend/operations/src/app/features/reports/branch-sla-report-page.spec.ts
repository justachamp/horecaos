import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { FiscalizationApi } from '../settings/fiscalization/fiscalization-api';
import { PaymentMethodsApi } from '../settings/payment-methods/payment-methods-api';
import { SalesChannelsApi } from '../settings/sales-channels/sales-channels-api';
import { LocationsApi } from '../settings/locations/locations-api';
import { BranchSlaReportPage, buildSlaRows } from './branch-sla-report-page';
import { SlaBucketSetApi } from '../../core/api/sla-bucket-set-api';
import { BucketResponse, PaymentMixResponse, ReportingApi } from './reporting-api';
import { ReportsFilterState } from './reports-filter-state';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

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

function bucket(
  locationId: string,
  bucketCode: string,
  orderCount: number,
  shareBasisPoints: number,
  businessDate = '2026-09-10',
): BucketResponse {
  return { businessDate, locationId, bucketCode, orderCount, shareBasisPoints };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

/**
 * Wave T06 (7.3a): pins the arithmetic fix on its own, with no Angular
 * TestBed — `buildSlaRows` is a pure function of the `/sla-buckets` response,
 * and the defect it fixes is arithmetic, not rendering.
 *
 * The previous version summed `count` across every day in range while
 * overwriting `sharePercent` with whatever day was processed *last* — so a
 * 7-day or month preset rendered the last day's share printed beside a
 * whole-range count. These cases would have passed under that bug (each
 * ends with `sharePercent` equal to the last day's own 20%/80% split) and
 * fail it now that `sharePercent` is the whole range's own 50/50 split.
 */
describe('buildSlaRows: sharePercent is the whole range’s share', () => {
  const nameById = new Map([['l1', 'Юнусабад']]);
  const noMedians = new Map<string, number | null>();

  it('computes sharePercent from the summed whole-range counts, not the last day processed', () => {
    // Day 1: UNDER_30 80% (8 of 10), OVER_60 20% (2 of 10).
    // Day 2: UNDER_30 20% (2 of 10), OVER_60 80% (8 of 10) — the opposite split.
    // Whole range: UNDER_30 10 of 20 (50%), OVER_60 10 of 20 (50%).
    const buckets: readonly BucketResponse[] = [
      bucket('l1', 'UNDER_30', 8, 8000, '2026-09-10'),
      bucket('l1', 'OVER_60', 2, 2000, '2026-09-10'),
      bucket('l1', 'UNDER_30', 2, 2000, '2026-09-11'),
      bucket('l1', 'OVER_60', 8, 8000, '2026-09-11'),
    ];

    const rows = buildSlaRows(buckets, nameById, noMedians);
    const row = rows.find((r) => r.locationId === 'l1');

    expect(row?.buckets['UNDER_30'].count).toBe(10);
    expect(row?.buckets['OVER_60'].count).toBe(10);
    // The bug this fixes: naively taking the last day's shareBasisPoints
    // would print 20% beside UNDER_30's count of 10 and 80% beside OVER_60's
    // — a real number (10) beside a share (20%/80%) that belongs to neither
    // bucket's actual whole-range count.
    expect(row?.buckets['UNDER_30'].sharePercent).toBe(50);
    expect(row?.buckets['OVER_60'].sharePercent).toBe(50);
    expect(row?.total).toBe(20);
  });

  it('shares still sum to 100 for an uneven three-day split, never to whatever the last day alone summed to', () => {
    const buckets: readonly BucketResponse[] = [
      bucket('l1', 'UNDER_30', 6, 6000, '2026-09-10'),
      bucket('l1', 'M30_35', 4, 4000, '2026-09-10'),
      bucket('l1', 'UNDER_30', 1, 1000, '2026-09-11'),
      bucket('l1', 'M30_35', 9, 9000, '2026-09-11'),
      bucket('l1', 'UNDER_30', 3, 3000, '2026-09-12'),
      bucket('l1', 'M30_35', 7, 7000, '2026-09-12'),
    ];

    const rows = buildSlaRows(buckets, nameById, noMedians);
    const row = rows.find((r) => r.locationId === 'l1');

    // UNDER_30: 6+1+3 = 10 of 30 total = 33%. M30_35: 4+9+7 = 20 of 30 = 67%.
    expect(row?.buckets['UNDER_30'].count).toBe(10);
    expect(row?.buckets['UNDER_30'].sharePercent).toBe(33);
    expect(row?.buckets['M30_35'].count).toBe(20);
    expect(row?.buckets['M30_35'].sharePercent).toBe(67);
    // The last day alone (2026-09-12) reported 30%/70% — proof this is not
    // that day's share either, only the whole range's.
  });

  it('carries the median from the medians map, wave T06’s own new column', () => {
    const buckets: readonly BucketResponse[] = [bucket('l1', 'UNDER_30', 1, 10_000)];
    const medians = new Map<string, number | null>([['l1', 742]]);

    const rows = buildSlaRows(buckets, nameById, medians);

    expect(rows.find((r) => r.locationId === 'l1')?.medianSeconds).toBe(742);
  });

  it('a branch with no median in range reads null, never a stale zero', () => {
    const buckets: readonly BucketResponse[] = [bucket('l1', 'UNDER_30', 1, 10_000)];

    const rows = buildSlaRows(buckets, nameById, new Map());

    expect(rows.find((r) => r.locationId === 'l1')?.medianSeconds).toBeNull();
  });
});

describe('BranchSlaReportPage: the tenant-wide SLA histogram', () => {
  let fixture: ComponentFixture<BranchSlaReportPage>;
  beforeEach(() => {
    bucketSetVersion = 1;
  });

  /** What the Settings card's endpoint answers; `null` makes the read fail. */
  let bucketSetVersion: number | null;

  async function render(
    deliveryTimeRows: readonly { locationId: string; averageSeconds: number | null }[] = [],
    overrides: {
      readonly paymentMix?: PaymentMixResponse;
      readonly legalEntities?: readonly unknown[];
      readonly metrics?: readonly unknown[];
    } = {},
  ): Promise<void> {
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [BranchSlaReportPage],
      providers: [
        ReportsFilterState,
        {
          provide: SlaBucketSetApi,
          useValue: {
            get: vi
              .fn()
              .mockImplementation(() =>
                bucketSetVersion === null
                  ? Promise.reject(new Error('refused'))
                  : Promise.resolve({ version: bucketSetVersion, buckets: [] }),
              ),
          },
        },
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        {
          provide: LocationsApi,
          useValue: {
            list: vi.fn().mockResolvedValue([
              { id: 'l1', displayName: 'Юнусабад' },
              { id: 'l2', displayName: 'Чиланзар' },
            ]),
          },
        },
        {
          provide: SalesChannelsApi,
          useValue: { list: vi.fn().mockResolvedValue([]) },
        },
        {
          provide: PaymentMethodsApi,
          useValue: { list: vi.fn().mockResolvedValue([]) },
        },
        {
          provide: FiscalizationApi,
          useValue: { listLegalEntities: vi.fn().mockResolvedValue(overrides.legalEntities ?? []) },
        },
        {
          provide: ReportingApi,
          useValue: {
            query: vi.fn().mockResolvedValue({ rows: [], provenance: provenance() }),
            preparationTimeByLocation: vi
              .fn()
              .mockResolvedValue({ rows: [], provenance: provenance() }),
            deliveryTransitTimeByLocation: vi
              .fn()
              .mockResolvedValue({ rows: deliveryTimeRows, provenance: provenance() }),
            slaBuckets: vi.fn().mockResolvedValue({
              buckets: [
                bucket('l1', 'UNDER_30', 10, 10_000),
                bucket('l2', 'UNDER_30', 5, 10_000),
                bucket('l1', 'OVER_60', 1, 0),
              ],
              medians: [],
              provenance: provenance(),
            }),
            paymentMix: vi
              .fn()
              .mockResolvedValue(
                overrides.paymentMix ?? { overview: [], byLocation: [], provenance: provenance() },
              ),
            metrics: vi.fn().mockResolvedValue(overrides.metrics ?? []),
          },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(BranchSlaReportPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('aggregates every branch’s own bucket counts into one tenant-wide histogram', async () => {
    await render();
    const host = fixture.nativeElement as HTMLElement;
    const chart = host.querySelector('[data-testid="q-histogram-chart"]');
    expect(chart).not.toBeNull();

    const table = host.querySelector('[data-testid="q-histogram-chart-table"]') as HTMLElement;
    // UNDER_30: 10 (l1) + 5 (l2) = 15; OVER_60: 1 (l1) + 0 (l2) = 1.
    expect(table.textContent).toContain('15');
    expect(table.textContent).toContain('1');
  });

  it('never repeats the same total for a bucket no branch reported — a real zero, not the last value carried over', async () => {
    await render();
    const host = fixture.nativeElement as HTMLElement;
    const table = host.querySelector('[data-testid="q-histogram-chart-table"]') as HTMLElement;
    const rows = Array.from(table.querySelectorAll('tbody tr'));
    const m3040Row = rows.find(
      (r) => r.textContent?.includes('30') && r.textContent?.includes('35'),
    );
    expect(m3040Row?.querySelector('td')?.textContent?.trim()).toBe('0');
  });

  it('prints the bucket-set version the Settings card’s endpoint answers, not a constant (row 10.10c)', async () => {
    bucketSetVersion = 7;
    await render();

    const footnote = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="sla-bucket-version"]',
    );
    expect(footnote?.textContent?.trim()).toBe('Buckets: sla_bucket_set.v7');
  });

  it('leaves the footnote out, and still draws the report, when the version cannot be read', async () => {
    bucketSetVersion = null;
    await render();

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="sla-bucket-version"]')).toBeNull();
    expect(host.querySelector('[data-testid="q-histogram-chart"]')).not.toBeNull();
  });

  it('renders the branch leaderboard’s average delivery (courier transit) time column, row 7.3', async () => {
    await render([
      { locationId: 'l1', averageSeconds: 900 },
      { locationId: 'l2', averageSeconds: null },
    ]);
    const host = fixture.nativeElement as HTMLElement;

    const cells = Array.from(host.querySelectorAll('[data-testid="branch-row-delivery-time"]')).map(
      (cell) => cell.textContent?.trim(),
    );

    // 900 seconds -> 15 min for l1; l2 had no settled delivery in range, "—" not "0 min".
    expect(cells).toContain('15 min');
    expect(cells).toContain('—');
  });
  // ------------------------------------------------------- Table D, the payment split (7.3b)

  function mixRow(locationId: string, legalEntityId: string | null, amountSom: number) {
    return {
      locationId,
      legalEntityId,
      paymentMethodCode: 'CASH',
      settlesFromBalance: false,
      tenderCount: 2,
      amountSom,
    };
  }

  function paymentRows(): HTMLElement[] {
    return Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>(
        '.payments-table tbody tr',
      ),
    );
  }

  it('keeps one branch’s two taxpayers on two rows and names each, never one summed row (ADR 0115)', async () => {
    await render([], {
      paymentMix: {
        overview: [],
        byLocation: [mixRow('l1', 'e1', 30_000), mixRow('l1', 'e2', 5_000)],
        provenance: provenance(),
      },
      legalEntities: [
        { id: 'e1', shortName: 'Birinchi MCHJ', legalName: 'Birinchi', status: 'ACTIVE' },
        { id: 'e2', shortName: null, legalName: 'Ikkinchi MCHJ', status: 'ACTIVE' },
      ],
    });

    const rows = paymentRows();
    expect(rows).toHaveLength(2);
    expect(rows.map((row) => row.textContent)).toEqual([
      expect.stringContaining('Birinchi MCHJ'),
      expect.stringContaining('Ikkinchi MCHJ'),
    ]);
    // Taxpayers sort by name within the branch; each row keeps its own figure.
    expect(rows[0].textContent).toMatch(/30[\s  ]000/);
    expect(rows[1].textContent).toMatch(/5[\s  ]000/);
  });

  it('adds no taxpayer column for a tenant with one legal entity', async () => {
    await render([], {
      paymentMix: {
        overview: [],
        byLocation: [mixRow('l1', 'e1', 30_000), mixRow('l2', 'e1', 12_000)],
        provenance: provenance(),
      },
      legalEntities: [{ id: 'e1', shortName: 'Birinchi MCHJ', legalName: 'B', status: 'ACTIVE' }],
    });

    expect(paymentRows()).toHaveLength(2);
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="payment-split-entity"]'),
    ).toBeNull();
  });

  it('says the payment split is provisional and what it leaves out, from the same note the overview carries', async () => {
    await render([], {
      paymentMix: {
        overview: [],
        byLocation: [mixRow('l1', 'e1', 30_000)],
        provenance: { ...provenance(), provisionalMetrics: ['payment_mix.amount.v1'] },
      },
      metrics: [
        { metricCode: 'payment_mix.amount.v1', openQuestion: 'net of provider commission?' },
      ],
    });
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('[data-testid="payment-mix-provisional"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="payment-mix-open-question"]')?.textContent).toContain(
      'commission',
    );
  });
});

import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { LocationsApi } from '../settings/locations/locations-api';
import { SalesChannelsApi } from '../settings/sales-channels/sales-channels-api';
import { BusinessOverviewPage } from './business-overview-page';
import { QueryParams, QueryResponse, ReportingApi, RowResponse } from './reporting-api';
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

function row(overrides: Partial<RowResponse> & { businessDate: string }): RowResponse {
  return {
    locationId: null,
    channelCode: null,
    fulfilmentType: null,
    legalEntityId: null,
    values: {},
    ...overrides,
  };
}

/** A stand-in `ReportingApi.query` that answers Band A's four distinct query shapes by their `groupBy`. */
function queryStub(): (tenantId: string, params: QueryParams) => Promise<QueryResponse> {
  return (_tenantId, params) => {
    if (params.groupBy?.[0] === 'CHANNEL') {
      return Promise.resolve({
        rows: [
          row({
            businessDate: '2026-09-10',
            channelCode: 'TELEGRAM',
            values: { 'channel_mix.count.v1': 6, 'revenue.gross.v1': 600_000 },
          }),
          row({
            businessDate: '2026-09-10',
            channelCode: 'YANDEX',
            values: { 'channel_mix.count.v1': 4, 'revenue.gross.v1': 400_000 },
          }),
        ],
        provenance: provenance(),
      });
    }
    if (params.groupBy?.[0] === 'FULFILMENT_TYPE') {
      return Promise.resolve({
        rows: [
          row({
            businessDate: '2026-09-10',
            fulfilmentType: 'DELIVERY',
            values: { 'orders.count.v1': 7 },
          }),
          row({
            businessDate: '2026-09-10',
            fulfilmentType: 'PICKUP',
            values: { 'orders.count.v1': 3 },
          }),
        ],
        provenance: provenance(),
      });
    }
    if (params.groupBy?.[0] === 'LOCATION') {
      return Promise.resolve({ rows: [], provenance: provenance() });
    }
    // Band A itself: the two-day current range, no groupBy.
    return Promise.resolve({
      rows: [
        row({
          businessDate: '2026-09-09',
          values: {
            'revenue.gross.v1': 400_000,
            'orders.count.v1': 8,
            'orders.cancelled.v1': 1,
            'orders.late.v1': 0,
          },
        }),
        row({
          businessDate: '2026-09-10',
          values: {
            'revenue.gross.v1': 600_000,
            'orders.count.v1': 12,
            'orders.cancelled.v1': 2,
            'orders.late.v1': 1,
          },
        }),
      ],
      provenance: provenance(),
    });
  };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('BusinessOverviewPage', () => {
  let fixture: ComponentFixture<BusinessOverviewPage>;

  async function render(): Promise<void> {
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [BusinessOverviewPage],
      providers: [
        provideRouter([]),
        ReportsFilterState,
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        {
          provide: ReportingApi,
          useValue: {
            query: vi.fn(queryStub()),
            preparationTime: vi
              .fn()
              .mockResolvedValue({ medianSeconds: 300, provenance: provenance() }),
            orderOutcomes: vi.fn().mockResolvedValue({ rows: [], provenance: provenance() }),
            orders: vi
              .fn()
              .mockResolvedValue({ rows: [], maybeMore: false, provenance: provenance() }),
          },
        },
        { provide: LocationsApi, useValue: { list: vi.fn().mockResolvedValue([]) } },
        { provide: SalesChannelsApi, useValue: { list: vi.fn().mockResolvedValue([]) } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(BusinessOverviewPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('renders a KPI tile per Band A metric, each with a value and a sparkline over the per-day rows', async () => {
    await render();
    const host = fixture.nativeElement as HTMLElement;
    const tiles = host.querySelectorAll('[data-testid="q-kpi-tile"]');
    expect(tiles.length).toBe(5);
    expect(host.textContent).toContain('Revenue');
    // Revenue totals 1 000 000 over the two days — the tile's own formatted value.
    expect(host.textContent).toMatch(/1[\s ]000[\s ]000/);
    // Every tile draws a sparkline from the two-day series (both days have data).
    expect(host.querySelectorAll('[data-testid="q-sparkline"]').length).toBe(5);
  });

  it('renders the channel mix as a donut, not the retired bar-row divs', async () => {
    await render();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="q-donut-chart"]')).not.toBeNull();
    expect(host.querySelector('.bar-row')).toBeNull();
    const legend = host.querySelector('[data-testid="q-donut-chart-legend"]');
    expect(legend?.textContent).toContain('TELEGRAM');
    expect(legend?.textContent).toContain('60%');
  });

  it('renders the fulfilment mix as a stacked bar chart, not the retired divs', async () => {
    await render();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="q-stacked-bar-chart"]')).not.toBeNull();
    expect(host.querySelector('.stacked-bar')).toBeNull();
  });

  it('renders the daily revenue and orders trend as line charts over the per-day rows Band A already fetched', async () => {
    await render();
    const host = fixture.nativeElement as HTMLElement;
    const lineCharts = host.querySelectorAll('[data-testid="q-line-chart"]');
    expect(lineCharts.length).toBe(2);
  });
});

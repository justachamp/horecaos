import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { LocationsApi } from '../settings/locations/locations-api';
import { DemandForecastPage } from './demand-forecast-page';
import {
  DemandForecastResponse,
  DemandHistoryResponse,
  HourDemandResponse,
  ReportingApi,
} from './reporting-api';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function provenance() {
  return {
    asOf: '2026-08-26T04:00:00Z',
    closedThrough: '2026-08-25',
    lastCloseCompletedAt: '2026-08-25T22:00:00Z',
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

function response(overrides: Partial<DemandHistoryResponse> = {}): DemandHistoryResponse {
  return {
    locationId: 'l1',
    weekday: 2,
    requestedSampleSize: 4,
    minimumSampleSize: 3,
    sampleDates: ['2026-08-25', '2026-08-18', '2026-08-11', '2026-08-04'],
    holidayDates: [],
    holidayMode: 'INCLUDE',
    hours: hours({
      18: {
        ordersByDate: { '2026-08-25': 8, '2026-08-18': 6, '2026-08-11': 4, '2026-08-04': 2 },
        totalOrders: 20,
        averageOrders: 5,
      },
    }),
    provenance: provenance(),
    ...overrides,
  };
}

/** Wave W02: an empty run — the shape `demandForecast` returns before `ForecastScheduler` has ever generated one. */
function emptyForecast(overrides: Partial<DemandForecastResponse> = {}): DemandForecastResponse {
  return {
    locationId: 'l1',
    weekday: 2,
    runId: null,
    modelVersion: 1,
    confidenceLevel: 0.8,
    generatedAt: null,
    targetDate: null,
    hours: [],
    comparisons: [],
    provenance: provenance(),
    ...overrides,
  };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('DemandForecastPage', () => {
  let fixture: ComponentFixture<DemandForecastPage>;

  async function render(
    api: Partial<ReportingApi>,
    options: {
      readonly scope?: LocationScope | null;
      readonly denied?: boolean;
      readonly locations?: readonly { id: string; displayName: string }[];
    } = {},
  ): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [DemandForecastPage],
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
            demandForecast: vi.fn().mockResolvedValue(emptyForecast()),
            demandForecastBreakdown: vi.fn(),
            ...api,
          },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(DemandForecastPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('shows the denied state when the location grant is missing', async () => {
    await render({ demandHistory: vi.fn() }, { scope: null, denied: true });

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="forecast-denied"]'),
    ).not.toBeNull();
  });

  it('shows the average with its sample size once there is enough history', async () => {
    await render({ demandHistory: () => Promise.resolve(response()) });

    const host = fixture.nativeElement as HTMLElement;
    const summary = host.querySelector('[data-testid="forecast-sample-summary"]');
    expect(summary?.textContent).toContain('4');
    const table = host.querySelector('[data-testid="forecast-table"]') as HTMLElement;
    expect(table.textContent).toContain('18:00');
    expect(table.textContent).toContain('5.0');
  });

  it('computes hourWindowLabel relative to a non-midnight businessDayStart, not the wall-clock hour', async () => {
    // 22:00 start: operating hour 2 (the table's third row — one row per
    // hourOfDay, 0..23 in order) covers wall-clock 00:00-01:00 the following
    // day, not wall-clock 02:00-03:00. A regression that dropped the
    // businessDayStart offset (defaulting to a naive wall-clock window)
    // would print 02:00-03:00 in that row instead — checking the row
    // specifically matters because 02:00-03:00 legitimately appears
    // elsewhere in this same table (as operating hour 4's own window), so a
    // whole-table text search would not catch the regression. Every other
    // test in this file leaves businessDayStart at the default '00:00:00',
    // where the two computations coincide and would not catch it either.
    await render({
      demandHistory: () =>
        Promise.resolve(
          response({ provenance: { ...provenance(), businessDayStart: '22:00:00' } }),
        ),
    });

    const table = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="forecast-table"]',
    ) as HTMLElement;
    const operatingHourTwoRow = table.querySelectorAll('tbody tr')[2];
    expect(operatingHourTwoRow.querySelector('td')?.textContent).toBe('00:00–01:00');
  });

  it('the demand-history section never prints prediction language, in any of the three locales', async () => {
    await render({ demandHistory: () => Promise.resolve(response()) });
    const section = () =>
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="demand-history-section"]',
      ) as HTMLElement;
    const i18n = TestBed.inject(I18n);

    i18n.setLocale('en');
    fixture.detectChanges();
    expect(section().textContent?.toLowerCase() ?? '').not.toContain('forecast');

    i18n.setLocale('ru');
    fixture.detectChanges();
    const ru = section().textContent?.toLowerCase() ?? '';
    expect(ru).not.toContain('прогноз');
    expect(ru).not.toContain('предсказ');

    i18n.setLocale('uz-Latn');
    fixture.detectChanges();
    const uz = section().textContent?.toLowerCase() ?? '';
    expect(uz).not.toContain('bashorat');
    expect(uz).not.toContain('prognoz');
  });

  it('shows raw per-date counts, not an average, below the minimum sample size', async () => {
    await render({
      demandHistory: () =>
        Promise.resolve(
          response({
            sampleDates: ['2026-08-25'],
            hours: hours({
              12: { ordersByDate: { '2026-08-25': 7 }, totalOrders: 7, averageOrders: null },
            }),
          }),
        ),
    });

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="forecast-thin-sample"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="forecast-table"]')).toBeNull();
    const rawTable = host.querySelector('[data-testid="forecast-raw-table"]') as HTMLElement;
    expect(rawTable.textContent).toContain('7');
  });

  it('names the empty state honestly instead of rendering a table of zeros', async () => {
    await render({
      demandHistory: () => Promise.resolve(response({ sampleDates: [], hours: hours() })),
    });

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="forecast-no-history"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="forecast-table"]')).toBeNull();
    expect(host.querySelector('[data-testid="forecast-raw-table"]')).toBeNull();
  });

  it('reloads for the newly selected weekday', async () => {
    const demandHistory = vi.fn().mockResolvedValue(response());
    const demandForecast = vi.fn().mockResolvedValue(emptyForecast());
    await render({ demandHistory, demandForecast });

    (fixture.nativeElement as HTMLElement)
      .querySelector<HTMLButtonElement>('[data-testid="forecast-weekday-4"]')
      ?.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(demandHistory).toHaveBeenLastCalledWith('t1', {
      locationId: 'l1',
      weekday: 4,
      sampleSize: 4,
      holidayMode: 'INCLUDE',
    });
    expect(demandForecast).toHaveBeenLastCalledWith('t1', { locationId: 'l1', weekday: 4 });
  });

  it('7.8b: reloads under the selected holiday mode', async () => {
    const demandHistory = vi.fn().mockResolvedValue(response());
    await render({ demandHistory });

    (fixture.nativeElement as HTMLElement)
      .querySelector<HTMLButtonElement>('[data-testid="forecast-holiday-mode-EXCLUDE"]')
      ?.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(demandHistory).toHaveBeenLastCalledWith('t1', {
      locationId: 'l1',
      weekday: 2,
      sampleSize: 4,
      holidayMode: 'EXCLUDE',
    });
  });

  it('7.8b: flags a holiday sample date rather than rendering it identically to an ordinary one', async () => {
    // The raw per-date table (below the minimum sample) is where a date's own
    // flag renders — the sample-summary table above minimum shows only an
    // hour/average grid, with the count of flagged dates in its own caption.
    await render({
      demandHistory: () =>
        Promise.resolve(
          response({
            sampleDates: ['2026-08-18', '2026-08-11'],
            holidayDates: ['2026-08-18'],
            hours: hours({
              12: {
                ordersByDate: { '2026-08-18': 9, '2026-08-11': 5 },
                totalOrders: 14,
                averageOrders: null,
              },
            }),
          }),
        ),
    });

    const host = fixture.nativeElement as HTMLElement;
    const flagged = host.querySelector('[data-testid="forecast-holiday-2026-08-18"]');
    expect(flagged).not.toBeNull();
    expect(host.querySelector('[data-testid="forecast-holiday-2026-08-11"]')).toBeNull();
  });

  it('7.8b: names how many sample dates were holidays once there is enough history to average', async () => {
    await render({
      demandHistory: () => Promise.resolve(response({ holidayDates: ['2026-08-18'] })),
    });

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="forecast-table"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="forecast-holiday-summary"]')?.textContent).toContain(
      '1',
    );
  });

  describe('the branch selector', () => {
    it('is hidden for a single-location tenant (no other branches to switch to)', async () => {
      await render({ demandHistory: () => Promise.resolve(response()) }, { locations: [] });

      expect(
        (fixture.nativeElement as HTMLElement).querySelector('[data-testid="forecast-branch"]'),
      ).toBeNull();
    });

    it('changes the series for both sections when a different branch is chosen', async () => {
      const demandHistory = vi.fn().mockResolvedValue(response());
      const demandForecast = vi.fn().mockResolvedValue(emptyForecast());
      await render(
        { demandHistory, demandForecast },
        {
          locations: [
            { id: 'l1', displayName: 'Chilonzor' },
            { id: 'l2', displayName: 'Yunusobod' },
          ],
        },
      );
      demandHistory.mockClear();
      demandForecast.mockClear();

      const select = (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>(
        '[data-testid="forecast-branch"]',
      );
      expect(select).not.toBeNull();
      select!.value = 'l2';
      select!.dispatchEvent(new Event('change'));
      await flushMicrotasks();
      fixture.detectChanges();

      expect(demandHistory).toHaveBeenLastCalledWith('t1', {
        locationId: 'l2',
        weekday: 2,
        sampleSize: 4,
        holidayMode: 'INCLUDE',
      });
      expect(demandForecast).toHaveBeenLastCalledWith('t1', { locationId: 'l2', weekday: 2 });
    });
  });

  describe('the forecast section (wave W02)', () => {
    it('shows the model, its confidence interval and the sample size once a run exists', async () => {
      await render({
        demandHistory: () => Promise.resolve(response()),
        demandForecast: () =>
          Promise.resolve(
            emptyForecast({
              runId: 'r1',
              targetDate: '2026-09-01',
              hours: [
                {
                  operatingHour: 18,
                  forecastQuantity: 10,
                  confidenceLow: 7.9,
                  confidenceHigh: 12.1,
                  actualQuantity: null,
                  absolutePercentageError: null,
                },
              ],
              comparisons: [],
            }),
          ),
      });

      const host = fixture.nativeElement as HTMLElement;
      const table = host.querySelector('[data-testid="forecast-model-table"]') as HTMLElement;
      expect(table.textContent).toContain('18:00');
      expect(table.textContent).toContain('10.0');
      expect(table.textContent).toContain('7.9');
      expect(table.textContent).toContain('12.1');
    });

    it('says plainly that no forecast has been generated yet, rather than an empty table', async () => {
      await render({
        demandHistory: () => Promise.resolve(response()),
        demandForecast: () => Promise.resolve(emptyForecast({ runId: null })),
      });

      expect(
        (fixture.nativeElement as HTMLElement).querySelector('[data-testid="forecast-model-none"]'),
      ).not.toBeNull();
    });
  });

  describe('the department/product breakdown (7.8a)', () => {
    /** The breakdown lives inside the forecast section, which needs a real run before it renders at all. */
    function forecastWithARun(): DemandForecastResponse {
      return emptyForecast({
        runId: 'r1',
        targetDate: '2026-09-01',
        hours: [
          {
            operatingHour: 18,
            forecastQuantity: 4,
            confidenceLow: 3,
            confidenceHigh: 5,
            actualQuantity: null,
            absolutePercentageError: null,
          },
        ],
      });
    }

    it('is not fetched until the operator asks', async () => {
      const demandForecastBreakdown = vi.fn();
      await render({
        demandHistory: () => Promise.resolve(response()),
        demandForecast: () => Promise.resolve(forecastWithARun()),
        demandForecastBreakdown,
      });

      expect(demandForecastBreakdown).not.toHaveBeenCalled();
    });

    it('fetches and renders once shown', async () => {
      const demandForecastBreakdown = vi.fn().mockResolvedValue({
        locationId: 'l1',
        weekday: 2,
        byProduct: false,
        rows: [
          {
            categoryId: 'c1',
            variantId: null,
            productName: null,
            operatingHour: 18,
            forecastQuantity: 4,
            actualQuantity: null,
            absolutePercentageError: null,
          },
        ],
      });
      await render({
        demandHistory: () => Promise.resolve(response()),
        demandForecast: () => Promise.resolve(forecastWithARun()),
        demandForecastBreakdown,
      });

      (fixture.nativeElement as HTMLElement)
        .querySelector<HTMLButtonElement>('[data-testid="forecast-breakdown-show"]')
        ?.click();
      await flushMicrotasks();
      fixture.detectChanges();

      expect(demandForecastBreakdown).toHaveBeenCalledWith('t1', {
        locationId: 'l1',
        weekday: 2,
        dimension: 'CATEGORY',
      });
      expect(
        (fixture.nativeElement as HTMLElement).querySelector(
          '[data-testid="forecast-breakdown-table"]',
        ),
      ).not.toBeNull();
    });
  });

  describe('the week overview heatmap (IA X.19)', () => {
    it('is not fetched on load — only the current weekday call fires', async () => {
      const demandHistory = vi.fn().mockResolvedValue(response());
      await render({ demandHistory });

      expect(demandHistory).toHaveBeenCalledTimes(1);
      expect(
        (fixture.nativeElement as HTMLElement).querySelector('[data-testid="q-heatmap-chart"]'),
      ).toBeNull();
    });

    it('fetches all seven weekdays and renders the heatmap once the operator asks', async () => {
      const demandHistory = vi
        .fn()
        .mockImplementation((_tenantId: string, params: { weekday: number }) =>
          Promise.resolve(response({ weekday: params.weekday })),
        );
      await render({ demandHistory });

      const host = fixture.nativeElement as HTMLElement;
      host
        .querySelector<HTMLButtonElement>('[data-testid="forecast-week-overview-button"]')
        ?.click();
      await flushMicrotasks();
      fixture.detectChanges();

      // One call for the initial weekday's own table, seven more for the grid.
      expect(demandHistory).toHaveBeenCalledTimes(8);
      expect(host.querySelector('[data-testid="q-heatmap-chart"]')).not.toBeNull();
      expect(host.querySelector('[data-testid="forecast-week-overview-button"]')).toBeNull();
    });

    it('shows an honest error rather than a half-drawn grid when a weekday call fails', async () => {
      const demandHistory = vi
        .fn()
        .mockResolvedValueOnce(response())
        .mockRejectedValue(new ApiError('INTERNAL', 500, null, 'corr-week'));
      await render({ demandHistory });

      const host = fixture.nativeElement as HTMLElement;
      host
        .querySelector<HTMLButtonElement>('[data-testid="forecast-week-overview-button"]')
        ?.click();
      await flushMicrotasks();
      fixture.detectChanges();

      expect(host.querySelector('[data-testid="forecast-week-overview-error"]')).not.toBeNull();
      expect(host.querySelector('[data-testid="q-heatmap-chart"]')).toBeNull();
    });
  });

  it('surfaces a load failure and retries on request', async () => {
    const demandHistory = vi
      .fn()
      .mockRejectedValueOnce(new ApiError('INTERNAL', 500, null, 'corr-1'))
      .mockResolvedValueOnce(response());
    await render({ demandHistory });

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="forecast-retry"]')).not.toBeNull();

    host.querySelector<HTMLButtonElement>('[data-testid="forecast-retry"]')?.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(demandHistory).toHaveBeenCalledTimes(2);
    expect(host.querySelector('[data-testid="forecast-table"]')).not.toBeNull();
  });
});

import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { DemandForecastPage } from './demand-forecast-page';
import { DemandHistoryResponse, HourDemandResponse, ReportingApi } from './reporting-api';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function provenance() {
  return {
    asOf: '2026-08-26T04:00:00Z',
    closedThrough: '2026-08-25',
    lastCloseCompletedAt: '2026-08-25T22:00:00Z',
    businessDayStart: '00:00',
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
    hours: hours({
      18: { ordersByDate: { '2026-08-25': 8, '2026-08-18': 6, '2026-08-11': 4, '2026-08-04': 2 }, totalOrders: 20, averageOrders: 5 },
    }),
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
    locationOverrides: { scope?: LocationScope | null; denied?: boolean } = {},
  ): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [DemandForecastPage],
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(
              'scope' in locationOverrides ? (locationOverrides.scope ?? null) : SCOPE,
            ),
            denied: signal(locationOverrides.denied ?? false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: ReportingApi, useValue: api },
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

  it('never prints prediction language in any of the three locales — this is the whole point of the wave', async () => {
    await render({ demandHistory: () => Promise.resolve(response()) });
    const host = fixture.nativeElement as HTMLElement;
    const i18n = TestBed.inject(I18n);

    i18n.setLocale('en');
    fixture.detectChanges();
    const en = host.textContent?.toLowerCase() ?? '';
    expect(en).not.toContain('forecast');
    expect(en).not.toContain('predict');

    i18n.setLocale('ru');
    fixture.detectChanges();
    const ru = host.textContent?.toLowerCase() ?? '';
    expect(ru).not.toContain('прогноз');
    expect(ru).not.toContain('предсказ');

    i18n.setLocale('uz-Latn');
    fixture.detectChanges();
    const uz = host.textContent?.toLowerCase() ?? '';
    expect(uz).not.toContain('bashorat');
    expect(uz).not.toContain('prognoz');
  });

  it('shows raw per-date counts, not an average, below the minimum sample size', async () => {
    await render({
      demandHistory: () =>
        Promise.resolve(
          response({
            sampleDates: ['2026-08-25'],
            hours: hours({ 12: { ordersByDate: { '2026-08-25': 7 }, totalOrders: 7, averageOrders: null } }),
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
    await render({ demandHistory });

    (fixture.nativeElement as HTMLElement)
      .querySelector<HTMLButtonElement>('[data-testid="forecast-weekday-4"]')
      ?.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(demandHistory).toHaveBeenLastCalledWith('t1', { locationId: 'l1', weekday: 4, sampleSize: 4 });
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

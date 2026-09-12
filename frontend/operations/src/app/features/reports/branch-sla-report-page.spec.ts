import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { LocationsApi } from '../settings/locations/locations-api';
import { BranchSlaReportPage } from './branch-sla-report-page';
import { BucketResponse, ReportingApi } from './reporting-api';
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

function bucket(locationId: string, bucketCode: string, orderCount: number): BucketResponse {
  return { businessDate: '2026-09-10', locationId, bucketCode, orderCount, shareBasisPoints: 0 };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('BranchSlaReportPage: the tenant-wide SLA histogram', () => {
  let fixture: ComponentFixture<BranchSlaReportPage>;

  async function render(): Promise<void> {
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [BranchSlaReportPage],
      providers: [
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
          provide: LocationsApi,
          useValue: {
            list: vi.fn().mockResolvedValue([
              { id: 'l1', displayName: 'Юнусабад' },
              { id: 'l2', displayName: 'Чиланзар' },
            ]),
          },
        },
        {
          provide: ReportingApi,
          useValue: {
            query: vi.fn().mockResolvedValue({ rows: [], provenance: provenance() }),
            preparationTime: vi
              .fn()
              .mockResolvedValue({ medianSeconds: null, provenance: provenance() }),
            slaBuckets: vi.fn().mockResolvedValue({
              buckets: [
                bucket('l1', 'UNDER_30', 10),
                bucket('l2', 'UNDER_30', 5),
                bucket('l1', 'OVER_60', 1),
              ],
              provenance: provenance(),
            }),
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
});

import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { ApiError } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { LocationView, LocationsApi } from '../../settings/locations/locations-api';
import { ReviewReportPage, meanOf } from './review-report-page';
import { RecoverySummary, ReviewReportApi, ReviewSummary } from './review-report-api';

const SCOPE: LocationScope = {
  tenantId: 'tenant-1',
  brandId: 'brand-1',
  locationId: 'loc-chilonzor',
};

const PROVENANCE = {
  asOf: '2026-10-14T09:00:00Z',
  closedThrough: '2026-10-13',
  lastCloseCompletedAt: '2026-10-14T01:00:00Z',
  businessDayStart: '04:00',
  timezone: 'Asia/Tashkent',
  boundaryVersion: 1,
  metricVersions: [],
  provisionalMetrics: [],
  openDivergences: 0,
};

/** Two branches: 10 reviews summing 40 (4.0) and 2 reviews summing 4 (2.0). The mean of means is 3.0; the true mean is 44/12. */
const SUMMARY: ReviewSummary = {
  rows: [
    {
      brandId: 'brand-1',
      locationId: 'loc-chilonzor',
      reviews: 10,
      ratingSum: 40,
      average: 4,
      withComment: 3,
      distribution: [0, 0, 2, 6, 2],
    },
    {
      brandId: 'brand-1',
      locationId: 'loc-yunusobod',
      reviews: 2,
      ratingSum: 4,
      average: 2,
      withComment: 1,
      distribution: [0, 2, 0, 0, 0],
    },
  ],
  tags: [
    {
      brandId: 'brand-1',
      locationId: 'loc-chilonzor',
      category: 'FOOD',
      sentiment: 'POSITIVE',
      tags: 5,
    },
    {
      brandId: 'brand-1',
      locationId: 'loc-chilonzor',
      category: 'FOOD',
      sentiment: 'NEGATIVE',
      tags: 1,
    },
    {
      brandId: 'brand-1',
      locationId: 'loc-chilonzor',
      category: 'COURIER',
      sentiment: 'NEGATIVE',
      tags: 2,
    },
  ],
  provenance: PROVENANCE,
};

const RECOVERY: RecoverySummary = {
  rows: [
    {
      brandId: 'brand-1',
      locationId: 'loc-chilonzor',
      opened: 4,
      closed: 3,
      firstResponseMeanSeconds: 600,
      firstResponseP90Seconds: 5_400,
      closeMeanSeconds: 7_200,
      closeP90Seconds: null,
      breached: 1,
      breachRateBasisPoints: 2_500,
      compensations: 2,
    },
  ],
  outcomes: [
    { brandId: 'brand-1', locationId: 'loc-chilonzor', outcomeCode: 'REMEDY_GRANTED', cases: 2 },
    { brandId: 'brand-1', locationId: 'loc-yunusobod', outcomeCode: 'REMEDY_GRANTED', cases: 1 },
    { brandId: 'brand-1', locationId: 'loc-chilonzor', outcomeCode: 'NOT_ACTIONABLE', cases: 1 },
  ],
  remedies: [
    { brandId: 'brand-1', locationId: 'loc-chilonzor', currency: 'UZS', amountMinor: 3_000_000 },
    { brandId: 'brand-1', locationId: 'loc-yunusobod', currency: 'UZS', amountMinor: 1_000_000 },
  ],
  provenance: PROVENANCE,
};

function location(id: string, displayName: string): LocationView {
  return { id, displayName } as unknown as LocationView;
}

describe('ReviewReportPage', () => {
  let fixture: ComponentFixture<ReviewReportPage>;
  let api: { summary: ReturnType<typeof vi.fn>; recovery: ReturnType<typeof vi.fn> };
  let locations: { list: ReturnType<typeof vi.fn> };

  async function render(
    options: {
      scope?: LocationScope | null;
      denied?: boolean;
      summary?: ReviewSummary | Error;
      recovery?: RecoverySummary | Error;
    } = {},
  ): Promise<HTMLElement> {
    const { scope = SCOPE, denied = false, summary = SUMMARY, recovery = RECOVERY } = options;
    api = {
      summary: vi
        .fn()
        .mockImplementation(() =>
          summary instanceof Error ? Promise.reject(summary) : Promise.resolve(summary),
        ),
      recovery: vi
        .fn()
        .mockImplementation(() =>
          recovery instanceof Error ? Promise.reject(recovery) : Promise.resolve(recovery),
        ),
    };
    locations = {
      list: vi
        .fn()
        .mockResolvedValue([
          location('loc-chilonzor', 'Chilonzor'),
          location('loc-yunusobod', 'Yunusobod'),
        ]),
    };
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [ReviewReportPage],
      providers: [
        { provide: ReviewReportApi, useValue: api },
        { provide: LocationsApi, useValue: locations },
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(scope),
            denied: signal(denied),
            ensureLoaded: vi.fn().mockResolvedValue(undefined),
          },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(ReviewReportPage);
    fixture.detectChanges();
    await settle();
    return fixture.nativeElement as HTMLElement;
  }

  async function settle(): Promise<void> {
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(
        (fixture.nativeElement as HTMLElement).querySelector(
          '[data-testid="review-report-loading"]',
        ),
      ).toBeNull();
    });
    // The branch names are read after the figures are shown: await that call rather than counting turns.
    await Promise.all(locations.list.mock.results.map((result) => result.value));
    fixture.detectChanges();
  }

  function rows(host: HTMLElement, testId: string): string[][] {
    return [...host.querySelectorAll(`[data-testid="${testId}"]`)].map((row) =>
      [...row.querySelectorAll('td')].map((cell) =>
        (cell.textContent ?? '').replace(/\s+/g, ' ').trim(),
      ),
    );
  }

  it('asks both report endpoints for the last seven days of the operator’s brand', async () => {
    await render();

    expect(api.summary).toHaveBeenCalledTimes(1);
    expect(api.recovery).toHaveBeenCalledTimes(1);
    const [tenantId, range, brandId] = api.summary.mock.calls[0];
    expect(tenantId).toBe('tenant-1');
    expect(brandId).toBe('brand-1');
    const days = (new Date(range.to).getTime() - new Date(range.from).getTime()) / 86_400_000;
    expect(days).toBe(6);
    expect(api.recovery.mock.calls[0]).toEqual(api.summary.mock.calls[0]);
  });

  it('prints each branch’s ratings under its name, and the total is the sum over the count, not the mean of means', async () => {
    const host = await render();

    expect(rows(host, 'review-report-rating-row')).toEqual([
      ['Chilonzor', '10', '4.0', '3', '0 · 0 · 2 · 6 · 2'],
      ['Yunusobod', '2', '2.0', '1', '0 · 2 · 0 · 0 · 0'],
    ]);
    // (40 + 4) / (10 + 2) = 3.666..., which rounds to 3.7; the mean of 4.0 and 2.0 would read 3.0.
    expect(rows(host, 'review-report-rating-total')).toEqual([
      ['All branches', '12', '3.7', '4', '0 · 2 · 2 · 6 · 2'],
    ]);
  });

  it('counts tags by category and sentiment under the branch, naming the courier as a topic', async () => {
    const host = await render();

    expect(rows(host, 'review-report-tag-row')).toEqual([
      ['Chilonzor', 'Food', '5', '1'],
      ['Chilonzor', 'Courier', '0', '2'],
    ]);
  });

  it('shows the case clocks, the breach rate, the outcomes and the remedy money per currency', async () => {
    const host = await render();

    expect(rows(host, 'review-report-case-row')).toEqual([
      ['Chilonzor', '4', '3', '10 min / 1 h 30 min', '2 h 00 min / —', '1 (25.0%)', '2'],
    ]);
    expect(
      [...host.querySelectorAll('[data-testid="review-report-outcome"]')].map((li) =>
        (li.textContent ?? '').replace(/\s+/g, ' ').trim(),
      ),
    ).toEqual(['Remedy granted: 3', 'Nothing to act on: 1']);
    const money = (
      host.querySelector('[data-testid="review-report-remedy-money"]')?.textContent ?? ''
    )
      .replace(/[  ]/g, ' ')
      .replace(/\s+/g, ' ');
    expect(money).toContain('4 000 000');
  });

  it('asks again over a new range', async () => {
    const host = await render();
    expect(api.summary).toHaveBeenCalledTimes(1);

    const picker = fixture.debugElement.query((node) => node.name === 'q-date-range-picker');
    picker.triggerEventHandler('rangeChange', { start: '2026-09-01', end: '2026-09-30' });
    await settle();

    expect(api.summary).toHaveBeenCalledTimes(2);
    expect(api.summary.mock.calls[1][1]).toEqual({ from: '2026-09-01', to: '2026-09-30' });
    expect(host.querySelector('[data-testid="review-report"]')).not.toBeNull();
  });

  it('says there is nothing to show instead of printing a table of zeros', async () => {
    const host = await render({
      summary: { rows: [], tags: [], provenance: PROVENANCE },
      recovery: { rows: [], outcomes: [], remedies: [], provenance: PROVENANCE },
    });

    expect(host.querySelector('[data-testid="review-report-empty"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="review-report-ratings"]')).toBeNull();
  });

  it('tells a viewer without the reporting capability so, and offers no retry', async () => {
    const host = await render({
      summary: new ApiError('FORBIDDEN', 403, null, null),
    });

    expect(host.querySelector('[data-testid="review-report-denied"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="review-report-error"]')).toBeNull();
  });

  it('offers a retry after a failure and loads on the second try', async () => {
    const host = await render({ summary: new Error('network') });
    expect(host.querySelector('[data-testid="review-report-error"]')).not.toBeNull();

    api.summary.mockResolvedValue(SUMMARY);
    host.querySelector<HTMLButtonElement>('[data-testid="review-report-error"] button')!.click();
    await settle();

    expect(host.querySelector('[data-testid="review-report-ratings"]')).not.toBeNull();
  });

  it('does not call the endpoints without a location scope', async () => {
    const host = await render({ scope: null, denied: true });

    expect(api.summary).not.toHaveBeenCalled();
    expect(host.querySelector('[data-testid="review-report-denied"]')).not.toBeNull();
  });
});

describe('meanOf', () => {
  it('rounds the quotient half up to one decimal, and says nothing for no reviews', () => {
    expect(meanOf(44, 12)).toBe(3.7);
    expect(meanOf(9, 2)).toBe(4.5);
    expect(meanOf(0, 0)).toBeNull();
  });
});

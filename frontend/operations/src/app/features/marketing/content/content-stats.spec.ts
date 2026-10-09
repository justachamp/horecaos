import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { ContentApi, ContentStats } from './content-api';
import { ContentStatsPanel } from './content-stats';

const STATS: ContentStats = {
  clientReported: true,
  from: '2026-09-10',
  to: '2026-10-09',
  rows: [
    {
      revisionNo: 1,
      day: '2026-10-01',
      channelCode: 'web',
      impressions: 10,
      clicks: 2,
      dismissals: 0,
      completions: 0,
    },
    {
      revisionNo: 1,
      day: '2026-10-02',
      channelCode: 'tg',
      impressions: 5,
      clicks: 1,
      dismissals: 0,
      completions: 0,
    },
    {
      revisionNo: 2,
      day: '2026-10-03',
      channelCode: 'web',
      impressions: 7,
      clicks: 3,
      dismissals: 4,
      completions: 6,
    },
  ],
};

async function setup(result: () => Promise<ContentStats>): Promise<HTMLElement> {
  TestBed.configureTestingModule({
    providers: [
      { provide: ContentApi, useValue: { stats: result } },
      { provide: CurrentBrand, useValue: { scope: signal({ tenantId: 't', brandId: 'b' }) } },
    ],
  });
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(ContentStatsPanel);
  fixture.componentRef.setInput('itemId', 'item');
  fixture.detectChanges();
  await new Promise((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

describe('ContentStatsPanel (ADR 0157, Decision 10: client-reported counts)', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('says the counts are client-reported and not a measurement', async () => {
    const root = await setup(() => Promise.resolve(STATS));

    expect(root.querySelector('[data-testid="content-stats-note"]')?.textContent).toContain(
      'Anyone can inflate these numbers',
    );
  });

  it('adds the days and channels up to one line per version, newest first', async () => {
    const root = await setup(() => Promise.resolve(STATS));

    const rows = Array.from(root.querySelectorAll('[data-testid="content-stats-row"]')).map((row) =>
      Array.from(row.children).map((cell) => cell.textContent?.trim()),
    );
    expect(rows).toEqual([
      ['2', '7', '3', '4', '6'],
      ['1', '15', '3', '0', '0'],
    ]);
  });

  it('says nothing has been reported rather than drawing an empty table', async () => {
    const root = await setup(() => Promise.resolve({ ...STATS, rows: [] }));

    expect(root.querySelector('[data-testid="content-stats-empty"]')).toBeTruthy();
    expect(root.querySelector('table')).toBeNull();
  });

  it('shows a refusal as a sentence', async () => {
    const root = await setup(() =>
      Promise.reject(new ApiError('INSUFFICIENT_CAPABILITY', 403, null, null)),
    );

    expect(root.querySelector('[role="alert"]')?.textContent?.trim()).not.toBe('');
  });
});

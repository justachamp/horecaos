import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ReportsFilterState } from './reports-filter-state';

// A Friday, deliberately: the comparison-range test below only proves
// something if the shifted date lands on the same weekday, and Friday to
// Friday is where a naive "minus seven calendar days that happens to be a
// month" bug would still look right for `today` but not for `month`.
const FIXED_NOW = new Date('2026-08-21T10:00:00Z'); // 15:00 Asia/Tashkent (UTC+5)

describe('ReportsFilterState', () => {
  let state: ReportsFilterState;

  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(FIXED_NOW);
    TestBed.configureTestingModule({ providers: [ReportsFilterState] });
    state = TestBed.inject(ReportsFilterState);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('defaults to today, not month-to-date', () => {
    expect(state.period()).toBe('today');
    expect(state.range()).toEqual({ from: '2026-08-21', to: '2026-08-21' });
  });

  it('resolves yesterday as a single day', () => {
    state.setPeriod('yesterday');
    expect(state.range()).toEqual({ from: '2026-08-20', to: '2026-08-20' });
  });

  it('resolves 7 days as a trailing week including today', () => {
    state.setPeriod('7d');
    expect(state.range()).toEqual({ from: '2026-08-15', to: '2026-08-21' });
  });

  it('resolves month as the calendar month to date', () => {
    state.setPeriod('month');
    expect(state.range()).toEqual({ from: '2026-08-01', to: '2026-08-21' });
  });

  it('compares "today" against the same weekday one week back, not yesterday', () => {
    // 2026-08-21 is a Friday; one week back is also a Friday.
    expect(state.comparisonRange()).toEqual({ from: '2026-08-14', to: '2026-08-14' });
  });

  it('compares "7 days" against the prior non-overlapping week', () => {
    state.setPeriod('7d');
    expect(state.comparisonRange()).toEqual({ from: '2026-08-08', to: '2026-08-14' });
  });

  it('shifts "month" back by a whole number of weeks, keeping weekday alignment', () => {
    // 21 days (Aug 1 -> Aug 21) needs ceil(21/7) = 3 weeks = 21 days back.
    state.setPeriod('month');
    expect(state.comparisonRange()).toEqual({ from: '2026-07-11', to: '2026-07-31' });
  });

  it('carries the fulfilment-type filter as a plain signal', () => {
    expect(state.fulfilmentType()).toBe('ALL');
    state.setFulfilmentType('DELIVERY');
    expect(state.fulfilmentType()).toBe('DELIVERY');
  });
});

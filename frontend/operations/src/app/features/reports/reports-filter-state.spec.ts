import { ApplicationRef } from '@angular/core';
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
    history.replaceState(null, '', '/statistics/overview');
    TestBed.configureTestingModule({ providers: [ReportsFilterState] });
    state = TestBed.inject(ReportsFilterState);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  /** Flushes the pending `effect()` that writes filter state to the URL. */
  function flush(): void {
    TestBed.inject(ApplicationRef).tick();
  }

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

  it('carries the payment-method filter, empty meaning every method (P39)', () => {
    expect(state.paymentMethodCodes()).toEqual([]);
    state.setPaymentMethodCodes(['CASH', 'CARD']);
    expect(state.paymentMethodCodes()).toEqual(['CASH', 'CARD']);
  });

  // ----------------------------------------------------------- wave P27 (7.1d)

  it('carries the branch and legal-entity axes, empty meaning every one', () => {
    expect(state.locationIds()).toEqual([]);
    expect(state.legalEntityIds()).toEqual([]);
    state.setLocationIds(['loc-1', 'loc-2']);
    state.setLegalEntityIds(['entity-1']);
    expect(state.locationIds()).toEqual(['loc-1', 'loc-2']);
    expect(state.legalEntityIds()).toEqual(['entity-1']);
  });

  it('resolves a custom range instead of a preset once one is set', () => {
    state.setCustomRange({ from: '2026-08-01', to: '2026-08-10' });
    expect(state.period()).toBe('custom');
    expect(state.range()).toEqual({ from: '2026-08-01', to: '2026-08-10' });
  });

  it('steps the active window back and forward by its own length', () => {
    state.setPeriod('7d'); // 2026-08-15..2026-08-21, 7 days
    state.stepPeriod(-1);
    expect(state.period()).toBe('custom');
    expect(state.range()).toEqual({ from: '2026-08-08', to: '2026-08-14' });

    state.stepPeriod(1);
    expect(state.range()).toEqual({ from: '2026-08-15', to: '2026-08-21' });
  });

  it('steps a single-day period by one day', () => {
    state.setPeriod('today');
    state.stepPeriod(-1);
    expect(state.range()).toEqual({ from: '2026-08-20', to: '2026-08-20' });
  });

  it('reports whether any secondary filter is set, for the reset control', () => {
    expect(state.hasAnySecondaryFilter()).toBe(false);
    state.setFulfilmentType('DELIVERY');
    expect(state.hasAnySecondaryFilter()).toBe(true);
    state.resetFilters();
    expect(state.hasAnySecondaryFilter()).toBe(false);
    expect(state.fulfilmentType()).toBe('ALL');
  });

  it('writes the active filters into the URL so a filtered view is a shareable link', () => {
    state.setPeriod('7d');
    state.setFulfilmentType('DELIVERY');
    state.setLocationIds(['loc-1']);
    flush();

    const params = new URLSearchParams(window.location.search);
    expect(params.get('rp_period')).toBe('7d');
    expect(params.get('rp_fulfilment')).toBe('DELIVERY');
    expect(params.get('rp_locations')).toBe('loc-1');
  });

  it('reads a previously-written URL back into the same filter state', () => {
    history.replaceState(
      null,
      '',
      '/statistics/overview?rp_period=custom&rp_from=2026-08-01&rp_to=2026-08-05&rp_fulfilment=PICKUP&rp_locations=loc-9%2Cloc-8',
    );
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [ReportsFilterState] });
    const restored = TestBed.inject(ReportsFilterState);

    expect(restored.period()).toBe('custom');
    expect(restored.range()).toEqual({ from: '2026-08-01', to: '2026-08-05' });
    expect(restored.fulfilmentType()).toBe('PICKUP');
    expect(restored.locationIds()).toEqual(['loc-9', 'loc-8']);
  });
});

import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { DateRange, DateRangePicker } from './date-range-picker';

// A Wednesday, safely mid-month so "this month" and "last 7 days" never cross
// a month boundary the test would have to special-case.
const FIXED_NOW = new Date(2026, 8, 16, 10, 0, 0);

function render(): ReturnType<typeof TestBed.createComponent<DateRangePicker>> {
  const fixture = TestBed.createComponent(DateRangePicker);
  fixture.detectChanges();
  return fixture;
}

function click(
  fixture: ReturnType<typeof TestBed.createComponent<DateRangePicker>>,
  testid: string,
): void {
  (
    (fixture.nativeElement as HTMLElement).querySelector(
      `[data-testid="${testid}"]`,
    ) as HTMLButtonElement
  ).click();
}

describe('DateRangePicker', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(FIXED_NOW);
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
  });

  afterEach(() => vi.useRealTimers());

  it('sets today as a single-day range', () => {
    const fixture = render();
    let emitted: DateRange | undefined;
    fixture.componentInstance.rangeChange.subscribe((r) => (emitted = r));

    click(fixture, 'q-date-range-picker-preset-today');

    expect(emitted).toEqual({ start: '2026-09-16', end: '2026-09-16' });
  });

  it('sets yesterday as a single-day range', () => {
    const fixture = render();
    let emitted: DateRange | undefined;
    fixture.componentInstance.rangeChange.subscribe((r) => (emitted = r));

    click(fixture, 'q-date-range-picker-preset-yesterday');

    expect(emitted).toEqual({ start: '2026-09-15', end: '2026-09-15' });
  });

  it('sets the last 7 days inclusive of today', () => {
    const fixture = render();
    let emitted: DateRange | undefined;
    fixture.componentInstance.rangeChange.subscribe((r) => (emitted = r));

    click(fixture, 'q-date-range-picker-preset-last7Days');

    expect(emitted).toEqual({ start: '2026-09-10', end: '2026-09-16' });
  });

  it('sets this month from the 1st through today', () => {
    const fixture = render();
    let emitted: DateRange | undefined;
    fixture.componentInstance.rangeChange.subscribe((r) => (emitted = r));

    click(fixture, 'q-date-range-picker-preset-thisMonth');

    expect(emitted).toEqual({ start: '2026-09-01', end: '2026-09-16' });
  });

  it('accepts a hand-typed custom range', () => {
    const fixture = render();
    let emitted: DateRange | undefined;
    fixture.componentInstance.rangeChange.subscribe((r) => (emitted = r));
    const startField = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>(
      '[data-testid="q-date-range-picker-start"]',
    )!;

    startField.value = '2026-08-01';
    startField.dispatchEvent(new Event('input'));

    expect(emitted?.start).toBe('2026-08-01');
  });

  it('swaps a custom range typed backwards so start never exceeds end', () => {
    const fixture = render();
    fixture.componentRef.setInput('end', '2026-08-01');
    fixture.detectChanges();
    let emitted: DateRange | undefined;
    fixture.componentInstance.rangeChange.subscribe((r) => (emitted = r));
    const startField = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>(
      '[data-testid="q-date-range-picker-start"]',
    )!;

    startField.value = '2026-08-15';
    startField.dispatchEvent(new Event('input'));

    expect(emitted).toEqual({ start: '2026-08-01', end: '2026-08-15' });
  });
});

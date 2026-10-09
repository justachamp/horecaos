import { describe, expect, it } from 'vitest';

import {
  addDays,
  endDay,
  endInstant,
  scheduleModeOf,
  startDay,
  startInstant,
  today,
  windowIsBackwards,
} from './content-schedule';

describe('content schedule (whole days in the operator’s timezone)', () => {
  it('starts a window at the midnight that begins its first day', () => {
    const instant = startInstant('2026-10-12')!;

    expect(new Date(instant).getTime()).toBe(new Date(2026, 9, 12, 0, 0, 0, 0).getTime());
    expect(startDay(instant)).toBe('2026-10-12');
  });

  it('ends a window that includes a day at the next midnight, so "until the 16th" keeps the whole 16th', () => {
    const instant = endInstant('2026-10-16')!;

    expect(new Date(instant).getTime()).toBe(new Date(2026, 9, 17, 0, 0, 0, 0).getTime());
    expect(endDay(instant)).toBe('2026-10-16');
  });

  it('carries a month and a year boundary', () => {
    expect(endDay(endInstant('2026-12-31'))).toBe('2026-12-31');
    expect(new Date(endInstant('2026-12-31')!).getTime()).toBe(new Date(2027, 0, 1).getTime());
  });

  it('shows a saved end that is not a midnight by the day it falls in', () => {
    const afternoon = new Date(2026, 9, 16, 15, 30).toISOString();

    expect(endDay(afternoon)).toBe('2026-10-16');
  });

  it('reads no instant as no day, and a malformed day as no instant', () => {
    expect(startDay(null)).toBeNull();
    expect(endDay(null)).toBeNull();
    expect(startInstant('soon')).toBeNull();
    expect(endInstant('')).toBeNull();
  });

  it('names the mode of a saved window', () => {
    const a = new Date(2026, 9, 12).toISOString();
    const b = new Date(2026, 9, 17).toISOString();
    expect(scheduleModeOf(null, null)).toBe('always');
    expect(scheduleModeOf(a, b)).toBe('between');
    expect(scheduleModeOf(a, null)).toBe('from');
    expect(scheduleModeOf(null, b)).toBe('between');
  });

  it('knows a window that ends before, or exactly when, it starts', () => {
    const a = new Date(2026, 9, 12).toISOString();
    const b = new Date(2026, 9, 17).toISOString();
    expect(windowIsBackwards(a, b)).toBe(false);
    expect(windowIsBackwards(b, a)).toBe(true);
    expect(windowIsBackwards(a, a)).toBe(true);
    expect(windowIsBackwards(a, null)).toBe(false);
  });

  it('adds days across a month', () => {
    expect(addDays('2026-10-28', 6)).toBe('2026-11-03');
    expect(today(new Date(2026, 9, 9, 23, 59))).toBe('2026-10-09');
  });
});

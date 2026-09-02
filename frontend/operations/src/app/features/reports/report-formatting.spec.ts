import { describe, expect, it } from 'vitest';

import {
  ddmm,
  ddmmyyyy,
  formatCount,
  formatDeltaPercent,
  formatSecondsDuration,
  formatShare,
  formatSignedMinutes,
  median,
} from './report-formatting';

describe('ddmm', () => {
  it('renders a LocalDate string as DD.MM with no zone conversion', () => {
    expect(ddmm('2026-08-21')).toBe('21.08');
  });
});

describe('ddmmyyyy', () => {
  it('renders a LocalDate string as DD.MM.YYYY', () => {
    expect(ddmmyyyy('2026-01-05')).toBe('05.01.2026');
  });
});

describe('formatSecondsDuration', () => {
  it('renders under an hour as mm:ss', () => {
    expect(formatSecondsDuration(75)).toBe('1:15');
    expect(formatSecondsDuration(0)).toBe('0:00');
  });

  it('renders an hour or more as h:mm:ss', () => {
    expect(formatSecondsDuration(3661)).toBe('1:01:01');
  });

  it('never goes negative on a bad input', () => {
    expect(formatSecondsDuration(-30)).toBe('0:00');
  });
});

describe('formatSignedMinutes', () => {
  it('signs a positive duration', () => {
    expect(formatSignedMinutes(840, 'мин')).toBe('+14 мин');
  });

  it('signs a negative duration with a real minus, not a zero', () => {
    expect(formatSignedMinutes(-300, 'мин')).toBe('−5 мин');
  });

  it('renders exactly zero with no sign', () => {
    expect(formatSignedMinutes(0, 'мин')).toBe('0 мин');
  });
});

describe('formatShare', () => {
  it('renders a percentage share', () => {
    expect(formatShare(12, 293)).toBe('4%');
  });

  it('renders — for a zero total, never 0%', () => {
    expect(formatShare(0, 0)).toBe('—');
  });
});

describe('median', () => {
  it('is null for an empty list, never zero', () => {
    expect(median([])).toBeNull();
  });

  it('is the middle value for an odd-length list', () => {
    expect(median([5, 1, 3])).toBe(3);
  });

  it('averages the two middle values for an even-length list', () => {
    expect(median([10, 20, 30, 40])).toBe(25);
  });
});

describe('formatCount', () => {
  it('groups thousands with a non-breaking space', () => {
    expect(formatCount(12_400)).toBe('12 400');
  });

  it('signs a negative count with a real minus', () => {
    expect(formatCount(-7)).toBe('−7');
  });
});

describe('formatDeltaPercent', () => {
  it('computes a signed percentage change', () => {
    expect(formatDeltaPercent(120, 100)).toBe('+20%');
    expect(formatDeltaPercent(80, 100)).toBe('−20%');
  });

  it('is null against a zero comparison — nothing to divide by', () => {
    expect(formatDeltaPercent(50, 0)).toBeNull();
  });
});

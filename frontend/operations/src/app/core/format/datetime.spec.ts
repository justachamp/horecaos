import { describe, expect, it } from 'vitest';

import {
  formatClock,
  formatDate,
  formatDateTime,
  formatDuration,
  formatTime,
  zonedTimeToInstant,
} from './datetime';

const TASHKENT = 'Asia/Tashkent';
/** 2026-08-21T14:34:05Z is 19:34:05 in Tashkent (UTC+5, no DST). */
const INSTANT = new Date('2026-08-21T14:34:05Z');

describe('date and time formatting', () => {
  it('renders the tenant timezone, not the browser one', () => {
    // The failure this prevents: a manager checking a Tashkent branch from a
    // laptop set to Europe/London sees every order an hour early and starts
    // asking the kitchen why nothing is ready.
    expect(formatTime(INSTANT, TASHKENT)).toBe('19:34');
    expect(formatTime(INSTANT, 'Europe/London')).toBe('15:34');
  });

  it('uses a 24-hour clock and DD.MM dates', () => {
    expect(formatDateTime(INSTANT, TASHKENT)).toBe('21.08 19:34');
    expect(formatDate(INSTANT, TASHKENT)).toBe('21.08.2026');
  });

  it('renders the seconds stamp a queue needs', () => {
    // A queue that silently stopped updating looks identical to a quiet shift.
    expect(formatClock(INSTANT, TASHKENT)).toBe('19:34:05');
  });

  it('never renders midnight as 12 or as 24', () => {
    const midnight = new Date('2026-08-21T19:00:00Z'); // 00:00 in Tashkent, next day
    expect(formatTime(midnight, TASHKENT)).toBe('00:00');
  });

  it('zero-pads the minutes past an hour so a column stays aligned', () => {
    const units = { hour: 'ч', minute: 'мин' };
    expect(formatDuration(12, units)).toBe('12 мин');
    expect(formatDuration(64, units)).toBe('1 ч 04 мин');
    expect(formatDuration(74, units)).toBe('1 ч 14 мин');
    expect(formatDuration(60, units)).toBe('1 ч 00 мин');
  });

  it('clamps a negative duration rather than rendering a negative wait', () => {
    expect(formatDuration(-5, { hour: 'ч', minute: 'мин' })).toBe('0 мин');
  });

  describe('zonedTimeToInstant', () => {
    it('is the inverse of formatTime for a whole hour, in the tenant zone not the browser one', () => {
      const instant = zonedTimeToInstant('2026-08-21', 19.5, TASHKENT);
      expect(instant.toISOString()).toBe('2026-08-21T14:30:00.000Z');
      expect(formatTime(instant, TASHKENT)).toBe('19:30');
    });

    it('rolls an hour at or past 24 into the next calendar day — the wrap a booking window past midnight needs', () => {
      // 25.5 hours from 2026-08-21 00:00 Tashkent is 2026-08-22 01:30 Tashkent.
      const instant = zonedTimeToInstant('2026-08-21', 25.5, TASHKENT);
      expect(formatDate(instant, TASHKENT)).toBe('22.08.2026');
      expect(formatTime(instant, TASHKENT)).toBe('01:30');
    });

    it('rolls a negative hour into the previous calendar day', () => {
      const instant = zonedTimeToInstant('2026-08-21', -1, TASHKENT);
      expect(formatDate(instant, TASHKENT)).toBe('20.08.2026');
      expect(formatTime(instant, TASHKENT)).toBe('23:00');
    });

    it('answers a different UTC instant for the same wall clock in a different zone', () => {
      const inTashkent = zonedTimeToInstant('2026-08-21', 19, TASHKENT);
      const inLondon = zonedTimeToInstant('2026-08-21', 19, 'Europe/London');
      expect(inTashkent.getTime()).not.toBe(inLondon.getTime());
      expect(formatTime(inLondon, 'Europe/London')).toBe('19:00');
    });
  });
});

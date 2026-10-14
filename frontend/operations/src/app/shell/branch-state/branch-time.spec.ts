import { describe, expect, it } from 'vitest';

import { ModeBindingView } from '../../features/settings/locations/locations-api';
import { clockIn, endOfServiceToday, isServiceOpenNow } from './branch-time';

const TASHKENT = 'Asia/Tashkent'; // UTC+5, no daylight saving
const NEW_YORK = 'America/New_York';

function binding(
  rules: readonly { dayOfWeek: number; opensAt: string; closesAt: string }[],
  exceptions: ModeBindingView['exceptions'] = [],
): ModeBindingView {
  return {
    fulfillmentMode: 'DELIVERY',
    scheduleId: 's1',
    scheduleName: 'Main',
    acceptsScheduledOrders: false,
    sharedWithLocationCount: 1,
    rules,
    exceptions,
    scheduleVersion: 1,
  };
}

// 2026-10-12 is a Monday. 12:00Z is 17:00 in Tashkent.
const MONDAY_NOON_UTC = new Date('2026-10-12T12:00:00Z');

describe('clockIn', () => {
  it('reads an instant on the branch’s own wall clock, 24-hour', () => {
    expect(clockIn(new Date('2026-10-12T16:30:00Z'), TASHKENT)).toBe('21:30');
    expect(clockIn(new Date('2026-10-12T16:30:00Z'), 'UTC')).toBe('16:30');
  });

  it('writes midnight as 00:00, not 24:00', () => {
    expect(clockIn(new Date('2026-10-12T19:00:00Z'), TASHKENT)).toBe('00:00');
  });
});

describe('endOfServiceToday', () => {
  const weekdays = binding([
    { dayOfWeek: 1, opensAt: '10:00:00', closesAt: '23:00:00' },
    { dayOfWeek: 2, opensAt: '10:00:00', closesAt: '22:00:00' },
  ]);

  it('is the closing time of today’s last window, as an instant in the branch’s zone', () => {
    const end = endOfServiceToday([weekdays], MONDAY_NOON_UTC, TASHKENT);

    expect(end?.toISOString()).toBe('2026-10-12T18:00:00.000Z'); // 23:00 in Tashkent
  });

  it('takes the latest of several windows, whatever order they are listed in', () => {
    const split = binding([
      { dayOfWeek: 1, opensAt: '18:00', closesAt: '23:30' },
      { dayOfWeek: 1, opensAt: '10:00', closesAt: '14:00' },
    ]);

    expect(clockIn(endOfServiceToday([split], MONDAY_NOON_UTC, TASHKENT)!, TASHKENT)).toBe('23:30');
  });

  it('uses the weekday where the branch is, not where the reader is', () => {
    // 2026-10-12T20:00Z is already Tuesday 01:00 in Tashkent.
    const end = endOfServiceToday([weekdays], new Date('2026-10-12T20:00:00Z'), TASHKENT);

    expect(clockIn(end!, TASHKENT)).toBe('22:00');
  });

  it('is null when the last window has already ended, so a preset that is in the past is not offered', () => {
    const late = new Date('2026-10-12T18:30:00Z'); // 23:30 in Tashkent
    expect(endOfServiceToday([weekdays], late, TASHKENT)).toBeNull();
  });

  it('is null on a day with no window', () => {
    const sunday = new Date('2026-10-11T08:00:00Z');
    expect(endOfServiceToday([weekdays], sunday, TASHKENT)).toBeNull();
  });

  it('lets a date exception replace the weekly windows for its day', () => {
    const shortDay = binding(
      [{ dayOfWeek: 1, opensAt: '10:00', closesAt: '23:00' }],
      [{ date: '2026-10-12', closedAllDay: false, opensAt: '12:00', closesAt: '19:00' }],
    );
    const closedDay = binding(
      [{ dayOfWeek: 1, opensAt: '10:00', closesAt: '23:00' }],
      [{ date: '2026-10-12', closedAllDay: true, opensAt: null, closesAt: null }],
    );

    expect(clockIn(endOfServiceToday([shortDay], MONDAY_NOON_UTC, TASHKENT)!, TASHKENT)).toBe(
      '19:00',
    );
    expect(endOfServiceToday([closedDay], MONDAY_NOON_UTC, TASHKENT)).toBeNull();
  });

  it('leaves out a window that runs past midnight: end of day is a time on today’s date', () => {
    const overnight = binding([{ dayOfWeek: 1, opensAt: '20:00', closesAt: '02:00' }]);

    expect(endOfServiceToday([overnight], MONDAY_NOON_UTC, TASHKENT)).toBeNull();
  });

  it('is right across a daylight-saving change: New York on the Sunday the clocks go back', () => {
    // 2026-11-01 is the Sunday US clocks go back; 06:00Z is 01:00 EST (after the change) that morning.
    const sundayRule = binding([{ dayOfWeek: 7, opensAt: '10:00', closesAt: '22:00' }]);
    const end = endOfServiceToday([sundayRule], new Date('2026-11-01T14:00:00Z'), NEW_YORK);

    expect(end?.toISOString()).toBe('2026-11-02T03:00:00.000Z'); // 22:00 EST = 03:00Z
  });
});

describe('isServiceOpenNow', () => {
  const day = binding([{ dayOfWeek: 1, opensAt: '10:00:00', closesAt: '23:00:00' }]);

  it('is true inside today’s window and false outside it', () => {
    expect(isServiceOpenNow([day], MONDAY_NOON_UTC, TASHKENT)).toBe(true); // 17:00
    expect(isServiceOpenNow([day], new Date('2026-10-12T04:00:00Z'), TASHKENT)).toBe(false); // 09:00
    expect(isServiceOpenNow([day], new Date('2026-10-12T18:00:00Z'), TASHKENT)).toBe(false); // 23:00 sharp
  });

  it('is false on a day with no window, and false with no schedule at all', () => {
    expect(isServiceOpenNow([day], new Date('2026-10-13T12:00:00Z'), TASHKENT)).toBe(false);
    expect(isServiceOpenNow([], MONDAY_NOON_UTC, TASHKENT)).toBe(false);
  });

  it('honours a date exception: closed all day, or its own hours', () => {
    const closed = binding(
      [{ dayOfWeek: 1, opensAt: '10:00', closesAt: '23:00' }],
      [{ date: '2026-10-12', closedAllDay: true, opensAt: null, closesAt: null }],
    );
    const lateStart = binding(
      [{ dayOfWeek: 1, opensAt: '10:00', closesAt: '23:00' }],
      [{ date: '2026-10-12', closedAllDay: false, opensAt: '18:00', closesAt: '23:00' }],
    );

    expect(isServiceOpenNow([closed], MONDAY_NOON_UTC, TASHKENT)).toBe(false);
    expect(isServiceOpenNow([lateStart], MONDAY_NOON_UTC, TASHKENT)).toBe(false); // 17:00 < 18:00
  });

  it('counts a window that runs past midnight from its opening time onward', () => {
    const overnight = binding([{ dayOfWeek: 1, opensAt: '20:00', closesAt: '02:00' }]);

    expect(isServiceOpenNow([overnight], new Date('2026-10-12T16:00:00Z'), TASHKENT)).toBe(true); // 21:00
  });
});

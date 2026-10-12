import type { ContentPopupDaily } from '../types/content.types';
import {
  capAllows,
  popupStorageKey,
  readShownRecord,
  withinDailyWindow,
  writeShownRecord,
} from './popup-schedule';

/** Local-clock dates: 2026-10-09 is a Friday. */
const at = (day: number, hour: number, minute = 0, month = 9) =>
  new Date(2026, month, day, hour, minute, 0, 0);

const ALL_DAYS = [1, 2, 3, 4, 5, 6, 7];
const window = (
  from: string,
  until: string,
  daysOfWeek: number[] = ALL_DAYS,
): ContentPopupDaily => ({
  from,
  until,
  daysOfWeek,
});

describe('withinDailyWindow: the device-local clock decides', () => {
  it('2026-10-09 really is a Friday (the fixtures below depend on it)', () => {
    expect(at(9, 12).getDay()).toBe(5);
  });

  it('no window means always', () => {
    expect(withinDailyWindow(undefined, at(9, 3))).toBe(true);
  });

  it('opens at from (inclusive) and closes at until (exclusive)', () => {
    const daily = window('09:00', '18:00');
    expect(withinDailyWindow(daily, at(9, 8, 59))).toBe(false);
    expect(withinDailyWindow(daily, at(9, 9, 0))).toBe(true);
    expect(withinDailyWindow(daily, at(9, 17, 59))).toBe(true);
    expect(withinDailyWindow(daily, at(9, 18, 0))).toBe(false);
  });

  it('only on the listed days, 1 = Monday to 7 = Sunday', () => {
    expect(withinDailyWindow(window('09:00', '18:00', [1]), at(9, 12))).toBe(false); // Friday
    expect(withinDailyWindow(window('09:00', '18:00', [5]), at(9, 12))).toBe(true);
    expect(withinDailyWindow(window('09:00', '18:00', [7]), at(11, 12))).toBe(true); // Sunday
    expect(withinDailyWindow(window('09:00', '18:00', [1]), at(12, 12))).toBe(true); // Monday
  });

  it('a window that runs past midnight is open on both sides of it', () => {
    const daily = window('22:00', '02:00');
    expect(withinDailyWindow(daily, at(9, 21, 59))).toBe(false);
    expect(withinDailyWindow(daily, at(9, 22, 0))).toBe(true);
    expect(withinDailyWindow(daily, at(9, 23, 30))).toBe(true);
    expect(withinDailyWindow(daily, at(10, 1, 59))).toBe(true);
    expect(withinDailyWindow(daily, at(10, 2, 0))).toBe(false);
  });

  it('the small hours after midnight belong to the day the window opened on', () => {
    const fridayNight = window('22:00', '02:00', [5]);
    expect(withinDailyWindow(fridayNight, at(9, 23))).toBe(true); // Friday 23:00
    expect(withinDailyWindow(fridayNight, at(10, 1))).toBe(true); // Saturday 01:00, still Friday's
    expect(withinDailyWindow(fridayNight, at(9, 1))).toBe(false); // Friday 01:00 is Thursday's
    expect(withinDailyWindow(fridayNight, at(10, 23))).toBe(false); // Saturday 23:00
  });

  it('Sunday night runs into Monday morning', () => {
    const sundayNight = window('23:00', '01:00', [7]);
    expect(withinDailyWindow(sundayNight, at(11, 23, 30))).toBe(true); // Sunday
    expect(withinDailyWindow(sundayNight, at(12, 0, 30))).toBe(true); // Monday
    expect(withinDailyWindow(sundayNight, at(12, 23, 30))).toBe(false); // Monday night
  });

  it('a window that opens and closes at the same time is empty', () => {
    expect(withinDailyWindow(window('10:00', '10:00'), at(9, 10))).toBe(false);
  });
});

describe('capAllows: the frequency cap is a device-local hint', () => {
  const record = (lastShownAt: Date, count = 1) => ({ count, lastShownAt: lastShownAt.getTime() });
  const cap = (mode: 'ONCE_EVER' | 'ONCE_PER_DAY' | 'EVERY_N_DAYS', n = 1) => ({ mode, n });

  it.each(['ONCE_EVER', 'ONCE_PER_DAY', 'EVERY_N_DAYS'] as const)(
    '%s allows a popup never shown on this device',
    (mode) => {
      expect(capAllows(cap(mode, 3), null, at(9, 12))).toBe(true);
    },
  );

  it('ONCE_EVER never allows it again', () => {
    expect(capAllows(cap('ONCE_EVER'), record(at(1, 8, 0, 0)), at(9, 12))).toBe(false);
    expect(capAllows(cap('ONCE_EVER'), record(at(9, 8)), at(9, 12))).toBe(false);
  });

  it('ONCE_PER_DAY allows it once per local calendar day', () => {
    expect(capAllows(cap('ONCE_PER_DAY'), record(at(9, 0, 5)), at(9, 23, 59))).toBe(false);
    expect(capAllows(cap('ONCE_PER_DAY'), record(at(8, 23, 59)), at(9, 0, 1))).toBe(true);
  });

  it('EVERY_N_DAYS waits until n local calendar days have passed', () => {
    const shown = record(at(6, 18)); // Tuesday evening
    expect(capAllows(cap('EVERY_N_DAYS', 3), shown, at(8, 9))).toBe(false); // 2 days later
    expect(capAllows(cap('EVERY_N_DAYS', 3), shown, at(9, 0, 1))).toBe(true); // 3 days later
    expect(capAllows(cap('EVERY_N_DAYS', 1), shown, at(6, 23))).toBe(false); // same day
    expect(capAllows(cap('EVERY_N_DAYS', 1), shown, at(7, 0, 1))).toBe(true);
  });

  it('counts days across a month boundary', () => {
    const shown = record(new Date(2026, 8, 30, 20, 0)); // 30 September
    expect(capAllows(cap('EVERY_N_DAYS', 2), shown, at(1, 12))).toBe(false); // 1 October
    expect(capAllows(cap('EVERY_N_DAYS', 2), shown, at(2, 12))).toBe(true);
  });

  it('a record from the future (the clock was wrong) does not lock the popup out for good', () => {
    const future = record(at(20, 12));
    expect(capAllows(cap('ONCE_PER_DAY'), future, at(9, 12))).toBe(true);
    expect(capAllows(cap('EVERY_N_DAYS', 5), future, at(9, 12))).toBe(true);
  });
});

describe('the shown record in localStorage', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => vi.restoreAllMocks());

  it('is keyed by item AND revision, so editing the item (a new revision) resets the cap', () => {
    expect(popupStorageKey('p-1', 3)).not.toBe(popupStorageKey('p-1', 4));
    expect(popupStorageKey('p-1', 3)).not.toBe(popupStorageKey('p-2', 3));
    expect(popupStorageKey('p-1', 3)).toContain('p-1');
    expect(popupStorageKey('p-1', 3)).toContain('3');
  });

  it('reads nothing where nothing was written', () => {
    expect(readShownRecord('p-1', 1)).toBeNull();
  });

  it('writes the time and counts the shows', () => {
    writeShownRecord('p-1', 1, at(9, 12));
    writeShownRecord('p-1', 1, at(10, 12));

    expect(readShownRecord('p-1', 1)).toEqual({ count: 2, lastShownAt: at(10, 12).getTime() });
  });

  it('a new revision of the same item starts from nothing', () => {
    writeShownRecord('p-1', 1, at(9, 12));

    expect(readShownRecord('p-1', 2)).toBeNull();
  });

  it('reads garbage as nothing', () => {
    localStorage.setItem(popupStorageKey('p-1', 1), '{not json');
    expect(readShownRecord('p-1', 1)).toBeNull();
    localStorage.setItem(popupStorageKey('p-1', 1), JSON.stringify({ count: 'x' }));
    expect(readShownRecord('p-1', 1)).toBeNull();
    localStorage.setItem(popupStorageKey('p-1', 1), '42');
    expect(readShownRecord('p-1', 1)).toBeNull();
  });

  it('does not throw when reading storage throws, and reads nothing', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError');
    });

    expect(() => readShownRecord('p-1', 1)).not.toThrow();
    expect(readShownRecord('p-1', 1)).toBeNull();
  });

  it('does not throw when writing storage throws', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('quota', 'QuotaExceededError');
    });

    expect(() => writeShownRecord('p-1', 1, at(9, 12))).not.toThrow();
  });
});

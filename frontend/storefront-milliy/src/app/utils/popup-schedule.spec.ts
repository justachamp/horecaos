import type { ContentPopup, PopupDaily } from '../types/content.types';
import {
  capAllows,
  isWithinDaily,
  mayShowPopup,
  popupStorageKey,
  readCapState,
  recordPopupShown,
  resetPopupMemoryForTests,
  writeCapState,
} from './popup-schedule';

// Every Date below is built from local components: the window is evaluated on the device's own
// clock, so the specs must not depend on the zone they run in.
// 2026-10-05 is a Monday, 2026-10-09 a Friday, 2026-10-10 a Saturday, 2026-10-11 a Sunday.
const at = (day: number, hour: number, minute = 0) => new Date(2026, 9, day, hour, minute);

const WORKDAY: PopupDaily = { from: '09:00', until: '18:00', daysOfWeek: [1, 2, 3, 4, 5, 6, 7] };

function popup(overrides: Partial<ContentPopup> = {}): ContentPopup {
  return {
    id: 'pop-1',
    revisionNo: 1,
    title: 'Hello',
    textLocale: 'uz-Latn',
    link: { type: 'NONE' },
    showAfterSeconds: 3,
    cap: { mode: 'ONCE_EVER', n: 1 },
    ...overrides,
  };
}

beforeEach(() => {
  localStorage.clear();
  resetPopupMemoryForTests();
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('isWithinDaily: the window is read on the device clock', () => {
  it('opens at "from" and closes at "until"', () => {
    expect(isWithinDaily(WORKDAY, at(9, 8, 59))).toBe(false);
    expect(isWithinDaily(WORKDAY, at(9, 9, 0))).toBe(true);
    expect(isWithinDaily(WORKDAY, at(9, 17, 59))).toBe(true);
    expect(isWithinDaily(WORKDAY, at(9, 18, 0))).toBe(false);
  });

  it('runs across midnight when "until" is earlier than "from"', () => {
    const night: PopupDaily = { from: '22:00', until: '02:00', daysOfWeek: [1, 2, 3, 4, 5, 6, 7] };

    expect(isWithinDaily(night, at(9, 21, 59))).toBe(false);
    expect(isWithinDaily(night, at(9, 22, 0))).toBe(true);
    expect(isWithinDaily(night, at(9, 23, 30))).toBe(true);
    expect(isWithinDaily(night, at(10, 0, 30))).toBe(true);
    expect(isWithinDaily(night, at(10, 1, 59))).toBe(true);
    expect(isWithinDaily(night, at(10, 2, 0))).toBe(false);
    expect(isWithinDaily(night, at(10, 12, 0))).toBe(false);
  });

  it('numbers the days from Monday = 1 to Sunday = 7', () => {
    const mondayOnly: PopupDaily = { ...WORKDAY, daysOfWeek: [1] };
    const sundayOnly: PopupDaily = { ...WORKDAY, daysOfWeek: [7] };

    expect(isWithinDaily(mondayOnly, at(5, 12))).toBe(true);
    expect(isWithinDaily(mondayOnly, at(9, 12))).toBe(false);
    expect(isWithinDaily(sundayOnly, at(11, 12))).toBe(true);
    expect(isWithinDaily(sundayOnly, at(5, 12))).toBe(false);
  });

  it('counts the small hours after midnight to the day the window started', () => {
    const fridayNight: PopupDaily = { from: '22:00', until: '02:00', daysOfWeek: [5] };

    expect(isWithinDaily(fridayNight, at(9, 23, 0))).toBe(true); // Friday evening
    expect(isWithinDaily(fridayNight, at(10, 1, 0))).toBe(true); // Saturday 01:00 is still Friday's window
    expect(isWithinDaily(fridayNight, at(9, 1, 0))).toBe(false); // Friday 01:00 belongs to Thursday's
    expect(isWithinDaily(fridayNight, at(10, 23, 0))).toBe(false); // Saturday evening
  });

  it('is never open for a window whose bounds are not HH:mm', () => {
    expect(isWithinDaily({ ...WORKDAY, from: 'soon' }, at(9, 12))).toBe(false);
    expect(isWithinDaily({ ...WORKDAY, until: '25:00' }, at(9, 12))).toBe(false);
  });
});

describe('capAllows: the frequency cap is a hint the device enforces', () => {
  it('shows a pop-up that has never been shown, under any mode', () => {
    expect(capAllows({ mode: 'ONCE_EVER', n: 1 }, null, at(9, 12))).toBe(true);
    expect(capAllows({ mode: 'ONCE_PER_DAY', n: 1 }, null, at(9, 12))).toBe(true);
    expect(capAllows({ mode: 'EVERY_N_DAYS', n: 3 }, null, at(9, 12))).toBe(true);
  });

  it('ONCE_EVER never shows it again, however long ago', () => {
    const shown = { count: 1, lastShownAt: new Date(2020, 0, 1).getTime() };

    expect(capAllows({ mode: 'ONCE_EVER', n: 1 }, shown, at(9, 12))).toBe(false);
  });

  it('ONCE_PER_DAY means once per local calendar day, not once per 24 hours', () => {
    const cap = { mode: 'ONCE_PER_DAY', n: 1 } as const;
    const shownEarlyToday = { count: 1, lastShownAt: at(9, 0, 1).getTime() };
    const shownLateYesterday = { count: 1, lastShownAt: at(8, 23, 59).getTime() };

    expect(capAllows(cap, shownEarlyToday, at(9, 23, 59))).toBe(false);
    expect(capAllows(cap, shownLateYesterday, at(9, 0, 1))).toBe(true);
  });

  it('EVERY_N_DAYS waits until n local days have passed', () => {
    const cap = { mode: 'EVERY_N_DAYS', n: 3 } as const;
    const shownOnThe5th = { count: 2, lastShownAt: at(5, 20).getTime() };

    expect(capAllows(cap, shownOnThe5th, at(5, 21))).toBe(false);
    expect(capAllows(cap, shownOnThe5th, at(7, 23, 59))).toBe(false);
    expect(capAllows(cap, shownOnThe5th, at(8, 0, 1))).toBe(true);
    expect(capAllows(cap, shownOnThe5th, at(9, 12))).toBe(true);
  });

  it('does not stay hidden for good because the device clock was once set far ahead', () => {
    const fromTheFuture = { count: 1, lastShownAt: new Date(2030, 0, 1).getTime() };

    expect(capAllows({ mode: 'ONCE_PER_DAY', n: 1 }, fromTheFuture, at(9, 12))).toBe(true);
    expect(capAllows({ mode: 'EVERY_N_DAYS', n: 3 }, fromTheFuture, at(9, 12))).toBe(true);
  });
});

describe('the cap in storage: keyed by item AND revision, always inside try/catch', () => {
  it('builds the key from the item id and its revision', () => {
    expect(popupStorageKey({ id: 'pop-1', revisionNo: 4 })).toContain('pop-1');
    expect(popupStorageKey({ id: 'pop-1', revisionNo: 4 })).toContain('4');
    expect(popupStorageKey({ id: 'pop-1', revisionNo: 4 })).not.toBe(
      popupStorageKey({ id: 'pop-1', revisionNo: 5 }),
    );
    expect(popupStorageKey({ id: 'pop-1', revisionNo: 4 })).not.toBe(
      popupStorageKey({ id: 'pop-2', revisionNo: 4 }),
    );
  });

  it('shows a pop-up once and then, under ONCE_EVER, not again', () => {
    const p = popup();
    expect(mayShowPopup(p, at(9, 12))).toBe(true);

    recordPopupShown(p, at(9, 12));

    expect(mayShowPopup(p, at(10, 12))).toBe(false);
    expect(readCapState(popupStorageKey(p))).toEqual({
      count: 1,
      lastShownAt: at(9, 12).getTime(),
    });
  });

  it('counts the shows', () => {
    const p = popup({ cap: { mode: 'ONCE_PER_DAY', n: 1 } });

    recordPopupShown(p, at(9, 12));
    recordPopupShown(p, at(10, 12));

    expect(readCapState(popupStorageKey(p))?.count).toBe(2);
  });

  it('resets when the item is edited: a new revision is a new key', () => {
    recordPopupShown(popup({ revisionNo: 1 }), at(9, 12));

    expect(mayShowPopup(popup({ revisionNo: 1 }), at(10, 12))).toBe(false);
    expect(mayShowPopup(popup({ revisionNo: 2 }), at(10, 12))).toBe(true);
  });

  it('treats a stored value it cannot read as "never shown"', () => {
    const p = popup();
    localStorage.setItem(popupStorageKey(p), '{not json');
    expect(mayShowPopup(p, at(9, 12))).toBe(true);

    localStorage.setItem(popupStorageKey(p), JSON.stringify({ count: 'many' }));
    expect(mayShowPopup(p, at(9, 12))).toBe(true);
  });

  it('still shows by its other rules when storage throws on every read and write', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('storage disabled');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('quota exceeded');
    });
    const p = popup({ cap: { mode: 'ONCE_PER_DAY', n: 1 } });

    expect(mayShowPopup(p, at(9, 12))).toBe(true);
    expect(() => recordPopupShown(p, at(9, 12))).not.toThrow();
    expect(() => writeCapState(popupStorageKey(p), { count: 1, lastShownAt: 1 })).not.toThrow();
    expect(() => readCapState(popupStorageKey(p))).not.toThrow();
  });

  it('still shows by its other rules when merely touching localStorage throws', () => {
    vi.spyOn(window, 'localStorage', 'get').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError');
    });
    const p = popup();

    expect(mayShowPopup(p, at(9, 12))).toBe(true);
    expect(() => recordPopupShown(p, at(9, 12))).not.toThrow();
  });

  it('does not show it twice in one page session even when nothing can be stored', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('quota exceeded');
    });
    const p = popup({ cap: { mode: 'ONCE_PER_DAY', n: 1 } });

    recordPopupShown(p, at(9, 12));

    expect(mayShowPopup(p, at(9, 13))).toBe(false);
  });
});

describe('mayShowPopup: the daily window and the cap together', () => {
  it('stays hidden outside its daily window even when the cap would allow it', () => {
    const p = popup({ daily: WORKDAY });

    expect(mayShowPopup(p, at(9, 8, 0))).toBe(false);
    expect(mayShowPopup(p, at(9, 10, 0))).toBe(true);
  });

  it('stays hidden when the cap refuses it even inside its window', () => {
    const p = popup({ daily: WORKDAY });
    recordPopupShown(p, at(9, 10));

    expect(mayShowPopup(p, at(9, 11))).toBe(false);
  });
});

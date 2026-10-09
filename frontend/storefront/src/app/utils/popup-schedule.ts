import type { ContentPopup, ContentPopupDaily } from '../types/content.types';

/**
 * When a pop-up may be shown, decided on the device (ADR 0157, Decision 10).
 *
 * An anonymous browser has no identity the server could count against, so the frequency cap and
 * the daily window are *hints* the document publishes and the device enforces: against its own
 * clock, and against a record it keeps in `localStorage`. Both are kept out of the component so
 * they can be proven with fixed dates.
 *
 * Everything that touches storage is in a `try/catch`. Storage throws in private browsing, in
 * WebViews that block it, and when it is full; the page must then be correct without it, which here
 * means the pop-up is simply governed by its other rules (the window, the in-memory "already shown
 * this visit" flag the component keeps) rather than by a cap this device cannot remember.
 */

/** What the device remembers of one pop-up revision. */
export interface PopupShownRecord {
  readonly count: number;
  /** Epoch milliseconds. */
  readonly lastShownAt: number;
}

/**
 * The storage key. It carries the revision as well as the id on purpose: editing an item
 * re-publishes it as a new revision, and a new revision starts with a clean cap, so an editor
 * who fixes a typo in a pop-up does not find it suppressed for everyone who saw the old one.
 */
export function popupStorageKey(id: string, revisionNo: number): string {
  return `horecaos.content.popup.${id}.r${revisionNo}`;
}

function minutesOf(clock: string): number {
  const [hours, minutes] = clock.split(':').map(Number);
  return hours * 60 + minutes;
}

/** 1 (Monday) to 7 (Sunday). */
function isoWeekday(date: Date): number {
  return ((date.getDay() + 6) % 7) + 1;
}

/**
 * Whether `now` falls in the daily window, on the device's local clock: `from` inclusive, `until`
 * exclusive, `daysOfWeek` 1 = Monday to 7 = Sunday. A window whose `until` is earlier than its
 * `from` runs past midnight; the part after midnight belongs to the day the window *opened* on, so
 * a Friday 22:00 to 02:00 window is still open at Saturday 01:00. No window means always; a window
 * that opens and closes at the same minute is empty.
 */
export function withinDailyWindow(daily: ContentPopupDaily | undefined, now: Date): boolean {
  if (!daily) {
    return true;
  }
  const from = minutesOf(daily.from);
  const until = minutesOf(daily.until);
  if (from === until) {
    return false;
  }
  const minute = now.getHours() * 60 + now.getMinutes();
  const today = isoWeekday(now);
  if (from < until) {
    return minute >= from && minute < until && daily.daysOfWeek.includes(today);
  }
  if (minute >= from) {
    return daily.daysOfWeek.includes(today);
  }
  if (minute < until) {
    const yesterday = today === 1 ? 7 : today - 1;
    return daily.daysOfWeek.includes(yesterday);
  }
  return false;
}

/** A local calendar day as a number, so subtracting two of them counts days and ignores DST hours. */
function localDayNumber(date: Date): number {
  return Math.floor(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()) / 86_400_000);
}

/**
 * Whether the cap lets the pop-up show again.
 *
 *  - `ONCE_EVER`: never again once it was shown on this device.
 *  - `ONCE_PER_DAY`: not again on the same local calendar day.
 *  - `EVERY_N_DAYS`: not again until `n` local calendar days have passed.
 *
 * A record from the future (the device clock was wrong when it was written) must not lock the
 * pop-up out until the calendar catches up, so it counts as not recently shown.
 */
export function capAllows(
  cap: ContentPopup['cap'],
  record: PopupShownRecord | null,
  now: Date,
): boolean {
  if (record === null) {
    return true;
  }
  if (cap.mode === 'ONCE_EVER') {
    return false;
  }
  const daysSince = localDayNumber(now) - localDayNumber(new Date(record.lastShownAt));
  if (daysSince < 0) {
    return true;
  }
  return cap.mode === 'ONCE_PER_DAY' ? daysSince >= 1 : daysSince >= cap.n;
}

export function readShownRecord(id: string, revisionNo: number): PopupShownRecord | null {
  try {
    const raw = localStorage.getItem(popupStorageKey(id, revisionNo));
    if (raw === null) {
      return null;
    }
    const parsed: unknown = JSON.parse(raw);
    if (
      typeof parsed === 'object' &&
      parsed !== null &&
      typeof (parsed as PopupShownRecord).count === 'number' &&
      typeof (parsed as PopupShownRecord).lastShownAt === 'number'
    ) {
      const { count, lastShownAt } = parsed as PopupShownRecord;
      return { count, lastShownAt };
    }
    return null;
  } catch {
    return null;
  }
}

export function writeShownRecord(id: string, revisionNo: number, now: Date): void {
  try {
    const previous = readShownRecord(id, revisionNo);
    const next: PopupShownRecord = {
      count: (previous?.count ?? 0) + 1,
      lastShownAt: now.getTime(),
    };
    localStorage.setItem(popupStorageKey(id, revisionNo), JSON.stringify(next));
  } catch {
    // The cap cannot be remembered here; the pop-up is governed by its other rules.
  }
}

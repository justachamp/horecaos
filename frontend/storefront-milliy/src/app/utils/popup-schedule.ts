import type { ContentPopup, PopupCap, PopupDaily } from '../types/content.types';

/**
 * When an entry pop-up may be shown, decided on the device (ADR 0157, Decision 10).
 *
 * The frequency cap is a hint the server publishes and the device enforces: an anonymous browser
 * has no identity the server could count against. The state lives in `localStorage` under a key of
 * the item id AND its revision -- editing the item re-publishes a new revision, so the cap resets.
 * Every read and write is inside `try/catch` and the page is correct without storage: a pop-up whose
 * cap cannot be read is shown by its other rules, and an in-memory copy keeps it from being shown a
 * second time in the same page session.
 */

export interface PopupCapState {
  /** How many times this revision has been shown on this device. */
  readonly count: number;
  /** Epoch milliseconds of the last show. */
  readonly lastShownAt: number;
}

const DAY_MS = 86_400_000;

/** Page-session copy of the cap state, for the day storage is disabled or full. */
const memory = new Map<string, PopupCapState>();

/** Forgets the in-memory copy. For specs only. */
export function resetPopupMemoryForTests(): void {
  memory.clear();
}

export function popupStorageKey(popup: Pick<ContentPopup, 'id' | 'revisionNo'>): string {
  return `content-popup:${popup.id}:${popup.revisionNo}`;
}

function minutesOfDay(text: string): number | null {
  const match = /^([01]\d|2[0-3]):([0-5]\d)$/.exec(text);
  return match ? Number(match[1]) * 60 + Number(match[2]) : null;
}

/** 1 = Monday ... 7 = Sunday. */
function isoWeekday(date: Date): number {
  return date.getDay() === 0 ? 7 : date.getDay();
}

/**
 * Whether `now`, on the device's own clock, is inside the window: `from` inclusive, `until`
 * exclusive, wrapping midnight when `until` is earlier than `from`. The small hours after midnight
 * of a wrapping window belong to the day it started on ("Friday 22:00-02:00" runs into Saturday
 * 02:00). A window whose bounds cannot be read, or whose bounds are equal, is never open.
 */
export function isWithinDaily(daily: PopupDaily, now: Date): boolean {
  const from = minutesOfDay(daily.from);
  const until = minutesOfDay(daily.until);
  if (from === null || until === null || from === until) {
    return false;
  }
  const minutes = now.getHours() * 60 + now.getMinutes();
  const today = isoWeekday(now);
  if (from < until) {
    return minutes >= from && minutes < until && daily.daysOfWeek.includes(today);
  }
  if (minutes >= from) {
    return daily.daysOfWeek.includes(today);
  }
  if (minutes < until) {
    return daily.daysOfWeek.includes(today === 1 ? 7 : today - 1);
  }
  return false;
}

/** Whole local calendar days from `earlier` to `later` -- by date, not by 24-hour spans. */
function localDaysBetween(earlier: number, later: Date): number {
  const from = new Date(earlier);
  const a = Date.UTC(from.getFullYear(), from.getMonth(), from.getDate());
  const b = Date.UTC(later.getFullYear(), later.getMonth(), later.getDate());
  return Math.round((b - a) / DAY_MS);
}

/**
 * Whether the cap lets the pop-up show again.
 *
 * `ONCE_EVER` never again; `ONCE_PER_DAY` once per local calendar day; `EVERY_N_DAYS` not until n
 * local days have passed. A last-shown time in the future (the device clock was moved) is not
 * believed for the two day-based modes: the alternative is a pop-up hidden until that date.
 */
export function capAllows(cap: PopupCap, state: PopupCapState | null, now: Date): boolean {
  if (!state || state.count < 1) {
    return true;
  }
  if (cap.mode === 'ONCE_EVER') {
    return false;
  }
  const days = localDaysBetween(state.lastShownAt, now);
  if (days < 0) {
    return true;
  }
  return days >= (cap.mode === 'ONCE_PER_DAY' ? 1 : Math.max(1, cap.n));
}

function parseState(raw: string): PopupCapState | null {
  try {
    const value: unknown = JSON.parse(raw);
    if (typeof value !== 'object' || value === null) {
      return null;
    }
    const { count, lastShownAt } = value as Record<string, unknown>;
    return typeof count === 'number' &&
      Number.isFinite(count) &&
      count >= 0 &&
      typeof lastShownAt === 'number' &&
      Number.isFinite(lastShownAt)
      ? { count, lastShownAt }
      : null;
  } catch {
    return null;
  }
}

/** What this device remembers of the pop-up; null when nothing, or nothing readable. */
export function readCapState(key: string): PopupCapState | null {
  try {
    const raw = window.localStorage.getItem(key);
    if (raw !== null) {
      const stored = parseState(raw);
      if (stored) {
        return stored;
      }
    }
  } catch {
    // Storage disabled, blocked or throwing: fall through to this page session's own copy.
  }
  return memory.get(key) ?? null;
}

export function writeCapState(key: string, state: PopupCapState): void {
  memory.set(key, state);
  try {
    window.localStorage.setItem(key, JSON.stringify(state));
  } catch {
    // Quota, private mode, disabled: the in-memory copy above still holds for this page session.
  }
}

/** Whether the pop-up may be shown right now: inside its daily window, and the cap allows it. */
export function mayShowPopup(popup: ContentPopup, now: Date): boolean {
  if (popup.daily && !isWithinDaily(popup.daily, now)) {
    return false;
  }
  return capAllows(popup.cap, readCapState(popupStorageKey(popup)), now);
}

/** Remembers that the pop-up was shown, for the cap. */
export function recordPopupShown(popup: ContentPopup, now: Date): void {
  const key = popupStorageKey(popup);
  const before = readCapState(key);
  writeCapState(key, { count: (before?.count ?? 0) + 1, lastShownAt: now.getTime() });
}

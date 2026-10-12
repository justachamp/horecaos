/**
 * A content schedule is two instants the server evaluates when it reads (ADR 0157, Decision 2):
 * start inclusive, end exclusive. The console offers whole days in the operator's own timezone -- the
 * start is that day's midnight, and a window that ends *on* a day ends at the next midnight -- because
 * "until the 16th" means the whole of the 16th to a person, and an exclusive end at 16th 00:00 would
 * quietly take it down a day early.
 *
 * A saved window that does not fall on midnights (one written through the API) is shown by the day it
 * falls in and **left alone** unless the author changes it.
 */

export type ScheduleMode = 'always' | 'between' | 'from';

export interface ScheduleDays {
  readonly mode: ScheduleMode;
  /** `YYYY-MM-DD`, the first day. */
  readonly start: string | null;
  /** `YYYY-MM-DD`, the last day, inclusive. */
  readonly end: string | null;
}

function pad(n: number): string {
  return String(n).padStart(2, '0');
}

function dayOf(date: Date): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

function localMidnight(day: string): Date | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(day);
  if (!match) {
    return null;
  }
  const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]), 0, 0, 0, 0);
  return Number.isNaN(date.getTime()) ? null : date;
}

/** The instant a window starts at: the midnight that begins `day`. */
export function startInstant(day: string): string | null {
  return localMidnight(day)?.toISOString() ?? null;
}

/** The instant a window that includes `day` ends at: the midnight that ends it. */
export function endInstant(day: string): string | null {
  const midnight = localMidnight(day);
  if (midnight === null) {
    return null;
  }
  midnight.setDate(midnight.getDate() + 1);
  return midnight.toISOString();
}

/** The first day of a saved window. */
export function startDay(startsAt: string | null): string | null {
  return startsAt === null ? null : dayOf(new Date(startsAt));
}

/**
 * The last day a saved window includes. An end at a midnight is the end of the day before; any other
 * instant falls inside the day it is on.
 */
export function endDay(endsAt: string | null): string | null {
  if (endsAt === null) {
    return null;
  }
  const end = new Date(endsAt);
  const atMidnight =
    end.getHours() === 0 &&
    end.getMinutes() === 0 &&
    end.getSeconds() === 0 &&
    end.getMilliseconds() === 0;
  if (atMidnight) {
    end.setDate(end.getDate() - 1);
  }
  return dayOf(end);
}

export function scheduleModeOf(startsAt: string | null, endsAt: string | null): ScheduleMode {
  if (startsAt !== null && endsAt !== null) {
    return 'between';
  }
  return startsAt !== null ? 'from' : endsAt !== null ? 'between' : 'always';
}

/** Today's day, for the default of a window the author has just switched on. */
export function today(now: Date = new Date()): string {
  return dayOf(now);
}

export function addDays(day: string, days: number): string {
  const midnight = localMidnight(day) ?? new Date();
  midnight.setDate(midnight.getDate() + days);
  return dayOf(midnight);
}

/** Whether a window ends before it starts. */
export function windowIsBackwards(startsAt: string | null, endsAt: string | null): boolean {
  return (
    startsAt !== null &&
    endsAt !== null &&
    new Date(endsAt).getTime() <= new Date(startsAt).getTime()
  );
}

/** `DD.MM.YYYY` of a `YYYY-MM-DD` day, the way ru and uz read a date. */
export function dayLabel(day: string): string {
  const [year, month, date] = day.split('-');
  return `${date}.${month}.${year}`;
}

/**
 * The window of a saved item as the list shows it: `12.10.2026 – 16.10.2026`, `from 12.10.2026`
 * (the word is the caller's), or null when it has no schedule.
 */
export function windowLabel(
  startsAt: string | null,
  endsAt: string | null,
  from: (day: string) => string,
): string | null {
  const first = startDay(startsAt);
  const last = endDay(endsAt);
  if (first !== null && last !== null) {
    return `${dayLabel(first)} – ${dayLabel(last)}`;
  }
  if (first !== null) {
    return from(dayLabel(first));
  }
  return last === null ? null : `– ${dayLabel(last)}`;
}

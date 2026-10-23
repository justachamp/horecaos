import { ModeBindingView } from '../../features/settings/locations/locations-api';

/** The wall-clock time of an instant in a time zone, 24-hour: `21:30`. */
export function clockIn(instant: Date, timeZone: string): string {
  return new Intl.DateTimeFormat('en-GB', {
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
    timeZone,
  }).format(instant);
}

interface LocalParts {
  readonly year: number;
  readonly month: number;
  readonly day: number;
  readonly weekday: number;
  readonly hour: number;
  readonly minute: number;
  readonly second: number;
}

const WEEKDAYS: Readonly<Record<string, number>> = {
  Mon: 1,
  Tue: 2,
  Wed: 3,
  Thu: 4,
  Fri: 5,
  Sat: 6,
  Sun: 7,
};

function localParts(instant: Date, timeZone: string): LocalParts {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone,
    hourCycle: 'h23',
    weekday: 'short',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).formatToParts(instant);
  const value = (type: string): string => parts.find((part) => part.type === type)?.value ?? '0';
  return {
    year: Number(value('year')),
    month: Number(value('month')),
    day: Number(value('day')),
    weekday: WEEKDAYS[value('weekday')] ?? 1,
    hour: Number(value('hour')),
    minute: Number(value('minute')),
    second: Number(value('second')),
  };
}

/** How far the zone's wall clock is ahead of UTC at `instant`, in milliseconds. */
function offsetMs(instant: Date, timeZone: string): number {
  const local = localParts(instant, timeZone);
  const asUtc = Date.UTC(
    local.year,
    local.month - 1,
    local.day,
    local.hour,
    local.minute,
    local.second,
  );
  return asUtc - Math.floor(instant.getTime() / 1000) * 1000;
}

/** The instant at which the zone's wall clock reads `hour:minute` on the given local date. */
function zonedInstant(
  year: number,
  month: number,
  day: number,
  hour: number,
  minute: number,
  timeZone: string,
): Date {
  const guess = new Date(Date.UTC(year, month - 1, day, hour, minute));
  const first = new Date(guess.getTime() - offsetMs(guess, timeZone));
  // Across a daylight-saving change the offset at the guess is not the offset at the answer.
  return new Date(guess.getTime() - offsetMs(first, timeZone));
}

/**
 * When today's service ends at a branch: the latest closing time among today's windows in its time zone, or
 * null when it has none left (shut all day, no windows, or the last one has already ended). A date exception
 * replaces the weekly windows for its day, as it does on the branch's schedule. Windows that run past
 * midnight are left out, because «end of day» is a time on today's date.
 */
export function endOfServiceToday(
  bindings: readonly ModeBindingView[],
  now: Date,
  timeZone: string,
): Date | null {
  const today = localParts(now, timeZone);
  const isoDate = `${today.year}-${String(today.month).padStart(2, '0')}-${String(today.day).padStart(2, '0')}`;
  const closings: string[] = [];
  for (const binding of bindings) {
    const exception = binding.exceptions.find((candidate) => candidate.date === isoDate);
    if (exception !== undefined) {
      if (!exception.closedAllDay && exception.opensAt && exception.closesAt) {
        if (exception.closesAt.slice(0, 5) > exception.opensAt.slice(0, 5)) {
          closings.push(exception.closesAt.slice(0, 5));
        }
      }
      continue;
    }
    for (const rule of binding.rules) {
      if (
        rule.dayOfWeek === today.weekday &&
        rule.closesAt.slice(0, 5) > rule.opensAt.slice(0, 5)
      ) {
        closings.push(rule.closesAt.slice(0, 5));
      }
    }
  }
  if (closings.length === 0) {
    return null;
  }
  const latest = closings.sort()[closings.length - 1];
  const [hour, minute] = latest.split(':').map(Number);
  const end = zonedInstant(today.year, today.month, today.day, hour, minute, timeZone);
  return end.getTime() > now.getTime() ? end : null;
}

/**
 * Whether the schedule has the branch open at `now`: inside one of today's windows, in its time zone. A date
 * exception replaces the weekly windows for its day. A window that runs past midnight counts from its opening
 * time onward; the tail it has after midnight is not looked for on the next day, so a branch in that tail
 * reads as shut -- the safe side for «can only be opened by force», where the operator is told, not blocked.
 */
export function isServiceOpenNow(
  bindings: readonly ModeBindingView[],
  now: Date,
  timeZone: string,
): boolean {
  const today = localParts(now, timeZone);
  const isoDate = `${today.year}-${String(today.month).padStart(2, '0')}-${String(today.day).padStart(2, '0')}`;
  const clock = clockIn(now, timeZone);
  const within = (opensAt: string, closesAt: string): boolean =>
    closesAt > opensAt ? clock >= opensAt && clock < closesAt : clock >= opensAt;
  return bindings.some((binding) => {
    const exception = binding.exceptions.find((candidate) => candidate.date === isoDate);
    if (exception !== undefined) {
      return (
        !exception.closedAllDay &&
        exception.opensAt !== null &&
        exception.closesAt !== null &&
        within(exception.opensAt.slice(0, 5), exception.closesAt.slice(0, 5))
      );
    }
    return binding.rules.some(
      (rule) =>
        rule.dayOfWeek === today.weekday &&
        within(rule.opensAt.slice(0, 5), rule.closesAt.slice(0, 5)),
    );
  });
}

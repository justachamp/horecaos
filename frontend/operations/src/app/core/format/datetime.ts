/**
 * Time, the way ru and uz actually read it.
 *
 * 24-hour clock, `DD.MM` dates, no am/pm anywhere
 * (`docs/operations-spec/orders.md` §1.4). Written by hand rather than through
 * `Intl.DateTimeFormat` for the same reason as the money grouping: the output
 * must be byte-identical across browsers, because two operators comparing the
 * same order over the phone must be reading the same string.
 *
 * **Timezone.** Every instant from the platform is RFC 3339 UTC with `Z`
 * (ADR 0031), and every instant on screen is in the *tenant's* timezone, not the
 * browser's. A manager checking a Tashkent branch from a laptop still set to
 * Europe/London must not see orders an hour early. The tenant zone is therefore
 * an explicit parameter with no default — omitting it should be impossible, not
 * merely discouraged.
 */

/** An IANA zone identifier, e.g. `Asia/Tashkent`. */
export type TimeZone = string;

/** `HH:mm` — for a timestamp that falls on the business date being viewed. */
export function formatTime(instant: Date, zone: TimeZone): string {
  const parts = zonedParts(instant, zone);
  return `${parts.hour}:${parts.minute}`;
}

/** `DD.MM HH:mm` — for anything not on the date being viewed. */
export function formatDateTime(instant: Date, zone: TimeZone): string {
  const parts = zonedParts(instant, zone);
  return `${parts.day}.${parts.month} ${parts.hour}:${parts.minute}`;
}

/** `DD.MM.YYYY` — for a date on its own. */
export function formatDate(instant: Date, zone: TimeZone): string {
  const parts = zonedParts(instant, zone);
  return `${parts.day}.${parts.month}.${parts.year}`;
}

/** `HH:mm:ss` — the "last updated" stamp, which is never optional on a queue. */
export function formatClock(instant: Date, zone: TimeZone): string {
  const parts = zonedParts(instant, zone);
  return `${parts.hour}:${parts.minute}:${parts.second}`;
}

/**
 * The UTC instant for a wall clock reading in a named zone — the inverse of
 * {@link formatTime} and the rest of this file's instant-to-zone direction.
 *
 * `hoursFromMidnight` may be fractional (`19.5` = 19:30) and may fall outside
 * `[0, 24)`: `24` is the next calendar day's `00:00`, `-1` the previous day's
 * `23:00`. `Date.UTC` normalises the overflow on its own, which is exactly
 * what a service window that closes after midnight needs — see
 * `reservations-page.ts`'s day-window comment for the booking screen this
 * exists for.
 *
 * Computed by a first guess-then-correct pass rather than one lookup, because
 * `Intl` converts instant-to-zone, never the other way: treat the wall clock
 * as if it were already UTC, read the zone's offset at that instant, and
 * shift by it. Exact for a zone with no DST transition at that moment —
 * `Asia/Tashkent` has none, ever — and off by at most the transition's own
 * size for the rare wall-clock hour a DST jump makes ambiguous or
 * non-existent, which every zone this platform trades in as of ADR 0055
 * (`docs/adr/meta/0055-greenfield-launch-scope.md`) avoids entirely.
 */
export function zonedTimeToInstant(
  dateIso: string,
  hoursFromMidnight: number,
  zone: TimeZone,
): Date {
  const [year, month, day] = dateIso.split('-').map(Number);
  const totalMinutes = Math.round(hoursFromMidnight * 60);
  const naiveUtc = Date.UTC(year, month - 1, day, 0, totalMinutes, 0);
  const offsetMinutes = offsetMinutesEastOfUtc(new Date(naiveUtc), zone);
  return new Date(naiveUtc - offsetMinutes * 60_000);
}

/** Minutes to ADD to a UTC instant to read the zone's local wall clock (positive east of UTC, e.g. +300 for Tashkent). */
function offsetMinutesEastOfUtc(instant: Date, zone: TimeZone): number {
  const parts = zonedParts(instant, zone);
  const asUtc = Date.UTC(
    Number(parts.year),
    Number(parts.month) - 1,
    Number(parts.day),
    Number(parts.hour),
    Number(parts.minute),
    Number(parts.second),
  );
  return (asUtc - instant.getTime()) / 60_000;
}

/**
 * `<input type="datetime-local">`'s own value shape (`YYYY-MM-DDTHH:mm`) for
 * an instant, read as wall-clock time in a named zone — the write-side
 * counterpart of {@link zonedTimeToInstant} for an editable field that must
 * round-trip through that input's un-timezoned string shape.
 */
export function toZonedDatetimeLocal(instant: Date, zone: TimeZone): string {
  const parts = zonedParts(instant, zone);
  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`;
}

/**
 * The inverse of {@link toZonedDatetimeLocal}: a `<input type="datetime-local">`
 * value, interpreted as wall-clock time in a named zone rather than the
 * browser's own — the fix for the class of bug where `new Date(local)` reads
 * an editable fire-time/schedule field in whatever zone the operator's device
 * happens to be set to.
 */
export function parseZonedDatetimeLocal(local: string, zone: TimeZone): Date {
  const [dateIso, time] = local.split('T');
  const [hour, minute] = (time ?? '00:00').split(':').map(Number);
  return zonedTimeToInstant(dateIso, hour + minute / 60, zone);
}

/**
 * A duration in whole minutes, as `12 мин` or `1 ч 04 мин`.
 *
 * The minutes are zero-padded past the hour so that a column of durations stays
 * aligned; `1 ч 4 мин` and `1 ч 14 мин` next to each other read as unrelated.
 */
export function formatDuration(
  totalMinutes: number,
  units: { readonly hour: string; readonly minute: string },
): string {
  const minutes = Math.max(0, Math.round(totalMinutes));
  if (minutes < 60) {
    return `${minutes} ${units.minute}`;
  }
  const hours = Math.floor(minutes / 60);
  const rest = String(minutes % 60).padStart(2, '0');
  return `${hours} ${units.hour} ${rest} ${units.minute}`;
}

interface ZonedParts {
  year: string;
  month: string;
  day: string;
  hour: string;
  minute: string;
  second: string;
}

/**
 * Decomposes an instant into calendar fields in a named zone.
 *
 * `formatToParts` with `en-GB` is used purely as a zone-conversion mechanism —
 * the locale is irrelevant because only the numeric parts are read, and `en-GB`
 * is 24-hour so `hour` never comes back as `12 PM`. This is the one place
 * `Intl` is trusted, because timezone rules are exactly the thing that must not
 * be hand-written.
 */
function zonedParts(instant: Date, zone: TimeZone): ZonedParts {
  const formatter = new Intl.DateTimeFormat('en-GB', {
    timeZone: zone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  });

  const parts: Partial<ZonedParts> = {};
  for (const part of formatter.formatToParts(instant)) {
    if (part.type in EMPTY_PARTS) {
      parts[part.type as keyof ZonedParts] = part.value;
    }
  }
  return { ...EMPTY_PARTS, ...parts };
}

const EMPTY_PARTS: ZonedParts = {
  year: '0000',
  month: '00',
  day: '00',
  hour: '00',
  minute: '00',
  second: '00',
};

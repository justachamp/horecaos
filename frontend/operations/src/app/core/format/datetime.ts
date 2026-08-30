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

/**
 * Formatting helpers specific to the reports feature: an ISO `LocalDate`
 * string (`business_date`, `closedThrough` — a calendar date with no instant
 * and no zone to convert) rendered `DD.MM`, and small pure helpers the report
 * tables and tiles share. Instant formatting stays in
 * `core/format/datetime.ts`; this file never duplicates it.
 */

/** `YYYY-MM-DD` → `DD.MM` (statistics.md §0: "Dates DD.MM"). No zone conversion — a LocalDate has none to do. */
export function ddmm(iso: string): string {
  const [, month, day] = iso.split('-');
  return `${day}.${month}`;
}

/** `YYYY-MM-DD` → `DD.MM.YYYY`, for a stand-alone date rather than one implicitly this period. */
export function ddmmyyyy(iso: string): string {
  const [year, month, day] = iso.split('-');
  return `${day}.${month}.${year}`;
}

/**
 * `мм:сс` under an hour, `ч:мм` above (statistics.md §0), from whole seconds.
 * Never rounds to a bare minute count — a duration column reads seconds-exact
 * up to the hour, which is what makes 14:59 visibly different from 15:00.
 */
export function formatSecondsDuration(totalSeconds: number): string {
  const seconds = Math.max(0, Math.round(totalSeconds));
  const minutes = Math.floor(seconds / 60);
  const restSeconds = String(seconds % 60).padStart(2, '0');
  if (minutes < 60) {
    return `${minutes}:${restSeconds}`;
  }
  const hours = Math.floor(minutes / 60);
  const restMinutes = String(minutes % 60).padStart(2, '0');
  return `${hours}:${restMinutes}:${restSeconds}`;
}

/** A signed minute count from signed seconds, `+14 мин` / `−6 мин`, for a lateness caption. */
export function formatSignedMinutes(totalSeconds: number, unit: string): string {
  const minutes = Math.round(totalSeconds / 60);
  const sign = minutes > 0 ? '+' : minutes < 0 ? '−' : '';
  return `${sign}${Math.abs(minutes)} ${unit}`;
}

/** A share as a whole percentage from a count and a total. `—` (never `0%`) when the total is zero. */
export function formatShare(count: number, total: number): string {
  if (total <= 0) {
    return '—';
  }
  return `${Math.round((count / total) * 100)}%`;
}

/**
 * Groups a plain integer with U+00A0 NO-BREAK SPACE, the same separator
 * `formatMoney` uses and for the same reason: a plain space lets a browser
 * break `12 400` across two lines in a dense table, and `Intl`'s own separator
 * varies by locale and ICU version.
 */
export function formatCount(value: number): string {
  const negative = value < 0;
  const digits = String(Math.abs(Math.round(value)));
  const grouped = digits.replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
  return negative ? `−${grouped}` : grouped;
}

/** A signed percentage delta, `+12%` / `−4%` / `0%`, from a current and a comparison value. */
export function formatDeltaPercent(current: number, comparison: number): string | null {
  if (comparison === 0) {
    return null;
  }
  const percent = Math.round(((current - comparison) / comparison) * 100);
  const sign = percent > 0 ? '+' : percent < 0 ? '−' : '';
  return `${sign}${Math.abs(percent)}%`;
}

/** The median of a list of numbers, or null for an empty list — never zero. */
export function median(values: readonly number[]): number | null {
  if (values.length === 0) {
    return null;
  }
  const sorted = [...values].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0 ? (sorted[mid - 1] + sorted[mid]) / 2 : sorted[mid];
}

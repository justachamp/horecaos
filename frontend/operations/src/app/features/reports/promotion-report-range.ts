/** A closed range of business dates, inclusive, `YYYY-MM-DD` — what the 7.9 promotion endpoints take. */
export interface ReportRange {
  readonly from: string;
  readonly to: string;
}

function isoDate(date: Date): string {
  const pad = (value: number): string => String(value).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/**
 * The week up to and including `now`, in the device's calendar. The fact is built at
 * day close, so the last day or two are usually still settling; the page's provenance
 * banner says so rather than this hiding them.
 */
export function defaultPromotionRange(now: Date): ReportRange {
  const start = new Date(now);
  start.setDate(start.getDate() - 6);
  return { from: isoDate(start), to: isoDate(now) };
}

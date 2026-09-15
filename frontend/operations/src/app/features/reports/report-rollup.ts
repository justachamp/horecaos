import { RowResponse } from './reporting-api';

/**
 * Sums a same-metric figure across the day rows `/queries` always returns —
 * ADR 0043's typed query has no "whole period" grain; `businessDate` is part
 * of every slice regardless of `groupBy`, so a five-day range comes back as
 * five rows and a period total is a client-side sum across them.
 *
 * **Why this is not the aggregate ADR 0043 forbids a surface from computing.**
 * Every metric summed here is `SUM` or `COUNT` at the fact grain: the
 * registry's own day-grain figure for `revenue.gross.v1` is already `SUM(...)
 * WHERE business_date = :day`, and summing several such days is the identical
 * arithmetic the registry would perform were `business_date` not baked into
 * the slice. Nothing here recomputes what a `SUM` metric *means* — it rolls up
 * the axis the API does not collapse. A `RATIO` metric (`average_check.v1`)
 * is never summed this way; see {@link deriveAverageCheck}.
 */
export function sumAcrossDays(
  rows: readonly RowResponse[],
  keyOf: (row: RowResponse) => string,
  metricCodes: readonly string[],
): Map<string, Record<string, number>> {
  const byKey = new Map<string, Record<string, number>>();
  for (const row of rows) {
    const key = keyOf(row);
    const bucket = byKey.get(key) ?? Object.fromEntries(metricCodes.map((code) => [code, 0]));
    for (const code of metricCodes) {
      bucket[code] = (bucket[code] ?? 0) + (row.values[code] ?? 0);
    }
    byKey.set(key, bucket);
  }
  return byKey;
}

/** {@link sumAcrossDays} collapsed to one key — the whole period's total, unsplit by any dimension. */
export function sumTotal(
  rows: readonly RowResponse[],
  metricCodes: readonly string[],
): Record<string, number> {
  return (
    sumAcrossDays(rows, () => '_', metricCodes).get('_') ??
    Object.fromEntries(metricCodes.map((c) => [c, 0]))
  );
}

/** One metric's value for one business date — the shape a trend line or a sparkline plots. */
export interface DailyPoint {
  readonly date: string;
  readonly value: number;
}

/** {@link DailyPoint}, but for a `RATIO` metric derived per day — see {@link dailyAverageCheck}. */
export interface DailyRatioPoint {
  readonly date: string;
  readonly value: number | null;
}

/**
 * A single metric's day-by-day series, in business-date order — the axis
 * {@link sumAcrossDays}/{@link sumTotal} collapse away (wave T09, IA X.19/
 * X.20). `/reporting/queries` already returns one row per business date;
 * this keeps that date rather than folding every row into one number, which
 * is what a trend line (`business-overview-page.ts`'s new "Dynamics" band)
 * and a KPI tile's sparkline both need and neither `sumAcrossDays` nor
 * `sumTotal` can give them.
 *
 * Built on {@link sumAcrossDays} keyed by `businessDate` rather than a
 * second, competing grouping loop — the two functions differ only in which
 * axis they collapse.
 */
export function dailySeries(
  rows: readonly RowResponse[],
  metricCode: string,
): readonly DailyPoint[] {
  const byDate = sumAcrossDays(rows, (row) => row.businessDate, [metricCode]);
  return [...byDate.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([date, values]) => ({ date, value: values[metricCode] }));
}

/**
 * {@link deriveAverageCheck} applied per business date rather than once
 * across the whole period — the day-by-day counterpart {@link dailySeries}
 * cannot offer a `RATIO` metric directly (summing per-day averages would be
 * exactly the averaging-of-averages bug `average_check.v1`'s own definition
 * exists to prevent). `value: null` on a date with no completed orders,
 * never a zero that reads as free food — same rule as {@link deriveAverageCheck}.
 */
export function dailyAverageCheck(rows: readonly RowResponse[]): readonly DailyRatioPoint[] {
  const revenue = dailySeries(rows, 'revenue.gross.v1');
  const ordersByDate = new Map(dailySeries(rows, 'orders.count.v1').map((p) => [p.date, p.value]));
  return revenue.map((point) => ({
    date: point.date,
    value: deriveAverageCheck(point.value, ordersByDate.get(point.date) ?? 0),
  }));
}

/**
 * Wave P27 (7.1d): the filter bar's granularity axis, applied to a
 * day-by-day series client-side — the typed `/queries` endpoint has no week
 * or month grain of its own (`Grain` in the registry is a metric's storage
 * grain, not a display bucket), and folding calendar weeks/months is
 * arithmetic on numbers the day-grain read already returned correctly, the
 * same footing {@link sumAcrossDays} itself stands on.
 *
 * ISO weeks (Monday start) for `'week'`; calendar months for `'month'`. The
 * bucket key is the bucket's own first date, so callers can still label it
 * with `ddmm`/a month name without a second lookup.
 */
export function rollUpByGranularity(
  points: readonly DailyPoint[],
  granularity: 'day' | 'week' | 'month',
): readonly DailyPoint[] {
  if (granularity === 'day') {
    return points;
  }
  const byBucket = new Map<string, number>();
  for (const point of points) {
    const key =
      granularity === 'week' ? startOfIsoWeek(point.date) : `${point.date.slice(0, 7)}-01`;
    byBucket.set(key, (byBucket.get(key) ?? 0) + point.value);
  }
  return [...byBucket.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([date, value]) => ({ date, value }));
}

function startOfIsoWeek(iso: string): string {
  const [year, month, day] = iso.split('-').map(Number);
  const date = new Date(Date.UTC(year, month - 1, day));
  // getUTCDay(): 0 = Sunday .. 6 = Saturday. ISO weeks start Monday, so
  // Sunday is 6 days into the week it belongs to, not 0.
  const isoDayIndex = (date.getUTCDay() + 6) % 7;
  date.setUTCDate(date.getUTCDate() - isoDayIndex);
  return date.toISOString().slice(0, 10);
}

/**
 * `average_check.v1`'s own published formula (`MetricRegistry`): gross
 * revenue over completed-order count, same filter, same date attribution.
 * Applying the registry's stated formula to two already-correctly-summed
 * `SUM` figures is not a client-invented aggregate — it is the one way to
 * roll a `RATIO` metric up across days without averaging averages, which is
 * exactly the bug `average_check.v1`'s definition exists to prevent.
 */
export function deriveAverageCheck(
  grossRevenueSom: number,
  completedOrderCount: number,
): number | null {
  return completedOrderCount === 0 ? null : Math.trunc(grossRevenueSom / completedOrderCount);
}

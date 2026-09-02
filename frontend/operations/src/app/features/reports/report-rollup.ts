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

/**
 * The data shapes every chart in the family (wave T09, IA X.19) accepts.
 * Deliberately generic — a category is "a business date" in one caller and
 * "a sales channel" in another, and no chart component here knows or cares
 * which. Callers translate their own domain (a `RowResponse`, a
 * `BucketResponse`, an `HourDemandResponse`) into these shapes; see
 * `report-rollup.ts` for the reporting-specific half of that translation.
 */

/** One point on a line/area series. `y: null` renders as a gap, never a false zero. */
export interface ChartPoint {
  readonly x: string;
  readonly y: number | null;
}

/** One named series of points sharing the same x-axis. */
export interface ChartSeries {
  readonly key: string;
  readonly label: string;
  readonly points: readonly ChartPoint[];
}

/** One labelled category and its value — a bar, a donut slice, a stacked-bar segment. */
export interface ChartCategory {
  readonly key: string;
  readonly label: string;
  readonly value: number;
}

/** One row of a heatmap grid — a weekday, say — holding one value per column. */
export interface ChartHeatRow {
  readonly key: string;
  readonly label: string;
  readonly cells: readonly ChartHeatCell[];
}

/** One heatmap cell. `value: null` means too thin a sample to plot, never a false zero. */
export interface ChartHeatCell {
  readonly key: string;
  readonly label: string;
  readonly value: number | null;
}

/** Formats a raw number for display — money, a plain count, an average. Defaults to `String`. */
export type ChartValueFormatter = (value: number) => string;

export const DEFAULT_VALUE_FORMATTER: ChartValueFormatter = (value) => String(Math.round(value));

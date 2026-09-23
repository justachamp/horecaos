import { deriveAverageCheck } from './report-rollup';

/**
 * One already-day-folded, already-legal-entity-folded bucket behind both
 * «Сводка» roll-ups (wave 8 w7-reports, 7.2c) — `order-reports-page.ts`'s
 * `loadSummary` builds these off one `GET .../reporting/queries` read (money
 * always named `LEGAL_ENTITY` in `groupBy`, ADR 0038) and folds both the
 * date and entity axes away with `sumAcrossDays`, the same transparent fold
 * `business-overview-page.ts`'s Band A already uses. Everything in this file
 * is pure arithmetic over buckets already fetched — no second read.
 */
export interface SummaryBucket {
  readonly locationId: string;
  readonly channelCode: string;
  readonly fulfilmentType: string;
  readonly orderCount: number;
  /** revenue.gross.v1 — already delivery-fee-inclusive (MetricRegistry's own definition). */
  readonly grossSom: number;
  /** delivery_fee.v1 — a component already inside grossSom, never added to it. */
  readonly deliveryFeeSom: number;
  /** revenue.net.v1 — gross less discount (and any refund on this date). */
  readonly netSom: number;
}

export type SummaryMeasure = 'count' | 'sum' | 'avgCheck';
export type SummarySplit = 'ALL' | 'DELIVERY' | 'PICKUP' | 'DINE_IN';

/** One order-type row of «Сводка 1» (statistics.md §2.2) — `key` is a fulfilment type code, or `'TOTAL'`. */
export interface Report1Row {
  readonly key: string;
  readonly orderCount: number;
  /** «Сумма» — delivery-fee-EXCLUSIVE, i.e. grossSom minus deliveryFeeSom. */
  readonly sumSom: number;
  /** «Сумма с учётом доставки» — delivery-fee-INCLUSIVE, i.e. grossSom itself. */
  readonly sumWithDeliverySom: number;
  /** «Итого» — net of discount (revenue.net.v1). */
  readonly totalSom: number;
}

const ORDER_TYPES: readonly string[] = ['DELIVERY', 'PICKUP', 'DINE_IN'];

interface Totals {
  orderCount: number;
  grossSom: number;
  deliveryFeeSom: number;
  netSom: number;
}

function emptyTotals(): Totals {
  return { orderCount: 0, grossSom: 0, deliveryFeeSom: 0, netSom: 0 };
}

function add(
  a: Totals,
  b: Pick<SummaryBucket, 'orderCount' | 'grossSom' | 'deliveryFeeSom' | 'netSom'>,
): Totals {
  return {
    orderCount: a.orderCount + b.orderCount,
    grossSom: a.grossSom + b.grossSom,
    deliveryFeeSom: a.deliveryFeeSom + b.deliveryFeeSom,
    netSom: a.netSom + b.netSom,
  };
}

function rowOf(key: string, totals: Totals): Report1Row {
  return {
    key,
    orderCount: totals.orderCount,
    sumSom: totals.grossSom - totals.deliveryFeeSom,
    sumWithDeliverySom: totals.grossSom,
    totalSom: totals.netSom,
  };
}

/**
 * «Сводка 1»: by `Тип заказа` — `Кол-во заказов`, `Сумма`, `Сумма с учётом
 * доставки`, `Итого` (statistics.md §2.2). One row per fulfilment type
 * actually present in `buckets`, in a fixed DELIVERY/PICKUP/DINE_IN order,
 * plus a `'TOTAL'` row — never more than four rows, matching "small, four
 * rows, correct".
 */
export function buildReport1Rows(buckets: readonly SummaryBucket[]): readonly Report1Row[] {
  const byType = new Map<string, Totals>();
  for (const bucket of buckets) {
    byType.set(
      bucket.fulfilmentType,
      add(byType.get(bucket.fulfilmentType) ?? emptyTotals(), bucket),
    );
  }

  const rows = ORDER_TYPES.filter((type) => byType.has(type)).map((type) =>
    rowOf(type, byType.get(type)!),
  );
  const grandTotal = [...byType.values()].reduce((acc, totals) => add(acc, totals), emptyTotals());
  rows.push(rowOf('TOTAL', grandTotal));
  return rows;
}

/** One axis label of the branch×channel grid — a branch row or a channel column. */
export interface GridAxis {
  readonly key: string;
  readonly label: string;
}

/** One row of the grid — `cells` aligned 1:1 with {@link SummaryGrid.columns}. `null` is "no orders in this cell", never a zero. */
export interface GridRow {
  readonly key: string;
  readonly label: string;
  readonly cells: readonly (number | null)[];
  readonly rowTotal: number;
}

/**
 * «Сводка 2», as the true pivot statistics.md §2.2 draws it — rows = branch,
 * columns = channel — rather than the flat (branch, channel) list the prior
 * wave shipped. A row/column total, and a grand total, so a reader can check
 * the grid sums to the same figure the flat read would have printed (see
 * `order-summary-grid.spec.ts`'s own reconciliation test).
 */
export interface SummaryGrid {
  readonly columns: readonly GridAxis[];
  readonly rows: readonly GridRow[];
  readonly columnTotals: readonly number[];
  readonly grandTotal: number;
}

function valueOf(measure: SummaryMeasure, totals: Totals): number {
  switch (measure) {
    case 'count':
      return totals.orderCount;
    case 'sum':
      // The grid's one money measure is always delivery-fee-inclusive —
      // grossSom itself — matching Delever's report 1's own inclusive
      // column; see this module's own doc on SummaryBucket.grossSom.
      return totals.grossSom;
    case 'avgCheck':
      return deriveAverageCheck(totals.grossSom, totals.orderCount) ?? 0;
  }
}

export function buildSummaryGrid(
  buckets: readonly SummaryBucket[],
  measure: SummaryMeasure,
  split: SummarySplit,
  nameOfLocation: (locationId: string) => string,
  nameOfChannel: (channelCode: string) => string,
): SummaryGrid {
  const matching = buckets.filter((bucket) => split === 'ALL' || bucket.fulfilmentType === split);

  const cellTotals = new Map<string, Map<string, Totals>>();
  const locationKeys = new Set<string>();
  const channelKeys = new Set<string>();
  for (const bucket of matching) {
    locationKeys.add(bucket.locationId);
    channelKeys.add(bucket.channelCode);
    const byChannel = cellTotals.get(bucket.locationId) ?? new Map<string, Totals>();
    byChannel.set(
      bucket.channelCode,
      add(byChannel.get(bucket.channelCode) ?? emptyTotals(), bucket),
    );
    cellTotals.set(bucket.locationId, byChannel);
  }

  const columns = [...channelKeys]
    .map((key) => ({ key, label: nameOfChannel(key) }))
    .sort((a, b) => a.label.localeCompare(b.label));
  const locations = [...locationKeys]
    .map((key) => ({ key, label: nameOfLocation(key) }))
    .sort((a, b) => a.label.localeCompare(b.label));

  const rows: GridRow[] = locations.map((location) => {
    const byChannel = cellTotals.get(location.key) ?? new Map<string, Totals>();
    const cells = columns.map((column) => {
      const cell = byChannel.get(column.key);
      return cell ? valueOf(measure, cell) : null;
    });
    const rowTotals = [...byChannel.values()].reduce(
      (acc, totals) => add(acc, totals),
      emptyTotals(),
    );
    return {
      key: location.key,
      label: location.label,
      cells,
      rowTotal: valueOf(measure, rowTotals),
    };
  });

  const columnTotals = columns.map((column) => {
    const totals = locations.reduce((acc, location) => {
      const cell = cellTotals.get(location.key)?.get(column.key);
      return cell ? add(acc, cell) : acc;
    }, emptyTotals());
    return valueOf(measure, totals);
  });

  const grandTotals = matching.reduce((acc, bucket) => add(acc, bucket), emptyTotals());
  return { columns, rows, columnTotals, grandTotal: valueOf(measure, grandTotals) };
}

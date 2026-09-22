import { describe, expect, it } from 'vitest';

import { SummaryBucket, buildReport1Rows, buildSummaryGrid } from './order-summary-grid';

const BUCKETS: readonly SummaryBucket[] = [
  {
    locationId: 'branch-a',
    channelCode: 'TELEGRAM',
    fulfilmentType: 'DELIVERY',
    orderCount: 10,
    grossSom: 1_000_000,
    deliveryFeeSom: 150_000,
    netSom: 900_000,
  },
  {
    locationId: 'branch-a',
    channelCode: 'WEBSITE',
    fulfilmentType: 'PICKUP',
    orderCount: 4,
    grossSom: 200_000,
    deliveryFeeSom: 0,
    netSom: 190_000,
  },
  {
    locationId: 'branch-b',
    channelCode: 'TELEGRAM',
    fulfilmentType: 'DELIVERY',
    orderCount: 6,
    grossSom: 600_000,
    deliveryFeeSom: 90_000,
    netSom: 550_000,
  },
];

describe('buildReport1Rows', () => {
  it('sums each fulfilment type into its own row — count, fee-exclusive, fee-inclusive, and net', () => {
    const rows = buildReport1Rows(BUCKETS);
    const delivery = rows.find((r) => r.key === 'DELIVERY');
    expect(delivery).toEqual({
      key: 'DELIVERY',
      orderCount: 16,
      sumSom: 1_600_000 - 240_000,
      sumWithDeliverySom: 1_600_000,
      totalSom: 1_450_000,
    });
  });

  it('never mixes delivery fee into the fee-exclusive column — sumSom plus deliveryFeeSom reproduces gross', () => {
    const rows = buildReport1Rows(BUCKETS);
    const delivery = rows.find((r) => r.key === 'DELIVERY')!;
    expect(delivery.sumSom + 240_000).toBe(delivery.sumWithDeliverySom);
  });

  it('adds a TOTAL row that reconciles to every type row summed', () => {
    const rows = buildReport1Rows(BUCKETS);
    const total = rows.find((r) => r.key === 'TOTAL')!;
    const typeRows = rows.filter((r) => r.key !== 'TOTAL');
    expect(total.orderCount).toBe(typeRows.reduce((sum, r) => sum + r.orderCount, 0));
    expect(total.sumWithDeliverySom).toBe(
      typeRows.reduce((sum, r) => sum + r.sumWithDeliverySom, 0),
    );
    expect(total.totalSom).toBe(typeRows.reduce((sum, r) => sum + r.totalSom, 0));
  });

  it('never rows a fulfilment type with no orders in range — small, correct, not padded with zero rows', () => {
    const rows = buildReport1Rows(BUCKETS);
    expect(rows.find((r) => r.key === 'DINE_IN')).toBeUndefined();
    expect(rows).toHaveLength(3); // DELIVERY, PICKUP, TOTAL
  });
});

describe('buildSummaryGrid', () => {
  const nameOfLocation = (id: string) => (id === 'branch-a' ? 'Chilanzar' : 'Yunusabad');
  const nameOfChannel = (code: string) => code;

  it('renders the true branch×channel grid — a cell per pair, null where the pair never ordered', () => {
    const grid = buildSummaryGrid(BUCKETS, 'sum', 'ALL', nameOfLocation, nameOfChannel);
    expect(grid.columns.map((c) => c.key).sort()).toEqual(['TELEGRAM', 'WEBSITE']);
    expect(grid.rows).toHaveLength(2);

    const chilanzar = grid.rows.find((r) => r.key === 'branch-a')!;
    const telegramIndex = grid.columns.findIndex((c) => c.key === 'TELEGRAM');
    const websiteIndex = grid.columns.findIndex((c) => c.key === 'WEBSITE');
    expect(chilanzar.cells[telegramIndex]).toBe(1_000_000);
    expect(chilanzar.cells[websiteIndex]).toBe(200_000);

    const yunusabad = grid.rows.find((r) => r.key === 'branch-b')!;
    expect(yunusabad.cells[websiteIndex]).toBeNull();
  });

  it("the grid's cell sums equal the flat total the prior wave's list would have printed", () => {
    const grid = buildSummaryGrid(BUCKETS, 'sum', 'ALL', nameOfLocation, nameOfChannel);
    const cellSum = grid.rows.reduce(
      (sum, row) => sum + row.cells.reduce((rowSum: number, cell) => rowSum + (cell ?? 0), 0),
      0,
    );
    const flatTotal = BUCKETS.reduce((sum, bucket) => sum + bucket.grossSom, 0);
    expect(cellSum).toBe(flatTotal);
    expect(grid.grandTotal).toBe(flatTotal);
  });

  it("the grid's cell sums equal the flat total for the count measure too", () => {
    const grid = buildSummaryGrid(BUCKETS, 'count', 'ALL', nameOfLocation, nameOfChannel);
    const cellSum = grid.rows.reduce(
      (sum, row) => sum + row.cells.reduce((rowSum: number, cell) => rowSum + (cell ?? 0), 0),
      0,
    );
    const flatTotal = BUCKETS.reduce((sum, bucket) => sum + bucket.orderCount, 0);
    expect(cellSum).toBe(flatTotal);
  });

  it('every row and column total reconciles to its own cells, not just the grand total', () => {
    const grid = buildSummaryGrid(BUCKETS, 'sum', 'ALL', nameOfLocation, nameOfChannel);
    for (const row of grid.rows) {
      const rowSum = row.cells.reduce((sum: number, cell) => sum + (cell ?? 0), 0);
      expect(row.rowTotal).toBe(rowSum);
    }
    grid.columns.forEach((_column, index) => {
      const columnSum = grid.rows.reduce((sum, row) => sum + (row.cells[index] ?? 0), 0);
      expect(grid.columnTotals[index]).toBe(columnSum);
    });
  });

  it('the split control narrows buckets before the grid is built, not after', () => {
    const grid = buildSummaryGrid(BUCKETS, 'sum', 'PICKUP', nameOfLocation, nameOfChannel);
    expect(grid.rows).toHaveLength(1);
    expect(grid.rows[0].key).toBe('branch-a');
    expect(grid.grandTotal).toBe(200_000);
  });

  it('derives average check from summed gross and count, never averaging two branches’ own averages', () => {
    const grid = buildSummaryGrid(BUCKETS, 'avgCheck', 'DELIVERY', nameOfLocation, nameOfChannel);
    // branch-a TELEGRAM DELIVERY: 1 000 000 / 10 = 100 000. branch-b TELEGRAM
    // DELIVERY: 600 000 / 6 = 100 000. Column total must be the SUMMED
    // gross over the SUMMED count (1 600 000 / 16 = 100 000), which happens
    // to equal both rows' own average here — a stronger case is the next
    // assertion, on differently-shaped rows.
    const telegramIndex = grid.columns.findIndex((c) => c.key === 'TELEGRAM');
    expect(grid.columnTotals[telegramIndex]).toBe(100_000);
  });

  it('an average-check column total is the weighted ratio, never the mean of two unequal per-row ratios', () => {
    const uneven: readonly SummaryBucket[] = [
      // branch-a's own average check: 100 000 / 1 = 100 000.
      {
        locationId: 'branch-a',
        channelCode: 'TELEGRAM',
        fulfilmentType: 'DELIVERY',
        orderCount: 1,
        grossSom: 100_000,
        deliveryFeeSom: 0,
        netSom: 100_000,
      },
      // branch-b's own average check: 900 000 / 3 = 300 000.
      {
        locationId: 'branch-b',
        channelCode: 'TELEGRAM',
        fulfilmentType: 'DELIVERY',
        orderCount: 3,
        grossSom: 900_000,
        deliveryFeeSom: 0,
        netSom: 900_000,
      },
    ];
    const grid = buildSummaryGrid(uneven, 'avgCheck', 'ALL', nameOfLocation, nameOfChannel);
    // A naive mean of the two rows' own averages, (100 000 + 300 000) / 2,
    // reads 200 000 — the wrong answer the registry's own average_check.v1
    // formula exists to prevent. The correct, weighted figure is summed
    // gross over summed count: 1 000 000 / 4 = 250 000.
    expect(grid.grandTotal).toBe(250_000);
    expect(grid.grandTotal).not.toBe(200_000);
  });
});

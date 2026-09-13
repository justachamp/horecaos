import { describe, expect, it } from 'vitest';

import {
  dailyAverageCheck,
  dailySeries,
  deriveAverageCheck,
  sumAcrossDays,
  sumTotal,
} from './report-rollup';
import { RowResponse } from './reporting-api';

function row(
  businessDate: string,
  channelCode: string | null,
  values: Record<string, number>,
): RowResponse {
  return {
    businessDate,
    locationId: null,
    channelCode,
    fulfilmentType: null,
    legalEntityId: null,
    values,
  };
}

describe('sumAcrossDays', () => {
  it('sums the same metric across the day rows the typed query always returns', () => {
    const rows = [
      row('2026-08-20', 'TELEGRAM', { 'orders.count.v1': 3 }),
      row('2026-08-21', 'TELEGRAM', { 'orders.count.v1': 5 }),
      row('2026-08-21', 'YANDEX', { 'orders.count.v1': 2 }),
    ];

    const byChannel = sumAcrossDays(rows, (r) => r.channelCode ?? '', ['orders.count.v1']);

    expect(byChannel.get('TELEGRAM')?.['orders.count.v1']).toBe(8);
    expect(byChannel.get('YANDEX')?.['orders.count.v1']).toBe(2);
  });

  it('treats a missing metric value as zero, not as skipped', () => {
    const rows = [row('2026-08-20', 'TELEGRAM', {})];
    const byChannel = sumAcrossDays(rows, (r) => r.channelCode ?? '', ['orders.count.v1']);
    expect(byChannel.get('TELEGRAM')?.['orders.count.v1']).toBe(0);
  });
});

describe('sumTotal', () => {
  it('collapses every row into one whole-period total', () => {
    const rows = [
      row('2026-08-20', 'TELEGRAM', { 'revenue.gross.v1': 100_000 }),
      row('2026-08-21', 'YANDEX', { 'revenue.gross.v1': 50_000 }),
    ];
    expect(sumTotal(rows, ['revenue.gross.v1'])['revenue.gross.v1']).toBe(150_000);
  });

  it('is all zeros for an empty range, never undefined', () => {
    expect(sumTotal([], ['revenue.gross.v1'])['revenue.gross.v1']).toBe(0);
  });
});

describe('dailySeries', () => {
  it('keeps the day axis sumAcrossDays/sumTotal collapse away, in date order', () => {
    const rows = [
      row('2026-08-21', null, { 'orders.count.v1': 5 }),
      row('2026-08-20', null, { 'orders.count.v1': 3 }),
    ];
    expect(dailySeries(rows, 'orders.count.v1')).toEqual([
      { date: '2026-08-20', value: 3 },
      { date: '2026-08-21', value: 5 },
    ]);
  });

  it('sums same-date rows sharing a metric — a grouped query returns one row per date per group', () => {
    const rows = [
      row('2026-08-20', 'TELEGRAM', { 'orders.count.v1': 3 }),
      row('2026-08-20', 'YANDEX', { 'orders.count.v1': 2 }),
    ];
    expect(dailySeries(rows, 'orders.count.v1')).toEqual([{ date: '2026-08-20', value: 5 }]);
  });

  it('is empty for an empty range, never a single zero-value point', () => {
    expect(dailySeries([], 'orders.count.v1')).toEqual([]);
  });
});

describe('dailyAverageCheck', () => {
  it('applies the registry formula per day, not once across the whole period', () => {
    const rows = [
      row('2026-08-20', null, { 'revenue.gross.v1': 100_000, 'orders.count.v1': 4 }),
      row('2026-08-21', null, { 'revenue.gross.v1': 90_000, 'orders.count.v1': 3 }),
    ];
    expect(dailyAverageCheck(rows)).toEqual([
      { date: '2026-08-20', value: 25_000 },
      { date: '2026-08-21', value: 30_000 },
    ]);
  });

  it('is null, never zero, on a day with no completed orders', () => {
    const rows = [row('2026-08-20', null, { 'revenue.gross.v1': 0, 'orders.count.v1': 0 })];
    expect(dailyAverageCheck(rows)).toEqual([{ date: '2026-08-20', value: null }]);
  });
});

describe('deriveAverageCheck', () => {
  it('applies the registry formula: gross over completed count', () => {
    expect(deriveAverageCheck(1_000_000, 40)).toBe(25_000);
  });

  it('truncates rather than rounds, matching MetricRegistry.average_check.v1', () => {
    expect(deriveAverageCheck(100, 3)).toBe(33);
  });

  it('is null with no completed orders — never a zero that reads as free food', () => {
    expect(deriveAverageCheck(0, 0)).toBeNull();
  });
});

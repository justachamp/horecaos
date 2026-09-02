import { describe, expect, it } from 'vitest';

import { deriveAverageCheck, sumAcrossDays, sumTotal } from './report-rollup';
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

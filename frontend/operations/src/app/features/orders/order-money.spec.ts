import { describe, expect, it } from 'vitest';

import { reconcileMoney } from './order-money';

describe('reconcileMoney', () => {
  it('reconciles when the line sum equals the subtotal', () => {
    const lines = [{ finalAmountMinor: 60_000 }, { finalAmountMinor: 86_000 }];
    const result = reconcileMoney(lines, 146_000, 146_000);
    expect(result.reconciles).toBe(true);
    expect(result.lineSumMinor).toBe(146_000);
  });

  it('does not reconcile when the line sum disagrees with the subtotal — data corruption, never a wrong total', () => {
    const lines = [{ finalAmountMinor: 60_000 }, { finalAmountMinor: 80_000 }];
    const result = reconcileMoney(lines, 146_000, 146_000);
    expect(result.reconciles).toBe(false);
    expect(result.lineSumMinor).toBe(140_000);
    expect(result.subtotalMinor).toBe(146_000);
  });

  it('reconciles an empty order (zero lines, zero subtotal)', () => {
    expect(reconcileMoney([], 0, 0).reconciles).toBe(true);
  });

  it('carries totalMinor through unexamined — the wire has no discount/fee field to check it against', () => {
    const lines = [{ finalAmountMinor: 100_000 }];
    const result = reconcileMoney(lines, 100_000, 118_000);
    // subtotal reconciles against the lines; total is simply reported.
    expect(result.reconciles).toBe(true);
    expect(result.totalMinor).toBe(118_000);
  });
});

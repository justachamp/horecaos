import { describe, expect, it } from 'vitest';

import { CountableOrder, OrderCounts, zeroTabCounts } from './order-counts';

const NOW = new Date('2026-08-30T12:00:00Z');

function order(overrides: Partial<CountableOrder>): CountableOrder {
  return {
    status: 'RECEIVED',
    createdAt: NOW,
    approvalDeadlineAt: null,
    hasBlockedProcess: false,
    ...overrides,
  };
}

describe('OrderCounts', () => {
  const counts = new OrderCounts();

  it('counts nothing for an empty page', () => {
    expect(counts.forOrders([], NOW)).toEqual(zeroTabCounts());
  });

  it('counts each order into every tab it belongs to, since attention is not a partition', () => {
    const orders = [
      order({ status: 'AWAITING_APPROVAL' }), // attention + new
      order({ status: 'RECEIVED' }), // new only
      order({ status: 'PREPARING' }), // preparing only
      order({ status: 'FULFILLING' }), // delivering only
      order({ status: 'COMPLETED' }), // completed only
      order({ status: 'CANCELLED' }), // cancelled only
    ];

    const result = counts.forOrders(orders, NOW);

    expect(result.attention).toBe(1);
    expect(result.new).toBe(2);
    expect(result.preparing).toBe(1);
    expect(result.delivering).toBe(1);
    expect(result.completed).toBe(1);
    expect(result.cancelled).toBe(1);
    expect(result.all).toBe(orders.length);
  });

  it('counts a stalled order (no promise, 45+ minutes old) into attention even though its status is not', () => {
    const stalled = order({
      status: 'PREPARING',
      createdAt: new Date(NOW.getTime() - 50 * 60 * 1000),
    });

    expect(counts.forOrders([stalled], NOW).attention).toBe(1);
  });

  it('never counts a terminal order into attention, however old', () => {
    const oldButDone = order({
      status: 'COMPLETED',
      createdAt: new Date(NOW.getTime() - 500 * 60 * 1000),
    });

    expect(counts.forOrders([oldButDone], NOW).attention).toBe(0);
  });
});

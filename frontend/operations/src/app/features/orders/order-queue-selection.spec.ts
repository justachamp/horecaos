import { describe, expect, it } from 'vitest';

import { OrderSummaryResponse } from './order-summary';
import {
  bulkAdvanceTarget,
  bulkCancelEligible,
  bulkCancelIneligibleCount,
  pruneSelection,
  toggleOne,
  toggleSelectPage,
} from './order-queue-selection';

function order(overrides: Partial<OrderSummaryResponse>): OrderSummaryResponse {
  return {
    orderId: 'order-1',
    publicOrderNumber: '0001',
    status: 'RECEIVED',
    createdAt: new Date().toISOString(),
    totalMinor: 100_000,
    currency: 'UZS',
    ...overrides,
  };
}

describe('toggleSelectPage', () => {
  it('selects every id on the page when none of them are selected yet', () => {
    const next = toggleSelectPage(new Set(), ['a', 'b', 'c']);
    expect([...next].sort()).toEqual(['a', 'b', 'c']);
  });

  it('deselects the whole page when every one of its ids is already selected', () => {
    const next = toggleSelectPage(new Set(['a', 'b', 'c']), ['a', 'b', 'c']);
    expect(next.size).toBe(0);
  });

  it('selects the rest of the page when only some of it is selected — never a silent partial toggle', () => {
    const next = toggleSelectPage(new Set(['a']), ['a', 'b', 'c']);
    expect([...next].sort()).toEqual(['a', 'b', 'c']);
  });

  it('leaves a selection made on a different page untouched', () => {
    const next = toggleSelectPage(new Set(['x']), ['a', 'b']);
    expect([...next].sort()).toEqual(['a', 'b', 'x']);
  });
});

describe('toggleOne', () => {
  it('adds an unselected id and removes a selected one', () => {
    expect([...toggleOne(new Set(), 'a')]).toEqual(['a']);
    expect([...toggleOne(new Set(['a']), 'a')]).toEqual([]);
  });
});

describe('pruneSelection — selection across a page boundary', () => {
  it('drops a selected id once its order is no longer in the loaded rows', () => {
    const pruned = pruneSelection(new Set(['a', 'b']), new Set(['b']));
    expect([...pruned]).toEqual(['b']);
  });

  it('keeps every id that is still present, in a fresh refetch’s window', () => {
    const pruned = pruneSelection(new Set(['a', 'b']), new Set(['a', 'b', 'c']));
    expect([...pruned].sort()).toEqual(['a', 'b']);
  });

  it('returns the identical instance when nothing needed pruning, so a signal does not re-render for free', () => {
    const selected = new Set(['a', 'b']);
    expect(pruneSelection(selected, new Set(['a', 'b', 'c']))).toBe(selected);
  });
});

describe('bulkCancelEligible / bulkCancelIneligibleCount', () => {
  it('is not eligible for an empty selection', () => {
    expect(bulkCancelEligible([])).toBe(false);
  });

  it('is eligible when every selected order offers CANCEL', () => {
    const selected = [
      order({ orderId: 'a', actions: [{ action: 'CANCEL' }] }),
      order({
        orderId: 'b',
        actions: [{ action: 'ADVANCE', targetStatus: 'PREPARING' }, { action: 'CANCEL' }],
      }),
    ];
    expect(bulkCancelEligible(selected)).toBe(true);
    expect(bulkCancelIneligibleCount(selected)).toBe(0);
  });

  it('counts exactly the orders missing CANCEL, and is not eligible while any are missing it', () => {
    const selected = [
      order({ orderId: 'a', actions: [{ action: 'CANCEL' }] }),
      order({ orderId: 'b', status: 'COMPLETED', actions: [] }),
      order({ orderId: 'c', status: 'CANCELLED', actions: [] }),
    ];
    expect(bulkCancelIneligibleCount(selected)).toBe(2);
    expect(bulkCancelEligible(selected)).toBe(false);
  });
});

describe('bulkAdvanceTarget', () => {
  it('is null for an empty selection', () => {
    expect(bulkAdvanceTarget([])).toBeNull();
  });

  it('names the shared target when every selected order advances to the same status', () => {
    const selected = [
      order({
        orderId: 'a',
        status: 'CONFIRMED',
        actions: [{ action: 'ADVANCE', targetStatus: 'PREPARING' }],
      }),
      order({
        orderId: 'b',
        status: 'CONFIRMED',
        actions: [{ action: 'ADVANCE', targetStatus: 'PREPARING' }],
      }),
    ];
    expect(bulkAdvanceTarget(selected)).toBe('PREPARING');
  });

  it('is null when the selected orders are not all at the same stage', () => {
    const selected = [
      order({
        orderId: 'a',
        status: 'CONFIRMED',
        actions: [{ action: 'ADVANCE', targetStatus: 'PREPARING' }],
      }),
      order({
        orderId: 'b',
        status: 'PREPARING',
        actions: [{ action: 'ADVANCE', targetStatus: 'READY' }],
      }),
    ];
    expect(bulkAdvanceTarget(selected)).toBeNull();
  });

  it('is null when any selected order has no ADVANCE action at all', () => {
    const selected = [
      order({
        orderId: 'a',
        status: 'CONFIRMED',
        actions: [{ action: 'ADVANCE', targetStatus: 'PREPARING' }],
      }),
      order({ orderId: 'b', status: 'COMPLETED', actions: [] }),
    ];
    expect(bulkAdvanceTarget(selected)).toBeNull();
  });

  it(
    'is null when the shared target is CONFIRMED — OrderBulkActionService.ADVANCE_TARGETS ' +
      'never includes it, so a RECEIVED-only selection must never offer a bulk Advance that ' +
      'the server refuses wholesale',
    () => {
      const selected = [
        order({
          orderId: 'a',
          status: 'RECEIVED',
          actions: [{ action: 'ADVANCE', targetStatus: 'CONFIRMED' }],
        }),
        order({
          orderId: 'b',
          status: 'RECEIVED',
          actions: [{ action: 'ADVANCE', targetStatus: 'CONFIRMED' }],
        }),
      ];
      expect(bulkAdvanceTarget(selected)).toBeNull();
    },
  );

  it('names PREPARING/READY/FULFILLING — every target OrderBulkActionService.ADVANCE_TARGETS allows', () => {
    for (const target of ['PREPARING', 'READY', 'FULFILLING']) {
      const selected = [
        order({
          orderId: 'a',
          status: target,
          actions: [{ action: 'ADVANCE', targetStatus: target }],
        }),
      ];
      expect(bulkAdvanceTarget(selected)).toBe(target);
    }
  });
});

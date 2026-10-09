import { describe, expect, it } from 'vitest';

import { SAVED_VIEW_DATE_RANGES, SavedView } from '../../core/preferences/preference-documents';
import { EMPTY_ORDER_QUEUE_FILTERS, OrderQueueFilters } from './order-queue-filter-state';
import {
  filtersFromView,
  savedViewRange,
  savedViewRangeToken,
  viewFiltersFrom,
} from './order-saved-view-filters';

/** Thursday 8 October 2026, midday, in the viewer's own calendar. */
const NOW = new Date(2026, 9, 8, 12, 0, 0);

function board(patch: Partial<OrderQueueFilters>): OrderQueueFilters {
  return { ...EMPTY_ORDER_QUEUE_FILTERS, ...patch };
}

function view(filters: SavedView['filters']): SavedView {
  return { id: '0198a3c0-0000-7000-8000-000000000001', label: 'Late', tab: 'delivering', filters };
}

describe('a saved view’s period (ADR 0160)', () => {
  it('resolves each relative token to the days it means today, the way the date picker’s presets do', () => {
    expect(savedViewRange('TODAY', NOW)).toEqual({ start: '2026-10-08', end: '2026-10-08' });
    expect(savedViewRange('YESTERDAY', NOW)).toEqual({ start: '2026-10-07', end: '2026-10-07' });
    expect(savedViewRange('LAST_7_DAYS', NOW)).toEqual({ start: '2026-10-02', end: '2026-10-08' });
    expect(savedViewRange('THIS_MONTH', NOW)).toEqual({ start: '2026-10-01', end: '2026-10-08' });
  });

  it('means today’s orders on whatever day the view is opened, which is why it is a token and not a date', () => {
    const friday = new Date(2026, 9, 9, 8, 0, 0);

    expect(savedViewRange('TODAY', friday)).toEqual({ start: '2026-10-09', end: '2026-10-09' });
    expect(savedViewRange('TODAY', NOW)).not.toEqual(savedViewRange('TODAY', friday));
  });

  it('names a range that is exactly one of the four, and nothing else', () => {
    for (const token of SAVED_VIEW_DATE_RANGES) {
      expect(savedViewRangeToken(savedViewRange(token, NOW), NOW)).toBe(token);
    }
    expect(savedViewRangeToken({ start: '2026-09-01', end: '2026-09-30' }, NOW)).toBeNull();
    expect(savedViewRangeToken({ start: '2026-10-08', end: '2026-10-09' }, NOW)).toBeNull();
  });
});

describe('what a saved view keeps of the board (ADR 0160)', () => {
  it('keeps the closed list of filters, with a false switch left out', () => {
    const { filters, leftOut } = viewFiltersFrom(
      board({
        allBranches: true,
        channelCode: 'TELEGRAM',
        origin: 'MARKETPLACE',
        fulfillmentMode: 'DELIVERY',
        paymentMethodCode: 'CLICK',
        paymentStatus: 'PENDING',
        fiscalStatus: 'ATTENTION',
        lateOnly: true,
        problemOnly: false,
        callbackRequested: true,
        mineOnly: true,
        dateRange: savedViewRange('TODAY', NOW),
      }),
      NOW,
    );

    expect(filters).toEqual({
      allBranches: true,
      channelCode: 'TELEGRAM',
      origin: 'MARKETPLACE',
      fulfillmentMode: 'DELIVERY',
      paymentMethodCode: 'CLICK',
      paymentStatus: 'PENDING',
      fiscalStatus: 'ATTENTION',
      lateOnly: true,
      callbackRequested: true,
      mineOnly: true,
      dateRange: 'TODAY',
    });
    expect(leftOut).toBe(false);
  });

  it('never keeps the search box, a courier, an aggregator, the branch of «Все филиалы» or an exact period, and says so', () => {
    for (const patch of [
      { reference: '1042' },
      { courierId: 'courier-1' },
      { marketplaceBindingId: 'binding-1' },
      { locationId: 'l2' },
      { dateRange: { start: '2026-09-01', end: '2026-09-30' } },
    ] satisfies Partial<OrderQueueFilters>[]) {
      const { filters, leftOut } = viewFiltersFrom(board({ ...patch, lateOnly: true }), NOW);

      expect(leftOut, JSON.stringify(patch)).toBe(true);
      expect(filters).toEqual({ lateOnly: true });
      expect(JSON.stringify(filters)).not.toContain('1042');
      expect(JSON.stringify(filters)).not.toContain('courier-1');
    }
  });

  it('keeps nothing from an empty board and has nothing to say about it', () => {
    expect(viewFiltersFrom(EMPTY_ORDER_QUEUE_FILTERS, NOW)).toEqual({
      filters: {},
      leftOut: false,
    });
  });

  it('opens a view as exactly its filters over an empty board, not over whatever was on', () => {
    const opened = filtersFromView(view({ lateOnly: true, dateRange: 'YESTERDAY' }), NOW, {
      canViewAllBranches: true,
    });

    expect(opened).toEqual({
      ...EMPTY_ORDER_QUEUE_FILTERS,
      lateOnly: true,
      dateRange: { start: '2026-10-07', end: '2026-10-07' },
    });
    expect(opened.reference).toBe('');
    expect(opened.courierId).toBeNull();
  });

  it('falls back to the one-branch board for a view saved on «Все филиалы» by someone who cannot read the brand now', () => {
    const saved = view({ allBranches: true, mineOnly: true });

    expect(filtersFromView(saved, NOW, { canViewAllBranches: true }).allBranches).toBe(true);
    const withoutMode = filtersFromView(saved, NOW, { canViewAllBranches: false });
    expect(withoutMode.allBranches).toBe(false);
    expect(withoutMode.mineOnly).toBe(true);
  });

  it('round-trips: what a view keeps is what it opens', () => {
    const original = board({
      channelCode: 'WEB',
      fulfillmentMode: 'PICKUP',
      paymentStatus: 'CAPTURED',
      problemOnly: true,
      dateRange: savedViewRange('LAST_7_DAYS', NOW),
    });

    const { filters } = viewFiltersFrom(original, NOW);
    const reopened = filtersFromView(view(filters), NOW, { canViewAllBranches: false });

    expect(reopened).toEqual(original);
  });
});

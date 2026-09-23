import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import {
  EMPTY_ORDER_QUEUE_FILTERS,
  OrderQueueFilterState,
  boardQueryParams,
} from './order-queue-filter-state';

function service(): OrderQueueFilterState {
  return TestBed.inject(OrderQueueFilterState);
}

describe('OrderQueueFilterState', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({});
  });

  it('starts at the empty filter set for a tab it has never seen', () => {
    const state = service();
    state.loadForTab('attention');
    expect(state.current()).toEqual(EMPTY_ORDER_QUEUE_FILTERS);
    expect(state.hasActive()).toBe(false);
  });

  it('keeps each tab’s own filters independent — setting one does not leak into another', () => {
    const state = service();
    state.loadForTab('attention');
    state.update({ mineOnly: true });

    state.loadForTab('completed');
    expect(state.current().mineOnly).toBe(false);

    state.loadForTab('attention');
    expect(state.current().mineOnly).toBe(true);
  });

  it('survives what a full reload does — a fresh service instance reads the same tab’s filters back', () => {
    const first = service();
    first.loadForTab('preparing');
    first.update({ channelCode: 'wolt', fulfillmentMode: 'DELIVERY' });

    // `TestBed.resetTestingModule` is what actually discards the current
    // injector — re-injecting without it would just hand back the same
    // cached singleton, which would pass this assertion even if
    // `readStored`/`writeStored` did nothing at all.
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    const fresh = TestBed.inject(OrderQueueFilterState);
    expect(fresh).not.toBe(first);
    fresh.loadForTab('preparing');

    expect(fresh.current().channelCode).toBe('wolt');
    expect(fresh.current().fulfillmentMode).toBe('DELIVERY');
  });

  it('reports hasActive false for the default period-only state and true once anything else is set', () => {
    const state = service();
    state.loadForTab('all');
    expect(state.hasActive()).toBe(false);

    state.update({ courierId: 'courier-1' });
    expect(state.hasActive()).toBe(true);
  });

  it('reset clears every field except the date range, per §2.4', () => {
    const state = service();
    state.loadForTab('all');
    state.update({
      dateRange: { start: '2026-09-01', end: '2026-09-07' },
      channelCode: 'wolt',
      mineOnly: true,
      reference: '0911-142',
    });

    state.reset();

    expect(state.current()).toEqual({
      ...EMPTY_ORDER_QUEUE_FILTERS,
      dateRange: { start: '2026-09-01', end: '2026-09-07' },
    });
  });

  it('persists the reset too, so a reload does not resurrect the cleared filters', () => {
    const first = service();
    first.loadForTab('new');
    first.update({ mineOnly: true });
    first.reset();

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    const fresh = TestBed.inject(OrderQueueFilterState);
    fresh.loadForTab('new');
    expect(fresh.current().mineOnly).toBe(false);
  });

  it('boardQueryParams: sends nothing for the default, unfiltered set', () => {
    expect(boardQueryParams(EMPTY_ORDER_QUEUE_FILTERS, 'actor-1')).toEqual({});
  });

  it('boardQueryParams: turns the calendar date range into a fixed-offset from/to pair', () => {
    const params = boardQueryParams(
      { ...EMPTY_ORDER_QUEUE_FILTERS, dateRange: { start: '2026-09-01', end: '2026-09-07' } },
      null,
    );
    expect(params['from']).toBe('2026-09-01T00:00:00+05:00');
    expect(params['to']).toBe('2026-09-07T23:59:59+05:00');
  });

  it('boardQueryParams: sends createdByActorId only when mineOnly is set and the actor id is known', () => {
    expect(
      boardQueryParams({ ...EMPTY_ORDER_QUEUE_FILTERS, mineOnly: true }, null),
    ).not.toHaveProperty('createdByActorId');
    expect(boardQueryParams({ ...EMPTY_ORDER_QUEUE_FILTERS, mineOnly: true }, 'actor-1')).toEqual({
      createdByActorId: 'actor-1',
    });
    expect(boardQueryParams({ ...EMPTY_ORDER_QUEUE_FILTERS, mineOnly: false }, 'actor-1')).toEqual(
      {},
    );
  });

  it('boardQueryParams: sends the exact-match reference only from two characters — §2.8', () => {
    expect(boardQueryParams({ ...EMPTY_ORDER_QUEUE_FILTERS, reference: 'a' }, null)).toEqual({});
    expect(
      boardQueryParams({ ...EMPTY_ORDER_QUEUE_FILTERS, reference: ' 0911-142 ' }, null),
    ).toEqual({ reference: '0911-142' });
  });

  it('boardQueryParams: passes channel, fulfilment type, courier and payment method straight through', () => {
    const params = boardQueryParams(
      {
        ...EMPTY_ORDER_QUEUE_FILTERS,
        channelCode: 'wolt',
        fulfillmentMode: 'DELIVERY',
        courierId: 'courier-1',
        paymentMethodCode: 'CASH',
      },
      null,
    );
    expect(params).toEqual({
      channelCode: 'wolt',
      fulfillmentMode: 'DELIVERY',
      courierId: 'courier-1',
      paymentMethodCode: 'CASH',
    });
  });

  it('boardQueryParams: passes origin («Источник», wave 9 row 1.1c) straight through', () => {
    expect(
      boardQueryParams({ ...EMPTY_ORDER_QUEUE_FILTERS, origin: 'MARKETPLACE' }, null),
    ).toEqual({ origin: 'MARKETPLACE' });
    expect(boardQueryParams({ ...EMPTY_ORDER_QUEUE_FILTERS, origin: 'HORECAOS' }, null)).toEqual({
      origin: 'HORECAOS',
    });
  });

  it('origin counts toward hasActive and survives a reload like the other filters', () => {
    const state = service();
    state.loadForTab('all');
    state.update({ origin: 'MARKETPLACE' });
    expect(state.hasActive()).toBe(true);

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    const fresh = TestBed.inject(OrderQueueFilterState);
    fresh.loadForTab('all');
    expect(fresh.current().origin).toBe('MARKETPLACE');
  });

  it('never throws when localStorage is unavailable, and simply does not persist', () => {
    const original = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true,
      get() {
        throw new Error('storage disabled');
      },
    });
    try {
      const state = service();
      expect(() => state.loadForTab('attention')).not.toThrow();
      expect(() => state.update({ mineOnly: true })).not.toThrow();
      expect(state.current().mineOnly).toBe(true);
    } finally {
      if (original) {
        Object.defineProperty(globalThis, 'localStorage', original);
      }
    }
  });
});

import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { TableFilterStore } from './table-filter-store';

interface QueueFilters {
  readonly status: string;
  readonly courierId: string | null;
}

describe('TableFilterStore', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it('returns the fallback when nothing was ever saved', () => {
    const store = TestBed.inject(TableFilterStore);
    expect(store.load<QueueFilters>('orders.queue', { status: 'ALL', courierId: null })).toEqual({
      status: 'ALL',
      courierId: null,
    });
  });

  it('persists filters and reloads them across what a page reload looks like — a fresh injector', () => {
    const firstInstance = TestBed.inject(TableFilterStore);
    firstInstance.save<QueueFilters>('orders.queue', { status: 'LATE', courierId: 'c-1' });

    // A reload tears down every Angular service and rebuilds the app from
    // scratch; only `localStorage` survives. Re-creating the testbed (a new
    // TableFilterStore instance backed by the same `window.localStorage`) is
    // the fixture-level stand-in for that survival.
    TestBed.resetTestingModule();
    const afterReload = TestBed.inject(TableFilterStore);

    expect(
      afterReload.load<QueueFilters>('orders.queue', { status: 'ALL', courierId: null }),
    ).toEqual({
      status: 'LATE',
      courierId: 'c-1',
    });
  });

  it('keeps two views separate under two keys', () => {
    const store = TestBed.inject(TableFilterStore);
    store.save('orders.queue', { status: 'LATE' });
    store.save('catalog.products', { status: 'DRAFT' });

    expect(store.load('orders.queue', null)).toEqual({ status: 'LATE' });
    expect(store.load('catalog.products', null)).toEqual({ status: 'DRAFT' });
  });

  it('clears a view without touching another', () => {
    const store = TestBed.inject(TableFilterStore);
    store.save('orders.queue', { status: 'LATE' });
    store.save('catalog.products', { status: 'DRAFT' });

    store.clear('orders.queue');

    expect(store.load('orders.queue', 'fallback')).toBe('fallback');
    expect(store.load('catalog.products', null)).toEqual({ status: 'DRAFT' });
  });

  it('falls back rather than throwing when a stored value is not valid JSON', () => {
    window.localStorage.setItem('q-data-table.filters.orders.queue', '{not json');
    const store = TestBed.inject(TableFilterStore);

    expect(store.load('orders.queue', 'fallback')).toBe('fallback');
  });

  it('degrades to the fallback rather than throwing when storage itself is unavailable', () => {
    const store = TestBed.inject(TableFilterStore);
    const spy = vi.spyOn(window.localStorage, 'getItem').mockImplementation(() => {
      throw new DOMException('blocked');
    });

    expect(store.load('orders.queue', 'fallback')).toBe('fallback');
    spy.mockRestore();
  });
});

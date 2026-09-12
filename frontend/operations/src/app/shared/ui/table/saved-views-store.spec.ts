import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { SavedViewsStore } from './saved-views-store';

describe('SavedViewsStore', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it('starts empty', () => {
    const store = TestBed.inject(SavedViewsStore);
    expect(store.list('orders.queue')).toEqual([]);
  });

  it('saves a named view and lists it back with a generated id', () => {
    const store = TestBed.inject(SavedViewsStore);
    const saved = store.save('orders.queue', 'Моя смена', { status: 'LATE', courierId: 'c-1' });

    expect(saved.id).toBeTruthy();
    expect(store.list('orders.queue')).toEqual([saved]);
  });

  it('keeps views for different screens apart', () => {
    const store = TestBed.inject(SavedViewsStore);
    store.save('orders.queue', 'Просрочено', { status: 'LATE' });
    store.save('catalog.products', 'Без ИКПУ', { status: 'NO_MXIK' });

    expect(store.list('orders.queue').map((v) => v.name)).toEqual(['Просрочено']);
    expect(store.list('catalog.products').map((v) => v.name)).toEqual(['Без ИКПУ']);
  });

  it('removes one view without disturbing the rest', () => {
    const store = TestBed.inject(SavedViewsStore);
    const first = store.save('orders.queue', 'A', { status: 'A' });
    const second = store.save('orders.queue', 'B', { status: 'B' });

    store.remove('orders.queue', first.id);

    expect(store.list('orders.queue')).toEqual([second]);
  });

  it('survives what a reload looks like — a fresh injector over the same localStorage', () => {
    const before = TestBed.inject(SavedViewsStore);
    before.save('orders.queue', 'Просрочено', { status: 'LATE' });

    TestBed.resetTestingModule();
    const after = TestBed.inject(SavedViewsStore);

    expect(after.list('orders.queue').map((v) => v.name)).toEqual(['Просрочено']);
  });
});

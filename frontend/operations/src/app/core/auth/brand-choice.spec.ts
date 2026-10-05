import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { BrandChoice } from './brand-choice';

const STORAGE_KEY = 'horecaos.operations.brandId';

const TWO = [
  { id: 'b1', displayName: 'Rayhon' },
  { id: 'b2', displayName: 'Evos' },
];

describe('BrandChoice', () => {
  beforeEach(() => localStorage.removeItem(STORAGE_KEY));
  afterEach(() => {
    localStorage.removeItem(STORAGE_KEY);
    vi.restoreAllMocks();
  });

  const choice = (): BrandChoice => TestBed.inject(BrandChoice);

  it('has nothing to choose, and no brand, until a brand list has been read', () => {
    expect(choice().options()).toEqual([]);
    expect(choice().multiBrand()).toBe(false);
    expect(choice().brandId()).toBeNull();
  });

  it('is a choice only with more than one brand: a picker with one option is noise', () => {
    choice().offer([TWO[0]]);
    expect(choice().multiBrand()).toBe(false);
    expect(choice().brandId()).toBe('b1');

    choice().offer(TWO);
    expect(choice().multiBrand()).toBe(true);
  });

  it('takes the first brand until the operator picks one', () => {
    choice().offer(TWO);
    expect(choice().brandId()).toBe('b1');
  });

  it('remembers a pick across a reload', () => {
    choice().offer(TWO);
    choice().select('b2');
    expect(choice().brandId()).toBe('b2');
    expect(localStorage.getItem(STORAGE_KEY)).toBe('b2');

    TestBed.resetTestingModule();
    choice().offer(TWO);
    expect(choice().brandId()).toBe('b2');
  });

  it('counts the picks that change the brand in effect, so a consumer can tell a pick made since it last looked', () => {
    choice().offer(TWO);
    expect(choice().picks()).toBe(0);

    choice().select('b2');
    expect(choice().picks()).toBe(1);

    // Picking what is already in effect changes nothing.
    choice().select('b2');
    expect(choice().picks()).toBe(1);

    choice().select('b1');
    expect(choice().picks()).toBe(2);
  });

  it('falls back to the first brand when the remembered one is no longer offered', () => {
    localStorage.setItem(STORAGE_KEY, 'gone');
    choice().offer(TWO);
    expect(choice().brandId()).toBe('b1');
  });

  it('keeps the pick in memory when storage refuses it, rather than failing the page', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('storage disabled');
    });
    choice().offer(TWO);

    choice().select('b2');

    expect(choice().brandId()).toBe('b2');
  });
});

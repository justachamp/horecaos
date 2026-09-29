import { TestBed } from '@angular/core/testing';

import { ReturnDestination } from './return-destination';

const KEY = 'horecaos_sign_in_return_to';

function fresh(): ReturnDestination {
  TestBed.configureTestingModule({});
  return TestBed.inject(ReturnDestination);
}

describe('ReturnDestination', () => {
  beforeEach(() => {
    sessionStorage.clear();
    vi.useRealTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('hands back the table screen a guest signed in from', () => {
    const destination = fresh();

    destination.remember('/dine-in/table');

    expect(destination.consume()).toBe('/dine-in/table');
  });

  it('is spent by the sign-in that used it', () => {
    const destination = fresh();
    destination.remember('/dine-in/table');

    expect(destination.consume()).toBe('/dine-in/table');
    expect(destination.consume()).toBeNull();
    expect(sessionStorage.getItem(KEY)).toBeNull();
  });

  it('is null when nothing was remembered, so an ordinary sign-in goes where it always did', () => {
    expect(fresh().consume()).toBeNull();
  });

  it('keeps the destination in sessionStorage, never localStorage', () => {
    const destination = fresh();

    destination.remember('/dine-in/table');

    expect(sessionStorage.getItem(KEY)).not.toBeNull();
    expect(localStorage.getItem(KEY)).toBeNull();
  });

  it('never holds a table token: the scan route is not a returnable path', () => {
    const destination = fresh();

    destination.remember('/dine-in/tok_live_abc123');

    expect(sessionStorage.getItem(KEY)).toBeNull();
    expect(destination.consume()).toBeNull();
  });

  it.each([
    'https://evil.example/dine-in/table',
    '//evil.example',
    '/dine-in/table?next=https://evil.example',
    '/dine-in/table#x',
    '/auth/login',
    '/cart',
    '',
  ])('refuses to remember %s', (path) => {
    const destination = fresh();

    destination.remember(path);

    expect(sessionStorage.getItem(KEY)).toBeNull();
    expect(destination.consume()).toBeNull();
  });

  it('refuses a tampered stored value even though it was not written through remember()', () => {
    sessionStorage.setItem(KEY, JSON.stringify({ path: 'https://evil.example', at: Date.now() }));

    expect(fresh().consume()).toBeNull();
  });

  it('refuses a stored value that is not JSON at all', () => {
    sessionStorage.setItem(KEY, 'not json');

    expect(fresh().consume()).toBeNull();
  });

  it('forgets a destination remembered more than half an hour ago', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-09-29T09:00:00Z'));
    const destination = fresh();
    destination.remember('/dine-in/table');

    vi.setSystemTime(new Date('2026-09-29T09:31:00Z'));

    expect(destination.consume()).toBeNull();
  });

  it('still honours a destination remembered a few minutes ago', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-09-29T09:00:00Z'));
    const destination = fresh();
    destination.remember('/dine-in/table');

    vi.setSystemTime(new Date('2026-09-29T09:05:00Z'));

    expect(destination.consume()).toBe('/dine-in/table');
  });

  it('degrades to "nowhere to return to" when sessionStorage throws', () => {
    const destination = fresh();
    const original = Storage.prototype.setItem;
    Storage.prototype.setItem = () => {
      throw new Error('blocked');
    };
    try {
      expect(() => destination.remember('/dine-in/table')).not.toThrow();
    } finally {
      Storage.prototype.setItem = original;
    }
    expect(destination.consume()).toBeNull();
  });
});

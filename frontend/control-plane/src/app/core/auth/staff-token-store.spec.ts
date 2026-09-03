import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { REFRESH_TOKEN_KEY, StaffTokenStore } from './staff-token-store';

describe('StaffTokenStore', () => {
  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({});
  });

  it('keeps the access token in memory only', () => {
    const store = TestBed.inject(StaffTokenStore);
    store.set('an-access-token', 'a-refresh-token');

    expect(store.accessToken()).toBe('an-access-token');
    // Nothing sessionStorage holds, under any key, should be the access
    // token — only the refresh token belongs there.
    const stored = Array.from({ length: sessionStorage.length }, (_, i) =>
      sessionStorage.getItem(sessionStorage.key(i) ?? ''),
    );
    expect(stored).not.toContain('an-access-token');
    expect(stored).toContain('a-refresh-token');
  });

  it('persists the refresh token to sessionStorage under the exported key', () => {
    const store = TestBed.inject(StaffTokenStore);
    store.set('an-access-token', 'a-refresh-token');

    expect(sessionStorage.getItem(REFRESH_TOKEN_KEY)).toBe('a-refresh-token');
    expect(store.refreshToken()).toBe('a-refresh-token');
  });

  it('survives what a reload does to this class — a fresh instance still finds the stored refresh token', () => {
    const before = TestBed.inject(StaffTokenStore);
    before.set('an-access-token', 'a-refresh-token');

    // A reload rebuilds every service from nothing; sessionStorage is the one
    // thing that survives it. A brand new instance approximates that: it
    // shares no in-memory state with `before` at all.
    const after = new StaffTokenStore();
    expect(after.refreshToken()).toBe('a-refresh-token');
    // ...but never the access token, which is exactly the point.
    expect(after.accessToken()).toBeNull();
  });

  it('clear() removes the refresh token from sessionStorage, not just from memory', () => {
    const store = TestBed.inject(StaffTokenStore);
    store.set('an-access-token', 'a-refresh-token');

    store.clear();

    expect(store.accessToken()).toBeNull();
    expect(store.refreshToken()).toBeNull();
    expect(sessionStorage.getItem(REFRESH_TOKEN_KEY)).toBeNull();
  });

  it('reports no refresh token when nothing has ever been stored', () => {
    const store = TestBed.inject(StaffTokenStore);
    expect(store.refreshToken()).toBeNull();
  });
});

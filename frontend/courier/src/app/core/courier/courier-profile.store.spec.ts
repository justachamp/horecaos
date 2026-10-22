import { TestBed } from '@angular/core/testing';

import { courierProfile } from '../../../testing/fixtures';
import { ApiError } from '../api/problem-details';
import { CourierApi } from './courier-api';
import { CourierProfileStore } from './courier-profile.store';

describe('CourierProfileStore', () => {
  let me: ReturnType<typeof vi.fn>;
  let store: CourierProfileStore;

  beforeEach(() => {
    me = vi.fn();
    TestBed.configureTestingModule({ providers: [{ provide: CourierApi, useValue: { me } }] });
    store = TestBed.inject(CourierProfileStore);
  });

  it('starts idle, with nothing to show', () => {
    expect(store.state()).toBe('idle');
    expect(store.profile()).toBeNull();
  });

  it('holds the record the platform returns', async () => {
    me.mockResolvedValue(courierProfile());

    await store.load();

    expect(store.state()).toBe('ready');
    expect(store.profile()?.displayReference).toBe('REF-00E1');
    expect(store.primaryBranch()?.displayName).toBe('Chilonzor');
  });

  it('shows "loading" only until the first answer, not on a reload', async () => {
    me.mockResolvedValueOnce(courierProfile());
    await store.load();
    let release: (value: ReturnType<typeof courierProfile>) => void = () => undefined;
    me.mockReturnValueOnce(new Promise((resolve) => (release = resolve)));

    const reloading = store.load();

    expect(store.state()).toBe('ready');
    release(courierProfile({ displayReference: 'REF-NEW' }));
    await reloading;
    expect(store.profile()?.displayReference).toBe('REF-NEW');
  });

  it('treats a 404 as "this account is not a courier here" -- a state, not a failure to retry', async () => {
    me.mockRejectedValue(
      new ApiError({
        status: 404,
        code: 'RESOURCE_NOT_FOUND',
        detail: 'The caller is not a courier',
      }),
    );

    await store.load();

    expect(store.state()).toBe('not-registered');
    expect(store.profile()).toBeNull();
  });

  it('reports any other failure of a first load as an error the courier can retry', async () => {
    me.mockRejectedValue(new ApiError({ status: 0, code: 'NETWORK', detail: 'offline' }));

    await store.load();

    expect(store.state()).toBe('error');
    expect(store.error()?.isNetwork).toBe(true);
  });

  it('keeps the record on screen when a reload fails, rather than blanking a courier mid-shift', async () => {
    me.mockResolvedValueOnce(courierProfile());
    await store.load();
    me.mockRejectedValueOnce(new ApiError({ status: 0, code: 'NETWORK', detail: 'offline' }));

    await store.load();

    expect(store.state()).toBe('ready');
    expect(store.profile()).not.toBeNull();
    expect(store.error()?.isNetwork).toBe(true);
  });

  it('forgets the record when the platform stops knowing the courier', async () => {
    me.mockResolvedValueOnce(courierProfile());
    await store.load();
    me.mockRejectedValueOnce(
      new ApiError({ status: 404, code: 'RESOURCE_NOT_FOUND', detail: 'gone' }),
    );

    await store.load();

    expect(store.state()).toBe('not-registered');
    expect(store.profile()).toBeNull();
  });

  it('clears the previous error once a load succeeds', async () => {
    me.mockRejectedValueOnce(new ApiError({ status: 500, code: 'INTERNAL_ERROR', detail: 'x' }));
    await store.load();
    me.mockResolvedValueOnce(courierProfile());

    await store.load();

    expect(store.error()).toBeNull();
  });
  describe('the branch the courier works at', () => {
    it('is the open shift’s branch, even when it is not the main one', async () => {
      const profile = courierProfile();
      me.mockResolvedValue({
        ...profile,
        openShift: { ...profile.openShift!, locationId: profile.branches[1].locationId },
      });

      await store.load();

      expect(store.workBranch()).toEqual({
        brandId: profile.branches[1].brandId,
        locationId: profile.branches[1].locationId,
      });
      expect(store.shiftBranch()).toEqual(store.workBranch());
    });

    it('is the main branch when no shift is open, and the shift branch is then nothing', async () => {
      const profile = courierProfile({ openShift: null });
      me.mockResolvedValue(profile);

      await store.load();

      expect(store.workBranch()).toEqual({
        brandId: profile.branches[0].brandId,
        locationId: profile.branches[0].locationId,
      });
      expect(store.shiftBranch()).toBeNull();
    });

    it('is nothing for a courier bound to no branch', async () => {
      me.mockResolvedValue(courierProfile({ openShift: null, branches: [] }));

      await store.load();

      expect(store.workBranch()).toBeNull();
    });
  });

  it('loads once for the screens that only need the record to be there', async () => {
    me.mockResolvedValue(courierProfile());

    await store.ensureLoaded();
    await store.ensureLoaded();

    expect(me).toHaveBeenCalledOnce();
  });
});

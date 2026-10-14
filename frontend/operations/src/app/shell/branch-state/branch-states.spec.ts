import { DestroyRef, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import {
  LocationServiceStateView,
  LocationsApi,
} from '../../features/settings/locations/locations-api';
import { BRANCH_STATE_POLL_MS, BranchStates } from './branch-states';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function state(overrides: Partial<LocationServiceStateView>): LocationServiceStateView {
  return {
    locationId: 'l1',
    mode: 'FOLLOW_SCHEDULE',
    effectiveMode: 'FOLLOW_SCHEDULE',
    reasonCode: null,
    effectiveUntil: null,
    ...overrides,
  };
}

describe('BranchStates', () => {
  let api: {
    serviceStates: ReturnType<typeof vi.fn>;
    list: ReturnType<typeof vi.fn>;
    changeServiceState: ReturnType<typeof vi.fn>;
  };
  let service: BranchStates;

  function setUp(initial: readonly LocationServiceStateView[]): void {
    api = {
      serviceStates: vi.fn().mockResolvedValue(initial),
      list: vi.fn().mockResolvedValue([
        { id: 'l1', timezone: 'Asia/Tashkent' },
        { id: 'l2', timezone: 'Asia/Samarkand' },
      ]),
      changeServiceState: vi.fn().mockResolvedValue(undefined),
    };
    TestBed.configureTestingModule({
      providers: [
        { provide: LocationsApi, useValue: api },
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(SCOPE),
            options: signal([
              { id: 'l1', displayName: 'Chilonzor', status: 'ACTIVE' },
              { id: 'l2', displayName: 'Yunusobod', status: 'ACTIVE' },
            ]),
            ensureLoaded: () => Promise.resolve(),
          },
        },
      ],
    });
    service = TestBed.inject(BranchStates);
  }

  afterEach(() => {
    vi.useRealTimers();
  });

  it('lists the branches whose effective mode is closed, named from the branch picker, in the server’s order', async () => {
    setUp([
      state({
        locationId: 'l2',
        mode: 'FORCE_CLOSED',
        effectiveMode: 'FORCE_CLOSED',
        effectiveUntil: '2026-10-12T16:30:00Z',
      }),
      state({ locationId: 'l1', mode: 'FORCE_CLOSED', effectiveMode: 'FORCE_CLOSED' }),
    ]);

    await service.refresh();

    expect(service.closed().map((branch) => [branch.name, branch.until])).toEqual([
      ['Yunusobod', '2026-10-12T16:30:00Z'],
      ['Chilonzor', null],
    ]);
  });

  it('does not count a branch whose manual close has run out: it reads as back on the schedule', async () => {
    setUp([
      state({
        mode: 'FORCE_CLOSED',
        effectiveMode: 'FOLLOW_SCHEDULE',
        effectiveUntil: '2026-10-12T08:00:00Z',
      }),
    ]);

    await service.refresh();

    expect(service.closed()).toEqual([]);
  });

  it('does not count a branch forced open, or one following its schedule', async () => {
    setUp([
      state({ locationId: 'l1', mode: 'FORCE_OPEN', effectiveMode: 'FORCE_OPEN' }),
      state({ locationId: 'l2' }),
    ]);

    await service.refresh();

    expect(service.closed()).toEqual([]);
  });

  it('knows the state of the branch the operator is looking at', async () => {
    setUp([
      state({ locationId: 'l1', mode: 'FORCE_OPEN', effectiveMode: 'FORCE_OPEN' }),
      state({ locationId: 'l2' }),
    ]);

    await service.refresh();

    expect(service.current()?.effectiveMode).toBe('FORCE_OPEN');
  });

  it('gives each closed branch its own time zone, read once however often it polls', async () => {
    setUp([state({ locationId: 'l2', mode: 'FORCE_CLOSED', effectiveMode: 'FORCE_CLOSED' })]);

    await service.refresh();
    await service.refresh();

    expect(service.closed()[0].timeZone).toBe('Asia/Samarkand');
    expect(api.list).toHaveBeenCalledTimes(1);
  });

  it('is denied, and empty, when the read is refused', async () => {
    setUp([]);
    api.serviceStates.mockRejectedValue(new ApiError('INSUFFICIENT_CAPABILITY', 403, null, null));

    await service.refresh();

    expect(service.denied()).toBe(true);
    expect(service.closed()).toEqual([]);
    expect(service.loaded()).toBe(true);
  });

  it('keeps the last answer when a poll fails for any other reason: a strip that vanishes hides a closed branch', async () => {
    setUp([state({ mode: 'FORCE_CLOSED', effectiveMode: 'FORCE_CLOSED' })]);
    await service.refresh();
    api.serviceStates.mockRejectedValue(new ApiError('SERVICE_UNAVAILABLE', 503, null, null));

    await service.refresh();

    expect(service.denied()).toBe(false);
    expect(service.closed()).toHaveLength(1);
  });

  it('reopen sends the schedule mode for that branch and reads again', async () => {
    setUp([state({ locationId: 'l2', mode: 'FORCE_CLOSED', effectiveMode: 'FORCE_CLOSED' })]);
    await service.refresh();
    api.serviceStates.mockResolvedValue([state({ locationId: 'l2' })]);

    await service.reopen('l2');

    expect(api.changeServiceState).toHaveBeenCalledWith(
      { tenantId: 't1', brandId: 'b1', locationId: 'l2' },
      { mode: 'FOLLOW_SCHEDULE' },
    );
    expect(service.closed()).toEqual([]);
  });

  it('a failed write rejects with the server’s error and leaves the list as it was', async () => {
    setUp([state({ mode: 'FORCE_CLOSED', effectiveMode: 'FORCE_CLOSED' })]);
    await service.refresh();
    const refusal = new ApiError('INSUFFICIENT_CAPABILITY', 403, null, null);
    api.changeServiceState.mockRejectedValue(refusal);

    await expect(service.reopen('l1')).rejects.toBe(refusal);

    expect(service.closed()).toHaveLength(1);
  });

  it('shares one request among callers that ask together', async () => {
    setUp([]);

    await Promise.all([service.refresh(), service.refresh(), service.refresh()]);

    expect(api.serviceStates).toHaveBeenCalledTimes(1);
  });

  describe('watching', () => {
    beforeEach(() => {
      vi.useFakeTimers();
    });

    function watcher(): { destroy: () => void } {
      let destroy: () => void = () => undefined;
      const ref = {
        onDestroy: (callback: () => void) => (destroy = callback),
      } as unknown as DestroyRef;
      service.watch(ref);
      return { destroy: () => destroy() };
    }

    it('reads at once and then every half minute, for as long as anyone watches', async () => {
      setUp([]);
      const first = watcher();
      await vi.advanceTimersByTimeAsync(0);
      expect(api.serviceStates).toHaveBeenCalledTimes(1);

      await vi.advanceTimersByTimeAsync(BRANCH_STATE_POLL_MS);
      await vi.advanceTimersByTimeAsync(BRANCH_STATE_POLL_MS);
      expect(api.serviceStates).toHaveBeenCalledTimes(3);

      first.destroy();
      await vi.advanceTimersByTimeAsync(BRANCH_STATE_POLL_MS * 3);
      expect(api.serviceStates).toHaveBeenCalledTimes(3);
    });

    it('keeps polling until the last of two watchers has gone', async () => {
      setUp([]);
      const first = watcher();
      const second = watcher();
      await vi.advanceTimersByTimeAsync(0);
      expect(api.serviceStates).toHaveBeenCalledTimes(1);

      first.destroy();
      await vi.advanceTimersByTimeAsync(BRANCH_STATE_POLL_MS);
      expect(api.serviceStates).toHaveBeenCalledTimes(2);

      second.destroy();
      await vi.advanceTimersByTimeAsync(BRANCH_STATE_POLL_MS * 2);
      expect(api.serviceStates).toHaveBeenCalledTimes(2);
    });
  });
});

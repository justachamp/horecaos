import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiClient } from '../api/api-client';
import { ApiError, ApiErrorCode } from '../api/problem-details';
import { ConsoleProfile, ConsoleProfileResponse } from './console-profile';
import { CurrentTenant } from './current-tenant';

const DELIVERIES: ConsoleProfileResponse = {
  variant: 'DELIVERIES',
  landing: '/deliveries',
  hiddenSections: ['/kitchen', '/marketing', '/inbox'],
  handovers: ['DELIVERY'],
};

const STANDARD: ConsoleProfileResponse = {
  variant: 'STANDARD',
  landing: '/today',
  hiddenSections: [],
  handovers: ['DELIVERY', 'PICKUP', 'DINE_IN'],
};

describe('ConsoleProfile (ADR 0164)', () => {
  const tenantId = signal<string | null>('t1');
  let get: ReturnType<typeof vi.fn>;

  function profile(): ConsoleProfile {
    return TestBed.inject(ConsoleProfile);
  }

  beforeEach(() => {
    localStorage.removeItem('horecaos.console.showAllSections');
    tenantId.set('t1');
    get = vi.fn().mockReturnValue(of({ value: STANDARD }));
    TestBed.configureTestingModule({
      providers: [
        { provide: ApiClient, useValue: { get } },
        { provide: CurrentTenant, useValue: { tenantId, ensureLoaded: () => Promise.resolve() } },
      ],
    });
  });

  afterEach(() => {
    localStorage.removeItem('horecaos.console.showAllSections');
  });

  it('is the standard console before anything has been read', () => {
    const p = profile();

    expect(p.variant()).toBe('STANDARD');
    expect(p.landing()).toBe('/today');
    expect(p.hiddenSections()).toEqual([]);
    expect(p.handovers()).toBeNull();
  });

  it('reads the profile of the signed-in tenant, once', async () => {
    get.mockReturnValue(of({ value: DELIVERIES }));
    const p = profile();

    await p.ensureLoaded();
    await p.ensureLoaded();

    expect(get).toHaveBeenCalledTimes(1);
    expect(get).toHaveBeenCalledWith('/api/v1/session/console-profile', {
      params: { tenantId: 't1' },
    });
    expect(p.variant()).toBe('DELIVERIES');
    expect(p.isDeliveries()).toBe(true);
    expect(p.landing()).toBe('/deliveries');
    expect(p.hiddenSections()).toEqual(['/kitchen', '/marketing', '/inbox']);
    expect(p.handovers()).toEqual(['DELIVERY']);
  });

  it('offers every handover on the standard profile: no restriction at all', async () => {
    const p = profile();

    await p.ensureLoaded();

    expect(p.variant()).toBe('STANDARD');
    expect(p.handovers()).toBeNull();
  });

  it('is the standard console when the profile cannot be read', async () => {
    get.mockReturnValue(
      throwError(() => new ApiError(ApiErrorCode.RESOURCE_NOT_FOUND, 404, null, null)),
    );
    const p = profile();

    await p.ensureLoaded();

    expect(p.variant()).toBe('STANDARD');
    expect(p.landing()).toBe('/today');
    expect(p.hiddenSections()).toEqual([]);
  });

  it('asks nothing when the token names no tenant (a platform-scope session)', async () => {
    tenantId.set(null);
    const p = profile();

    await p.ensureLoaded();

    expect(get).not.toHaveBeenCalled();
    expect(p.variant()).toBe('STANDARD');
  });

  it('«Показать все» shows the put-away sections without a request, and remembers the choice', async () => {
    get.mockReturnValue(of({ value: DELIVERIES }));
    const p = profile();
    await p.ensureLoaded();
    expect(p.hiddenSections()).toEqual(['/kitchen', '/marketing', '/inbox']);
    expect(p.showingAll()).toBe(false);

    p.toggleShowAll();

    expect(p.hiddenSections()).toEqual([]);
    expect(p.putAway()).toEqual(['/kitchen', '/marketing', '/inbox']);
    expect(p.showingAll()).toBe(true);
    expect(get).toHaveBeenCalledTimes(1);
    expect(localStorage.getItem('horecaos.console.showAllSections')).toBe('true');

    p.toggleShowAll();
    expect(p.hiddenSections()).toEqual(['/kitchen', '/marketing', '/inbox']);
  });

  it('works when the browser store is unavailable: the choice is simply not remembered', async () => {
    get.mockReturnValue(of({ value: DELIVERIES }));
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('storage disabled');
    });
    const p = profile();
    await p.ensureLoaded();

    expect(() => p.toggleShowAll()).not.toThrow();
    expect(p.hiddenSections()).toEqual([]);

    setItem.mockRestore();
  });

  it('never claims to be showing all on a profile that puts nothing away', async () => {
    const p = profile();
    await p.ensureLoaded();

    p.toggleShowAll();

    expect(p.showingAll()).toBe(false);
  });
});

import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../core/api/operations-paths';
import { CurrentLocation } from '../core/auth/current-location';
import { CallCentreApi, PresenceView, ScreenPopCard } from '../features/orders/call-centre-api';
import { VoicePresence } from './voice-presence';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function presence(overrides: Partial<PresenceView> = {}): PresenceView {
  return {
    operatorPrincipalId: 'op-1',
    state: 'ONLINE',
    reason: null,
    changedAt: new Date().toISOString(),
    version: 1,
    ...overrides,
  };
}

function card(overrides: Partial<ScreenPopCard> = {}): ScreenPopCard {
  return {
    ringing: false,
    callEventId: null,
    lineDid: null,
    maskedCallerNumber: null,
    occurredAt: null,
    unknownCaller: false,
    customerAccountId: null,
    customerDisplayName: null,
    recentOrders: [],
    acknowledgedBy: null,
    ...overrides,
  };
}

describe('VoicePresence', () => {
  function setUp(api: Partial<CallCentreApi>, scope: LocationScope | null = SCOPE): VoicePresence {
    TestBed.configureTestingModule({
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(scope),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: CallCentreApi, useValue: api },
      ],
    });
    return TestBed.inject(VoicePresence);
  }

  it('start() is idempotent — a second call polls no more than the first did', async () => {
    const currentCall = vi.fn().mockResolvedValue(card());
    const myPresence = vi.fn().mockResolvedValue(presence());
    const service = setUp({ currentCall, myPresence });

    service.start();
    service.start();
    await Promise.resolve();
    await Promise.resolve();

    expect(currentCall).toHaveBeenCalledTimes(1);
    expect(myPresence).toHaveBeenCalledTimes(1);
  });

  it('refreshPresence sets and returns the read presence', async () => {
    const myPresence = vi.fn().mockResolvedValue(presence({ state: 'PAUSED', reason: 'Lunch' }));
    const service = setUp({ myPresence });

    const result = await service.refreshPresence();

    expect(result.state).toBe('PAUSED');
    expect(service.presence()?.reason).toBe('Lunch');
  });

  it('currentCall is null for a non-ringing card, even once one has loaded', async () => {
    const currentCall = vi.fn().mockResolvedValue(card({ ringing: false }));
    const service = setUp({ currentCall });

    service.start();
    await Promise.resolve();
    await Promise.resolve();

    expect(service.currentCall()).toBeNull();
  });

  it('claim acknowledges the call and re-polls so acknowledgedBy is visible afterward', async () => {
    let served = card({ ringing: true, callEventId: 'call-1' });
    const acknowledge = vi.fn().mockImplementation(async () => {
      served = { ...served, acknowledgedBy: 'op-1' };
    });
    const currentCall = vi.fn().mockImplementation(() => Promise.resolve(served));
    const service = setUp({ currentCall, acknowledge });

    await service.claim('call-1');

    expect(acknowledge).toHaveBeenCalledWith(SCOPE, 'call-1');
    expect(service.currentCall()?.acknowledgedBy).toBe('op-1');
  });

  it('does nothing when no location is resolved, rather than calling the API with an undefined scope', async () => {
    const currentCall = vi.fn();
    const myPresence = vi.fn();
    const service = setUp({ currentCall, myPresence }, null);

    service.start();
    await Promise.resolve();
    await Promise.resolve();

    expect(currentCall).not.toHaveBeenCalled();
    expect(myPresence).not.toHaveBeenCalled();
  });
});

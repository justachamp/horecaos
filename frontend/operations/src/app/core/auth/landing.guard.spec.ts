import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  Router,
  RouterStateSnapshot,
  UrlTree,
  provideRouter,
} from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';

import { ConsoleProfile } from './console-profile';
import { landingGuard, statisticsLandingGuard } from './landing.guard';
import { SessionCapabilities } from './session-capabilities';

class FakeProfile {
  readonly landing = signal('/today');
  readonly isDeliveries = signal(false);
  readonly ensureLoaded = () => Promise.resolve();
}

class FakeCapabilities {
  held = new Set<string>();
  has = (capability: string): boolean => this.held.has(capability);
  ensureLoaded = () => Promise.resolve();
}

describe('the console landing (ADR 0164)', () => {
  let profile: FakeProfile;
  let capabilities: FakeCapabilities;

  beforeEach(() => {
    profile = new FakeProfile();
    capabilities = new FakeCapabilities();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: ConsoleProfile, useValue: profile },
        { provide: SessionCapabilities, useValue: capabilities },
      ],
    });
  });

  async function run(guard: typeof landingGuard): Promise<string> {
    const result = await TestBed.runInInjectionContext(() =>
      guard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot),
    );
    return TestBed.inject(Router).serializeUrl(result as UrlTree);
  }

  it('opens on Today for the standard console', async () => {
    expect(await run(landingGuard)).toBe('/today');
  });

  it('opens on the deliveries landing for the deliveries profile', async () => {
    profile.landing.set('/deliveries');
    capabilities.held.add('DELIVERY_PLAN_READ');

    expect(await run(landingGuard)).toBe('/deliveries');
  });

  it('falls back to Today for a deliveries operator who holds no delivery capability', async () => {
    profile.landing.set('/deliveries');

    expect(await run(landingGuard)).toBe('/today');
  });

  it('opens Statistics on the couriers report for the deliveries profile and the overview otherwise', async () => {
    expect(await run(statisticsLandingGuard)).toBe('/statistics/overview');

    profile.isDeliveries.set(true);
    expect(await run(statisticsLandingGuard)).toBe('/statistics/couriers');
  });
});

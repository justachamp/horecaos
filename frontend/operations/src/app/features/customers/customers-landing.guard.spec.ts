import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  RouterStateSnapshot,
  UrlTree,
  provideRouter,
} from '@angular/router';
import { describe, expect, it } from 'vitest';

import { SessionCapabilities } from '../../core/auth/session-capabilities';
import { customersLandingGuard } from './customers-landing.guard';

function run(held: readonly string[]): Promise<boolean | UrlTree> {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      {
        provide: SessionCapabilities,
        useValue: {
          ensureLoaded: () => Promise.resolve(),
          has: (capability: string) => held.includes(capability),
        },
      },
    ],
  });
  return TestBed.runInInjectionContext(() =>
    customersLandingGuard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot),
  ) as Promise<boolean | UrlTree>;
}

describe('customersLandingGuard', () => {
  it('sends a brand manager, who reads the queue and not the customer list, to the queue', async () => {
    const result = await run(['CUSTOMER_LEAD_READ', 'CUSTOMER_LEAD_MANAGE']);

    expect(result).toBeInstanceOf(UrlTree);
    expect(String(result)).toBe('/customers/leads');
  });

  it('keeps the list as the landing screen for anyone who can read customers', async () => {
    expect(await run(['CUSTOMER_READ', 'CUSTOMER_LEAD_READ'])).toBe(true);
  });

  it('keeps the list for an owner who reads customers and holds no lead capability at all', async () => {
    expect(await run(['CUSTOMER_READ'])).toBe(true);
  });

  it('does not redirect an operator who holds neither: the capability guard has already decided', async () => {
    expect(await run([])).toBe(true);
  });
});

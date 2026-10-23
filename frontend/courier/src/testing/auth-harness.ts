import { TestBed } from '@angular/core/testing';

import { APP_CONFIG } from '../app/core/config/app-config';
import {
  AUTH_CLOCK,
  BROWSER_NAVIGATION,
  OIDC_FETCH,
  type BrowserNavigation,
} from '../app/core/auth/oidc-client';
import { FakeIdentityProvider } from './fake-identity-provider';
import { TEST_CONFIG } from './fixtures';

export const ORIGIN = 'https://courier.example.test';

export interface AuthHarness {
  readonly provider: FakeIdentityProvider;
  /** Every URL the app asked the browser to go to, in order. */
  readonly navigations: string[];
  /** Moves the clock the auth code reads forward. */
  advance(milliseconds: number): void;
}

/**
 * Wires the real sign-in code to the fake identity provider, a recording stand-in for the
 * browser's location, and a clock a spec can advance. Nothing in `core/auth` is replaced.
 */
export function configureAuth(): AuthHarness {
  const provider = new FakeIdentityProvider();
  const navigations: string[] = [];
  let offset = 0;
  const navigation: BrowserNavigation = {
    origin: ORIGIN,
    assign: (url) => {
      navigations.push(url);
    },
  };

  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: TEST_CONFIG },
      { provide: OIDC_FETCH, useValue: provider.fetch },
      { provide: BROWSER_NAVIGATION, useValue: navigation },
      { provide: AUTH_CLOCK, useValue: () => Date.now() + offset },
    ],
  });

  return {
    provider,
    navigations,
    advance: (milliseconds) => {
      offset += milliseconds;
    },
  };
}

/** The `state` of the authorization URL the app most recently navigated to. */
export function lastAuthorization(harness: AuthHarness): URL {
  const url = harness.navigations.at(-1);
  if (url === undefined) {
    throw new Error('the app has not navigated anywhere');
  }
  return new URL(url);
}

/** Starts a sign-in and returns the callback query a well-behaved provider would send back. */
export function callbackFor(harness: AuthHarness, code = 'code-1'): URLSearchParams {
  const { state } = harness.provider.rememberAuthorization(lastAuthorization(harness).href);
  return new URLSearchParams({ code, state });
}

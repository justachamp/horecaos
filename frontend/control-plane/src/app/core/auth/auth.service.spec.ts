import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  bearerTokenInterceptor,
  correlationIdInterceptor,
  problemDetailsInterceptor,
} from '../api/interceptors';
import { AccessTokenSource } from './access-token-source';
import { APP_CONFIG, AppConfig } from '../config/app-config';
import { AuthService } from './auth.service';

const CONFIG: AppConfig = {
  apiBaseUrl: 'https://api.test.horecaos.uz',
  displayTimeZone: 'Asia/Tashkent',
};

/** `header.eyJwcmVmZXJyZWRfdXNlcm5hbWUiOiJheml6YSJ9.signature` — {"preferred_username":"aziza"}. */
const ACCESS_TOKEN_WITH_CLAIMS = 'header.eyJwcmVmZXJyZWRfdXNlcm5hbWUiOiJheml6YSJ9.signature';

function session(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    accessToken: ACCESS_TOKEN_WITH_CLAIMS,
    refreshToken: 'a-refresh-token',
    accessTokenExpiresAt: new Date(Date.now() + 300_000).toISOString(),
    refreshTokenExpiresAt: undefined,
    tokenType: 'Bearer',
    ...overrides,
  };
}

describe('AuthService', () => {
  let auth: AuthService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(
          withInterceptors([
            correlationIdInterceptor,
            bearerTokenInterceptor,
            problemDetailsInterceptor,
          ]),
        ),
        provideHttpClientTesting(),
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: AccessTokenSource, useExisting: AuthService },
      ],
    });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
    // Every real caller reaches this service through the app initializer,
    // which always calls this first; tests that skip it would be asserting
    // against the transient 'starting' default no production code path ever
    // observes.
    auth.initialise();
  });

  afterEach(() => http.verify());

  it('starts signed out, with no network call at all', () => {
    expect(auth.initialise()).toBe('signed-out');
    expect(auth.status()).toBe('signed-out');
  });

  it('signs in against this console-s own control-plane sessions endpoint', async () => {
    const promise = auth.signIn('aziza', 'correct horse');
    http
      .expectOne('https://api.test.horecaos.uz/api/v1/control-plane/auth/sessions')
      .flush(session());
    await promise;

    expect(auth.status()).toBe('signed-in');
    expect(auth.accessToken()).toBe(ACCESS_TOKEN_WITH_CLAIMS);
  });

  it('reads the display name from the fresh access token, never a separate call', async () => {
    const promise = auth.signIn('aziza', 'correct horse');
    http
      .expectOne('https://api.test.horecaos.uz/api/v1/control-plane/auth/sessions')
      .flush(session());
    await promise;

    expect(auth.displayName()).toBe('aziza');
  });

  it('propagates a refusal as an ApiError rather than silently staying signed out', async () => {
    const promise = auth.signIn('aziza', 'wrong');
    http.expectOne('https://api.test.horecaos.uz/api/v1/control-plane/auth/sessions').flush(
      {
        type: 'https://docs.horecaos.uz/problems/unauthenticated',
        title: 'Authentication required',
        status: 401,
        detail: 'Invalid credentials.',
        code: 'UNAUTHENTICATED',
      },
      {
        status: 401,
        statusText: 'Unauthorized',
        headers: { 'Content-Type': 'application/problem+json' },
      },
    );

    await expect(promise).rejects.toMatchObject({ code: 'UNAUTHENTICATED' });
    expect(auth.status()).toBe('signed-out');
  });

  it('refreshes through the backend and keeps the session on success', async () => {
    const signIn = auth.signIn('aziza', 'correct horse');
    http
      .expectOne('https://api.test.horecaos.uz/api/v1/control-plane/auth/sessions')
      .flush(session());
    await signIn;

    const refreshed = auth.refresh();
    http
      .expectOne('https://api.test.horecaos.uz/api/v1/control-plane/auth/sessions/refresh')
      .flush(session({ accessToken: 'header.eyJwcmVmZXJyZWRfdXNlcm5hbWUiOiJheml6YSJ9.new-sig' }));

    expect(await refreshed).toBe(true);
    expect(auth.status()).toBe('signed-in');
  });

  it('signs out locally when a refresh is refused, without throwing', async () => {
    const signIn = auth.signIn('aziza', 'correct horse');
    http
      .expectOne('https://api.test.horecaos.uz/api/v1/control-plane/auth/sessions')
      .flush(session());
    await signIn;

    const refreshed = auth.refresh();
    http
      .expectOne('https://api.test.horecaos.uz/api/v1/control-plane/auth/sessions/refresh')
      .flush({ code: 'SESSION_EXPIRED' }, { status: 401, statusText: 'Unauthorized' });

    expect(await refreshed).toBe(false);
    expect(auth.status()).toBe('signed-out');
    expect(auth.accessToken()).toBeNull();
  });

  it('has nothing to refresh before any sign-in', async () => {
    expect(await auth.refresh()).toBe(false);
    http.expectNone('https://api.test.horecaos.uz/api/v1/control-plane/auth/sessions/refresh');
  });

  it('revokes the refresh token at sign-out and clears the local session regardless of the answer', async () => {
    const signIn = auth.signIn('aziza', 'correct horse');
    http
      .expectOne('https://api.test.horecaos.uz/api/v1/control-plane/auth/sessions')
      .flush(session());
    await signIn;

    const signOut = auth.signOut();
    const revoke = http.expectOne(
      'https://api.test.horecaos.uz/api/v1/control-plane/auth/sessions/current',
    );
    expect(revoke.request.method).toBe('DELETE');
    expect(revoke.request.body).toEqual({ refreshToken: 'a-refresh-token' });
    revoke.flush(null, { status: 204, statusText: 'No Content' });
    await signOut;

    expect(auth.status()).toBe('signed-out');
    expect(auth.accessToken()).toBeNull();
    expect(auth.displayName()).toBeNull();
  });

  it('sign-out clears the local session even when revocation fails', async () => {
    const signIn = auth.signIn('aziza', 'correct horse');
    http
      .expectOne('https://api.test.horecaos.uz/api/v1/control-plane/auth/sessions')
      .flush(session());
    await signIn;

    const signOut = auth.signOut();
    http
      .expectOne('https://api.test.horecaos.uz/api/v1/control-plane/auth/sessions/current')
      .flush({ code: 'INTERNAL_ERROR' }, { status: 500, statusText: 'Internal Server Error' });
    await signOut;

    expect(auth.status()).toBe('signed-out');
  });

  it('does nothing at sign-out when there was never a session', async () => {
    await auth.signOut();
    http.expectNone('https://api.test.horecaos.uz/api/v1/control-plane/auth/sessions/current');
  });

  it('schedules a proactive refresh a minute before the access token expires', async () => {
    vi.useFakeTimers();
    try {
      const promise = auth.signIn('aziza', 'correct horse');
      http
        .expectOne('https://api.test.horecaos.uz/api/v1/control-plane/auth/sessions')
        .flush(session({ accessTokenExpiresAt: new Date(Date.now() + 300_000).toISOString() }));
      await promise;

      // 300s expiry minus the 60s margin: nothing yet at 239s...
      await vi.advanceTimersByTimeAsync(239_000);
      http.expectNone('https://api.test.horecaos.uz/api/v1/control-plane/auth/sessions/refresh');

      // ...and the scheduled call by 241s.
      await vi.advanceTimersByTimeAsync(2_000);
      http
        .expectOne('https://api.test.horecaos.uz/api/v1/control-plane/auth/sessions/refresh')
        .flush(session());
    } finally {
      vi.useRealTimers();
    }
  });
});

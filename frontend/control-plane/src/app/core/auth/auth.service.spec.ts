import { HttpClient, HttpContext, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { PLATFORM_API_REQUEST } from '../api/api-client';
import {
  bearerTokenInterceptor,
  correlationIdInterceptor,
  problemDetailsInterceptor,
  sessionRefreshInterceptor,
} from '../api/interceptors';
import { ApiError } from '../api/problem';
import { AccessTokenSource } from './access-token-source';
import { APP_CONFIG, AppConfig } from '../config/app-config';
import { AuthService, REFRESH_PATH, SIGN_IN_PATH, SIGN_OUT_PATH } from './auth.service';
import { RETURN_TO_KEY } from './guards';
import { REFRESH_TOKEN_KEY } from './staff-token-store';

const CONFIG: AppConfig = {
  apiBaseUrl: 'https://api.test.horecaos.uz',
  displayTimeZone: 'Asia/Tashkent',
};

/** `header.eyJwcmVmZXJyZWRfdXNlcm5hbWUiOiJheml6YSJ9.signature` — {"preferred_username":"aziza"}. */
const ACCESS_TOKEN_WITH_CLAIMS = 'header.eyJwcmVmZXJyZWRfdXNlcm5hbWUiOiJheml6YSJ9.signature';

function url(path: string): string {
  return `${CONFIG.apiBaseUrl}${path}`;
}

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

/** Every interceptor the real app registers, in the real app's order (`app.config.ts`). */
function providePlatformHttp() {
  return provideHttpClient(
    withInterceptors([
      correlationIdInterceptor,
      sessionRefreshInterceptor,
      bearerTokenInterceptor,
      problemDetailsInterceptor,
    ]),
  );
}

describe('AuthService', () => {
  let auth: AuthService;
  let http: HttpTestingController;

  beforeEach(() => {
    // `StaffTokenStore` now persists the refresh token here (see its own
    // doc). A leftover key from an earlier test would make `initialise()`
    // below issue a real refresh call no test in this block expects.
    sessionStorage.clear();

    TestBed.configureTestingModule({
      providers: [
        providePlatformHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: AccessTokenSource, useExisting: AuthService },
      ],
    });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
    // Every real caller reaches this service through the app initializer,
    // which always calls this first; tests that skip it would be asserting
    // against the transient 'starting' default no production code path ever
    // observes. Synchronous here because `beforeEach` never seeds a stored
    // refresh token, so `initialise()` settles before its first `await`.
    void auth.initialise();
  });

  afterEach(() => {
    http.verify();
    sessionStorage.clear();
  });

  it('starts signed out, with no network call at all, when nothing is stored', async () => {
    expect(await auth.initialise()).toBe('signed-out');
    expect(auth.status()).toBe('signed-out');
  });

  it('signs in against this console-s own control-plane sessions endpoint', async () => {
    const promise = auth.signIn('aziza', 'correct horse');
    http.expectOne(url(SIGN_IN_PATH)).flush(session());
    await promise;

    expect(auth.status()).toBe('signed-in');
    expect(auth.accessToken()).toBe(ACCESS_TOKEN_WITH_CLAIMS);
  });

  it('reads the display name from the fresh access token, never a separate call', async () => {
    const promise = auth.signIn('aziza', 'correct horse');
    http.expectOne(url(SIGN_IN_PATH)).flush(session());
    await promise;

    expect(auth.displayName()).toBe('aziza');
  });

  it('propagates a refusal as an ApiError rather than silently staying signed out', async () => {
    const promise = auth.signIn('aziza', 'wrong');
    http.expectOne(url(SIGN_IN_PATH)).flush(
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
    http.expectOne(url(SIGN_IN_PATH)).flush(session());
    await signIn;

    const refreshed = auth.refresh();
    http
      .expectOne(url(REFRESH_PATH))
      .flush(session({ accessToken: 'header.eyJwcmVmZXJyZWRfdXNlcm5hbWUiOiJheml6YSJ9.new-sig' }));

    expect(await refreshed).toBe(true);
    expect(auth.status()).toBe('signed-in');
  });

  it('shares one in-flight refresh across callers that ask concurrently', async () => {
    // The scenario this exists for: a laptop wakes from sleep with several
    // screens open, and every one of them discovers the expired access
    // token in the same tick. Two network calls for one expired session
    // risks Keycloak refusing the second as an already-used refresh token.
    const signIn = auth.signIn('aziza', 'correct horse');
    http.expectOne(url(SIGN_IN_PATH)).flush(session());
    await signIn;

    const first = auth.refresh();
    const second = auth.refresh();
    http
      .expectOne(url(REFRESH_PATH))
      .flush(session({ accessToken: 'header.eyJwcmVmZXJyZWRfdXNlcm5hbWUiOiJheml6YSJ9.new-sig' }));

    expect(await first).toBe(true);
    expect(await second).toBe(true);
    http.expectNone(url(REFRESH_PATH));
  });

  it('signs out locally when a refresh is refused, without throwing', async () => {
    const signIn = auth.signIn('aziza', 'correct horse');
    http.expectOne(url(SIGN_IN_PATH)).flush(session());
    await signIn;

    const refreshed = auth.refresh();
    http
      .expectOne(url(REFRESH_PATH))
      .flush({ code: 'SESSION_EXPIRED' }, { status: 401, statusText: 'Unauthorized' });

    expect(await refreshed).toBe(false);
    expect(auth.status()).toBe('signed-out');
    expect(auth.accessToken()).toBeNull();
  });

  it('has nothing to refresh before any sign-in', async () => {
    expect(await auth.refresh()).toBe(false);
    http.expectNone(url(REFRESH_PATH));
  });

  it('revokes the refresh token at sign-out and clears the local session regardless of the answer', async () => {
    const signIn = auth.signIn('aziza', 'correct horse');
    http.expectOne(url(SIGN_IN_PATH)).flush(session());
    await signIn;
    expect(sessionStorage.getItem(REFRESH_TOKEN_KEY)).toBe('a-refresh-token');

    const signOut = auth.signOut();
    const revoke = http.expectOne(url(SIGN_OUT_PATH));
    expect(revoke.request.method).toBe('DELETE');
    expect(revoke.request.body).toEqual({ refreshToken: 'a-refresh-token' });
    revoke.flush(null, { status: 204, statusText: 'No Content' });
    await signOut;

    expect(auth.status()).toBe('signed-out');
    expect(auth.accessToken()).toBeNull();
    expect(auth.displayName()).toBeNull();
    // The whole point of moving the refresh token into sessionStorage: a
    // sign-out has to remove it from there too, or the next reload would
    // silently resurrect the very session sign-out just ended.
    expect(sessionStorage.getItem(REFRESH_TOKEN_KEY)).toBeNull();
  });

  it('sign-out clears the local session even when revocation fails', async () => {
    const signIn = auth.signIn('aziza', 'correct horse');
    http.expectOne(url(SIGN_IN_PATH)).flush(session());
    await signIn;

    const signOut = auth.signOut();
    http
      .expectOne(url(SIGN_OUT_PATH))
      .flush({ code: 'INTERNAL_ERROR' }, { status: 500, statusText: 'Internal Server Error' });
    await signOut;

    expect(auth.status()).toBe('signed-out');
    expect(sessionStorage.getItem(REFRESH_TOKEN_KEY)).toBeNull();
  });

  it('does nothing at sign-out when there was never a session', async () => {
    await auth.signOut();
    http.expectNone(url(SIGN_OUT_PATH));
  });

  it('schedules a proactive refresh a minute before the access token expires', async () => {
    vi.useFakeTimers();
    try {
      const promise = auth.signIn('aziza', 'correct horse');
      http
        .expectOne(url(SIGN_IN_PATH))
        .flush(session({ accessTokenExpiresAt: new Date(Date.now() + 300_000).toISOString() }));
      await promise;

      // 300s expiry minus the 60s margin: nothing yet at 239s...
      await vi.advanceTimersByTimeAsync(239_000);
      http.expectNone(url(REFRESH_PATH));

      // ...and the scheduled call by 241s.
      await vi.advanceTimersByTimeAsync(2_000);
      http.expectOne(url(REFRESH_PATH)).flush(session());
    } finally {
      vi.useRealTimers();
    }
  });
});

/**
 * `initialise()`'s own bootstrap behaviour, isolated in its own `describe`
 * because it has to seed `sessionStorage` *before* `AuthService` is
 * constructed — the outer block's `beforeEach` always starts from an empty
 * store, which is exactly what these tests must not do.
 */
describe('AuthService bootstrap from a reload', () => {
  let http: HttpTestingController;

  function setUp(): AuthService {
    TestBed.configureTestingModule({
      providers: [
        providePlatformHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: AccessTokenSource, useExisting: AuthService },
      ],
    });
    const auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
    return auth;
  }

  afterEach(() => {
    http.verify();
    sessionStorage.clear();
  });

  it('redeems a refresh token that survived a reload, before the first route resolves', async () => {
    sessionStorage.setItem(REFRESH_TOKEN_KEY, 'a-refresh-token-from-before-the-reload');
    const auth = setUp();

    const status = auth.initialise();
    const request = http.expectOne(url(REFRESH_PATH));
    expect(request.request.body).toEqual({ refreshToken: 'a-refresh-token-from-before-the-reload' });
    request.flush(session());

    expect(await status).toBe('signed-in');
    expect(auth.accessToken()).toBe(ACCESS_TOKEN_WITH_CLAIMS);
  });

  it('clears the stored token and settles signed-out when the platform rejects it', async () => {
    sessionStorage.setItem(REFRESH_TOKEN_KEY, 'a-stale-refresh-token');
    const auth = setUp();

    const status = auth.initialise();
    http
      .expectOne(url(REFRESH_PATH))
      .flush({ code: 'SESSION_EXPIRED' }, { status: 401, statusText: 'Unauthorized' });

    expect(await status).toBe('signed-out');
    expect(sessionStorage.getItem(REFRESH_TOKEN_KEY)).toBeNull();
  });

  it('settles signed-out with no network call when there is nothing stored', async () => {
    const auth = setUp();
    expect(await auth.initialise()).toBe('signed-out');
    http.expectNone(url(REFRESH_PATH));
  });
});

/**
 * `sessionRefreshInterceptor`'s own behaviour. Exercised through the real
 * `HttpClient` pipeline — the same one every feature's own API call goes
 * through — rather than by calling anything on the interceptor directly,
 * because the property under test is what a *caller* sees, not the
 * interceptor's internals.
 */
describe('sessionRefreshInterceptor', () => {
  let auth: AuthService;
  let httpClient: HttpClient;
  let http: HttpTestingController;
  let router: Router;

  const TENANTS_URL = 'https://api.test.horecaos.uz/api/v1/control-plane/tenants';

  beforeEach(async () => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        providePlatformHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: AccessTokenSource, useExisting: AuthService },
      ],
    });
    auth = TestBed.inject(AuthService);
    httpClient = TestBed.inject(HttpClient);
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    void auth.initialise();

    const signIn = auth.signIn('aziza', 'correct horse');
    http.expectOne(url(SIGN_IN_PATH)).flush(session());
    await signIn;
  });

  afterEach(() => {
    http.verify();
    sessionStorage.clear();
  });

  function callTenants() {
    return firstValueFrom(
      httpClient.get(TENANTS_URL, { context: new HttpContext().set(PLATFORM_API_REQUEST, true) }),
    );
  }

  /**
   * Between flushing the refresh call and the interceptor issuing the retry
   * sits a promise chain several hops deep (`AuthService.refresh()`'s own
   * `firstValueFrom` and `.finally()`, then this interceptor's `from()` and
   * `switchMap`) — more hops than a single microtask turn drains. A
   * `setTimeout` macrotask always runs after every pending microtask, so
   * this reliably waits for the retry to actually be issued before the test
   * asserts on it.
   */
  function flushInterceptorChain(): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, 0));
  }

  it('retries a 401 exactly once after a silent refresh, and the retry carries the fresh token', async () => {
    const promise = callTenants();

    http
      .expectOne(TENANTS_URL)
      .flush({ code: 'UNAUTHENTICATED' }, { status: 401, statusText: 'Unauthorized' });

    http
      .expectOne(url(REFRESH_PATH))
      .flush(session({ accessToken: 'header.eyJwcmVmZXJyZWRfdXNlcm5hbWUiOiJheml6YSJ9.new-sig' }));
    await flushInterceptorChain();

    const retried = http.expectOne(TENANTS_URL);
    expect(retried.request.headers.get('Authorization')).toBe(
      'Bearer header.eyJwcmVmZXJyZWRfdXNlcm5hbWUiOiJheml6YSJ9.new-sig',
    );
    retried.flush({ items: [] });

    await expect(promise).resolves.toEqual({ items: [] });
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('clears the session and sends the operator to /login with returnTo when the silent refresh itself fails', async () => {
    const expectedReturnTo = router.url;
    const promise = callTenants();

    http
      .expectOne(TENANTS_URL)
      .flush({ code: 'UNAUTHENTICATED' }, { status: 401, statusText: 'Unauthorized' });

    http
      .expectOne(url(REFRESH_PATH))
      .flush({ code: 'SESSION_EXPIRED' }, { status: 401, statusText: 'Unauthorized' });

    await expect(promise).rejects.toBeInstanceOf(ApiError);
    expect(auth.status()).toBe('signed-out');
    expect(router.navigateByUrl).toHaveBeenCalledWith('/login');
    expect(sessionStorage.getItem(RETURN_TO_KEY)).toBe(expectedReturnTo);
  });

  it('does not touch a non-401 failure', async () => {
    const promise = callTenants();

    http
      .expectOne(TENANTS_URL)
      .flush(
        { status: 500, code: 'INTERNAL_ERROR' },
        { status: 500, statusText: 'Internal Server Error' },
      );

    await expect(promise).rejects.toMatchObject({ code: 'INTERNAL_ERROR' });
    http.expectNone(url(REFRESH_PATH));
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('never turns a rejected refresh grant into a second refresh attempt', async () => {
    // A 401 on the refresh endpoint itself must not be handed back into
    // this same interceptor as "another call that needs refreshing" — that
    // is an unbounded loop of refresh attempts, not a retry.
    const refreshed = auth.refresh();
    http
      .expectOne(url(REFRESH_PATH))
      .flush({ code: 'SESSION_EXPIRED' }, { status: 401, statusText: 'Unauthorized' });

    expect(await refreshed).toBe(false);
    http.expectNone(url(REFRESH_PATH));
  });
});

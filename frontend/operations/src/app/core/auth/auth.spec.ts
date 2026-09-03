import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { environment } from '../../../environments/environment';
import { ApiClient } from '../api/api-client';
import { bearerTokenInterceptor } from '../api/bearer-token.interceptor';
import { correlationIdInterceptor } from '../api/correlation-id.interceptor';
import { ApiError } from '../api/problem-details';
import { sessionRefreshInterceptor } from '../api/session-refresh.interceptor';
import { Auth, REFRESH_PATH, SIGN_IN_PATH, SIGN_OUT_PATH } from './auth';
import { RETURN_TO_KEY } from './auth.guard';
import { REFRESH_TOKEN_KEY } from './staff-token-store';

/** `header.eyJwcmVmZXJyZWRfdXNlcm5hbWUiOiJheml6YSJ9.signature` — {"preferred_username":"aziza"}. */
const ACCESS_TOKEN_WITH_CLAIMS = 'header.eyJwcmVmZXJyZWRfdXNlcm5hbWUiOiJheml6YSJ9.signature';

function url(path: string): string {
  return `${environment.apiBaseUrl}${path}`;
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
    withInterceptors([correlationIdInterceptor, sessionRefreshInterceptor, bearerTokenInterceptor]),
  );
}

describe('Auth', () => {
  let auth: Auth;
  let http: HttpTestingController;

  beforeEach(() => {
    // `StaffTokenStore` now persists the refresh token here (see its own
    // doc). A leftover key from an earlier test would make `initialise()`
    // below issue a real refresh call no test in this block expects.
    sessionStorage.clear();

    TestBed.configureTestingModule({
      providers: [providePlatformHttp(), provideHttpClientTesting(), provideRouter([])],
    });
    auth = TestBed.inject(Auth);
    http = TestBed.inject(HttpTestingController);
    // Every real caller reaches this service through the app initializer,
    // which always calls this first. Synchronous here because `beforeEach`
    // never seeds a stored refresh token, so `initialise()` settles before
    // its first `await`.
    void auth.initialise();
  });

  afterEach(() => {
    http.verify();
    sessionStorage.clear();
  });

  it('starts signed out, with no network call at all, when nothing is stored', async () => {
    expect(await auth.initialise()).toBe('signed-out');
    expect(auth.isAuthenticated()).toBe(false);
  });

  it("signs in against this console's own operations sessions endpoint", async () => {
    const promise = auth.signIn('aziza', 'correct horse');
    http.expectOne(url(SIGN_IN_PATH)).flush(session());
    await promise;

    expect(auth.isAuthenticated()).toBe(true);
    expect(auth.accessToken()).toBe(ACCESS_TOKEN_WITH_CLAIMS);
  });

  it('sends the idempotency key ADR 0031 requires on every mutation, even this one', async () => {
    const promise = auth.signIn('aziza', 'correct horse');
    const request = http.expectOne(url(SIGN_IN_PATH));
    expect(request.request.headers.get('Idempotency-Key')).toBeTruthy();
    request.flush(session());
    await promise;
  });

  it('reads the display name and subject from the fresh access token, never a separate call', async () => {
    const promise = auth.signIn('aziza', 'correct horse');
    http.expectOne(url(SIGN_IN_PATH)).flush(session());
    await promise;

    expect(auth.displayName()).toBe('aziza');
  });

  it('propagates a refusal rather than silently staying signed out', async () => {
    const promise = auth.signIn('aziza', 'wrong');
    http
      .expectOne(url(SIGN_IN_PATH))
      .flush(
        { status: 401, code: 'UNAUTHENTICATED', detail: 'Invalid credentials.' },
        { status: 401, statusText: 'Unauthorized' },
      );

    await expect(promise).rejects.toMatchObject({ code: 'UNAUTHENTICATED' });
    expect(auth.isAuthenticated()).toBe(false);
  });

  it('refreshes through the backend and keeps the session on success', async () => {
    const signIn = auth.signIn('aziza', 'correct horse');
    http.expectOne(url(SIGN_IN_PATH)).flush(session());
    await signIn;

    const refreshed = auth.refresh();
    http.expectOne(url(REFRESH_PATH)).flush(session());

    expect(await refreshed).toBe(true);
    expect(auth.isAuthenticated()).toBe(true);
  });

  it('shares one in-flight refresh across callers that ask concurrently', async () => {
    // The scenario this exists for: a till tablet wakes from sleep with
    // several requests already queued, and every one of them discovers the
    // expired access token in the same tick. Two network calls for one
    // expired session risks Keycloak refusing the second as an already-used
    // refresh token.
    const signIn = auth.signIn('aziza', 'correct horse');
    http.expectOne(url(SIGN_IN_PATH)).flush(session());
    await signIn;

    const first = auth.refresh();
    const second = auth.refresh();
    http.expectOne(url(REFRESH_PATH)).flush(session());

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
      .flush({ status: 401, code: 'SESSION_EXPIRED' }, { status: 401, statusText: 'Unauthorized' });

    expect(await refreshed).toBe(false);
    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.accessToken()).toBeNull();
  });

  it('has nothing to refresh before any sign-in', async () => {
    expect(await auth.refresh()).toBe(false);
    http.expectNone(url(REFRESH_PATH));
  });

  it('revokes the refresh token at logout and clears the local session regardless of the answer', async () => {
    const signIn = auth.signIn('aziza', 'correct horse');
    http.expectOne(url(SIGN_IN_PATH)).flush(session());
    await signIn;
    expect(sessionStorage.getItem(REFRESH_TOKEN_KEY)).toBe('a-refresh-token');

    let completed = false;
    auth.logout().subscribe({ complete: () => (completed = true) });
    const revoke = http.expectOne(url(SIGN_OUT_PATH));
    expect(revoke.request.method).toBe('DELETE');
    expect(revoke.request.body).toEqual({ refreshToken: 'a-refresh-token' });
    revoke.flush(null, { status: 204, statusText: 'No Content' });

    expect(completed).toBe(true);
    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.accessToken()).toBeNull();
    expect(auth.displayName()).toBeNull();
    // The whole point of moving the refresh token into sessionStorage: a
    // logout has to remove it from there too, or the next reload would
    // silently resurrect the very session logout just ended.
    expect(sessionStorage.getItem(REFRESH_TOKEN_KEY)).toBeNull();
  });

  it('logout clears the local session even when revocation fails', async () => {
    const signIn = auth.signIn('aziza', 'correct horse');
    http.expectOne(url(SIGN_IN_PATH)).flush(session());
    await signIn;

    let completed = false;
    auth.logout().subscribe({ complete: () => (completed = true) });
    http
      .expectOne(url(SIGN_OUT_PATH))
      .flush({ code: 'INTERNAL_ERROR' }, { status: 500, statusText: 'Internal Server Error' });

    expect(completed).toBe(true);
    expect(auth.isAuthenticated()).toBe(false);
    expect(sessionStorage.getItem(REFRESH_TOKEN_KEY)).toBeNull();
  });

  it('does nothing at logout when there was never a session', () => {
    let completed = false;
    auth.logout().subscribe({ complete: () => (completed = true) });
    expect(completed).toBe(true);
    http.expectNone(url(SIGN_OUT_PATH));
  });

  it('attaches the bearer token to a platform API call once signed in', async () => {
    const signIn = auth.signIn('aziza', 'correct horse');
    http.expectOne(url(SIGN_IN_PATH)).flush(session());
    await signIn;

    TestBed.inject(HttpClient).get('/api/v1/operations/tenants/t/orders').subscribe();
    const request = http.expectOne('/api/v1/operations/tenants/t/orders');
    expect(request.request.headers.get('Authorization')).toBe(`Bearer ${ACCESS_TOKEN_WITH_CLAIMS}`);
    request.flush({});
  });

  it('schedules a proactive refresh a minute before the access token expires', async () => {
    vi.useFakeTimers();
    try {
      const promise = auth.signIn('aziza', 'correct horse');
      http
        .expectOne(url(SIGN_IN_PATH))
        .flush(session({ accessTokenExpiresAt: new Date(Date.now() + 300_000).toISOString() }));
      await promise;

      await vi.advanceTimersByTimeAsync(239_000);
      http.expectNone(url(REFRESH_PATH));

      await vi.advanceTimersByTimeAsync(2_000);
      http.expectOne(url(REFRESH_PATH)).flush(session());
    } finally {
      vi.useRealTimers();
    }
  });
});

/**
 * `initialise()`'s own bootstrap behaviour, isolated in its own `describe`
 * because it has to seed `sessionStorage` *before* `Auth` is constructed —
 * the outer block's `beforeEach` always starts from an empty store, which is
 * exactly what these tests must not do.
 */
describe('Auth bootstrap from a reload', () => {
  let http: HttpTestingController;

  function setUp(): Auth {
    TestBed.configureTestingModule({
      providers: [providePlatformHttp(), provideHttpClientTesting(), provideRouter([])],
    });
    const auth = TestBed.inject(Auth);
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
      .flush({ status: 401, code: 'SESSION_EXPIRED' }, { status: 401, statusText: 'Unauthorized' });

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
  let auth: Auth;
  let api: ApiClient;
  let http: HttpTestingController;
  let router: Router;

  const ORDERS_URL = '/api/v1/operations/tenants/t/orders';

  beforeEach(async () => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [providePlatformHttp(), provideHttpClientTesting(), provideRouter([])],
    });
    auth = TestBed.inject(Auth);
    api = TestBed.inject(ApiClient);
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

  /**
   * `ApiClient.get`, not the raw `HttpClient` — this proves what a real
   * feature sees. `sessionRefreshInterceptor` runs beneath `ApiClient`'s own
   * error shaping, so a raw `HttpClient` call here would never see the
   * `ApiError` a feature actually gets; it would see whatever
   * `HttpErrorResponse` the interceptor chain last threw.
   */
  function callOrders() {
    return firstValueFrom(api.get<{ items: readonly unknown[] }>(ORDERS_URL));
  }

  /**
   * Between flushing the refresh call and the interceptor issuing the retry
   * sits a promise chain several hops deep (`Auth.refresh()`'s own
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
    const promise = callOrders();

    http.expectOne(ORDERS_URL).flush(null, { status: 401, statusText: 'Unauthorized' });

    http
      .expectOne(url(REFRESH_PATH))
      .flush(session({ accessToken: 'header.eyJwcmVmZXJyZWRfdXNlcm5hbWUiOiJheml6YSJ9.new-sig' }));
    await flushInterceptorChain();

    const retried = http.expectOne(ORDERS_URL);
    expect(retried.request.headers.get('Authorization')).toBe(
      'Bearer header.eyJwcmVmZXJyZWRfdXNlcm5hbWUiOiJheml6YSJ9.new-sig',
    );
    retried.flush({ items: [] });

    await expect(promise).resolves.toEqual({ value: { items: [] }, version: null });
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('clears the session and sends the operator to /login with returnTo when the silent refresh itself fails', async () => {
    const expectedReturnTo = router.url;
    const promise = callOrders();

    http.expectOne(ORDERS_URL).flush(null, { status: 401, statusText: 'Unauthorized' });

    http
      .expectOne(url(REFRESH_PATH))
      .flush({ status: 401, code: 'SESSION_EXPIRED' }, { status: 401, statusText: 'Unauthorized' });

    await expect(promise).rejects.toBeInstanceOf(ApiError);
    expect(auth.isAuthenticated()).toBe(false);
    expect(router.navigateByUrl).toHaveBeenCalledWith('/login');
    expect(sessionStorage.getItem(RETURN_TO_KEY)).toBe(expectedReturnTo);
  });

  it('does not touch a non-401 failure', async () => {
    const promise = callOrders();

    http
      .expectOne(ORDERS_URL)
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
      .flush({ status: 401, code: 'SESSION_EXPIRED' }, { status: 401, statusText: 'Unauthorized' });

    expect(await refreshed).toBe(false);
    http.expectNone(url(REFRESH_PATH));
  });
});

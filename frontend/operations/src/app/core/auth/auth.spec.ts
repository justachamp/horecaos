import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { environment } from '../../../environments/environment';
import { bearerTokenInterceptor } from '../api/bearer-token.interceptor';
import { correlationIdInterceptor } from '../api/correlation-id.interceptor';
import { Auth } from './auth';

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

describe('Auth', () => {
  let auth: Auth;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([correlationIdInterceptor, bearerTokenInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    auth = TestBed.inject(Auth);
    http = TestBed.inject(HttpTestingController);
    // Every real caller reaches this service through the app initializer,
    // which always calls this first.
    auth.initialise();
  });

  afterEach(() => http.verify());

  it('starts signed out, with no network call at all', () => {
    expect(auth.initialise()).toBe('signed-out');
    expect(auth.isAuthenticated()).toBe(false);
  });

  it("signs in against this console's own operations sessions endpoint", async () => {
    const promise = auth.signIn('aziza', 'correct horse');
    http.expectOne(url('/api/v1/operations/auth/sessions')).flush(session());
    await promise;

    expect(auth.isAuthenticated()).toBe(true);
    expect(auth.accessToken()).toBe(ACCESS_TOKEN_WITH_CLAIMS);
  });

  it('sends the idempotency key ADR 0031 requires on every mutation, even this one', async () => {
    const promise = auth.signIn('aziza', 'correct horse');
    const request = http.expectOne(url('/api/v1/operations/auth/sessions'));
    expect(request.request.headers.get('Idempotency-Key')).toBeTruthy();
    request.flush(session());
    await promise;
  });

  it('reads the display name and subject from the fresh access token, never a separate call', async () => {
    const promise = auth.signIn('aziza', 'correct horse');
    http.expectOne(url('/api/v1/operations/auth/sessions')).flush(session());
    await promise;

    expect(auth.displayName()).toBe('aziza');
  });

  it('propagates a refusal rather than silently staying signed out', async () => {
    const promise = auth.signIn('aziza', 'wrong');
    http
      .expectOne(url('/api/v1/operations/auth/sessions'))
      .flush(
        { status: 401, code: 'UNAUTHENTICATED', detail: 'Invalid credentials.' },
        { status: 401, statusText: 'Unauthorized' },
      );

    await expect(promise).rejects.toMatchObject({ code: 'UNAUTHENTICATED' });
    expect(auth.isAuthenticated()).toBe(false);
  });

  it('refreshes through the backend and keeps the session on success', async () => {
    const signIn = auth.signIn('aziza', 'correct horse');
    http.expectOne(url('/api/v1/operations/auth/sessions')).flush(session());
    await signIn;

    const refreshed = auth.refresh();
    http.expectOne(url('/api/v1/operations/auth/sessions/refresh')).flush(session());

    expect(await refreshed).toBe(true);
    expect(auth.isAuthenticated()).toBe(true);
  });

  it('signs out locally when a refresh is refused, without throwing', async () => {
    const signIn = auth.signIn('aziza', 'correct horse');
    http.expectOne(url('/api/v1/operations/auth/sessions')).flush(session());
    await signIn;

    const refreshed = auth.refresh();
    http
      .expectOne(url('/api/v1/operations/auth/sessions/refresh'))
      .flush({ status: 401, code: 'SESSION_EXPIRED' }, { status: 401, statusText: 'Unauthorized' });

    expect(await refreshed).toBe(false);
    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.accessToken()).toBeNull();
  });

  it('has nothing to refresh before any sign-in', async () => {
    expect(await auth.refresh()).toBe(false);
    http.expectNone(url('/api/v1/operations/auth/sessions/refresh'));
  });

  it('revokes the refresh token at logout and clears the local session regardless of the answer', async () => {
    const signIn = auth.signIn('aziza', 'correct horse');
    http.expectOne(url('/api/v1/operations/auth/sessions')).flush(session());
    await signIn;

    let completed = false;
    auth.logout().subscribe({ complete: () => (completed = true) });
    const revoke = http.expectOne(url('/api/v1/operations/auth/sessions/current'));
    expect(revoke.request.method).toBe('DELETE');
    expect(revoke.request.body).toEqual({ refreshToken: 'a-refresh-token' });
    revoke.flush(null, { status: 204, statusText: 'No Content' });

    expect(completed).toBe(true);
    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.accessToken()).toBeNull();
    expect(auth.displayName()).toBeNull();
  });

  it('logout clears the local session even when revocation fails', async () => {
    const signIn = auth.signIn('aziza', 'correct horse');
    http.expectOne(url('/api/v1/operations/auth/sessions')).flush(session());
    await signIn;

    let completed = false;
    auth.logout().subscribe({ complete: () => (completed = true) });
    http
      .expectOne(url('/api/v1/operations/auth/sessions/current'))
      .flush({ code: 'INTERNAL_ERROR' }, { status: 500, statusText: 'Internal Server Error' });

    expect(completed).toBe(true);
    expect(auth.isAuthenticated()).toBe(false);
  });

  it('does nothing at logout when there was never a session', () => {
    let completed = false;
    auth.logout().subscribe({ complete: () => (completed = true) });
    expect(completed).toBe(true);
    http.expectNone(url('/api/v1/operations/auth/sessions/current'));
  });

  it('attaches the bearer token to a platform API call once signed in', async () => {
    const signIn = auth.signIn('aziza', 'correct horse');
    http.expectOne(url('/api/v1/operations/auth/sessions')).flush(session());
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
        .expectOne(url('/api/v1/operations/auth/sessions'))
        .flush(session({ accessTokenExpiresAt: new Date(Date.now() + 300_000).toISOString() }));
      await promise;

      await vi.advanceTimersByTimeAsync(239_000);
      http.expectNone(url('/api/v1/operations/auth/sessions/refresh'));

      await vi.advanceTimersByTimeAsync(2_000);
      http.expectOne(url('/api/v1/operations/auth/sessions/refresh')).flush(session());
    } finally {
      vi.useRealTimers();
    }
  });
});

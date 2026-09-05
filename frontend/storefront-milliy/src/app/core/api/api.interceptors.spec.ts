import { TestBed } from '@angular/core/testing';
import { HttpContext, HttpErrorResponse, HttpRequest, HttpResponse } from '@angular/common/http';
import type { HttpHandlerFn } from '@angular/common/http';
import { firstValueFrom, of, throwError } from 'rxjs';

import {
  bearerInterceptor,
  conventionsInterceptor,
  expiredSessionInterceptor,
  problemDetailsInterceptor,
} from './api.interceptors';
import { ANONYMOUS, PLATFORM_API_REQUEST } from './api-client';
import { HorecaOSApiError } from './problem-details';
import { Session } from '../auth/session';

function platformRequest(anonymous = false): HttpRequest<unknown> {
  return new HttpRequest('GET', '/api/v1/thing', {
    context: new HttpContext().set(PLATFORM_API_REQUEST, true).set(ANONYMOUS, anonymous),
  });
}

function nonPlatformRequest(): HttpRequest<unknown> {
  return new HttpRequest('GET', 'https://tiles.example.com/thing');
}

const passthrough: HttpHandlerFn = (req) => of(new HttpResponse({ status: 200, body: null }));

describe('bearerInterceptor', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({});
  });

  it('attaches the bearer for a platform, non-anonymous request when a token is held', async () => {
    const session = TestBed.inject(Session);
    session.adopt({ accessToken: 'tok-abc', expiresAt: new Date(Date.now() + 60_000).toISOString() });

    let seen: HttpRequest<unknown> | null = null;
    const next: HttpHandlerFn = (req) => {
      seen = req;
      return of(new HttpResponse({ status: 200 }));
    };

    await TestBed.runInInjectionContext(() =>
      firstValueFrom(bearerInterceptor(platformRequest(false), next)),
    );

    expect(seen!.headers.get('Authorization')).toBe('Bearer tok-abc');
  });

  it('does not attach a bearer to a non-platform request, even with a token held', async () => {
    const session = TestBed.inject(Session);
    session.adopt({ accessToken: 'tok-abc', expiresAt: new Date(Date.now() + 60_000).toISOString() });

    let seen: HttpRequest<unknown> | null = null;
    const next: HttpHandlerFn = (req) => {
      seen = req;
      return of(new HttpResponse({ status: 200 }));
    };

    await TestBed.runInInjectionContext(() =>
      firstValueFrom(bearerInterceptor(nonPlatformRequest(), next)),
    );

    expect(seen!.headers.has('Authorization')).toBe(false);
  });

  it('does not attach a bearer to a platform request marked ANONYMOUS', async () => {
    const session = TestBed.inject(Session);
    session.adopt({ accessToken: 'tok-abc', expiresAt: new Date(Date.now() + 60_000).toISOString() });

    let seen: HttpRequest<unknown> | null = null;
    const next: HttpHandlerFn = (req) => {
      seen = req;
      return of(new HttpResponse({ status: 200 }));
    };

    await TestBed.runInInjectionContext(() =>
      firstValueFrom(bearerInterceptor(platformRequest(true), next)),
    );

    expect(seen!.headers.has('Authorization')).toBe(false);
  });

  it('does not attach a bearer when there is no live token', async () => {
    let seen: HttpRequest<unknown> | null = null;
    const next: HttpHandlerFn = (req) => {
      seen = req;
      return of(new HttpResponse({ status: 200 }));
    };

    await TestBed.runInInjectionContext(() =>
      firstValueFrom(bearerInterceptor(platformRequest(false), next)),
    );

    expect(seen!.headers.has('Authorization')).toBe(false);
  });
});

describe('conventionsInterceptor', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({});
  });

  it('adds Accept and X-Correlation-Id to a platform request', async () => {
    let seen: HttpRequest<unknown> | null = null;
    const next: HttpHandlerFn = (req) => {
      seen = req;
      return of(new HttpResponse({ status: 200 }));
    };

    await TestBed.runInInjectionContext(() =>
      firstValueFrom(conventionsInterceptor(platformRequest(), next)),
    );

    expect(seen!.headers.get('Accept')).toBe('application/json, application/problem+json');
    expect(seen!.headers.get('X-Correlation-Id')).toBeTruthy();
  });

  it('leaves a non-platform request untouched', async () => {
    let seen: HttpRequest<unknown> | null = null;
    const next: HttpHandlerFn = (req) => {
      seen = req;
      return of(new HttpResponse({ status: 200 }));
    };

    await TestBed.runInInjectionContext(() =>
      firstValueFrom(conventionsInterceptor(nonPlatformRequest(), next)),
    );

    expect(seen!.headers.has('X-Correlation-Id')).toBe(false);
  });
});

describe('expiredSessionInterceptor', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({});
  });

  it('expires the session when the failure is SESSION_EXPIRED', async () => {
    const session = TestBed.inject(Session);
    session.adopt({ accessToken: 'tok-live', expiresAt: new Date(Date.now() + 60_000).toISOString() });

    const failure = new HorecaOSApiError({
      status: 401,
      code: 'SESSION_EXPIRED',
      detail: 'gone',
    });
    const next: HttpHandlerFn = () => throwError(() => failure);

    await expect(
      TestBed.runInInjectionContext(() =>
        firstValueFrom(expiredSessionInterceptor(platformRequest(), next)),
      ),
    ).rejects.toBe(failure);

    expect(session.accessToken()).toBeNull();
  });

  it('does not expire the session for a plain UNAUTHENTICATED failure', async () => {
    const session = TestBed.inject(Session);
    session.adopt({ accessToken: 'tok-live-2', expiresAt: new Date(Date.now() + 60_000).toISOString() });

    const failure = new HorecaOSApiError({
      status: 401,
      code: 'UNAUTHENTICATED',
      detail: 'wrong credentials',
    });
    const next: HttpHandlerFn = () => throwError(() => failure);

    await expect(
      TestBed.runInInjectionContext(() =>
        firstValueFrom(expiredSessionInterceptor(platformRequest(), next)),
      ),
    ).rejects.toBe(failure);

    // Still holding the live token: a plain UNAUTHENTICATED (e.g. a spent
    // sign-in grant, before anybody is signed in) must not clear a session
    // that is unrelated to it.
    expect(session.accessToken()).toBe('tok-live-2');
  });

  it('re-throws an HttpErrorResponse normalised, without expiring on a bare 401 that is not SESSION_EXPIRED', async () => {
    const session = TestBed.inject(Session);
    session.adopt({ accessToken: 'tok-live-3', expiresAt: new Date(Date.now() + 60_000).toISOString() });

    const httpError = new HttpErrorResponse({ status: 401, statusText: 'Unauthorized' });
    const next: HttpHandlerFn = () => throwError(() => httpError);

    const failure = await TestBed.runInInjectionContext(() =>
      firstValueFrom(expiredSessionInterceptor(platformRequest(), next)).catch((e: unknown) => e),
    );

    expect(failure).toBe(httpError);
    // A bare 401 normalises to INTERNAL_ERROR, not SESSION_EXPIRED -- so this
    // must not have cleared the session either.
    expect(session.accessToken()).toBe('tok-live-3');
  });

  it('passes a non-platform request straight through', async () => {
    const next: HttpHandlerFn = () => of(new HttpResponse({ status: 200 }));

    const result = (await TestBed.runInInjectionContext(() =>
      firstValueFrom(expiredSessionInterceptor(nonPlatformRequest(), next)),
    )) as HttpResponse<unknown>;

    expect(result.status).toBe(200);
  });
});

describe('problemDetailsInterceptor', () => {
  it('normalises an HttpErrorResponse into a HorecaOSApiError', async () => {
    const httpError = new HttpErrorResponse({
      status: 404,
      error: { status: 404, code: 'RESOURCE_NOT_FOUND', detail: 'no such cart' },
    });
    const next: HttpHandlerFn = () => throwError(() => httpError);

    const failure = await firstValueFrom(problemDetailsInterceptor(platformRequest(), next)).catch(
      (e: unknown) => e,
    );

    expect(failure).toBeInstanceOf(HorecaOSApiError);
    expect((failure as HorecaOSApiError).code).toBe('RESOURCE_NOT_FOUND');
  });

  it('passes through a failure that is not an HttpErrorResponse unchanged', async () => {
    const notHttp = new Error('boom, unrelated to http');
    const next: HttpHandlerFn = () => throwError(() => notHttp);

    const failure = await firstValueFrom(problemDetailsInterceptor(platformRequest(), next)).catch(
      (e: unknown) => e,
    );

    expect(failure).toBe(notHttp);
  });

  it('passes a success straight through', async () => {
    const result = (await firstValueFrom(
      problemDetailsInterceptor(platformRequest(), passthrough),
    )) as HttpResponse<unknown>;
    expect(result.status).toBe(200);
  });
});

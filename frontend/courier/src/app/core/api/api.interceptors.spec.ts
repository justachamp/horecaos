import { HttpClient, HttpContext, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { callbackFor, configureAuth, type AuthHarness } from '../../../testing/auth-harness';
import { AuthService } from '../auth/auth.service';
import { PLATFORM_API_REQUEST } from './api-client';
import {
  bearerInterceptor,
  conventionsInterceptor,
  problemDetailsInterceptor,
} from './api.interceptors';
import { ApiError } from './problem-details';

describe('API interceptors', () => {
  let harness: AuthHarness;
  let auth: AuthService;
  let http: HttpClient;
  let backend: HttpTestingController;

  const platform = () => ({ context: new HttpContext().set(PLATFORM_API_REQUEST, true) });

  /** The next request to the test URL, once the token has been resolved and the interceptors have run. */
  const nextRequest = (url = '/api/v1/courier/x') => vi.waitFor(() => backend.expectOne(url));

  beforeEach(async () => {
    sessionStorage.clear();
    harness = configureAuth();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(
          withInterceptors([conventionsInterceptor, bearerInterceptor, problemDetailsInterceptor]),
        ),
        provideHttpClientTesting(),
      ],
    });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);

    await auth.beginSignIn('/');
    await auth.completeSignIn(callbackFor(harness));
  });

  afterEach(() => {
    backend.verify();
    sessionStorage.clear();
  });

  it('sends the bearer token and the ADR 0031 headers to the platform', async () => {
    const answer = firstValueFrom(http.get('/api/v1/courier/x', platform()));

    const request = await nextRequest();
    expect(request.request.headers.get('Authorization')).toBe('Bearer access-1');
    expect(request.request.headers.get('X-Correlation-Id')).toMatch(/^[0-9a-f-]{36}$/);
    expect(request.request.headers.get('Accept')).toContain('application/problem+json');
    request.flush({});
    await answer;
  });

  it('never sends the token, or a custom header, anywhere that is not the platform', async () => {
    const answer = firstValueFrom(http.get('https://tiles.example.test/x'));

    const request = await nextRequest('https://tiles.example.test/x');
    expect(request.request.headers.has('Authorization')).toBe(false);
    expect(request.request.headers.has('X-Correlation-Id')).toBe(false);
    request.flush({});
    await answer;
  });

  it('renews the token and retries once when the platform does not recognise it', async () => {
    const answer = firstValueFrom(http.get('/api/v1/courier/x', platform()));
    (await nextRequest()).flush(
      { status: 401, code: 'UNAUTHENTICATED' },
      { status: 401, statusText: 'Unauthorized' },
    );

    const retry = await nextRequest();
    expect(retry.request.headers.get('Authorization')).toBe('Bearer access-2');
    retry.flush({ ok: true });

    await expect(answer).resolves.toEqual({ ok: true });
  });

  it('ends the session and starts a sign-in when the renewal is refused too', async () => {
    const answer = firstValueFrom(http.get('/api/v1/courier/x', platform())).catch(
      (failure: unknown) => failure,
    );
    const first = await nextRequest();
    harness.provider.refuseWith = 'invalid_grant';
    first.flush({}, { status: 401, statusText: 'Unauthorized' });

    const failure = await answer;
    expect(failure).toBeInstanceOf(ApiError);
    expect((failure as ApiError).code).toBe('SESSION_EXPIRED');
    expect(auth.signedIn()).toBe(false);
    await vi.waitFor(() =>
      expect(harness.navigations.at(-1)).toContain('/protocol/openid-connect/auth'),
    );
  });

  it('gives up after one retry rather than looping on a platform that keeps saying 401', async () => {
    const answer = firstValueFrom(http.get('/api/v1/courier/x', platform())).catch(
      (failure: unknown) => failure,
    );
    (await nextRequest()).flush({}, { status: 401, statusText: 'Unauthorized' });
    (await nextRequest()).flush({}, { status: 401, statusText: 'Unauthorized' });

    expect(((await answer) as ApiError).code).toBe('SESSION_EXPIRED');
  });

  it('turns a problem document into an ApiError carrying the stable code and the refusal reason', async () => {
    const answer = firstValueFrom(http.get('/api/v1/courier/x', platform())).catch(
      (failure: unknown) => failure,
    );
    (await nextRequest()).flush(
      {
        status: 422,
        code: 'UNPROCESSABLE_STATE',
        detail: 'too far',
        reason: 'TOO_FAR_FROM_PICKUP',
        correlationId: 'c-1',
      },
      { status: 422, statusText: 'Unprocessable' },
    );

    const failure = (await answer) as ApiError;
    expect(failure.status).toBe(422);
    expect(failure.code).toBe('UNPROCESSABLE_STATE');
    expect(failure.reason).toBe('TOO_FAR_FROM_PICKUP');
    expect(failure.correlationId).toBe('c-1');
  });

  it('reports a request that never arrived as NETWORK, not as a server answer', async () => {
    const answer = firstValueFrom(http.get('/api/v1/courier/x', platform())).catch(
      (failure: unknown) => failure,
    );
    (await nextRequest()).error(new ProgressEvent('error'), { status: 0 });

    const failure = (await answer) as ApiError;
    expect(failure.isNetwork).toBe(true);
  });

  it('leaves a non-JSON error body as a generic failure of that status', async () => {
    const answer = firstValueFrom(http.get('/api/v1/courier/x', platform())).catch(
      (failure: unknown) => failure,
    );
    (await nextRequest()).flush('<html>bad gateway</html>', {
      status: 502,
      statusText: 'Bad Gateway',
    });

    const failure = (await answer) as ApiError;
    expect(failure.status).toBe(502);
    expect(failure.code).toBe('INTERNAL_ERROR');
  });
});

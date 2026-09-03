import { HttpErrorResponse, HttpEvent, HttpHandlerFn, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, catchError, from, switchMap, throwError } from 'rxjs';

import { AccessTokenSource } from '../auth/access-token-source';
import { AuthService, REFRESH_PATH, SIGN_IN_PATH, SIGN_OUT_PATH } from '../auth/auth.service';
import { rememberReturnTo } from '../auth/guards';
import { PLATFORM_API_REQUEST } from './api-client';
import { ApiError, toProblem } from './problem';

/**
 * `X-Correlation-Id` on every platform request (ADR 0031).
 *
 * The server generates one when it is absent, so this is not strictly
 * required. It is here because a client-generated identifier exists before the
 * request leaves the browser, which means a failure that never reached the
 * server still has an identifier to quote.
 */
export const correlationIdInterceptor: HttpInterceptorFn = (request, next) => {
  if (!request.context.get(PLATFORM_API_REQUEST)) {
    return next(request);
  }
  return next(request.clone({ setHeaders: { 'X-Correlation-Id': crypto.randomUUID() } }));
};

/**
 * The bearer token, and only where it belongs.
 *
 * Gated on the platform-API context flag rather than on a URL prefix match. A
 * prefix match is one misconfigured base URL away from attaching a Keycloak
 * access token to a request to somebody else's host, and that failure is
 * silent.
 */
export const bearerTokenInterceptor: HttpInterceptorFn = (request, next) => {
  if (!request.context.get(PLATFORM_API_REQUEST)) {
    return next(request);
  }

  const token = inject(AccessTokenSource).accessToken();
  if (token === null) {
    // Deliberately still sent. The server answers 401 with a Problem Details
    // body the application can act on, which is more useful than a client-side
    // guess about whether a token would have been accepted.
    return next(request);
  }
  return next(request.clone({ setHeaders: { Authorization: `Bearer ${token}` } }));
};

/**
 * Turns every failed platform call into an `ApiError` carrying a Problem.
 *
 * Nothing downstream should ever see an `HttpErrorResponse`: a screen that
 * branches on `error.status` re-derives the error code the server already
 * sent, and gets it subtly wrong for the three different 409s.
 */
export const problemDetailsInterceptor: HttpInterceptorFn = (request, next) =>
  handleProblems(request, next);

function handleProblems(request: HttpRequest<unknown>, next: HttpHandlerFn): Observable<HttpEvent<unknown>> {
  if (!request.context.get(PLATFORM_API_REQUEST)) {
    return next(request);
  }

  return next(request).pipe(
    catchError((failure: unknown) => {
      if (!(failure instanceof HttpErrorResponse)) {
        return throwError(() => failure);
      }

      const problem = toProblem(
        failure.status,
        failure.headers.get('Content-Type'),
        failure.error,
        request.url,
      );

      // Only the code, the status and the correlation identifier are logged.
      // Not the URL, which carries tenant and customer identifiers, and not
      // the response body, which may carry whatever the caller submitted
      // (ADR 0029).
      console.warn(
        `[api] ${request.method} failed: ${problem.code} ${problem.status}`,
        problem.correlationId ?? '(no correlation id)',
      );

      // A stored failure replayed for a repeated key is still a failure, but
      // it is not a second attempt at anything: the effect happened once.
      const replayed = failure.headers.get('Idempotency-Replayed') === 'true';
      return throwError(() => new ApiError(problem, replayed));
    }),
  );
}

/** Platform requests this interceptor must never try to silently refresh — see the doc on `AuthService`'s exported path constants. */
const AUTH_SESSION_PATHS: readonly string[] = [SIGN_IN_PATH, REFRESH_PATH, SIGN_OUT_PATH];

function isAuthSessionRequest(url: string): boolean {
  return AUTH_SESSION_PATHS.some((path) => url.endsWith(path));
}

/**
 * Turns a mid-session 401 into one silent refresh-and-retry, so an expired
 * access token never reaches a screen as a raw error.
 *
 * The common trigger is the one named in the incident this exists to fix: a
 * laptop sleeps past `AuthService`'s own proactive refresh timer, wakes, and
 * whatever screen was open finds out its access token expired the hard way,
 * from a 401, instead of the easy way, from the timer. Refusing to leave that
 * as a rendered error is the whole point — the operator should see either
 * their screen, unchanged, or `/login`, never a Problem Details toast for a
 * session that is fixable in one round trip.
 *
 * Registered in `app.config.ts` between `correlationIdInterceptor` and
 * `bearerTokenInterceptor`, ahead of both on the way out and so behind both
 * on the way back: this must see the `ApiError` that `problemDetailsInterceptor`
 * (last on the way out, first back) already built, so it never re-parses a
 * Problem Details body itself, and a retried request must still pass forward
 * through `bearerTokenInterceptor` so it picks up the fresh token
 * `AuthService.refresh()` just wrote to `StaffTokenStore` rather than
 * replaying the stale one. Excluded from the sign-in/refresh/sign-out
 * endpoints themselves (`isAuthSessionRequest`) — without that, a refresh
 * token Keycloak has genuinely revoked would turn its own 401 into another
 * refresh attempt, forever.
 */
export const sessionRefreshInterceptor: HttpInterceptorFn = (request, next) => {
  if (!request.context.get(PLATFORM_API_REQUEST) || isAuthSessionRequest(request.url)) {
    return next(request);
  }

  const auth = inject(AuthService);
  const router = inject(Router);

  return next(request).pipe(
    catchError((failure: unknown) => {
      if (!(failure instanceof ApiError) || failure.status !== 401) {
        return throwError(() => failure);
      }

      return from(auth.refresh()).pipe(
        switchMap((refreshed) => {
          if (refreshed) {
            return next(request);
          }
          // The session is already cleared — `AuthService.refresh()` did that
          // on its own failure — so this only has to preserve where the
          // operator was headed and hand them to `/login`, the same
          // treatment `authGuard` gives an unguarded navigation.
          rememberReturnTo(router.url);
          void router.navigateByUrl('/login');
          return throwError(() => failure);
        }),
      );
    }),
  );
};

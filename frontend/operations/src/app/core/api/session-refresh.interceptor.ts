import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, from, switchMap, throwError } from 'rxjs';

import { Auth, REFRESH_PATH, SIGN_IN_PATH, SIGN_OUT_PATH } from '../auth/auth';
import { rememberReturnTo } from '../auth/auth.guard';

/** Platform requests this interceptor must never try to silently refresh — see the doc on `Auth`'s exported path constants. */
const AUTH_SESSION_PATHS: readonly string[] = [SIGN_IN_PATH, REFRESH_PATH, SIGN_OUT_PATH];

function isAuthSessionRequest(url: string): boolean {
  return AUTH_SESSION_PATHS.some((path) => url.endsWith(path));
}

/**
 * Turns a mid-session 401 into one silent refresh-and-retry, so an expired
 * access token never reaches a screen as a raw error.
 *
 * The common trigger is the one named in the incident this exists to fix: a
 * till tablet sleeps past `Auth`'s own proactive refresh timer, wakes, and
 * whatever screen was open finds out its access token expired the hard way,
 * from a 401, instead of the easy way, from the timer. Refusing to leave that
 * as a rendered error is the whole point — an operator mid-shift should see
 * either their screen, unchanged, or `/login`, never an error toast for a
 * session that is fixable in one round trip.
 *
 * Registered in `app.config.ts` **before** `bearerTokenInterceptor`: a
 * retried request must pass forward through it again to pick up the fresh
 * token `Auth.refresh()` just wrote to `StaffTokenStore`, not the stale one
 * the first attempt carried. This console has no `problemDetailsInterceptor`
 * of its own — `ApiClient` converts errors to `ApiError` itself, downstream
 * of every interceptor — so this operates on the raw `HttpErrorResponse`
 * every interceptor here sees, the same shape `bearerTokenInterceptor`'s own
 * same-origin check reads `request.url` from.
 *
 * Excluded from the sign-in/refresh/sign-out endpoints themselves
 * (`isAuthSessionRequest`) — without that, a refresh token Keycloak has
 * genuinely revoked would turn its own 401 into another refresh attempt,
 * forever.
 */
export const sessionRefreshInterceptor: HttpInterceptorFn = (request, next) => {
  const sameOrigin = request.url.startsWith('/') || request.url.startsWith(window.location.origin);
  if (!sameOrigin || isAuthSessionRequest(request.url)) {
    return next(request);
  }

  const auth = inject(Auth);
  const router = inject(Router);

  return next(request).pipe(
    catchError((failure: unknown) => {
      if (!(failure instanceof HttpErrorResponse) || failure.status !== 401) {
        return throwError(() => failure);
      }

      return from(auth.refresh()).pipe(
        switchMap((refreshed) => {
          if (refreshed) {
            return next(request);
          }
          // The session is already cleared — `Auth.refresh()` did that on its
          // own failure — so this only has to preserve where the operator
          // was headed and hand them to `/login`, the same treatment
          // `authGuard` gives an unguarded navigation.
          rememberReturnTo(router.url);
          void router.navigateByUrl('/login');
          return throwError(() => failure);
        }),
      );
    }),
  );
};

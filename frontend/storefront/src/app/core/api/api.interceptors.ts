import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';

import { Session } from '../auth/session';
import { ANONYMOUS, PLATFORM_API_REQUEST } from './api-client';
import { newCorrelationId } from './idempotency';
import { isSessionExpired, toHorecaOSApiError } from './problem-details';

/**
 * Headers ADR 0031 expects on every request <em>to the platform</em>.
 *
 * `X-Correlation-Id` is generated per request and is a random UUID: it must be
 * traceable through the platform's logs and must not identify a person, so it is
 * never derived from a customer, a device, or a session (ADR 0029).
 *
 * <p>Gated on {@link PLATFORM_API_REQUEST} for the same reason the bearer token
 * is, though the failure looks nothing alike. A custom header turns any
 * cross-origin request into a preflighted one, and the other origin has to name
 * that header in `Access-Control-Allow-Headers` or the browser refuses the
 * request before it is sent. Keycloak does not name it — so stamping every
 * request meant the OIDC discovery document could not be fetched at all, and
 * sign-in failed with a CORS error that says nothing about correlation ids.
 * Nothing outside the platform asked for this header, and sending it to them
 * only tells them we were here.
 */
export const conventionsInterceptor: HttpInterceptorFn = (request, next) => {
  if (!request.context.get(PLATFORM_API_REQUEST)) {
    return next(request);
  }
  return next(
    request.clone({
      setHeaders: {
        Accept: 'application/json, application/problem+json',
        'X-Correlation-Id': newCorrelationId(),
      },
    }),
  );
};

/**
 * The bearer token, from memory, and only where it belongs.
 *
 * Two gates, and they are not the same gate. {@link PLATFORM_API_REQUEST} says
 * the destination is the HorecaOS API at all — anywhere else, a token attached
 * here is a credential handed to a third party, which is a leak rather than a
 * mistake that shows up as a 401. {@link ANONYMOUS} then says that this
 * platform call is one the customer makes without an identity.
 *
 * There is no refresh-on-401 retry here: the session owns refresh and does it
 * proactively, and a retry loop in an interceptor turns one expired token into
 * a burst of failed requests.
 */
export const bearerInterceptor: HttpInterceptorFn = (request, next) => {
  if (!request.context.get(PLATFORM_API_REQUEST) || request.context.get(ANONYMOUS)) {
    return next(request);
  }
  const token = inject(Session).accessToken();
  if (!token) {
    return next(request);
  }
  return next(request.clone({ setHeaders: { Authorization: `Bearer ${token}` } }));
};

/**
 * Turns every failure into a {@link HorecaOSApiError}.
 *
 * Placed last so it also normalises failures the interceptors above produce, and
 * so no call site anywhere in the application has to know the shape of an
 * `HttpErrorResponse`.
 */
export const problemDetailsInterceptor: HttpInterceptorFn = (request, next) =>
  next(request).pipe(
    catchError((failure: unknown) =>
      throwError(() => (failure instanceof HttpErrorResponse ? toHorecaOSApiError(failure) : failure)),
    ),
  );

/**
 * Drops a bearer the platform has told us is dead.
 *
 * `SESSION_EXPIRED` is the one 401 that is a statement about the token we sent
 * rather than about the absence of one: the platform found the session, and it
 * has ended — its thirty days ran out, or it was revoked from another handset.
 * Nothing local can discover that. This tab's own clock says the token is good
 * until the deadline it was minted with, so without this the customer stays
 * `AUTHENTICATED` on screen, every guarded route lets them through, and every
 * call behind it fails, forever.
 *
 * Deliberately not "any 401". `POST /identity/sessions` answers 401 with
 * `UNAUTHENTICATED` for a spent grant, and that happens *before* anybody is
 * signed in — treating it as an expiry would clear a session that a moment
 * later is about to be adopted. Branching on the code is the whole point of
 * there being two of them (ADR 0051).
 *
 * The failure is re-thrown untouched: this ends the local session, it does not
 * decide what the screen says about it.
 */
export const expiredSessionInterceptor: HttpInterceptorFn = (request, next) => {
  if (!request.context.get(PLATFORM_API_REQUEST)) {
    return next(request);
  }
  const session = inject(Session);
  return next(request).pipe(
    catchError((failure: unknown) => {
      if (
        isSessionExpired(failure instanceof HttpErrorResponse ? toHorecaOSApiError(failure) : failure)
      ) {
        session.expire();
      }
      return throwError(() => failure);
    }),
  );
};

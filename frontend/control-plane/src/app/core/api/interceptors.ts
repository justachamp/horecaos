import { HttpErrorResponse, HttpEvent, HttpHandlerFn, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Observable, catchError, throwError } from 'rxjs';

import { AccessTokenSource } from '../auth/access-token-source';
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

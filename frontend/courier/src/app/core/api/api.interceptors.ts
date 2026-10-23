import { HttpErrorResponse, type HttpInterceptorFn, type HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, from, switchMap, throwError } from 'rxjs';

import { AuthService } from '../auth/auth.service';
import { PLATFORM_API_REQUEST } from './api-client';
import { newCorrelationId } from './idempotency';
import { ApiError, toApiError } from './problem-details';

/**
 * The headers ADR 0031 expects on every request to the platform -- and only to the platform: a
 * custom header turns a cross-origin request into a preflighted one the other origin has to
 * answer, and nothing outside the platform asked for it.
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
 * A platform refusal as nobody (401) gets exactly one renewal and one retry; if the renewal is
 * refused too the session is over, so the sign-in is started from here and the caller receives a
 * `SESSION_EXPIRED` error it can ignore, because the page is about to be replaced.
 */
export const bearerInterceptor: HttpInterceptorFn = (request, next) => {
  if (!request.context.get(PLATFORM_API_REQUEST)) {
    return next(request);
  }
  const auth = inject(AuthService);
  const router = inject(Router);

  const withToken = (token: string | null, original: HttpRequest<unknown>) =>
    token === null
      ? original
      : original.clone({ setHeaders: { Authorization: `Bearer ${token}` } });

  const sessionOver = (failure: ApiError) => {
    auth.expire();
    void auth.beginSignIn(router.url).catch(() => undefined);
    return throwError(
      () =>
        new ApiError({
          status: failure.status,
          code: 'SESSION_EXPIRED',
          detail: 'the session ended',
        }),
    );
  };

  return from(auth.accessToken()).pipe(
    switchMap((token) => next(withToken(token, request))),
    catchError((failure: unknown) => {
      const error = toApiError(failure);
      if (error.status !== 401) {
        return throwError(() => error);
      }
      return from(auth.forceRefresh()).pipe(
        switchMap((renewed) =>
          renewed
            ? from(auth.accessToken()).pipe(
                switchMap((token) => next(withToken(token, request))),
                catchError((second: unknown) => {
                  const secondError = toApiError(second);
                  return secondError.status === 401
                    ? sessionOver(secondError)
                    : throwError(() => secondError);
                }),
              )
            : sessionOver(error),
        ),
      );
    }),
  );
};

/** Turns every failure of a platform call into an {@link ApiError}. */
export const problemDetailsInterceptor: HttpInterceptorFn = (request, next) => {
  if (!request.context.get(PLATFORM_API_REQUEST)) {
    return next(request);
  }
  return next(request).pipe(
    catchError((failure: unknown) =>
      throwError(() => (failure instanceof HttpErrorResponse ? toApiError(failure) : failure)),
    ),
  );
};

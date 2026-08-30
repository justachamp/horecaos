import { HttpInterceptorFn, HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { NotificationService } from '../services/notification.service';
import { TranslateService } from '../services/translate.service';
import { PLATFORM_API_REQUEST } from '../core/api/api-client';
import { QoidaApiError, messageKeyFor } from '../core/api/problem-details';

const FALLBACK_MESSAGE = 'An error occurred';

interface ApiErrorBody {
  message?: string;
  code?: string;
}

function formatLegacyNotification(err: HttpErrorResponse): string {
  const body = err?.error as ApiErrorBody | null | undefined;
  const code = body?.code ?? err?.status?.toString() ?? 'ERR';
  const message =
    (typeof body?.message === 'string' && body.message.trim())
      ? body.message.trim()
      : FALLBACK_MESSAGE;
  return `${code} - ${message}`;
}

/**
 * Turns a failure into something a customer can read.
 *
 * The two backends are shown differently on purpose. The legacy API's own
 * `message` is written for a customer, so it is passed through. The platform's
 * `detail` is not: ADR 0031 says it is developer-facing, and a client that
 * printed it would put a sentence about an aggregate version in front of
 * somebody trying to buy lunch. Platform failures are mapped from their stable
 * `code` to a translated string instead, which is also the only way the message
 * appears in the customer's own language.
 *
 * Placed first in the chain so it sees the QoidaApiError that
 * `problemDetailsInterceptor` produces further down.
 */
export const errorNotificationInterceptor: HttpInterceptorFn = (req, next) => {
  const notification = inject(NotificationService);
  const translate = inject(TranslateService);
  const isPlatform = req.context.get(PLATFORM_API_REQUEST);

  return next(req).pipe(
    catchError((err: unknown) => {
      if (isPlatform) {
        // An expired session is not an error to report. The customer is about
        // to be shown a sign-in screen, and a toast saying "something went
        // wrong" on top of it explains nothing and blames them for it.
        if (err instanceof QoidaApiError && err.status !== 401) {
          notification.show(translate.get(messageKeyFor(err)));
        }
        return throwError(() => err);
      }
      if (err instanceof HttpErrorResponse && err.status !== 401) {
        notification.show(formatLegacyNotification(err));
      }
      return throwError(() => err);
    })
  );
};

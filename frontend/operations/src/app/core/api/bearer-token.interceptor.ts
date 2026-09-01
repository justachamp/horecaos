import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';

import { StaffTokenStore } from '../auth/staff-token-store';

/**
 * Attaches the bearer token to the platform API, and only to it (ADR 0062).
 *
 * Replaces `authInterceptor()` from `angular-auth-oidc-client`, which used to
 * attach the token to every request listed in `secureRoutes`. That
 * configuration doesn't exist any more because there is nothing else this
 * application's `HttpClient` talks to: before ADR 0062, the OIDC library used
 * this same client to reach Keycloak's own discovery document and token
 * endpoint, so `secureRoutes` existed specifically to keep the bearer off
 * those calls. There are no such calls left — the browser never talks to
 * Keycloak — so the same-origin guard `correlationIdInterceptor` already uses
 * is reused here rather than invented twice.
 *
 * A wildcard match here would send a token to any host this application ever
 * calls, including a map tile server; the same-origin check is the boundary
 * that matters.
 */
export const bearerTokenInterceptor: HttpInterceptorFn = (request, next) => {
  const sameOrigin = request.url.startsWith('/') || request.url.startsWith(window.location.origin);
  if (!sameOrigin) {
    return next(request);
  }

  const token = inject(StaffTokenStore).accessToken();
  if (token === null) {
    // Deliberately still sent. The server answers 401 with a Problem Details
    // body this application can act on, which is more useful than a
    // client-side guess about whether a token would have been accepted.
    return next(request);
  }
  return next(request.clone({ setHeaders: { Authorization: `Bearer ${token}` } }));
};

import {
  ApplicationConfig,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
  provideZonelessChangeDetection,
  inject,
} from '@angular/core';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { provideRouter, withComponentInputBinding } from '@angular/router';

import { routes } from './app.routes';
import {
  bearerTokenInterceptor,
  correlationIdInterceptor,
  problemDetailsInterceptor,
  sessionRefreshInterceptor,
} from './core/api/interceptors';
import { AccessTokenSource } from './core/auth/access-token-source';
import { AuthService } from './core/auth/auth.service';
import { SessionContextService } from './core/auth/session-context.service';
import { APP_CONFIG, resolveAppConfig } from './core/config/app-config';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZonelessChangeDetection(),
    provideRouter(routes, withComponentInputBinding()),

    /**
     * `withFetch` rather than XHR: it is the platform primitive, it supports
     * request cancellation properly, and the ADR 0031 client has no need for
     * anything XHR offers that fetch does not.
     *
     * Interceptor order is the order they run in on the way out, and it
     * matters. Correlation first so the identifier exists on every request;
     * `sessionRefreshInterceptor` next, so a request it retries still passes
     * forward through the bearer token interceptor and picks up a freshly
     * refreshed token; the bearer token itself next; Problem Details last, so
     * it is the innermost wrapper on the way back, no other interceptor sees
     * a raw `HttpErrorResponse`, and `sessionRefreshInterceptor` — earlier on
     * the way out, later on the way back — receives the `ApiError` Problem
     * Details already built rather than a raw response of its own to parse.
     */
    provideHttpClient(
      withFetch(),
      withInterceptors([
        correlationIdInterceptor,
        sessionRefreshInterceptor,
        bearerTokenInterceptor,
        problemDetailsInterceptor,
      ]),
    ),

    { provide: APP_CONFIG, useFactory: () => resolveAppConfig() },

    { provide: AccessTokenSource, useExisting: AuthService },

    /**
     * `AuthService.initialise()` completes before the first route is
     * resolved, so the guard reads a settled status signal rather than doing
     * async work of its own. Before ADR 0062 this was the step that finished
     * a Keycloak redirect and could itself fail; today it redeems whatever
     * refresh token `StaffTokenStore` finds waiting in `sessionStorage` — see
     * that class's own doc and `AuthService.initialise()`'s. When that
     * succeeds, `session.load()` runs here too, before the first route
     * resolves, for the same reason {@link SignInPage} awaits it after a
     * manual sign-in: `ConsoleShell`'s rail and every `requiresCapability`
     * guard need it loaded, and a route resolved before it is loaded reads
     * every capability as absent and bounces to `/denied`. A cold start with
     * nothing stored, or a stored token the platform refuses, both settle to
     * `signed-out` exactly as before, and `authGuard` sends the operator to
     * `/login` with `returnTo` set.
     */
    provideAppInitializer(() => {
      // The injection happens before the first await. An `inject()` after one
      // runs outside the injection context and fails with NG0203, which is a
      // runtime error in a code path that only executes at start-up — so it
      // reaches production looking like a blank page.
      const auth = inject(AuthService);
      const session = inject(SessionContextService);

      return (async () => {
        const status = await auth.initialise();
        if (status === 'signed-in') {
          await session.load();
        }
      })();
    }),
  ],
};

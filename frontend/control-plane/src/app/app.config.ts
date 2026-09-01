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
} from './core/api/interceptors';
import { AccessTokenSource } from './core/auth/access-token-source';
import { AuthService } from './core/auth/auth.service';
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
     * the bearer token next; Problem Details last, so it is the innermost
     * wrapper on the way back and no other interceptor sees a raw
     * HttpErrorResponse.
     */
    provideHttpClient(
      withFetch(),
      withInterceptors([
        correlationIdInterceptor,
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
     * a Keycloak redirect and could itself fail; `initialise()` now makes no
     * network call and always settles to `signed-out` — a fresh load never
     * has a session to restore (tokens are in-memory only, ADR 0035) — so
     * this stays for the ordering guarantee alone. `session.load()` runs from
     * here only when there already is a session to describe, which after ADR
     * 0062 is never on a cold start; {@link SignInPage} calls it itself once
     * sign-in succeeds, the same "rail cannot render without it" reasoning
     * this used to apply after a redirect completed.
     */
    provideAppInitializer(() => {
      // The injection happens before the first await. An `inject()` after one
      // runs outside the injection context and fails with NG0203, which is a
      // runtime error in a code path that only executes at start-up — so it
      // reaches production looking like a blank page.
      const auth = inject(AuthService);

      return (async () => {
        await auth.initialise();
      })();
    }),
  ],
};

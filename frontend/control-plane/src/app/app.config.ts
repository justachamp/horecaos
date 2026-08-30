import {
  ApplicationConfig,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
  provideZonelessChangeDetection,
  inject,
} from '@angular/core';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { OAuthStorage, provideOAuthClient } from 'angular-oauth2-oidc';

import { routes } from './app.routes';
import {
  bearerTokenInterceptor,
  correlationIdInterceptor,
  problemDetailsInterceptor,
} from './core/api/interceptors';
import { AccessTokenSource } from './core/auth/access-token-source';
import { AuthService } from './core/auth/auth.service';
import { InMemoryOAuthStorage } from './core/auth/in-memory-oauth-storage';
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
     * the bearer token next; Problem Details last, so it is the innermost
     * wrapper on the way back and no other interceptor sees a raw
     * HttpErrorResponse.
     */
    provideHttpClient(
      withFetch(),
      withInterceptors([correlationIdInterceptor, bearerTokenInterceptor, problemDetailsInterceptor]),
    ),

    { provide: APP_CONFIG, useFactory: () => resolveAppConfig() },

    provideOAuthClient(),
    { provide: OAuthStorage, useClass: InMemoryOAuthStorage },
    { provide: AccessTokenSource, useExisting: AuthService },

    /**
     * Authentication completes before the first route is resolved.
     *
     * The guards read a status signal rather than doing async work of their
     * own, which keeps a guard synchronous and keeps the "am I signed in"
     * question from being asked concurrently by three routes at once.
     */
    provideAppInitializer(() => {
      // Both injections happen before the first await. An `inject()` after one
      // runs outside the injection context and fails with NG0203, which is a
      // runtime error in a code path that only executes at start-up — so it
      // reaches production looking like a blank page.
      const auth = inject(AuthService);
      const session = inject(SessionContextService);

      return (async () => {
        if ((await auth.initialise()) === 'signed-in') {
          // The rail cannot be rendered without knowing what the operator may
          // reach, so this is awaited rather than left to resolve later and
          // rearrange the navigation under the pointer.
          await session.load();
        }
      })();
    }),
  ],
};

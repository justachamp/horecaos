import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';

import { routes } from './app.routes';
import { bearerTokenInterceptor } from './core/api/bearer-token.interceptor';
import { correlationIdInterceptor } from './core/api/correlation-id.interceptor';
import { Auth } from './core/auth/auth';
import { I18n } from './core/i18n/i18n';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),

    provideRouter(
      routes,
      // Route params and route `data` arrive as component inputs, so a detail
      // pane declares what it needs instead of reaching into ActivatedRoute.
      withComponentInputBinding(),
      // The docked detail scrolls inside its own pane; the page itself must not
      // jump to the top when the operator selects a different order.
      withInMemoryScrolling({ scrollPositionRestoration: 'disabled' }),
    ),

    provideHttpClient(
      withInterceptors([
        // Order matters. The correlation id goes on first so that it is present
        // on every request, including one the bearer interceptor sends with no
        // token because there is not one yet.
        correlationIdInterceptor,
        bearerTokenInterceptor,
      ]),
    ),

    /**
     * `Auth.initialise()` completes before the first route is resolved, so
     * the guard reads a settled status rather than doing async work of its
     * own. Before ADR 0062 this was `provideHorecaOSAuth()` plus whatever the
     * OIDC library's own bootstrap needed; `initialise()` now makes no
     * network call and always settles to `signed-out` — a fresh load never
     * has a session to restore (tokens are in-memory only, ADR 0035).
     */
    provideAppInitializer(() => {
      inject(Auth).initialise();
    }),

    // The stored locale is applied to <html lang> before the first paint.
    // Getting this wrong is not cosmetic: `lang` is what a screen reader uses to
    // choose a voice, and Russian read with an English voice is unintelligible.
    provideAppInitializer(() => {
      const i18n = inject(I18n);
      i18n.setLocale(i18n.locale());
    }),
  ],
};

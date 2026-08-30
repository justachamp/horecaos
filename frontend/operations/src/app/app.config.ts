import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';
import { authInterceptor } from 'angular-auth-oidc-client';

import { routes } from './app.routes';
import { correlationIdInterceptor } from './core/api/correlation-id.interceptor';
import { provideQoidaAuth } from './core/auth/auth.providers';
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
        // on requests the auth interceptor may fail or retry.
        correlationIdInterceptor,
        // Attaches the bearer, and only to the origins listed in `secureRoutes`.
        authInterceptor(),
      ]),
    ),

    provideQoidaAuth(),

    // The stored locale is applied to <html lang> before the first paint.
    // Getting this wrong is not cosmetic: `lang` is what a screen reader uses to
    // choose a voice, and Russian read with an English voice is unintelligible.
    provideAppInitializer(() => {
      const i18n = inject(I18n);
      i18n.setLocale(i18n.locale());
    }),
  ],
};

import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import {
  type ApplicationConfig,
  inject,
  isDevMode,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideRouter, TitleStrategy, withComponentInputBinding } from '@angular/router';
import { provideServiceWorker } from '@angular/service-worker';

import { routes } from './app.routes';
import {
  bearerInterceptor,
  conventionsInterceptor,
  problemDetailsInterceptor,
} from './core/api/api.interceptors';
import { I18n } from './core/i18n/i18n';
import { AppTitleStrategy } from './core/i18n/title-strategy';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    // The language is chosen before the first paint, so no screen ever draws as raw keys.
    provideAppInitializer(() => inject(I18n).init()),
    provideRouter(routes, withComponentInputBinding()),
    { provide: TitleStrategy, useClass: AppTitleStrategy },
    // Order matters: conventions outermost, then the bearer, and the problem-details mapping
    // innermost so the bearer sees a normalised ApiError when it decides whether to renew.
    provideHttpClient(
      withFetch(),
      withInterceptors([conventionsInterceptor, bearerInterceptor, problemDetailsInterceptor]),
    ),
    // The shell is installable and loads without the network; every screen still needs the
    // network to do anything (ADR 0045: there is no offline write queue). Registered once the
    // app is stable so it never competes with the first paint.
    provideServiceWorker('ngsw-worker.js', {
      enabled: !isDevMode(),
      registrationStrategy: 'registerWhenStable:30000',
    }),
  ],
};

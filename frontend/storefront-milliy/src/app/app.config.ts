import { ApplicationConfig, provideBrowserGlobalErrorListeners, provideAppInitializer, inject } from '@angular/core';
import {
  PreloadAllModules,
  provideRouter,
  withComponentInputBinding,
  withPreloading,
} from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';

import { environment } from '../environments/environment';
import { routes } from './app.routes';
import { TranslateService } from './services/translate.service';
import { ThemeService } from './services/theme.service';
import { provideYaConfig, YaConfig } from 'angular8-yandex-maps';
import { errorNotificationInterceptor } from './interceptors/error-notification.interceptor';
import {
  bearerInterceptor,
  conventionsInterceptor,
  expiredSessionInterceptor,
  problemDetailsInterceptor,
} from './core/api/api.interceptors';

const yaConfig: YaConfig = {
  apikey: environment.yandexMapsApiKey,
};

export const appConfig: ApplicationConfig = {
  providers: [
    provideAppInitializer(async () => {
      try {
        const theme = inject(ThemeService);
        await theme.load();
      } catch {
        console.error('Error initializing theme');
      }
    }),
    provideAppInitializer(async () => {
      try {
        const translate = inject(TranslateService);
        await translate.init();
      } catch {
        console.error('Error initializing translations');
      }
    }),
    provideYaConfig(yaConfig),
    provideBrowserGlobalErrorListeners(),
    // Component input binding feeds a route param straight into a required
    // input, so a screen never reaches for the router to learn what it is showing.
    provideRouter(routes, withPreloading(PreloadAllModules), withComponentInputBinding()),
    provideHttpClient(
      // Order matters. errorNotificationInterceptor is outermost so it sees the
      // HorecaOSApiError that problemDetailsInterceptor produces below it, and that
      // one is innermost so it also normalises failures the others cause.
      //
      // The legacy auth, headers and unauthorized interceptors are gone with the
      // backend they served. Their jobs are done by bearerInterceptor (which
      // sends a token only to the platform, and never on an anonymous call),
      // conventionsInterceptor, and expiredSessionInterceptor (which branches on
      // SESSION_EXPIRED rather than on any 401 -- a spent sign-in grant also
      // answers 401, and treating that as an expiry would clear a session a
      // moment before it is adopted).
      withInterceptors([
        errorNotificationInterceptor,
        expiredSessionInterceptor,
        conventionsInterceptor,
        bearerInterceptor,
        problemDetailsInterceptor,
      ]),
    ),
  ],
};

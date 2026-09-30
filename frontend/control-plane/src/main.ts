import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';
import { DEFAULT_LOCALE, peekStoredLocale, preloadLocale } from './app/core/i18n/i18n.service';

/**
 * Awaits only the stored locale's catalogue before bootstrapping. Russian is in
 * this bundle, so for the default locale — every first-time visitor — this adds
 * no wait; a returning `en` or `uz-Latn` reader pays one chunk fetch here and
 * gets a first paint already in their language instead of a flash of Russian.
 * A failed fetch must not stop the console opening: it opens in Russian and the
 * language switcher retries.
 */
async function bootstrap(): Promise<void> {
  const locale = peekStoredLocale();
  if (locale !== DEFAULT_LOCALE) {
    await preloadLocale(locale).catch((err) => console.error(err));
  }
  await bootstrapApplication(App, appConfig);
}

bootstrap().catch((err) => console.error(err));

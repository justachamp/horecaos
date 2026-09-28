import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';
import { DEFAULT_LOCALE, peekStoredLocale, preloadLocale } from './app/core/i18n/i18n';

/**
 * Awaits only the persisted locale's catalogue, not all three (ADR 0035's
 * loading-model addition — see `core/i18n/i18n.ts`'s class doc comment for
 * the full model).
 *
 * `ru` is already in this bundle, so for the default locale — and therefore
 * for every first-time operator — this resolves synchronously and adds no
 * wait before `bootstrapApplication`. For a returning operator who chose
 * `en` or `uz-Latn`, this is the one await between the bundle arriving and
 * first paint: it is what lets `I18n`'s constructor set `<html lang>` and
 * back `t()` with the right catalogue on the very first render, with no
 * flash of Russian and no one-frame fallback to the raw key.
 */
async function bootstrap(): Promise<void> {
  const locale = peekStoredLocale();
  if (locale !== DEFAULT_LOCALE) {
    await preloadLocale(locale);
  }
  await bootstrapApplication(App, appConfig);
}

bootstrap().catch((err) => console.error(err));

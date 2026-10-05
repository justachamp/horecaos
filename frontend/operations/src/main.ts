import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';
import { DEFAULT_LOCALE, peekStoredLocale, preloadLocale } from './app/core/i18n/i18n';

/**
 * Awaits only the persisted locale's `core` messages, not all three locales' and not any feature
 * area (ADR 0035's loading model -- see `core/i18n/i18n.ts`'s class doc comment for the full model).
 *
 * `ru`'s `core` is already in this bundle, so for the default locale -- and therefore for every
 * first-time operator -- this resolves synchronously and adds no wait before `bootstrapApplication`.
 * For a returning operator who chose `en` or `uz-Latn`, this is the one await between the bundle
 * arriving and first paint: it is what lets `I18n`'s constructor set `<html lang>` and back `t()` with
 * the right language on the very first render, with no flash of Russian and no one-frame fallback to
 * the raw key. The areas the first route shows are not awaited here; the router waits for them
 * (`messagesGuard` in `app.routes.ts`), in whichever locale this resolved to.
 */
async function bootstrap(): Promise<void> {
  const locale = peekStoredLocale();
  if (locale !== DEFAULT_LOCALE) {
    // A rejected preload (a bad deploy, a flaky connection on the
    // locale-chunk fetch) must never stop the app from opening at all — the
    // worst acceptable outcome is one flash of Russian or a raw-key fallback
    // until the operator's next `setLocale`, not a permanently empty
    // `<q-root>`. See i18n.ts's `ensureLoaded`, which retries rather than
    // caching this rejection forever.
    await preloadLocale(locale).catch((err) => console.error(err));
  }
  await bootstrapApplication(App, appConfig);
}

bootstrap().catch((err) => console.error(err));

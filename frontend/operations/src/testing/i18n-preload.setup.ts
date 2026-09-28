import { LOCALES, preloadLocale } from '../app/core/i18n/i18n';

/**
 * Global Vitest setup (wired in `angular.json`'s `test` target via
 * `setupFiles`) that warms every locale's catalogue once, before any spec
 * file runs.
 *
 * ADR 0035's loading-model addition made `en` and `uz-Latn` load through a
 * dynamic `import()` on request (see `core/i18n/i18n.ts`'s class doc
 * comment). Well over a hundred existing specs call
 * `TestBed.inject(I18n).setLocale('en' | 'ru' | 'uz-Latn')` and assert
 * rendered text in the very next synchronous line — a pattern `setLocale`
 * only honours immediately when the target catalogue is *already* cached
 * (see its doc comment). Warming all three here, once, keeps every one of
 * those specs meaningful without editing each call site: by the time any
 * spec's `setLocale` call runs, the catalogue it asks for has already
 * resolved.
 *
 * This is a real `await` of the real dynamic import, not a fake timer —
 * `core/i18n/i18n.spec.ts` covers the not-yet-loaded path directly, with
 * `vi.resetModules()`, precisely so this file isn't the only thing standing
 * between the suite and a lazy-loading regression that only shows up when a
 * catalogue truly isn't warm yet.
 */
await Promise.all(LOCALES.map((locale) => preloadLocale(locale)));

import { LOCALES, preloadAllAreas } from '../app/core/i18n/i18n';

/**
 * Global Vitest setup (wired in `angular.json`'s `test` target via
 * `setupFiles`) that warms every area of every locale's messages once, before
 * any spec file runs.
 *
 * ADR 0035's loading model makes the messages load through dynamic `import()`s
 * on request: `core` of the default locale ships eagerly, everything else --
 * the other locales, and each feature area -- is its own chunk (see
 * `core/i18n/i18n.ts`'s class doc comment). Well over a hundred existing specs
 * call `TestBed.inject(I18n).setLocale('en' | 'ru' | 'uz-Latn')`, render a
 * component from any area, and assert rendered text in the very next
 * synchronous line -- a pattern `setLocale` only honours immediately when
 * everything it needs is *already* cached, and a component only renders
 * without raw keys when its area is. Warming all of it here, once, keeps every
 * one of those specs meaningful without editing each call site: by the time any
 * spec runs, the messages it asks for have already resolved.
 *
 * This is a real `await` of the real dynamic imports, not a fake timer --
 * `core/i18n/i18n.spec.ts` covers the not-yet-loaded paths directly, with
 * `resetCatalogueCacheForTesting()`, precisely so this file isn't the only
 * thing standing between the suite and a lazy-loading regression that only
 * shows up when an area truly isn't warm yet.
 */
await Promise.all(LOCALES.map((locale) => preloadAllAreas(locale)));

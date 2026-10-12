import { CATALOGUE_LOADERS } from '../app/core/i18n/catalogue-loaders';
import { LOCALES, preloadAllAreas, registerCatalogueLoaders } from '../app/core/i18n/i18n';
import { seedPlatformLocalesForTesting } from '../app/core/i18n/platform-locales';
import { REGISTRY_FIXTURE } from './platform-locales.fixture';

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
 * This file is also the one place the unit tests get the loaders from: `i18n.ts` does not import
 * `catalogue-loaders.ts`, so a spec bundle (each spec is its own esbuild entry, with code splitting
 * disabled) does not carry the catalogues; the real loaders are registered here, once, and every spec's
 * copy of `I18n` finds them in the shared `globalThis` slot. See `catalogue-loaders.ts`.
 *
 * This is a real `await` of the real dynamic imports, not a fake timer --
 * `core/i18n/i18n.spec.ts` covers the not-yet-loaded paths directly, with
 * `resetCatalogueCacheForTesting()`, precisely so this file isn't the only
 * thing standing between the suite and a lazy-loading regression that only
 * shows up when an area truly isn't warm yet.
 */
registerCatalogueLoaders(CATALOGUE_LOADERS);
await Promise.all(LOCALES.map((locale) => preloadAllAreas(locale)));

/**
 * Every spec starts with the registry already read (ADR 0149), as the shell guarantees for anything
 * beneath it: the editors read the languages a brand may choose, a template needs and the catalog
 * stores synchronously. `platform-locales.spec.ts` clears the seed to test the unread state.
 */
seedPlatformLocalesForTesting(REGISTRY_FIXTURE);

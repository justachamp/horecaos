import { LOCALES, preloadLocale } from '../app/core/i18n/i18n.service';
import { seedPlatformLocalesForTesting } from '../app/core/i18n/platform-locales';
import { REGISTRY_FIXTURE } from './platform-locales.fixture';

/**
 * Global Vitest setup (wired in `angular.json`'s `test` target via
 * `setupFiles`) that warms every locale's catalogue once, before any spec file
 * runs.
 *
 * `en` and `uz-Latn` load through a dynamic `import()` on request (see
 * `core/i18n/i18n.service.ts`), so a `use('en')` on a catalogue that is not yet
 * in memory takes effect a moment later. Specs that switch locale and assert on
 * the very next line rely on the catalogue already being cached; warming all
 * three here keeps them meaningful without editing each call site.
 * `i18n.service.spec.ts` covers the not-yet-loaded path directly.
 */
await Promise.all(LOCALES.map((locale) => preloadLocale(locale)));

/**
 * Every spec starts with the registry already read (ADR 0149), as the shell guarantees for anything
 * beneath it. `platform-locales.spec.ts` clears the seed to test the unread state.
 */
seedPlatformLocalesForTesting(REGISTRY_FIXTURE);

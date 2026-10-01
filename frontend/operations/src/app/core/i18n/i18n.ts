import { Injectable, Signal, signal } from '@angular/core';

import type { MessageCatalogue, MessageKey } from './messages.en';
import { messagesRu } from './messages.ru';

/**
 * The three locales this platform supports (ADR 0035).
 *
 * `uz-Latn` carries its script subtag because uz-Latn and uz-Cyrl are different
 * locales and a bare `uz` is ambiguous.
 */
export const LOCALES = ['ru', 'uz-Latn', 'en'] as const;
export type Locale = (typeof LOCALES)[number];

/**
 * Russian is the default, not English.
 *
 * The staff using this console work in Russian and Uzbek. Defaulting to English
 * and letting them switch means every operator's first screen is in a language
 * they did not ask for, and on a shared terminal they will switch it again
 * tomorrow.
 */
export const DEFAULT_LOCALE: Locale = 'ru';

/** Exported for `i18n.spec.ts`'s cold-start test; not otherwise a public API. */
export const STORAGE_KEY = 'horecaos.operations.locale';

/**
 * Dynamic-import loaders for the two catalogues that do not ship eagerly.
 *
 * `messages.en.ts` (351 kB raw), `messages.ru.ts` (455 kB raw) and
 * `messages.uz-latn.ts` (368 kB raw) together used to cost the initial bundle
 * 1.17 MB of raw catalogue text — see the loading-model addition to ADR 0035.
 * Only `ru` still loads eagerly, as `messagesRu` below; `en` and `uz-Latn`
 * each become their own chunk, fetched the first time a caller actually asks
 * for that locale.
 */
const LOADERS: Record<Exclude<Locale, 'ru'>, () => Promise<MessageCatalogue>> = {
  en: () => import('./messages.en').then((m) => m.messagesEn),
  'uz-Latn': () => import('./messages.uz-latn').then((m) => m.messagesUzLatn),
};

/**
 * Test-only: overrides one lazy locale's loader, e.g. to simulate a failed
 * dynamic import without touching the network. Returns a function that
 * restores the real loader; every caller must call it before the test ends,
 * since `LOADERS` — like the cache in {@link catalogueCache} — is shared
 * with every other spec bundled into the same entry point (see that
 * function's doc comment).
 */
export function setLoaderForTesting(
  locale: Exclude<Locale, 'ru'>,
  loader: () => Promise<MessageCatalogue>,
): () => void {
  const original = LOADERS[locale];
  LOADERS[locale] = loader;
  return () => {
    LOADERS[locale] = original;
  };
}

interface CatalogueCache {
  readonly loaded: Map<Locale, MessageCatalogue>;
  readonly pending: Map<Locale, Promise<MessageCatalogue>>;
}

const CACHE_SLOT = Symbol.for('horecaos.operations.i18n.catalogue-cache');

/**
 * The load cache, shared by every `I18n` instance — there is normally one,
 * `providedIn: 'root'` — and by {@link preloadLocale}'s callers.
 *
 * This reads from a `globalThis` slot rather than a plain module-level
 * variable for a reason specific to this app's unit-test builder: its
 * generated Vitest config sets `disableCodeSplitting: true` because every
 * spec file is its own esbuild entry point (see that option's own comment
 * in the Angular CLI source — bundling all specs from shared chunks makes
 * ESM live bindings across a spec/setup-file boundary read `undefined`
 * before the chunk's lazy initializer has run). One consequence: this
 * module is bundled once *per entry point*, so `src/testing/i18n-preload.setup.ts`
 * and, say, `order-queue.spec.ts` each get their own independent copy of
 * this file, each with its own module-scoped variables that share nothing.
 * A `Symbol.for` registry key is the one thing that *is* shared across those
 * independent bundles, as long as they run in the same JS realm — which
 * they do: Vitest's `isolate: false` (this project's default, deliberately,
 * per that option's "Karma/Jasmine experience" framing) runs every spec
 * file in one shared environment rather than a fresh one per file. Without
 * this indirection, the preload setup file would warm a cache nothing else
 * can ever see, and every spec calling `setLocale('en' | 'uz-Latn')` would
 * hit the not-yet-loaded path regardless.
 *
 * None of this matters for the one production bundle, where this module
 * exists exactly once — the `globalThis` slot is just an unused extra
 * indirection there, not a behaviour change.
 */
function catalogueCache(): CatalogueCache {
  const globalObject = globalThis as typeof globalThis & { [CACHE_SLOT]?: CatalogueCache };
  return (globalObject[CACHE_SLOT] ??= {
    loaded: new Map<Locale, MessageCatalogue>([[DEFAULT_LOCALE, messagesRu]]),
    pending: new Map<Locale, Promise<MessageCatalogue>>(),
  });
}

function ensureLoaded(locale: Locale): Promise<MessageCatalogue> {
  const cache = catalogueCache();
  const cached = cache.loaded.get(locale);
  if (cached) {
    return Promise.resolve(cached);
  }
  let inFlight = cache.pending.get(locale);
  if (!inFlight) {
    inFlight = LOADERS[locale as Exclude<Locale, 'ru'>]().then(
      (catalogue) => {
        cache.loaded.set(locale, catalogue);
        cache.pending.delete(locale);
        return catalogue;
      },
      (err: unknown) => {
        // Don't leave a rejected promise cached forever: the next request
        // for this locale — a retry, or the operator clicking the language
        // switcher again — must hit the network again, not the same dead
        // promise. Without this, one transient failure permanently disables
        // that locale for the rest of the tab's life.
        cache.pending.delete(locale);
        throw err;
      },
    );
    cache.pending.set(locale, inFlight);
  }
  return inFlight;
}

/**
 * Warms a locale's catalogue without switching to it, so a later
 * {@link I18n.setLocale} for that locale resolves synchronously instead of
 * leaving the previous catalogue on screen for a frame.
 *
 * Two callers use this:
 *
 *  - `main.ts`'s bootstrap, for the locale a returning operator last chose —
 *    persisted in `localStorage` under {@link STORAGE_KEY} — so first paint
 *    already renders in that language instead of flashing `ru` and then
 *    swapping. See `peekStoredLocale`.
 *  - The unit-test suite's global setup (`src/testing/i18n-preload.setup.ts`),
 *    which warms every locale once before any spec runs. Every existing spec
 *    calls `setLocale('en' | 'ru' | 'uz-Latn')` and asserts in the same
 *    synchronous tick; that only keeps working because the catalogue it asks
 *    for is already cached by the time the spec runs — `setLocale` itself
 *    makes no promise about *when* an uncached locale becomes visible, only
 *    that it will, atomically, once loaded.
 */
export function preloadLocale(locale: Locale): Promise<void> {
  return ensureLoaded(locale).then(() => undefined);
}

/**
 * Test-only: drops the shared cache so the next `ensureLoaded` call behaves
 * like nothing has ever been requested, including `ru`. `i18n.spec.ts` uses
 * this to exercise the not-yet-loaded paths in {@link I18n}'s doc comment —
 * the previous-catalogue-meanwhile behaviour, the superseded-request
 * discard, and the cold-start raw-key fallback — which the suite-wide
 * preload in `src/testing/i18n-preload.setup.ts` otherwise makes
 * unreachable. Every test that calls this restores the cache before it ends
 * (see that spec file), because the cache is process-global — see
 * {@link catalogueCache}'s doc comment for why.
 */
export function resetCatalogueCacheForTesting(): void {
  const globalObject = globalThis as typeof globalThis & { [CACHE_SLOT]?: CatalogueCache };
  delete globalObject[CACHE_SLOT];
}

/** Narrowing guard, for values arriving from storage or a query string. */
export function isLocale(value: unknown): value is Locale {
  return typeof value === 'string' && (LOCALES as readonly string[]).includes(value);
}

/**
 * Reads the persisted locale without constructing {@link I18n} — used by
 * `main.ts`'s pre-bootstrap warm-up, which runs before Angular's injector
 * exists.
 */
export function peekStoredLocale(): Locale {
  try {
    const stored = globalThis.localStorage?.getItem(STORAGE_KEY);
    if (isLocale(stored)) {
      return stored;
    }
  } catch {
    // Fall through to the default.
  }
  return DEFAULT_LOCALE;
}

/**
 * Whether anybody has chosen a language on this browser. A person who has not
 * -- a new device, a cleared profile -- gets the interface language their staff
 * record carries (`OwnProfile`); one who has is never overridden by it.
 */
export function hasStoredLocale(): boolean {
  try {
    return isLocale(globalThis.localStorage?.getItem(STORAGE_KEY));
  } catch {
    return false;
  }
}

function persistLocale(locale: Locale): void {
  try {
    globalThis.localStorage?.setItem(STORAGE_KEY, locale);
  } catch {
    // A kiosk profile with storage disabled loses the preference between
    // sessions. That is a worse experience, not a broken application.
  }
}

/**
 * Runtime locale switching over compile-time-complete catalogues.
 *
 * Angular's built-in `$localize` was the obvious choice and is not the right
 * one here. It compiles one bundle per locale, so switching locale means
 * navigating to a different deployment — and ADR 0035 requires the locale to be
 * runtime-switchable, because a shared terminal changes hands between operators
 * who read different languages, mid-shift, without a page load they will wait
 * for.
 *
 * What `$localize` gives up in exchange is the build-time guarantee, and this
 * module gets it back from the type system instead: every catalogue is typed
 * `Record<MessageKey, string>` against the English one, so a missing translation
 * is a `tsc` error and cannot reach a screen. See `messages.en.ts`.
 *
 * **Loading model (ADR 0035, dated status addition).** `ru` is the default and
 * ships in the initial bundle; `en` and `uz-Latn` load through their own
 * dynamic-import chunk. `t()` stays synchronous — it always has *some*
 * catalogue to read, because {@link setLocale} never discards the catalogue
 * currently backing it. Concretely:
 *
 *  - {@link setLocale} is a *request*, not a guarantee. If the target
 *    catalogue is already cached, the switch is synchronous and indistinguishable
 *    from the old eager-everything behaviour. If it is not, the request kicks
 *    off the `import()` in the background and — this is the part that keeps
 *    `t()` honest — `locale()` and every rendered string keep showing
 *    whatever was active before the request, unchanged, until the import
 *    resolves. There is no half-switched state where the selected locale and
 *    the rendered text disagree.
 *  - When the import resolves, `locale` and the active catalogue swap in the
 *    same synchronous callback — one signal write each, back to back, so a
 *    template never renders the new locale with the old text or vice versa.
 *  - A later `setLocale` call supersedes an in-flight one: if the operator
 *    requests `en` and then `uz-Latn` before `en` has finished loading, the
 *    `en` response is discarded when it arrives.
 *  - **The one case with no "previous catalogue" to fall back to** is the very
 *    first read, when the persisted locale is not `ru` and nothing has warmed
 *    it yet. `t()` then returns the raw key — never blank, never English —
 *    until the load resolves, which is normally not observable: `main.ts`
 *    awaits exactly this load, for exactly this locale, before
 *    `bootstrapApplication` — see that file. The fallback exists for the path
 *    that skips the bootstrap (a `TestBed`-constructed `I18n` with a
 *    locale nothing has preloaded), not as the expected production behaviour.
 */
@Injectable({ providedIn: 'root' })
export class I18n {
  private readonly current = signal<Locale>(peekStoredLocale());

  readonly locale: Signal<Locale> = this.current.asReadonly();

  /**
   * The catalogue actually backing {@link t} right now. Always defined once
   * warm; `undefined` only in the cold-start gap described in this class's
   * doc comment.
   */
  private readonly activeCatalogue = signal<MessageCatalogue | undefined>(
    catalogueCache().loaded.get(this.current()),
  );

  /**
   * The last locale actually requested, so a stale `applyOnceLoaded`
   * resolution (superseded by a newer request) knows not to apply.
   */
  private lastRequested: Locale = this.current();

  constructor() {
    const cached = catalogueCache().loaded.get(this.current());
    if (cached) {
      document.documentElement.lang = this.current();
    } else {
      // Cold start on a locale nobody has warmed — see the class doc comment.
      void this.applyOnceLoaded(this.current());
    }
  }

  /**
   * Requests a locale. See the class doc comment for exactly what "requests"
   * means when the catalogue is not already cached.
   */
  setLocale(locale: Locale): void {
    this.lastRequested = locale;
    // Persisted immediately, not after the catalogue resolves: a reload
    // before the import ever finishes should still request this locale, not
    // fall back to whatever loaded last time — see `main.ts`.
    persistLocale(locale);
    const cached = catalogueCache().loaded.get(locale);
    if (cached) {
      this.applyLoaded(locale, cached);
      return;
    }
    void this.applyOnceLoaded(locale);
  }

  private async applyOnceLoaded(locale: Locale): Promise<void> {
    try {
      const catalogue = await ensureLoaded(locale);
      if (this.lastRequested === locale) {
        this.applyLoaded(locale, catalogue);
      }
    } catch (err) {
      // The import failed (bad deploy, flaky network). Both call sites
      // (`void this.applyOnceLoaded(...)`) discard this promise, so without
      // catching here the rejection would be unhandled. Leave whatever
      // catalogue is already active in place — see the class doc comment —
      // and report the failure instead of failing silently.
      console.error(err);
    }
  }

  private applyLoaded(locale: Locale, catalogue: MessageCatalogue): void {
    this.current.set(locale);
    this.activeCatalogue.set(catalogue);
    document.documentElement.lang = locale;
  }

  /**
   * Looks up a message, interpolating `{placeholder}` values.
   *
   * Synchronous, always — see the class doc comment for the loading model
   * that makes that true. There is no "missing key" branch for a *loaded*
   * catalogue, because {@link MessageKey} makes a missing key impossible to
   * write; the only fallback is the raw key, for the one cold-start gap
   * documented above.
   */
  t(key: MessageKey, values?: Readonly<Record<string, string | number>>): string {
    const catalogue = this.activeCatalogue();
    return catalogue ? interpolate(catalogue[key], values) : key;
  }
}

/**
 * Replaces `{name}` with `values.name`.
 *
 * An unmatched placeholder is left as-is rather than blanked. `опаздывают: {count}`
 * on screen is an obvious bug that gets reported; `опаздывают: ` is a plausible
 * sentence that does not.
 */
export function interpolate(
  template: string,
  values?: Readonly<Record<string, string | number>>,
): string {
  if (!values) {
    return template;
  }
  return template.replace(/\{(\w+)\}/g, (whole, name: string) =>
    Object.hasOwn(values, name) ? String(values[name]) : whole,
  );
}

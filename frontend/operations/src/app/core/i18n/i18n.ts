import { Injectable, Signal, isDevMode, signal } from '@angular/core';

import { CORE_AREA, MESSAGE_AREAS, type MessageArea, areaOfKey } from './message-areas';
import type { MessageCatalogue, MessageKey } from './messages.en';
import { coreRu } from './messages/core.ru';

/**
 * The languages this *build* has a message catalogue for (ADR 0035, ADR 0149).
 *
 * This is the one list a console keeps, on purpose: the catalogues are compile-time and typed, so a
 * missing translation fails the build, and a language the build does not hold cannot be rendered
 * whatever the platform says. It is not the platform's list of languages -- that is the registry
 * (`platform-locales.ts`), which narrows what is offered to the languages live in the staff-UI tier.
 * Adding a language is its catalogues here plus its registry entry going live; nothing else.
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

/** One area's messages in one locale, as its module exports them. */
type Messages = Readonly<Record<string, string>>;

type Loader = () => Promise<Messages>;

/**
 * Dynamic-import loaders: one chunk per area and locale (`messages/<area>.<locale>.ts`).
 *
 * The one entry missing is the one that is not lazy: `ru`'s `core` is imported statically above, so the
 * default locale's first paint needs no network round trip and no await. Every other chunk is fetched
 * the first time something asks for it -- `main.ts` for the persisted locale's `core`, a route's
 * `messagesGuard` for the area it is about to show, a locale switch for the areas already in use.
 */
const LOADERS: Record<Locale, Partial<Record<MessageArea, Loader>>> = {
  ru: {
    auth: () => import('./messages/auth.ru').then((m) => m.authRu),
    today: () => import('./messages/today.ru').then((m) => m.todayRu),
    orders: () => import('./messages/orders.ru').then((m) => m.ordersRu),
    customers: () => import('./messages/customers.ru').then((m) => m.customersRu),
    inbox: () => import('./messages/inbox.ru').then((m) => m.inboxRu),
    settings: () => import('./messages/settings.ru').then((m) => m.settingsRu),
    staff: () => import('./messages/staff.ru').then((m) => m.staffRu),
    finance: () => import('./messages/finance.ru').then((m) => m.financeRu),
    marketing: () => import('./messages/marketing.ru').then((m) => m.marketingRu),
    catalog: () => import('./messages/catalog.ru').then((m) => m.catalogRu),
    reports: () => import('./messages/reports.ru').then((m) => m.reportsRu),
    kitchen: () => import('./messages/kitchen.ru').then((m) => m.kitchenRu),
    delivery: () => import('./messages/delivery.ru').then((m) => m.deliveryRu),
    couriers: () => import('./messages/couriers.ru').then((m) => m.couriersRu),
    device: () => import('./messages/device.ru').then((m) => m.deviceRu),
    wallboard: () => import('./messages/wallboard.ru').then((m) => m.wallboardRu),
    map: () => import('./messages/map.ru').then((m) => m.mapRu),
  },
  'uz-Latn': {
    core: () => import('./messages/core.uz-latn').then((m) => m.coreUzLatn),
    auth: () => import('./messages/auth.uz-latn').then((m) => m.authUzLatn),
    today: () => import('./messages/today.uz-latn').then((m) => m.todayUzLatn),
    orders: () => import('./messages/orders.uz-latn').then((m) => m.ordersUzLatn),
    customers: () => import('./messages/customers.uz-latn').then((m) => m.customersUzLatn),
    inbox: () => import('./messages/inbox.uz-latn').then((m) => m.inboxUzLatn),
    settings: () => import('./messages/settings.uz-latn').then((m) => m.settingsUzLatn),
    staff: () => import('./messages/staff.uz-latn').then((m) => m.staffUzLatn),
    finance: () => import('./messages/finance.uz-latn').then((m) => m.financeUzLatn),
    marketing: () => import('./messages/marketing.uz-latn').then((m) => m.marketingUzLatn),
    catalog: () => import('./messages/catalog.uz-latn').then((m) => m.catalogUzLatn),
    reports: () => import('./messages/reports.uz-latn').then((m) => m.reportsUzLatn),
    kitchen: () => import('./messages/kitchen.uz-latn').then((m) => m.kitchenUzLatn),
    delivery: () => import('./messages/delivery.uz-latn').then((m) => m.deliveryUzLatn),
    couriers: () => import('./messages/couriers.uz-latn').then((m) => m.couriersUzLatn),
    device: () => import('./messages/device.uz-latn').then((m) => m.deviceUzLatn),
    wallboard: () => import('./messages/wallboard.uz-latn').then((m) => m.wallboardUzLatn),
    map: () => import('./messages/map.uz-latn').then((m) => m.mapUzLatn),
  },
  en: {
    core: () => import('./messages/core.en').then((m) => m.coreEn),
    auth: () => import('./messages/auth.en').then((m) => m.authEn),
    today: () => import('./messages/today.en').then((m) => m.todayEn),
    orders: () => import('./messages/orders.en').then((m) => m.ordersEn),
    customers: () => import('./messages/customers.en').then((m) => m.customersEn),
    inbox: () => import('./messages/inbox.en').then((m) => m.inboxEn),
    settings: () => import('./messages/settings.en').then((m) => m.settingsEn),
    staff: () => import('./messages/staff.en').then((m) => m.staffEn),
    finance: () => import('./messages/finance.en').then((m) => m.financeEn),
    marketing: () => import('./messages/marketing.en').then((m) => m.marketingEn),
    catalog: () => import('./messages/catalog.en').then((m) => m.catalogEn),
    reports: () => import('./messages/reports.en').then((m) => m.reportsEn),
    kitchen: () => import('./messages/kitchen.en').then((m) => m.kitchenEn),
    delivery: () => import('./messages/delivery.en').then((m) => m.deliveryEn),
    couriers: () => import('./messages/couriers.en').then((m) => m.couriersEn),
    device: () => import('./messages/device.en').then((m) => m.deviceEn),
    wallboard: () => import('./messages/wallboard.en').then((m) => m.wallboardEn),
    map: () => import('./messages/map.en').then((m) => m.mapEn),
  },
};

/**
 * Test-only: overrides one loader, e.g. to simulate a failed dynamic import without touching the
 * network. `area` defaults to `core`, the one every locale switch needs. Returns a function that
 * restores the real loader; every caller must call it before the test ends, since `LOADERS` -- like
 * the cache in {@link catalogueCache} -- is shared with every other spec bundled into the same entry
 * point (see that function's doc comment).
 */
export function setLoaderForTesting(
  locale: Locale,
  loader: Loader,
  area: MessageArea = CORE_AREA,
): () => void {
  const loaders = LOADERS[locale];
  const original = loaders[area];
  loaders[area] = loader;
  return () => {
    if (original) {
      loaders[area] = original;
    } else {
      delete loaders[area];
    }
  };
}

interface CatalogueCache {
  /** The areas in memory, per locale. A locale with no `core` entry is not usable yet. */
  readonly loaded: Map<Locale, Map<MessageArea, Messages>>;
  /** Imports in flight, keyed `<locale>/<area>`, so two askers share one request. */
  readonly pending: Map<string, Promise<Messages>>;
}

const CACHE_SLOT = Symbol.for('horecaos.operations.i18n.catalogue-cache');

function freshCache(): CatalogueCache {
  // `coreRu` is the module statically imported above; it is the default locale's `core` only while
  // DEFAULT_LOCALE is 'ru', which is why the key below is not written as a literal.
  return {
    loaded: new Map([[DEFAULT_LOCALE, new Map<MessageArea, Messages>([[CORE_AREA, coreRu]])]]),
    pending: new Map(),
  };
}

/**
 * The load cache, shared by every `I18n` instance -- there is normally one,
 * `providedIn: 'root'` -- and by {@link preloadLocale}'s callers.
 *
 * This reads from a `globalThis` slot rather than a plain module-level
 * variable for a reason specific to this app's unit-test builder: its
 * generated Vitest config sets `disableCodeSplitting: true` because every
 * spec file is its own esbuild entry point (see that option's own comment
 * in the Angular CLI source -- bundling all specs from shared chunks makes
 * ESM live bindings across a spec/setup-file boundary read `undefined`
 * before the chunk's lazy initializer has run). One consequence: this
 * module is bundled once *per entry point*, so `src/testing/i18n-preload.setup.ts`
 * and, say, `order-queue.spec.ts` each get their own independent copy of
 * this file, each with its own module-scoped variables that share nothing.
 * A `Symbol.for` registry key is the one thing that *is* shared across those
 * independent bundles, as long as they run in the same JS realm -- which
 * they do: Vitest's `isolate: false` (this project's default, deliberately,
 * per that option's "Karma/Jasmine experience" framing) runs every spec
 * file in one shared environment rather than a fresh one per file. Without
 * this indirection, the preload setup file would warm a cache nothing else
 * can ever see, and every spec calling `setLocale('en' | 'uz-Latn')` would
 * hit the not-yet-loaded path regardless.
 *
 * What is shared is plain data only (maps of message objects and promises).
 * A signal is not: each bundle copy has its own signal graph, so every `I18n`
 * keeps its signals to itself and reads this cache when it needs to.
 *
 * None of this matters for the one production bundle, where this module
 * exists exactly once -- the `globalThis` slot is just an unused extra
 * indirection there, not a behaviour change.
 */
function catalogueCache(): CatalogueCache {
  const globalObject = globalThis as typeof globalThis & { [CACHE_SLOT]?: CatalogueCache };
  return (globalObject[CACHE_SLOT] ??= freshCache());
}

function loadedAreas(locale: Locale): Map<MessageArea, Messages> {
  const cache = catalogueCache();
  let areas = cache.loaded.get(locale);
  if (!areas) {
    areas = new Map();
    cache.loaded.set(locale, areas);
  }
  return areas;
}

function ensureArea(locale: Locale, area: MessageArea): Promise<Messages> {
  const cached = loadedAreas(locale).get(area);
  if (cached) {
    return Promise.resolve(cached);
  }
  const cache = catalogueCache();
  const id = `${locale}/${area}`;
  let inFlight = cache.pending.get(id);
  if (!inFlight) {
    const load = LOADERS[locale][area];
    if (!load) {
      return Promise.reject(new Error(`no message loader for ${id}`));
    }
    inFlight = load().then(
      (messages) => {
        loadedAreas(locale).set(area, messages);
        cache.pending.delete(id);
        return messages;
      },
      (err: unknown) => {
        // Don't leave a rejected promise cached forever: the next request
        // for this area -- a retry, the next route that needs it, the operator
        // clicking the language switcher again -- must hit the network again,
        // not the same dead promise. Without this, one transient failure
        // permanently disables that area for the rest of the tab's life.
        cache.pending.delete(id);
        throw err;
      },
    );
    cache.pending.set(id, inFlight);
  }
  return inFlight;
}

/**
 * Starts every one of `areas` (and `core`) and resolves once *each* has settled, with the reasons of the
 * ones that could not be fetched. A bare `Promise.all` would report the first failure at once and leave
 * the others still downloading, out of sight of whoever is waiting: they would land in the shared cache
 * after the caller had moved on, and nothing would ever merge them into what the screen reads.
 */
async function settleAreas(locale: Locale, areas: Iterable<MessageArea>): Promise<unknown[]> {
  const results = await Promise.allSettled(
    [...new Set<MessageArea>([CORE_AREA, ...areas])].map((area) => ensureArea(locale, area)),
  );
  return results.flatMap((result) => (result.status === 'rejected' ? [result.reason] : []));
}

/** Loads `areas`; rejects with the first failure, but only after every area has settled. */
async function ensureAreas(locale: Locale, areas: Iterable<MessageArea>): Promise<void> {
  const failures = await settleAreas(locale, areas);
  if (failures.length > 0) {
    throw failures[0];
  }
}

/** Every area of `locale` that is in memory, as one lookup table; `undefined` until its `core` is. */
function assemble(locale: Locale): Partial<MessageCatalogue> | undefined {
  const areas = catalogueCache().loaded.get(locale);
  if (!areas?.has(CORE_AREA)) {
    return undefined;
  }
  return Object.assign({}, ...areas.values()) as Partial<MessageCatalogue>;
}

/**
 * Warms a locale's messages without switching to it, so a later
 * {@link I18n.setLocale} for that locale resolves synchronously instead of
 * leaving the previous language on screen for a frame. With no `areas` that is the
 * locale's `core` -- what every screen needs; pass the areas a screen needs
 * to have those too.
 *
 * Two callers use this:
 *
 *  - `main.ts`'s bootstrap, for the locale a returning operator last chose --
 *    persisted in `localStorage` under {@link STORAGE_KEY} -- so first paint
 *    already renders in that language instead of flashing `ru` and then
 *    swapping. See `peekStoredLocale`.
 *  - The unit-test suite's global setup (`src/testing/i18n-preload.setup.ts`),
 *    which warms every area of every locale once before any spec runs. Every
 *    existing spec calls `setLocale('en' | 'ru' | 'uz-Latn')` and asserts in
 *    the same synchronous tick; that only keeps working because the messages
 *    it asks for are already cached by the time the spec runs -- `setLocale`
 *    itself makes no promise about *when* an uncached locale becomes visible,
 *    only that it will, atomically, once loaded.
 */
export function preloadLocale(
  locale: Locale,
  areas: Iterable<MessageArea> = [CORE_AREA],
): Promise<void> {
  return ensureAreas(locale, areas);
}

/** Warms every area of `locale`: what the unit tests, which render any screen in any order, need. */
export function preloadAllAreas(locale: Locale): Promise<void> {
  return preloadLocale(locale, MESSAGE_AREAS);
}

/**
 * Test-only: drops the shared cache so the next load behaves like nothing has
 * ever been requested, including every area but the statically imported `ru`
 * `core`. `i18n.spec.ts` uses this to exercise the not-yet-loaded paths in
 * {@link I18n}'s doc comment -- the previous-language-meanwhile behaviour, the
 * superseded-request discard, and the cold-start raw-key fallback -- which the
 * suite-wide preload in `src/testing/i18n-preload.setup.ts` otherwise makes
 * unreachable. Every test that calls this restores the cache before it ends
 * (see that spec file), because the cache is process-global -- see
 * {@link catalogueCache}'s doc comment for why.
 */
export function resetCatalogueCacheForTesting(): void {
  const globalObject = globalThis as typeof globalThis & { [CACHE_SLOT]?: CatalogueCache };
  delete globalObject[CACHE_SLOT];
}

/**
 * Test-only: the areas of `locale` that are in memory right now, by area. `message-areas.spec.ts` reads
 * them to prove every loader returns the right module, and the lazy-loading specs read them to prove
 * what was and was not fetched.
 */
export function areasLoadedForTesting(locale: Locale): ReadonlyMap<MessageArea, Messages> {
  return new Map(catalogueCache().loaded.get(locale) ?? []);
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
 * navigating to a different deployment -- and ADR 0035 requires the locale to be
 * runtime-switchable, because a shared terminal changes hands between operators
 * who read different languages, mid-shift, without a page load they will wait
 * for.
 *
 * What `$localize` gives up in exchange is the build-time guarantee, and this
 * module gets it back from the type system instead: every area's catalogue is typed
 * against its English one, so a missing translation is a `tsc` error and cannot
 * reach a screen. See `messages.en.ts`.
 *
 * **Loading model (ADR 0035, dated status addition).** The messages are split by feature area
 * (`message-areas.ts`) and each area of each locale is its own chunk. The default locale's `core`
 * area -- what the shell, the shared widgets and the error messages need -- is in the initial bundle;
 * everything else is fetched when something asks: `main.ts` for the persisted locale's `core`, a
 * route's {@link messagesGuard} for the areas it is about to show, {@link setLocale} for the areas
 * already in use. `t()` stays synchronous -- it always has *some* messages to read, because nothing
 * ever discards what currently backs it. Concretely:
 *
 *  - {@link require} is how a screen asks for its areas. The promise resolves once they are in memory
 *    for the active locale, and the screen renders with every string present. It never rejects: a chunk
 *    that cannot be fetched is logged and the screen opens on what it has -- every area that did arrive,
 *    raw keys for the one that did not -- because a broken chunk must not make a route unreachable.
 *  - {@link setLocale} is a *request*, not a guarantee. If everything the session has used so far is
 *    already cached for the target locale, the switch is synchronous. If it is not, the request kicks
 *    off the imports in the background and -- this is the part that keeps `t()` honest -- `locale()`
 *    and every rendered string keep showing whatever was active before the request, unchanged, until
 *    the imports resolve. There is no half-switched state where the selected locale and the rendered
 *    text disagree.
 *  - When the imports resolve, `locale` and the active messages swap in the same synchronous callback
 *    -- one signal write each, back to back, so a template never renders the new locale with the old
 *    text or vice versa.
 *  - A later `setLocale` call supersedes an in-flight one: if the operator requests `en` and then
 *    `uz-Latn` before `en` has finished loading, the `en` response is discarded when it arrives.
 *  - **A key whose area nobody asked for** -- a route that forgot to declare it, a string reached some
 *    way the build-time check (`tools/i18n/areas.mjs`) cannot see -- renders as the raw key and starts
 *    loading its area; when it arrives the text appears. In development a warning names the area.
 *    This is a safety net, not a loading strategy: the check fails the build for a route that needs an
 *    area it does not declare.
 *  - **The one case with no "previous messages" to fall back to** is the very first read, when the
 *    persisted locale is not `ru` and nothing has warmed it yet. `t()` then returns the raw key -- never
 *    blank, never English -- until the load resolves, which is normally not observable: `main.ts`
 *    awaits exactly this load, for exactly this locale, before `bootstrapApplication` -- see that file.
 *    The fallback exists for the path that skips the bootstrap (a `TestBed`-constructed `I18n` with a
 *    locale nothing has preloaded), not as the expected production behaviour.
 */
@Injectable({ providedIn: 'root' })
export class I18n {
  private readonly current = signal<Locale>(peekStoredLocale());

  readonly locale: Signal<Locale> = this.current.asReadonly();

  /**
   * The messages actually backing {@link t} right now: every area of the active locale that is in
   * memory. Always defined once warm; `undefined` only in the cold-start gap described in this
   * class's doc comment.
   */
  private readonly messages = signal<Partial<MessageCatalogue> | undefined>(
    assemble(this.current()),
  );

  /** How many areas {@link messages} was assembled from, to tell when the cache has moved on without it. */
  private mergedAreas = loadedAreas(this.current()).size;

  /**
   * The areas this session has asked for, `core` always among them. A locale switch loads all of
   * them in the target locale before it swaps, so the screen the operator is looking at does not
   * lose strings in the middle of the change.
   */
  private readonly wanted = new Set<MessageArea>([
    CORE_AREA,
    ...(catalogueCache().loaded.get(this.current())?.keys() ?? []),
  ]);

  /**
   * The last locale actually requested, so a stale `applyOnceLoaded`
   * resolution (superseded by a newer request) knows not to apply.
   */
  private lastRequested: Locale = this.current();

  constructor() {
    if (this.messages()) {
      document.documentElement.lang = this.current();
    } else {
      // Cold start on a locale nobody has warmed -- see the class doc comment.
      void this.applyOnceLoaded(this.current());
    }
  }

  /**
   * Makes `areas` available in the active locale. Resolves when they are in memory (or, if a chunk
   * could not be fetched, once every other requested area has arrived, been merged into what {@link t}
   * reads, and the failure has been reported); never rejects.
   */
  async require(areas: Iterable<MessageArea>): Promise<void> {
    const asked = [...areas];
    for (const area of asked) {
      this.wanted.add(area);
    }
    const locale = this.current();
    const inMemory = loadedAreas(locale);
    if (inMemory.has(CORE_AREA) && asked.every((area) => inMemory.has(area))) {
      // Nothing to fetch. If something else filled the cache since the messages were last assembled
      // (a `preloadLocale` call, the unit-test setup), pick it up; otherwise leave the signal alone,
      // because a new value re-renders every string on the screen.
      if (inMemory.size !== this.mergedAreas) {
        this.refresh(locale);
      }
      return;
    }
    // Waits for every area to settle, so the ones that did arrive are merged below even when a
    // sibling chunk could not be fetched.
    const failures = await settleAreas(locale, asked);
    // Both of the callers that matter (a route guard, the on-demand path in `t`) discard the
    // failure, so report each one here; the screen opens on what was fetched.
    for (const failure of failures) {
      console.error(failure);
    }
    if (this.current() !== locale) {
      return;
    }
    // After a failure, only rebuild if something did arrive: a new value re-renders every string.
    if (failures.length === 0 || loadedAreas(locale).size !== this.mergedAreas) {
      this.refresh(locale);
    }
  }

  /**
   * Requests a locale. See the class doc comment for exactly what "requests"
   * means when the messages are not already cached.
   */
  setLocale(locale: Locale): void {
    this.lastRequested = locale;
    // Persisted immediately, not after the messages resolve: a reload
    // before the import ever finishes should still request this locale, not
    // fall back to whatever loaded last time -- see `main.ts`.
    persistLocale(locale);
    if (this.missingIn(locale).length === 0) {
      this.applyLoaded(locale);
      return;
    }
    void this.applyOnceLoaded(locale);
  }

  private missingIn(locale: Locale): MessageArea[] {
    const inMemory = loadedAreas(locale);
    return [...this.wanted].filter((area) => !inMemory.has(area));
  }

  private async applyOnceLoaded(locale: Locale): Promise<void> {
    try {
      // `wanted` can grow while the imports are in flight (a route opening during a language
      // switch), so keep going until nothing the session has asked for is missing.
      for (
        let missing = this.missingIn(locale);
        missing.length > 0;
        missing = this.missingIn(locale)
      ) {
        await ensureAreas(locale, missing);
        if (this.lastRequested !== locale) {
          return;
        }
      }
      if (this.lastRequested === locale) {
        this.applyLoaded(locale);
      }
    } catch (err) {
      // The import failed (bad deploy, flaky network). Both call sites
      // (`void this.applyOnceLoaded(...)`) discard this promise, so without
      // catching here the rejection would be unhandled. Leave whatever
      // messages are already active in place -- see the class doc comment --
      // and report the failure instead of failing silently.
      console.error(err);
    }
  }

  private applyLoaded(locale: Locale): void {
    this.current.set(locale);
    this.refresh(locale);
    document.documentElement.lang = locale;
  }

  private refresh(locale: Locale): void {
    this.mergedAreas = loadedAreas(locale).size;
    this.messages.set(assemble(locale));
  }

  /**
   * Looks up a message, interpolating `{placeholder}` values.
   *
   * Synchronous, always -- see the class doc comment for the loading model
   * that makes that true. There is no "missing key" branch for a *loaded*
   * area, because {@link MessageKey} makes a missing key impossible to
   * write; the only fallback is the raw key, for the cold-start gap and for
   * an area nobody has asked for yet, both documented above.
   */
  t(key: MessageKey, values?: Readonly<Record<string, string | number>>): string {
    const messages = this.messages();
    if (!messages) {
      return key;
    }
    const message = messages[key];
    if (message === undefined) {
      this.loadAreaOf(key);
      return key;
    }
    return interpolate(message, values);
  }

  /** The on-demand path: a key was read before its area was asked for. */
  private loadAreaOf(key: MessageKey): void {
    const area = areaOfKey(key);
    // An area already asked for is on its way (or failed, and was reported); asking again from
    // inside a template would retry a dead chunk on every change-detection pass.
    if (area === undefined || this.wanted.has(area)) {
      return;
    }
    this.wanted.add(area);
    if (isDevMode()) {
      console.warn(
        `i18n: '${key}' was read before the '${area}' messages were loaded; the route that shows it ` +
          `should declare messagesGuard('${area}') (tools/i18n/areas.mjs checks this)`,
      );
    }
    // Not synchronously: this runs inside a template's evaluation, where writing a signal is an error.
    queueMicrotask(() => void this.require([area]));
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

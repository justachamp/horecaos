import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  I18n,
  LOCALES,
  STORAGE_KEY,
  areasLoadedForTesting,
  interpolate,
  preloadAllAreas,
  preloadLocale,
  resetCatalogueCacheForTesting,
  setLoaderForTesting,
} from './i18n';
import { messagesGuard } from './messages.guard';
import { messagesEn } from './messages.en';
import { messagesRu } from './messages.ru';
import { messagesUzLatn } from './messages.uz-latn';

/**
 * Completeness is enforced by the type system, not here: `messages.ru.ts` and
 * `messages.uz-latn.ts` are typed `Record<MessageKey, string>`, so a missing key
 * fails `tsc` and therefore fails the build.
 *
 * What the type system cannot catch is a key that is *present* and useless — an
 * empty string, or an untranslated copy-paste of the English. Those are what
 * this file checks, plus the runtime switching behaviour.
 */
describe('message catalogues', () => {
  const catalogues = { en: messagesEn, ru: messagesRu, 'uz-Latn': messagesUzLatn } as const;

  it('covers every declared locale', () => {
    expect(Object.keys(catalogues).sort()).toEqual([...LOCALES].sort());
  });

  it('uses one apostrophe codepoint throughout the uz-Latn catalogue (row X.40)', () => {
    // U+02BB (MODIFIER LETTER TURNED COMMA) is the standard mark for oʻ/gʻ
    // and the tutuq belgisi alike in this catalogue's own established
    // majority style. U+2018/U+2019 (curly quotes) and U+02BC (modifier
    // letter apostrophe) crept in as the same mark typed a different way —
    // "yo‘q", "e’lon", "maʼlumot" all read the same word three ways — which
    // renders inconsistently across fonts and defeats a search for either.
    const strayApostrophes = ['‘', '’', 'ʼ'];
    const offendingKeys = Object.entries(messagesUzLatn)
      .filter(([, value]) => strayApostrophes.some((mark) => value.includes(mark)))
      .map(([key]) => key);
    expect(offendingKeys).toEqual([]);
  });

  for (const [locale, catalogue] of Object.entries(catalogues)) {
    it(`has no blank message in ${locale}`, () => {
      const blank = Object.entries(catalogue)
        .filter(([, value]) => value.trim() === '')
        .map(([key]) => key);
      expect(blank).toEqual([]);
    });

    it(`keeps every placeholder from the English source in ${locale}`, () => {
      // A translation that drops `{count}` silently renders "late" with no
      // number. The type system sees a string and is satisfied.
      const mismatched = Object.entries(messagesEn)
        .filter(([key, english]) => {
          const translated = (catalogue as Record<string, string>)[key];
          return placeholders(english) !== placeholders(translated);
        })
        .map(([key]) => key);
      expect(mismatched).toEqual([]);
    });
  }
});

describe('I18n', () => {
  let i18n: I18n;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({});
    i18n = TestBed.inject(I18n);
  });

  it('opens in Russian, because that is what the staff read', () => {
    expect(i18n.locale()).toBe('ru');
    expect(i18n.t('shell.nav.orders')).toBe('Заказы');
  });

  it('switches at runtime without a reload', () => {
    i18n.setLocale('uz-Latn');
    expect(i18n.t('shell.nav.orders')).toBe('Buyurtmalar');
    i18n.setLocale('en');
    expect(i18n.t('shell.nav.orders')).toBe('Orders');
  });

  it('sets the document language, which is what picks a screen reader’s voice', () => {
    i18n.setLocale('uz-Latn');
    expect(document.documentElement.lang).toBe('uz-Latn');
  });

  it('interpolates named placeholders', () => {
    i18n.setLocale('en');
    expect(i18n.t('shell.late', { count: 6 })).toBe('6 late');
  });
});

/**
 * ADR 0035's loading model: the default locale's `core` area ships eagerly, every other area of every
 * locale loads through a dynamic `import()` on first request -- see `i18n.ts`'s class doc comment for
 * the full model. `src/testing/i18n-preload.setup.ts` warms every area of every locale before any spec
 * runs, precisely so the suite above (and every other spec file that calls `setLocale` or renders a
 * screen) can keep treating the messages as synchronous. That preload is what makes the not-yet-loaded
 * paths unreachable from an ordinary spec, so this suite reaches them directly with
 * `resetCatalogueCacheForTesting()` -- a real dynamic import awaited for real, not a fake timer
 * standing in for one.
 *
 * Every test here restores the shared cache in `afterEach`: it is process-global (see
 * `catalogueCache`'s doc comment in `i18n.ts`), and every other spec file in this suite depends on it
 * staying warm.
 */
describe('I18n — lazy loading', () => {
  afterEach(async () => {
    await Promise.all(LOCALES.map((locale) => preloadAllAreas(locale)));
  });

  it('keeps the previous language rendered while a requested locale is still loading, then swaps atomically', async () => {
    resetCatalogueCacheForTesting();
    localStorage.clear();
    TestBed.configureTestingModule({});
    const i18n = TestBed.inject(I18n);
    expect(i18n.locale()).toBe('ru');
    expect(i18n.t('shell.nav.orders')).toBe('Заказы');

    i18n.setLocale('en');
    // The import has not resolved yet: still Russian, not a half-switched
    // state and not the raw key either.
    expect(i18n.locale()).toBe('ru');
    expect(i18n.t('shell.nav.orders')).toBe('Заказы');
    expect(document.documentElement.lang).toBe('ru');

    await preloadLocale('en');

    // Swapped atomically: locale and text agree, and so does <html lang>.
    expect(i18n.locale()).toBe('en');
    expect(i18n.t('shell.nav.orders')).toBe('Orders');
    expect(document.documentElement.lang).toBe('en');
  });

  it('discards a superseded request when it resolves after a later one', async () => {
    resetCatalogueCacheForTesting();
    localStorage.clear();
    TestBed.configureTestingModule({});
    const i18n = TestBed.inject(I18n);

    i18n.setLocale('en');
    i18n.setLocale('uz-Latn');
    await Promise.all([preloadLocale('en'), preloadLocale('uz-Latn')]);

    // Whichever import settled first, the last *request* wins.
    expect(i18n.locale()).toBe('uz-Latn');
    expect(i18n.t('shell.nav.orders')).toBe('Buyurtmalar');
  });

  it('falls back to the raw key when the persisted locale has never been warmed, until it resolves', async () => {
    resetCatalogueCacheForTesting();
    localStorage.clear();
    localStorage.setItem(STORAGE_KEY, 'en');
    TestBed.configureTestingModule({});
    const i18n = TestBed.inject(I18n);

    // Cold start on a locale nothing has warmed: no previous messages to
    // fall back to, so the raw key -- never blank, never the wrong language.
    expect(i18n.locale()).toBe('en');
    expect(i18n.t('shell.nav.orders')).toBe('shell.nav.orders');

    await preloadLocale('en');

    expect(i18n.t('shell.nav.orders')).toBe('Orders');
    expect(document.documentElement.lang).toBe('en');
  });

  it('retries a locale after a failed import instead of caching the rejection forever', async () => {
    resetCatalogueCacheForTesting();
    const restore = setLoaderForTesting('en', () => Promise.reject(new Error('network blip')));

    await expect(preloadLocale('en')).rejects.toThrow('network blip');
    restore();

    // If the rejected promise were still cached, this would reject again
    // instead of actually retrying the (now working) loader.
    await expect(preloadLocale('en')).resolves.toBeUndefined();
  });

  it('reports a failed load instead of leaving the language switcher silently inert', async () => {
    resetCatalogueCacheForTesting();
    localStorage.clear();
    TestBed.configureTestingModule({});
    const i18n = TestBed.inject(I18n);
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const restore = setLoaderForTesting('en', () => Promise.reject(new Error('network blip')));

    i18n.setLocale('en');
    await expect(preloadLocale('en')).rejects.toThrow('network blip');
    // Give applyOnceLoaded's own await-chain, off the same cached promise,
    // a microtask turn to run its catch handler.
    await Promise.resolve();

    // Stayed on the previous messages -- no crash, no half-switched state --
    // and the failure was reported instead of an unhandled rejection.
    expect(i18n.locale()).toBe('ru');
    expect(consoleError).toHaveBeenCalled();

    consoleError.mockRestore();
    restore();
  });
});

/**
 * The messages are split by feature area: the default locale's `core` is the only part of them in the
 * initial bundle, and a route fetches the rest (`messagesGuard`). These specs pin that nothing but `core`
 * is loaded until somebody asks, that asking works, and that a switch of language carries the areas the
 * session has used.
 */
describe('I18n — message areas', () => {
  /** A key of the `orders` area that is in no other. */
  const ORDERS_KEY = 'orders.title';

  beforeEach(() => {
    resetCatalogueCacheForTesting();
    localStorage.clear();
    TestBed.configureTestingModule({});
  });

  afterEach(async () => {
    await Promise.all(LOCALES.map((locale) => preloadAllAreas(locale)));
  });

  it("starts with the default locale's core area and nothing else in memory", () => {
    const i18n = TestBed.inject(I18n);

    expect([...areasLoadedForTesting('ru').keys()]).toEqual(['core']);
    expect(areasLoadedForTesting('en').size).toBe(0);
    expect(i18n.t('shell.nav.orders')).toBe('Заказы');
  });

  it('shows the raw key of an area nobody asked for, then loads the area and shows the text', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const i18n = TestBed.inject(I18n);

    expect(i18n.t(ORDERS_KEY)).toBe(ORDERS_KEY);
    await vi.waitFor(() => expect(i18n.t(ORDERS_KEY)).not.toBe(ORDERS_KEY));

    expect(i18n.t(ORDERS_KEY)).toBe('Заказы');
    // Said once, naming the area, so the route that forgot to declare it can be found.
    expect(warn).toHaveBeenCalledTimes(1);
    expect(warn.mock.calls[0][0]).toContain("messagesGuard('orders')");
    warn.mockRestore();
  });

  it('does not ask for the same missing area again on every read', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const i18n = TestBed.inject(I18n);
    const restore = setLoaderForTesting(
      'ru',
      () => Promise.reject(new Error('network blip')),
      'orders',
    );
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    for (let read = 0; read < 5; read++) {
      i18n.t(ORDERS_KEY);
    }
    await vi.waitFor(() => expect(consoleError).toHaveBeenCalled());
    for (let read = 0; read < 5; read++) {
      i18n.t(ORDERS_KEY);
    }
    await Promise.resolve();

    // One warning and one failed fetch for ten reads: a dead chunk is not hammered from a template.
    expect(warn).toHaveBeenCalledTimes(1);
    expect(consoleError).toHaveBeenCalledTimes(1);
    restore();
    consoleError.mockRestore();
    warn.mockRestore();
  });

  it('require() resolves once the areas are in memory, so the screen draws with every string', async () => {
    const i18n = TestBed.inject(I18n);

    await i18n.require(['orders', 'customers']);

    expect(i18n.t(ORDERS_KEY)).toBe('Заказы');
    expect(areasLoadedForTesting('ru').has('customers')).toBe(true);
    expect(areasLoadedForTesting('ru').has('kitchen')).toBe(false);
  });

  it('require() never rejects: a chunk that cannot be fetched is reported and the screen opens anyway', async () => {
    const i18n = TestBed.inject(I18n);
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const restore = setLoaderForTesting(
      'ru',
      () => Promise.reject(new Error('network blip')),
      'orders',
    );

    await expect(i18n.require(['orders'])).resolves.toBeUndefined();
    expect(consoleError).toHaveBeenCalledTimes(1);
    expect(i18n.t('shell.nav.orders')).toBe('Заказы');

    // The next route that needs it retries the real chunk.
    restore();
    await i18n.require(['orders']);
    expect(i18n.t(ORDERS_KEY)).toBe('Заказы');
    consoleError.mockRestore();
  });

  it('require() with one failing and one slow area still shows the area that did load, and reports each failure', async () => {
    const i18n = TestBed.inject(I18n);
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    // `customers` fails at once; `orders` is still downloading when it does -- the order a
    // deploy that dropped one chunk produces on a slow connection.
    const restoreCustomers = setLoaderForTesting(
      'ru',
      () => Promise.reject(new Error('chunk 404')),
      'customers',
    );
    const restoreOrders = setLoaderForTesting(
      'ru',
      async () => {
        await new Promise((resolve) => setTimeout(resolve, 20));
        return (await import('./messages/orders.ru')).ordersRu;
      },
      'orders',
    );

    try {
      await expect(i18n.require(['customers', 'orders'])).resolves.toBeUndefined();

      // The area that fetched fine is drawn, not left as raw keys because its sibling failed.
      expect(areasLoadedForTesting('ru').has('orders')).toBe(true);
      expect(i18n.t(ORDERS_KEY)).toBe('Заказы');
      // The area that failed is reported, and shows its raw key.
      expect(consoleError).toHaveBeenCalledTimes(1);
      expect(i18n.t('customers.nav.label')).toBe('customers.nav.label');
    } finally {
      restoreCustomers();
      restoreOrders();
      consoleError.mockRestore();
    }
  });

  it('carries the areas in use into the new language, keeps the old one until they are all there, and fetches nothing else', async () => {
    const i18n = TestBed.inject(I18n);
    await i18n.require(['orders']);

    i18n.setLocale('en');
    // The English chunks are still on their way: nothing half-switched.
    expect(i18n.locale()).toBe('ru');
    expect(i18n.t(ORDERS_KEY)).toBe('Заказы');

    await vi.waitFor(() => expect(i18n.locale()).toBe('en'));

    expect(i18n.t(ORDERS_KEY)).toBe('Orders');
    expect(i18n.t('shell.nav.orders')).toBe('Orders');
    expect([...areasLoadedForTesting('en').keys()].sort()).toEqual(['core', 'orders']);
  });

  it('includes an area asked for while a language switch is in flight', async () => {
    const i18n = TestBed.inject(I18n);

    i18n.setLocale('en');
    await i18n.require(['customers']);
    await vi.waitFor(() => expect(i18n.locale()).toBe('en'));

    expect(areasLoadedForTesting('en').has('customers')).toBe(true);
    expect(i18n.t('customers.nav.label')).toBe('Customers section');
  });

  it('opens a persisted non-default locale on its own core, with the raw key until it arrives', async () => {
    localStorage.setItem(STORAGE_KEY, 'uz-Latn');
    const i18n = TestBed.inject(I18n);

    expect(i18n.t('shell.nav.orders')).toBe('shell.nav.orders');
    await preloadLocale('uz-Latn');

    expect(i18n.t('shell.nav.orders')).toBe('Buyurtmalar');
    expect([...areasLoadedForTesting('uz-Latn').keys()]).toEqual(['core']);
  });
});

describe('messagesGuard', () => {
  beforeEach(() => {
    resetCatalogueCacheForTesting();
    localStorage.clear();
    TestBed.configureTestingModule({});
  });

  afterEach(async () => {
    await Promise.all(LOCALES.map((locale) => preloadAllAreas(locale)));
  });

  it('lets the route open only after its areas are in memory', async () => {
    const i18n = TestBed.inject(I18n);
    expect(areasLoadedForTesting('ru').has('kitchen')).toBe(false);

    const result = TestBed.runInInjectionContext(() =>
      messagesGuard('kitchen', 'orders')({} as never, {} as never),
    );

    await expect(result).resolves.toBe(true);
    expect(areasLoadedForTesting('ru').has('kitchen')).toBe(true);
    expect(areasLoadedForTesting('ru').has('orders')).toBe(true);
    expect(i18n.t('kitchen.nav.label')).not.toBe('kitchen.nav.label');
  });

  it('opens the route even when a chunk cannot be fetched', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const restore = setLoaderForTesting(
      'ru',
      () => Promise.reject(new Error('network blip')),
      'kitchen',
    );
    TestBed.inject(I18n);

    const result = TestBed.runInInjectionContext(() =>
      messagesGuard('kitchen')({} as never, {} as never),
    );

    await expect(result).resolves.toBe(true);
    expect(consoleError).toHaveBeenCalled();
    restore();
    consoleError.mockRestore();
  });
});

describe('interpolate', () => {
  it('leaves an unmatched placeholder visible rather than blanking it', () => {
    // `опаздывают: {count}` on screen gets reported. `опаздывают: ` does not.
    expect(interpolate('{count} late', {})).toBe('{count} late');
  });

  it('substitutes numbers and strings alike', () => {
    expect(interpolate('{a}/{b}', { a: 1, b: 'x' })).toBe('1/x');
  });
});

function placeholders(template: string | undefined): string {
  return [...(template ?? '').matchAll(/\{(\w+)\}/g)]
    .map((m) => m[1])
    .sort()
    .join(',');
}

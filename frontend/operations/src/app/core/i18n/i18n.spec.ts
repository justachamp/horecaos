import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import {
  I18n,
  LOCALES,
  STORAGE_KEY,
  interpolate,
  preloadLocale,
  resetCatalogueCacheForTesting,
} from './i18n';
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
 * ADR 0035's loading-model addition: `ru` ships eagerly, `en` and `uz-Latn`
 * load through a dynamic `import()` on first request — see `i18n.ts`'s class
 * doc comment for the full model. `src/testing/i18n-preload.setup.ts` warms
 * every locale before any spec runs, precisely so the suite above (and every
 * other spec file that calls `setLocale`) can keep treating it as
 * synchronous. That preload is what makes the not-yet-loaded paths
 * unreachable from an ordinary spec, so this suite reaches them directly
 * with `resetCatalogueCacheForTesting()` — a real dynamic import awaited for
 * real, not a fake timer standing in for one.
 *
 * Every test here restores the shared cache in `afterEach`: it is
 * process-global (see `catalogueCache`'s doc comment in `i18n.ts`), and
 * every other spec file in this suite depends on it staying warm.
 */
describe('I18n — lazy loading', () => {
  afterEach(async () => {
    await Promise.all(LOCALES.map((locale) => preloadLocale(locale)));
  });

  it('keeps the previous catalogue rendered while a requested locale is still loading, then swaps atomically', async () => {
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

    // Cold start on a locale nothing has warmed: no previous catalogue to
    // fall back to, so the raw key — never blank, never the wrong language.
    expect(i18n.locale()).toBe('en');
    expect(i18n.t('shell.nav.orders')).toBe('shell.nav.orders');

    await preloadLocale('en');

    expect(i18n.t('shell.nav.orders')).toBe('Orders');
    expect(document.documentElement.lang).toBe('en');
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

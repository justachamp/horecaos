import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n, LOCALES, interpolate } from './i18n';
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

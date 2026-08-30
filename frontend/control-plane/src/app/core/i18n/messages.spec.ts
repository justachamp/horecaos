import { describe, expect, it } from 'vitest';

import { LOCALES } from './i18n.service';
import { en } from './messages.en';
import { ru } from './messages.ru';
import { uzLatn } from './messages.uz-latn';

/**
 * Completeness is enforced by the compiler: ru and uz-Latn are typed as
 * `Messages`, so a key added to `en` and not translated fails `ng build`.
 *
 * These tests cover what a type cannot: a key that is present but empty, and a
 * catalogue that was satisfied by pasting the English text.
 */
const CATALOGUES = { en, ru, 'uz-Latn': uzLatn };

describe('message catalogues', () => {
  it('covers exactly the declared locales', () => {
    expect(Object.keys(CATALOGUES).sort()).toEqual([...LOCALES].sort());
  });

  for (const [locale, catalogue] of Object.entries(CATALOGUES)) {
    it(`${locale} has no blank message`, () => {
      const blank = Object.entries(catalogue)
        .filter(([, value]) => value.trim() === '')
        .map(([key]) => key);
      expect(blank).toEqual([]);
    });

    it(`${locale} has the same keys as the canonical catalogue`, () => {
      // Redundant with the type, and here because a reader should be able to
      // see the guarantee without reading messages.en.ts.
      expect(Object.keys(catalogue).sort()).toEqual(Object.keys(en).sort());
    });
  }

  it('does not leave navigation untranslated', () => {
    // The cheapest way to satisfy the type is to paste English. These eight
    // labels are the ones a user sees first, so they are the ones checked.
    const navigationKeys = Object.keys(en).filter((key) => key.startsWith('nav.'));
    expect(navigationKeys.length).toBeGreaterThan(0);

    for (const key of navigationKeys) {
      const typed = key as keyof typeof en;
      expect(ru[typed], `ru ${key}`).not.toBe(en[typed]);
      expect(uzLatn[typed], `uz-Latn ${key}`).not.toBe(en[typed]);
    }
  });

  it('keeps the som suffix in the script the reader uses', () => {
    expect(en['money.uzsSuffix']).toBe("so'm");
    expect(ru['money.uzsSuffix']).toBe('сўм');
    expect(uzLatn['money.uzsSuffix']).toBe('so‘m');
  });
});

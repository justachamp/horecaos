import { describe, expect, it } from 'vitest';

import { LOCALES } from '../../../core/i18n/i18n';
import { APPROVAL_FIELDS } from './approvals-settings-page';
import { APPROVAL_TEXTS, approvalText } from './approvals-texts';

const en = APPROVAL_TEXTS['en'];

function placeholders(text: string): string[] {
  return [...text.matchAll(/\{(\w+)\}/g)].map((match) => match[1]).sort();
}

describe('approval texts', () => {
  it('holds the same keys in every locale', () => {
    const keys = Object.keys(en).sort();
    for (const locale of LOCALES) {
      expect(Object.keys(APPROVAL_TEXTS[locale]).sort(), locale).toEqual(keys);
    }
  });

  for (const locale of LOCALES) {
    it(`has no blank string in ${locale}`, () => {
      const blank = Object.entries(APPROVAL_TEXTS[locale])
        .filter(([, value]) => value.trim() === '')
        .map(([key]) => key);
      expect(blank).toEqual([]);
    });

    it(`keeps every placeholder of the English source in ${locale}`, () => {
      const mismatched = Object.entries(APPROVAL_TEXTS[locale])
        .filter(
          ([key, value]) =>
            placeholders(value).join() !== placeholders(en[key as keyof typeof en]).join(),
        )
        .map(([key]) => key);
      expect(mismatched).toEqual([]);
    });
  }

  it('is actually translated: no Russian or Uzbek string is the English one', () => {
    const untranslated = (['ru', 'uz-Latn'] as const).flatMap((locale) =>
      Object.entries(APPROVAL_TEXTS[locale])
        .filter(([key, value]) => value === en[key as keyof typeof en] && value.length > 4)
        .map(([key]) => `${locale}:${key}`),
    );
    expect(untranslated).toEqual([]);
  });

  it('uses one apostrophe codepoint in uz-Latn, the one the central catalogue uses', () => {
    const stray = ['‘', '’', 'ʼ'];
    const offending = Object.entries(APPROVAL_TEXTS['uz-Latn'])
      .filter(([, value]) => stray.some((mark) => value.includes(mark)))
      .map(([key]) => key);
    expect(offending).toEqual([]);
  });

  it('has a label and a hint for every field the screen renders', () => {
    for (const field of APPROVAL_FIELDS) {
      expect(Object.hasOwn(en, field.labelKey), field.code).toBe(true);
      expect(Object.hasOwn(en, field.hintKey), field.code).toBe(true);
    }
  });

  it('answers in the language asked for', () => {
    expect(approvalText('en', 'title')).toBe('Approvals');
    expect(approvalText('ru', 'title')).toBe('Согласования');
    expect(approvalText('uz-Latn', 'title')).toBe('Tasdiqlashlar');
  });
});

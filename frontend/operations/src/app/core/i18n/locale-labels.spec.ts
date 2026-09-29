import { describe, expect, it } from 'vitest';

import { labelDrafts, labelsToSend, pickLabel, platformColumns } from './locale-labels';

describe('labelDrafts', () => {
  it('makes one field per offered locale, prefilled from the wording the row has and blank otherwise', () => {
    expect(labelDrafts(['uz-Latn', 'en'], { ru: 'Без лука', 'uz-Latn': 'Piyozsiz' })).toEqual({
      'uz-Latn': 'Piyozsiz',
      en: '',
    });
  });

  it('copes with a row that has no wording yet', () => {
    expect(labelDrafts(['ru'], undefined)).toEqual({ ru: '' });
  });
});

describe('labelsToSend', () => {
  it('sends only the offered locales the operator filled in, trimmed', () => {
    expect(labelsToSend(['ru', 'en'], { ru: '  Без лука ', en: '' })).toEqual({ ru: 'Без лука' });
  });

  it('never sends a locale that is not offered, even when its draft holds wording', () => {
    // A draft keyed by a hidden locale would delete nothing here, but sending it
    // would rewrite wording the operator was never shown.
    expect(
      labelsToSend(['ru'], { ru: 'Без лука', kaa: 'Piyazsiz', 'uz-Latn': 'Piyozsiz' }),
    ).toEqual({
      ru: 'Без лука',
    });
  });

  it('leaves a blank field out instead of sending an empty string', () => {
    expect(
      Object.values(labelsToSend(['ru', 'uz-Latn', 'en'], { ru: 'x', 'uz-Latn': '  ' })),
    ).toEqual(['x']);
  });
});

describe('platformColumns', () => {
  const stored = { ru: 'Без лука', 'uz-Latn': 'Piyozsiz', en: 'No onion' };

  it('uses the wording of an offered platform language the operator filled in', () => {
    expect(
      platformColumns(['ru', 'uz-Latn', 'en'], { ru: 'А', 'uz-Latn': 'B', en: 'C' }, 'ru'),
    ).toEqual({ ru: 'А', 'uz-Latn': 'B', en: 'C' });
  });

  it('round-trips a platform language the editor does not offer exactly as the row has it', () => {
    expect(platformColumns(['ru', 'en'], { ru: 'Новое', en: '' }, 'ru', stored)).toEqual({
      ru: 'Новое',
      'uz-Latn': 'Piyozsiz',
      // Cleared but offered: "leave it", so the stored wording, not a blank.
      en: 'No onion',
    });
  });

  it('words a platform language nobody offered with the default language on a create', () => {
    expect(
      platformColumns(['uz-Latn', 'en'], { 'uz-Latn': 'Piyozsiz', en: 'No onion' }, 'uz-Latn'),
    ).toEqual({
      ru: 'Piyozsiz',
      'uz-Latn': 'Piyozsiz',
      en: 'No onion',
    });
  });

  it('ignores a draft keyed by a language that is not offered', () => {
    expect(
      platformColumns(['ru'], { ru: 'А', 'uz-Latn': 'HIDDEN DRAFT' }, 'ru', stored)['uz-Latn'],
    ).toBe('Piyozsiz');
  });
});

describe('pickLabel', () => {
  it('prefers the operator’s language, then the fallbacks, then anything at all', () => {
    expect(pickLabel({ en: 'Centre', kaa: 'Orayı' }, 'ru', ['ru', 'uz-Latn', 'en'])).toBe('Centre');
    expect(pickLabel({ kaa: 'Orayı' }, 'ru', ['ru', 'uz-Latn', 'en'])).toBe('Orayı');
    expect(pickLabel({ ru: '  ', en: 'Centre' }, 'ru', ['ru', 'en'])).toBe('Centre');
    expect(pickLabel({}, 'ru', ['ru'])).toBe('');
  });
});

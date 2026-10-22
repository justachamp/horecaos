import { describe, expect, it } from 'vitest';

import {
  changedLabels,
  labelDrafts,
  labelsToSend,
  pickLabel,
  presetLabelFor,
} from './locale-labels';

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

describe('changedLabels', () => {
  const loaded = { ru: 'Центр', 'uz-Latn': 'Markaz', en: 'Centre' };

  it('sends only the offered locales whose text differs from what the editor loaded', () => {
    expect(
      changedLabels(
        ['ru', 'uz-Latn', 'en'],
        { ru: 'Центр города', 'uz-Latn': 'Markaz', en: 'Centre' },
        loaded,
      ),
    ).toEqual({ ru: 'Центр города' });
  });

  it('ignores surrounding whitespace on either side of the comparison', () => {
    expect(
      changedLabels(
        ['ru', 'en'],
        { ru: '  Центр ', en: 'City centre' },
        { ru: 'Центр  ', en: 'Centre' },
      ),
    ).toEqual({ en: 'City centre' });
  });

  it('treats a locale the row did not have as changed once filled in, and a blank as not sent', () => {
    expect(
      changedLabels(['ru', 'kaa', 'en'], { ru: 'Центр', kaa: 'Orayı', en: '' }, loaded),
    ).toEqual({ kaa: 'Orayı' });
    expect(changedLabels(['ru'], { ru: 'Центр' }, undefined)).toEqual({ ru: 'Центр' });
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

describe('presetLabelFor', () => {
  const labels = { ru: 'Без лука', 'uz-Latn': 'Piyozsiz', en: 'No onions' };

  it('answers the wording of the console language from the labels map', () => {
    expect(presetLabelFor({ labels }, 'ru')).toBe('Без лука');
    expect(presetLabelFor({ labels }, 'uz-Latn')).toBe('Piyozsiz');
    expect(presetLabelFor({ labels }, 'en')).toBe('No onions');
  });

  it('shows a wording beyond the platform triple like any other', () => {
    expect(presetLabelFor({ labels: { ...labels, kaa: 'Piyazsiz' } }, 'kaa')).toBe('Piyazsiz');
  });

  it('prefers the wording the platform resolved for the request over a language the map lacks', () => {
    expect(presetLabelFor({ labels: { 'uz-Latn': 'Piyozsiz' }, label: 'Piyazsiz' }, 'en')).toBe(
      'Piyazsiz',
    );
  });

  it('falls back to the first wording the map holds, the platform triple first, rather than a blank', () => {
    expect(
      presetLabelFor({ labels: { ru: '', 'uz-Latn': 'Piyozsiz', en: 'No onions' } }, 'kaa'),
    ).toBe('Piyozsiz');
    expect(presetLabelFor({ labels: { ru: ' ', kaa: 'Piyazsiz' } }, 'en')).toBe('Piyazsiz');
  });

  it('answers an empty string, never undefined, for a preset with no wording at all', () => {
    expect(presetLabelFor({}, 'en')).toBe('');
    expect(presetLabelFor({ labels: {} }, 'en')).toBe('');
  });
});

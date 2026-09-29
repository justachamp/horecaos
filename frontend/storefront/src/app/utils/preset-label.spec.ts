import { presetLabelFor, type PresetWording } from './preset-label';

const TRIPLE: PresetWording = {
  labelRu: 'Без лука',
  labelUz: 'Piyozsiz',
  labelEn: 'No onions',
};

describe('presetLabelFor', () => {
  it('reads the triple’s columns for ru / uz / en when the platform sends no per-locale map', () => {
    expect(presetLabelFor(TRIPLE, 'ru')).toBe('Без лука');
    expect(presetLabelFor(TRIPLE, 'uz')).toBe('Piyozsiz');
    expect(presetLabelFor(TRIPLE, 'en')).toBe('No onions');
  });

  it('reads the wording of a locale beyond the triple from the per-locale map', () => {
    const preset: PresetWording = {
      ...TRIPLE,
      labels: {
        ru: 'Без лука',
        'uz-Latn': 'Piyozsiz',
        en: 'No onions',
        kaa: 'Piyazsiz',
      },
      label: 'Piyozsiz',
    };

    expect(presetLabelFor(preset, 'kaa')).toBe('Piyazsiz');
    // The storefront's own `uz` is the platform's `uz-Latn`.
    expect(presetLabelFor(preset, 'uz')).toBe('Piyozsiz');
  });

  it('shows the platform’s own resolution for a language the preset has no wording in', () => {
    const preset: PresetWording = {
      ...TRIPLE,
      labels: { ru: 'Без лука', 'uz-Latn': 'Piyozsiz', en: 'No onions', kaa: 'Piyazsiz' },
      label: 'Piyazsiz',
    };

    expect(presetLabelFor(preset, 'de')).toBe('Piyazsiz');
  });

  it('falls back to Uzbek, the screen’s historical default, when nothing else answers', () => {
    expect(presetLabelFor(TRIPLE, 'de')).toBe('Piyozsiz');
  });
});

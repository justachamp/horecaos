import { presetLabelFor, type PresetWording } from './preset-label';

const TRIPLE: PresetWording = {
  labels: { ru: 'Без лука', 'uz-Latn': 'Piyozsiz', en: 'No onions' },
};

describe('presetLabelFor', () => {
  it('reads the wording of ru / uz / en from the labels map, the storefront’s uz being the platform’s uz-Latn', () => {
    expect(presetLabelFor(TRIPLE, 'ru')).toBe('Без лука');
    expect(presetLabelFor(TRIPLE, 'uz')).toBe('Piyozsiz');
    expect(presetLabelFor(TRIPLE, 'en')).toBe('No onions');
  });

  it('reads the wording of a locale beyond the triple from the per-locale map', () => {
    const preset: PresetWording = {
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
      labels: { ru: 'Без лука', 'uz-Latn': 'Piyozsiz', en: 'No onions', kaa: 'Piyazsiz' },
      label: 'Piyazsiz',
    };

    expect(presetLabelFor(preset, 'de')).toBe('Piyazsiz');
  });

  it('falls back to the first wording the map holds, the platform triple first, when nothing else answers', () => {
    expect(presetLabelFor(TRIPLE, 'de')).toBe('Без лука');
  });

  it('answers an empty string, never undefined, for a preset with no wording at all', () => {
    expect(presetLabelFor({}, 'uz')).toBe('');
  });
});

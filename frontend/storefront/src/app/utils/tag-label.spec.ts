import { tagLabel } from './tag-label';

const TAG = {
  code: 'food_cold',
  labels: { 'uz-Latn': 'Taom sovigan', ru: 'Остыла еда', en: 'Food arrived cold' },
};

describe('tagLabel', () => {
  it('reads the visitor’s own language, naming the storefront’s uz the platform’s uz-Latn', () => {
    expect(tagLabel(TAG, 'uz')).toBe('Taom sovigan');
    expect(tagLabel(TAG, 'ru')).toBe('Остыла еда');
    expect(tagLabel(TAG, 'en')).toBe('Food arrived cold');
  });

  it('falls back to Uzbek, then Russian, then English, then any wording', () => {
    expect(tagLabel({ code: 'x', labels: { 'uz-Latn': 'Uz', ru: 'Ru' } }, 'en')).toBe('Uz');
    expect(tagLabel({ code: 'x', labels: { ru: 'Ru', en: 'En' } }, 'uz')).toBe('Ru');
    expect(tagLabel({ code: 'x', labels: { en: 'En' } }, 'ru')).toBe('En');
    expect(tagLabel({ code: 'x', labels: { de: 'Kalt' } }, 'ru')).toBe('Kalt');
  });

  it('shows the code, visibly wrong, when a tag has no wording at all', () => {
    expect(tagLabel({ code: 'food_cold', labels: {} }, 'uz')).toBe('food_cold');
  });
});

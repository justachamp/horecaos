import enTranslations from '../../../i18n/en.json';
import ruTranslations from '../../../i18n/ru.json';
import uzTranslations from '../../../i18n/uz.json';

import { ORDER_STATUS_I18N_KEY, formatPlacedAt } from './orders.data';

/**
 * The real shipped translation files, not a copy pasted into this spec. If a
 * future edit deletes or renames an `orders.platformStatus.*` entry, this
 * reads the actual regression rather than a fixture that drifted from it.
 */
const TRANSLATIONS: Record<'en' | 'ru' | 'uz', Record<string, unknown>> = {
  en: enTranslations,
  ru: ruTranslations,
  uz: uzTranslations,
};

function getNested(dict: Record<string, unknown>, dottedKey: string): unknown {
  return dottedKey.split('.').reduce<unknown>((node, part) => {
    if (node == null || typeof node !== 'object') return undefined;
    return (node as Record<string, unknown>)[part];
  }, dict);
}

describe('ORDER_STATUS_I18N_KEY: every real platform status resolves to an actual translation', () => {
  // ordering.domain.OrderStatus's full vocabulary, verified against
  // OrderStatus.java and StorefrontOrderingController.OrderResponse.of /
  // OrderSummaryResponse.of, which send `status().name()` verbatim.
  const ALL_PLATFORM_STATUSES = [
    'RECEIVED',
    'PAYMENT_AUTHORIZING',
    'AWAITING_APPROVAL',
    'PAYMENT_FAILED',
    'CONFIRMED',
    'REJECTED',
    'EXPIRED',
    'PREPARING',
    'READY',
    'FULFILLING',
    'COMPLETED',
    'CANCELLED',
  ];

  it('names every real OrderStatus value, and nothing else', () => {
    expect(Object.keys(ORDER_STATUS_I18N_KEY).sort()).toEqual([...ALL_PLATFORM_STATUSES].sort());
  });

  it.each(ALL_PLATFORM_STATUSES)('has a key pointing at orders.platformStatus.%s', (status) => {
    expect(ORDER_STATUS_I18N_KEY[status]).toBe(`orders.platformStatus.${status}`);
  });

  describe.each(['en', 'ru', 'uz'] as const)('%s.json', (locale) => {
    const dict = TRANSLATIONS[locale];

    it.each(ALL_PLATFORM_STATUSES)('resolves %s to a real, non-empty translated string', (status) => {
      const key = ORDER_STATUS_I18N_KEY[status];
      const value = getNested(dict, key);

      expect(typeof value).toBe('string');
      expect((value as string).trim().length).toBeGreaterThan(0);
      // The exact defect this whole wave exists to fix: a raw i18n key left
      // on screen because the lookup missed. Guard against the lookup
      // resolving to the key path itself (which is what `TranslateService.get`
      // returns when nothing is found).
      expect(value).not.toBe(key);
    });
  });
});

describe('formatPlacedAt', () => {
  it('formats a real ISO timestamp into a non-empty, locale-formatted string', () => {
    expect(formatPlacedAt('2026-09-01T10:00:00Z')).not.toBe('');
  });

  it('never fabricates a date: empty for null, undefined, or an unparsable value', () => {
    expect(formatPlacedAt(undefined)).toBe('');
    expect(formatPlacedAt(null)).toBe('');
    expect(formatPlacedAt('')).toBe('');
    expect(formatPlacedAt('not-a-date')).toBe('');
  });
});

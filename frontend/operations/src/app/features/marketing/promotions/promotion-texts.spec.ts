import { describe, expect, it } from 'vitest';

import { LOCALES } from '../../../core/i18n/i18n';
import { PROMOTION_ACTION_TYPES, CHANNEL_TYPES, FULFILLMENT_MODES } from './promotion-draft';
import { PROMOTION_TEXTS, promotionText } from './promotion-texts';

const en = PROMOTION_TEXTS['en'];

function placeholders(text: string): string[] {
  return [...text.matchAll(/\{(\w+)\}/g)].map((match) => match[1]).sort();
}

describe('promotion texts', () => {
  it('holds the same keys in every locale', () => {
    const keys = Object.keys(en).sort();
    for (const locale of LOCALES) {
      expect(Object.keys(PROMOTION_TEXTS[locale]).sort(), locale).toEqual(keys);
    }
  });

  for (const locale of LOCALES) {
    it(`has no blank string in ${locale}`, () => {
      const blank = Object.entries(PROMOTION_TEXTS[locale])
        .filter(([, value]) => value.trim() === '')
        .map(([key]) => key);
      expect(blank).toEqual([]);
    });

    it(`keeps every placeholder of the English source in ${locale}`, () => {
      const mismatched = Object.entries(PROMOTION_TEXTS[locale])
        .filter(
          ([key, value]) =>
            placeholders(value).join() !== placeholders(en[key as keyof typeof en]).join(),
        )
        .map(([key]) => key);
      expect(mismatched).toEqual([]);
    });
  }

  it('uses one apostrophe codepoint in uz-Latn, the one the central catalogue uses', () => {
    const stray = ['‘', '’', 'ʼ'];
    const offending = Object.entries(PROMOTION_TEXTS['uz-Latn'])
      .filter(([, value]) => stray.some((mark) => value.includes(mark)))
      .map(([key]) => key);
    expect(offending).toEqual([]);
  });

  it('translates into the language asked for, with placeholders filled', () => {
    expect(promotionText('en', 'group', { name: 'order' })).toBe('Group: order');
    expect(promotionText('ru', 'group', { name: 'order' })).toBe('Группа: order');
    expect(promotionText('uz-Latn', 'group', { name: 'order' })).toBe('Guruh: order');
  });

  // The screen builds some keys from server values; every value it can meet needs a string.
  const families: Readonly<Record<string, readonly string[]>> = {
    status: ['DRAFT', 'VALIDATED', 'ACTIVE', 'SUSPENDED', 'ARCHIVED'],
    kind: ['DISCOUNT', 'MARKUP'],
    scope: ['ITEM', 'ORDER', 'DELIVERY'],
    action: [...PROMOTION_ACTION_TYPES],
    channelType: [...CHANNEL_TYPES],
    fulfillment: [...FULFILLMENT_MODES],
    'loyalty.accrual': ['ACCRUE', 'SUPPRESS'],
    'loyalty.redemption': ['ALLOW', 'BLOCK'],
    adjustment: [
      'BASE_PRICE',
      'MODIFIER',
      'ITEM_DISCOUNT',
      'ORDER_DISCOUNT',
      'FEE',
      'TAX',
      'ROUNDING',
      'DELIVERY_FEE_WAIVER',
      'DELIVERY_FEE_BENEFIT',
      'DELIVERY_TARIFF_DISCOUNT',
      'ITEM_MARKUP',
    ],
    verdict: [
      'APPLIED',
      'CONDITION_FAILED',
      'OUTSIDE_WINDOW',
      'COUPON_NOT_PRESENTED',
      'LIMIT_REACHED',
      'NOT_CLAIMED_AT_PLACEMENT',
      'LOST_TO',
      'SUPPRESSED_BY_EXCLUSIVE',
      'ZERO_BENEFIT',
      'CURRENCY_MISMATCH',
    ],
    issue: [
      'NAME_REQUIRED',
      'CODE_INVALID',
      'STACKING_GROUP_REQUIRED',
      'CURRENCY_INVALID',
      'MAXIMUM_INVALID',
      'EXCLUSIVE_WITH_MARKUP',
      'MARKUP_SCOPE_INVALID',
      'ORDER_MARKUP_NOT_AVAILABLE',
      'MARKUP_NOT_COUPON_GATED',
      'NO_ACTION',
      'ACTION_SCOPE_MISMATCH',
      'OPERAND_INVALID',
      'PERCENTAGE_OVER_100',
      'FREE_ITEM_UNBOUNDED',
      'UNKNOWN_REFERENCE',
      'NO_PRICED_VARIANT_MATCH',
      'WEEKDAYS_EMPTY',
      'WINDOW_INVERTED',
      'SEQUENCE_NEEDS_CUSTOMER_LIMIT',
      'LIMIT_ON_COUPON_PROMOTION',
      'STACKING_GROUP_MIXES_SCOPES',
      'CURRENCY_MISMATCH',
      'UNCAPPED_PERCENTAGE',
    ],
    'report.source': ['AUTOMATIC', 'COUPON', 'GRANT'],
  };

  for (const [family, values] of Object.entries(families)) {
    it(`has a string for every ${family} the screen can name`, () => {
      const missing = values.filter((value) => !Object.hasOwn(en, `${family}.${value}`));
      expect(missing).toEqual([]);
    });
  }
});

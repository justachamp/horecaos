import { describe, expect, it } from 'vitest';

import { ordersEn } from '../../core/i18n/messages/orders.en';
import {
  CHANNEL_SYSTEM_TYPES,
  CHANNEL_SYSTEM_TYPE_LABEL_KEYS,
  FISCAL_STATUS_LABEL_KEYS,
  PAYMENT_METHOD_LABEL_KEYS,
  paymentMethodGlyph,
  sourceGlyph,
} from './order-glyphs';

describe('the glyphs the order board draws (gap map row 1.1)', () => {
  it('has a word and a mark for each of the nine channel kinds ADR 0036 closes the set at', () => {
    expect(CHANNEL_SYSTEM_TYPES).toHaveLength(9);
    for (const type of CHANNEL_SYSTEM_TYPES) {
      const mark = sourceGlyph({ channelSystemType: type });
      expect(mark, type).not.toBeNull();
      expect(mark!.typeLabelKey, type).toBe(CHANNEL_SYSTEM_TYPE_LABEL_KEYS[type]);
      expect(
        ordersEn[CHANNEL_SYSTEM_TYPE_LABEL_KEYS[type] as keyof typeof ordersEn],
        type,
      ).toBeTruthy();
    }
  });

  it('lets the channel’s own icon choose among the marks, and ignores a name it does not know', () => {
    expect(sourceGlyph({ channelSystemType: 'WEB', channelIcon: 'telegram' })!.glyph).toBe(
      'telegram',
    );
    expect(sourceGlyph({ channelSystemType: 'WEB', channelIcon: 'yandex-eda-logo' })!.glyph).toBe(
      'web',
    );
    expect(
      sourceGlyph({ channelSystemType: 'WEB', channelIcon: '<img src=x onerror=alert(1)>' })!.glyph,
    ).toBe('web');
  });

  it('draws nothing for an order the platform named no channel kind for, but an aggregator order is still one', () => {
    expect(sourceGlyph({})).toBeNull();
    expect(sourceGlyph({ channelSystemType: null, origin: 'HORECAOS' })).toBeNull();
    const pushed = sourceGlyph({ channelSystemType: null, origin: 'MARKETPLACE' })!;
    expect(pushed.glyph).toBe('courier');
    expect(pushed.typeLabelKey).toBe('orders.source.type.AGGREGATOR');
  });

  it('draws the generic mark for a channel kind a later platform adds', () => {
    const mark = sourceGlyph({ channelSystemType: 'SMART_FRIDGE' })!;
    expect(mark.glyph).toBe('channel');
    expect(mark.typeLabelKey).toBeNull();
  });

  it('draws each payment method its own kind of mark, a coin for a code it has not met, and nothing for no code', () => {
    expect(paymentMethodGlyph('CASH')).toBe('cash');
    expect(paymentMethodGlyph('CLICK')).toBe('card');
    expect(paymentMethodGlyph('PAYME')).toBe('card');
    expect(paymentMethodGlyph('MARKETPLACE')).toBe('courier');
    expect(paymentMethodGlyph('UZUM_NASIYA')).toBe('coin');
    expect(paymentMethodGlyph(null)).toBeNull();
    expect(paymentMethodGlyph('')).toBeNull();
    for (const code of Object.keys(PAYMENT_METHOD_LABEL_KEYS)) {
      expect(ordersEn[PAYMENT_METHOD_LABEL_KEYS[code] as keyof typeof ordersEn], code).toBeTruthy();
    }
  });

  it('has a word for each of the ten fiscal statuses', () => {
    expect(Object.keys(FISCAL_STATUS_LABEL_KEYS)).toHaveLength(10);
    for (const status of Object.keys(FISCAL_STATUS_LABEL_KEYS)) {
      expect(
        ordersEn[FISCAL_STATUS_LABEL_KEYS[status] as keyof typeof ordersEn],
        status,
      ).toBeTruthy();
    }
  });
});

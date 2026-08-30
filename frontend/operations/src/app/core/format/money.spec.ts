import { describe, expect, it } from 'vitest';

import { formatMoney, minorUnitExponent } from './money';

/**
 * The group separator is U+00A0 NO-BREAK SPACE, written as an escape throughout
 * this file. A literal non-breaking space in a test looks exactly like a normal
 * one, so an assertion that should have failed passes and the reviewer cannot
 * see why.
 */
const NBSP = '\u00a0';

describe('formatMoney', () => {
  /**
   * The regression test for the bug that shipped. Everything else in this file
   * is ordinary coverage; this one exists because the wrong answer is plausible.
   */
  it('treats a UZS minor unit as a whole som, not a hundredth of one', () => {
    expect(formatMoney({ amountMinor: 125_000, currency: 'UZS' }, 'ru')).toBe(`125${NBSP}000`);
  });

  it('disagrees with Intl about UZS, deliberately', () => {
    // Intl follows ISO 4217 and reports two minor digits for UZS. The platform
    // stores whole som. If this assertion ever fails, ICU has changed its mind
    // and the comment in money.ts needs revisiting — the formatter does not.
    const intlDigits = new Intl.NumberFormat('ru', {
      style: 'currency',
      currency: 'UZS',
    }).resolvedOptions().maximumFractionDigits;

    expect(intlDigits).toBe(2);
    expect(minorUnitExponent('UZS')).toBe(0);
  });

  it('groups thousands with a non-breaking space and no decimals', () => {
    expect(formatMoney({ amountMinor: 1_234_567, currency: 'UZS' }, 'ru')).toBe(
      '1\u00a0234\u00a0567',
    );
    expect(formatMoney({ amountMinor: 999, currency: 'UZS' }, 'ru')).toBe('999');
    expect(formatMoney({ amountMinor: 0, currency: 'UZS' }, 'ru')).toBe('0');
  });

  it('omits the unit on a row and writes it on a total', () => {
    const money = { amountMinor: 146_000, currency: 'UZS' };
    expect(formatMoney(money, 'ru')).toBe('146\u00a0000');
    expect(formatMoney(money, 'ru', { withUnit: true })).toBe('146\u00a0000\u00a0сум');
    expect(formatMoney(money, 'uz-Latn', { withUnit: true })).toBe('146\u00a0000\u00a0soʻm');
    expect(formatMoney(money, 'en', { withUnit: true })).toBe('146\u00a0000\u00a0UZS');
  });

  it('still divides correctly for a currency that really has minor units', () => {
    expect(formatMoney({ amountMinor: 125_000, currency: 'USD' }, 'en')).toBe('1\u00a0250.00');
    expect(formatMoney({ amountMinor: 5, currency: 'USD' }, 'en')).toBe('0.05');
    expect(formatMoney({ amountMinor: 125_000, currency: 'RUB' }, 'ru')).toBe('1\u00a0250,00');
  });

  it('uses a minus sign rather than a hyphen so a column stays aligned', () => {
    expect(formatMoney({ amountMinor: -45_000, currency: 'UZS' }, 'ru')).toBe('−45\u00a0000');
  });

  it('refuses an unknown currency rather than guessing its exponent', () => {
    expect(() => formatMoney({ amountMinor: 100, currency: 'KZT' }, 'ru')).toThrow(
      /Unknown currency/,
    );
  });

  it('refuses a fractional minor unit, which means money went through a float', () => {
    expect(() => formatMoney({ amountMinor: 1500.5, currency: 'UZS' }, 'ru')).toThrow(TypeError);
  });
});

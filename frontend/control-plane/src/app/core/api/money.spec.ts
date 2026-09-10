import { describe, expect, it } from 'vitest';

import { UnknownCurrencyError, formatAmount, groupDigits, parseAmount } from './money';

/**
 * The regression this file exists for is the first test.
 *
 * A formatter that consults `Intl` for UZS finds ISO 4217's exponent of 2,
 * divides by a hundred, and renders eighty-four thousand som as 840. That
 * shipped. If this test is ever changed to expect `840,00`, the change is the
 * bug.
 */
describe('UZS is whole som', () => {
  it('does not divide a UZS amount', () => {
    expect(formatAmount({ amountMinor: 84_000, currency: 'UZS' })).toBe('84 000');
  });

  it('renders a large amount grouped in threes with spaces', () => {
    expect(formatAmount({ amountMinor: 1_884_900_000, currency: 'UZS' })).toBe('1 884 900 000');
  });

  it('never renders a decimal part for UZS', () => {
    expect(formatAmount({ amountMinor: 1, currency: 'UZS' })).toBe('1');
  });

  it('disagrees with Intl on purpose', () => {
    // Pinning the disagreement: if a future runtime decides UZS has no minor
    // unit after all, this fails and the comment in money.ts can be revisited.
    const intlDigits = new Intl.NumberFormat('en', {
      style: 'currency',
      currency: 'UZS',
    }).resolvedOptions().maximumFractionDigits;

    expect(intlDigits).toBeGreaterThan(0);
    expect(formatAmount({ amountMinor: 84_000, currency: 'UZS' })).not.toContain(',');
  });
});

describe('currencies that do have minor units', () => {
  it('splits USD into som and cents at two places', () => {
    expect(formatAmount({ amountMinor: 125_075, currency: 'USD' })).toBe('1 250,75');
  });

  it('pads a sub-unit amount rather than losing the leading zero', () => {
    expect(formatAmount({ amountMinor: 5, currency: 'USD' })).toBe('0,05');
  });
});

describe('signs and separators', () => {
  it('uses a typographic minus so a sign is legible in a tabular column', () => {
    expect(formatAmount({ amountMinor: -3_200_000, currency: 'UZS' })).toBe('−3 200 000');
  });

  it('groups with a space in every locale', () => {
    expect(groupDigits('1234567')).toBe('1 234 567');
    expect(groupDigits('100')).toBe('100');
  });
});

describe('an unknown currency', () => {
  it('throws rather than guessing a scale', () => {
    expect(() => formatAmount({ amountMinor: 100, currency: 'JPY' })).toThrow(UnknownCurrencyError);
  });
});

describe('an amount typed into a form', () => {
  it('stores a UZS price as the whole som typed, never times a hundred', () => {
    expect(parseAmount('9 000 000', 'UZS')).toBe(9_000_000);
    expect(parseAmount('84000', 'UZS')).toBe(84_000);
  });

  it('reads either decimal separator at the currency’s own scale', () => {
    expect(parseAmount('12,50', 'USD')).toBe(1250);
    expect(parseAmount('12.5', 'USD')).toBe(1250);
    expect(parseAmount('12', 'USD')).toBe(1200);
  });

  it('refuses what it cannot read exactly rather than rounding it', () => {
    expect(parseAmount('1,5', 'UZS')).toBeNull();
    expect(parseAmount('12,505', 'USD')).toBeNull();
    expect(parseAmount('-3', 'UZS')).toBeNull();
    expect(parseAmount('', 'UZS')).toBeNull();
  });

  it('reads back exactly what the formatter shows', () => {
    const shown = formatAmount({ amountMinor: 125075, currency: 'USD' });
    expect(parseAmount(shown, 'USD')).toBe(125075);
  });
});

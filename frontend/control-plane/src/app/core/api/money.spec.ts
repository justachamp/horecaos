import { describe, expect, it } from 'vitest';

import { UnknownCurrencyError, formatAmount, groupDigits, parseAmount, parseSignedAmount } from './money';

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

/**
 * A wallet correction is the one amount a form may type downwards (ADR 0095,
 * item 4), and the field's own placeholder asks for the minus sign. Every
 * other form reads a price, so the sign stays refused there — the test above
 * pins `parseAmount('-3', 'UZS')` as null and this one must not change it.
 */
describe('an amount that may take money away', () => {
  it('reads a minus the same whichever sign was typed', () => {
    expect(parseSignedAmount('-5 000 000', 'UZS')).toBe(-5_000_000);
    // U+2212, which is what the ledger shows and therefore what an operator
    // pastes back in to reverse an entry.
    expect(parseSignedAmount('−5 000 000', 'UZS')).toBe(-5_000_000);
    expect(parseSignedAmount('-1 250,75', 'USD')).toBe(-125075);
  });

  it('reads an unsigned amount exactly as the unsigned reader does', () => {
    expect(parseSignedAmount('9 000 000', 'UZS')).toBe(parseAmount('9 000 000', 'UZS'));
    expect(parseSignedAmount('12.5', 'USD')).toBe(1250);
  });

  it('reads back what the formatter shows, sign and all', () => {
    const shown = formatAmount({ amountMinor: -3_200_000, currency: 'UZS' });
    expect(parseSignedAmount(shown, 'UZS')).toBe(-3_200_000);
  });

  it('refuses a sign that is not one amount', () => {
    expect(parseSignedAmount('--5', 'UZS')).toBeNull();
    expect(parseSignedAmount('−−5', 'UZS')).toBeNull();
    expect(parseSignedAmount('-', 'UZS')).toBeNull();
    expect(parseSignedAmount('+5', 'UZS')).toBeNull();
    expect(parseSignedAmount('-1,5', 'UZS')).toBeNull();
  });

  it('reads “-0” as zero, which the caller refuses rather than the reader', () => {
    // A minus with nothing behind it is a statement of intent, not an amount;
    // the wallet form's own `!== 0` guard is what keeps the button disabled.
    // Compared with `===` on purpose: the value is negative zero, and `toBe`
    // is `Object.is`, which tells the two zeroes apart where `!== 0` does not.
    expect(parseSignedAmount('-0', 'UZS') === 0).toBe(true);
  });

  it('leaves the unsigned reader refusing a sign, which is what prices rely on', () => {
    expect(parseAmount('-3', 'UZS')).toBeNull();
    expect(parseAmount('−3', 'UZS')).toBeNull();
  });
});

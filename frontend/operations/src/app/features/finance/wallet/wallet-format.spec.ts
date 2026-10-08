import { describe, expect, it } from 'vitest';

import {
  cardExpiry,
  currencyIsScaled,
  parseAmountInput,
  safeFormUrl,
  walletDate,
  walletDateTime,
  walletMoney,
} from './wallet-format';

const plain = (text: string): string => text.replace(/[  ]/g, ' ');

describe('walletMoney', () => {
  it('writes a som amount grouped, with its unit, and never scales UZS', () => {
    expect(plain(walletMoney({ amountMinor: 150_000, currency: 'UZS' }, 'en'))).toBe('150 000 UZS');
  });

  it('shows the stored figure unscaled for a currency this console has no scale for, instead of throwing', () => {
    // formatMoney throws on KZT on purpose; a template that throws takes the whole screen with it.
    expect(walletMoney({ amountMinor: 1_250_000, currency: 'KZT' }, 'en')).toBe('1250000 KZT');
  });
});

describe('currencyIsScaled', () => {
  it('is true only for a currency money.ts declares a scale for', () => {
    expect(currencyIsScaled('UZS')).toBe(true);
    expect(currencyIsScaled('USD')).toBe(true);
    expect(currencyIsScaled('KZT')).toBe(false);
  });
});

describe('parseAmountInput', () => {
  it('reads a typed som amount, with or without grouping, as whole som', () => {
    expect(parseAmountInput('150000', 'UZS')).toBe(150_000);
    expect(parseAmountInput('150 000', 'UZS')).toBe(150_000);
    expect(parseAmountInput('150 000', 'UZS')).toBe(150_000);
  });

  it('refuses a decimal point in a currency that has none, rather than reading it as tiyin', () => {
    expect(parseAmountInput('1500.50', 'UZS')).toBeNull();
    expect(parseAmountInput('1500,5', 'UZS')).toBeNull();
  });

  it('reads decimals against a currency that has them, and scales by its own exponent', () => {
    expect(parseAmountInput('12,50', 'USD')).toBe(1250);
    expect(parseAmountInput('12.5', 'USD')).toBe(1250);
    expect(parseAmountInput('12', 'USD')).toBe(1200);
    expect(parseAmountInput('12.505', 'USD')).toBeNull();
  });

  it('is null for zero, a negative, text or nothing: a top-up is a positive amount', () => {
    expect(parseAmountInput('0', 'UZS')).toBeNull();
    expect(parseAmountInput('-5000', 'UZS')).toBeNull();
    expect(parseAmountInput('abc', 'UZS')).toBeNull();
    expect(parseAmountInput('', 'UZS')).toBeNull();
  });

  it('is null for a currency with no declared scale: guessing one is the bug money.ts documents', () => {
    expect(parseAmountInput('1000', 'KZT')).toBeNull();
  });

  it('is null past the safe integer range rather than rounding', () => {
    expect(parseAmountInput('99999999999999999999', 'UZS')).toBeNull();
  });
});

describe('dates and cards', () => {
  it('reads an instant in Tashkent, as every tenant-wide screen does', () => {
    expect(walletDate('2026-10-31T20:30:00Z')).toBe('01.11.2026');
    expect(walletDateTime('2026-10-31T20:30:00Z')).toBe('01.11 01:30');
  });

  it('shows a dash for a missing instant and the raw text for an unreadable one', () => {
    expect(walletDate(null)).toBe('—');
    expect(walletDateTime(null)).toBe('—');
    expect(walletDate('not a date')).toBe('not a date');
  });

  it('writes a card’s expiry the way the card does', () => {
    expect(cardExpiry(3, 2029)).toBe('03/2029');
    expect(cardExpiry(null, 2029)).toBeNull();
  });

  it('offers only an https URL as a link to the provider’s form', () => {
    expect(safeFormUrl('https://pay.example.test/enrol/1')).toBe(
      'https://pay.example.test/enrol/1',
    );
    expect(safeFormUrl('http://pay.example.test/enrol/1')).toBeNull();
    expect(safeFormUrl('javascript:alert(1)')).toBeNull();
    expect(safeFormUrl('not a url')).toBeNull();
    expect(safeFormUrl(null)).toBeNull();
  });
});

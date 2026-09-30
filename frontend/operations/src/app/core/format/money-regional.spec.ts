import { afterEach, describe, expect, it } from 'vitest';

import { formatMoney } from './money';
import { applyRegionalFormats, resetRegionalFormats } from './regional-format';

/** U+00A0 NO-BREAK SPACE, spelled as an escape for the reason `money.spec.ts` gives. */
const NBSP = ' ';

const TOTAL = { amountMinor: 146_000, currency: 'UZS' };

/**
 * Row 10.12: `formatMoney` is the one place every amount in the console goes through, so the
 * brand's choice has to change what it returns without a single call site knowing about it.
 */
describe('formatMoney follows the brand’s regional formats', () => {
  afterEach(() => resetRegionalFormats());

  it('writes the unit before the amount when the brand chose BEFORE, and only on a total', () => {
    applyRegionalFormats({ moneySymbolPlacement: 'BEFORE' });

    expect(formatMoney(TOTAL, 'ru', { withUnit: true })).toBe(`сум${NBSP}146${NBSP}000`);
    expect(formatMoney(TOTAL, 'uz-Latn', { withUnit: true })).toBe(`soʻm${NBSP}146${NBSP}000`);
    expect(formatMoney(TOTAL, 'en', { withUnit: true })).toBe(`UZS${NBSP}146${NBSP}000`);
    expect(formatMoney(TOTAL, 'ru')).toBe(`146${NBSP}000`);
  });

  it('keeps the minus sign in front of a negative total whichever side the unit is on', () => {
    const refund = { amountMinor: -45_000, currency: 'UZS' };

    expect(formatMoney(refund, 'en', { withUnit: true })).toBe(`−45${NBSP}000${NBSP}UZS`);
    applyRegionalFormats({ moneySymbolPlacement: 'BEFORE' });
    expect(formatMoney(refund, 'en', { withUnit: true })).toBe(`−UZS${NBSP}45${NBSP}000`);
  });

  it('groups with a comma, a dot or nothing at all', () => {
    const million = { amountMinor: 1_234_567, currency: 'UZS' };

    applyRegionalFormats({ moneyGrouping: 'COMMA' });
    expect(formatMoney(million, 'ru')).toBe('1,234,567');
    applyRegionalFormats({ moneyGrouping: 'DOT' });
    expect(formatMoney(million, 'ru')).toBe('1.234.567');
    applyRegionalFormats({ moneyGrouping: 'NONE' });
    expect(formatMoney(million, 'ru')).toBe('1234567');
  });

  it('moves the decimal separator off the group separator, so the two never read alike', () => {
    const usd = { amountMinor: 125_000, currency: 'USD' };

    applyRegionalFormats({ moneyGrouping: 'COMMA' });
    expect(formatMoney(usd, 'ru')).toBe('1,250.00');
    applyRegionalFormats({ moneyGrouping: 'DOT' });
    expect(formatMoney(usd, 'en')).toBe('1.250,00');
    applyRegionalFormats({ moneyGrouping: 'NONE' });
    expect(formatMoney(usd, 'ru')).toBe('1250,00');
    expect(formatMoney(usd, 'en')).toBe('1250.00');
  });

  it('applies a placement and a grouping together', () => {
    applyRegionalFormats({ moneySymbolPlacement: 'BEFORE', moneyGrouping: 'COMMA' });

    expect(formatMoney(TOTAL, 'en', { withUnit: true })).toBe(`UZS${NBSP}146,000`);
  });

  it('reads a value it cannot render as the default rather than as a broken price', () => {
    applyRegionalFormats({ moneySymbolPlacement: 'MIDDLE', moneyGrouping: 'PIPE' });

    expect(formatMoney(TOTAL, 'ru', { withUnit: true })).toBe(`146${NBSP}000${NBSP}сум`);
  });

  it('is back to what it always did once the formats are reset', () => {
    applyRegionalFormats({ moneySymbolPlacement: 'BEFORE', moneyGrouping: 'DOT' });
    resetRegionalFormats();

    expect(formatMoney(TOTAL, 'ru', { withUnit: true })).toBe(`146${NBSP}000${NBSP}сум`);
  });
});

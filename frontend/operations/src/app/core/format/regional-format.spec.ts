import { afterEach, describe, expect, it } from 'vitest';

import {
  DEFAULT_REGIONAL_FORMATS,
  activeRegionalFormats,
  applyRegionalFormats,
  decimalSeparatorFor,
  groupDigits,
  groupSeparator,
  normalizeRegionalFormats,
  resetRegionalFormats,
} from './regional-format';

describe('normalizeRegionalFormats', () => {
  it('keeps a value this console can render', () => {
    expect(
      normalizeRegionalFormats({
        moneySymbolPlacement: 'BEFORE',
        moneyGrouping: 'COMMA',
        phoneDisplayPattern: '+### ## ### ## ##',
      }),
    ).toEqual({
      moneySymbolPlacement: 'BEFORE',
      moneyGrouping: 'COMMA',
      phoneDisplayPattern: '+### ## ### ## ##',
    });
  });

  it('reads nothing, an older platform’s reply, as the default', () => {
    expect(normalizeRegionalFormats(undefined)).toEqual(DEFAULT_REGIONAL_FORMATS);
    expect(normalizeRegionalFormats(null)).toEqual(DEFAULT_REGIONAL_FORMATS);
    expect(normalizeRegionalFormats({})).toEqual(DEFAULT_REGIONAL_FORMATS);
  });

  it('reads a value from a newer platform, one this build has never heard of, as the default', () => {
    expect(
      normalizeRegionalFormats({ moneySymbolPlacement: 'MIDDLE', moneyGrouping: 'APOSTROPHE' }),
    ).toEqual(DEFAULT_REGIONAL_FORMATS);
  });

  it('treats a blank pattern as none', () => {
    expect(normalizeRegionalFormats({ phoneDisplayPattern: '   ' }).phoneDisplayPattern).toBeNull();
  });
});

describe('the active formats', () => {
  afterEach(() => resetRegionalFormats());

  it('start as the default and follow apply and reset', () => {
    expect(activeRegionalFormats()).toEqual(DEFAULT_REGIONAL_FORMATS);

    applyRegionalFormats({ moneyGrouping: 'DOT' });
    expect(activeRegionalFormats().moneyGrouping).toBe('DOT');

    resetRegionalFormats();
    expect(activeRegionalFormats()).toEqual(DEFAULT_REGIONAL_FORMATS);
  });
});

describe('separators', () => {
  it('names one character per grouping', () => {
    expect(groupSeparator('SPACE')).toBe(' ');
    expect(groupSeparator('COMMA')).toBe(',');
    expect(groupSeparator('DOT')).toBe('.');
    expect(groupSeparator('NONE')).toBe('');
  });

  it('never lets the decimal separator be the group separator', () => {
    expect(decimalSeparatorFor('ru', 'SPACE')).toBe(',');
    expect(decimalSeparatorFor('en', 'SPACE')).toBe('.');
    expect(decimalSeparatorFor('ru', 'COMMA')).toBe('.');
    expect(decimalSeparatorFor('en', 'COMMA')).toBe('.');
    expect(decimalSeparatorFor('ru', 'DOT')).toBe(',');
    expect(decimalSeparatorFor('en', 'DOT')).toBe(',');
  });

  it('groups a digit string by three from the right', () => {
    expect(groupDigits('1234567', 'COMMA')).toBe('1,234,567');
    expect(groupDigits('123', 'COMMA')).toBe('123');
    expect(groupDigits('1234', 'NONE')).toBe('1234');
  });
});

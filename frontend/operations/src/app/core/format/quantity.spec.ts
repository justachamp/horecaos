import { afterEach, describe, expect, it } from 'vitest';

import { applyRegionalFormats, resetRegionalFormats } from './regional-format';
import {
  catchweightPriceOf,
  formatQuantity,
  formatVolume,
  formatWeight,
  initialQuantity,
  lineQuantityStep,
  timesQuantity,
} from './quantity';

const NBSP = '\u00a0';

describe('formatQuantity', () => {
  afterEach(() => resetRegionalFormats());

  it('writes a whole quantity as a whole number: 2, never 2.000', () => {
    expect(formatQuantity(2, 'ru')).toBe('2');
    expect(formatQuantity(2.0, 'en')).toBe('2');
    expect(formatQuantity(999, 'ru')).toBe('999');
  });

  it('writes a portion with the locale’s decimal separator and no trailing zeros', () => {
    expect(formatQuantity(0.5, 'ru')).toBe('0,5');
    expect(formatQuantity(0.5, 'uz-Latn')).toBe('0,5');
    expect(formatQuantity(0.5, 'en')).toBe('0.5');
    expect(formatQuantity(1.25, 'ru')).toBe('1,25');
    expect(formatQuantity(0.125, 'en')).toBe('0.125');
  });

  it('never shows float noise: three decimals at most', () => {
    expect(formatQuantity(0.1 + 0.2, 'en')).toBe('0.3');
    expect(formatQuantity(2.0004, 'en')).toBe('2');
    expect(formatQuantity(2.9996, 'en')).toBe('3');
  });

  it('follows the brand’s choice of decimal mark, as the money formatter does', () => {
    applyRegionalFormats({ moneyGrouping: 'COMMA' });
    expect(formatQuantity(0.5, 'ru')).toBe('0.5');
    applyRegionalFormats({ moneyGrouping: 'DOT' });
    expect(formatQuantity(0.5, 'en')).toBe('0,5');
  });
});

describe('formatWeight', () => {
  it('writes grams below a kilogram and kilograms from there, with the locale’s unit', () => {
    expect(formatWeight(350, 'ru')).toBe(`350${NBSP}г`);
    expect(formatWeight(350, 'uz-Latn')).toBe(`350${NBSP}g`);
    expect(formatWeight(350, 'en')).toBe(`350${NBSP}g`);
    expect(formatWeight(1000, 'ru')).toBe(`1${NBSP}кг`);
    expect(formatWeight(1340, 'ru')).toBe(`1,34${NBSP}кг`);
    expect(formatWeight(1340, 'en')).toBe(`1.34${NBSP}kg`);
    expect(formatWeight(12_345, 'en')).toBe(`12.345${NBSP}kg`);
  });
});

describe('formatVolume', () => {
  it('writes millilitres below a litre and litres from there', () => {
    expect(formatVolume(330, 'ru')).toBe(`330${NBSP}мл`);
    expect(formatVolume(1500, 'ru')).toBe(`1,5${NBSP}л`);
    expect(formatVolume(1500, 'uz-Latn')).toBe(`1,5${NBSP}l`);
    expect(formatVolume(500, 'en')).toBe(`500${NBSP}ml`);
  });
});

describe('lineQuantityStep', () => {
  it('a variant with no portion step is ordered in whole units', () => {
    expect(lineQuantityStep(null)).toBe(1);
    expect(lineQuantityStep(undefined)).toBe(1);
  });

  it('a splittable variant moves by its portion size', () => {
    expect(lineQuantityStep({ splittable: true, portionSize: 0.5 })).toBe(0.5);
    expect(lineQuantityStep({ splittable: true, portionSize: null })).toBe(1);
  });

  it('a portion size without the splittable flag is not honoured, as the cart refuses it too', () => {
    expect(lineQuantityStep({ splittable: false, portionSize: 0.5 })).toBe(1);
  });
});

describe('timesQuantity', () => {
  it('is exact for a whole quantity, so an integer order totals as it always did', () => {
    expect(timesQuantity(38_000, 1)).toBe(38_000);
    expect(timesQuantity(38_000, 3)).toBe(114_000);
    expect(timesQuantity(0, 7)).toBe(0);
  });

  it('prices a portion and rounds once, half up, to a whole minor unit', () => {
    expect(timesQuantity(38_000, 0.5)).toBe(19_000);
    expect(timesQuantity(18_001, 0.5)).toBe(9_001); // 9000.5 rounds up
    expect(timesQuantity(10_000, 1.25)).toBe(12_500);
    expect(timesQuantity(10_001, 0.001)).toBe(10); // 10.001 rounds down
    expect(timesQuantity(15_000, 1.2)).toBe(18_000); // 1.2 is not exact in binary floating point
  });
});

describe('catchweightPriceOf', () => {
  it('is the price per quantum times the weight over the quantum, rounded once', () => {
    // 150 000 UZS per 100 g, the cake of ADR 0137's own tests.
    expect(catchweightPriceOf(15_000, 100, 1_200)).toBe(180_000);
    expect(catchweightPriceOf(15_000, 100, 1_340)).toBe(201_000);
    expect(catchweightPriceOf(15_000, 100, 1_100)).toBe(165_000);
  });

  it('rounds half up and not per gram', () => {
    expect(catchweightPriceOf(15_001, 100, 1_250)).toBe(187_513); // 187 512.5
    expect(catchweightPriceOf(1, 100, 49)).toBe(0); // 0.49
    expect(catchweightPriceOf(1, 100, 50)).toBe(1); // 0.5
  });

  it('takes a decimal weight: two units of a nominal weight', () => {
    expect(catchweightPriceOf(15_000, 100, 2 * 1_200)).toBe(360_000);
    expect(catchweightPriceOf(15_000, 100, 0.5 * 1_200)).toBe(90_000);
  });
});

describe('initialQuantity', () => {
  it('starts a whole-unit or a halving variant at one', () => {
    expect(initialQuantity(1)).toBe(1);
    expect(initialQuantity(0.5)).toBe(1);
    expect(initialQuantity(0.25)).toBe(1);
  });

  it('starts at the first whole multiple of a step that does not divide one', () => {
    expect(initialQuantity(0.3)).toBe(1.2);
    expect(initialQuantity(1.5)).toBe(1.5);
    expect(initialQuantity(0.4)).toBe(1.2);
  });
});

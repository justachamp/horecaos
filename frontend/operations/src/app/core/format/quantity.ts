/**
 * Quantities, weights and volumes (ADR 0137).
 *
 * A line quantity stopped being a whole number when ADR 0137 widened it to
 * `numeric(10,3)`: half a portion of plov is `0.5` on the wire, a whole portion
 * is still `2` and never `2.000`. Everything the console shows or computes from
 * one goes through here, for the same reason `money.ts` exists — a figure that
 * is formatted or multiplied in four places is wrong in at least one of them.
 *
 * Money stays integer minor units. A quantity multiplies a minor-unit amount
 * and the result is rounded **once**, half up, to a whole minor unit — what
 * `Quantities.times` and `CatchweightPricing.priceOf` do on the server — so a
 * running total on screen cannot disagree with the one the platform will price.
 */

import { Locale } from '../i18n/i18n';
import {
  NO_BREAK_SPACE,
  RegionalFormats,
  activeRegionalFormats,
  decimalSeparatorFor,
} from './regional-format';

/** The fraction digits a quantity is stored with (`numeric(10,3)`). */
const QUANTITY_SCALE = 3;
const MILLI = 1000;

/** Three decimals at most, no trailing zeros, the brand's decimal mark: `2`, `0,5`, `1,25`. */
export function formatQuantity(
  quantity: number,
  locale: Locale,
  options: { readonly formats?: RegionalFormats } = {},
): string {
  return formatDecimal(quantity, locale, options.formats ?? activeRegionalFormats());
}

function formatDecimal(value: number, locale: Locale, formats: RegionalFormats): string {
  const fixed = (Math.round(value * MILLI) / MILLI).toFixed(QUANTITY_SCALE);
  const [whole, fraction = ''] = fixed.split('.');
  const trimmed = fraction.replace(/0+$/, '');
  return trimmed === ''
    ? whole
    : `${whole}${decimalSeparatorFor(locale, formats.moneyGrouping)}${trimmed}`;
}

const GRAM: Readonly<Record<Locale, readonly [string, string]>> = {
  ru: ['г', 'кг'],
  'uz-Latn': ['g', 'kg'],
  en: ['g', 'kg'],
};

const MILLILITRE: Readonly<Record<Locale, readonly [string, string]>> = {
  ru: ['мл', 'л'],
  'uz-Latn': ['ml', 'l'],
  en: ['ml', 'l'],
};

/** `350 г`, `1,34 кг`: grams below a kilogram, kilograms from there. */
export function formatWeight(grams: number, locale: Locale): string {
  return withUnit(grams, GRAM[locale], locale);
}

/** `330 мл`, `1,5 л`. */
export function formatVolume(millilitres: number, locale: Locale): string {
  return withUnit(millilitres, MILLILITRE[locale], locale);
}

function withUnit(amount: number, units: readonly [string, string], locale: Locale): string {
  const formats = activeRegionalFormats();
  return amount >= MILLI
    ? `${formatDecimal(amount / MILLI, locale, formats)}${NO_BREAK_SPACE}${units[1]}`
    : `${formatDecimal(amount, locale, formats)}${NO_BREAK_SPACE}${units[0]}`;
}

/** What a variant says about how it may be ordered — the part of `physical` the quantity controls need. */
export interface PortionFacts {
  readonly splittable: boolean;
  readonly portionSize?: number | null;
}

/**
 * The step a quantity control moves by: the portion size of a splittable variant,
 * one otherwise. A portion size without the splittable flag is not honoured — the
 * cart would refuse the fraction, so the control must not offer it.
 */
export function lineQuantityStep(physical: PortionFacts | null | undefined): number {
  return physical?.splittable && physical.portionSize ? physical.portionSize : 1;
}

/**
 * The quantity a first tap puts in the basket: one whole portion, or the first whole multiple
 * of the portion size above one when the step does not divide it (a step of 0.3 starts at 1.2,
 * because 1 is not a quantity the cart accepts for it).
 */
export function initialQuantity(step: number): number {
  return Math.round(Math.max(1, Math.ceil(1 / step - 1e-9)) * step * MILLI) / MILLI;
}

/** A quantity as thousandths, the integer the arithmetic below works in. */
function milli(quantity: number): bigint {
  return BigInt(Math.round(quantity * MILLI));
}

/** `numerator / denominator` rounded half up to a whole number, for non-negative operands. */
function divideHalfUp(numerator: bigint, denominator: bigint): number {
  return Number((2n * numerator + denominator) / (2n * denominator));
}

/**
 * A unit amount multiplied by a quantity, rounded once to a whole minor unit — the
 * client's copy of `Quantities.times`. Exact for a whole quantity.
 */
export function timesQuantity(unitMinor: number, quantity: number): number {
  return divideHalfUp(BigInt(unitMinor) * milli(quantity), BigInt(MILLI));
}

/**
 * What `grams` of a variant priced per `quantumGrams` cost — the client's copy of
 * `CatchweightPricing.priceOf`: one multiplication, one division, one rounding.
 */
export function catchweightPriceOf(
  pricePerQuantumMinor: number,
  quantumGrams: number,
  grams: number,
): number {
  return divideHalfUp(
    BigInt(pricePerQuantumMinor) * milli(grams),
    BigInt(quantumGrams) * BigInt(MILLI),
  );
}

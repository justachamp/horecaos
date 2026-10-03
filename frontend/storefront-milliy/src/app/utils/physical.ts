/**
 * What a customer may be told about a variant's physical nature, and the arithmetic that follows
 * from it (ADR 0137).
 *
 * `PhysicalFacts` is `StorefrontCatalogQuery.PhysicalFacts` as published: a variant sold by weight
 * (`catchweight`), by the portion (`splittable` + `portionSize`), with a weight or volume and a
 * КБЖУ per 100 g. A variant that carries none of it is a fixed unit sold whole and has no block
 * at all, so every function here takes `null` and answers as it always did.
 *
 * **Money stays integer minor units.** A quantity multiplies a minor-unit amount and the result is
 * rounded **once**, half up, to a whole minor unit — what the platform's `Quantities.times` and
 * `CatchweightPricing.priceOf` do — so a line shown here is the line the platform will price. The
 * platform still prices the cart; these figures are what a customer reads *before* the quote and
 * what a weighed line is *estimated* at until it is weighed at handover.
 */

export interface NutritionPer100 {
  readonly caloriesKcalPer100?: number | null;
  readonly proteinGramsPer100?: number | null;
  readonly fatGramsPer100?: number | null;
  readonly carbohydratesGramsPer100?: number | null;
}

export interface PhysicalFacts {
  readonly netWeightGrams?: number | null;
  readonly netVolumeMillilitres?: number | null;
  /** The price is per `catchweightQuantumGrams`, and the amount is final only once weighed. */
  readonly catchweight: boolean;
  readonly catchweightQuantumGrams?: number | null;
  readonly catchweightNominalGrams?: number | null;
  readonly splittable: boolean;
  /** The step the quantity moves in; absent means whole units only. */
  readonly portionSize?: number | null;
  readonly nutrition?: NutritionPer100 | null;
}

const MILLI = 1000;
const NBSP = ' ';

/** `2`, `0,5`, `1,25` — never `2.000`; the decimal mark follows the customer's language. */
export function formatQuantity(quantity: number, lang: string): string {
  return formatDecimal(quantity, lang);
}

function formatDecimal(value: number, lang: string): string {
  const [whole, fraction = ''] = (Math.round(value * MILLI) / MILLI).toFixed(3).split('.');
  const trimmed = fraction.replace(/0+$/, '');
  return trimmed === '' ? whole : `${whole}${lang === 'en' ? '.' : ','}${trimmed}`;
}

const GRAM: Readonly<Record<string, readonly [string, string]>> = {
  ru: ['г', 'кг'],
  uz: ['g', 'kg'],
  en: ['g', 'kg'],
};

const MILLILITRE: Readonly<Record<string, readonly [string, string]>> = {
  ru: ['мл', 'л'],
  uz: ['ml', 'l'],
  en: ['ml', 'l'],
};

/** `350 г`, `1,34 кг`. */
export function formatWeight(grams: number, lang: string): string {
  return withUnit(grams, GRAM[lang] ?? GRAM['uz'], lang);
}

/** `330 мл`, `1,5 л`. */
export function formatVolume(millilitres: number, lang: string): string {
  return withUnit(millilitres, MILLILITRE[lang] ?? MILLILITRE['uz'], lang);
}

function withUnit(amount: number, units: readonly [string, string], lang: string): string {
  return amount >= MILLI
    ? `${formatDecimal(amount / MILLI, lang)}${NBSP}${units[1]}`
    : `${formatDecimal(amount, lang)}${NBSP}${units[0]}`;
}

/**
 * The step a quantity moves in: the portion size of a splittable variant, one otherwise. A portion
 * size without the splittable flag is not honoured — the cart would refuse the fraction, so the
 * control must not offer it.
 */
export function portionStep(physical: PhysicalFacts | null | undefined): number {
  return physical?.splittable && physical.portionSize ? physical.portionSize : 1;
}

/**
 * The quantity a first tap puts in the cart: one whole portion, or the first whole multiple of the
 * step above one when the step does not divide it (a step of 0.3 starts at 1.2, because 1 is not a
 * quantity the cart accepts for it).
 */
export function initialQuantity(step: number): number {
  return Math.round(Math.max(1, Math.ceil(1 / step - 1e-9)) * step * MILLI) / MILLI;
}

/** `numerator / denominator` rounded half up to a whole number, for non-negative operands. */
function divideHalfUp(numerator: bigint, denominator: bigint): number {
  return Number((2n * numerator + denominator) / (2n * denominator));
}

function milli(quantity: number): bigint {
  return BigInt(Math.round(quantity * MILLI));
}

/**
 * The weight one unit of a weighed variant is estimated at: the authored estimate, failing that the
 * net weight — what the platform quotes against. `null` for a variant that is not sold by weight.
 */
export function catchweightEstimateGrams(
  physical: PhysicalFacts | null | undefined,
): number | null {
  if (!physical?.catchweight || !physical.catchweightQuantumGrams) {
    return null;
  }
  return physical.catchweightNominalGrams ?? physical.netWeightGrams ?? null;
}

/**
 * What one unit costs: the price row, or for a weighed variant the price of one unit at its
 * estimated weight (the price row is per quantum).
 */
export function unitPriceMinor(
  priceRowMinor: number,
  physical: PhysicalFacts | null | undefined,
): number {
  const estimate = catchweightEstimateGrams(physical);
  if (estimate === null || !physical?.catchweightQuantumGrams) {
    return priceRowMinor;
  }
  return divideHalfUp(
    BigInt(priceRowMinor) * milli(estimate),
    BigInt(physical.catchweightQuantumGrams) * BigInt(MILLI),
  );
}

/**
 * A line's amount: the price row times the quantity rounded once, or for a weighed variant `price ×
 * grams ÷ quantum` at the estimated weight of every unit, rounded once.
 */
export function lineAmountMinor(
  priceRowMinor: number,
  quantity: number,
  physical: PhysicalFacts | null | undefined,
): number {
  const estimate = catchweightEstimateGrams(physical);
  if (estimate === null || !physical?.catchweightQuantumGrams) {
    return divideHalfUp(BigInt(priceRowMinor) * milli(quantity), BigInt(MILLI));
  }
  return divideHalfUp(
    BigInt(priceRowMinor) * milli(quantity * estimate),
    BigInt(physical.catchweightQuantumGrams) * BigInt(MILLI),
  );
}

/** КБЖУ of one whole unit, scaled from the per-100 figures by the variant's own weight or volume. */
export interface NutritionOfUnit {
  /** Whether the figures are per 100 g or per 100 ml, and what `amount` is in. */
  readonly basisLabel: 'WEIGHT' | 'VOLUME';
  readonly amount: number;
  readonly caloriesKcal: number | null;
  readonly proteinGrams: number | null;
  readonly fatGrams: number | null;
  readonly carbohydratesGrams: number | null;
}

/**
 * The per-100 КБЖУ scaled to the variant's own weight (or volume), as the platform's own record
 * says a client does: "a presentation computation the client makes from the stored per-100
 * figures, not a second stored value that could drift". `null` when there is nothing to scale or
 * nothing to scale by.
 */
export function nutritionPerUnit(
  physical: PhysicalFacts | null | undefined,
): NutritionOfUnit | null {
  const nutrition = physical?.nutrition;
  if (!physical || !nutrition) {
    return null;
  }
  const weight = physical.netWeightGrams ?? null;
  const volume = physical.netVolumeMillilitres ?? null;
  const amount = weight ?? volume;
  if (amount === null) {
    return null;
  }
  const scale = (per100: number | null | undefined, digits: number): number | null =>
    per100 === null || per100 === undefined
      ? null
      : Math.round(((per100 * amount) / 100) * 10 ** digits) / 10 ** digits;
  const result: NutritionOfUnit = {
    basisLabel: weight !== null ? 'WEIGHT' : 'VOLUME',
    amount,
    caloriesKcal: scale(nutrition.caloriesKcalPer100, 0),
    proteinGrams: scale(nutrition.proteinGramsPer100, 0),
    fatGrams: scale(nutrition.fatGramsPer100, 0),
    carbohydratesGrams: scale(nutrition.carbohydratesGramsPer100, 0),
  };
  return result.caloriesKcal === null &&
    result.proteinGrams === null &&
    result.fatGrams === null &&
    result.carbohydratesGrams === null
    ? null
    : result;
}

/** Whether any of the figures a customer reads about nutrition exists at all. */
export function hasNutrition(physical: PhysicalFacts | null | undefined): boolean {
  const n = physical?.nutrition;
  return (
    !!n &&
    [n.caloriesKcalPer100, n.proteinGramsPer100, n.fatGramsPer100, n.carbohydratesGramsPer100].some(
      (value) => value !== null && value !== undefined,
    )
  );
}

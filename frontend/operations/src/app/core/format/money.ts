/**
 * Money, and the one arithmetic mistake this codebase has already made in
 * production.
 *
 * ADR 0031: money is always an object, never a bare number —
 * `{ "amountMinor": 125000, "currency": "UZS" }`.
 *
 * **The bug, so nobody reintroduces it.** ISO 4217 assigns UZS two minor units,
 * and every `Intl` implementation agrees:
 *
 *     new Intl.NumberFormat('ru', { style: 'currency', currency: 'UZS' })
 *       .resolvedOptions().maximumFractionDigits   // => 2
 *
 * The platform does not store tiyin. It stores whole som, so `amountMinor` of
 * 125000 is one hundred and twenty-five thousand som. A formatter that asks
 * `Intl` how many decimal places UZS has, divides by 10^2, and formats the
 * result renders `1 250,00` — a bill of 125 000 som shown to a customer as
 * 1 250. That shipped last week.
 *
 * So this module never asks `Intl` for a currency's exponent. The exponents are
 * declared below, as data this platform owns, and an unknown currency throws
 * rather than guessing. Throwing on an unknown currency is deliberate: a wrong
 * price is worse than a missing one.
 */

import { Locale } from '../i18n/i18n';

export interface Money {
  /** Integer count of minor units. For UZS a minor unit is a whole som. */
  readonly amountMinor: number;
  /** ISO 4217 alphabetic code. */
  readonly currency: string;
}

/**
 * How many decimal places separate a minor unit from a major unit, **as this
 * platform stores them**.
 *
 * UZS is 0 and ISO 4217 says 2. The platform is what is being formatted, so the
 * platform wins. Adding a currency here without checking how the backend stores
 * it is how the bug comes back.
 */
const MINOR_UNIT_EXPONENT: Readonly<Record<string, number>> = {
  UZS: 0,
  USD: 2,
  EUR: 2,
  RUB: 2,
};

/**
 * U+00A0 NO-BREAK SPACE as the group separator.
 *
 * A plain space lets a browser break `146 000` across two lines in a dense
 * table, where the halves then read as two different numbers. `Intl`'s own
 * separator is not used because it varies by locale and by ICU version, and an
 * amount that groups differently between two operators' browsers is a support
 * call nobody can reproduce.
 */
const GROUP_SEPARATOR = ' ';

/**
 * The unit written after a total, per locale.
 *
 * Only after a total. `docs/operations-spec/orders.md` §1.3: rows carry the bare
 * number, because repeating the unit on forty rows is forty pieces of noise
 * between the operator and the one figure that differs.
 */
const UZS_UNIT: Readonly<Record<Locale, string>> = {
  ru: 'сум',
  'uz-Latn': 'soʻm',
  en: 'UZS',
};

export interface MoneyFormatOptions {
  /** Append the currency unit. Use on totals, not on rows. */
  readonly withUnit?: boolean;
}

/**
 * Formats an amount for display.
 *
 * Grouping is done here rather than by `Intl.NumberFormat` so that the output is
 * identical in every browser and every locale, which is what makes a column of
 * amounts scannable.
 */
export function formatMoney(
  money: Money,
  locale: Locale,
  options: MoneyFormatOptions = {},
): string {
  const exponent = minorUnitExponent(money.currency);

  if (!Number.isInteger(money.amountMinor)) {
    // A fractional minor unit means somebody did floating-point arithmetic on
    // money upstream. Rendering it rounded would hide that.
    throw new TypeError(
      `amountMinor must be an integer, got ${money.amountMinor} for ${money.currency}`,
    );
  }

  const negative = money.amountMinor < 0;
  const absolute = Math.abs(money.amountMinor);
  const digits = String(absolute).padStart(exponent + 1, '0');
  const majorDigits = exponent === 0 ? digits : digits.slice(0, -exponent);
  const minorDigits = exponent === 0 ? '' : digits.slice(-exponent);

  let text = group(majorDigits);
  if (exponent > 0) {
    text += `${decimalSeparator(locale)}${minorDigits}`;
  }
  if (negative) {
    // U+2212 MINUS SIGN, not a hyphen. In tabular figures a hyphen is narrower
    // than a digit and a column of negatives stops aligning.
    text = `−${text}`;
  }
  if (options.withUnit) {
    text += `${GROUP_SEPARATOR}${unitFor(money.currency, locale)}`;
  }
  return text;
}

/** The exponent this platform stores a currency at. Throws on an unknown code. */
export function minorUnitExponent(currency: string): number {
  const exponent = MINOR_UNIT_EXPONENT[currency];
  if (exponent === undefined) {
    throw new RangeError(
      `Unknown currency "${currency}". Add it to MINOR_UNIT_EXPONENT after confirming ` +
        `how the platform stores it — do not fall back to Intl, which reports 2 for UZS.`,
    );
  }
  return exponent;
}

function group(digits: string): string {
  return digits.replace(/\B(?=(\d{3})+(?!\d))/g, GROUP_SEPARATOR);
}

function decimalSeparator(locale: Locale): string {
  return locale === 'en' ? '.' : ',';
}

function unitFor(currency: string, locale: Locale): string {
  return currency === 'UZS' ? UZS_UNIT[locale] : currency;
}

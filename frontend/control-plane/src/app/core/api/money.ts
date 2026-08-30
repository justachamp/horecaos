/**
 * Money, and the one bug this file exists to prevent.
 *
 * ADR 0031 puts money on the wire as `{ amountMinor, currency }`. ISO 4217
 * gives UZS an exponent of 2 — a som is notionally a hundred tiyin — and the
 * platform does not: ADR 0018 stores whole som, so `amountMinor: 84000` is
 * eighty-four thousand som, not eight hundred and forty.
 *
 * A formatter that asks `Intl` how many decimal places a currency has divides
 * that by 100 and tells a customer the wrong price. That shipped. So the scale
 * comes from this table, which encodes what the platform stores, and never
 * from `Intl.NumberFormat`, which encodes what ISO says.
 *
 * An unknown currency throws. Guessing a scale is how the original bug got in.
 */

export interface Money {
  readonly amountMinor: number;
  readonly currency: string;
}

/**
 * Decimal places between the stored minor unit and the displayed amount, as the
 * platform stores it.
 *
 * UZS is 0 on purpose and disagrees with ISO 4217. Tiyin have not circulated
 * for decades and no HorecaOS price is ever expressed in them.
 */
const DISPLAY_DECIMALS: Readonly<Record<string, number>> = {
  UZS: 0,
  USD: 2,
  EUR: 2,
  RUB: 2,
};

export class UnknownCurrencyError extends Error {
  constructor(currency: string) {
    super(
      `No display scale is declared for ${currency}. Add it to DISPLAY_DECIMALS ` +
        `with the scale the platform stores, not the scale ISO 4217 publishes.`,
    );
    this.name = 'UnknownCurrencyError';
  }
}

/**
 * Groups an integer in threes with spaces — `1234567` becomes `1 234 567`.
 *
 * Not `Intl.NumberFormat`: it groups with a comma in en, a non-breaking space
 * in ru, and an apostrophe in some locales, and the design system says the
 * separator is a space on every surface in every locale so that a column of
 * numbers lines up on the same digit boundaries whoever is reading it.
 */
export function groupDigits(whole: string): string {
  return whole.replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
}

/**
 * Renders the amount without its currency, e.g. `84 000` or `1 250,75`.
 *
 * The decimal separator is a comma, which is what ru and uz-Latn both read.
 */
export function formatAmount(money: Money): string {
  const decimals = DISPLAY_DECIMALS[money.currency];
  if (decimals === undefined) {
    throw new UnknownCurrencyError(money.currency);
  }

  const negative = money.amountMinor < 0;
  const digits = Math.abs(money.amountMinor).toString().padStart(decimals + 1, '0');
  const whole = decimals === 0 ? digits : digits.slice(0, digits.length - decimals);
  const fraction = decimals === 0 ? '' : `,${digits.slice(digits.length - decimals)}`;

  // U+2212 minus, not a hyphen: at tabular widths a hyphen is too short to read
  // as a sign in a column of figures.
  return `${negative ? '−' : ''}${groupDigits(whole)}${fraction}`;
}

/** True when the two amounts are the same money, not merely the same number. */
export function sameCurrency(a: Money, b: Money): boolean {
  return a.currency === b.currency;
}

export function addMoney(a: Money, b: Money): Money {
  if (!sameCurrency(a, b)) {
    throw new Error(`Cannot add ${a.currency} to ${b.currency}`);
  }
  return { amountMinor: a.amountMinor + b.amountMinor, currency: a.currency };
}

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
 * A screen that renders an amount the server handed it, in whatever currency
 * the tenant holds, asks {@link hasDisplayScale} first and falls back to
 * {@link formatMinorUnits} — unscaled, and labelled as such — rather than
 * throwing where a reader is.
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
  const digits = Math.abs(money.amountMinor)
    .toString()
    .padStart(decimals + 1, '0');
  const whole = decimals === 0 ? digits : digits.slice(0, digits.length - decimals);
  const fraction = decimals === 0 ? '' : `,${digits.slice(digits.length - decimals)}`;

  // U+2212 minus, not a hyphen: at tabular widths a hyphen is too short to read
  // as a sign in a column of figures.
  return `${negative ? '−' : ''}${groupDigits(whole)}${fraction}`;
}

/**
 * Whether this currency has a declared display scale, so {@link formatAmount}
 * will render it rather than throw.
 *
 * <p>A screen that renders an amount it was handed — rather than one the
 * operator typed into a form this console controls — has to ask. Tenant
 * currency is `^[A-Z]{3}$` in the database and anything `java.util.Currency`
 * accepts in the service, and the platform's own market list already declares
 * KZ/KZT and GE/GEL, none of which is in the table above.
 */
export function hasDisplayScale(currency: string): boolean {
  return DISPLAY_DECIMALS[currency] !== undefined;
}

/**
 * Renders stored minor units as they are stored, grouped and signed, with no
 * scale applied at all — `−50000000`.
 *
 * <p>For a currency this console has no declared scale for. The alternative is
 * not "render it nicely": it is either guessing a scale, which is the bug the
 * head of this file exists to prevent, or throwing where a reader is, which on
 * the approvals queue truncates the table at the offending row and takes the
 * four-eyes control down with it.
 *
 * <p>Callers must label the result as unscaled. `−50000000 KZT` read as though
 * it were scaled is a hundredfold misreading of a figure somebody is about to
 * sign, which is worse than a row that will not draw.
 */
export function formatMinorUnits(money: Money): string {
  const negative = money.amountMinor < 0;
  return `${negative ? '−' : ''}${groupDigits(Math.abs(money.amountMinor).toString())}`;
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

/** The currencies a price may be entered in: the ones with a declared scale. */
export const ENTRY_CURRENCIES: readonly string[] = Object.keys(DISPLAY_DECIMALS);

/**
 * Reads an amount typed the way the screen shows it -- `9 000 000`, `12,50` or
 * `12.50` -- into stored minor units. Null when it is not a non-negative
 * amount with at most the currency's own decimal places.
 *
 * The inverse of {@link formatAmount}, from the same table: `9 000 000` typed
 * against UZS is stored as 9000000 and never multiplied by a hundred.
 */
export function parseAmount(text: string, currency: string): number | null {
  const decimals = DISPLAY_DECIMALS[currency];
  if (decimals === undefined) {
    throw new UnknownCurrencyError(currency);
  }
  const compact = text.replace(/\s/g, '').replace(',', '.');
  const pattern = decimals === 0 ? /^\d+$/ : new RegExp(`^\\d+(\\.\\d{1,${decimals}})?$`);
  if (!pattern.test(compact)) {
    return null;
  }
  const [whole, fraction = ''] = compact.split('.');
  const minor = Number(whole) * 10 ** decimals + Number(fraction.padEnd(decimals, '0') || '0');
  return Number.isSafeInteger(minor) ? minor : null;
}

/**
 * Reads an amount that may be negative -- a wallet correction taking money
 * away (ADR 0095, item 4) -- into signed minor units.
 *
 * Separate from {@link parseAmount} rather than an option on it, because every
 * other form that reads an amount is reading a price, an overage rate or a
 * deposit, and for those a minus sign is a typing mistake that must stay
 * refused. Only the field whose own placeholder asks for a sign reads one.
 *
 * Both signs {@link formatAmount} could have produced are accepted: the U+2212
 * it actually emits, so an operator may copy a figure straight out of the
 * ledger to reverse it, and the ASCII hyphen their keyboard gives them. The
 * magnitude is still {@link parseAmount}'s, so `--5`, `+5` and a bare sign are
 * all null. `-0` reads as 0, which is a sign of intent rather than an amount,
 * so a caller's own refusal of zero is still the thing that stops it.
 */
export function parseSignedAmount(text: string, currency: string): number | null {
  const signed = text.trim().replace('−', '-');
  const negative = signed.startsWith('-');
  const magnitude = parseAmount(negative ? signed.slice(1) : signed, currency);
  if (magnitude === null) {
    return null;
  }
  return negative ? -magnitude : magnitude;
}

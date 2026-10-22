import type { LocaleCode } from './i18n/locales';

/**
 * Times and dates for display, in the branch's zone and the courier's language.
 *
 * Intl, not hand-built strings: the order of day and month, the digits and the 24-hour clock all
 * differ between ru, uz-Latn and en, and `timeZone` is what keeps a phone set to another zone
 * from showing a shift that opened "at 03:00".
 */
export function formatTime(iso: string, locale: LocaleCode, timeZone: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  return new Intl.DateTimeFormat(locale, {
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
    timeZone,
  }).format(date);
}

/** A calendar date such as `2027-01-31`, which has no zone to convert from. */
export function formatDate(isoDate: string, locale: LocaleCode): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(isoDate);
  if (match === null) {
    return '';
  }
  const date = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])));
  return new Intl.DateTimeFormat(locale, { dateStyle: 'long', timeZone: 'UTC' }).format(date);
}

/** Hours and zero-padded minutes (`8:05`): a duration a courier reads at a glance, without a unit word to translate. */
export function formatDuration(totalSeconds: number): string {
  const minutes = Math.floor(Math.max(0, totalSeconds) / 60);
  return `${Math.floor(minutes / 60)}:${String(minutes % 60).padStart(2, '0')}`;
}

/** Metres under a kilometre, kilometres above, in the courier's number format. */
export function formatDistance(meters: number, locale: LocaleCode): string {
  if (meters < 1_000) {
    return `${new Intl.NumberFormat(locale, { maximumFractionDigits: 0 }).format(meters)} ${UNIT[locale].metres}`;
  }
  const kilometres = meters / 1_000;
  return `${new Intl.NumberFormat(locale, {
    maximumFractionDigits: kilometres < 10 ? 1 : 0,
  }).format(kilometres)} ${UNIT[locale].kilometres}`;
}

const UNIT: Readonly<Record<LocaleCode, { metres: string; kilometres: string }>> = {
  ru: { metres: 'м', kilometres: 'км' },
  'uz-Latn': { metres: 'm', kilometres: 'km' },
  en: { metres: 'm', kilometres: 'km' },
};

/**
 * How many minor units make one major unit, from the currency itself (2 for UZS and USD, 0 for
 * JPY). Money on the wire is integer minor units; this is what keeps a screen from showing 100
 * times too much, or too little, for a currency that does not follow the usual two decimals.
 */
export function minorUnitsOf(currency: string): number {
  try {
    return new Intl.NumberFormat('en', { style: 'currency', currency }).resolvedOptions()
      .maximumFractionDigits as number;
  } catch {
    return 2;
  }
}

/**
 * An amount for display. The decimal is built from the integer by string, never by dividing a
 * float, and handed to Intl as an exact decimal, so a large amount is shown as it is held.
 */
export function formatMoney(minor: number, currency: string, locale: LocaleCode): string {
  const exponent = minorUnitsOf(currency);
  const negative = minor < 0;
  const digits = String(Math.abs(Math.trunc(minor))).padStart(exponent + 1, '0');
  const whole = digits.slice(0, digits.length - exponent);
  const fraction = digits.slice(digits.length - exponent);
  const decimal = `${negative ? '-' : ''}${whole}${exponent > 0 ? `.${fraction}` : ''}`;
  return new Intl.NumberFormat(locale, { style: 'currency', currency }).format(
    decimal as unknown as number,
  );
}

/**
 * Reads what a courier typed as an amount of major units and returns integer minor units, or null
 * when it is not an amount of this currency: a decimal comma is accepted, spaces are thousands
 * separators, and nothing else is -- a sign, an exponent, a second point or too many decimals is
 * refused so nothing odd reaches the platform. Done on digits, not floats (`0.29 * 100` is not 29).
 */
export function parseMinorUnits(text: string, currency: string): number | null {
  const exponent = minorUnitsOf(currency);
  const cleaned = text.replace(/[\s  ]/g, '').replace(',', '.');
  const match = /^(\d+)(?:\.(\d+))?$/.exec(cleaned);
  if (match === null) {
    return null;
  }
  const fraction = match[2] ?? '';
  if (fraction.length > exponent) {
    return null;
  }
  const minor = Number(`${match[1]}${fraction.padEnd(exponent, '0')}`);
  return Number.isSafeInteger(minor) ? minor : null;
}

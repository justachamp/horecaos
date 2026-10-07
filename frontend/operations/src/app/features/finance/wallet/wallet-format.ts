import { formatDate, formatDateTime } from '../../../core/format/datetime';
import { Money, formatMoney, minorUnitExponent } from '../../../core/format/money';
import { Locale } from '../../../core/i18n/i18n';

/**
 * The zone the wallet's dates are read in. A tenant-scope screen has no brand or
 * location to take a zone from, so it follows every other tenant-wide screen in
 * this console (`customers-page.ts`, `inbox-list.ts`) and reads Tashkent.
 */
export const WALLET_TIME_ZONE = 'Asia/Tashkent';

/**
 * An amount with its unit, or the stored figure unscaled when this console has no
 * scale for the currency.
 *
 * `formatMoney` throws on a currency it does not know, on purpose: a wrong price
 * is worse than a missing one. A wallet is held in the tenant's billing currency,
 * which may be any the platform accepts, and a throw inside a template takes the
 * whole screen down with it. So the unknown case falls back to the stored minor
 * units and the code, which is honest about being unscaled and cannot be misread
 * as a scaled figure (control-plane's `moneyOrRaw` makes the same choice).
 */
export function walletMoney(money: Money, locale: Locale): string {
  try {
    return formatMoney(money, locale, { withUnit: true });
  } catch {
    return `${money.amountMinor} ${money.currency}`;
  }
}

/** Whether this console can scale a currency, and so can both show and accept an amount in it. */
export function currencyIsScaled(currency: string): boolean {
  try {
    minorUnitExponent(currency);
    return true;
  } catch {
    return false;
  }
}

/**
 * Reads an amount typed the way the screen shows it -- `150 000`, or `12,50` /
 * `12.50` for a currency with decimals -- into stored minor units.
 *
 * Null when it is not a positive amount with at most the currency's own decimal
 * places, or when the currency has no declared scale. The scale comes from
 * `money.ts`'s own table, never from a guess: `150 000` against UZS is stored as
 * 150000 and is never multiplied by a hundred.
 */
export function parseAmountInput(text: string, currency: string): number | null {
  let exponent: number;
  try {
    exponent = minorUnitExponent(currency);
  } catch {
    return null;
  }
  const compact = text.replace(/[\s  ]/g, '').replace(',', '.');
  const pattern = exponent === 0 ? /^\d+$/ : new RegExp(`^\\d+(\\.\\d{1,${exponent}})?$`);
  if (!pattern.test(compact)) {
    return null;
  }
  const [whole, fraction = ''] = compact.split('.');
  const minor = Number(whole) * 10 ** exponent + Number(fraction.padEnd(exponent, '0') || '0');
  return Number.isSafeInteger(minor) && minor > 0 ? minor : null;
}

/** `DD.MM.YYYY` of an instant, or an em dash for a missing one. */
export function walletDate(instant: string | null): string {
  if (instant === null) {
    return '—';
  }
  const parsed = new Date(instant);
  return Number.isNaN(parsed.getTime()) ? instant : formatDate(parsed, WALLET_TIME_ZONE);
}

/** `DD.MM HH:mm` of an instant, or an em dash for a missing one. */
export function walletDateTime(instant: string | null): string {
  if (instant === null) {
    return '—';
  }
  const parsed = new Date(instant);
  return Number.isNaN(parsed.getTime()) ? instant : formatDateTime(parsed, WALLET_TIME_ZONE);
}

/** `MM/YYYY`, the way a card shows its expiry. */
export function cardExpiry(month: number | null, year: number | null): string | null {
  if (month === null || year === null) {
    return null;
  }
  return `${String(month).padStart(2, '0')}/${year}`;
}

/** Only an `https:` URL is ever offered as a link: a provider's form is never reached over anything else. */
export function safeFormUrl(url: string | null): string | null {
  if (url === null) {
    return null;
  }
  try {
    return new URL(url).protocol === 'https:' ? url : null;
  } catch {
    return null;
  }
}

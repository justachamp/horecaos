/**
 * How this brand's operators read money and phone numbers (Settings 10.12).
 *
 * `formatMoney`, `formatCount` and `formatPhone` are called from a hundred
 * templates and computeds, none of which knows which brand it is showing. So
 * the brand's choice lives here, once, as a signal the formatters read:
 * `RegionalFormatSync` writes it when the brand resolves and the brand-profile
 * screen writes it when the operator saves, and every screen that calls a
 * formatter inside a `computed` or a template re-renders on its own, because a
 * signal read inside one is a dependency.
 *
 * **The default is what the console did before this was configurable** — the
 * unit after a total, groups of three separated by a no-break space, a phone
 * as it arrives — so a brand that has chosen nothing, and every spec that
 * never sets anything, sees no change.
 *
 * Nothing here is stored or sent anywhere: it is display only. Amounts stay
 * integer minor units plus a currency, and a phone stays E.164, on the wire.
 */

import { signal } from '@angular/core';

import type { Locale } from '../i18n/i18n';

/** Where the currency unit is written on a total; a row carries the bare number either way. */
export type MoneySymbolPlacement = 'BEFORE' | 'AFTER';

/** How the thousands of an amount are separated. */
export type MoneyGrouping = 'SPACE' | 'COMMA' | 'DOT' | 'NONE';

export const MONEY_SYMBOL_PLACEMENTS: readonly MoneySymbolPlacement[] = ['AFTER', 'BEFORE'];
export const MONEY_GROUPINGS: readonly MoneyGrouping[] = ['SPACE', 'COMMA', 'DOT', 'NONE'];

/** `TenantControlPlaneService.RegionalFormatsView`, and what the formatters read. */
export interface RegionalFormats {
  readonly moneySymbolPlacement: MoneySymbolPlacement;
  readonly moneyGrouping: MoneyGrouping;
  /** One `#` per digit with `+ ( ) - .` and spaces kept as written; `null` shows a number as it arrives. */
  readonly phoneDisplayPattern: string | null;
}

export const DEFAULT_REGIONAL_FORMATS: RegionalFormats = {
  moneySymbolPlacement: 'AFTER',
  moneyGrouping: 'SPACE',
  phoneDisplayPattern: null,
};

/**
 * U+00A0 NO-BREAK SPACE, the default group separator and the gap between an
 * amount and its unit.
 *
 * A plain space lets a browser break `146 000` across two lines in a dense
 * table, where the halves then read as two different numbers. `Intl`'s own
 * separator is not used because it varies by locale and by ICU version, and an
 * amount that groups differently between two operators' browsers is a support
 * call nobody can reproduce.
 */
export const NO_BREAK_SPACE = '\u00a0';

/**
 * What a platform reply becomes: a value this console can render, whatever the
 * platform sent. An older platform sends nothing, a newer one may send a value
 * this build has not heard of; both read as the default rather than as a screen
 * that cannot show a price.
 */
export function normalizeRegionalFormats(
  raw: Partial<Record<keyof RegionalFormats, string | null>> | null | undefined,
): RegionalFormats {
  const placement = MONEY_SYMBOL_PLACEMENTS.find(
    (candidate) => candidate === raw?.moneySymbolPlacement,
  );
  const grouping = MONEY_GROUPINGS.find((candidate) => candidate === raw?.moneyGrouping);
  const pattern = raw?.phoneDisplayPattern;
  return {
    moneySymbolPlacement: placement ?? DEFAULT_REGIONAL_FORMATS.moneySymbolPlacement,
    moneyGrouping: grouping ?? DEFAULT_REGIONAL_FORMATS.moneyGrouping,
    phoneDisplayPattern:
      typeof pattern === 'string' && pattern.trim() !== '' ? pattern.trim() : null,
  };
}

const active = signal<RegionalFormats>(DEFAULT_REGIONAL_FORMATS);

/** The formats the formatters use right now. Reading it inside a `computed` or a template makes that a dependency. */
export function activeRegionalFormats(): RegionalFormats {
  return active();
}

/** Makes a brand's formats the ones every formatter uses; anything this console cannot render reads as the default. */
export function applyRegionalFormats(
  raw: Partial<Record<keyof RegionalFormats, string | null>> | null | undefined,
): void {
  active.set(normalizeRegionalFormats(raw));
}

/** Back to the defaults — sign-out, and a spec's `afterEach`. */
export function resetRegionalFormats(): void {
  active.set(DEFAULT_REGIONAL_FORMATS);
}

/** The character written between groups of three digits. */
export function groupSeparator(grouping: MoneyGrouping): string {
  switch (grouping) {
    case 'SPACE':
      return NO_BREAK_SPACE;
    case 'COMMA':
      return ',';
    case 'DOT':
      return '.';
    case 'NONE':
      return '';
  }
}

/**
 * The decimal separator that goes with a grouping.
 *
 * Russian and Uzbek write a decimal comma and English a decimal point — unless
 * the group separator is that very character, and `1,250,00` or `1.250.00` is a
 * different number to the eye. So a comma grouping forces a point and a dot
 * grouping forces a comma, whatever the locale, and the two can never collide.
 */
export function decimalSeparatorFor(locale: Locale, grouping: MoneyGrouping): string {
  if (grouping === 'COMMA') {
    return '.';
  }
  if (grouping === 'DOT') {
    return ',';
  }
  return locale === 'en' ? '.' : ',';
}

/** Groups a string of digits by three, right to left, with the brand's separator. */
export function groupDigits(
  digits: string,
  grouping: MoneyGrouping = activeRegionalFormats().moneyGrouping,
): string {
  return digits.replace(/\B(?=(\d{3})+(?!\d))/g, groupSeparator(grouping));
}

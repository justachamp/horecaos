import { Pipe, PipeTransform, inject } from '@angular/core';

import { TranslateService } from '../../services/translate.service';

/**
 * The wire shape of money (ADR 0031): integer minor units and a currency, never
 * a bare number.
 */
export interface Money {
  readonly amountMinor: number;
  readonly currency: string;
}

export function money(amountMinor: number, currency: string): Money {
  return { amountMinor, currency };
}

/**
 * How many minor units make one major unit, as **this platform** stores them.
 *
 * This table exists because ISO 4217 disagrees with the platform about UZS and
 * `Intl` believes ISO 4217. `Intl.NumberFormat(l, {style: 'currency', currency:
 * 'UZS'})` divides by 100 and renders `840.00`, and ADR 0018 stores whole som:
 * 84000 minor units is 84 000 som, not 840. That exact bug shipped in this
 * codebase, so nothing here may ask Intl what the scale of a currency is.
 *
 * An unlisted currency is not guessed. It is rendered at zero decimals with its
 * ISO code attached, which is visibly odd rather than quietly wrong.
 */
const MINOR_UNIT_DIGITS: Readonly<Record<string, number>> = {
  UZS: 0,
  USD: 2,
  EUR: 2,
  RUB: 2,
};

/** U+00A0. A plain space would let a total wrap across two lines mid-number. */
const GROUP_SEPARATOR = ' ';

const DECIMAL_SEPARATOR = ',';

export function minorUnitDigits(currency: string): number {
  return MINOR_UNIT_DIGITS[currency] ?? 0;
}

/**
 * Renders the amount without a unit: `84 000`, `-1 250,50`.
 *
 * Grouping is done here rather than by `Intl.NumberFormat` so the output does
 * not change when the runtime's ICU data does — a formatter whose result depends
 * on the browser version cannot be asserted in a test.
 */
export function formatAmount(value: Money): string {
  const digits = minorUnitDigits(value.currency);
  const negative = value.amountMinor < 0;
  const magnitude = Math.abs(Math.trunc(value.amountMinor))
    .toString()
    .padStart(digits + 1, '0');

  const whole = digits === 0 ? magnitude : magnitude.slice(0, magnitude.length - digits);
  const fraction = digits === 0 ? '' : magnitude.slice(magnitude.length - digits);

  const grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, GROUP_SEPARATOR);
  const rendered = fraction ? `${grouped}${DECIMAL_SEPARATOR}${fraction}` : grouped;

  return negative ? `−${rendered}` : rendered;
}

/**
 * Renders the amount with its unit as a customer reads it: `84 000 so'm`.
 *
 * @param unitLabel the localised unit. Callers inside a component should use
 *        {@link MoneyPipe}, which reads it from the active catalogue.
 */
export function formatMoney(value: Money, unitLabel: string): string {
  return `${formatAmount(value)}${GROUP_SEPARATOR}${unitLabel}`;
}

/** Same currency, added exactly. Mixing currencies is a programming error. */
export function addMoney(left: Money, right: Money): Money {
  if (left.currency !== right.currency) {
    throw new Error(`Cannot add ${left.currency} to ${right.currency}`);
  }
  return { amountMinor: left.amountMinor + right.amountMinor, currency: left.currency };
}

@Pipe({ name: 'money', pure: false })
export class MoneyPipe implements PipeTransform {
  private readonly translate = inject(TranslateService);

  transform(value: Money | null | undefined): string {
    if (!value) {
      return '';
    }
    const key = `money.unit.${value.currency}`;
    // `TranslateService.get` answers with the key itself when it is missing. An
    // unlisted currency therefore falls back to its ISO code rather than to a
    // wrong unit borrowed from another currency.
    const unit = this.translate.get(key);
    return formatMoney(value, unit === key ? value.currency : unit);
  }
}

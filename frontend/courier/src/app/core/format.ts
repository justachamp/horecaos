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

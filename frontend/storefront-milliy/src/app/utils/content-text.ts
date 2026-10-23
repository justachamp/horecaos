import { platformTag } from '../services/lang.service';

/**
 * A BCP 47 language tag fit to put in a `lang` attribute, or null.
 *
 * Content says which language its texts are in (`textLocale`), and it differs from the page's when
 * the brand had no translation: a screen reader then reads it in its own voice. The value is
 * text a brand's document supplied, so only a plausible tag is passed on.
 */
export function languageTag(value: string | null | undefined): string | null {
  return typeof value === 'string' && /^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8}){0,3}$/.test(value)
    ? value
    : null;
}

/** The first letter of a title, for a round avatar with no picture; empty for an empty title. */
export function initialOf(title: string): string {
  const first = Array.from(title.trim())[0];
  return first ? first.toLocaleUpperCase() : '';
}

const UZ_MONTHS = [
  'yanvar',
  'fevral',
  'mart',
  'aprel',
  'may',
  'iyun',
  'iyul',
  'avgust',
  'sentabr',
  'oktabr',
  'noyabr',
  'dekabr',
] as const;

/**
 * A publication date as the reader's language writes it ("1 октября 2026 г."), from an ISO-8601
 * instant; null when it is not a date, so the caller shows no date rather than "Invalid Date".
 *
 * Uzbek is written here and not asked of the browser: the month names for Uzbek are missing from
 * the locale data of some WebViews and shells, which answer "2026 M10 1". The storefront runs in
 * exactly such WebViews (Telegram's), and the text is short enough to own.
 */
export function formatDate(iso: string, language: string): string | null {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return null;
  }
  if (platformTag(language) === 'uz-Latn') {
    return `${date.getDate()}-${UZ_MONTHS[date.getMonth()]}, ${date.getFullYear()}`;
  }
  try {
    return new Intl.DateTimeFormat(language, { dateStyle: 'long' }).format(date);
  } catch {
    return new Intl.DateTimeFormat('en', { dateStyle: 'long' }).format(date);
  }
}

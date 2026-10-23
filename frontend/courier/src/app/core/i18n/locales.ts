/**
 * The three languages the courier app speaks, and how one is chosen.
 *
 * Russian is the default because it is the platform's own (the staff consoles open in it) and the
 * working language of most dispatch conversations; Uzbek is written in Latin script, which is the
 * official one and the one couriers read on a phone.
 */
export type LocaleCode = 'ru' | 'uz-Latn' | 'en';

export interface LocaleDescriptor {
  readonly code: LocaleCode;
  /** The language's own name for itself, shown in the switcher whatever language is active. */
  readonly label: string;
  /** The BCP 47 tag for `<html lang>`. */
  readonly htmlLang: string;
}

export const LOCALES: readonly LocaleDescriptor[] = [
  { code: 'ru', label: 'Русский', htmlLang: 'ru' },
  { code: 'uz-Latn', label: 'Oʻzbekcha', htmlLang: 'uz-Latn' },
  { code: 'en', label: 'English', htmlLang: 'en' },
];

export const DEFAULT_LOCALE: LocaleCode = 'ru';

/** Accepts a stored or typed value only if it is exactly one of ours. */
export function parseLocale(value: unknown): LocaleCode | null {
  return LOCALES.some((locale) => locale.code === value) ? (value as LocaleCode) : null;
}

/**
 * The best of ours for a browser's language list, or the default.
 *
 * `uz` in any script maps to Uzbek-Latin: a phone configured for Cyrillic Uzbek still gets the
 * Latin catalogue, which is the only Uzbek one there is.
 */
export function localeFromBrowser(languages: readonly string[]): LocaleCode {
  for (const language of languages) {
    const primary = language.toLowerCase().split('-')[0];
    if (primary === 'ru') {
      return 'ru';
    }
    if (primary === 'uz') {
      return 'uz-Latn';
    }
    if (primary === 'en') {
      return 'en';
    }
  }
  return DEFAULT_LOCALE;
}

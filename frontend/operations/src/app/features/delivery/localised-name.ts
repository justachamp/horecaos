import { Locale } from '../../core/i18n/i18n';

/**
 * The name to show for a row that carries all three of them.
 *
 * `fulfillment.service_zones` and `fulfillment.regions` both store
 * `display_name_ru`, `display_name_uz` and `display_name_en` as three NOT NULL
 * columns rather than a translations table, so every row has all three and
 * there is nothing to fall back *from*. What there is to fall back from is a
 * blank one: the console asks for three names and an operator can still paste a
 * space, and a row that renders as an empty cell is worse than one that renders
 * in the wrong language. Russian is the last resort because it is the console's
 * own default locale (ADR 0035).
 */
export function localisedName(
  locale: Locale,
  names: {
    readonly displayNameRu: string;
    readonly displayNameUz: string;
    readonly displayNameEn: string;
  },
): string {
  const preferred =
    locale === 'uz-Latn'
      ? names.displayNameUz
      : locale === 'en'
        ? names.displayNameEn
        : names.displayNameRu;
  return (
    firstNonBlank(preferred, names.displayNameRu, names.displayNameUz, names.displayNameEn) ?? ''
  );
}

function firstNonBlank(...candidates: readonly string[]): string | null {
  for (const candidate of candidates) {
    if (candidate.trim().length > 0) {
      return candidate;
    }
  }
  return null;
}

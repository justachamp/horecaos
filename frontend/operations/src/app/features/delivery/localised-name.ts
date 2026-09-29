import { Locale } from '../../core/i18n/i18n';
import { LabelsByLocale, pickLabel } from '../../core/i18n/locale-labels';

/**
 * The name to show for a row that carries a name per locale.
 *
 * `fulfillment.service_zones` and `fulfillment.regions` store
 * `display_name_ru`, `display_name_uz` and `display_name_en` as three NOT NULL
 * columns, so every row has all three and there is nothing to fall back *from*;
 * since row 10.12 (V0431) the server also answers `displayNames` — those three
 * merged with any other language the row is named in, each once. This prefers
 * `displayNames` when the response carries it and falls to the three columns
 * when it does not (a response that predates it).
 *
 * What there is to fall back from is a blank one: the console asks for names
 * and an operator can still paste a space, and a row that renders as an empty
 * cell is worse than one that renders in the wrong language. Russian is the
 * last resort because it is the console's own default locale (ADR 0035).
 */
export function localisedName(
  locale: Locale,
  names: {
    readonly displayNameRu: string;
    readonly displayNameUz: string;
    readonly displayNameEn: string;
    readonly displayNames?: LabelsByLocale;
  },
): string {
  const byLocale: LabelsByLocale = {
    ...(names.displayNames ?? {}),
    // The columns are the source for the platform triple; a blank one must not
    // shadow a non-blank name the map carries for the same locale.
    ...nonBlank({
      ru: names.displayNameRu,
      'uz-Latn': names.displayNameUz,
      en: names.displayNameEn,
    }),
  };
  return pickLabel(byLocale, locale, ['ru', 'uz-Latn', 'en']);
}

function nonBlank(labels: LabelsByLocale): LabelsByLocale {
  return Object.fromEntries(Object.entries(labels).filter(([, value]) => value.trim().length > 0));
}

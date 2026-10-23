import { LabelsByLocale, pickLabel } from '../../core/i18n/locale-labels';

/**
 * The name to show for a row that carries a name per locale.
 *
 * Since V0679 (ADR 0149, row 10.12) a region, a service zone and the dispatch editor's zone options carry
 * their name as `displayNames` / `names`, one entry per language, and nothing else: there is no triple to fall
 * back to and no language that is always present. What there is to fall back from is a language the row is
 * not named in, or a blank one — the console asks for names and an operator can still paste a space, and a
 * row that renders as an empty cell is worse than one that renders in the wrong language. Russian is the last
 * resort because it is the console's own default locale (ADR 0035); `fallbackOrder` is the registry's order
 * (`PlatformLocales.fallbackOrder()`, ADR 0149), not a list kept here.
 */
export function localisedName(
  locale: string,
  names: { readonly displayNames?: LabelsByLocale },
  fallbackOrder: readonly string[],
): string {
  return pickLabel(nonBlank(names.displayNames ?? {}), locale, fallbackOrder);
}

function nonBlank(labels: LabelsByLocale): LabelsByLocale {
  return Object.fromEntries(Object.entries(labels).filter(([, value]) => value.trim().length > 0));
}

import { DEFAULT_LOCALE, I18n, LOCALES } from './i18n';

/**
 * Wording keyed by locale tag — `ru`, `uz-Latn`, `en`, or any other well-formed
 * tag a tenant's brands come to support. A plain string key rather than the
 * closed {@link Locale} union on purpose: the per-locale translations tables
 * (V0430, V0431) exist so a fourth language needs no migration, and a screen
 * that dropped an unknown key from a response would delete it on the next save.
 */
export type LabelsByLocale = Readonly<Record<string, string>>;

/**
 * The languages a *tenant-scoped* vocabulary (preset product comments,
 * regions) is edited in — what `GET .../locale-set` answers (row 10.12).
 *
 * Decision, mirrored from `TenantLocaleSet` on the server: the union of the
 * tenant's brands' supported locales, default first, the default being the
 * tenant's *first* brand's. A menu, not a constraint — a language outside it
 * that a row already carries is kept, never deleted, by an edit.
 */
export interface LocaleSetView {
  readonly locales: readonly string[];
  readonly defaultLocale: string;
  /** Whether at least one brand has chosen its own set (as opposed to the whole tenant on the platform fallback). */
  readonly configured: boolean;
}

/** What an editor uses before the set has loaded, and when it cannot be read: the platform triple, `ru` first. */
export const PLATFORM_LOCALE_SET: LocaleSetView = {
  locales: LOCALES,
  defaultLocale: DEFAULT_LOCALE,
  configured: false,
};

/** One editable field per offered locale, prefilled with the wording the row already has. */
export function labelDrafts(
  locales: readonly string[],
  existing: LabelsByLocale | null | undefined,
): Record<string, string> {
  return Object.fromEntries(locales.map((locale) => [locale, existing?.[locale] ?? '']));
}

/**
 * The wording an edit sends: only the offered locales, and only those the
 * operator filled in.
 *
 * This is the *client half* of the never-delete guarantee (the server's write
 * touches exactly the locales named — `CommentPresetService`, `RegionService`,
 * `ServiceZoneService`): a locale the editor does not offer is never in the
 * body, and a blank field is left out rather than sent as an empty string, so
 * neither can blank the wording the row already carries.
 */
export function labelsToSend(
  locales: readonly string[],
  drafts: LabelsByLocale,
): Record<string, string> {
  const sent: Record<string, string> = {};
  for (const locale of locales) {
    const value = (drafts[locale] ?? '').trim();
    if (value.length > 0) {
      sent[locale] = value;
    }
  }
  return sent;
}

/**
 * The wording an edit of an <em>unversioned</em> row sends: {@link labelsToSend}'s
 * locales, minus every one whose text is what the editor loaded.
 *
 * A dialog prefills every offered locale from a list response it may have held
 * for a while; sending them all back writes each untouched one over whatever
 * another operator saved since, with no conflict to say so (the zone rename has
 * no version to check). Naming only what the operator actually changed means a
 * concurrent edit of a different language survives.
 */
export function changedLabels(
  locales: readonly string[],
  drafts: LabelsByLocale,
  loaded: LabelsByLocale | null | undefined,
): Record<string, string> {
  const sent = labelsToSend(locales, drafts);
  return Object.fromEntries(
    Object.entries(sent).filter(([locale, value]) => value !== (loaded?.[locale] ?? '').trim()),
  );
}

/** The platform triple's wording, as the three columns a request must still carry. */
export interface PlatformColumns {
  readonly ru: string;
  readonly 'uz-Latn': string;
  readonly en: string;
}

/**
 * The three platform columns a create or a rewrite must carry.
 *
 * The OpenAPI contract keeps `labelRu`/`labelUz`/`labelEn` (and the regions'
 * and zones' `displayName*`) *required* — it refuses to make a published
 * required request field optional — so an editor that offers only some of the
 * platform languages still has to name all three. For each one:
 *
 * 1. an offered language the operator filled in → that wording;
 * 2. else, on an edit, the wording the row already has — a language the
 *    editor does not offer is round-tripped **unchanged**, never blanked or
 *    replaced (this is the client half of row 10.12's never-delete guarantee
 *    for the triple; a language beyond it is simply left out of `labels` and
 *    the server keeps it);
 * 3. else (a create) → the wording in the default language, which the form
 *    has already required — the same fallback the server applies to a service
 *    caller that leaves one out.
 */
export function platformColumns(
  offered: readonly string[],
  drafts: LabelsByLocale,
  defaultLocale: string,
  existing?: PlatformColumns | null,
): PlatformColumns {
  const typed = (locale: 'ru' | 'uz-Latn' | 'en'): string =>
    offered.includes(locale) ? (drafts[locale] ?? '').trim() : '';
  const fallback = (drafts[defaultLocale] ?? '').trim();
  return {
    ru: typed('ru') || existing?.ru || fallback,
    'uz-Latn': typed('uz-Latn') || existing?.['uz-Latn'] || fallback,
    en: typed('en') || existing?.en || fallback,
  };
}

/** The locale's name for an operator: "Russian", "Uzbek (Latin)", "English" — or the bare tag for one this console has no name for. */
export function localeDisplayName(i18n: I18n, locale: string): string {
  switch (locale) {
    case 'ru':
      return i18n.t('settings.brandProfile.locale.ru');
    case 'uz-Latn':
      return i18n.t('settings.brandProfile.locale.uzLatn');
    case 'en':
      return i18n.t('settings.brandProfile.locale.en');
    default:
      return locale;
  }
}

/**
 * A row's name to show in the operator's own language, falling back through the
 * tenant's default and then any wording at all — so a row never renders as an
 * empty cell because one language is missing.
 */
export function pickLabel(
  labels: LabelsByLocale,
  preferred: string,
  fallbacks: readonly string[],
): string {
  for (const locale of [preferred, ...fallbacks]) {
    const value = labels[locale];
    if (value && value.trim().length > 0) {
      return value;
    }
  }
  return Object.values(labels).find((value) => value.trim().length > 0) ?? '';
}

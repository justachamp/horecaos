import { I18n, LOCALES } from './i18n';
import type { PlatformLocales } from './platform-locales';

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

/**
 * What an editor uses before the set has loaded, and when it cannot be read: every language the
 * registry has live in the content tier, in its fallback order, `ru` default (ADR 0149). Before the
 * registry itself has been read, the languages this build has catalogues for.
 *
 * A function and not the constant it replaced, because a constant of three languages was one more
 * declaration of which languages exist.
 */
export function platformLocaleSet(registry: PlatformLocales): LocaleSetView {
  const content = registry.active('CONTENT');
  return {
    locales: content.length > 0 ? content : LOCALES,
    defaultLocale: registry.fallback(),
    configured: false,
  };
}

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

/** The locale's name for an operator: "Russian", "Uzbek (Latin)", "English" — or the bare tag for one this console has no name for. */
export function localeDisplayName(i18n: I18n, locale: string, registry?: PlatformLocales): string {
  switch (locale) {
    case 'ru':
      return i18n.t('settings.brandProfile.locale.ru');
    case 'uz-Latn':
      return i18n.t('settings.brandProfile.locale.uzLatn');
    case 'en':
      return i18n.t('settings.brandProfile.locale.en');
    default:
      // A language this console has no key for: the registry names every language it declares.
      return registry?.nameOf(locale, i18n.locale()) ?? locale;
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

/**
 * The wording of one preset comment as the platform sends it — on an order line (`CommentPresetChip`)
 * and on a menu product (`CommentPresetOption`).
 *
 * `labels` holds every wording the preset has, keyed by locale, the platform triple first (row 10.12,
 * V0430 for the catalog and V0433 for the order-line snapshot; the only shape of it since V0678, which
 * dropped the `labelRu`/`labelUz`/`labelEn` fields this type used to carry as the floor). `label` is the
 * wording the platform itself resolved for the language the menu was requested in, then the brand's
 * default. Both are optional so a fixture, or a platform that predates them, still reads.
 */
export interface PresetWording {
  readonly labels?: Readonly<Record<string, string>>;
  readonly label?: string;
}

function filled(value: string | null | undefined): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}

/**
 * A preset's wording in the console's own language: the `labels` map for that language, else the
 * wording the platform resolved for the request (`label`), else whatever wording the map holds first
 * (the platform sends the triple first, in its own order), and an empty string only for a preset with no
 * wording at all, which the schema does not allow.
 */
export function presetLabelFor(preset: PresetWording, locale: string): string {
  const own = preset.labels?.[locale];
  if (filled(own)) {
    return own;
  }
  if (filled(preset.label)) {
    return preset.label;
  }
  return Object.values(preset.labels ?? {}).find(filled) ?? '';
}

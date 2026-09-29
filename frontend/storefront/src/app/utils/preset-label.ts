/**
 * The wording of one preset comment, as the platform sends it
 * (`StorefrontCatalogQuery.CommentPresetOption`).
 *
 * `labelRu` / `labelUz` / `labelEn` are the platform triple's columns, always present.
 * `labels` holds every wording the preset has keyed by locale -- the triple plus any locale a
 * tenant's brands support beyond it (row 10.12) -- and `label` is the wording the platform
 * resolved for the language the menu was requested in, then the brand's default. Both are
 * optional so a fixture, or a platform that predates them, still reads.
 */
export interface PresetWording {
  readonly labelRu: string;
  readonly labelUz: string;
  readonly labelEn: string;
  readonly labels?: Readonly<Record<string, string>>;
  readonly label?: string;
}

/** The storefront's language ids are the catalog's (`uz`); the platform's locale for Uzbek is `uz-Latn`. */
function platformLocale(langId: string): string {
  return langId === 'uz' ? 'uz-Latn' : langId;
}

/**
 * A preset's wording in the customer's own language.
 *
 * The customer's language wins wherever the preset has it -- from `labels`, which is how a
 * locale beyond ru / uz / en reaches the screen once a brand offers one. A preset with no
 * wording in it shows the platform's own resolution (`label`: the brand's default), and a
 * platform that sends neither falls back to the triple's columns, Uzbek by default, the way
 * this screen always did.
 */
export function presetLabelFor(preset: PresetWording, langId: string): string {
  const own = preset.labels?.[platformLocale(langId)];
  if (own) {
    return own;
  }
  switch (langId) {
    case 'ru':
      return preset.labelRu;
    case 'en':
      return preset.labelEn;
    case 'uz':
      return preset.labelUz;
    default:
      // A language the triple has no column for and the preset has no wording in.
      return preset.label ?? preset.labelUz;
  }
}

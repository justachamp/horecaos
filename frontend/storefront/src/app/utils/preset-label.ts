import { platformTag } from '../services/lang.service';

/**
 * The wording of one preset comment, as the platform sends it
 * (`StorefrontCatalogQuery.CommentPresetOption`).
 *
 * `labels` holds every wording the preset has keyed by locale, the platform triple first and any
 * further language a brand serves after it (row 10.12, V0430; the only shape of it since V0678, which
 * dropped the `labelRu` / `labelUz` / `labelEn` fields this type used to carry). `label` is the wording
 * the platform resolved for the language the menu was requested in, then the brand's default. Both are
 * optional so a fixture, or a platform that predates them, still reads.
 */
export interface PresetWording {
  readonly labels?: Readonly<Record<string, string>>;
  readonly label?: string;
}

function filled(value: string | undefined): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}

/**
 * A preset's wording in the customer's own language.
 *
 * The customer's language wins wherever the preset has it (the storefront's own `uz` is the
 * platform's `uz-Latn`, from the registry). A preset with no wording in it shows the platform's own
 * resolution (`label`: the brand's default), then whatever wording the map holds first, and an empty
 * string only for a preset with no wording at all, which the schema does not allow.
 */
export function presetLabelFor(preset: PresetWording, langId: string): string {
  const own = preset.labels?.[platformTag(langId)];
  if (filled(own)) {
    return own;
  }
  if (filled(preset.label)) {
    return preset.label;
  }
  return Object.values(preset.labels ?? {}).find(filled) ?? '';
}

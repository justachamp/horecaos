import type { EditorialFeatures, EditorialKind, EmploymentType } from '../types/editorial.types';

/**
 * The four editorial kinds as the storefront knows them: the route (and the path under
 * `.../content/`), the flag in `GET .../content/features` that turns it on (the vacancy listing is
 * `vacancies` there and `careers` here), and the translation key of its title.
 *
 * The keys are written out in full rather than built from the kind, so the build-time check that
 * every `content.*` key exists in all three languages can see them.
 */
export const EDITORIAL_KINDS: readonly {
  readonly kind: EditorialKind;
  readonly feature: keyof EditorialFeatures;
  readonly titleKey: string;
}[] = [
  { kind: 'news', feature: 'news', titleKey: 'content.editorial.news' },
  { kind: 'gallery', feature: 'gallery', titleKey: 'content.editorial.gallery' },
  { kind: 'recipes', feature: 'recipes', titleKey: 'content.editorial.recipes' },
  { kind: 'careers', feature: 'vacancies', titleKey: 'content.editorial.careers' },
];

/** The kind a route names, or `null` for anything else (a misconfigured route is "not available"). */
export function editorialKindOf(value: unknown): EditorialKind | null {
  return EDITORIAL_KINDS.find((entry) => entry.kind === value)?.kind ?? null;
}

export function editorialTitleKey(kind: EditorialKind): string {
  return (EDITORIAL_KINDS.find((entry) => entry.kind === kind) ?? EDITORIAL_KINDS[0]).titleKey;
}

export const EMPLOYMENT_LABEL_KEYS: Readonly<Record<EmploymentType, string>> = {
  FULL_TIME: 'content.employment.FULL_TIME',
  PART_TIME: 'content.employment.PART_TIME',
  SHIFT: 'content.employment.SHIFT',
  INTERNSHIP: 'content.employment.INTERNSHIP',
};

/**
 * A feedback tag's wording in the visitor's own language, then in the others the restaurant wrote.
 *
 * The platform keys wording by locale tag (`ru`, `uz-Latn`, `en`) while this storefront names its
 * Uzbek `uz`, so the one place that translates between the two is here and not in three components.
 * A tag with no wording at all shows its code, which is visibly wrong rather than blank.
 */
export function tagLabel(
  tag: { readonly code: string; readonly labels: Readonly<Record<string, string>> },
  langId: string,
): string {
  const own = langId === 'uz' ? 'uz-Latn' : langId;
  return (
    tag.labels[own] ??
    tag.labels['uz-Latn'] ??
    tag.labels['ru'] ??
    tag.labels['en'] ??
    Object.values(tag.labels)[0] ??
    tag.code
  );
}

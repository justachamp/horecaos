import type { MenuItem, MenuItemTag } from '../types/home.types';

/**
 * Tags on a menu (ADR 0174): the badges a card draws and the filter chips a menu offers.
 *
 * A tag is a controlled vocabulary entry the author registered once; the customer-facing wording arrives
 * already resolved for their language. Nothing here invents a tag or a name.
 */

/** The tags a product card draws as badges, in the author's order. */
export function badgesOf(item: MenuItem): MenuItemTag[] {
  return (item.tags ?? []).filter((tag) => tag.showAsBadge);
}

/**
 * The filter chips a set of products offers: each tag that some product carries and the author made a filter
 * option, once, in the order first met (the author's order within the first product that carries it).
 */
export function filterOptionsOf(items: readonly MenuItem[]): MenuItemTag[] {
  const seen = new Map<string, MenuItemTag>();
  for (const item of items) {
    for (const tag of item.tags ?? []) {
      if (tag.showAsFilter && !seen.has(tag.id)) {
        seen.set(tag.id, tag);
      }
    }
  }
  return [...seen.values()];
}

/**
 * Selected chips combine as AND (ADR 0174, open input on how filters combine): a product shows only when it
 * carries every selected tag. Selecting nothing shows everything.
 */
export function matchesAllTags(item: MenuItem, selected: ReadonlySet<string>): boolean {
  if (selected.size === 0) {
    return true;
  }
  const carried = new Set((item.tags ?? []).map((tag) => tag.id));
  for (const id of selected) {
    if (!carried.has(id)) {
      return false;
    }
  }
  return true;
}

/** A small, neutral 24x24 pictogram path for each name of the closed list; an unknown name draws nothing. */
export const TAG_ICON_PATHS: Readonly<Record<string, string>> = {
  FLAME: 'M12 2c1 4-3 5-3 9a4 4 0 0 0 8 0c0-1.5-.7-2.6-1.5-3.6C15.3 9.2 15 10 14 10c0-3-1-6-2-8z',
  LEAF: 'M20 4C11 4 4 9 4 16c0 1.5.4 2.8 1 4 1-2 3-5 6-7-2.5 3-4 6-4.4 8.2C8 21.6 9.4 22 11 22c7 0 10-6.5 9-18z',
  STAR: 'M12 2l3.09 6.26L22 9.27l-5 4.87L18.18 21 12 17.77 5.82 21 7 14.14 2 9.27l6.91-1.01L12 2z',
  HEART:
    'M12 21s-8-5-9.5-10C1.6 7.700 3.700 5 6.700 5c2 0 3.700 1.100 5.300 3 1.600-1.900 3.300-3 5.300-3 3 0 5.100 2.700 4.200 6-1.500 5-9.500 10-9.500 10z',
  FISH: 'M2 12c3-5 8-6 12-4l5-3v14l-5-3c-4 2-9 1-12-4z',
  NEW: 'M12 3l2.4 5.4 5.9.6-4.4 4 1.3 5.8L12 15.8 6.800 18.800l1.300-5.800-4.400-4 5.900-.6L12 3z',
};

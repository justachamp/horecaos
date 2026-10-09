import type { ContentGroup } from '../types/content.types';
import type { MenuItem } from '../types/home.types';

/** A category of the menu the home page already holds, with its dishes in the menu's own order. */
export interface MenuSection {
  readonly id: string;
  readonly name: string;
  readonly items: readonly MenuItem[];
}

/**
 * The dishes a home group shows, joined against the menu the page already loaded.
 *
 * The document names products and categories by id and carries no dish data on purpose: the menu
 * read owns prices, availability and images, and the page never asks the server for products a
 * second time. So:
 *
 *  - a **manual** group keeps its own order and skips an id the menu does not hold (a dish that
 *    left the menu between the two reads, or a menu that has not loaded), and shows a dish once
 *    however many times, or in however many categories, it appears;
 *  - a **by-category** group takes that category's dishes in the menu's order. `NEWEST` is read as
 *    the same order: the menu carries no creation timestamp to sort by, and inventing one would
 *    be a lie the screen tells confidently. `limit` caps the result.
 *
 * An empty result is the caller's cue to render nothing at all.
 */
export function resolveGroupItems(group: ContentGroup, menu: readonly MenuSection[]): MenuItem[] {
  const source = group.source;
  if (source.kind === 'BY_CATEGORY') {
    const items = menu.find((section) => section.id === source.categoryId)?.items ?? [];
    return source.limit === undefined ? [...items] : items.slice(0, source.limit);
  }
  const byId = new Map<string, MenuItem>();
  for (const section of menu) {
    for (const item of section.items) {
      if (!byId.has(item.id)) {
        byId.set(item.id, item);
      }
    }
  }
  const out: MenuItem[] = [];
  const seen = new Set<string>();
  for (const id of source.productIds) {
    const item = byId.get(id);
    if (item !== undefined && !seen.has(id)) {
      seen.add(id);
      out.push(item);
    }
  }
  return out;
}

import type { ContentGroup } from '../types/content.types';
import type { CategoryItem, MenuItem } from '../types/home.types';

/**
 * The dishes a home group shows, joined against the menu the page already holds. The server is
 * never asked for products (ADR 0157, Front ends).
 *
 * - A **by-category** group takes that category's dishes in the menu's own order. `NEWEST` keeps the
 *   menu order too: the published menu carries no timestamp to sort by. `limit` caps it.
 * - A **manual** group lists its dishes in the order the brand chose and skips an id the menu lacks
 *   (a dish withdrawn since the group was published), and a dish it names twice.
 * - A group left with nothing returns an empty list, which the caller draws as nothing at all.
 */
export function resolveGroupItems(
  group: ContentGroup,
  sections: readonly CategoryItem[],
): readonly MenuItem[] {
  if (group.categoryId) {
    const section = sections.find((candidate) => candidate.id === group.categoryId);
    if (!section) {
      return [];
    }
    const { limit } = group;
    return limit !== undefined && Number.isInteger(limit) && limit > 0
      ? section.items.slice(0, limit)
      : [...section.items];
  }

  const byId = new Map<string, MenuItem>();
  for (const section of sections) {
    for (const item of section.items) {
      if (!byId.has(item.id)) {
        byId.set(item.id, item);
      }
    }
  }
  const seen = new Set<string>();
  const items: MenuItem[] = [];
  for (const id of group.productIds) {
    const item = byId.get(id);
    if (item && !seen.has(id)) {
      seen.add(id);
      items.push(item);
    }
  }
  return items;
}

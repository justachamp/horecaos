import type { ContentCategoryRef, ContentLink } from '../types/content.types';
import { httpsUrlOrNull } from './safe-url';

/**
 * Where a content link may send the customer, or `null` when it is not a link at all.
 *
 *  - `route`: a place inside this storefront. `category` is set for a category link, because the
 *    category page reads its choice from session storage (see {@link rememberSelectedCategory}),
 *    not from the URL.
 *  - `external`: an `https:` URL, re-checked here even though the server vouched for it; the
 *    client is the one that puts it in an `href`.
 *
 * `NONE` is not a link. `PROMOTION` is read but is not a link either: this storefront has no
 * promotion screen to open, and a button that goes nowhere is worse than no button, so such an
 * item renders as a plain tile with no call to action.
 */
export type ResolvedContentLink =
  | { readonly kind: 'route'; readonly commands: string[]; readonly category?: ContentCategoryRef }
  | { readonly kind: 'external'; readonly href: string };

export function resolveContentLink(
  link: ContentLink,
  categories: readonly ContentCategoryRef[],
): ResolvedContentLink | null {
  switch (link.type) {
    case 'PRODUCT':
      return link.targetId ? { kind: 'route', commands: ['/product', link.targetId] } : null;
    case 'CATEGORY': {
      const id = link.targetId;
      if (!id) {
        return null;
      }
      const known = categories.find((c) => c.id === id);
      return {
        kind: 'route',
        commands: ['/category'],
        category: { id, name: known?.name ?? '' },
      };
    }
    case 'CART':
      return { kind: 'route', commands: ['/cart'] };
    case 'EXTERNAL': {
      const href = httpsUrlOrNull(link.url);
      return href === null ? null : { kind: 'external', href };
    }
    default:
      // NONE, and PROMOTION, which this storefront has no screen for.
      return null;
  }
}

/**
 * The category page opens on whatever `mar_selected_cat` names (the section header writes it the
 * same way); without it the page sends the visitor home. Storage can throw (private mode, a
 * blocked-storage WebView), and a link that cannot say which category it means just lands on home.
 */
export function rememberSelectedCategory(category: ContentCategoryRef): void {
  try {
    sessionStorage.setItem(
      'mar_selected_cat',
      JSON.stringify(
        category.name ? { id: category.id, name: category.name } : { id: category.id },
      ),
    );
  } catch {
    // Nothing to remember it in; the category page falls back to home.
  }
}

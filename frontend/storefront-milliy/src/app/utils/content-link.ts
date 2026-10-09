import type { ContentLink } from '../types/content.types';

/**
 * Where a tap on content goes, once the client has decided it may.
 *
 * `route` is a screen of this application; `external` is an address outside it. Content is data the
 * brand typed and the server relayed, so a link is never trusted as it arrives: this is the one
 * place that turns a wire link into something a template may put in an `href` or a `routerLink`.
 */
export type ResolvedContentLink =
  | {
      readonly kind: 'route';
      readonly commands: string[];
      readonly queryParams?: Readonly<Record<string, string>>;
    }
  | { readonly kind: 'external'; readonly url: string };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * The address, normalised, when -- and only when -- it parses as an absolute `https:` URL with a
 * host and no embedded credentials. Everything else (`http:`, `javascript:`, `data:`, a relative or
 * protocol-relative address, `user:password@host`) is null.
 *
 * The server already re-checks the host of an `EXTERNAL` link against the brand's allowlist on every
 * read; this is the client's own check of the one thing that decides whether a click can run code.
 */
export function safeHttpsUrl(raw: string | null | undefined): string | null {
  if (typeof raw !== 'string') {
    return null;
  }
  const text = raw.trim();
  if (!text) {
    return null;
  }
  let parsed: URL;
  try {
    parsed = new URL(text);
  } catch {
    return null;
  }
  if (parsed.protocol !== 'https:' || !parsed.hostname || parsed.username || parsed.password) {
    return null;
  }
  return parsed.href;
}

/**
 * A link as the screens may act on it, or null when it is not a link.
 *
 * - `PRODUCT` and `CATEGORY` need an id that is a UUID; anything else (a path, an empty string) is
 *   no link, so a target can never reach the router as something other than one path segment or
 *   one query value.
 * - `CATEGORY` goes to the home screen with the category chosen: this storefront has no category
 *   page, the category is a filter on the menu.
 * - `PROMOTION` is no link: this storefront has no promotion screen.
 * - `EXTERNAL` only through {@link safeHttpsUrl}.
 */
export function resolveContentLink(
  link: ContentLink | null | undefined,
): ResolvedContentLink | null {
  if (!link) {
    return null;
  }
  switch (link.type) {
    case 'PRODUCT':
      return link.targetId && UUID.test(link.targetId)
        ? { kind: 'route', commands: ['/product', link.targetId] }
        : null;
    case 'CATEGORY':
      return link.targetId && UUID.test(link.targetId)
        ? { kind: 'route', commands: ['/home'], queryParams: { category: link.targetId } }
        : null;
    case 'CART':
      return { kind: 'route', commands: ['/cart'] };
    case 'EXTERNAL': {
      const url = safeHttpsUrl(link.url);
      return url ? { kind: 'external', url } : null;
    }
    default:
      return null;
  }
}

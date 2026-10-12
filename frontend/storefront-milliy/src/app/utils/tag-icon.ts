import { resolveMediaUrl } from './content-media';

/**
 * Where the browser loads a feedback tag's icon from, or null when the tag has none.
 *
 * The platform serves a tenant's uploaded icon through its public media route and names it two ways:
 * as that route's site-relative path (`iconUrl`, on the tags a customer is offered and on public
 * comments) or as a bare asset id (`iconAssetId`, on the customer's own review). Either becomes the
 * same address, put on the origin the API is served from -- which is not this page's own origin when
 * the API is cross-origin. Only a site-relative path is an icon: an absolute URL or a protocol-relative
 * one is not the platform's own, so the tag simply has none.
 */
export function tagIconSrc(
  tag: { readonly iconUrl?: string | null; readonly iconAssetId?: string | null },
  config: { readonly apiBaseUrl: string; readonly tenantId: string },
): string | null {
  const path =
    tag.iconUrl ??
    (tag.iconAssetId
      ? `/api/v1/storefront/tenants/${encodeURIComponent(config.tenantId)}/media/${encodeURIComponent(tag.iconAssetId)}`
      : null);
  return path !== null && path.startsWith('/') ? resolveMediaUrl(path, config.apiBaseUrl) : null;
}

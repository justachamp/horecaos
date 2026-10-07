/**
 * Where the map configuration and the address lookups live on the platform (ADR 0145,
 * `OperationsGeocodeController`).
 *
 * Brand-scoped on the ADR 0031 `/api/v1/operations/**` prefix. The three lookups exist twice,
 * once under the brand and once under a branch, and which one a screen calls is not a style
 * choice: a grant at `LOCATION` never covers a `BRAND` requirement (ADR 0025 scopes cover
 * downwards and never up), and New order's address pane is used by staff whose grant is at the
 * branch. A screen that knows its branch calls the branch path, which a brand-level grant also
 * covers; a screen without one (the zone and region editors) calls the brand path.
 */
import { BrandScope } from './catalog-paths';

const OPERATIONS = '/api/v1/operations';

function tenantBrand(scope: BrandScope): string {
  return `/tenants/${encodeURIComponent(scope.tenantId)}/brands/${encodeURIComponent(scope.brandId)}`;
}

export type GeoLookupKind = 'suggestions' | 'resolutions' | 'reverse-resolutions';

export const geoPaths = {
  /**
   * `GET`: the provider, whether it is configured, its public browser key, the features on offer
   * and the attribution. Readable by any signed-in staff principal; holds nothing about a tenant.
   */
  mapConfig(scope: BrandScope): string {
    return `${OPERATIONS}${tenantBrand(scope)}/map-config`;
  },

  /**
   * `POST` with the address in the body, never in the URL (ADR 0029). Writes nothing, so it needs
   * no idempotency key of its own meaning, though the shared client sends one.
   */
  lookup(scope: BrandScope, kind: GeoLookupKind, locationId?: string | null): string {
    const base = `${OPERATIONS}${tenantBrand(scope)}`;
    const at = locationId ? `/locations/${encodeURIComponent(locationId)}` : '';
    return `${base}${at}/geocode/${kind}`;
  },
};

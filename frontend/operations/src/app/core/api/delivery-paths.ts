/**
 * Where delivery zones, regions and tariffs live on the platform (ADR 0037,
 * ADR 0104).
 *
 * **The mismatch this file used to document is now closed.** Every builder
 * below points at `/api/v1/operations` — the surface group ADR 0057 defines
 * for the console's own app, served by `OperationsServiceZoneController`,
 * `OperationsDeliveryTariffController` and `OperationsRegionController`. The
 * previous revision built from `/api/v1/control-plane` and said why: the
 * operations mirrors did not exist when it was written, and re-plumbing every
 * control-plane consumer was a larger change than adding a route. The mirrors
 * exist now, and the control-plane pair is left exactly where it is.
 *
 * That move is not cosmetic. The operations controllers differ from the
 * control-plane pair in one deliberate way — the actor who drafted or
 * activated a version is read from the caller's own authenticated identity and
 * is never accepted as a request field. Until this file moved, the console was
 * posting `actorId: auth.subject()` into the `created_by`/`activated_by`
 * columns of a row that governs what every customer is charged for delivery:
 * a value the client chose, written onto fee evidence.
 */

import { BrandScope } from './catalog-paths';

const OPERATIONS = '/api/v1/operations';

function tenantBrand(scope: BrandScope): string {
  return `/tenants/${encodeURIComponent(scope.tenantId)}/brands/${encodeURIComponent(scope.brandId)}`;
}

export const deliveryZonePaths = {
  base(scope: BrandScope): string {
    return `${OPERATIONS}${tenantBrand(scope)}/service-zones`;
  },

  /** Every zone this brand has registered, with its live version's numbers. */
  zones(scope: BrandScope): string {
    return this.base(scope);
  },

  /** One zone's live numbers and the branches it currently applies to. */
  zone(scope: BrandScope, zoneId: string): string {
    return `${this.base(scope)}/${encodeURIComponent(zoneId)}`;
  },

  /** Register a zone's lineage. Mutation: key required. */
  zoneCreate(scope: BrandScope): string {
    return this.base(scope);
  },

  /** Draft a new version (circle or polygon). Mutation: key required. */
  zoneVersions(scope: BrandScope, zoneId: string): string {
    return `${this.zone(scope, zoneId)}/versions`;
  },

  /** Make a drafted version live. Mutation: key required. */
  zoneVersionActivate(scope: BrandScope, zoneId: string, version: number): string {
    return `${this.zoneVersions(scope, zoneId)}/${version}/activate`;
  },

  /** Retire the live version and put nothing in its place. Mutation: key required. */
  zoneVersionDeactivate(scope: BrandScope, zoneId: string, version: number): string {
    return `${this.zoneVersions(scope, zoneId)}/${version}/deactivate`;
  },

  /** Bind the zone to a branch. Mutation: key required. */
  zoneLocations(scope: BrandScope, zoneId: string): string {
    return `${this.zone(scope, zoneId)}/locations`;
  },

  /** Stop the zone applying to one branch. Mutation: key required. */
  zoneLocation(scope: BrandScope, zoneId: string, locationId: string): string {
    return `${this.zoneLocations(scope, zoneId)}/${encodeURIComponent(locationId)}`;
  },
} as const;

/**
 * Regions are tenant-wide, not brand-wide: the row has no `brand_id` and its
 * bounding box constrains the geocoder for every brand under the tenant. The
 * path says so, and the capability check reads `tenantId` out of it.
 */
export const regionPaths = {
  base(tenantId: string): string {
    return `${OPERATIONS}/tenants/${encodeURIComponent(tenantId)}/regions`;
  },

  /** This tenant's regions and the platform's. */
  regions(tenantId: string): string {
    return this.base(tenantId);
  },

  /** Register a region. Mutation: key required. */
  regionCreate(tenantId: string): string {
    return this.base(tenantId);
  },

  /** Rewrite one of this tenant's own regions. Mutation: key required. */
  region(tenantId: string, regionId: string): string {
    return `${this.base(tenantId)}/${encodeURIComponent(regionId)}`;
  },

  /** Archive a region — never delete one. Mutation: key required. */
  regionArchive(tenantId: string, regionId: string): string {
    return `${this.region(tenantId, regionId)}/archive`;
  },
} as const;

export const deliveryTariffPaths = {
  base(scope: BrandScope): string {
    return `${OPERATIONS}${tenantBrand(scope)}/delivery-tariffs`;
  },

  /** Every rate table this brand has registered, with its live version's headline numbers. */
  tariffs(scope: BrandScope): string {
    return this.base(scope);
  },

  /** One tariff's live bands, time rules and discounts in full. */
  tariff(scope: BrandScope, tariffId: string): string {
    return `${this.base(scope)}/${encodeURIComponent(tariffId)}`;
  },

  /** Register a rate table's lineage. Mutation: key required. */
  tariffCreate(scope: BrandScope): string {
    return this.base(scope);
  },

  /** Draft a new version — bands, time rules and discounts. Mutation: key required. */
  tariffVersions(scope: BrandScope, tariffId: string): string {
    return `${this.tariff(scope, tariffId)}/versions`;
  },

  /** Make a drafted version live. Mutation: key required. */
  tariffVersionActivate(scope: BrandScope, tariffId: string, version: number): string {
    return `${this.tariffVersions(scope, tariffId)}/${version}/activate`;
  },

  /** Bind the tariff to a branch. Mutation: key required. */
  tariffLocations(scope: BrandScope, tariffId: string): string {
    return `${this.tariff(scope, tariffId)}/locations`;
  },
} as const;

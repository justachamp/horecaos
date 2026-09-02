/**
 * Where delivery zones and tariffs live on the platform (ADR 0037).
 *
 * **Same pre-existing mismatch `catalog-paths.ts` already documents, for the
 * same reason.** `ServiceZoneController` and `DeliveryTariffController` are
 * mapped under `/api/v1/control-plane/tenants/{tenantId}/brands/{brandId}/**`
 * — the `control-plane` OpenAPI surface group — even though the capabilities
 * they declare (`DELIVERY_ZONE_READ`/`MANAGE`/`ACTIVATE`,
 * `DELIVERY_TARIFF_READ`/`MANAGE`/`ACTIVATE`) are granted to tenant-side roles
 * (owner, brand manager, tenant finance), never platform-staff-only ones. This
 * wave added the read endpoints (`GET` list and detail on both controllers)
 * to serve operations §3.6/§3.7 and left the mismatch itself alone, exactly
 * as `catalog-paths.ts` did for Catalog: re-plumbing every existing
 * control-plane consumer of these controllers is a separate, larger change
 * than adding a route. This module is the one place that knows about it —
 * when the day comes to remap these under `/api/v1/operations/**`, this file
 * is what changes.
 */

import { BrandScope } from './catalog-paths';

const CONTROL_PLANE = '/api/v1/control-plane';

function tenantBrand(scope: BrandScope): string {
  return `/tenants/${encodeURIComponent(scope.tenantId)}/brands/${encodeURIComponent(scope.brandId)}`;
}

export const deliveryZonePaths = {
  base(scope: BrandScope): string {
    return `${CONTROL_PLANE}${tenantBrand(scope)}/service-zones`;
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

  /** Bind the zone to a branch. Mutation: key required. */
  zoneLocations(scope: BrandScope, zoneId: string): string {
    return `${this.zone(scope, zoneId)}/locations`;
  },
} as const;

export const deliveryTariffPaths = {
  base(scope: BrandScope): string {
    return `${CONTROL_PLANE}${tenantBrand(scope)}/delivery-tariffs`;
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

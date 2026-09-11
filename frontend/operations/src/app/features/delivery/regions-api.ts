import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { regionPaths } from '../../core/api/delivery-paths';
import { command } from '../../core/api/idempotency';

/** Mirrors `OperationsRegionController.RegionResponse`. */
export interface RegionResponse {
  readonly regionId: string;
  /**
   * A platform region every tenant may reference (V0025: "Tashkent is not one
   * tenant's fact"). Read-only here — the server refuses a write to one, and
   * the screen has to say so rather than offering an edit that always fails.
   */
  readonly platform: boolean;
  readonly code: string;
  readonly displayNameRu: string;
  readonly displayNameUz: string;
  readonly displayNameEn: string;
  readonly centreLat: number;
  readonly centreLon: number;
  readonly bboxSwLat: number;
  readonly bboxSwLon: number;
  readonly bboxNeLat: number;
  readonly bboxNeLon: number;
  /** `ACTIVE` | `ARCHIVED`. */
  readonly status: string;
  readonly version: number;
}

/**
 * A region's geography as an operator types it.
 *
 * The SW/NE box is the whole point of the row: it constrains the geocoder, and
 * `ServiceZoneService.activate` checks every zone polygon against it — the
 * check that catches a transposed latitude, where the geometry is valid and
 * simply somewhere else.
 */
export interface RegionGeographyRequest {
  readonly code: string;
  readonly displayNameRu: string;
  readonly displayNameUz: string;
  readonly displayNameEn: string;
  readonly centreLat: number;
  readonly centreLon: number;
  readonly bboxSwLat: number;
  readonly bboxSwLon: number;
  readonly bboxNeLat: number;
  readonly bboxNeLon: number;
  /**
   * Required to rewrite a region, refused with `STALE_VERSION` when it no
   * longer matches (ADR 0031's optimistic-locking convention) — undefined on
   * a create, which the server ignores.
   */
  readonly expectedVersion?: number;
}

export interface RegionRegisteredView {
  readonly regionId: string;
  readonly code: string;
}

/**
 * Regions (operations §3.6b) — `OperationsRegionController` (ADR 0037,
 * ADR 0101).
 *
 * Tenant-scoped, not brand-scoped: the row has no `brand_id` and every brand
 * under the tenant geocodes against it. That is also why the capability is
 * checked at `TENANT` scope, so a brand manager cannot author one.
 */
@Injectable({ providedIn: 'root' })
export class RegionsApi {
  private readonly api = inject(ApiClient);

  async list(tenantId: string): Promise<readonly RegionResponse[]> {
    const result = await firstValueFrom(
      this.api.get<readonly RegionResponse[]>(regionPaths.regions(tenantId)),
    );
    return result.value ?? [];
  }

  async create(tenantId: string, request: RegionGeographyRequest): Promise<RegionRegisteredView> {
    return firstValueFrom(
      this.api.post<RegionGeographyRequest, RegionRegisteredView>(
        regionPaths.regionCreate(tenantId),
        command(request),
      ),
    );
  }

  async update(tenantId: string, regionId: string, request: RegionGeographyRequest): Promise<void> {
    await firstValueFrom(
      this.api.put<RegionGeographyRequest, void>(
        regionPaths.region(tenantId, regionId),
        command(request),
      ),
    );
  }

  /** Archived, never deleted: zone versions name this row. */
  async archive(tenantId: string, regionId: string): Promise<void> {
    await firstValueFrom(
      this.api.post<Record<string, never>, void>(
        regionPaths.regionArchive(tenantId, regionId),
        command({}),
      ),
    );
  }
}

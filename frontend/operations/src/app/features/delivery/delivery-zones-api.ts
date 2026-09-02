import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { BrandScope } from '../../core/api/catalog-paths';
import { deliveryZonePaths } from '../../core/api/delivery-paths';
import { command } from '../../core/api/idempotency';

/** Mirrors `ServiceZoneController.ZoneSummaryResponse`. */
export interface ZoneSummaryResponse {
  readonly zoneId: string;
  /** `DELIVERY` | `CATCHMENT`. */
  readonly role: string;
  readonly code: string;
  readonly displayNameRu: string;
  readonly displayNameUz: string;
  readonly displayNameEn: string;
  readonly status: string;
  readonly activeVersion?: number | null;
  readonly priority?: number | null;
  readonly currency?: string | null;
  readonly deliveryTariffId?: string | null;
  readonly freeDeliveryFromMinor?: number | null;
  readonly minBasketMinor?: number | null;
  readonly areaSquareMeters?: number | null;
}

export interface ZoneDetailResponse {
  readonly zone: ZoneSummaryResponse;
  readonly boundLocationIds: readonly string[];
}

export interface CreateZoneRequest {
  readonly role: 'DELIVERY' | 'CATCHMENT';
  readonly code: string;
  readonly displayNameRu: string;
  readonly displayNameUz: string;
  readonly displayNameEn: string;
}

export interface ZoneView {
  readonly zoneId: string;
  readonly code: string;
  readonly role: string;
}

/**
 * A circle drawn around a branch — the only shape this wave's frontend
 * offers. No `MapCanvas`/`PolygonEditor` exists in this design system (IA
 * Part 4's own "Pilot blockers" table names it as missing entirely), so a
 * free-hand polygon is not buildable this wave; the backend's circle-draft
 * path (`ServiceZoneController.CircleRequest`) is real and does not need one.
 */
export interface DraftCircleVersionRequest {
  readonly originLocationId: string;
  readonly radiusMeters: number;
  readonly regionId?: string | null;
  readonly priority: number;
  readonly currency: string;
  readonly deliveryTariffId?: string | null;
  readonly freeDeliveryFromMinor?: number | null;
  readonly minBasketMinor?: number | null;
  readonly actorId: string;
}

export interface VersionView {
  readonly zoneId: string;
  readonly version: number;
  readonly status: string;
}

/**
 * Delivery zones (operations §3.6) — `ServiceZoneController` (ADR 0037,
 * `control-plane` OpenAPI surface — see `delivery-paths.ts`'s own doc for
 * why this operations-app service reaches across surfaces the same way
 * `catalog-api.ts` already does).
 */
@Injectable({ providedIn: 'root' })
export class DeliveryZonesApi {
  private readonly api = inject(ApiClient);

  async list(scope: BrandScope): Promise<readonly ZoneSummaryResponse[]> {
    const result = await firstValueFrom(
      this.api.get<readonly ZoneSummaryResponse[]>(deliveryZonePaths.zones(scope)),
    );
    return result.value ?? [];
  }

  async detail(scope: BrandScope, zoneId: string): Promise<ZoneDetailResponse> {
    const result = await firstValueFrom(
      this.api.get<ZoneDetailResponse>(deliveryZonePaths.zone(scope, zoneId)),
    );
    return result.value;
  }

  async create(scope: BrandScope, request: CreateZoneRequest): Promise<ZoneView> {
    return firstValueFrom(
      this.api.post<CreateZoneRequest, ZoneView>(
        deliveryZonePaths.zoneCreate(scope),
        command(request),
      ),
    );
  }

  async draftCircleVersion(
    scope: BrandScope,
    zoneId: string,
    request: DraftCircleVersionRequest,
  ): Promise<VersionView> {
    const body: DraftCircleVersionWireRequest = {
      circle: { originLocationId: request.originLocationId, radiusMeters: request.radiusMeters },
      regionId: request.regionId ?? null,
      priority: request.priority,
      currency: request.currency,
      deliveryTariffId: request.deliveryTariffId ?? null,
      freeDeliveryFromMinor: request.freeDeliveryFromMinor ?? null,
      minBasketMinor: request.minBasketMinor ?? null,
      actorId: request.actorId,
    };
    return firstValueFrom(
      this.api.post<DraftCircleVersionWireRequest, VersionView>(
        deliveryZonePaths.zoneVersions(scope, zoneId),
        command(body),
      ),
    );
  }

  async activate(
    scope: BrandScope,
    zoneId: string,
    version: number,
    actorId: string,
  ): Promise<VersionView> {
    return firstValueFrom(
      this.api.post<{ actorId: string }, VersionView>(
        deliveryZonePaths.zoneVersionActivate(scope, zoneId, version),
        command({ actorId }),
      ),
    );
  }

  async bindLocation(scope: BrandScope, zoneId: string, locationId: string): Promise<void> {
    await firstValueFrom(
      this.api.post<{ locationId: string }, void>(
        deliveryZonePaths.zoneLocations(scope, zoneId),
        command({ locationId }),
      ),
    );
  }
}

/** The wire shape `ServiceZoneController.DraftVersionRequest` expects for a circle draft. */
interface DraftCircleVersionWireRequest {
  readonly circle: { readonly originLocationId: string; readonly radiusMeters: number };
  readonly regionId: string | null;
  readonly priority: number;
  readonly currency: string;
  readonly deliveryTariffId: string | null;
  readonly freeDeliveryFromMinor: number | null;
  readonly minBasketMinor: number | null;
  readonly actorId: string;
}

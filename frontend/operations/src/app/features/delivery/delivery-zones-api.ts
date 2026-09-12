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

/** Mirrors `OperationsServiceZoneController.ZoneVersionResponse`. */
export interface ZoneVersionResponse {
  readonly version: number;
  /** `DRAFT` | `ACTIVE` | `RETIRED` | `DISCARDED`. */
  readonly status: string;
  readonly priority: number;
  readonly currency: string;
  readonly deliveryTariffId?: string | null;
  readonly freeDeliveryFromMinor?: number | null;
  readonly minBasketMinor?: number | null;
  readonly areaSquareMeters: number;
  readonly regionId?: string | null;
  readonly originLocationId?: string | null;
  /** `CIRCLE` | `POLYGON`. */
  readonly shapeKind?: string | null;
  readonly createdAt?: string | null;
  readonly activatedAt?: string | null;
  readonly retiredAt?: string | null;
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
 * A circle drawn around a branch — the only shape this console offers. No
 * `MapCanvas`/`PolygonEditor` exists in this design system (IA Part 4's own
 * "Pilot blockers" table names it as missing entirely, and ADR 0015 still
 * owes the provider decision ADR 0037 inherited), so a free-hand polygon is
 * not buildable yet; the backend's circle-draft path
 * (`ServiceZoneController.CircleRequest`) is real and needs no map.
 *
 * `deliveryTariffId` is the field this wave exists for. It has been on the
 * wire contract and on this interface since the screen shipped, and the page
 * never set it — so every zone a console user had ever drawn carried a null
 * tariff and ADR 0037's zone-beats-branch precedence could not be reached
 * from the product. `null` is still a legal value and still means "fall
 * through to the branch binding, then the brand default"; what changed is
 * that an operator can now choose.
 *
 * There is no `actorId`. The operations surface reads the actor from the
 * caller's own token — see `delivery-paths.ts`.
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
}

export interface VersionView {
  readonly zoneId: string;
  readonly version: number;
  readonly status: string;
}

/**
 * Delivery zones (operations §3.6) — `OperationsServiceZoneController`
 * (ADR 0037, ADR 0104, `operations` OpenAPI surface).
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

  async versions(scope: BrandScope, zoneId: string): Promise<readonly ZoneVersionResponse[]> {
    const result = await firstValueFrom(
      this.api.get<readonly ZoneVersionResponse[]>(deliveryZonePaths.zoneVersions(scope, zoneId)),
    );
    return result.value ?? [];
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
    };
    return firstValueFrom(
      this.api.post<DraftCircleVersionWireRequest, VersionView>(
        deliveryZonePaths.zoneVersions(scope, zoneId),
        command(body),
      ),
    );
  }

  async activate(scope: BrandScope, zoneId: string, version: number): Promise<VersionView> {
    return firstValueFrom(
      this.api.post<Record<string, never>, VersionView>(
        deliveryZonePaths.zoneVersionActivate(scope, zoneId, version),
        command({}),
      ),
    );
  }

  /** Retires the live version. The zone then covers nothing, deliberately. */
  async deactivate(scope: BrandScope, zoneId: string, version: number): Promise<VersionView> {
    return firstValueFrom(
      this.api.post<Record<string, never>, VersionView>(
        deliveryZonePaths.zoneVersionDeactivate(scope, zoneId, version),
        command({}),
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

  /**
   * Closes the binding's validity window server-side; the row survives,
   * because a fee resolution six weeks old names the binding that applied.
   */
  async unbindLocation(scope: BrandScope, zoneId: string, locationId: string): Promise<void> {
    await firstValueFrom(
      this.api.send<Record<string, never>, void>(
        'DELETE',
        deliveryZonePaths.zoneLocation(scope, zoneId, locationId),
        command({}),
      ),
    );
  }
}

/** The wire shape `OperationsServiceZoneController.DraftVersionRequest` expects for a circle draft. */
interface DraftCircleVersionWireRequest {
  readonly circle: { readonly originLocationId: string; readonly radiusMeters: number };
  readonly regionId: string | null;
  readonly priority: number;
  readonly currency: string;
  readonly deliveryTariffId: string | null;
  readonly freeDeliveryFromMinor: number | null;
  readonly minBasketMinor: number | null;
}

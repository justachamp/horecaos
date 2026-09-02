import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { BrandScope } from '../../core/api/catalog-paths';
import { deliveryTariffPaths } from '../../core/api/delivery-paths';
import { command } from '../../core/api/idempotency';

/** Mirrors `DeliveryTariffController.TariffSummaryResponse`. */
export interface TariffSummaryResponse {
  readonly tariffId: string;
  readonly code: string;
  readonly name: string;
  readonly status: string;
  readonly brandDefault: boolean;
  readonly activeVersion?: number | null;
  readonly currency?: string | null;
  /** `TARIFF` | `PROVIDER_QUOTE`. */
  readonly feeSource?: string | null;
  /** `RADIUS` | `ROAD`. */
  readonly distanceMode?: string | null;
  readonly maxDistanceMeters?: number | null;
}

export interface BandView {
  readonly bandSet: string;
  readonly fromMeters: number;
  readonly toMeters: number;
  readonly baseMinor: number;
  readonly perKmMinor: number;
}

export interface TimeRuleView {
  readonly priority: number;
  /** Bit 0 = Monday. */
  readonly dayMask: number;
  readonly fromTime: string;
  readonly toTime: string;
  readonly bandSet?: string | null;
  readonly multiplierBasisPoints: number;
  readonly surchargeMinor: number;
}

export interface DiscountView {
  readonly priority: number;
  readonly kind: string;
  readonly amountMinor?: number | null;
  readonly allowanceMeters?: number | null;
  readonly dayMask: number;
  readonly fromTime: string;
  readonly toTime: string;
}

export interface ActiveVersionResponse {
  readonly version: number;
  readonly currency: string;
  readonly feeSource: string;
  readonly distanceMode: string;
  readonly roadFactorBasisPoints: number;
  readonly routingProviderInstallationId?: string | null;
  readonly maxDistanceMeters: number;
  readonly minFeeMinor: number;
  readonly maxFeeMinor?: number | null;
  readonly distanceAccrual: string;
  readonly feeRoundingStepMinor?: number | null;
  readonly feeRoundingRule?: string | null;
  readonly bands: readonly BandView[];
  readonly timeRules: readonly TimeRuleView[];
  readonly discounts: readonly DiscountView[];
}

export interface TariffDetailResponse {
  readonly tariff: TariffSummaryResponse;
  readonly activeVersion?: ActiveVersionResponse | null;
}

export interface CreateTariffRequest {
  readonly code: string;
  readonly name: string;
  readonly brandDefault: boolean;
}

export interface TariffView {
  readonly tariffId: string;
  readonly code: string;
  readonly brandDefault: boolean;
}

/**
 * A single flat band across the tariff's whole reach — this wave's authoring
 * form. `DeliveryTariffController.DraftTariffVersionRequest` supports many
 * bands, peak-hour time rules and standing discounts; this form drafts the
 * common one-band case (base fee + per-km) an operator needs to price a
 * brand's delivery on day one, and leaves multi-band/time-rule/discount
 * authoring to a later wave rather than half-building a rule editor no
 * `ConditionBuilder` component exists for yet (IA Part 4).
 */
export interface DraftFlatVersionRequest {
  readonly currency: string;
  readonly feeSource: 'TARIFF' | 'PROVIDER_QUOTE';
  readonly distanceMode: 'RADIUS' | 'ROAD';
  readonly maxDistanceMeters: number;
  readonly minFeeMinor: number;
  readonly maxFeeMinor?: number | null;
  readonly baseMinor: number;
  readonly perKmMinor: number;
  readonly actorId: string;
}

export interface VersionView {
  readonly tariffId: string;
  readonly version: number;
  readonly status: string;
}

/**
 * Delivery tariffs (operations §3.7) — `DeliveryTariffController` (ADR 0037,
 * same `control-plane`-surface situation `delivery-zones-api.ts` documents).
 */
@Injectable({ providedIn: 'root' })
export class DeliveryTariffsApi {
  private readonly api = inject(ApiClient);

  async list(scope: BrandScope): Promise<readonly TariffSummaryResponse[]> {
    const result = await firstValueFrom(
      this.api.get<readonly TariffSummaryResponse[]>(deliveryTariffPaths.tariffs(scope)),
    );
    return result.value ?? [];
  }

  async detail(scope: BrandScope, tariffId: string): Promise<TariffDetailResponse> {
    const result = await firstValueFrom(
      this.api.get<TariffDetailResponse>(deliveryTariffPaths.tariff(scope, tariffId)),
    );
    return result.value;
  }

  async create(scope: BrandScope, request: CreateTariffRequest): Promise<TariffView> {
    return firstValueFrom(
      this.api.post<CreateTariffRequest, TariffView>(
        deliveryTariffPaths.tariffCreate(scope),
        command(request),
      ),
    );
  }

  async draftFlatVersion(
    scope: BrandScope,
    tariffId: string,
    request: DraftFlatVersionRequest,
  ): Promise<VersionView> {
    const body = {
      currency: request.currency,
      feeSource: request.feeSource,
      distanceMode: request.distanceMode,
      roadFactorBasisPoints: 13_000,
      maxDistanceMeters: request.maxDistanceMeters,
      minFeeMinor: request.minFeeMinor,
      maxFeeMinor: request.maxFeeMinor ?? null,
      bands: [
        {
          bandSet: null,
          fromMeters: 0,
          toMeters: request.maxDistanceMeters,
          baseMinor: request.baseMinor,
          perKmMinor: request.perKmMinor,
        },
      ],
      timeRules: [],
      discounts: [],
      actorId: request.actorId,
    };
    return firstValueFrom(
      this.api.post<typeof body, VersionView>(
        deliveryTariffPaths.tariffVersions(scope, tariffId),
        command(body),
      ),
    );
  }

  async activate(
    scope: BrandScope,
    tariffId: string,
    version: number,
    actorId: string,
  ): Promise<VersionView> {
    return firstValueFrom(
      this.api.post<{ actorId: string }, VersionView>(
        deliveryTariffPaths.tariffVersionActivate(scope, tariffId, version),
        command({ actorId }),
      ),
    );
  }

  async bindLocation(scope: BrandScope, tariffId: string, locationId: string): Promise<void> {
    await firstValueFrom(
      this.api.post<{ locationId: string }, void>(
        deliveryTariffPaths.tariffLocations(scope, tariffId),
        command({ locationId }),
      ),
    );
  }
}

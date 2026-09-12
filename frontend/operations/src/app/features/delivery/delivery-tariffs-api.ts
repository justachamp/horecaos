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
 * One distance band.
 *
 * `bandSet` is null for the base table. A named set is a complete rate table
 * in its own right, put in force by a time rule naming it — and a set no rule
 * names is refused at activation, because its bands would never price
 * anything.
 *
 * `baseMinor` is the flat charge for *entering* this band, not the cumulative
 * charge for reaching it: bands accumulate (V0032).
 */
export interface BandRequest {
  readonly bandSet?: string | null;
  readonly fromMeters: number;
  readonly toMeters: number;
  readonly baseMinor: number;
  readonly perKmMinor: number;
}

/** A peak window. `dayMask` bit 0 is Monday, so weekdays is 31 and the whole week 127. */
export interface TimeRuleRequest {
  readonly priority: number;
  readonly dayMask: number;
  readonly fromTime: string;
  readonly toTime: string;
  readonly bandSet?: string | null;
  readonly multiplierBasisPoints: number;
  readonly surchargeMinor: number;
}

/** A standing discount on the rate table, capped at the fee when it resolves. */
export interface DiscountRequest {
  readonly priority: number;
  readonly kind: 'AMOUNT' | 'DISTANCE_ALLOWANCE';
  readonly amountMinor?: number | null;
  readonly allowanceMeters?: number | null;
  readonly dayMask: number;
  readonly fromTime: string;
  readonly toTime: string;
}

/**
 * A whole rate table as the console now authors it (ADR 0104).
 *
 * The previous revision drafted one band, no time rules and no discounts, and
 * said so honestly. What it could not say is that the backend had accepted all
 * of it since V0032: `DeliveryTariffController.DraftTariffVersionRequest`
 * takes many bands with named band sets, day-masked peak windows with a
 * multiplier and a surcharge, `AMOUNT` and `DISTANCE_ALLOWANCE` discounts,
 * min/max fee, a rounding step and rule, `feeSource` and `distanceMode`. An
 * operator pricing a real city had to ask a developer for everything past the
 * first band.
 *
 * There is no `actorId`: the operations surface reads the actor from the
 * caller's own token.
 */
export interface DraftTariffVersionRequest {
  readonly currency: string;
  readonly feeSource: 'TARIFF' | 'PROVIDER_QUOTE';
  /**
   * `ROAD` needs a routing installation; activation refuses it otherwise, and
   * a `ROAD` tariff whose routing provider does not answer prices from the
   * straight line inflated by `roadFactorBasisPoints` and stamps
   * `RADIUS_FALLBACK` on the resolution. The page renders both facts rather
   * than letting either arrive as a surprise.
   */
  readonly distanceMode: 'RADIUS' | 'ROAD';
  readonly roadFactorBasisPoints: number;
  readonly routingProviderInstallationId?: string | null;
  readonly maxDistanceMeters: number;
  readonly minFeeMinor: number;
  readonly maxFeeMinor?: number | null;
  readonly distanceAccrual?: 'STARTED_KILOMETRE' | 'PRORATED_METRE' | null;
  readonly feeRoundingStepMinor?: number | null;
  readonly feeRoundingRule?: 'HALF_UP' | 'HALF_EVEN' | null;
  readonly bands: readonly BandRequest[];
  readonly timeRules: readonly TimeRuleRequest[];
  readonly discounts: readonly DiscountRequest[];
}

export interface VersionView {
  readonly tariffId: string;
  readonly version: number;
  readonly status: string;
}

/**
 * Delivery tariffs (operations §3.7) — `OperationsDeliveryTariffController`
 * (ADR 0037, ADR 0104, `operations` OpenAPI surface).
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

  async draftVersion(
    scope: BrandScope,
    tariffId: string,
    request: DraftTariffVersionRequest,
  ): Promise<VersionView> {
    const body: DraftTariffVersionRequest = {
      ...request,
      routingProviderInstallationId: request.routingProviderInstallationId ?? null,
      maxFeeMinor: request.maxFeeMinor ?? null,
      distanceAccrual: request.distanceAccrual ?? null,
      feeRoundingStepMinor: request.feeRoundingStepMinor ?? null,
      feeRoundingRule: request.feeRoundingRule ?? null,
      bands: request.bands.map((band) => ({ ...band, bandSet: band.bandSet ?? null })),
      timeRules: request.timeRules.map((rule) => ({ ...rule, bandSet: rule.bandSet ?? null })),
      discounts: request.discounts.map((discount) => ({
        ...discount,
        amountMinor: discount.amountMinor ?? null,
        allowanceMeters: discount.allowanceMeters ?? null,
      })),
    };
    return firstValueFrom(
      this.api.post<DraftTariffVersionRequest, VersionView>(
        deliveryTariffPaths.tariffVersions(scope, tariffId),
        command(body),
      ),
    );
  }

  async activate(scope: BrandScope, tariffId: string, version: number): Promise<VersionView> {
    return firstValueFrom(
      this.api.post<Record<string, never>, VersionView>(
        deliveryTariffPaths.tariffVersionActivate(scope, tariffId, version),
        command({}),
      ),
    );
  }

  /**
   * Binds the rate table to a branch — the middle rung of ADR 0037's
   * precedence chain, which existed on the backend from the start and had no
   * caller anywhere in this console, so every location rode the brand default.
   */
  async bindLocation(scope: BrandScope, tariffId: string, locationId: string): Promise<void> {
    await firstValueFrom(
      this.api.post<{ locationId: string }, void>(
        deliveryTariffPaths.tariffLocations(scope, tariffId),
        command({ locationId }),
      ),
    );
  }
}

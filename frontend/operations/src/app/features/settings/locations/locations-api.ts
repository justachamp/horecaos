import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { command } from '../../../core/api/idempotency';
import { LocationScope } from '../../../core/api/operations-paths';
import { settingsPaths } from '../../../core/api/settings-paths';

export type CoordinateSource = 'NOT_GEOCODED' | 'GEOCODER' | 'MERCHANT_PIN' | 'OPERATOR_PIN';

/** Mirrors uz.horecaos.platform.tenancy.application.TenantControlPlaneService.LocationView. */
export interface LocationView {
  readonly id: string;
  readonly tenantId: string;
  readonly brandId: string;
  readonly code: string;
  readonly slug: string;
  readonly displayName: string;
  readonly timezone: string;
  readonly status: 'DRAFT' | 'ACTIVE' | 'SUSPENDED' | 'ARCHIVED';
  readonly addressLine: string | null;
  readonly district: string | null;
  readonly city: string | null;
  readonly landmark: string | null;
  readonly contactPhone: string | null;
  readonly latitude: number | null;
  readonly longitude: number | null;
  readonly coordinateSource: CoordinateSource;
}

export interface DescribeLocationRequest {
  readonly addressLine?: string;
  readonly district?: string;
  readonly city?: string;
  readonly landmark?: string;
  readonly contactPhone?: string;
  readonly latitude?: number;
  readonly longitude?: number;
  readonly coordinateSource?: CoordinateSource;
}

export interface RuleView {
  readonly dayOfWeek: number;
  readonly opensAt: string;
  readonly closesAt: string;
}

export interface ExceptionView {
  readonly date: string;
  readonly closedAllDay: boolean;
  readonly opensAt: string | null;
  readonly closesAt: string | null;
}

export interface ModeBindingView {
  readonly fulfillmentMode: string;
  readonly scheduleId: string;
  readonly scheduleName: string;
  readonly acceptsScheduledOrders: boolean;
  readonly sharedWithLocationCount: number;
  readonly rules: readonly RuleView[];
  readonly exceptions: readonly ExceptionView[];
}

export interface BandView {
  readonly fulfillmentMode: string | null;
  readonly dayOfWeek: number | null;
  readonly startsAt: string;
  readonly endsAt: string;
  readonly durationMinutes: number;
  readonly priority: number;
}

/** Mirrors uz.horecaos.platform.tenancy.web.LocationServiceOperationsController.ServiceSummaryResponse. */
export interface ServiceSummaryResponse {
  readonly mode: 'FOLLOW_SCHEDULE' | 'FORCE_OPEN' | 'FORCE_CLOSED';
  readonly effectiveMode: 'FOLLOW_SCHEDULE' | 'FORCE_OPEN' | 'FORCE_CLOSED';
  readonly reasonCode: string | null;
  readonly effectiveUntil: string | null;
  readonly maxConcurrentOrders: number | null;
  readonly openOrderCount: number;
  readonly bindings: readonly ModeBindingView[];
  readonly preparationBands: readonly BandView[];
}

export interface ChangeServiceStateRequest {
  readonly mode: 'FOLLOW_SCHEDULE' | 'FORCE_OPEN' | 'FORCE_CLOSED';
  readonly reasonCode?: string;
  readonly note?: string;
  readonly effectiveUntil?: string;
}

/**
 * 10.2 Locations. `LocationServiceOperationsController` (list/profile/summary,
 * plus the service-state and capacity writes) is on the operations surface —
 * new in wave 26. `describePlace` reuses `TenantControlPlaneController`'s
 * existing `place` write, cross-surface — see `settings-paths.ts`.
 */
@Injectable({ providedIn: 'root' })
export class LocationsApi {
  private readonly api = inject(ApiClient);

  async list(scope: LocationScope): Promise<readonly LocationView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly LocationView[]>(settingsPaths.locations(scope)),
    );
    return result.value ?? [];
  }

  async profile(scope: LocationScope): Promise<LocationView> {
    const result = await firstValueFrom(this.api.get<LocationView>(settingsPaths.location(scope)));
    return result.value;
  }

  async describePlace(
    scope: LocationScope,
    request: DescribeLocationRequest,
  ): Promise<LocationView> {
    return firstValueFrom(
      this.api.send<DescribeLocationRequest, LocationView>(
        'PUT',
        settingsPaths.locationPlace(scope),
        command(request),
      ),
    ).then((response) => response.body as LocationView);
  }

  async serviceSummary(scope: LocationScope): Promise<ServiceSummaryResponse> {
    const result = await firstValueFrom(
      this.api.get<ServiceSummaryResponse>(settingsPaths.locationServiceSummary(scope)),
    );
    return result.value;
  }

  async changeServiceState(
    scope: LocationScope,
    request: ChangeServiceStateRequest,
  ): Promise<void> {
    await firstValueFrom(
      this.api.send<ChangeServiceStateRequest, void>(
        'POST',
        settingsPaths.locationServiceState(scope),
        command(request),
      ),
    );
  }

  async setCapacity(scope: LocationScope, maxConcurrentOrders: number | null): Promise<void> {
    await firstValueFrom(
      this.api.send<{ maxConcurrentOrders: number | null }, void>(
        'PUT',
        settingsPaths.locationCapacity(scope),
        command({ maxConcurrentOrders }),
      ),
    );
  }
}

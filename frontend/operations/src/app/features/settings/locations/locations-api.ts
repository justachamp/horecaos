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
  /**
   * Explicitly removes a previously-set landmark, the same escape hatch
   * `coordinateSource: 'NOT_GEOCODED'` is for the point. An omitted
   * `landmark` alone (the shape an emptied form field collapses to) means
   * "this write did not touch the landmark" and carries the stored value
   * through unchanged — see `DescribeLocationCommand.toPlace`'s own doc.
   */
  readonly clearLandmark?: boolean;
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

export type ServiceMode = 'FOLLOW_SCHEDULE' | 'FORCE_OPEN' | 'FORCE_CLOSED';

export interface ChangeServiceStateRequest {
  readonly mode: ServiceMode;
  readonly reasonCode?: string;
  readonly note?: string;
  readonly effectiveUntil?: string;
}

/**
 * Mirrors uz.horecaos.platform.tenancy.web.OperationsBrandController
 * .LocationServiceStateResponse — one row of the branch list's batched state
 * read (10.2a, wave P32).
 */
export interface LocationServiceStateView {
  readonly locationId: string;
  readonly mode: ServiceMode;
  readonly effectiveMode: ServiceMode;
  readonly reasonCode: string | null;
  readonly effectiveUntil: string | null;
}

/**
 * Mirrors uz.horecaos.platform.tenancy.web.ServiceScheduleController
 * .ScheduleSummaryResponse (wave P43) — the Hours tab's rebind picker and
 * the source of `sharedWithLocationCount`'s own live count.
 */
export interface ScheduleSummaryView {
  readonly id: string;
  readonly name: string;
  readonly acceptsScheduledOrders: boolean;
  readonly boundLocationCount: number;
}

export interface RuleRequest {
  readonly dayOfWeek: number;
  readonly opensAt: string;
  readonly closesAt: string;
}

/**
 * Mirrors uz.horecaos.platform.tenancy.web.ServiceScheduleController
 * .ExceptionRequest. Unlike `ExceptionResponse`, `label`/`reason` are
 * required here — the server rejects a blank one with `VALIDATION_FAILED`.
 */
export interface ExceptionRequest {
  readonly date: string;
  readonly closedAllDay: boolean;
  readonly opensAt?: string;
  readonly closesAt?: string;
  readonly label: string;
  readonly reason: string;
}

export interface BindingRequest {
  readonly fulfillmentMode: string;
  readonly scheduleId: string;
}

export interface BandRequest {
  readonly fulfillmentMode?: string | null;
  readonly dayOfWeek?: number | null;
  readonly startsAt: string;
  readonly endsAt: string;
  readonly durationMinutes: number;
  readonly priority: number;
}

/**
 * 10.2 Locations. `LocationServiceOperationsController` (list/profile/summary,
 * plus the service-state and capacity writes) is on the operations surface —
 * new in wave 26. `describePlace` reuses `TenantControlPlaneController`'s
 * existing `place` write, cross-surface — see `settings-paths.ts`.
 *
 * `serviceStates` is new in wave P32: before it, the branch list's only way
 * to know which branches were shut was one {@link serviceSummary}-shaped call
 * per row — the N+1 `locations-page.ts`'s own comment named as the reason it
 * shipped without a state column, a state filter, or a close/open row action.
 *
 * Wave P43 (gap map row `10.2c`) adds {@link listSchedules}, {@link
 * replaceScheduleRules}, {@link upsertScheduleException}, {@link
 * bindSchedule} and {@link replacePreparationBands} — the writes {@link
 * serviceSummary} already had a reader for but nothing on this screen called.
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

  /** Every location's own manual-override state, batched — the branch list's state column and filter. */
  async serviceStates(scope: LocationScope): Promise<readonly LocationServiceStateView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly LocationServiceStateView[]>(
        settingsPaths.locationsServiceStates(scope),
      ),
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

  /**
   * `ServiceScheduleController.list` (wave P43) — every timetable this brand
   * owns, for the Hours tab's rebind picker. `settingsPaths.brandServiceSchedules`
   * pre-dated this wave as the base for the `rules`/`exceptions` writes below;
   * this is its first `GET` caller.
   */
  async listSchedules(scope: LocationScope): Promise<readonly ScheduleSummaryView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly ScheduleSummaryView[]>(settingsPaths.brandServiceSchedules(scope)),
    );
    return result.value ?? [];
  }

  /** `ServiceScheduleController.replaceRules` — the whole weekly grid, whole-set. */
  async replaceScheduleRules(
    scope: LocationScope,
    scheduleId: string,
    rules: readonly RuleRequest[],
  ): Promise<void> {
    await firstValueFrom(
      this.api.send<{ rules: readonly RuleRequest[] }, void>(
        'PUT',
        settingsPaths.scheduleRules(scope, scheduleId),
        command({ rules }),
      ),
    );
  }

  /**
   * `ServiceScheduleController.upsertException` — one dated exception per
   * call, upsert by date. There is no delete: a row removed locally from
   * `q-schedule-grid`'s draft and then saved stays exactly as it was on the
   * server until it is edited back over, not deleted (see
   * `location-detail-pane.ts`'s `saveExceptions` for where that is spelled
   * out to the operator).
   */
  async upsertScheduleException(
    scope: LocationScope,
    scheduleId: string,
    exception: ExceptionRequest,
  ): Promise<void> {
    await firstValueFrom(
      this.api.send<ExceptionRequest, void>(
        'PUT',
        settingsPaths.scheduleExceptions(scope, scheduleId),
        command(exception),
      ),
    );
  }

  /** `LocationServiceOperationsController.bindSchedule` — rebinds one fulfilment mode. */
  async bindSchedule(scope: LocationScope, request: BindingRequest): Promise<void> {
    await firstValueFrom(
      this.api.send<BindingRequest, void>(
        'PUT',
        settingsPaths.locationServiceBindings(scope),
        command(request),
      ),
    );
  }

  /** `LocationServiceOperationsController.replacePreparationBands` — the whole set. */
  async replacePreparationBands(
    scope: LocationScope,
    bands: readonly BandRequest[],
  ): Promise<void> {
    await firstValueFrom(
      this.api.send<{ bands: readonly BandRequest[] }, void>(
        'PUT',
        settingsPaths.locationPreparationBands(scope),
        command({ bands }),
      ),
    );
  }
}

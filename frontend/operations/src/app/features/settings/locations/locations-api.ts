import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { parseETag } from '../../../core/api/aggregate-version';
import { ApiClient } from '../../../core/api/api-client';
import { command } from '../../../core/api/idempotency';
import { LocationScope } from '../../../core/api/operations-paths';
import { settingsPaths } from '../../../core/api/settings-paths';

export type CoordinateSource = 'NOT_GEOCODED' | 'GEOCODER' | 'MERCHANT_PIN' | 'OPERATOR_PIN';

/** `uz.horecaos.platform.tenancy.domain.BrandProfile.KNOWN_LOCALES`, mirrored — same closed set brand-profile.locale editing uses. */
export type LocationLocaleCode = 'ru' | 'uz-Latn' | 'en';
export const LOCATION_KNOWN_LOCALES: readonly LocationLocaleCode[] = ['ru', 'uz-Latn', 'en'];

/** One locale's own localized content for a branch (10.2b). Mirrors ...TenantControlPlaneService.LocationLocaleView. */
export interface LocationLocaleView {
  readonly locale: LocationLocaleCode;
  readonly displayName: string | null;
  readonly description: string | null;
}

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
  /** 10.2b: the branch list's own manual ordering, lowest first. */
  readonly sortOrder: number;
  readonly seats: number | null;
  /** Minor units of {@link averageChequeCurrency}; both null or both set. */
  readonly averageChequeAmount: number | null;
  readonly averageChequeCurrency: string | null;
  readonly hasParking: boolean;
  readonly hasPlayground: boolean;
  readonly virtualTourUrl: string | null;
  /** This branch's own localized content, one entry per locale it has been given content in. */
  readonly locales: readonly LocationLocaleView[];
}

/** One locale's own localized display name/description for a branch, as authored (10.2b). */
export interface LocationLocaleRequest {
  readonly locale: LocationLocaleCode;
  readonly displayName?: string;
  readonly description?: string;
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
  /**
   * 10.2b: sort order and the venue attributes. Omitted means untouched —
   * the same silent-carry-through rule `clearLandmark`'s own doc names,
   * boxed server-side (`DescribeLocationCommand.toVenue`) for exactly the
   * same reason.
   */
  readonly sortOrder?: number;
  readonly seats?: number;
  /**
   * Explicitly removes a previously-set seat count, the same escape hatch
   * `clearLandmark` is for the landmark. An omitted `seats` alone means
   * "this write did not touch it" and carries the stored value through.
   */
  readonly clearSeats?: boolean;
  readonly averageChequeAmount?: number;
  readonly averageChequeCurrency?: string;
  /**
   * Explicitly removes a previously-set average cheque amount and currency
   * together — the backend requires both null or both set, so one flag
   * clears the pair.
   */
  readonly clearAverageCheque?: boolean;
  readonly hasParking?: boolean;
  readonly hasPlayground?: boolean;
  readonly virtualTourUrl?: string;
  /** Explicitly removes a previously-set virtual tour link. */
  readonly clearVirtualTourUrl?: boolean;
  /** 10.2b: a whole-set write when present — replaces the branch's entire localized-content set. Omitted leaves it untouched. */
  readonly locales?: readonly LocationLocaleRequest[];
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
  /** The bound schedule's own version — the `If-Match` token {@link LocationsApi.deleteScheduleException} needs. */
  readonly scheduleVersion: number;
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
 * Mirrors uz.horecaos.platform.tenancy.web.OperationsBrandController
 * .LocationLegalEntityResponse — one row of the branch list's batched INN
 * read (10.2a, wave 9). A location absent from this list has no legal entity
 * currently assigned.
 */
export interface LocationLegalEntityView {
  readonly locationId: string;
  readonly legalEntityCode: string;
  readonly taxpayerNumber: string;
}

/**
 * Mirrors uz.horecaos.platform.tenancy.web.OperationsBrandController
 * .LocationChannelsResponse — one row of the branch list's batched channel
 * read (10.2a, wave 9). A location absent from this list sells on no active
 * channel.
 */
export interface LocationChannelsView {
  readonly locationId: string;
  readonly channelCodes: readonly string[];
}

/**
 * Mirrors uz.horecaos.platform.tenancy.web.OperationsBrandController
 * .BulkServiceStateRequest — the branch list's bulk close/open bar (10.2a,
 * wave 9), the same fields {@link ChangeServiceStateRequest} carries plus the
 * selection itself.
 */
export interface BulkServiceStateRequest {
  readonly locationIds: readonly string[];
  readonly mode: ServiceMode;
  readonly reasonCode?: string;
  readonly note?: string;
  readonly effectiveUntil?: string;
}

/**
 * Mirrors uz.horecaos.platform.tenancy.web.OperationsBrandController
 * .BulkServiceStateItemResponse — one selected branch's own outcome.
 */
export interface BulkServiceStateItemView {
  readonly locationId: string;
  readonly applied: boolean;
  readonly problemCode: string | null;
}

/**
 * Mirrors uz.horecaos.platform.tenancy.web.OperationsBrandController
 * .BulkServiceStateResponse.
 */
export interface BulkServiceStateResponse {
  readonly requestedCount: number;
  readonly appliedCount: number;
  readonly failedCount: number;
  readonly items: readonly BulkServiceStateItemView[];
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
  /** The schedule's own version — the `If-Match` token {@link deleteScheduleException} needs. */
  readonly version: number;
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
 * A later pass on the same row adds {@link deleteScheduleException}, closing
 * the one gap that wave left: a row removed from the grid was hidden, not
 * deleted.
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

  /** Every location's own assigned legal entity, batched — the branch list's INN filter (wave 9). */
  async legalEntities(scope: LocationScope): Promise<readonly LocationLegalEntityView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly LocationLegalEntityView[]>(settingsPaths.locationsLegalEntities(scope)),
    );
    return result.value ?? [];
  }

  /** Every location's own active sales-channel codes, batched — the branch list's channel filter (wave 9). */
  async channels(scope: LocationScope): Promise<readonly LocationChannelsView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly LocationChannelsView[]>(settingsPaths.locationsChannels(scope)),
    );
    return result.value ?? [];
  }

  /**
   * Closes, force-opens, or returns several selected branches to schedule at
   * once — the branch list's bulk close/open bar (wave 9). Reuses the same
   * batched-state path {@link serviceStates} reads, `POST`ed to instead.
   */
  async bulkChangeServiceState(
    scope: LocationScope,
    request: BulkServiceStateRequest,
  ): Promise<BulkServiceStateResponse> {
    const response = await firstValueFrom(
      this.api.send<BulkServiceStateRequest, BulkServiceStateResponse>(
        'POST',
        settingsPaths.locationsServiceStatesBulk(scope),
        command(request),
      ),
    );
    return response.body as BulkServiceStateResponse;
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

  /** `ServiceScheduleController.upsertException` — one dated exception per call, upsert by date. */
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

  /**
   * `ServiceScheduleController.deleteException` (row 10.2c) — actually
   * removes a dated exception, rather than only ever hiding it in the
   * grid's local draft. `If-Match` carries the owning schedule's version,
   * since an exception has none of its own; the response's `ETag` carries
   * the version this delete produced, for the caller's next write in the
   * same save (see `location-detail-pane.ts`'s `saveHours`, which deletes
   * every removed row before it PUTs the rest).
   *
   * @returns the schedule's new version
   */
  async deleteScheduleException(
    scope: LocationScope,
    scheduleId: string,
    date: string,
    expectedVersion: number,
  ): Promise<number> {
    const response = await firstValueFrom(
      this.api.send<null, void>(
        'DELETE',
        settingsPaths.scheduleException(scope, scheduleId, date),
        command(null),
        { expectedVersion },
      ),
    );
    const version = parseETag(response.headers.get('ETag'));
    if (version === null) {
      throw new Error('DELETE .../exceptions/{date} did not return an ETag with the new version');
    }
    return version;
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

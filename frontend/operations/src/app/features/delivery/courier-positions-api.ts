import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';

/** Mirrors `OperationsCourierPositionController.CourierPin`. */
export interface CourierPin {
  readonly courierId: string;
  readonly latitude: number;
  readonly longitude: number;
  readonly accuracyMeters: number;
  readonly headingDegrees?: number | null;
  readonly speedMps?: number | null;
  readonly batteryPercent?: number | null;
  readonly deviceCharging?: boolean | null;
  readonly activeAssignmentCount: number;
  readonly capturedAt: string;
}

/** Mirrors `OperationsCourierPositionController.CoarseCourier` — on duty, not drawable. */
export interface CoarseCourier {
  readonly courierId: string;
  readonly activeAssignmentCount: number;
  readonly lastFixAt: string;
  /** `ACCURACY_BELOW_MAP_FLOOR` | `LAST_FIX_TOO_OLD`. */
  readonly reason: string;
}

/** Mirrors `OperationsCourierPositionController.FleetResponse`. */
export interface FleetResponse {
  readonly pins: readonly CourierPin[];
  readonly withoutPin: readonly CoarseCourier[];
}

/**
 * The dispatcher's live map (IA 3.2, ADR 0045) —
 * `OperationsCourierPositionController`.
 */
@Injectable({ providedIn: 'root' })
export class CourierPositionsApi {
  private readonly api = inject(ApiClient);

  async fleet(scope: LocationScope): Promise<FleetResponse> {
    const result = await firstValueFrom(
      this.api.get<FleetResponse>(operationsPaths.courierPositions(scope)),
    );
    return result.value;
  }
}

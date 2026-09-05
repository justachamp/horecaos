import { Injectable, inject } from '@angular/core';
import { Observable, firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';

/**
 * Mirrors `KitchenStationController.StationCapacityResponse` (IA §2.6, ADR
 * 0041). `windowStart`/`windowEnd` are `HH:mm:ss` — a plain local time of day,
 * not a date.
 */
export interface CapacityWindowResponse {
  readonly capacityWindowId: string;
  readonly stationId: string;
  /** ISO-8601: 1 = Monday .. 7 = Sunday. */
  readonly weekday: number;
  readonly windowStart: string;
  readonly windowEnd: string;
  readonly portionsPerHour: number;
  readonly version: number;
}

export interface NewCapacityWindow {
  readonly stationId: string;
  readonly weekday: number;
  readonly windowStart: string;
  readonly windowEnd: string;
  readonly portionsPerHour: number;
}

/**
 * IA §2.6 — Capacity & buffer settings (`KitchenStationController`, new this
 * wave). Read today only by this settings screen: the release scheduler does
 * not shift on a ceiling yet — see `CapacityPage`'s own doc for the full
 * honesty accounting.
 */
@Injectable({ providedIn: 'root' })
export class CapacityApi {
  private readonly api = inject(ApiClient);

  async list(scope: LocationScope): Promise<readonly CapacityWindowResponse[]> {
    const result = await firstValueFrom(
      this.api.get<readonly CapacityWindowResponse[]>(operationsPaths.kitchenStationCapacity(scope)),
    );
    return result.value ?? [];
  }

  /** Refused (409) when the window overlaps one already stored for that station and weekday. There is no edit or delete. */
  create(scope: LocationScope, body: NewCapacityWindow): Observable<CapacityWindowResponse> {
    return this.api.post<NewCapacityWindow, CapacityWindowResponse>(
      operationsPaths.kitchenStationCapacity(scope),
      command(body),
    );
  }
}

import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';
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
 * One decrypted point off a courier's stored track — `TelemetryIngestService`'s
 * own `wireForm`, short keys and all: `t` (captured-at, ISO), `lat`, `lon`,
 * `acc` (accuracy, metres), and `hdg`/`spd` only when the device reported them.
 */
export interface RevealedObservation {
  readonly t: string;
  readonly lat: number;
  readonly lon: number;
  readonly acc: number;
  readonly hdg?: number;
  readonly spd?: number;
}

/** Mirrors `OperationsCourierPositionController.RevealedWindow`. */
export interface RevealedWindow {
  readonly windowStart: string;
  readonly windowEnd: string;
  readonly observationCount: number;
  readonly distanceMeters: number;
  readonly observations: readonly RevealedObservation[];
}

/** Mirrors `OperationsCourierPositionController.RevealResponse`. */
export interface TrackRevealResponse {
  readonly courierId: string;
  readonly from: string;
  readonly to: string;
  readonly purpose: string;
  readonly windows: readonly RevealedWindow[];
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

  /**
   * Opens one courier's stored track for a stated purpose (`courier.track.reveal`).
   * Audited server-side in the same transaction as the decryption — see
   * `CourierTrackRevealService`'s own doc for why this is a `POST`, never a `GET`.
   */
  async revealTrack(
    scope: LocationScope,
    courierId: string,
    from: string,
    to: string,
    purpose: string,
  ): Promise<TrackRevealResponse> {
    return firstValueFrom(
      this.api.post<{ from: string; to: string; purpose: string }, TrackRevealResponse>(
        operationsPaths.courierTrackReveals(scope, courierId),
        command({ from, to, purpose }),
      ),
    );
  }
}

import { Injectable, inject } from '@angular/core';
import { Observable, firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';
import { ApiError } from '../../core/api/problem-details';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';

/** The classes a kitchen device is enrolled as (`DevicePrincipalClass`, ADR 0079 and ADR 0151). */
export type KitchenDeviceClass = 'KITCHEN_KDS' | 'KITCHEN_VDU';

/** A station as a wall display names it: its stable code and the three names it renders. */
export interface DeviceStationRef {
  readonly stationId: string;
  readonly code: string;
  readonly displayNameRu: string;
  readonly displayNameUz: string;
  readonly displayNameEn: string;
}

/**
 * Mirrors `KitchenDeviceController.DisplayResponse` (ADR 0151): a wall display's own configuration and
 * what the manager should make of its silence.
 */
export interface DeviceDisplayView {
  /** The station the wall shows; null for the whole branch. */
  readonly station: DeviceStationRef | null;
  /** When the wall last read its projection (RFC 3339, UTC); null before its first read. */
  readonly lastReadAt: string | null;
  /** The `If-Match` the next configuration is sent with. */
  readonly version: number;
  /** No read for `notSeenAfterMinutes`: powered off, off the network or erroring. */
  readonly notSeen: boolean;
  readonly notSeenAfterMinutes: number;
}

/** Mirrors `KitchenDeviceController.PendingEnrolmentResponse`: what a typed code claims. */
export interface PendingEnrolmentView {
  readonly requestedClass: KitchenDeviceClass;
  readonly requestedLabel: string | null;
  readonly expiresAt: string;
}

/**
 * Mirrors `KitchenDeviceController.DeviceResponse` (ADR 0079, row `2/X.2`; ADR 0151). `deviceClass` is
 * the class the device was approved as, `requestedClass` the one it asked for; they differ only when
 * the approver narrowed it. `display` is a wall's configuration and null for every other class.
 */
export interface KitchenDeviceView {
  readonly deviceId: string;
  readonly locationId: string;
  readonly deviceClass: string;
  readonly requestedClass?: string;
  readonly display?: DeviceDisplayView | null;
  readonly displayName: string;
  /** `ACTIVE` | `REVOKED`. */
  readonly status: string;
  readonly enrolledBy: string;
  readonly enrolledAt: string;
  readonly revokedBy: string | null;
  readonly revokedAt: string | null;
  readonly revokedReason: string | null;
}

/**
 * IA row `2/X.2` — the console half of ADR 0079: list a branch's kitchen
 * display devices, approve the `userCode` a new one shows on its own screen,
 * and revoke a lost or replaced tablet. Every call sits behind
 * `kitchen.station.manage` at `LOCATION` scope, the same capability that
 * already gates a branch's station layout — see `KitchenDeviceController`'s
 * own doc for why this reuses that capability rather than minting a new one.
 *
 * Never imported by `device/` — a device authenticates with its own ADR 0079
 * credential, never a staff session, and this class talks to the platform
 * through the staff-scoped {@link ApiClient}.
 */
@Injectable({ providedIn: 'root' })
export class KitchenDevicesApi {
  private readonly api = inject(ApiClient);

  async list(scope: LocationScope): Promise<readonly KitchenDeviceView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly KitchenDeviceView[]>(operationsPaths.kitchenDevices(scope)),
    );
    return result.value ?? [];
  }

  /**
   * What a typed code claims — the class the device asked for — so the approver sees it before
   * choosing the class to approve it as; null for an unknown, spent or expired code (ADR 0151).
   */
  async pending(scope: LocationScope, userCode: string): Promise<PendingEnrolmentView | null> {
    try {
      const result = await firstValueFrom(
        this.api.get<PendingEnrolmentView>(operationsPaths.kitchenDeviceEnrolment(scope, userCode)),
      );
      return result.value ?? null;
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) {
        return null;
      }
      throw error;
    }
  }

  /**
   * Approves the device as `deviceClass`: a touch KDS is granted exactly `kitchen.ticket.read`,
   * `kitchen.display.read` and `kitchen.ticket.advance` at this branch, a wall display exactly
   * `kitchen.display.read`; an approval may narrow what the device asked for and never widen it
   * (the server answers 400 otherwise). Writes an ADR 0027 audit fact naming this manager, the
   * device, this branch and both classes — all server-side, nothing this call chooses beyond the class.
   */
  approve(
    scope: LocationScope,
    userCode: string,
    displayName: string,
    deviceClass: KitchenDeviceClass,
  ): Observable<KitchenDeviceView> {
    return this.api.post<
      { displayName: string; deviceClass: KitchenDeviceClass },
      KitchenDeviceView
    >(operationsPaths.kitchenDeviceApprove(scope, userCode), command({ displayName, deviceClass }));
  }

  /**
   * Points a wall display at one station, or at the whole branch (`stationId` null). The version the
   * form was opened at goes as `If-Match`: a second manager's save in between is a `STALE_VERSION`.
   */
  configureDisplay(
    scope: LocationScope,
    deviceId: string,
    stationId: string | null,
    expectedVersion: number,
  ): Observable<DeviceDisplayView> {
    return this.api.put<{ stationId: string | null }, DeviceDisplayView>(
      operationsPaths.kitchenDeviceDisplay(scope, deviceId),
      command({ stationId }),
      { expectedVersion },
    );
  }

  /**
   * Revokes the device's grant immediately (cache-evicted, refused on its
   * very next request) and disables its Keycloak client. A second revoke of
   * an already-revoked device is not an error — `changed` on the response
   * says whether this call was the one that did it.
   */
  revoke(
    scope: LocationScope,
    deviceId: string,
    reason: string,
  ): Observable<{ readonly changed: boolean }> {
    return this.api.post<{ reason: string }, { readonly changed: boolean }>(
      operationsPaths.kitchenDeviceRevoke(scope, deviceId),
      command({ reason }),
    );
  }
}

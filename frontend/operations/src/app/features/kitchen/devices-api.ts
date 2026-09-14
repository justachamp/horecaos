import { Injectable, inject } from '@angular/core';
import { Observable, firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';

/**
 * Mirrors `KitchenDeviceController.DeviceResponse` (ADR 0079, row `2/X.2`).
 * `deviceClass` is `'KITCHEN_KDS'` today — the only class ADR 0079 builds —
 * carried as a string rather than a closed union so a future VDU/EXPO class
 * (ADR 0041's own rollout step 4) needs no change here.
 */
export interface KitchenDeviceView {
  readonly deviceId: string;
  readonly locationId: string;
  readonly deviceClass: string;
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
   * Grants exactly `kitchen.ticket.read`/`kitchen.ticket.advance` at this
   * branch and writes an ADR 0027 audit fact naming this manager, the
   * device, and this branch — all server-side, nothing this call chooses.
   */
  approve(
    scope: LocationScope,
    userCode: string,
    displayName: string,
  ): Observable<KitchenDeviceView> {
    return this.api.post<{ displayName: string }, KitchenDeviceView>(
      operationsPaths.kitchenDeviceApprove(scope, userCode),
      command({ displayName }),
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

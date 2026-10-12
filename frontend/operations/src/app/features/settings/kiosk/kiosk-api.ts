import { Injectable, inject } from '@angular/core';
import { Observable, firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { command } from '../../../core/api/idempotency';
import { LocationScope } from '../../../core/api/operations-paths';
import { terminalPaths } from '../../../core/api/terminal-paths';
import type { Capability } from '../../../core/auth/session-capabilities';
import type { FeatureFlags } from '../../../core/feature-flags';

/** The dark flag that holds kiosk pairing back until a pilot branch asks for it (ADR 0162, Decision 9). */
export const KIOSK_FLAG = 'feature.kiosk_devices';

/**
 * Whether the kiosk screens are offered to this operator: the dark flag is on for the tenant, or she cannot see it.
 *
 * The console reads flags with `tenant.read` (ADR 0082), which a branch manager does not hold: the read is refused and,
 * by that record's own consequence, every flag reads as off for a person whose only grant is at a location. She is the
 * person ADR 0162 has pair kiosks, so for her the flag cannot decide what is drawn; the server is the gate, and it
 * refuses a pairing while the flag is off (422, naming the flag), which the page shows as it is. A location-scoped flag
 * read, which ADR 0082 says a location-level feature will need, would let the page say so before she types a code.
 */
export function kioskOffered(
  flags: Pick<FeatureFlags, 'isOn'>,
  holds: (capability: Capability) => boolean,
): boolean {
  return flags.isOn(KIOSK_FLAG) || !holds('TENANT_READ');
}

/** Mirrors `KioskDeviceController.PendingResponse`: what a pending code claims. The label is whatever the device sent. */
export interface KioskPendingView {
  /** `KITCHEN_KDS` | `KITCHEN_VDU` | `KIOSK`. */
  readonly requestedClass: string;
  readonly requestedLabel: string | null;
  readonly expiresAt: string;
}

/** Mirrors `KioskDeviceController.ChannelChoiceResponse`: an active KIOSK channel selling at the branch. */
export interface KioskChannelChoice {
  readonly channelId: string;
  readonly code: string;
  readonly displayName: string;
}

export interface KioskChannelView {
  readonly channelId: string;
  readonly code: string;
  readonly displayName: string;
  readonly status: string;
}

/** Mirrors `KioskDeviceController.KioskResponse`. No client id, subject or credential is in it. */
export interface KioskView {
  readonly deviceId: string;
  readonly locationId: string;
  readonly displayName: string;
  /** `ACTIVE` | `REVOKED`. */
  readonly status: string;
  readonly channel: KioskChannelView | null;
  /** The `If-Match` of the next rename or rebind; 0 for a kiosk listed as not configured. */
  readonly version: number;
  /** A kiosk that cannot sell: no binding row, or a channel since archived or withdrawn here. */
  readonly needsAttention: boolean;
  readonly enrolledBy: string | null;
  readonly enrolledAt: string;
  readonly revokedBy: string | null;
  readonly revokedAt: string | null;
  readonly revokedReason: string | null;
  readonly lastSeenAt: string | null;
  readonly lastSeenBuild: string | null;
}

export interface KioskBinding {
  readonly displayName: string;
  readonly channelId: string;
}

/**
 * The console's side of kiosk pairing (ADR 0162): read what a code claims, choose a channel, pair, list, rename or move
 * to another KIOSK channel, and revoke. Every path is under one branch and needs `kiosk.device.manage` there.
 */
@Injectable({ providedIn: 'root' })
export class KioskApi {
  private readonly api = inject(ApiClient);

  async pending(scope: LocationScope, userCode: string): Promise<KioskPendingView> {
    const result = await firstValueFrom(
      this.api.get<KioskPendingView>(terminalPaths.kioskEnrolment(scope, userCode)),
    );
    return result.value;
  }

  async channels(scope: LocationScope): Promise<readonly KioskChannelChoice[]> {
    const result = await firstValueFrom(
      this.api.get<readonly KioskChannelChoice[]>(terminalPaths.kioskChannels(scope)),
    );
    return result.value ?? [];
  }

  approve(scope: LocationScope, userCode: string, body: KioskBinding): Observable<KioskView> {
    return this.api.post<KioskBinding, KioskView>(
      terminalPaths.kioskApprove(scope, userCode),
      command(body),
    );
  }

  async devices(scope: LocationScope): Promise<readonly KioskView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly KioskView[]>(terminalPaths.kioskDevices(scope)),
    );
    return result.value ?? [];
  }

  rebind(
    scope: LocationScope,
    deviceId: string,
    body: KioskBinding,
    expectedVersion: number,
  ): Observable<KioskView> {
    return this.api.put<KioskBinding, KioskView>(
      terminalPaths.kioskDevice(scope, deviceId),
      command(body),
      { expectedVersion },
    );
  }

  revoke(
    scope: LocationScope,
    deviceId: string,
    reason: string,
  ): Observable<{ readonly changed: boolean }> {
    return this.api.post<{ reason: string }, { readonly changed: boolean }>(
      terminalPaths.kioskRevoke(scope, deviceId),
      command({ reason }),
    );
  }
}

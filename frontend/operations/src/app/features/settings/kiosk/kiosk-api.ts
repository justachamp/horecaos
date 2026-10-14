import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, firstValueFrom, map } from 'rxjs';

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

/** One picture on a KIOSK channel's idle loop (ADR 0187). `url` is a path on the API's own origin. */
export interface IdleMediaItem {
  readonly id: string;
  readonly assetId: string;
  readonly position: number;
  readonly url: string;
}

/** Mirrors `KioskIdleMediaService.IdleMediaList`: the pictures in play order, the version the next change is sent against, and the platform's limits. */
export interface IdleMediaList {
  readonly channelId: string;
  /** The `If-Match` of the next attach, reorder or remove; 0 for a channel that has never had a picture. */
  readonly version: number;
  readonly items: readonly IdleMediaItem[];
  readonly maximumCount: number;
  readonly maximumBytes: number;
  /** `9:16`. */
  readonly ratio: string;
}

export interface IdleUploadTicket {
  readonly assetId: string;
  readonly uploadUrl: string;
  readonly requiredHeaders: Readonly<Record<string, string>>;
  readonly expiresAt: string;
}

/** Where a picture's verification stands. `rejectionCode` is set only for `REJECTED`. */
export interface IdleUploadState {
  readonly assetId: string;
  readonly status: 'PENDING_UPLOAD' | 'UPLOADED' | 'AVAILABLE' | 'REJECTED' | string;
  readonly rejectionCode: string | null;
}

/** An upload the platform's own pipeline refused after reading the file: the reason is its code. */
export class IdleUploadRejected extends Error {
  constructor(readonly rejectionCode: string | null) {
    super(`The picture was rejected (${rejectionCode ?? 'no reason given'})`);
  }
}

/** The platform did not finish verifying the picture in the time the console waits. */
export class IdleUploadTimedOut extends Error {}

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
  private readonly http = inject(HttpClient);

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

  // ---- a KIOSK channel's idle media (ADR 0187) ----

  async idleMedia(scope: LocationScope, channelId: string): Promise<IdleMediaList> {
    const result = await firstValueFrom(
      this.api.get<IdleMediaList>(terminalPaths.kioskIdleMedia(scope, channelId)),
    );
    return result.value;
  }

  attachIdle(
    scope: LocationScope,
    channelId: string,
    assetId: string,
    expectedVersion: number,
  ): Observable<IdleMediaList> {
    return this.api.post<{ assetId: string }, IdleMediaList>(
      terminalPaths.kioskIdleMedia(scope, channelId),
      command({ assetId }),
      { expectedVersion },
    );
  }

  reorderIdle(
    scope: LocationScope,
    channelId: string,
    itemIds: readonly string[],
    expectedVersion: number,
  ): Observable<IdleMediaList> {
    return this.api.put<{ itemIds: readonly string[] }, IdleMediaList>(
      terminalPaths.kioskIdleMediaOrder(scope, channelId),
      command({ itemIds }),
      { expectedVersion },
    );
  }

  removeIdle(
    scope: LocationScope,
    channelId: string,
    itemId: string,
    expectedVersion: number,
  ): Observable<IdleMediaList> {
    return this.api
      .send<undefined, IdleMediaList>(
        'DELETE',
        terminalPaths.kioskIdleMediaItem(scope, channelId, itemId),
        command(undefined),
        { expectedVersion },
      )
      .pipe(map((response) => response.body as IdleMediaList));
  }

  /**
   * The whole upload of one picture: ask for a constrained URL, put the bytes to the object store (not a platform call, so
   * `ApiClient`'s conventions do not apply to that one request), claim it complete, and wait for the pipeline to read the
   * file's own header and decide. Resolves with the asset id once it is `AVAILABLE`; rejects with
   * {@link IdleUploadRejected} when the pipeline refused it and {@link IdleUploadTimedOut} when it has not decided.
   */
  async uploadIdlePicture(
    scope: LocationScope,
    channelId: string,
    file: File,
    wait: { readonly intervalMs?: number; readonly attempts?: number } = {},
  ): Promise<string> {
    const ticket = await firstValueFrom(
      this.api.post<
        { contentType: string; sizeBytes: number; filename: string },
        IdleUploadTicket
      >(
        terminalPaths.kioskIdleMediaUploadRequest(scope, channelId),
        command({
          contentType: file.type || 'application/octet-stream',
          sizeBytes: file.size,
          filename: file.name,
        }),
      ),
    );
    await firstValueFrom(
      this.http.put(ticket.uploadUrl, file, { headers: { ...ticket.requiredHeaders } }),
    );
    await firstValueFrom(
      this.api.post<undefined, unknown>(
        terminalPaths.kioskIdleMediaFinalize(scope, channelId, ticket.assetId),
        command(undefined),
      ),
    );
    const intervalMs = wait.intervalMs ?? 1000;
    const attempts = wait.attempts ?? 30;
    for (let attempt = 0; attempt < attempts; attempt++) {
      const state = (
        await firstValueFrom(
          this.api.get<IdleUploadState>(
            terminalPaths.kioskIdleMediaUpload(scope, channelId, ticket.assetId),
          ),
        )
      ).value;
      if (state.status === 'AVAILABLE') {
        return ticket.assetId;
      }
      if (state.status === 'REJECTED') {
        throw new IdleUploadRejected(state.rejectionCode);
      }
      await new Promise<void>((resolve) => setTimeout(resolve, intervalMs));
    }
    throw new IdleUploadTimedOut();
  }
}

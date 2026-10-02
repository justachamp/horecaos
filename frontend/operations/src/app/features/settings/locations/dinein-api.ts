import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { command } from '../../../core/api/idempotency';
import { LocationScope, operationsPaths } from '../../../core/api/operations-paths';

/** `dinein.location_settings.qr_mode` (ADR 0047). `SETTLE_OPEN_TICKET` exists on the wire but is refused everywhere. */
export type QrMode = 'VIEW_ONLY' | 'ORDER_AND_PAY' | 'SETTLE_OPEN_TICKET';

/** Mirrors `FloorPlanController.SettingsResponse`. */
export interface DineInSettingsView {
  readonly locationId: string;
  readonly qrMode: QrMode;
  readonly turnaroundMinutes: number;
  readonly guestSessionTtlMinutes: number;
  readonly serviceChargeRateBp: number;
  readonly version: number;
  /**
   * The verified hostname the storefront answers on
   * (`QrChannelSource.storefrontHostname`): the tenant's `QR_TABLE` channel's
   * own, else its one `WEB` channel's. Null when none qualifies — an
   * unverified custom domain does not, and neither do two candidates with no
   * telling which is the guest's storefront. A printed table card encodes
   * `https://<hostname>/dine-in/<token>` from it, and falls back to the bare
   * token with a warning when it is null. A public DNS name, not a secret.
   */
  readonly storefrontHostname?: string | null;
  /**
   * ADR 0143: whether a guest who has scanned a free table may seat themselves. Off
   * until a manager turns it on with a reason; it does anything only while `qrMode`
   * is `ORDER_AND_PAY`.
   */
  readonly walkInSelfSeat: boolean;
  /** How long an unconfirmed claim holds its table (2..60). */
  readonly walkInClaimTtlMinutes: number;
  /** How far ahead a confirmed booking's hold keeps a self-seating guest off the table (0..480). */
  readonly walkInHorizonMinutes: number;
  /** The most live unconfirmed claims the branch allows at once (0..100). */
  readonly walkInMaxUnconfirmed: number;
  /** How many claims one account may open at this branch in a day (1..20). */
  readonly walkInDailyClaimsPerAccount: number;
  /** How long past its window a claim with a payment still in flight is kept (0..120). */
  readonly walkInPaymentDeferMinutes: number;
  /** The currency a self-seated session bills in (interim, ADR 0055). */
  readonly sessionCurrency: string;
}

/** Mirrors `FloorPlanController.SettingsRequest`. The self-seating fields are optional: omitted leaves them as they are. */
export interface DineInSettingsInput {
  readonly qrMode: QrMode;
  readonly turnaroundMinutes: number;
  readonly guestSessionTtlMinutes: number;
  readonly serviceChargeRateBp: number;
  readonly walkInSelfSeat?: boolean;
  readonly walkInClaimTtlMinutes?: number;
  readonly walkInHorizonMinutes?: number;
  readonly walkInMaxUnconfirmed?: number;
  readonly walkInDailyClaimsPerAccount?: number;
  readonly walkInPaymentDeferMinutes?: number;
  readonly reason: string;
}

/** Mirrors `FloorPlanController.SectionResponse`. */
export interface SectionView {
  readonly sectionId: string;
  readonly code: string;
  readonly displayName: string;
  readonly sortOrder: number;
  readonly status: string;
  readonly version: number;
}

export interface SectionInput {
  readonly code: string;
  readonly displayName: string;
  readonly sortOrder?: number;
}

/**
 * Mirrors `FloorPlanController.TableResponse` — `layoutX`/`layoutY` are new
 * on the wire this wave (P38); everything else pre-dates it.
 */
export interface TableView {
  readonly tableId: string;
  readonly sectionId: string;
  readonly code: string;
  readonly displayName: string;
  readonly seats: number;
  readonly joinable: boolean;
  readonly layoutX: number | null;
  readonly layoutY: number | null;
  readonly status: 'ACTIVE' | 'OUT_OF_SERVICE' | 'ARCHIVED';
  readonly qrIssued: boolean;
  readonly qrRotatedAt: string | null;
  readonly version: number;
}

export interface TableInput {
  readonly sectionId: string;
  readonly code: string;
  readonly displayName: string;
  readonly seats: number;
  readonly joinable?: boolean;
  readonly layoutX?: number | null;
  readonly layoutY?: number | null;
}

/** Mirrors `FloorPlanController.RotationResponse` — `qrToken` is returned exactly once, never stored. */
export interface QrRotationView {
  readonly tableId: string;
  readonly qrToken: string;
  readonly rotatedAt: string;
  readonly version: number;
  readonly revokedGuestSessions: number;
}

/**
 * The dine-in floor plan (ADR 0047, `FloorPlanController`), rows `10.2d`/
 * `X.36`/`10.5b` (wave P38). `PUT .../tables/{tableId}` and the response's
 * `layoutX`/`layoutY` are new this wave — before it, a table's canvas
 * position was write-only through {@link createTable} with nowhere to save
 * a drag. Everything else already existed on `FloorPlanController` with no
 * caller anywhere in this app.
 */
@Injectable({ providedIn: 'root' })
export class DineInApi {
  private readonly api = inject(ApiClient);

  async settings(scope: LocationScope): Promise<DineInSettingsView> {
    const result = await firstValueFrom(
      this.api.get<DineInSettingsView>(operationsPaths.dineInSettings(scope)),
    );
    return result.value;
  }

  /**
   * `expectedVersion` is the version `settings` last returned (a never-configured branch
   * reads as 0): two managers editing one branch's settings produce one change and one
   * stale-version refusal, not a silent last-writer-wins (ADR 0031).
   */
  async configure(
    scope: LocationScope,
    input: DineInSettingsInput,
    expectedVersion: number,
  ): Promise<DineInSettingsView> {
    return firstValueFrom(
      this.api.put<DineInSettingsInput, DineInSettingsView>(
        operationsPaths.dineInSettings(scope),
        command(input),
        { expectedVersion },
      ),
    );
  }

  async sections(scope: LocationScope): Promise<readonly SectionView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly SectionView[]>(operationsPaths.dineInSections(scope)),
    );
    return result.value ?? [];
  }

  async createSection(scope: LocationScope, input: SectionInput): Promise<SectionView> {
    return firstValueFrom(
      this.api.post<SectionInput, SectionView>(
        operationsPaths.dineInSections(scope),
        command(input),
      ),
    );
  }

  async tables(scope: LocationScope): Promise<readonly TableView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly TableView[]>(operationsPaths.dineInTables(scope)),
    );
    return result.value ?? [];
  }

  async createTable(scope: LocationScope, input: TableInput): Promise<TableView> {
    return firstValueFrom(
      this.api.post<TableInput, TableView>(operationsPaths.dineInTables(scope), command(input)),
    );
  }

  /** Drag-to-reposition's save. `expectedVersion` must be the table's current version. */
  async moveTable(
    scope: LocationScope,
    tableId: string,
    layoutX: number,
    layoutY: number,
    reason: string,
    expectedVersion: number,
  ): Promise<TableView> {
    return firstValueFrom(
      this.api.put<{ layoutX: number; layoutY: number; reason: string }, TableView>(
        operationsPaths.dineInTable(scope, tableId),
        command({ layoutX, layoutY, reason }),
        { expectedVersion },
      ),
    );
  }

  async changeTableStatus(
    scope: LocationScope,
    tableId: string,
    status: 'ACTIVE' | 'OUT_OF_SERVICE' | 'ARCHIVED',
    reason: string,
    expectedVersion: number,
  ): Promise<TableView> {
    return firstValueFrom(
      this.api.post<{ status: string; reason: string }, TableView>(
        operationsPaths.dineInTableStatusChanges(scope, tableId),
        command({ status, reason }),
        { expectedVersion },
      ),
    );
  }

  /**
   * Issues or rotates a table's QR token. The plaintext token comes back
   * exactly once, here — there is no endpoint that will ever return it
   * again (see `FloorPlanController`'s own doc): losing it means rotating.
   */
  async rotateQrToken(
    scope: LocationScope,
    tableId: string,
    reason: string,
    expectedVersion: number,
  ): Promise<QrRotationView> {
    return firstValueFrom(
      this.api.post<{ reason: string }, QrRotationView>(
        operationsPaths.dineInTableQrRotations(scope, tableId),
        command({ reason }),
        { expectedVersion },
      ),
    );
  }
}

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
}

/** Mirrors `FloorPlanController.SettingsRequest`. */
export interface DineInSettingsInput {
  readonly qrMode: QrMode;
  readonly turnaroundMinutes: number;
  readonly guestSessionTtlMinutes: number;
  readonly serviceChargeRateBp: number;
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

  async configure(scope: LocationScope, input: DineInSettingsInput): Promise<DineInSettingsView> {
    return firstValueFrom(
      this.api.put<DineInSettingsInput, DineInSettingsView>(
        operationsPaths.dineInSettings(scope),
        command(input),
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

import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';

export interface OpenSessionRequest {
  /** Null for a walk-in. Set, this is what seats a booking — the server moves it CONFIRMED -> SEATED in the same transaction. */
  readonly reservationId?: string | null;
  readonly tableIds: readonly string[];
  readonly partySize?: number | null;
  readonly currency: string;
  readonly reason: string;
}

/** Mirrors `TableSessionController.SessionResponse`. */
export interface SessionView {
  readonly sessionId: string;
  readonly reservationId: string | null;
  readonly partySize: number | null;
  readonly businessDate: string;
  readonly openedAt: string;
  /** `OPEN` | `BILL_REQUESTED` | `SETTLING` | `CLOSED` | `FORCE_CLOSED`. */
  readonly status: string;
  readonly serviceChargeRateBp: number | null;
  readonly currency: string;
  readonly settledTotalMinor: number | null;
  readonly closedAt: string | null;
  readonly closeReasonCode: string | null;
  readonly version: number;
}

/**
 * `TableSessionController` (ADR 0047) — create, rounds, state-actions and
 * force-closures, with zero frontend callers before wave W01. This wave adds
 * the one caller reservations 1.5a needs: opening a session against a
 * confirmed booking, which is what seats it (`open`, below). Rounds,
 * state-actions and force-closures stay uncalled — the running bill and
 * settlement screen is a different, unbuilt surface with no IA row of its
 * own yet.
 */
@Injectable({ providedIn: 'root' })
export class TableSessionsApi {
  private readonly api = inject(ApiClient);

  open(scope: LocationScope, body: OpenSessionRequest): Observable<SessionView> {
    return this.api.post<OpenSessionRequest, SessionView>(
      operationsPaths.dineInSessions(scope),
      command(body),
    );
  }
}

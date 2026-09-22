import { Injectable, inject } from '@angular/core';
import { Observable, tap } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { IntentCommandRegistry } from '../../core/api/idempotency';
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

  /**
   * `open` creates a session — no aggregate exists yet for an `If-Match` to
   * name — so nothing guarded a double click on "Seat" before this
   * (2026-09-21 audit follow-up (a)): a lost response used to mint a second
   * key and could open two sessions for the same booking or the same tables.
   * Keyed by the reservation being seated when there is one (a booking is
   * seated exactly once); for a walk-in, by the joined table ids, since two
   * walk-in opens for the same tables in quick succession are the same risk
   * a reservation id would otherwise guard.
   */
  private readonly openIntents = new IntentCommandRegistry<OpenSessionRequest>();

  open(scope: LocationScope, body: OpenSessionRequest): Observable<SessionView> {
    const id = body.reservationId ?? body.tableIds.join(',');
    const intent = this.openIntents.next(id, body);
    return this.api
      .post<OpenSessionRequest, SessionView>(operationsPaths.dineInSessions(scope), intent)
      .pipe(tap(() => this.openIntents.forget(id)));
  }
}

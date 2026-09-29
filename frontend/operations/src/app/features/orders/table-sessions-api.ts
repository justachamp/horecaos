import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, tap, throwError } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { IntentCommandRegistry } from '../../core/api/idempotency';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';

/**
 * A table session names a currency (`TableSessionController.OpenRequest`) that
 * this console has no clean operations-level read for: the tenant's own
 * `defaultCurrency` is control-plane-only today, and the only read that carries
 * one at this scope is the catalog/menu fetch the New Order screen makes for an
 * unrelated reason -- pulling that in to seat a party would be a real
 * cross-feature coupling for one string. ADR 0055 keeps the platform
 * single-currency per tenant for the pilot, so a fixed constant is the
 * least-wrong value available; replace it with a real read the moment one
 * exists at this scope.
 *
 * Shared by every screen that seats a party (the reservations screen's "Seat",
 * the floor plan's "Seat walk-in", the New Order screen's table picker) so the
 * three cannot drift apart.
 */
export const SESSION_CURRENCY = 'UZS';

export interface OpenSessionRequest {
  /** Null for a walk-in. Set, this is what seats a booking — the server moves it CONFIRMED -> SEATED in the same transaction. */
  readonly reservationId?: string | null;
  readonly tableIds: readonly string[];
  readonly partySize?: number | null;
  readonly currency: string;
  readonly reason: string;
}

/** One table a party sits at, in the order the party joined them (`TableSessionController.SessionTableResponse`). */
export interface SessionTableView {
  readonly tableId: string;
  /** The code printed on the QR card -- what staff say aloud ("T7"). */
  readonly code: string;
  readonly displayName: string;
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
  /** Every table the party sits at; a party pushed together for a large group has several. */
  readonly tables: readonly SessionTableView[];
}

/** Mirrors `TableSessionController.RoundResponse`. */
export interface RoundView {
  readonly sessionId: string;
  readonly orderId: string;
  /** Where the round sits on the bill. The same order attached again answers with the sequence it already has. */
  readonly sequence: number;
}

/**
 * `TableSessionController` (ADR 0047) — the staff side of a table visit.
 *
 * Callers: the reservations screen seats a booking (`open` with a
 * `reservationId`, W01); the floor plan's "Seat walk-in" opens one with none;
 * the New Order screen lists what is live and attaches a placed DINE_IN order as
 * a round, so an operator-keyed order shows its table at once. State-actions and
 * force-closures stay uncalled: the running bill and settlement screen is a
 * different, unbuilt surface with no IA row of its own yet.
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

  /**
   * A round is one order on one session's bill, so that pair is the intent: the
   * console asking again after a lost response reuses the key and is replayed
   * the first answer; the server also answers a second, differently-keyed
   * attach of the same order to the same session with the sequence it already
   * has, so either way the retry is not a conflict.
   */
  private readonly roundIntents = new IntentCommandRegistry<{ orderId: string; reason: string }>();

  open(scope: LocationScope, body: OpenSessionRequest): Observable<SessionView> {
    const id = body.reservationId ?? body.tableIds.join(',');
    const intent = this.openIntents.next(id, body);
    return this.api
      .post<OpenSessionRequest, SessionView>(operationsPaths.dineInSessions(scope), intent)
      .pipe(
        tap(() => this.openIntents.forget(id)),
        catchError((error: unknown) => {
          if (isSettledRejection(error)) {
            this.openIntents.forget(id);
          }
          return throwError(() => error);
        }),
      );
  }

  /** What is live in this room right now (`DINEIN_SESSION_READ`): every party not yet closed, oldest first. */
  live(scope: LocationScope): Observable<readonly SessionView[]> {
    return this.api
      .get<readonly SessionView[]>(operationsPaths.dineInSessions(scope))
      .pipe(map((result) => result.value ?? []));
  }

  /**
   * Attaches an already-placed order to a session's bill (`DINEIN_SESSION_MANAGE`).
   * The order is not created or changed here -- the server records that it
   * belongs to this evening, which is what puts the table on the order board,
   * the order detail and the kitchen ticket.
   */
  attachRound(
    scope: LocationScope,
    sessionId: string,
    orderId: string,
    reason: string,
  ): Observable<RoundView> {
    const id = `${sessionId}:${orderId}`;
    const intent = this.roundIntents.next(id, { orderId, reason });
    return this.api
      .post<{ orderId: string; reason: string }, RoundView>(
        operationsPaths.dineInSessionRounds(scope, sessionId),
        intent,
      )
      .pipe(
        tap(() => this.roundIntents.forget(id)),
        catchError((error: unknown) => {
          if (isSettledRejection(error)) {
            this.roundIntents.forget(id);
          }
          return throwError(() => error);
        }),
      );
  }
}

/**
 * The platform stores a business rejection under the idempotency key like any
 * other settled outcome (`IdempotencyInterceptor`), so asking again under the same
 * key gets the same rejection back -- even after the world has changed. That is
 * exactly wrong here: a table that was occupied a minute ago and is free now must
 * be seatable by the next click, and the next click is a new intent.
 *
 * Only an outcome the client does not know keeps its key: a network failure or a
 * 5xx (the request may have landed), a 408 or 429, and "still in progress" (a
 * first attempt is running under that very key).
 */
function isSettledRejection(error: unknown): boolean {
  return (
    error instanceof ApiError &&
    error.status >= 400 &&
    error.status < 500 &&
    error.status !== 408 &&
    error.status !== 429 &&
    error.code !== ApiErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS
  );
}

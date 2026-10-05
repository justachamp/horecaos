import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, tap, throwError } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { IntentCommandRegistry, command } from '../../core/api/idempotency';
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
  /**
   * Who opened it (ADR 0143): `STAFF` (a person with `dinein.session.manage`) or
   * `GUEST_QR` (a guest seated themselves from the table's code). Never who the guest is.
   */
  readonly origin: 'STAFF' | 'GUEST_QR' | (string & {});
  /** When an unconfirmed guest claim gives the table back; null once confirmed, and for a staff session. */
  readonly claimExpiresAt: string | null;
  /** When staff or an accepted round made a guest's claim an ordinary session; null while it is provisional. */
  readonly confirmedAt: string | null;
}

/**
 * A guest's self-seated table that nobody has confirmed: it lapses at
 * `claimExpiresAt` unless a round the restaurant accepted lands on it or staff keep it
 * (ADR 0143).
 */
export function isUnconfirmedClaim(session: SessionView): boolean {
  return session.origin === 'GUEST_QR' && session.confirmedAt === null;
}

/**
 * Mirrors `TableSessionController.SessionDetailResponse`: a party and its running bill. The bill is
 * summed from the orders on it on every read and never stored, so a read just before a close is the
 * figure the close is then made against.
 */
export interface SessionDetailView {
  readonly session: SessionView;
  /** The orders (rounds) on the bill, in the order they were attached. */
  readonly orderIds: readonly string[];
  readonly currency: string;
  /** Minor units: for UZS, a whole som. */
  readonly totalMinor: number;
  readonly roundCount: number;
  readonly openRoundCount: number;
}

/**
 * The reason code a walkout is closed under. ADR 0047 says a force-close names "a reason code from the
 * tenant's registry", and no such registry exists yet (it is an open decision, reported rather than
 * invented here): until it does, the console closes a party that left without paying under the one
 * code the endpoint's own description names, and the operator's words go in the free-text reason.
 */
export const WALKOUT_REASON_CODE = 'WALKOUT';

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
 * the New Order screen lists what is live and puts a DINE_IN order on a party's bill
 * in the placement itself, so an operator-keyed order shows its table at once.
 * `close` and `forceClose` end a party -- the New Order screen's picker and the floor
 * plan close the party they seat through `q-party-close` -- and `release` is the same
 * close, named for a guest's unconfirmed claim (ADR 0143). The rest of state-actions
 * (asking for the bill, settling) stays uncalled: the running bill and settlement
 * screen is a different, unbuilt surface with no IA row of its own yet.
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
   * Keeps a guest's self-seated table for them (`DINEIN_SESSION_MANAGE`, ADR 0143): a
   * guest who seated themselves holds a claim that lapses if nothing the restaurant
   * accepted is on it, and a member of staff who sees them standing there confirms it.
   * Conditional on the session's version, like every other write to one.
   */
  confirmClaim(
    scope: LocationScope,
    sessionId: string,
    reason: string,
    expectedVersion: number,
  ): Observable<SessionView> {
    return this.api.post<{ reason: string }, SessionView>(
      operationsPaths.dineInSessionClaimConfirmations(scope, sessionId),
      command({ reason }),
      { expectedVersion },
    );
  }

  /**
   * One party and its running bill (`DINEIN_SESSION_READ`). The bill is the sum of the orders on it,
   * read now: the figure a close is made against.
   */
  detail(scope: LocationScope, sessionId: string): Observable<SessionDetailView> {
    return this.api
      .get<SessionDetailView>(operationsPaths.dineInSession(scope, sessionId))
      .pipe(map((result) => result.value));
  }

  /**
   * Ends a party: `state-actions` to `CLOSED` (`DINEIN_SESSION_MANAGE`). The server settles the bill
   * as it stands at this moment -- "paid, or opened in error and owing nothing" -- and frees the
   * tables, so the caller names which of the two it is in `reason`. A party that left without paying
   * is not this: it is {@link forceClose}, which has its own capability. Conditional on the
   * session's version, like every other write to one.
   */
  close(
    scope: LocationScope,
    sessionId: string,
    reason: string,
    expectedVersion: number,
  ): Observable<SessionView> {
    return this.api.post<{ targetStatus: string; reason: string }, SessionView>(
      operationsPaths.dineInSessionStateActions(scope, sessionId),
      command({ targetStatus: 'CLOSED', reason }),
      { expectedVersion },
    );
  }

  /**
   * Gives a guest's unconfirmed claim back to the room by closing the session
   * (`DINEIN_SESSION_MANAGE`, `state-actions` to `CLOSED`) -- the override ADR 0143
   * keeps for staff beside the sweeper. Closing owes nothing and needs no force-close
   * grant when no round is on it.
   */
  release(
    scope: LocationScope,
    sessionId: string,
    reason: string,
    expectedVersion: number,
  ): Observable<SessionView> {
    return this.close(scope, sessionId, reason, expectedVersion);
  }

  /**
   * Closes a party that still owes money -- the walkout (`DINEIN_SESSION_FORCE_CLOSE`, its own
   * capability, not the close above's). The server writes the unsettled amount into the audit record
   * beside the actor and the reason, because an unpaid table that quietly disappears is how a shift's
   * cash shortfall becomes unattributable (ADR 0047).
   */
  forceClose(
    scope: LocationScope,
    sessionId: string,
    reasonCode: string,
    reason: string,
    expectedVersion: number,
  ): Observable<SessionView> {
    return this.api.post<{ reasonCode: string; reason: string }, SessionView>(
      operationsPaths.dineInSessionForceClosures(scope, sessionId),
      command({ reasonCode, reason }),
      { expectedVersion },
    );
  }

  /**
   * Attaches an already-placed order to a session's bill (`DINEIN_SESSION_MANAGE`).
   * The order is not created or changed here -- the server records that it
   * belongs to this evening, which is what puts the table on the order board,
   * the order detail and the kitchen ticket.
   *
   * Not how the New Order screen puts its own order on a bill: that names the
   * party in the placement (`dineInSessionId`), so the order is on the bill or does
   * not exist. This is for an order that already exists and is on no bill.
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

import { Injectable, inject } from '@angular/core';
import { Observable, firstValueFrom, tap } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { IntentCommandRegistry, command } from '../../core/api/idempotency';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';

/** Mirrors `ReservationController.AvailabilityResponse`. */
export interface TableAvailability {
  readonly tableId: string;
  readonly code: string;
  readonly seats: number;
  readonly sectionId: string;
  readonly booked: boolean;
  readonly occupied: boolean;
}

/**
 * Mirrors `ReservationController.ReservationResponse`. `guestName`/
 * `guestPhone`/`note` are null everywhere except a {@link ReservationsApi.find}
 * called with a `purpose` — see that method's own doc for why a booking list
 * is not where that PII is revealed.
 */
export interface ReservationResponse {
  readonly reservationId: string;
  readonly partySize: number;
  readonly requestedFrom: string;
  readonly requestedTo: string;
  readonly turnaroundMinutes: number;
  /** `REQUESTED` | `CONFIRMED` | `REJECTED` | `SEATED` | `CANCELLED` | `NO_SHOW` | `COMPLETED`. */
  readonly status: string;
  readonly tableIds: readonly string[];
  readonly version: number;
  readonly guestName: string | null;
  readonly guestPhone: string | null;
  readonly note: string | null;
}

export interface NewReservation {
  readonly customerAccountId?: string | null;
  readonly guestName: string;
  readonly guestPhone: string;
  readonly secondaryPhone?: string | null;
  readonly note?: string | null;
  readonly partySize: number;
  readonly requestedFrom: string;
  readonly requestedTo: string;
  readonly tableIds: readonly string[];
  readonly sourceChannelId: string;
}

export interface ReservationAmendment {
  readonly partySize: number;
  readonly requestedFrom: string;
  readonly requestedTo: string;
  readonly tableIds: readonly string[];
  /** Optional — blank or omitted leaves the booking's stored name unchanged. */
  readonly guestName?: string | null;
  /** Optional — blank or omitted leaves the booking's stored phone unchanged. */
  readonly guestPhone?: string | null;
  /** Optional — blank or omitted leaves the booking's stored note unchanged. */
  readonly note?: string | null;
  readonly reason: string;
}

/**
 * The host stand (ADR 0047, IA 1.5) — `ReservationController` and the
 * table-availability read on `FloorPlanController`'s sibling path.
 *
 * `list` and the `amendments` endpoint were new in a previous wave; `find`'s
 * `purpose` parameter and `amendments`' guest fields are new in W01 — see
 * `ReservationService.revealGuest` and `ReservationService.amend`'s own
 * docs for the ADR 0029 reveal both now go through.
 */
@Injectable({ providedIn: 'root' })
export class ReservationsApi {
  private readonly api = inject(ApiClient);

  /**
   * {@link create} creates a booking — no aggregate exists yet for an
   * `If-Match` to name — so nothing guarded a double click on "Book" before
   * this (2026-09-21 audit follow-up (a)): a lost response used to mint a
   * second key and could double-book the same table and window. {@link
   * stateAction} and {@link amend} both already carry `expectedVersion`,
   * which a blind retry with the same (now stale) version fails loudly
   * against rather than double-applying — left as they are.
   *
   * One host stand, one active booking form at a time is the real shape of
   * this screen, so a fixed id is enough: a second, genuinely different
   * booking started right after still mints its own key, since its body
   * differs.
   */
  private readonly createIntents = new IntentCommandRegistry<NewReservation>();

  /** Advisory, per `ReservationController`'s own doc: a race is settled by the database, not by this read. */
  async availability(
    scope: LocationScope,
    from: string,
    to: string,
  ): Promise<readonly TableAvailability[]> {
    const result = await firstValueFrom(
      this.api.get<readonly TableAvailability[]>(
        operationsPaths.reservationTableAvailability(scope),
        {
          params: { from, to },
        },
      ),
    );
    return result.value ?? [];
  }

  /** A branch's bookings overlapping the window, every status, oldest first. */
  async listForDay(
    scope: LocationScope,
    from: string,
    to: string,
  ): Promise<readonly ReservationResponse[]> {
    const result = await firstValueFrom(
      this.api.get<readonly ReservationResponse[]>(operationsPaths.reservations(scope), {
        params: { from, to },
      }),
    );
    return result.value ?? [];
  }

  /**
   * One booking. Omit `purpose` for the plain read — no guest name, phone or
   * note. Name one and the three decrypt, recorded server-side as an ADR
   * 0027 audit fact against that purpose — the same reveal-with-a-reason
   * shape the customers feature already uses (see that feature's fixed,
   * English `REVEAL_PURPOSE` constants for why: read by whoever reviews the
   * audit log, not the operator). `reservations-page.ts`'s own
   * `GUEST_REVEAL_PURPOSE` is this screen's one.
   */
  async find(
    scope: LocationScope,
    reservationId: string,
    purpose?: string,
  ): Promise<ReservationResponse> {
    const result = await firstValueFrom(
      this.api.get<ReservationResponse>(operationsPaths.reservation(scope, reservationId), {
        params: purpose ? { purpose } : undefined,
      }),
    );
    return result.value;
  }

  create(scope: LocationScope, body: NewReservation): Observable<ReservationResponse> {
    const intent = this.createIntents.next('create', body);
    return this.api
      .post<NewReservation, ReservationResponse>(operationsPaths.reservations(scope), intent)
      .pipe(tap(() => this.createIntents.forget('create')));
  }

  /** `targetStatus` one of `CONFIRMED` | `REJECTED` | `CANCELLED` | `NO_SHOW` | `COMPLETED` — never `SEATED`, which the server refuses (open a session instead — see {@link TableSessionsApi.open}). */
  stateAction(
    scope: LocationScope,
    reservationId: string,
    targetStatus: string,
    reason: string,
    expectedVersion: number,
  ): Observable<ReservationResponse> {
    return this.api.post<{ targetStatus: string; reason: string }, ReservationResponse>(
      operationsPaths.reservationStateActions(scope, reservationId),
      command({ targetStatus, reason }),
      { expectedVersion },
    );
  }

  /**
   * Refused once the booking is SEATED or terminal — see
   * `ReservationService.amend`'s own doc. `guestName`/`guestPhone`/`note` in
   * `body` are optional corrections, not a re-submission: blank or omitted
   * leaves the booking's stored value unchanged.
   */
  amend(
    scope: LocationScope,
    reservationId: string,
    body: ReservationAmendment,
    expectedVersion: number,
  ): Observable<ReservationResponse> {
    return this.api.post<ReservationAmendment, ReservationResponse>(
      operationsPaths.reservationAmendments(scope, reservationId),
      command(body),
      { expectedVersion },
    );
  }
}

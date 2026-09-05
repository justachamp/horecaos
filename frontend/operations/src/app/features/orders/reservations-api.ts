import { Injectable, inject } from '@angular/core';
import { Observable, firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';
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
 * Mirrors `ReservationController.ReservationResponse`. No guest name, phone or
 * note — `ReservationController`'s own doc explains why a booking list is not
 * where that PII is revealed.
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
  readonly reason: string;
}

/**
 * The host stand (ADR 0047, IA 1.5) — `ReservationController` and the
 * table-availability read on `FloorPlanController`'s sibling path.
 *
 * Every table in this module is the built backend from a previous wave;
 * `list` and the `amendments` endpoint are new this wave — see the
 * `ReservationService` doc for why an amendment cannot touch the guest's
 * name, phone or note.
 */
@Injectable({ providedIn: 'root' })
export class ReservationsApi {
  private readonly api = inject(ApiClient);

  /** Advisory, per `ReservationController`'s own doc: a race is settled by the database, not by this read. */
  async availability(scope: LocationScope, from: string, to: string): Promise<readonly TableAvailability[]> {
    const result = await firstValueFrom(
      this.api.get<readonly TableAvailability[]>(operationsPaths.reservationTableAvailability(scope), {
        params: { from, to },
      }),
    );
    return result.value ?? [];
  }

  /** A branch's bookings overlapping the window, every status, oldest first. */
  async listForDay(scope: LocationScope, from: string, to: string): Promise<readonly ReservationResponse[]> {
    const result = await firstValueFrom(
      this.api.get<readonly ReservationResponse[]>(operationsPaths.reservations(scope), {
        params: { from, to },
      }),
    );
    return result.value ?? [];
  }

  async find(scope: LocationScope, reservationId: string): Promise<ReservationResponse> {
    const result = await firstValueFrom(
      this.api.get<ReservationResponse>(operationsPaths.reservation(scope, reservationId)),
    );
    return result.value;
  }

  create(scope: LocationScope, body: NewReservation): Observable<ReservationResponse> {
    return this.api.post<NewReservation, ReservationResponse>(
      operationsPaths.reservations(scope),
      command(body),
    );
  }

  /** `targetStatus` one of `CONFIRMED` | `REJECTED` | `CANCELLED` | `NO_SHOW` — never `SEATED`, which the server refuses (open a session instead). */
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

  /** Refused once the booking is SEATED or terminal — see `ReservationService.amend`'s own doc. */
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

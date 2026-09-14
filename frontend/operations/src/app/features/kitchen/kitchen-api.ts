import { Injectable, inject } from '@angular/core';
import { Observable, firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';

/**
 * Mirrors `KitchenBoardController.ItemView` — deliberately no dish name or
 * comment. ADR 0041 keeps both off kitchen rows; `KitchenQueuePage` resolves
 * a name by joining `orderLineId` against the order it already fetches for
 * `kitchenNote`, and the per-line note itself (§2.1's "per-line comments"
 * sub-feature, wave P16) through the audited `OrderRevealApi.revealLineNote`
 * — `hasNote` on the order line says whether one exists; the text is a
 * separate ADR 0029 reveal, never carried here or on the order read.
 */
export interface TicketItemView {
  readonly itemId: string;
  readonly orderLineId: string;
  readonly stationId: string;
  readonly quantity: number;
  /** `LOCATION_VARIANT` | `LOCATION_PRODUCT` | `LOCATION_CATEGORY` | `BRAND_ROLE` | `FALLBACK`. */
  readonly routedBy: string;
  /** `QUEUED` | `STARTED` | `READY` | `CANCELLED`. */
  readonly status: string;
  readonly version: number;
}

/** Mirrors `KitchenBoardController.TicketResponse` (wave 30's `fulfilmentMode`/`channelCode`/`createdAt` addition included). */
export interface TicketResponse {
  readonly ticketId: string;
  readonly orderId: string;
  readonly sequenceLabel: string;
  /**
   * The provider-assigned identifier a courier or a customer would actually
   * quote — never `sequenceLabel`, which is HorecaOS's own number (wave T02,
   * gap map row 2.4, IA 2.4's "provider-assigned external identifiers shown
   * to humans"). Present only on a board read (`KitchenApi.board`); absent on
   * a mutation response, the same trade-off `channelSystemType` documents
   * below — a client that already holds it from its last board read loses
   * nothing.
   */
  readonly externalReference?: string | null;
  /** `DELIVERY` | `PICKUP` | `DINE_IN`. */
  readonly fulfilmentMode: string;
  readonly channelCode?: string | null;
  /**
   * `tenant.sales_channels.system_type` resolved off `channelCode` (wave
   * P16) — `AGGREGATOR` is what types the aggregator tab and chip, typed
   * rather than pattern-matched off the free-string code. Only `board()`
   * resolves this; a single-ticket read or a mutation response carries
   * `null` here (see `KitchenBoardController.TicketResponse`'s own doc) —
   * `kitchen-queue-page.ts` keeps whichever value the last board read gave
   * a ticket when merging a mutation response back in.
   */
  readonly channelSystemType?: string | null;
  /** `HELD` | `FIRED` | `IN_PRODUCTION` | `READY` | `HANDED_OVER` | `VOIDED`. */
  readonly status: string;
  readonly releaseMode: string;
  readonly releaseAt?: string | null;
  readonly releasedAt?: string | null;
  readonly targetReadyAt?: string | null;
  readonly prepEstimateSeconds?: number | null;
  readonly startedAt?: string | null;
  readonly readyAt?: string | null;
  readonly version: number;
  readonly createdAt: string;
  readonly items: readonly TicketItemView[];
}

/**
 * Mirrors `KitchenBoardController.CountsResponse` (wave P16) — the board's
 * own exact tab badges, over every matching ticket rather than only over
 * the page `tickets` above may have been cut to by `limit`.
 */
export interface BoardCounts {
  readonly total: number;
  readonly delivery: number;
  readonly pickup: number;
  readonly dineIn: number;
  readonly aggregator: number;
}

export interface BoardResponse {
  readonly tickets: readonly TicketResponse[];
  /** `OrderProgressPort.NOT_WIRED_WARNING` when present — every proposal from this board silently drops. */
  readonly warnings: readonly string[];
  /**
   * Present on every real response (`KitchenBoardController.board` always
   * sets it); optional here only so `buffer-page.ts`/`expo-page.ts`/
   * `vdu-page.ts` — which read `stream=buffer`/`pass` and never render a
   * tab count — keep their own fixtures unchanged by wave P16.
   */
  readonly counts?: BoardCounts;
}

export interface ItemResponse {
  readonly applied: boolean;
  readonly item: TicketItemView;
  readonly ticketStatus: string;
  readonly ticketVersion: number;
}

export interface StationResponse {
  readonly stationId: string;
  readonly code: string;
  /** `HOT` | `COLD` | `GRILL` | `BAR` | `BAKERY` | `PACKING` | `EXPO`. */
  readonly role: string;
  readonly displayNameRu: string;
  readonly displayNameUz: string;
  readonly displayNameEn: string;
  readonly sortOrder: number;
  readonly fallback: boolean;
  readonly status: string;
  readonly version: number;
}

/**
 * The kitchen board (2.1 KDS) — `KitchenBoardController` and
 * `KitchenStationController`, ADR 0041.
 */
@Injectable({ providedIn: 'root' })
export class KitchenApi {
  private readonly api = inject(ApiClient);

  /**
   * `stream=live` (default): FIRED + IN_PRODUCTION + READY — the KDS queue,
   * §2.1. `stream=buffer`: HELD tickets, oldest fire time first — the buffer,
   * §2.2. `stream=pass`: READY tickets — the expo/handover queue, §2.3.
   */
  async board(
    scope: LocationScope,
    stream: 'live' | 'buffer' | 'pass' = 'live',
    limit = 200,
  ): Promise<BoardResponse> {
    const result = await firstValueFrom(
      this.api.get<BoardResponse>(operationsPaths.kitchenTickets(scope), {
        params: { stream, limit },
      }),
    );
    return result.value;
  }

  /** Fire a buffered ticket now (§2.2's manual release). A second press is not an error. */
  release(
    scope: LocationScope,
    ticketId: string,
    expectedVersion: number,
    reasonCode: string,
  ): Observable<TicketResponse> {
    return this.api.post<{ expectedVersion: number; reasonCode: string }, TicketResponse>(
      operationsPaths.kitchenTicketRelease(scope, ticketId),
      command({ expectedVersion, reasonCode }),
    );
  }

  /**
   * Places a ticket on manual hold, or edits when a held ticket fires (§2.2's
   * buffer, `PUT .../release-schedule`). `releaseMode` is `MANUAL_HOLD` (no
   * `releaseAt`) or `SCHEDULED` (with one). Moving `releaseAt` later than the
   * ticket's own promise — or holding a ticket that has one at all — needs a
   * `reasonCode`; the server refuses with 403 when the caller lacks
   * `kitchen.ticket.release.override` for that case. Moving it earlier, or an
   * ordinary hold with no promise yet, needs neither.
   */
  reschedule(
    scope: LocationScope,
    ticketId: string,
    expectedVersion: number,
    releaseMode: 'MANUAL_HOLD' | 'SCHEDULED',
    releaseAt: string | null,
    reasonCode?: string,
  ): Observable<TicketResponse> {
    return this.api.put<
      {
        expectedVersion: number;
        releaseMode: string;
        releaseAt: string | null;
        reasonCode?: string;
      },
      TicketResponse
    >(
      operationsPaths.kitchenTicketReleaseSchedule(scope, ticketId),
      command({ expectedVersion, releaseMode, releaseAt, reasonCode }),
    );
  }

  /**
   * Custody transfer off the pass (§2.3, Раздача). A second press is not an
   * error — the caller wanted the ticket off the pass, and it already is.
   */
  handOver(scope: LocationScope, ticketId: string): Observable<TicketResponse> {
    return this.api.post<Record<string, never>, TicketResponse>(
      operationsPaths.kitchenTicketHandOver(scope, ticketId),
      command({}),
    );
  }

  async stations(scope: LocationScope): Promise<readonly StationResponse[]> {
    const result = await firstValueFrom(
      this.api.get<readonly StationResponse[]>(operationsPaths.kitchenStations(scope)),
    );
    return result.value ?? [];
  }

  start(scope: LocationScope, itemId: string): Observable<ItemResponse> {
    return this.api.post<Record<string, never>, ItemResponse>(
      operationsPaths.kitchenTicketItemStart(scope, itemId),
      command({}),
    );
  }

  ready(scope: LocationScope, itemId: string): Observable<ItemResponse> {
    return this.api.post<Record<string, never>, ItemResponse>(
      operationsPaths.kitchenTicketItemReady(scope, itemId),
      command({}),
    );
  }

  /** Refused once the ticket has been handed over — the item has left the pass. */
  recall(scope: LocationScope, itemId: string, reasonCode: string): Observable<ItemResponse> {
    return this.api.post<{ reasonCode: string }, ItemResponse>(
      operationsPaths.kitchenTicketItemRecall(scope, itemId),
      command({ reasonCode }),
    );
  }
}

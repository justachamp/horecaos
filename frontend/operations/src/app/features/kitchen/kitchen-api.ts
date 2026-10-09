import { Injectable, inject } from '@angular/core';
import { Observable, firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';
import type { LatenessPolicyResponse } from '../../core/lateness-policy-api';
import { OrderTableView } from '../../shared/ui/order-table-chip/order-table-chip';

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
  /**
   * ADR 0136: the key the component items of one combo purchase share, so a display groups them
   * under one header — an id and never a name (ADR 0041); the header's text is the order line's
   * `combo.name`. Null on every other item. Routing never reads it: each component routes by its own
   * variant, which is what a burger station and a bar each still see only their own item.
   */
  readonly comboSelectionId?: string | null;
  readonly comboContainerVariantId?: string | null;
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
  /** When the kitchen opened the ticket — not where a lateness clock starts (see `orderCreatedAt`). */
  readonly createdAt: string;
  /**
   * The order's lateness clock (ADR 0150): when the order was created, the promise it carries (null
   * for an order never promised a time) and whether it is over. These — not `createdAt`, which is
   * when the kitchen opened the ticket, and not `targetReadyAt`, which is the promise less the road —
   * are what a ticket is coloured by, through `ticketSeverityInput`, so the queue, the walls and the
   * order board call the same order late at the same instant. Present on every response, mutations
   * included; optional only so a response from a server that predates them still parses.
   */
  readonly orderCreatedAt?: string | null;
  readonly orderPromisedAt?: string | null;
  readonly orderTerminal?: boolean | null;
  /**
   * The winning partner quote's own delivery ETA (wave P11, gap map row
   * 2.1a) — absent for a pickup/dine-in ticket, a plan an in-house courier
   * carries, or a partner that answered no ETA. Only `board()` resolves this;
   * a mutation response keeps whichever value the last board read gave, the
   * same rule `channelSystemType` already follows.
   */
  readonly courierEtaAt?: string | null;
  /**
   * The table (or joined tables) and session a DINE_IN ticket's order was
   * seated at (batch 14, `OrderTablesPort`, ADR 0047) — codes and display
   * names only, never a guest. Resolved by `board()` and the single-ticket
   * read for DINE_IN tickets only; null for every other ticket and for a
   * DINE_IN order an operator keyed in without seating anyone. A mutation
   * response carries `null` (see `courierEtaAt`'s own rule), so
   * `kitchen-queue-page.ts` (which patches only the fields a mutation
   * changes) and `buffer-page.ts` (`mergeMutation`) keep whichever value the
   * last board read gave.
   */
  readonly table?: OrderTableView | null;
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

/**
 * `KitchenBoardController.KitchenEventResponse` — one `kitchen.ticket_events`
 * row (wave P11, gap map row 1.2b). `ticketItemId` absent means a
 * ticket-level transition; present means a per-line station advance.
 */
export interface KitchenEventResponse {
  readonly id: string;
  readonly ticketItemId?: string | null;
  /** `HELD` | `FIRED` | `IN_PRODUCTION` | `READY` | `HANDED_OVER` | `VOIDED`, absent for a ticket's own opening event. */
  readonly fromStatus?: string | null;
  readonly toStatus: string;
  readonly trigger: string;
  readonly actorType: string;
  readonly actorId: string;
  readonly reasonCode?: string | null;
  readonly occurredAt: string;
}

/**
 * `KitchenBoardController.KitchenEventsResponse` — `GET
 * .../kitchen/orders/{orderId}/events`, the order detail's production lane
 * (wave P11, gap map row 1.2b). `ticketId`/`ticketStatus` are `null` exactly
 * when `events` is empty — an order that never opened a ticket, not an error.
 */
export interface KitchenEventsResponse {
  readonly ticketId?: string | null;
  readonly ticketStatus?: string | null;
  readonly events: readonly KitchenEventResponse[];
}

/**
 * Mirrors `KitchenBoardController.VduItemView` — the two facts a wall
 * renders about a line, station and quantity, never a dish name.
 */
export interface VduItemView {
  readonly stationId: string;
  readonly quantity: number;
  /** `QUEUED` | `STARTED` | `READY` | `CANCELLED`. */
  readonly status: string;
  /** ADR 0136: the key a combo's component items are grouped on — an id and never a name. */
  readonly comboSelectionId?: string | null;
}

/**
 * Mirrors `KitchenBoardController.VduTicketResponse` (ADR 0041 rollout step
 * 4) — deliberately narrower than {@link TicketResponse}: no `orderId`, no
 * `releaseMode`/`releaseAt`/`releasedAt`/`prepEstimateSeconds`, and no
 * per-line `orderLineId`/`routedBy`/`version`. Only what a wall needs.
 */
export interface VduTicketResponse {
  readonly ticketId: string;
  readonly sequenceLabel: string;
  readonly externalReference?: string | null;
  readonly fulfilmentMode: string;
  /** `FIRED` | `IN_PRODUCTION` | `READY`. */
  readonly status: string;
  readonly targetReadyAt?: string | null;
  readonly createdAt: string;
  /** The order's lateness clock a wall colours the ticket by (ADR 0150); see `TicketResponse`. */
  readonly orderCreatedAt?: string | null;
  readonly orderPromisedAt?: string | null;
  readonly orderTerminal?: boolean | null;
  readonly courierEtaAt?: string | null;
  readonly items: readonly VduItemView[];
}

export interface VduBoardResponse {
  readonly tickets: readonly VduTicketResponse[];
  /**
   * The `ordering.lateness` policy resolved at the location of the call (ADR 0151), in the shape
   * `GET .../orders/lateness-policy` serves. A wall display holds one capability and cannot call that
   * endpoint, so the projection carries it; read it with `latenessPolicyFromWire`. Optional only so
   * that a response from a server that predates ADR 0151 still parses (the screen then uses the
   * platform default and says nothing — exactly what it did before).
   */
  readonly lateness?: LatenessPolicyResponse;
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

  /**
   * The production lane of the order detail's timeline (wave P11, gap map row
   * 1.2b) — `ORDER_READ`, not `KITCHEN_TICKET_READ`: this call is made from
   * the order detail pane, whose operator holds the former.
   */
  async eventsForOrder(scope: LocationScope, orderId: string): Promise<KitchenEventsResponse> {
    const result = await firstValueFrom(
      this.api.get<KitchenEventsResponse>(operationsPaths.kitchenEventsByOrder(scope, orderId)),
    );
    return result.value;
  }

  /**
   * The VDU wall projection (ADR 0041 rollout step 4, gap map row 2.4).
   * `station`, when given, narrows to that station's own lines and drops a
   * ticket with none there — `KitchenBoardController.vdu`'s own rule.
   */
  async vdu(scope: LocationScope, station?: string): Promise<VduBoardResponse> {
    const result = await firstValueFrom(
      this.api.get<VduBoardResponse>(operationsPaths.kitchenVdu(scope), {
        params: station ? { station } : {},
      }),
    );
    return result.value;
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

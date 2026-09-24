/**
 * Where the operations surface lives on the platform.
 *
 * ADR 0031 declares one prefix for this console's audience:
 *
 *     /api/v1/operations/**    brand and location staff
 *
 * **The server does not yet serve all of it there.** As of 2026-08-22 the
 * qoida-platform repository has both:
 *
 *   - `/api/v1/operations/tenants/{tenantId}/brands/{brandId}/locations/{locationId}`
 *     — the ADR 0031 shape, used by the location endpoints, and
 *   - `/api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/orders`
 *     — `OperationsOrderController`, which predates the ADR and was not moved.
 *
 * This module is the single place that knows about the split. Every path is
 * built here, so when the orders controller is remapped onto the declared prefix
 * exactly one file changes and nothing else in this application notices. Guessing
 * the ADR-correct path today would produce a 404 with no clue as to why.
 */

const OPERATIONS = '/api/v1/operations';

/**
 * The pre-ADR-0031 prefix that `OperationsOrderController` is still mapped on.
 * Delete this constant when the controller moves; the type checker will then
 * point at every path that has to change.
 */
const LEGACY_TENANT_PREFIX = '/api/v1';

/** The three identifiers that scope every call this console makes (ADR 0025). */
export interface LocationScope {
  readonly tenantId: string;
  readonly brandId: string;
  readonly locationId: string;
}

export const operationsPaths = {
  /** Orders at the location, filterable by status. */
  orders(scope: LocationScope): string {
    return `${LEGACY_TENANT_PREFIX}${tenantBrandLocation(scope)}/orders`;
  },

  /**
   * The ADR 0045 realtime push endpoint — `OperationsStreamController`, on
   * the same pre-ADR-0031 prefix `orders` above sits on, since it is mapped
   * directly under the location rather than under `/operations`. Query
   * params: `channels` (repeated, e.g. `order_queue`, `counters`), and
   * `scope` when a channel is carried wider than `LOCATION` (`TENANT:{id}` or
   * `BRAND:{id}`) — omitted here because every caller this console has today
   * subscribes at its own location. `core/realtime/realtime-client.ts` is the
   * one caller; nothing else should build this URL by hand.
   */
  streams(scope: LocationScope): string {
    return `${LEGACY_TENANT_PREFIX}${tenantBrandLocation(scope)}/operations/streams`;
  },

  /**
   * The order board (orders.md §2.4, ADR 0102, wave P07) — the branch's
   * orders, filtered in the database and cursor-paged, superseding {@link
   * orders} for any caller that needs a filter this console's toolbar offers
   * (period, channel, fulfilment type, courier, payment method,
   * `createdByActorId`, and the exact-match `reference` search). Same
   * response shape as {@link orders}; a caller that has not moved yet gains
   * nothing by switching path alone.
   */
  orderBoard(scope: LocationScope): string {
    return `${this.orders(scope)}/board`;
  },

  /**
   * N independent `ADVANCE`/`CANCEL` commands under one bulk operation id
   * (ADR 0039, orders.md §2.10, wave P07) — `OrderBulkActionService`, capped
   * at 200 orders, always `202` with a per-item outcome list. Bulk courier
   * assignment is explicitly out of scope (`BulkActionType` has no such
   * member); do not add a caller for it against this path.
   */
  orderBulkActions(scope: LocationScope): string {
    return `${this.orders(scope)}/bulk-actions`;
  },

  /**
   * The board's seven tab badges in one call (§2.3), and the live board's two
   * mixes beside them. Falls back to client derivation on error.
   *
   * Query param `period`: `ALL_TIME` (the default, and what this endpoint
   * answered before the parameter existed) or `BUSINESS_DAY`, which cuts
   * completed, cancelled and total to the tenant's own trading day (ADR 0043).
   */
  orderCounts(scope: LocationScope): string {
    return `${this.orders(scope)}/counts`;
  },

  /**
   * The whole brand's counters, its branch leaderboard and its two mixes in
   * one read — `OperationsBrandOrderController` (IA 0.1c).
   *
   * On the ADR 0031 prefix, unlike {@link orders} above: it is a new endpoint
   * and had no reason to be born on the legacy one. `ORDER_READ` at `BRAND`
   * scope, so a location-scoped principal is refused and reads
   * {@link orderCounts} for their own branch instead. Same `period` parameter.
   */
  brandOrderCounts(scope: LocationScope): string {
    return `${OPERATIONS}${tenantBrand(scope)}/orders/counts`;
  },

  /**
   * IA 0.2a (wave T01): the caller's own orders today, by sales channel —
   * `MyWorkQueryService`, self-scoped server-side by the token's own subject.
   * No `actorId` query param is ever built here: the one this endpoint
   * accepts exists only to be refused if it ever names anyone else, and this
   * console never asks for anyone else's.
   */
  myWorkChannelMix(scope: LocationScope): string {
    return `${this.orders(scope)}/my-work/channel-mix`;
  },

  /** Carts started and never converted (IA 1.4, orders.md §6). Query params: `from`, `to`, `channelId`. */
  orderDrafts(scope: LocationScope): string {
    return `${this.orders(scope)}/drafts`;
  },

  /**
   * IA 1.3a's phone lookup (orders.md §5.3) — a returning caller, by phone,
   * in the body, never a query string. `Idempotent` server-side, but a fresh
   * `Idempotency-Key` is minted per keystroke-settle regardless, since each
   * lookup is its own audited read.
   */
  orderCustomerLookups(scope: LocationScope): string {
    return `${this.orders(scope)}/customer-lookups`;
  },

  /**
   * Row 1.3a: `OperationsCustomerController#create` — create-on-miss for a
   * caller whose only grant is at `LOCATION` scope, a sibling of {@link
   * orders} and not a path under it (a customer has no order yet to nest
   * under). See that controller's own doc for why this is a second endpoint
   * rather than a widened {@link CustomersApi.create}: `LOCATION_STAFF`/
   * `LOCATION_MANAGER` cannot reach `customers(scope)` above, which
   * `CustomerController#createManually` declares at `TENANT` scope.
   */
  orderIntakeCustomers(scope: LocationScope): string {
    return `${LEGACY_TENANT_PREFIX}${tenantBrandLocation(scope)}/customers`;
  },

  /** Row 1.3g (ADR 0040): record an aggregator's own order by hand. */
  orderAggregatorEntries(scope: LocationScope): string {
    return `${this.orders(scope)}/aggregator-entries`;
  },

  /** One order with its snapshotted lines. Returns an `ETag`. */
  order(scope: LocationScope, orderId: string): string {
    return `${this.orders(scope)}/${encodeURIComponent(orderId)}`;
  },

  /**
   * Reveal the customer's phone in full.
   *
   * A separate capability and a separate audited call requiring a stated purpose
   * (ADR 0029), mirroring {@link orderLineNote}. Copy-to-clipboard of the phone
   * counts as a reveal and performs this call rather than copying an
   * already-decrypted value (§1.5).
   */
  orderCustomerPhone(scope: LocationScope, orderId: string): string {
    return `${this.order(scope, orderId)}/customer/phone`;
  },

  /** Reveal the delivery address and instructions in full. Same reveal contract as {@link orderCustomerPhone}. */
  orderCustomerAddress(scope: LocationScope, orderId: string): string {
    return `${this.order(scope, orderId)}/customer/address`;
  },

  /** Every transition with what caused it — the answer to "why is it in this state". */
  orderTimeline(scope: LocationScope, orderId: string): string {
    return `${this.order(scope, orderId)}/timeline`;
  },

  /**
   * Every decision this order ever received, winner and losers alike (wave
   * P11, gap map row 1.2b) — a sibling read to {@link orderTimeline}, not a
   * field folded into it: that response is a released contract this array
   * must not narrow or change the shape of.
   */
  orderDecisions(scope: LocationScope, orderId: string): string {
    return `${this.order(scope, orderId)}/decisions`;
  },

  /**
   * The order-to-fulfilment seam (`OrderDeliveryController`, wave P11, gap
   * map rows 1.2e/1.2n/2.1a) — on the ADR 0031 prefix like `dispatch`, not on
   * {@link order}'s legacy one: this controller is new and has no legacy
   * shape to inherit.
   */
  orderDelivery(scope: LocationScope, orderId: string): string {
    return `${OPERATIONS}${tenantBrandLocation(scope)}/orders/${encodeURIComponent(orderId)}/delivery`;
  },

  /**
   * Request an external courier for this order (`OrderDeliveryController
   * .externalCourier`, gap map rows 1.2e/2.1c) — the order-keyed path the
   * order detail pane and the KDS pass both call, over the same Millenium
   * pattern quote/accept services `dispatchExternalQuote`/`dispatchExternalBook`
   * expose per `planId` (gap map row 1.2f). Mutation: key required.
   */
  orderExternalCourier(scope: LocationScope, orderId: string): string {
    return `${OPERATIONS}${tenantBrandLocation(scope)}/orders/${encodeURIComponent(orderId)}/external-courier`;
  },

  /**
   * Every revision of this order (ADR 0039, wave P09/gap map `1.2p`) — the
   * append-only chain `orderQuery.revisions` already serves and no screen has
   * read before now.
   */
  /**
   * ADR 0039 amendment (wave P10, gap map `1.2h`): `POST` proposes and, with
   * `applyImmediately`, applies in the same call; `GET` is the history view
   * over every amendment the order has ever had. Mutation: `Idempotency-Key`
   * and `If-Match` required.
   */
  orderAmendments(scope: LocationScope, orderId: string): string {
    return `${this.order(scope, orderId)}/amendments`;
  },

  /**
   * Records the customer's recorded agreement to an amendment that raises the
   * total (orders.md §4.4). None of wave P10's five built commands reach this
   * — all five take `PRICED -> APPLIED` directly — but the path is real:
   * `OperationsOrderController.confirmAmendment` already serves it for the
   * day a financial command needs it. Mutation: `If-Match` required.
   */
  orderAmendmentConfirmation(scope: LocationScope, orderId: string, amendmentId: string): string {
    return `${this.orderAmendments(scope, orderId)}/${encodeURIComponent(amendmentId)}/confirmation`;
  },

  /**
   * Every revision of this order (ADR 0039, wave P09/gap map `1.2p`) — the
   * append-only chain `orderQuery.revisions` already serves and no screen has
   * read before now.
   */
  orderRevisions(scope: LocationScope, orderId: string): string {
    return `${this.order(scope, orderId)}/revisions`;
  },

  /**
   * Complete an order, naming how (orders.md §4.6, wave P09/gap map `1.2j`).
   * Mutation: `If-Match` required, `reasonId` optional in the body — omitting
   * it records the reason the fulfilment mode implies.
   */
  orderCompletion(scope: LocationScope, orderId: string): string {
    return `${this.order(scope, orderId)}/completion`;
  },

  /**
   * Links an order to the call it originated from (ADR 0064,
   * `OperationsOrderController.recordCallProvenance`). Write-once on the
   * server; called once, from the new-order screen, when the operator
   * started the order from a claimed screen-pop card. Mutation: key
   * required.
   */
  orderCallProvenance(scope: LocationScope, orderId: string): string {
    return `${this.order(scope, orderId)}/call-provenance`;
  },

  /** Approve or reject an order awaiting a decision. Mutation: key required. */
  orderApprovalDecisions(scope: LocationScope, orderId: string): string {
    return `${this.order(scope, orderId)}/approval-decisions`;
  },

  /**
   * The curated list the reject dialog picks from (wave 24, V0119) — platform
   * reference data, the same eight reasons for every tenant.
   */
  orderRejectReasons(scope: LocationScope): string {
    return `${this.orders(scope)}/reject-reasons`;
  },

  /** Move a confirmed order along the kitchen path. Mutation: key and `If-Match`. */
  orderStateActions(scope: LocationScope, orderId: string): string {
    return `${this.order(scope, orderId)}/state-actions`;
  },

  /**
   * A compensating transition that restores an earlier status (ADR 0019
   * amendment, ADR 0110, wave 9 gap map `1.1h`) — `OperationsOrderController
   * .stateOverride`, gated on `ORDER_STATE_OVERRIDE` rather than
   * `ORDER_ADVANCE` and carrying a mandatory registry reason. Mutation: key
   * and `If-Match`.
   */
  orderStateOverrides(scope: LocationScope, orderId: string): string {
    return `${this.order(scope, orderId)}/state-overrides`;
  },

  /**
   * The resolved `ordering.lateness` policy (ADR 0030, orders.md §2.7, wave
   * P06) — the one source the order board and the kitchen ticket queue both
   * read instead of each hard-coding its own thresholds. On the ADR 0031
   * prefix, like {@link orderRejectReasons}'s sibling reads that were born
   * after the split.
   */
  orderLatenessPolicy(scope: LocationScope): string {
    return `${OPERATIONS}${tenantBrandLocation(scope)}/orders/lateness-policy`;
  },

  /** Cancel an order that has not been confirmed. Mutation: key and `If-Match`. */
  orderCancellations(scope: LocationScope, orderId: string): string {
    return `${this.order(scope, orderId)}/cancellations`;
  },

  /**
   * Reveal one line's customer note.
   *
   * A separate capability and a separate audited call requiring a stated purpose
   * (ADR 0029). Never fold this into the order read to save a round trip: the
   * round trip is not what it is for.
   */
  orderLineNote(scope: LocationScope, orderId: string, lineId: string): string {
    return `${this.order(scope, orderId)}/lines/${encodeURIComponent(lineId)}/note`;
  },

  /** The location itself, already on the ADR 0031 prefix. */
  location(scope: LocationScope): string {
    return `${OPERATIONS}${tenantBrandLocation(scope)}`;
  },

  /**
   * The operator inbox (ADR 0059 stage 2): a brand's conversations,
   * needs-attention first. Brand-scoped, not location-scoped — {@code
   * conversations.conversations} has no location column — so this reads
   * only `scope.tenantId`/`scope.brandId` out of the `LocationScope` every
   * other call here takes, the same reuse `ConversationInboxController`'s
   * own Java doc explains for why the capability check is at `BRAND` scope.
   * Already on the ADR 0031 prefix — this controller was never on the
   * legacy `/api/v1/tenants/**` one `orders` predates.
   */
  conversations(scope: LocationScope): string {
    return `${OPERATIONS}${tenantBrand(scope)}/conversations`;
  },

  /** One conversation's full decrypted history. Returns an `ETag` (the aggregate version). */
  conversation(scope: LocationScope, conversationId: string): string {
    return `${this.conversations(scope)}/${encodeURIComponent(conversationId)}`;
  },

  /** Send a reply as the operator currently holding the conversation. Mutation: key required. */
  conversationReplies(scope: LocationScope, conversationId: string): string {
    return `${this.conversation(scope, conversationId)}/replies`;
  },

  /** Take a FLOW_ACTIVE conversation over from the flow engine. Mutation: key and `If-Match`. */
  conversationTakeover(scope: LocationScope, conversationId: string): string {
    return `${this.conversation(scope, conversationId)}/takeover`;
  },

  /** Return a HANDED_TO_OPERATOR conversation to the flow engine. Mutation: key and `If-Match`. */
  conversationReturnToFlow(scope: LocationScope, conversationId: string): string {
    return `${this.conversation(scope, conversationId)}/return-to-flow`;
  },

  /** Close a conversation. Mutation: key and `If-Match`. */
  conversationClose(scope: LocationScope, conversationId: string): string {
    return `${this.conversation(scope, conversationId)}/close`;
  },

  /**
   * The 86 toggle: register a variant as stocked at this location (waves 6/24,
   * `InventoryController`). Already on the ADR 0031 prefix.
   */
  inventoryStockItems(scope: LocationScope): string {
    return `${OPERATIONS}${tenantBrandLocation(scope)}/inventory/stock-items`;
  },

  /**
   * The audited stop/86 toggle, `InventoryService#setAvailabilityAudited`.
   * Mutation: key required. Takes effect immediately, no republishing
   * (catalog.md §0's authoring-vs-availability split).
   */
  inventoryVariantAvailability(scope: LocationScope, variantId: string): string {
    return `${OPERATIONS}${tenantBrandLocation(scope)}/inventory/variants/${encodeURIComponent(variantId)}/availability`;
  },

  /**
   * The stop list's own batch stop/unstop (gap map row 2.5, wave P16),
   * modelled on ADR 0039's bulk contract — replaces the sequential loop of
   * one {@link inventoryVariantAvailability} `PUT` per row `stop-list-page.ts`
   * used to run. Mutation: key required, capped at 200 variants.
   */
  inventoryBulkAvailability(scope: LocationScope): string {
    return `${OPERATIONS}${tenantBrandLocation(scope)}/inventory/variants/bulk-availability`;
  },

  /** Current binary availability for a set of variants at this location (query param `variantIds`, max 100). */
  inventoryAvailability(scope: LocationScope): string {
    return `${OPERATIONS}${tenantBrandLocation(scope)}/inventory/availability`;
  },

  /**
   * The kitchen board (ADR 0041, `KitchenBoardController`) — on
   * {@link LEGACY_TENANT_PREFIX} like `orders`, not on the ADR 0031 prefix,
   * because it was built alongside the orders controller and follows the
   * same path shape. `stream` is `live` (2.1's queue), `buffer` (2.2 — the
   * held tickets) or `pass` (2.3 — the ready tickets, Раздача).
   */
  kitchenTickets(scope: LocationScope): string {
    return `${LEGACY_TENANT_PREFIX}${tenantBrandLocation(scope)}/kitchen/tickets`;
  },

  /** One ticket with its lines. Returns an `ETag` (the aggregate version). */
  kitchenTicket(scope: LocationScope, ticketId: string): string {
    return `${this.kitchenTickets(scope)}/${encodeURIComponent(ticketId)}`;
  },

  /** Fire a held ticket now (2.2's manual release). Mutation: key and `If-Match`. */
  kitchenTicketRelease(scope: LocationScope, ticketId: string): string {
    return `${this.kitchenTicket(scope, ticketId)}/release`;
  },

  /**
   * Places a ticket on manual hold, or edits when a held ticket fires (2.2's
   * buffer, `KitchenBoardController.reschedule`, wave T02). Moving a fire time
   * later than the promise permits additionally needs
   * `kitchen.ticket.release.override` and a reason; moving it earlier or
   * placing an ordinary hold needs neither. `expectedVersion` travels in the
   * body, not `If-Match` — the same convention this controller's own
   * `release` above keeps.
   */
  kitchenTicketReleaseSchedule(scope: LocationScope, ticketId: string): string {
    return `${this.kitchenTicket(scope, ticketId)}/release-schedule`;
  },

  /** Custody transfer off the pass (2.3, Раздача). Mutation: key required, no body. */
  kitchenTicketHandOver(scope: LocationScope, ticketId: string): string {
    return `${this.kitchenTicket(scope, ticketId)}/hand-over`;
  },

  /** The order detail's production lane (wave P11, gap map row 1.2b) — `ORDER_READ`, not `KITCHEN_TICKET_READ`. */
  kitchenEventsByOrder(scope: LocationScope, orderId: string): string {
    return `${LEGACY_TENANT_PREFIX}${tenantBrandLocation(scope)}/kitchen/orders/${encodeURIComponent(orderId)}/events`;
  },

  /** One production line, at the station it routed to. */
  kitchenTicketItemStart(scope: LocationScope, itemId: string): string {
    return `${LEGACY_TENANT_PREFIX}${tenantBrandLocation(scope)}/kitchen/ticket-items/${encodeURIComponent(itemId)}/start`;
  },

  kitchenTicketItemReady(scope: LocationScope, itemId: string): string {
    return `${LEGACY_TENANT_PREFIX}${tenantBrandLocation(scope)}/kitchen/ticket-items/${encodeURIComponent(itemId)}/ready`;
  },

  /** Pull a line back from ready. Mutation: reasonCode in the body, no `If-Match` (item-scoped). */
  kitchenTicketItemRecall(scope: LocationScope, itemId: string): string {
    return `${LEGACY_TENANT_PREFIX}${tenantBrandLocation(scope)}/kitchen/ticket-items/${encodeURIComponent(itemId)}/recall`;
  },

  /** The location's production stations — department names for routed lines. */
  kitchenStations(scope: LocationScope): string {
    return `${LEGACY_TENANT_PREFIX}${tenantBrandLocation(scope)}/kitchen/stations`;
  },

  /** The branch's throughput ceilings (IA §2.6, `KitchenStationController`). Same `GET`/`POST` shape as {@link kitchenStations}. */
  kitchenStationCapacity(scope: LocationScope): string {
    return `${LEGACY_TENANT_PREFIX}${tenantBrandLocation(scope)}/kitchen/station-capacity`;
  },

  /**
   * One throughput ceiling — `PUT` corrects it, `DELETE` removes it (wave
   * T02, gap map row 2.6). Both take `expectedVersion` in the body, the same
   * convention {@link kitchenStationCapacity}'s own `POST` sibling keeps.
   */
  kitchenStationCapacityWindow(scope: LocationScope, capacityWindowId: string): string {
    return `${this.kitchenStationCapacity(scope)}/${encodeURIComponent(capacityWindowId)}`;
  },

  /**
   * Routes a catalogue node to a station role (the brand layer) or a station
   * (the location layer) -- `KitchenStationController.route`, row 4.2g's
   * kitchen department. `POST`-only; naming `stationRole` and leaving
   * `stationId` null writes the brand layer, which is what the product
   * editor's picker always does.
   */
  kitchenRoutingRules(scope: LocationScope): string {
    return `${LEGACY_TENANT_PREFIX}${tenantBrandLocation(scope)}/kitchen/routing-rules`;
  },

  /**
   * One routing rule, by id — `PUT` changes its role (brand layer) or its
   * station (location layer), row 4.2g's other half: before this, a product
   * already routed could only be re-`POST`ed into a 409, never actually
   * changed.
   */
  kitchenRoutingRule(scope: LocationScope, ruleId: string): string {
    return `${this.kitchenRoutingRules(scope)}/${encodeURIComponent(ruleId)}`;
  },

  /**
   * The branch's kitchen display devices (ADR 0079, `KitchenDeviceController`,
   * row `2/X.2`, wave P17) — active and revoked alike, with who enrolled or
   * revoked each one. `POST`s land on {@link kitchenDeviceApprove} and
   * {@link kitchenDeviceRevoke}, never here.
   */
  kitchenDevices(scope: LocationScope): string {
    return `${LEGACY_TENANT_PREFIX}${tenantBrandLocation(scope)}/kitchen/devices`;
  },

  /** Approves a pending enrolment by the `userCode` a new device's own screen shows. */
  kitchenDeviceApprove(scope: LocationScope, userCode: string): string {
    return `${this.kitchenDevices(scope)}/enrolments/${encodeURIComponent(userCode)}/approve`;
  },

  /** Revokes one enrolled device. Idempotent — revoking an already-revoked device is not an error. */
  kitchenDeviceRevoke(scope: LocationScope, deviceId: string): string {
    return `${this.kitchenDevices(scope)}/${encodeURIComponent(deviceId)}/revoke`;
  },

  /**
   * Table availability for a window (ADR 0047, `ReservationController`, IA
   * §1.5) — on {@link LEGACY_TENANT_PREFIX} directly under the location, not
   * under `/dine-in`: the controller's own `@RequestMapping` has no such
   * segment. Query params `from`/`to`, both instants.
   */
  reservationTableAvailability(scope: LocationScope): string {
    return `${LEGACY_TENANT_PREFIX}${tenantBrandLocation(scope)}/table-availability`;
  },

  /** A branch's bookings, either `GET` (query params `from`/`to`, the day window) or `POST` (a new booking). */
  reservations(scope: LocationScope): string {
    return `${LEGACY_TENANT_PREFIX}${tenantBrandLocation(scope)}/reservations`;
  },

  /** One booking, without the guest's name, phone or note (never rendered here — see `ReservationController`'s own doc). */
  reservation(scope: LocationScope, reservationId: string): string {
    return `${this.reservations(scope)}/${encodeURIComponent(reservationId)}`;
  },

  /** Confirm, reject, cancel, or mark a no-show. Mutation: key and `If-Match`. */
  reservationStateActions(scope: LocationScope, reservationId: string): string {
    return `${this.reservation(scope, reservationId)}/state-actions`;
  },

  /** Change the party size, the time, the tables, or the guest's own details of a booking not yet seated. Mutation: key and `If-Match`. */
  reservationAmendments(scope: LocationScope, reservationId: string): string {
    return `${this.reservation(scope, reservationId)}/amendments`;
  },

  /**
   * Dine-in sessions (ADR 0047, `TableSessionController`) — on
   * {@link LEGACY_TENANT_PREFIX} under the location, not under
   * `/reservations`: the controller's own `@RequestMapping` has no such
   * segment. `POST` here with a `reservationId` is what seats a booking
   * (IA 1.5a) — the reservation moves to `SEATED` in the same transaction.
   */
  dineInSessions(scope: LocationScope): string {
    return `${LEGACY_TENANT_PREFIX}${tenantBrandLocation(scope)}/dine-in/sessions`;
  },

  /**
   * Floor plan settings (ADR 0047, `FloorPlanController`, rows `10.2d`/
   * `10.5b`, wave P38): `qrMode`, turnaround buffer, guest-session TTL,
   * service-charge rate. `GET`/`PUT`, both `DINEIN_FLOORPLAN_MANAGE`.
   */
  dineInSettings(scope: LocationScope): string {
    return `${LEGACY_TENANT_PREFIX}${tenantBrandLocation(scope)}/dine-in/settings`;
  },

  /** A branch's sections (wave P38). `GET` (`RESERVATION_READ`) or `POST` (`DINEIN_FLOORPLAN_MANAGE`, mutating). */
  dineInSections(scope: LocationScope): string {
    return `${LEGACY_TENANT_PREFIX}${tenantBrandLocation(scope)}/dine-in/sections`;
  },

  /** A branch's tables (wave P38). `GET` (`RESERVATION_READ`) or `POST` (`DINEIN_FLOORPLAN_MANAGE`, mutating). */
  dineInTables(scope: LocationScope): string {
    return `${LEGACY_TENANT_PREFIX}${tenantBrandLocation(scope)}/dine-in/tables`;
  },

  /** One table (wave P38). `PUT` moves it — `layoutX`/`layoutY`, `If-Match` required. */
  dineInTable(scope: LocationScope, tableId: string): string {
    return `${this.dineInTables(scope)}/${encodeURIComponent(tableId)}`;
  },

  /** Archive/restore a table (ADR 0047). `POST`, `If-Match` required. */
  dineInTableStatusChanges(scope: LocationScope, tableId: string): string {
    return `${this.dineInTable(scope, tableId)}/status-changes`;
  },

  /**
   * Issue or rotate a table's QR token (ADR 0047). `POST`, `If-Match`
   * required. The only endpoint in the platform whose response carries a
   * live credential, and carries it exactly once — see
   * `FloorPlanController`'s own doc.
   */
  dineInTableQrRotations(scope: LocationScope, tableId: string): string {
    return `${this.dineInTable(scope, tableId)}/qr-token-rotations`;
  },

  /**
   * The dispatch board (ADR 0014, `DispatchController`, wave 30) — on the ADR
   * 0031 prefix, unlike the kitchen board above: this controller is new and
   * has no legacy shape to inherit.
   */
  dispatchQueue(scope: LocationScope): string {
    return `${OPERATIONS}${tenantBrandLocation(scope)}/dispatch/queue`;
  },

  /** Assign one courier to one plan. Mutation: key required (idempotent per ADR 0031, not `If-Match` — the body carries `expectedVersion`). */
  dispatchAssign(scope: LocationScope, planId: string): string {
    return `${OPERATIONS}${tenantBrandLocation(scope)}/dispatch/plans/${encodeURIComponent(planId)}/assign`;
  },

  /** Return a carried plan to the sourcing pool. Same mutation shape as {@link dispatchAssign}. */
  dispatchUnassign(scope: LocationScope, planId: string): string {
    return `${OPERATIONS}${tenantBrandLocation(scope)}/dispatch/plans/${encodeURIComponent(planId)}/unassign`;
  },

  /** Why a `MANUAL_ACTION_REQUIRED` plan needs a human (§3.1). Read, same capability as {@link operationsPaths.dispatchQueue}. */
  dispatchExceptions(scope: LocationScope, planId: string): string {
    return `${OPERATIONS}${tenantBrandLocation(scope)}/dispatch/plans/${encodeURIComponent(planId)}/exceptions`;
  },

  /**
   * Every external courier partner this branch has configured (`DispatchController
   * .externalPartners`, wave P44, gap map row 1.2f) — the picker behind «Вызвать курьера».
   */
  dispatchExternalPartners(scope: LocationScope, planId: string): string {
    return `${OPERATIONS}${tenantBrandLocation(scope)}/dispatch/plans/${encodeURIComponent(planId)}/external-partners`;
  },

  /** A non-binding price from one partner, against the customer's own fee. Mutation: key required. */
  dispatchExternalQuote(scope: LocationScope, planId: string): string {
    return `${OPERATIONS}${tenantBrandLocation(scope)}/dispatch/plans/${encodeURIComponent(planId)}/external-quote`;
  },

  /** Accept or abandon a quoted external booking. Mutation: key required. */
  dispatchExternalBook(scope: LocationScope, planId: string): string {
    return `${OPERATIONS}${tenantBrandLocation(scope)}/dispatch/plans/${encodeURIComponent(planId)}/external-book`;
  },

  /**
   * The dedicated, provider-notifying shipment cancel (`Capability.SHIPMENT_CANCEL`,
   * wave P44, gap map row 1.2g) — distinct from {@link operationsPaths.dispatchUnassign},
   * which never tells a PARTNER shipment's provider anything. Mutation: key required.
   */
  dispatchShipmentCancel(scope: LocationScope, shipmentId: string): string {
    return `${OPERATIONS}${tenantBrandLocation(scope)}/dispatch/shipments/${encodeURIComponent(shipmentId)}/cancel`;
  },

  /**
   * The dispatcher's live map (ADR 0045, `OperationsCourierPositionController`,
   * IA 3.2) — on the ADR 0031 prefix, but under its own `operations/couriers`
   * sub-path rather than beside `dispatch`, matching the controller's own
   * `RequestMapping`.
   */
  courierPositions(scope: LocationScope): string {
    return `${OPERATIONS}${tenantBrandLocation(scope)}/operations/couriers/positions`;
  },

  /** Open one courier's stored track for a stated purpose. Mutation: key required, audited. */
  courierTrackReveals(scope: LocationScope, courierId: string): string {
    return `${OPERATIONS}${tenantBrandLocation(scope)}/operations/couriers/${encodeURIComponent(courierId)}/track-reveals`;
  },

  /**
   * Operator presence (ADR 0064, `OperatorPresenceController`, IA 1.6) — on
   * the ADR 0031 prefix. Set/read your own presence at this branch; the
   * operator id always comes from the caller's own token, never the body.
   */
  voicePresence(scope: LocationScope): string {
    return `${OPERATIONS}${tenantBrandLocation(scope)}/voice/presence`;
  },

  /** Your own current presence, or the OFFLINE default when you have never set one. */
  voicePresenceMine(scope: LocationScope): string {
    return `${this.voicePresence(scope)}/me`;
  },

  /**
   * The screen-pop poll (ADR 0064, `ScreenPopController`) — call on the same
   * 10-second cadence every other live screen in this app uses. No push.
   */
  voiceScreenPopCurrent(scope: LocationScope): string {
    return `${OPERATIONS}${tenantBrandLocation(scope)}/voice/screen-pop/current`;
  },

  /** Claim a ringing call's card. Mutation: key required. */
  voiceScreenPopAcknowledgement(scope: LocationScope, callEventId: string): string {
    return `${OPERATIONS}${tenantBrandLocation(scope)}/voice/screen-pop/${encodeURIComponent(callEventId)}/acknowledgement`;
  },

  /** An unknown caller's unmasked number, for the create-customer prefill. Audited every call; never for a resolved caller. */
  voiceScreenPopCallerNumber(scope: LocationScope, callEventId: string): string {
    return `${OPERATIONS}${tenantBrandLocation(scope)}/voice/screen-pop/${encodeURIComponent(callEventId)}/caller-number`;
  },

  /** The branch's recent call list (`CallLogController`). */
  voiceCallLog(scope: LocationScope): string {
    return `${OPERATIONS}${tenantBrandLocation(scope)}/voice/call-log`;
  },

  /**
   * T12 (7.5b): offered/answered/missed/transferred and talk seconds, by
   * hour and operator, for one business date (`CallStatsController`).
   * Written by the same day-close pipeline every other ADR 0043 fact uses —
   * empty until the day closes, never live.
   */
  voiceCallStats(scope: LocationScope): string {
    return `${OPERATIONS}${tenantBrandLocation(scope)}/voice/call-stats`;
  },

  /**
   * The CRM grid: `CustomerController`, tenant-scoped like `orders` — never
   * moved onto the ADR 0031 prefix, so this sits on {@link LEGACY_TENANT_PREFIX}
   * beside it. `LocationScope.brandId`/`locationId` are unused here; the
   * customer base is a tenant-wide surface, not a branch's own.
   */
  customers(scope: LocationScope): string {
    return `${LEGACY_TENANT_PREFIX}${tenant(scope)}/customers`;
  },

  /** The grid header's three counters. */
  customersCounts(scope: LocationScope): string {
    return `${this.customers(scope)}/counts`;
  },

  /** A filtered export, decrypted, behind one audited egress event (query params `status`, `query`, `purpose`). */
  customersExport(scope: LocationScope): string {
    return `${this.customers(scope)}/export`;
  },

  /** Row X.13/5.1b: queue a customer CSV import (`CustomerImportController`). */
  customerImports(scope: LocationScope): string {
    return `${this.customers(scope)}/imports`;
  },

  /** One import run's status and progress. */
  customerImport(scope: LocationScope, runId: string): string {
    return `${this.customerImports(scope)}/${encodeURIComponent(runId)}`;
  },

  /** One import run's per-row report. */
  customerImportRows(scope: LocationScope, runId: string): string {
    return `${this.customerImport(scope, runId)}/rows`;
  },

  /** One customer's profile. */
  customer(scope: LocationScope, accountId: string): string {
    return `${this.customers(scope)}/${encodeURIComponent(accountId)}`;
  },

  /** Change the display name, language, or timezone. Mutation: key and `If-Match`. */
  customerProfile(scope: LocationScope, accountId: string): string {
    return `${this.customer(scope, accountId)}/profile`;
  },

  /** Set/clear (`PUT`, key + `If-Match`) or reveal (`GET`, query param `purpose`) the date of birth. */
  customerDateOfBirth(scope: LocationScope, accountId: string): string {
    return `${this.customer(scope, accountId)}/date-of-birth`;
  },

  /** Add (`POST`) or reveal every one (`GET`, query param `purpose`) of this customer's addresses. */
  customerAddresses(scope: LocationScope, accountId: string): string {
    return `${this.customer(scope, accountId)}/addresses`;
  },

  /** Replace (`PUT`, key + `If-Match`) or archive (`DELETE`, `If-Match`) one address. */
  customerAddress(scope: LocationScope, accountId: string, addressId: string): string {
    return `${this.customerAddresses(scope, accountId)}/${encodeURIComponent(addressId)}`;
  },

  /** Add (`POST`) or reveal (`GET`, query param `purpose`) this customer's contact points. */
  customerContactPoints(scope: LocationScope, accountId: string): string {
    return `${this.customer(scope, accountId)}/contact-points`;
  },

  /** Correct (`PUT`) or remove (`DELETE`) one contact point. Mutation: key required. */
  customerContactPoint(scope: LocationScope, accountId: string, contactPointId: string): string {
    return `${this.customerContactPoints(scope, accountId)}/${encodeURIComponent(contactPointId)}`;
  },

  /** Make this contact point the account's primary of its kind (`POST`). Mutation: key required. */
  customerContactPointSetPrimary(
    scope: LocationScope,
    accountId: string,
    contactPointId: string,
  ): string {
    return `${this.customerContactPoint(scope, accountId, contactPointId)}/set-primary`;
  },

  /** Record (`POST`) or read (`GET`) the full consent history. */
  customerConsentDecisions(scope: LocationScope, accountId: string): string {
    return `${this.customer(scope, accountId)}/consent-decisions`;
  },

  /** Whether this customer may be reached for a purpose and channel, right now (`GET`, query params `brandId`, `purpose`, `channel`). */
  customerEligibility(scope: LocationScope, accountId: string): string {
    return `${this.customer(scope, accountId)}/eligibility`;
  },

  /** Whether this customer is blacklisted right now, with no reveal. */
  customerBlacklistStatus(scope: LocationScope, accountId: string): string {
    return `${this.customer(scope, accountId)}/blacklist-status`;
  },

  /** Add (`POST`) or reveal the decrypted history (`GET`, query param `purpose`). */
  customerBlacklistEntries(scope: LocationScope, accountId: string): string {
    return `${this.customer(scope, accountId)}/blacklist-entries`;
  },

  /** Lift the active entry. Mutation: key required. */
  customerBlacklistLift(scope: LocationScope, accountId: string): string {
    return `${this.customerBlacklistEntries(scope, accountId)}/lift`;
  },

  /** Merge this account into another. Mutation: key and `If-Match` against `accountId`'s own version. */
  customerMerge(scope: LocationScope, accountId: string): string {
    return `${this.customer(scope, accountId)}/merge`;
  },

  /**
   * One customer's order history, brand-scoped (`CustomerOrderHistoryController`,
   * `ordering.web`) — a different module than the rest of this section, and
   * therefore its own path rather than a child of {@link customer}.
   */
  customerOrders(scope: LocationScope, accountId: string): string {
    return `${LEGACY_TENANT_PREFIX}${tenantBrand(scope)}/customers/${encodeURIComponent(accountId)}/orders`;
  },

  /**
   * Rows 1.3f/1.3a: the New Order screen's own «Повторить», reached through
   * `CustomerOrderReorderController` — the `LOCATION`-scoped twin of
   * `CustomerOrderHistoryController.reorderPlan` (`ORDER_READ` at `BRAND`,
   * used only by {@link customerOrders}' own Customers-section history read).
   * `LOCATION_STAFF`, this screen's primary persona, holds `ORDER_READ` only
   * at `LOCATION` scope, so this is the one and only reorder route this
   * console calls — pointed at {@link LocationScope.locationId} rather than
   * {@link customerOrders}' brand-scoped path, and resolved server-side
   * against that location's own menu and stock, not the order's original
   * branch's.
   */
  customerOrderReorder(scope: LocationScope, accountId: string, orderId: string): string {
    return (
      `${LEGACY_TENANT_PREFIX}${tenantBrandLocation(scope)}` +
      `/customers/${encodeURIComponent(accountId)}/orders/${encodeURIComponent(orderId)}/reorder`
    );
  },

  /**
   * Rows 1.3f/1.3a (major fix): the New Order screen's own history peek,
   * reached through `CustomerOrderReorderController.listOrders` — the
   * `LOCATION`-scoped twin of {@link customerOrders} (`ORDER_READ` at
   * `BRAND`, used only by the Customers section's own history tab).
   * `LOCATION_STAFF`, this screen's primary persona, holds `ORDER_READ` only
   * at `LOCATION` scope, so calling {@link customerOrders} from this screen
   * always 403s and silently renders an empty popover — this is the one
   * history route this screen may call, mirroring {@link customerOrderReorder}'s
   * own relationship to {@link customerOrders}.
   */
  customerOrderHistoryAtLocation(scope: LocationScope, accountId: string): string {
    return `${LEGACY_TENANT_PREFIX}${tenantBrandLocation(scope)}/customers/${encodeURIComponent(accountId)}/orders`;
  },

  /**
   * Every points (cashback) balance this customer holds, one per brand
   * (`LoyaltyOperationsController`, already on the ADR 0031 prefix).
   */
  customerLoyaltyBalances(scope: LocationScope, accountId: string): string {
    return `${OPERATIONS}${tenant(scope)}/customers/${encodeURIComponent(accountId)}/loyalty`;
  },

  /** One balance's own movement ledger — `loyaltyAccountId` from {@link customerLoyaltyBalances}, not `accountId`. */
  customerLoyaltyEntries(
    scope: LocationScope,
    accountId: string,
    loyaltyAccountId: string,
  ): string {
    return `${this.customerLoyaltyBalances(scope, accountId)}/${encodeURIComponent(loyaltyAccountId)}/entries`;
  },

  /**
   * Outstanding points per brand, never pooled into one tenant figure
   * (`LoyaltyOperationsController.liability`). Tenant-scoped like
   * {@link customers} — `LocationScope.brandId`/`locationId` are unused here —
   * because the liability belongs to each brand's own legal entity and the
   * report reads every brand at once so operations §6.3 Loyalty can show a
   * brand its own row without a second controller.
   */
  loyaltyLiability(scope: LocationScope): string {
    return `${OPERATIONS}${tenant(scope)}/reports/loyalty-liability`;
  },

  /**
   * Credit or debit a balance by hand (row 5.2e) — `LoyaltyOperationsController.adjust`,
   * the same ADR-0031-prefixed controller as {@link customerLoyaltyBalances}.
   * Mutation: key, `LOYALTY_ADJUST`, and above the configured threshold an
   * ADR 0027 approval — the response's own `status` says which.
   */
  customerLoyaltyAdjustments(scope: LocationScope, accountId: string): string {
    return `${this.customerLoyaltyBalances(scope, accountId)}/adjustments`;
  },

  /**
   * Row 5.2g: every coupon-gated discount this customer has ever held,
   * tenant-wide (`CustomerDiscountHistoryController`, ADR 0072, row 7.9a) —
   * `pricing.coupon_redemptions`' own `ix_redemptions_customer` index, read
   * here for the first time from this console.
   */
  customerDiscountHistory(scope: LocationScope, accountId: string): string {
    return `${OPERATIONS}${tenant(scope)}/customers/${encodeURIComponent(accountId)}/discount-history`;
  },

  /**
   * Row 5/X.1: raise (`POST`) or read (`GET`) this customer's own
   * data-subject erasure requests (`CustomerController`, V0178). The
   * tenant-wide worklist a merchant has no reason to browse from here stays
   * behind the control plane; this is the per-account history only.
   */
  customerErasureRequests(scope: LocationScope, accountId: string): string {
    return `${this.customer(scope, accountId)}/erasure-requests`;
  },

  /** Withdraw a PENDING request. Mutation: key required. */
  customerErasureRequestCancel(scope: LocationScope, accountId: string, requestId: string): string {
    return `${this.customerErasureRequests(scope, accountId)}/${encodeURIComponent(requestId)}/cancel`;
  },

  /** The transition that actually anonymises the account. Mutation: key and `CUSTOMER_ERASURE_EXECUTE`. */
  customerErasureRequestExecute(
    scope: LocationScope,
    accountId: string,
    requestId: string,
  ): string {
    return `${this.customerErasureRequests(scope, accountId)}/${encodeURIComponent(requestId)}/execute`;
  },
} as const;

/**
 * The in-house courier roster (ADR 0042, `OperationsCourierController`) —
 * tenant-scoped, not brand- or location-scoped: `fulfillment.couriers` itself
 * carries no `brand_id`/`location_id` column. Courier groups and branch
 * bindings are first-class relations (IA 3.3, V0221) reached through their
 * own paths below rather than through this one. Kept apart from {@link
 * operationsPaths} for the same reason {@link mediaPaths} is: every call here
 * takes a bare `tenantId`.
 */
export const courierPaths = {
  /** The roster, with today's load. Same read {@link operationsPaths.dispatchQueue}'s fleet rail uses. */
  couriers(tenantId: string): string {
    return `/api/v1/operations/tenants/${encodeURIComponent(tenantId)}/couriers`;
  },

  /** Register a courier and open their engagement. Mutation: key required. */
  courierRegistrations(tenantId: string): string {
    return this.couriers(tenantId);
  },

  /** One courier, with which compliance fields are on file — never their contents (IA 3.3). */
  courier(tenantId: string, courierId: string): string {
    return `${this.couriers(tenantId)}/${encodeURIComponent(courierId)}`;
  },

  /**
   * The compliance file. `POST` records or corrects it (mutation: key required);
   * `GET` with a `purpose` query parameter is the audited ADR 0029 reveal and is
   * the only path that returns a passport, a ПИНФЛ or a home address.
   */
  courierComplianceFile(tenantId: string, courierId: string): string {
    return `${this.courier(tenantId, courierId)}/compliance-file`;
  },

  /** Courier groups (IA 3.3). Same path for `POST` (author one). */
  courierGroups(tenantId: string): string {
    return `/api/v1/operations/tenants/${encodeURIComponent(tenantId)}/courier-groups`;
  },

  /** Put a courier in a group (`POST`) — idempotent. Mutation: key required. */
  courierGroupMemberships(tenantId: string, courierId: string): string {
    return `${this.courier(tenantId, courierId)}/groups`;
  },

  /**
   * Take a courier out of a group (`POST`). A sub-resource rather than a
   * `DELETE` because the reason travels in the body: ADR 0029 keeps reasons out
   * of URLs. Mutation: key required.
   */
  courierGroupRemoval(tenantId: string, courierId: string, groupId: string): string {
    return `${this.courierGroupMemberships(tenantId, courierId)}/${encodeURIComponent(groupId)}/removal`;
  },

  /** Which branches a courier rides for (`POST` to bind). Mutation: key required. */
  courierBranchBindings(tenantId: string, courierId: string): string {
    return `${this.courier(tenantId, courierId)}/branch-bindings`;
  },

  /**
   * Unbind a courier from one branch (`POST`). Mutation: key required.
   * `brandId` carries the LOCATION-scoped capability check only — the server
   * looks up the binding by courier and location, which is already unique —
   * but it must be a real path segment: the interceptor reads it off the URL
   * template, not the request body.
   */
  courierBranchUnbinding(
    tenantId: string,
    courierId: string,
    brandId: string,
    locationId: string,
  ): string {
    return `${this.courierBranchBindings(tenantId, courierId)}/${encodeURIComponent(brandId)}/${encodeURIComponent(locationId)}/removal`;
  },

  /** Vehicle classes, for the registration form's picker and the IA 3.4 management screen. Query param: `includeArchived`. */
  courierTypes(tenantId: string): string {
    return `/api/v1/operations/tenants/${encodeURIComponent(tenantId)}/courier-types`;
  },

  /** One vehicle class (ADR 0108). `PUT` corrects it; same path plus `/archival` for `POST` archives it. */
  courierType(tenantId: string, typeId: string): string {
    return `${this.courierTypes(tenantId)}/${encodeURIComponent(typeId)}`;
  },

  courierTypeArchival(tenantId: string, typeId: string): string {
    return `${this.courierType(tenantId, typeId)}/archival`;
  },

  /** The bonus/penalty registry (ADR 0108). Same path for `POST` (define one). */
  adjustmentReasons(tenantId: string): string {
    return `/api/v1/operations/tenants/${encodeURIComponent(tenantId)}/adjustment-reasons`;
  },

  adjustmentReasonArchival(tenantId: string, reasonId: string): string {
    return `${this.adjustmentReasons(tenantId)}/${encodeURIComponent(reasonId)}/archival`;
  },

  /** Record a bonus or a penalty against one courier. Mutation: key required. */
  courierAdjustments(tenantId: string, courierId: string): string {
    return `${this.courier(tenantId, courierId)}/adjustments`;
  },

  /** A courier's ledger (ADR 0042). Query param: `limit`. */
  courierLedger(tenantId: string, courierId: string): string {
    return `${this.courier(tenantId, courierId)}/ledger`;
  },

  /** Attest that the registration evidence was sighted. Mutation: key required. */
  courierEngagementVerify(tenantId: string, engagementId: string): string {
    return `/api/v1/operations/tenants/${encodeURIComponent(tenantId)}/courier-engagements/${encodeURIComponent(engagementId)}/verify`;
  },

  /** Suspend an engagement for an operational reason. Mutation: key required. */
  courierEngagementSuspend(tenantId: string, engagementId: string): string {
    return `/api/v1/operations/tenants/${encodeURIComponent(tenantId)}/courier-engagements/${encodeURIComponent(engagementId)}/suspend`;
  },

  /** Every rate card the brand has authored (IA 3.4). Query param `brandId`. Same path for `POST` (author). */
  rateCards(tenantId: string): string {
    return `/api/v1/operations/tenants/${encodeURIComponent(tenantId)}/rate-cards`;
  },

  /** One rate card with its band ladder. */
  rateCard(tenantId: string, cardId: string): string {
    return `${this.rateCards(tenantId)}/${encodeURIComponent(cardId)}`;
  },

  /** Put a draft rate card in front of couriers. Mutation: key required. */
  rateCardActivation(tenantId: string, cardId: string): string {
    return `${this.rateCard(tenantId, cardId)}/activation`;
  },

  /**
   * The branch's shifts, newest first (IA 3.5). Query params: `brandId`,
   * `locationId`, `limit`, and optionally `from`/`to` to window the read to a
   * period.
   */
  courierShifts(tenantId: string): string {
    return `/api/v1/operations/tenants/${encodeURIComponent(tenantId)}/courier-shifts`;
  },

  /**
   * The branch's planned shifts (IA 3.5's roster). `GET` with `brandId`,
   * `locationId`, optional `from`/`to`/`limit`; `POST` drafts one (mutation:
   * key required).
   */
  courierRosterEntries(tenantId: string): string {
    return `/api/v1/operations/tenants/${encodeURIComponent(tenantId)}/courier-roster-entries`;
  },

  /** Planned-versus-actual, for one period (IA 3.5). `GET` with `brandId`, `locationId`, `from`, `to`. */
  courierRosterComparison(tenantId: string): string {
    return `${this.courierRosterEntries(tenantId)}/comparison`;
  },

  /** Publish a planned shift (`POST`). Mutation: key required. */
  courierRosterEntryPublish(tenantId: string, entryId: string): string {
    return `${this.courierRosterEntries(tenantId)}/${encodeURIComponent(entryId)}/publish`;
  },

  /** Cancel a planned shift still DRAFT or PUBLISHED (`POST`). Mutation: key required. */
  courierRosterEntryCancel(tenantId: string, entryId: string): string {
    return `${this.courierRosterEntries(tenantId)}/${encodeURIComponent(entryId)}/cancel`;
  },

  /**
   * The courier compensation policy (IA 3.9, settings.md §10.13/couriers.md
   * §16). `GET` reads what is in force; `PUT` (wave P38) publishes the next
   * whole-document version. Both take optional `brandId`/`locationId` query
   * params — omit both for the tenant-wide scope.
   */
  courierPolicy(tenantId: string): string {
    return `/api/v1/operations/tenants/${encodeURIComponent(tenantId)}/courier-policy`;
  },

  /**
   * T11 7.4c (ADR 0125): the per-line reconcile action the external-
   * delivery-cost report names (`POST`). Mutation: key required. Lives beside
   * the invoice-line endpoints it mutates, not under `reportsPaths` — that
   * tree only ever reads.
   */
  externalDeliveryCostReconcile(tenantId: string, shipmentId: string): string {
    return `/api/v1/operations/tenants/${encodeURIComponent(tenantId)}/shipments/${encodeURIComponent(shipmentId)}/external-delivery-cost/reconcile`;
  },
} as const;

/**
 * Media upload (ADR 0010, `MediaController`) — tenant-scoped, not brand- or
 * location-scoped, because a media asset's owner (`MediaOwner.Scope`) is
 * `TENANT`/`BRAND`/`LOCATION` chosen at upload time, not fixed by the URL. Kept
 * apart from {@link operationsPaths} because every call here takes a bare
 * `tenantId`, never a full {@link LocationScope}.
 */
export const mediaPaths = {
  /** Request a presigned upload URL. The client PUTs bytes directly to it. */
  uploadRequests(tenantId: string): string {
    return `/api/v1/tenants/${encodeURIComponent(tenantId)}/media/assets/upload-requests`;
  },

  /** One asset's status. */
  asset(tenantId: string, assetId: string): string {
    return `/api/v1/tenants/${encodeURIComponent(tenantId)}/media/assets/${encodeURIComponent(assetId)}`;
  },

  /** Tell the server to re-read the object store and mark the upload complete. */
  finalize(tenantId: string, assetId: string): string {
    return `${this.asset(tenantId, assetId)}/finalize`;
  },

  /** A short-lived signed URL, only for an `AVAILABLE` asset. */
  downloadUrl(tenantId: string, assetId: string): string {
    return `${this.asset(tenantId, assetId)}/download-url`;
  },
} as const;

/**
 * Handover verification (ADR 0040, `MarketplaceOperationsController`) — one
 * of the surfaces already on the ADR 0031 prefix, tenant-scoped only (the
 * challenge table has no location column of its own; the order it belongs to
 * does). Kept apart from {@link operationsPaths} for the same reason {@link
 * mediaPaths} is: every call here takes a bare `tenantId`, not a full {@link
 * LocationScope} order-scoped call — wave P09/gap map `1.2m`.
 */
export const marketplacePaths = {
  /** The handover challenge's current state — never the expected value. */
  handoverChallenge(scope: LocationScope, orderId: string): string {
    return `${OPERATIONS}${tenant(scope)}/marketplace/orders/${encodeURIComponent(orderId)}/handover-challenge`;
  },

  /** Consumes one verification attempt, whether or not the code matches. */
  handoverVerifications(scope: LocationScope, orderId: string): string {
    return `${OPERATIONS}${tenant(scope)}/marketplace/orders/${encodeURIComponent(orderId)}/handover-verifications`;
  },

  /** The audited supervisor override, past exhaustion as well as before it. */
  handoverBypasses(scope: LocationScope, orderId: string): string {
    return `${OPERATIONS}${tenant(scope)}/marketplace/orders/${encodeURIComponent(orderId)}/handover-bypasses`;
  },
} as const;

function tenantBrandLocation(scope: LocationScope): string {
  return (
    `/tenants/${encodeURIComponent(scope.tenantId)}` +
    `/brands/${encodeURIComponent(scope.brandId)}` +
    `/locations/${encodeURIComponent(scope.locationId)}`
  );
}

/** Brand-scoped, no location segment — {@link operationsPaths.conversations} and its children. */
function tenantBrand(scope: LocationScope): string {
  return `/tenants/${encodeURIComponent(scope.tenantId)}/brands/${encodeURIComponent(scope.brandId)}`;
}

/**
 * Tenant-scoped only — the Customers section (§5). `LocationScope` carries a
 * `brandId` and `locationId` this console always has to hand, but the
 * customer base itself is not brand- or location-partitioned in the URL: a
 * `BRAND_ISOLATED` tenant still reads and writes through one tenant-scoped
 * `CustomerController`, brand only ever appearing inside a request body.
 */
function tenant(scope: LocationScope): string {
  return `/tenants/${encodeURIComponent(scope.tenantId)}`;
}

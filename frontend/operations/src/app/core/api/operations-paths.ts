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

  /** The board's seven tab badges in one call (§2.3). Falls back to client derivation on error. */
  orderCounts(scope: LocationScope): string {
    return `${this.orders(scope)}/counts`;
  },

  /** Carts started and never converted (IA 1.4, orders.md §6). Query params: `from`, `to`, `channelId`. */
  orderDrafts(scope: LocationScope): string {
    return `${this.orders(scope)}/drafts`;
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

  /** Custody transfer off the pass (2.3, Раздача). Mutation: key required, no body. */
  kitchenTicketHandOver(scope: LocationScope, ticketId: string): string {
    return `${this.kitchenTicket(scope, ticketId)}/hand-over`;
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

  /** Change the party size, the time, or the tables of a booking not yet seated. Mutation: key and `If-Match`. */
  reservationAmendments(scope: LocationScope, reservationId: string): string {
    return `${this.reservation(scope, reservationId)}/amendments`;
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

  /** Record (`POST`) or read (`GET`) the full consent history. */
  customerConsentDecisions(scope: LocationScope, accountId: string): string {
    return `${this.customer(scope, accountId)}/consent-decisions`;
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
} as const;

/**
 * The in-house courier roster (ADR 0042, `OperationsCourierController`) —
 * tenant-scoped, not brand- or location-scoped: `fulfillment.couriers` carries
 * no `brand_id`/`location_id` column at all (§3.3's branch-bindings ownership
 * is not built — see the wave's final report). Kept apart from {@link
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

  /** Vehicle classes, for the registration form's picker. */
  courierTypes(tenantId: string): string {
    return `/api/v1/operations/tenants/${encodeURIComponent(tenantId)}/courier-types`;
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

  /** The branch's shifts, newest first (IA 3.5). Query params: `brandId`, `locationId`, `limit`. */
  courierShifts(tenantId: string): string {
    return `/api/v1/operations/tenants/${encodeURIComponent(tenantId)}/courier-shifts`;
  },

  /** The courier compensation policy in force (IA 3.9). Query params: optional `brandId`, `locationId`. */
  courierPolicy(tenantId: string): string {
    return `/api/v1/operations/tenants/${encodeURIComponent(tenantId)}/courier-policy`;
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

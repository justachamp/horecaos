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

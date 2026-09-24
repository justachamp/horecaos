import { OrderActionResponse } from './order-actions';
import { OrderSummaryResponse } from './order-summary';

/**
 * These interfaces mirror `OperationsOrderController`'s response records in
 * `qoida-platform` directly, **not** `platform/api/openapi/v1/horecaos-api.json`.
 *
 * The generated spec is wrong for two of them: `LineResponse` and
 * `AddressResponse` are each declared twice in the platform — once for
 * ordering, once for an unrelated controller (pricing's quote lines, the
 * storefront customer address) — and springdoc's component registry keys
 * schemas by simple class name, so one definition silently overwrote the
 * other when the spec was generated. The Java source
 * (`ordering/web/OperationsOrderController.java`) has no such collision and is
 * what is actually serialized; every field below is copied from its records.
 */

/** `LineResponse` — one snapshotted order line. */
export interface OrderLine {
  readonly lineNumber: number;
  readonly productName: string;
  readonly variantName?: string | null;
  readonly sku?: string | null;
  readonly quantity: number;
  /** The line's own total, snapshotted at checkout — not a unit price. */
  readonly finalAmountMinor: number;
  readonly modifiers: readonly string[];
  readonly lineId: string;
  /**
   * Whether the customer left a note on this line. The text itself is
   * personal data and is never in this list — {@link OrderRevealApi.revealLineNote}
   * is the separate, audited call that returns it (§3.4).
   */
  readonly hasNote: boolean;
}

/** `CustomerResponse` — orders.md §3.7-§3.8, exactly as far as `ORDER_READ` may see. */
export interface OrderCustomer {
  readonly displayName: string;
  /** `+998 90 ••• •• 42`, or null when there is no phone on file. */
  readonly phoneMasked?: string | null;
  /** `"ACCOUNT"` or `"GUEST"`. */
  readonly customerType: string;
  readonly hasAddress: boolean;
  readonly hasDeliveryInstructions: boolean;
  readonly transactionalContactAllowed: boolean;
  /** The ADR 0029 retention job has blanked the snapshot — §1.5. */
  readonly anonymized: boolean;
}

/** `OutcomeResponse` — the terminal fact the order ended in, present only once it has. */
export interface OrderOutcome {
  readonly kind: string;
  readonly systemCategory?: string | null;
  readonly reasonId?: string | null;
  readonly reasonVersion?: number | null;
  readonly stockDisposition?: string | null;
  readonly liabilityParty?: string | null;
  readonly customerRefund?: string | null;
  readonly reservationCommitted: boolean;
  readonly occurredAt: string;
}

/** `OrderDetailResponse` — `GET .../orders/{orderId}`. Returns an `ETag` (the aggregate version). */
export interface OrderDetailResponse {
  readonly summary: OrderSummaryResponse;
  readonly subtotalMinor: number;
  readonly taxMinor: number;
  readonly acceptanceMode?: string | null;
  readonly lines: readonly OrderLine[];
  readonly warnings: readonly string[];
  readonly currentRevision: number;
  readonly createdByActorType?: string | null;
  readonly createdByActorId?: string | null;
  /**
   * Gap map row 9.2d: `createdByActorId` resolved to a name server-side
   * (`StaffDisplayNames`, the same cached lookup the staff activity log
   * already uses) — null for a non-`"USER"` actor or a subject with no name
   * on file, in which case {@link actorDisplay} still falls back to the raw
   * type/id pair rather than showing nothing.
   */
  readonly createdByDisplayName?: string | null;
  readonly acceptedByActorType?: string | null;
  readonly acceptedByActorId?: string | null;
  /** {@link createdByDisplayName}, for {@link acceptedByActorId}. */
  readonly acceptedByDisplayName?: string | null;
  readonly acceptedAt?: string | null;
  readonly callbackRequested: boolean;
  readonly callbackResolvedAt?: string | null;
  readonly kitchenNote?: string | null;
  readonly cashTenderedExpectedMinor?: number | null;
  readonly changeDueMinor?: number | null;
  readonly outcome?: OrderOutcome | null;
  readonly customer: OrderCustomer;
}

/**
 * `AddressResponse` as `GET .../customer/address` returns it — the delivery
 * address in full, decrypted (§3.8). Distinct from the summary-only
 * `hasAddress` flag on {@link OrderCustomer}.
 */
export interface OrderAddressReveal {
  readonly line1?: string | null;
  readonly line2?: string | null;
  readonly city?: string | null;
  readonly district?: string | null;
  readonly postalCode?: string | null;
  readonly entrance?: string | null;
  readonly floor?: string | null;
  readonly apartment?: string | null;
  readonly landmark?: string | null;
  readonly latitude: number;
  readonly longitude: number;
  readonly deliveryInstructions?: string | null;
}

/** `PhoneRevealResponse` — `GET .../customer/phone`. */
export interface OrderPhoneReveal {
  readonly phone: string | null;
}

/** `NoteResponse` — `GET .../lines/{lineId}/note`. */
export interface OrderLineNoteReveal {
  readonly lineId: string;
  readonly note: string | null;
}

/**
 * `TimelineEntryResponse` — one row of `GET .../timeline` (§3.10, commercial
 * lane).
 */
export interface OrderTimelineEntry {
  readonly sequence: number;
  readonly fromStatus: string;
  readonly toStatus: string;
  readonly trigger: string;
  readonly reasonCode?: string | null;
  readonly actorType: string;
  readonly occurredAt: string;
}

/**
 * `ApprovalDecisionResponse` — one row of `GET .../orders/{orderId}/decisions`
 * (wave P11, gap map row 1.2b): every approve/reject command this order ever
 * received, winner and losers alike. A separate read from {@link
 * OrderTimelineEntry}'s own `GET .../timeline` — that response is a released
 * contract `OpenApiContractTests` refuses to let narrow or change shape, so
 * this rides its own endpoint rather than widening that one from an array to
 * an object. At most one row per order has `effective: true`.
 */
export interface OrderApprovalDecision {
  readonly decisionId: string;
  /** `APPROVE` | `REJECT`. */
  readonly action: string;
  /** `HORECAOS_OPERATIONS` | `HORECAOS_TELEGRAM_BOT` | `POS` | `SYSTEM_TIMEOUT`. */
  readonly decisionChannel: string;
  /** `USER` | `SERVICE` | `SYSTEM_JOB` | `PROVIDER`. */
  readonly actorType: string;
  readonly actorId?: string | null;
  readonly reasonCode?: string | null;
  readonly effective: boolean;
  readonly issuedAt: string;
}

/**
 * `RevisionResponse` — one row of `GET .../revisions` (§3.9, ADR 0039, wave
 * P09/gap map `1.2p`). Revision 1 is the ADR 0019 checkout snapshot, byte
 * identical for ever; each applied amendment appends one carrying its own
 * complete recomputed total plus the delta against its predecessor.
 */
export interface RevisionResponse {
  readonly revision: number;
  readonly source: string;
  readonly amendmentId?: string | null;
  readonly currency: string;
  readonly subtotalMinor: number;
  readonly taxMinor: number;
  readonly discountMinor: number;
  readonly feeMinor: number;
  readonly totalMinor: number;
  readonly deltaTotalMinor: number;
  readonly createdByActorType: string;
  readonly createdByActorId?: string | null;
  readonly createdAt: string;
}

/**
 * `OrderCountsResponse` — `GET .../orders/counts` (§2.3). See `order-counts.ts`
 * for how this maps onto the board's seven tabs; note that `Внимание`'s live
 * severity queue is deliberately absent from this aggregate (the endpoint's
 * own Java doc comment on `JdbcOrderStore.counts` says why) and stays
 * client-derived regardless of whether this call succeeds.
 */
export interface OrderCountsResponse {
  readonly newOrders: number;
  readonly awaitingApproval: number;
  readonly inKitchen: number;
  readonly ready: number;
  readonly fulfilling: number;
  readonly completed: number;
  readonly cancelled: number;
  readonly totalNonTerminal: number;
  readonly total: number;
}

/**
 * `OrderDeliveryController.ShipmentResponse` — the shipment carrying one
 * order's plan, with the three V0054 custody timestamps `DispatchController`'s
 * own queue never serialises (gap map row 1.2n).
 */
export interface OrderDeliveryShipment {
  readonly shipmentId: string;
  /** `PENDING` | `ASSIGNED` | `PICKUP_PENDING` | `PICKED_UP` | `DELIVERED` | `CANCELLED`. */
  readonly status: string;
  /** `INTERNAL` | `PARTNER`. */
  readonly sourceType: string;
  readonly courierId?: string | null;
  readonly providerBindingId?: string | null;
  readonly assignedAt?: string | null;
  readonly pickedUpAt?: string | null;
  readonly deliveredAt?: string | null;
  readonly version: number;
}

/**
 * `OrderDeliveryController.DeliveryExceptionResponse` — one open ADR 0014
 * sourcing or cancellation exception against this order's plan (gap map rows
 * 1.2f/1.2g's own delivery-exception band). Never a customer name, address
 * or phone.
 */
export interface DeliveryExceptionView {
  readonly exceptionId: string;
  readonly reasonCode: string;
  readonly severity: string;
  readonly status: string;
  readonly detail?: string | null;
  readonly raisedAt: string;
}

/**
 * `OrderDeliveryController.OrderDeliveryResponse` — `GET .../orders/{orderId}/delivery`
 * (wave P11, gap map rows 1.2e/1.2n/2.1a; the `exceptions` band added by
 * wave P44, gap map rows 1.2f/1.2g). Not found for an order fulfilled some
 * other way (pickup, dine-in, a cancelled plan).
 */
export interface OrderDeliveryResponse {
  readonly planId: string;
  readonly planVersion: number;
  readonly planStatus: string;
  readonly estimatedReadyAt: string;
  readonly promisedDeliveryStart?: string | null;
  readonly promisedDeliveryEnd?: string | null;
  readonly customerDeliveryFeeMinor: number;
  /**
   * Set only when `fulfillment.delivery_cost_subsidies` recognised a gap
   * between the customer's fee and what the winning partner billed. A
   * provider-fulfilled order that came in at or under the customer's fee has
   * no such row — this is `null`, not zero, and the Money panel must say
   * "not tracked" rather than imply a break-even margin nothing recorded.
   */
  readonly providerCostMinor?: number | null;
  readonly currency: string;
  readonly courierEtaAt?: string | null;
  readonly shipment?: OrderDeliveryShipment | null;
  /** Empty for the ordinary case: sourcing settled cleanly and nothing needs an operator. */
  readonly exceptions: readonly DeliveryExceptionView[];
}

/** Re-exported so callers of `order-detail.ts` need not also import `order-actions.ts` for this one type. */
export type { OrderActionResponse };

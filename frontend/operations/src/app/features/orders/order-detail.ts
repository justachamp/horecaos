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
  readonly acceptedByActorType?: string | null;
  readonly acceptedByActorId?: string | null;
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
 * lane only; production and delivery are ADR 0041 / ADR 0014, not built).
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

/** Re-exported so callers of `order-detail.ts` need not also import `order-actions.ts` for this one type. */
export type { OrderActionResponse };

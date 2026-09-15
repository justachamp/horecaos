import { MessageKey } from '../../core/i18n/messages.en';
import { DeliveryCancellationOutcome } from './order-actions-api';
import {
  CustomerRefund,
  LiabilityParty,
  StockDisposition,
} from '../settings/reference-data/reference-data-api';

/**
 * Labels for the closed vocabularies `OutcomeResponse` and `ReasonResponse`
 * both carry (ADR 0039) — shared between the outcome band (§3.4's "why did
 * this order end") and the reasoned outcome dialog's consequences block
 * (§4.5), so the same category never reads two different ways depending on
 * which screen shows it.
 */

const STOCK_DISPOSITION_LABEL_KEYS: Readonly<Record<StockDisposition, MessageKey>> = {
  RELEASE: 'orders.dialog.outcome.stockDisposition.RELEASE',
  RETURN_TO_STOCK: 'orders.dialog.outcome.stockDisposition.RETURN_TO_STOCK',
  WRITE_OFF: 'orders.dialog.outcome.stockDisposition.WRITE_OFF',
  NO_EFFECT: 'orders.dialog.outcome.stockDisposition.NO_EFFECT',
};

const LIABILITY_PARTY_LABEL_KEYS: Readonly<Record<LiabilityParty, MessageKey>> = {
  TENANT: 'orders.dialog.outcome.liabilityParty.TENANT',
  CUSTOMER: 'orders.dialog.outcome.liabilityParty.CUSTOMER',
  COURIER_PARTNER: 'orders.dialog.outcome.liabilityParty.COURIER_PARTNER',
  PLATFORM: 'orders.dialog.outcome.liabilityParty.PLATFORM',
};

const CUSTOMER_REFUND_LABEL_KEYS: Readonly<Record<CustomerRefund, MessageKey>> = {
  FULL: 'orders.dialog.outcome.customerRefund.FULL',
  NONE: 'orders.dialog.outcome.customerRefund.NONE',
  DISCRETIONARY: 'orders.dialog.outcome.customerRefund.DISCRETIONARY',
};

/** `OutcomeSystemCategory` (ADR 0039) — the platform-owned category, never the tenant's own wording. */
const SYSTEM_CATEGORY_LABEL_KEYS: Readonly<Partial<Record<string, MessageKey>>> = {
  CUSTOMER_CANCELLED: 'orders.detail.outcome.category.CUSTOMER_CANCELLED',
  CUSTOMER_UNREACHABLE: 'orders.detail.outcome.category.CUSTOMER_UNREACHABLE',
  CUSTOMER_NO_SHOW: 'orders.detail.outcome.category.CUSTOMER_NO_SHOW',
  RESTAURANT_REFUSED: 'orders.detail.outcome.category.RESTAURANT_REFUSED',
  ITEM_UNAVAILABLE: 'orders.detail.outcome.category.ITEM_UNAVAILABLE',
  KITCHEN_CAPACITY: 'orders.detail.outcome.category.KITCHEN_CAPACITY',
  DELIVERY_FAILED: 'orders.detail.outcome.category.DELIVERY_FAILED',
  COURIER_UNAVAILABLE: 'orders.detail.outcome.category.COURIER_UNAVAILABLE',
  ADDRESS_UNSERVICEABLE: 'orders.detail.outcome.category.ADDRESS_UNSERVICEABLE',
  PAYMENT_NOT_RECEIVED: 'orders.detail.outcome.category.PAYMENT_NOT_RECEIVED',
  DUPLICATE_ORDER: 'orders.detail.outcome.category.DUPLICATE_ORDER',
  TEST_ORDER: 'orders.detail.outcome.category.TEST_ORDER',
  SUSPECTED_FRAUD: 'orders.detail.outcome.category.SUSPECTED_FRAUD',
  PRICING_ERROR: 'orders.detail.outcome.category.PRICING_ERROR',
  DELIVERED_OWN_COURIER: 'orders.detail.outcome.category.DELIVERED_OWN_COURIER',
  DELIVERED_PARTNER_COURIER: 'orders.detail.outcome.category.DELIVERED_PARTNER_COURIER',
  COLLECTED_BY_CUSTOMER: 'orders.detail.outcome.category.COLLECTED_BY_CUSTOMER',
  SERVED_IN_HOUSE: 'orders.detail.outcome.category.SERVED_IN_HOUSE',
  APPROVAL_DEADLINE_LAPSED: 'orders.detail.outcome.category.APPROVAL_DEADLINE_LAPSED',
  OTHER: 'orders.detail.outcome.category.OTHER',
};

const OUTCOME_KIND_LABEL_KEYS: Readonly<Partial<Record<string, MessageKey>>> = {
  COMPLETED: 'orders.detail.outcome.kind.COMPLETED',
  CANCELLED: 'orders.detail.outcome.kind.CANCELLED',
  REJECTED: 'orders.detail.outcome.kind.REJECTED',
  EXPIRED: 'orders.detail.outcome.kind.EXPIRED',
};

type Translate = (key: MessageKey, values?: Readonly<Record<string, string | number>>) => string;

export function stockDispositionLabel(value: string, translate: Translate): string {
  const key = STOCK_DISPOSITION_LABEL_KEYS[value as StockDisposition];
  return key ? translate(key) : value;
}

export function liabilityPartyLabel(value: string, translate: Translate): string {
  const key = LIABILITY_PARTY_LABEL_KEYS[value as LiabilityParty];
  return key ? translate(key) : value;
}

export function customerRefundLabel(value: string, translate: Translate): string {
  const key = CUSTOMER_REFUND_LABEL_KEYS[value as CustomerRefund];
  return key ? translate(key) : value;
}

/** A category this client does not recognise yet renders as its own raw value — never a blank cell. */
export function outcomeSystemCategoryLabel(value: string, translate: Translate): string {
  const key = SYSTEM_CATEGORY_LABEL_KEYS[value];
  return key ? translate(key) : value;
}

export function outcomeKindLabel(value: string, translate: Translate): string {
  const key = OUTCOME_KIND_LABEL_KEYS[value];
  return key ? translate(key) : value;
}

/**
 * `ShipmentCancellationPort.Result` (gap map row 1.2g) — what the cascade
 * found out about the courier provider the instant an order was cancelled.
 * `NOTHING_TO_CANCEL` and `PLAN_CANCELLED` have no key at all: neither is a
 * fact about a courier, so both render nothing rather than an empty notice.
 */
const DELIVERY_CANCELLATION_OUTCOME_LABEL_KEYS: Readonly<Partial<Record<string, MessageKey>>> = {
  INTERNAL_CANCELLED: 'orders.detail.delivery.cancelOutcome.INTERNAL_CANCELLED',
  PROVIDER_CANCELLED: 'orders.detail.delivery.cancelOutcome.PROVIDER_CANCELLED',
  PROVIDER_CANCELLED_CHARGEABLE:
    'orders.detail.delivery.cancelOutcome.PROVIDER_CANCELLED_CHARGEABLE',
  PROVIDER_UNCERTAIN: 'orders.detail.delivery.cancelOutcome.PROVIDER_UNCERTAIN',
  PROVIDER_FAILED: 'orders.detail.delivery.cancelOutcome.PROVIDER_FAILED',
};

/**
 * The text the cancel dialog's own notice band shows once the cascade
 * settles (gap map row 1.2g) — `null` when the order never had a courier to
 * tell in the first place, which is the ordinary case for a pickup or
 * dine-in order.
 */
export function deliveryCancellationOutcomeText(
  outcome: DeliveryCancellationOutcome | null | undefined,
  translate: Translate,
): string | null {
  if (!outcome) {
    return null;
  }
  const key = DELIVERY_CANCELLATION_OUTCOME_LABEL_KEYS[outcome.outcome];
  return key ? translate(key) : null;
}

/**
 * `DeliveryExceptionReason` (ADR 0014) — the delivery-exception band's own
 * reason label (gap map rows 1.2f/1.2g). A reason this client does not
 * recognise yet renders as its own raw value, never a blank cell.
 */
const DELIVERY_EXCEPTION_REASON_LABEL_KEYS: Readonly<Partial<Record<string, MessageKey>>> = {
  NO_PROVIDER: 'orders.detail.delivery.exceptionReason.NO_PROVIDER',
  LATE_ASSIGNMENT: 'orders.detail.delivery.exceptionReason.LATE_ASSIGNMENT',
  PROMISE_UNREACHABLE: 'orders.detail.delivery.exceptionReason.PROMISE_UNREACHABLE',
  AWAITING_RECONCILIATION: 'orders.detail.delivery.exceptionReason.AWAITING_RECONCILIATION',
  ABANDONED_HOLD: 'orders.detail.delivery.exceptionReason.ABANDONED_HOLD',
  LATE_RESTAURANT: 'orders.detail.delivery.exceptionReason.LATE_RESTAURANT',
  COURIER_NO_SHOW: 'orders.detail.delivery.exceptionReason.COURIER_NO_SHOW',
  ADDRESS_ISSUE: 'orders.detail.delivery.exceptionReason.ADDRESS_ISSUE',
  PROVIDER_CANCEL_UNCERTAIN: 'orders.detail.delivery.exceptionReason.PROVIDER_CANCEL_UNCERTAIN',
  PROVIDER_CANCEL_FAILED: 'orders.detail.delivery.exceptionReason.PROVIDER_CANCEL_FAILED',
};

export function deliveryExceptionReasonLabel(value: string, translate: Translate): string {
  const key = DELIVERY_EXCEPTION_REASON_LABEL_KEYS[value];
  return key ? translate(key) : value;
}

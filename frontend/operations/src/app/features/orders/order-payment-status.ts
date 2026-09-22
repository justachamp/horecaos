import { MessageKey } from '../../core/i18n/messages.en';

/**
 * `ordering.orders.payment_status_projection` (`ck_order_payment_projection`,
 * `V0022`) — the order-level payment projection `OrderSummaryResponse` (wave
 * P04, ADR 0102) carries on every board row. Distinct from Finance's own
 * richer `PaymentIntentStatus`/`PaymentAttemptStatus`/`TenderStatus`
 * (`finance-labels.ts`): this is the ordering module's own simplified view,
 * cheap enough to project onto a list row without a join into the payment
 * module's own tables.
 */
export const ORDER_PAYMENT_STATUS_PROJECTIONS = [
  'NOT_REQUIRED',
  'PENDING',
  'AUTHORIZED',
  'CAPTURED',
  'FAILED',
  'VOIDED',
  'REFUNDED',
] as const;

export type OrderPaymentStatusProjection = (typeof ORDER_PAYMENT_STATUS_PROJECTIONS)[number];

const KNOWN_PROJECTIONS: ReadonlySet<string> = new Set(ORDER_PAYMENT_STATUS_PROJECTIONS);

export function isKnownPaymentStatusProjection(
  value: string,
): value is OrderPaymentStatusProjection {
  return KNOWN_PROJECTIONS.has(value);
}

/**
 * `NOT_REQUIRED` has no badge of its own yet — orders.md §2.5's own column
 * note: "`NOT_REQUIRED` renders as Наличными once ADR 0013 lands, and as —
 * before". ADR 0013 (payment method on the row) is not built, so this stays a
 * dash rather than guessing a method the row does not actually carry.
 */
const PAYMENT_STATUS_LABEL_KEYS: Readonly<
  Record<Exclude<OrderPaymentStatusProjection, 'NOT_REQUIRED'>, MessageKey>
> = {
  PENDING: 'orders.paymentStatus.PENDING',
  AUTHORIZED: 'orders.paymentStatus.AUTHORIZED',
  CAPTURED: 'orders.paymentStatus.CAPTURED',
  FAILED: 'orders.paymentStatus.FAILED',
  VOIDED: 'orders.paymentStatus.VOIDED',
  REFUNDED: 'orders.paymentStatus.REFUNDED',
};

/**
 * §2.5 column 10 (Оплата). `value` is absent for a fixture or an interim
 * response minted before this field existed (the same fallback rule
 * `order-queue.ts`'s own `version` doc comment uses) — treated the same as
 * `NOT_REQUIRED`, a dash, rather than a fabricated status.
 *
 * An unrecognised value (a future projection this client does not know yet)
 * renders as its own raw text — the same forward-compatibility rule
 * {@link import('./order-status').orderStatusLabel} applies to an order
 * status.
 */
export function paymentStatusProjectionLabel(
  value: string | null | undefined,
  translate: (key: MessageKey) => string,
): string {
  if (!value || value === 'NOT_REQUIRED') {
    return '—';
  }
  if (!isKnownPaymentStatusProjection(value)) {
    return value;
  }
  // `value === 'NOT_REQUIRED'` already returned above, so the remaining six
  // are exactly what `PAYMENT_STATUS_LABEL_KEYS` is keyed on — the assertion
  // states that, it does not widen past it.
  return translate(
    PAYMENT_STATUS_LABEL_KEYS[value as Exclude<OrderPaymentStatusProjection, 'NOT_REQUIRED'>],
  );
}

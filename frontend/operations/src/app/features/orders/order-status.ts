import { MessageKey } from '../../core/i18n/messages.en';

/**
 * The twelve canonical statuses — `ordering.orders.status`, `ck_order_status`,
 * `docs/operations-spec/orders.md` §1.1. Code-owned; no tenant may reorder or
 * extend them.
 */
export const ORDER_STATUSES = [
  'RECEIVED',
  'PAYMENT_AUTHORIZING',
  'AWAITING_APPROVAL',
  'PAYMENT_FAILED',
  'CONFIRMED',
  'REJECTED',
  'EXPIRED',
  'PREPARING',
  'READY',
  'FULFILLING',
  'COMPLETED',
  'CANCELLED',
] as const;

export type OrderStatus = (typeof ORDER_STATUSES)[number];

const KNOWN_STATUSES: ReadonlySet<string> = new Set(ORDER_STATUSES);

/**
 * Whether `value` is one of the twelve. ADR 0031 allows the server to add
 * status values within a major version, so an order arriving with something
 * unfamiliar is a forward-compatible response, not a bug — see
 * {@link orderStatusLabel} for what the board does with one.
 */
export function isKnownOrderStatus(value: string): value is OrderStatus {
  return KNOWN_STATUSES.has(value);
}

/**
 * Terminal per §2.7 ("Terminal orders are never flagged, whatever their
 * history") and the §2.3 "Отменены" tab grouping. `COMPLETED` is the one
 * successful terminal state; `CANCELLED`, `REJECTED` and `EXPIRED` are the
 * three ways an order dies without being fulfilled. All four are dead ends in
 * `OrderStateMachine` — nothing transitions out of them — and none re-enters a
 * live tab.
 */
export const TERMINAL_ORDER_STATUSES: readonly OrderStatus[] = [
  'COMPLETED',
  'CANCELLED',
  'REJECTED',
  'EXPIRED',
];

const TERMINAL_STATUS_SET: ReadonlySet<string> = new Set(TERMINAL_ORDER_STATUSES);

export function isTerminalOrderStatus(status: string): boolean {
  return TERMINAL_STATUS_SET.has(status);
}

/**
 * The canonical "в процессе" grouping — frontend-information-architecture.md
 * §0's Live board (IA 0.1) "owns... the canonical definition of the 'in
 * progress' grouping over concrete statuses", and this is that one definition,
 * so a second screen computing it independently would be the exact drift the
 * IA line warns against.
 *
 * Not simply "not terminal": `PAYMENT_FAILED` is non-terminal (an operator can
 * still retry payment or cancel) but is not "in progress" — nobody is
 * working the order forward right now, which is why it lives only in the
 * board's `attention` tab (`order-tabs.ts`). Excluding it here is what makes
 * this set equal `order-tabs.ts`'s `new ∪ preparing ∪ delivering` union
 * exactly, and equal `JdbcOrderStore.counts`'s `total_non_terminal` column,
 * which excludes it by the same reasoning
 * (`WHERE status NOT IN ('PAYMENT_FAILED', 'REJECTED', 'EXPIRED', 'COMPLETED',
 * 'CANCELLED')`) — see `order-status.spec.ts` for the executable proof that
 * the three do not drift apart.
 */
export const IN_PROGRESS_ORDER_STATUSES: readonly OrderStatus[] = [
  'RECEIVED',
  'PAYMENT_AUTHORIZING',
  'AWAITING_APPROVAL',
  'CONFIRMED',
  'PREPARING',
  'READY',
  'FULFILLING',
];

const IN_PROGRESS_STATUS_SET: ReadonlySet<string> = new Set(IN_PROGRESS_ORDER_STATUSES);

export function isInProgressOrderStatus(status: string): boolean {
  return IN_PROGRESS_STATUS_SET.has(status);
}

/** The i18n key carrying a known status's operator-facing word (§1.1's ru column). */
export const ORDER_STATUS_LABEL_KEYS: Readonly<Record<OrderStatus, MessageKey>> = {
  RECEIVED: 'orders.status.RECEIVED',
  PAYMENT_AUTHORIZING: 'orders.status.PAYMENT_AUTHORIZING',
  AWAITING_APPROVAL: 'orders.status.AWAITING_APPROVAL',
  PAYMENT_FAILED: 'orders.status.PAYMENT_FAILED',
  CONFIRMED: 'orders.status.CONFIRMED',
  REJECTED: 'orders.status.REJECTED',
  EXPIRED: 'orders.status.EXPIRED',
  PREPARING: 'orders.status.PREPARING',
  READY: 'orders.status.READY',
  FULFILLING: 'orders.status.FULFILLING',
  COMPLETED: 'orders.status.COMPLETED',
  CANCELLED: 'orders.status.CANCELLED',
};

/**
 * The label to render for a status, known or not.
 *
 * An order whose `status` is not one of the twelve is not this client's to
 * reject: refusing to render the row would turn an additive, forward-compatible
 * server release into a blank spot on the board. Unknown status renders as the
 * raw wire value — harmless, visible, and a clear signal to whoever sees it
 * that this client is behind. `translate` is `I18n.t`, passed in rather than
 * injected so this stays a pure function.
 */
export function orderStatusLabel(status: string, translate: (key: MessageKey) => string): string {
  return isKnownOrderStatus(status) ? translate(ORDER_STATUS_LABEL_KEYS[status]) : status;
}

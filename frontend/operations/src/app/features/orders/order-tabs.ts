import { MessageKey } from '../../core/i18n/messages.en';
import { OrderSeverityLevel } from './order-severity';
import { OrderStatus } from './order-status';

/**
 * The seven tabs, §2.3 — route-bound (`/orders?tab=attention`), exactly the
 * ids `shell.html` already links to (`routerLink="/orders"
 * [queryParams]="{ tab: 'attention' }"`).
 */
export const ORDER_TABS = [
  'attention',
  'new',
  'preparing',
  'delivering',
  'completed',
  'cancelled',
  'all',
] as const;

export type OrderTabId = (typeof ORDER_TABS)[number];

/** §0.4: the board opens on Внимание, not on the newest order. */
export const DEFAULT_ORDER_TAB: OrderTabId = 'attention';

const TAB_ID_SET: ReadonlySet<string> = new Set(ORDER_TABS);

/** An unrecognised or absent `?tab=` value falls back to {@link DEFAULT_ORDER_TAB} — never a blank board. */
export function isOrderTabId(value: string | null): value is OrderTabId {
  return value !== null && TAB_ID_SET.has(value);
}

export interface OrderTabDefinition {
  readonly id: OrderTabId;
  readonly labelKey: MessageKey;
  /** True for the four "live" tabs that sort by severity rather than by time (§2.6). */
  readonly severityOrdered: boolean;
}

export const ORDER_TAB_DEFINITIONS: Readonly<Record<OrderTabId, OrderTabDefinition>> = {
  attention: { id: 'attention', labelKey: 'orders.tab.attention', severityOrdered: true },
  new: { id: 'new', labelKey: 'orders.tab.new', severityOrdered: true },
  preparing: { id: 'preparing', labelKey: 'orders.tab.preparing', severityOrdered: true },
  delivering: { id: 'delivering', labelKey: 'orders.tab.delivering', severityOrdered: true },
  completed: { id: 'completed', labelKey: 'orders.tab.completed', severityOrdered: false },
  cancelled: { id: 'cancelled', labelKey: 'orders.tab.cancelled', severityOrdered: false },
  all: { id: 'all', labelKey: 'orders.tab.all', severityOrdered: false },
};

const NEW_STATUSES: ReadonlySet<OrderStatus> = new Set([
  'RECEIVED',
  'PAYMENT_AUTHORIZING',
  'AWAITING_APPROVAL',
]);
const PREPARING_STATUSES: ReadonlySet<OrderStatus> = new Set(['CONFIRMED', 'PREPARING', 'READY']);
const CANCELLED_STATUSES: ReadonlySet<OrderStatus> = new Set(['CANCELLED', 'REJECTED', 'EXPIRED']);

/** The two fields {@link isOrderTabMember} needs from an order — not the whole DTO. */
export interface OrderTabMember {
  readonly status: string;
  readonly severityLevel: OrderSeverityLevel;
}

/**
 * Whether an order belongs to a tab (§2.3's Membership column).
 *
 * `attention` is deliberately not a partition — its members also appear under
 * their own status tab too ("its rows also appear in their status tab,
 * deliberately", §2.3). The full membership also includes any process in
 * `MANUAL_ACTION_REQUIRED` / `FAILED_RETRYABLE` and an unresolved
 * `callback_requested`; neither is built, so this uses what is:
 * `AWAITING_APPROVAL`, `PAYMENT_FAILED`, and anything
 * {@link computeOrderSeverity} flagged (which already covers the process and
 * lateness signals this board can compute).
 *
 * An order with a status outside the known twelve matches no specific tab —
 * it still renders under `all`, which is unconditional, so it is never simply
 * dropped (`order-status.ts`'s "render harmlessly" rule, applied to grouping).
 */
export function isOrderTabMember(tab: OrderTabId, order: OrderTabMember): boolean {
  switch (tab) {
    case 'attention':
      return (
        order.status === 'AWAITING_APPROVAL' ||
        order.status === 'PAYMENT_FAILED' ||
        order.severityLevel !== 'NORMAL'
      );
    case 'new':
      return NEW_STATUSES.has(order.status as OrderStatus);
    case 'preparing':
      return PREPARING_STATUSES.has(order.status as OrderStatus);
    case 'delivering':
      return order.status === 'FULFILLING';
    case 'completed':
      return order.status === 'COMPLETED';
    case 'cancelled':
      return CANCELLED_STATUSES.has(order.status as OrderStatus);
    case 'all':
      return true;
    default:
      return false;
  }
}

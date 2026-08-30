import { MessageKey } from '../../core/i18n/messages.en';

/**
 * The four lifecycle actions the server can ever offer (orders.md §4.2, §4.3),
 * mirroring `uz.horecaos.platform.ordering.application.OrderActionCode`. A
 * closed set on the server; this client still renders an unrecognised code
 * harmlessly (see {@link actionLabel}) for the same forward-compatibility
 * reason `order-status.ts` renders an unknown order status — an additive
 * server release must not blank a row.
 */
export const ORDER_ACTION_CODES = ['APPROVE', 'REJECT', 'ADVANCE', 'CANCEL'] as const;
export type OrderActionCode = (typeof ORDER_ACTION_CODES)[number];

/**
 * Mirrors `OrderActionResponse` — the entire wire shape of one entry in
 * `actions[]`. `targetStatus` is set only for `ADVANCE`.
 */
export interface OrderActionResponse {
  readonly action: string;
  readonly targetStatus?: string | null;
}

/**
 * §2.9: "at most two inline affordances on the row, everything else in the
 * overflow." The server's own ordering — decision first, then every legal
 * advance, then cancel last (`OrderActionsPolicy.availableFor`'s doc comment)
 * — already puts the single most likely next action first, so taking a prefix
 * is enough; this function invents no priority of its own.
 */
export const MAX_INLINE_ACTIONS = 2;

export interface SplitActions {
  readonly inline: readonly OrderActionResponse[];
  readonly overflow: readonly OrderActionResponse[];
}

export function splitInlineOverflow(
  actions: readonly OrderActionResponse[] | undefined,
): SplitActions {
  const all = actions ?? [];
  return {
    inline: all.slice(0, MAX_INLINE_ACTIONS),
    overflow: all.slice(MAX_INLINE_ACTIONS),
  };
}

const ADVANCE_LABEL_KEYS: Readonly<Record<string, MessageKey>> = {
  PREPARING: 'orders.action.advance.PREPARING',
  READY: 'orders.action.advance.READY',
  FULFILLING: 'orders.action.advance.FULFILLING',
};

/**
 * The label for one `actions[]` entry.
 *
 * `ADVANCE` is not one label: §2.9/§4.11 name it by what the *target* status
 * means to an operator, which is not the same word `order-status.ts` uses for
 * that status as a noun (`PREPARING`'s status word is "Готовится"; the button
 * that produces it is "На кухню"). `COMPLETED` further splits by fulfilment
 * mode — "Выдан" for pickup and dine-in, "Доставлен" for delivery, per §2.9's
 * own row table. A target this map does not name (the server's `Продвинуть`
 * is a generic transition and `OrderActionsPolicy` can legally offer others,
 * e.g. `RECEIVED -> CONFIRMED`) falls back to a generic "→ status" rendering
 * rather than throwing — the same harmless-render rule as an unknown status.
 */
export function actionLabel(
  action: OrderActionResponse,
  fulfillmentMode: string | null | undefined,
  translate: (key: MessageKey, values?: Readonly<Record<string, string | number>>) => string,
  statusLabel: (status: string) => string,
): string {
  switch (action.action) {
    case 'APPROVE':
      return translate('orders.action.approve');
    case 'REJECT':
      return translate('orders.action.reject');
    case 'CANCEL':
      return translate('orders.action.cancel');
    case 'ADVANCE': {
      const target = action.targetStatus ?? '';
      if (target === 'COMPLETED') {
        return fulfillmentMode === 'DELIVERY'
          ? translate('orders.action.advance.completedDelivery')
          : translate('orders.action.advance.completedPickup');
      }
      const key = ADVANCE_LABEL_KEYS[target];
      if (key) {
        return translate(key);
      }
      return translate('orders.action.advance.generic', { status: statusLabel(target) });
    }
    default:
      // A future action code this client does not know yet — the raw code is
      // at least visible and reportable, never a blank button.
      return action.action;
  }
}

/**
 * `POST .../state-actions` requires a non-blank `reasonCode` (`StateActionRequest`
 * in `OperationsOrderController`), but §4.3's own table marks `Продвинуть`'s
 * confirm column "no" — no dialog collects one from the operator, the same way
 * §4.6 completion skips a dialog "where exactly one reason is valid". This is
 * the fixed, honest value sent in that case: a real, auditable statement that
 * an operator advanced the order from the console, not a placeholder.
 */
export function advanceReasonCode(targetStatus: string): string {
  return `OPERATIONS_ADVANCE_${targetStatus}`;
}

/**
 * Mints and remembers one `decisionId` per order (orders.md §4.3): "client-supplied
 * and stable across retries of one human decision, so the same click arriving
 * twice is one decision." A second, unrelated decision on the same order (a
 * fresh click well after the first one settled) gets a fresh id once
 * {@link DecisionIdRegistry.settle} has cleared the old one — never a
 * random id per keystroke, which would turn a flaky retry into a race against
 * another operator.
 */
export class DecisionIdRegistry {
  private readonly ids = new Map<string, string>();

  idFor(orderId: string): string {
    let id = this.ids.get(orderId);
    if (id === undefined) {
      id = crypto.randomUUID();
      this.ids.set(orderId, id);
    }
    return id;
  }

  /** The decision reached an outcome (applied, lost the race, or failed permanently). */
  settle(orderId: string): void {
    this.ids.delete(orderId);
  }
}

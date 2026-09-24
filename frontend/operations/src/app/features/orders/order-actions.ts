import { MessageKey } from '../../core/i18n/messages.en';

/**
 * The lifecycle actions the server can ever offer (orders.md §4.2, §4.3),
 * mirroring `uz.horecaos.platform.ordering.application.OrderActionCode`. A
 * closed set on the server; this client still renders an unrecognised code
 * harmlessly (see {@link actionLabel}) for the same forward-compatibility
 * reason `order-status.ts` renders an unknown order status — an additive
 * server release must not blank a row.
 *
 * `COMPLETE` (wave P09, gap map `1.2j`) is offered alongside — never instead
 * of — the generic `ADVANCE` entry to a `COMPLETED` target; see
 * `OrderActionsPolicy`'s own Java doc for why both exist. `order-detail-pane.ts`
 * prefers `COMPLETE` and hides the redundant `ADVANCE` entry, because its
 * `onActionClick` can run the fulfilment-mode-aware completion-reason flow
 * `COMPLETE` deserves. `order-queue.ts` (H3, orders.md §2.9) has no room in a
 * dense row for that flow and no handler for `COMPLETE` — it drops the
 * redundant `COMPLETE` entry instead and keeps using the already-wired
 * `ADVANCE`, which the server still emits for exactly that reason (a client
 * built before wave P09 still works against the generic entry).
 *
 * `AMEND` (ADR 0039/0105/0113, wave P10, gap map `1.2h`/`1.1e`) opens the
 * amendment submenu (orders.md §4.4). `OrderActionsPolicy.AMEND_EMISSION_ENABLED`
 * held its emission back until this wave gave it a translated label here and
 * a real handler; `order-detail-pane.ts`'s `onActionClick` opens
 * `q-order-amend-menu`, and `order-queue.ts`'s opens the order itself, since
 * the menu's five dialogs live on the detail pane, not the row.
 *
 * `OVERRIDE` (ADR 0019 amendment, ADR 0110, wave 9 gap map `1.1h`) restores an
 * earlier status through `POST .../state-overrides` — a compensating edge
 * (`READY -> PREPARING`, `FULFILLING -> READY`), never a literal reversal.
 * Distinct from `ADVANCE` the same way `CANCEL` already is: its own
 * capability (`ORDER_STATE_OVERRIDE`), its own dialog, and a mandatory
 * registry reason with no reasonless path. `targetStatus` names the status it
 * restores, exactly as it does for `ADVANCE`.
 *
 * `ASSIGN_COURIER` (orders.md §4.7, gap map row 1.1e) is offered for a
 * delivery order whose active plan has nobody carrying it yet
 * (`Capability.DELIVERY_MANUAL_ASSIGN`). `order-queue.ts`'s handler opens the
 * order rather than a second picker — the assign control already lives on
 * the order detail pane (§1.2e), reusing `DispatchController`'s own
 * manual-assignment endpoint.
 */
export const ORDER_ACTION_CODES = [
  'APPROVE',
  'REJECT',
  'ADVANCE',
  'CANCEL',
  'COMPLETE',
  'AMEND',
  'OVERRIDE',
  'ASSIGN_COURIER',
] as const;
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
 * `OVERRIDE`'s own button word, by the target it restores — distinct from
 * {@link ADVANCE_LABEL_KEYS} even where the target status is the same one
 * (`READY`), because "На кухню" (send forward) and "Вернуть на кухню" (send
 * back) are different instructions to an operator holding two different
 * capabilities.
 */
const OVERRIDE_LABEL_KEYS: Readonly<Record<string, MessageKey>> = {
  PREPARING: 'orders.action.override.PREPARING',
  READY: 'orders.action.override.READY',
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
    case 'COMPLETE':
      return fulfillmentMode === 'DELIVERY'
        ? translate('orders.action.advance.completedDelivery')
        : translate('orders.action.advance.completedPickup');
    case 'AMEND':
      return translate('orders.action.amend');
    case 'ASSIGN_COURIER':
      return translate('orders.action.assignCourier');
    case 'OVERRIDE': {
      const target = action.targetStatus ?? '';
      const key = OVERRIDE_LABEL_KEYS[target];
      return key ? translate(key) : translate('orders.action.override.generic', { status: statusLabel(target) });
    }
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

const DECISION_OUTCOME_LABEL_KEYS: Readonly<Record<string, MessageKey>> = {
  APPROVE: 'orders.action.outcome.APPROVE',
  REJECT: 'orders.action.outcome.REJECT',
};

/**
 * The past-tense word for a decision that already settled an order —
 * "принят"/"отклонён" — distinct from {@link actionLabel}'s imperative button
 * word ("Принять"/"Отклонить"). §4.3's own example is a participle, not a
 * command: "«Уже принят — Ш. Каримов, 14:03»". Used only for the lost-race
 * notice on a settled `DecisionResponse.effectiveAction`.
 */
export function decisionOutcomeLabel(
  effectiveAction: string,
  translate: (key: MessageKey) => string,
): string {
  const key = DECISION_OUTCOME_LABEL_KEYS[effectiveAction];
  return key ? translate(key) : effectiveAction;
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
 * Mirrors `OrderActionsPolicy.canCancelWithoutReason` (server): false for
 * `CONFIRMED`, `PREPARING`, `READY` and `FULFILLING`, the four statuses wave
 * P09 gave a `CANCELLED` edge without also allowing the old reasonless path
 * (H2, orders.md §4.5) — every earlier status (`RECEIVED`,
 * `AWAITING_APPROVAL`, `PAYMENT_AUTHORIZING`, …) still accepts the reasonless
 * `Отменить`. A client that guesses wrong here does not get a second chance
 * to guess again: the reasonless call is refused outright with
 * `CancellationNotPermittedException` (409), so the caller must ask this
 * *before* choosing which dialog to open, not after the request fails.
 */
const STATUSES_REQUIRING_CANCELLATION_REASON: ReadonlySet<string> = new Set([
  'CONFIRMED',
  'PREPARING',
  'READY',
  'FULFILLING',
]);

export function requiresCancellationReason(status: string): boolean {
  return STATUSES_REQUIRING_CANCELLATION_REASON.has(status);
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

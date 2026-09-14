import { LatenessPolicy, evaluateLateness } from '../../core/lateness-policy';
import { MessageKey } from '../../core/i18n/messages.en';

/**
 * The KDS queue's tabs (IA 2.1: "delivery/pickup/hall/aggregator tabs").
 *
 * **Four fulfilment/channel facts, not a fifth "all".** `fulfilmentMode`
 * gives delivery/pickup/hall — "hall" is `DINE_IN`, matching
 * `order-queue.ts`'s own mapping — and `aggregator` (wave P16) is a
 * *channel* fact, `channelSystemType === 'AGGREGATOR'`, resolved server-side
 * off `sales_channels.system_type` rather than pattern-matched from the free
 * -string `channelCode` this response also carries. Before this wave nothing
 * on the wire let a client tell an aggregator channel from a direct one, so
 * `channelCode` rendered as an unclassified chip and this fourth tab did not
 * exist; `all` is kept as the default landing tab rather than dropped, since
 * every one of the other four is a genuine subset and a cook opening the
 * screen still wants the whole queue first.
 */
export const KITCHEN_TABS = ['all', 'delivery', 'pickup', 'dineIn', 'aggregator'] as const;
export type KitchenTabId = (typeof KITCHEN_TABS)[number];
export const DEFAULT_KITCHEN_TAB: KitchenTabId = 'all';

const TAB_ID_SET: ReadonlySet<string> = new Set(KITCHEN_TABS);

export function isKitchenTabId(value: string | null): value is KitchenTabId {
  return value !== null && TAB_ID_SET.has(value);
}

export interface KitchenTabDefinition {
  readonly id: KitchenTabId;
  readonly labelKey: MessageKey;
}

export const KITCHEN_TAB_DEFINITIONS: Readonly<Record<KitchenTabId, KitchenTabDefinition>> = {
  all: { id: 'all', labelKey: 'kitchen.tab.all' },
  delivery: { id: 'delivery', labelKey: 'kitchen.tab.delivery' },
  pickup: { id: 'pickup', labelKey: 'kitchen.tab.pickup' },
  dineIn: { id: 'dineIn', labelKey: 'kitchen.tab.dineIn' },
  aggregator: { id: 'aggregator', labelKey: 'kitchen.tab.aggregator' },
};

/**
 * `channelSystemType` is `undefined`/`null` on a ticket a single-item
 * mutation just settled without repeating the board's own resolved chip
 * (see `TicketResponse.channelSystemType`'s own doc) — such a ticket never
 * matches the `aggregator` tab until the next board refresh re-resolves it,
 * which is the same "eventually correct, never wrong" trade every other
 * field on this board already makes at the 10-second poll boundary.
 */
export function isKitchenTabMember(
  tab: KitchenTabId,
  fulfilmentMode: string,
  channelSystemType?: string | null,
): boolean {
  switch (tab) {
    case 'all':
      return true;
    case 'delivery':
      return fulfilmentMode === 'DELIVERY';
    case 'pickup':
      return fulfilmentMode === 'PICKUP';
    case 'dineIn':
      return fulfilmentMode === 'DINE_IN';
    case 'aggregator':
      return channelSystemType === 'AGGREGATOR';
  }
}

/**
 * SLA colour-coding (IA 2.1: "colour-coded by SLA", gap map rows
 * `1.1g`/`X.39`).
 *
 * **More real than the order board's own severity model, not less.** A
 * kitchen ticket carries `targetReadyAt` — a genuine, server-computed promise
 * (`KitchenTicketService`/ADR 0041's own time model) — so this needs none of
 * `order-severity.ts`'s ADR 0014 workarounds. Before this wave the two tiers
 * here (`BREACHED`/`AT_RISK`) were computed against a five-minute window this
 * file invented on its own (`AT_RISK_THRESHOLD_MS`), independent of
 * `order-severity.ts`'s own, different invented number. Both now call
 * `core/lateness-policy.ts`'s shared `evaluateLateness` against the same
 * resolved `ordering.lateness` policy the order board reads, so the two
 * boards agree on the AT_RISK/LATE boundary by construction — this row's
 * whole point. `BREACHED` is this file's own name for that shared module's
 * `LATE`; kept rather than renamed because every caller of this type
 * (`kitchen-queue-page.ts`, `vdu-page.ts`) already matches on it.
 */
export type TicketSeverityLevel = 'BREACHED' | 'AT_RISK' | 'NORMAL';
export type TicketSeverityTone = 'danger' | 'warning' | 'none';

export interface TicketSeverityInput {
  readonly targetReadyAt: Date | null;
  readonly createdAt: Date;
  /** `DELIVERY` | `PICKUP` | `DINE_IN` — selects the resolved policy's per-mode thresholds. */
  readonly fulfilmentMode: string | null | undefined;
}

export interface TicketSeverity {
  readonly level: TicketSeverityLevel;
  readonly tone: TicketSeverityTone;
}

const NORMAL_SEVERITY: TicketSeverity = { level: 'NORMAL', tone: 'none' };

export function computeTicketSeverity(
  input: TicketSeverityInput,
  now: Date,
  policy: LatenessPolicy,
): TicketSeverity {
  const level = evaluateLateness(
    {
      fulfilmentMode: input.fulfilmentMode,
      // The kitchen's own promise is targetReadyAt (already net of travel,
      // ADR 0041) — not the order's full promisedAt, which is the order
      // board's own concern.
      promisedAt: input.targetReadyAt,
      createdAt: input.createdAt,
      isTerminal: false, // a ticket carries no order status here; the board filters handed-over tickets itself
    },
    policy,
    now,
  );

  switch (level) {
    case 'LATE':
      return { level: 'BREACHED', tone: 'danger' };
    case 'AT_RISK':
      return { level: 'AT_RISK', tone: 'warning' };
    case 'NORMAL':
      return NORMAL_SEVERITY;
  }
}

/**
 * What a device may do to one ticket line right now, derived client-side from
 * `TicketItemStatus` and `TicketStatus`.
 *
 * **A deliberate, documented exception to "the client never computes
 * availability" (`order-actions.ts`'s own rule, restated in `order-queue.ts`).**
 * `KitchenBoardController`'s responses carry no `actions[]`-equivalent at the
 * item level — unlike the order board, nothing on the wire says what this
 * line may do next. The alternative to reconstructing
 * `KitchenStateMachine.permits()`'s small, closed transition table here is
 * rendering every button on every line regardless of state and letting the
 * server's own `409`/`422` be the first place an operator learns a recall was
 * refused — a worse experience than a client-derived affordance that is wrong
 * for, at most, the instant between an event this device has not polled yet
 * and the next 10-second refresh.
 */
export type KitchenItemAction = 'START' | 'READY' | 'RECALL';

export function availableItemActions(
  itemStatus: string,
  ticketStatus: string,
): readonly KitchenItemAction[] {
  switch (itemStatus) {
    case 'QUEUED':
      return ['START'];
    case 'STARTED':
      return ['READY'];
    case 'READY':
      // Refused server-side once the ticket has been handed over — the food
      // has left the pass. Mirrored here so the button is not offered only to
      // be refused.
      return ticketStatus === 'HANDED_OVER' ? [] : ['RECALL'];
    default:
      return [];
  }
}

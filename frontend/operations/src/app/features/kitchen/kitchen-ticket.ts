import { MessageKey } from '../../core/i18n/messages.en';

/**
 * The KDS queue's tabs (IA 2.1: "delivery/pickup/hall/aggregator tabs").
 *
 * **Reduced to three, deliberately.** `TicketResponse.fulfilmentMode` is
 * `DELIVERY` | `PICKUP` | `DINE_IN` (`uz.horecaos.platform.tenancy.api.FulfillmentMode`)
 * — "hall" is `DINE_IN`, matching `order-queue.ts`'s own mapping. There is no
 * fourth value for "aggregator": that is a *channel* fact
 * (`sales_channels.system_type = 'AGGREGATOR'`), and `channelCode` on the wire
 * is a free string, not a typed system-type — nothing on this response lets
 * this client tell an aggregator channel from a direct one. Fabricating a
 * fourth tab from data that is not there is exactly the mistake `order-queue.ts`
 * already refuses for its own missing columns, so `channelCode` renders as a
 * chip on each ticket instead (§2.1's own "channel" fact, honestly reduced to
 * "here is the raw code" rather than a classified tab).
 */
export const KITCHEN_TABS = ['all', 'delivery', 'pickup', 'dineIn'] as const;
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
};

export function isKitchenTabMember(tab: KitchenTabId, fulfilmentMode: string): boolean {
  switch (tab) {
    case 'all':
      return true;
    case 'delivery':
      return fulfilmentMode === 'DELIVERY';
    case 'pickup':
      return fulfilmentMode === 'PICKUP';
    case 'dineIn':
      return fulfilmentMode === 'DINE_IN';
  }
}

/**
 * SLA colour-coding (IA 2.1: "colour-coded by SLA").
 *
 * **More real than the order board's own severity model, not less.** A
 * kitchen ticket carries `targetReadyAt` — a genuine, server-computed promise
 * (`KitchenTicketService`/ADR 0041's own time model) — so this needs none of
 * `order-severity.ts`'s ADR 0014 workarounds. Two tiers only: `BREACHED` once
 * the target has passed, `AT_RISK` inside a five-minute warning window before
 * it, mirroring §2.6's own "AWAITING_APPROVAL, under two minutes" precedent of
 * a named threshold ahead of the deadline rather than only after it. A ticket
 * with no `targetReadyAt` (a fixture, or a future response shape this client
 * has not learned yet) falls back to `orders.md` §2.7's own number — 45
 * minutes with no promise — applied to `createdAt`, exactly the fallback
 * `order-severity.ts`'s `NO_PROMISE_FALLBACK_MS` already uses for the same
 * reason.
 */
export type TicketSeverityLevel = 'BREACHED' | 'AT_RISK' | 'NORMAL';
export type TicketSeverityTone = 'danger' | 'warning' | 'none';

export interface TicketSeverityInput {
  readonly targetReadyAt: Date | null;
  readonly createdAt: Date;
}

export interface TicketSeverity {
  readonly level: TicketSeverityLevel;
  readonly tone: TicketSeverityTone;
}

/** The warning window before `targetReadyAt`. */
export const AT_RISK_THRESHOLD_MS = 5 * 60 * 1000;

/** `orders.md` §2.7's own fallback, reused: no promise, 45 minutes. */
export const NO_PROMISE_FALLBACK_MS = 45 * 60 * 1000;

const NORMAL_SEVERITY: TicketSeverity = { level: 'NORMAL', tone: 'none' };

export function computeTicketSeverity(input: TicketSeverityInput, now: Date): TicketSeverity {
  if (input.targetReadyAt !== null) {
    const remainingMs = input.targetReadyAt.getTime() - now.getTime();
    if (remainingMs <= 0) {
      return { level: 'BREACHED', tone: 'danger' };
    }
    if (remainingMs <= AT_RISK_THRESHOLD_MS) {
      return { level: 'AT_RISK', tone: 'warning' };
    }
    return NORMAL_SEVERITY;
  }

  const elapsedMs = now.getTime() - input.createdAt.getTime();
  return elapsedMs > NO_PROMISE_FALLBACK_MS
    ? { level: 'BREACHED', tone: 'danger' }
    : NORMAL_SEVERITY;
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

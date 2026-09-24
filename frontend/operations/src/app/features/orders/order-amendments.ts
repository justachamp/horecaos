/**
 * ADR 0039's amendment client (wave P10, gap map `1.2h`; financial commands
 * wave 10, rows `1.2c`/`2.1d`) — the first console caller of
 * `POST/GET .../orders/{orderId}/amendments`. Mirrors
 * `OperationsOrderController`'s own records directly, the same rule
 * `order-detail.ts`'s own file doc states for why this is not generated from
 * `platform/api/openapi/v1/horecaos-api.json`.
 *
 * Eleven of ADR 0039's twelve commands are built: the five non-financial ones
 * from wave P10 (`SET_KITCHEN_NOTE`, `SET_CALLBACK_REQUESTED`,
 * `SET_CASH_TENDERED`, `SET_COURIER_NOTE`, `SET_INTERNAL_NOTE`) and, as of
 * wave 10, six of the seven financial ones (`ADD_LINES`,
 * `CHANGE_LINE_QUANTITY` increase-only, `CHANGE_PAYMENT_METHOD`,
 * `CHANGE_DELIVERY_ADDRESS`, `CHANGE_FULFILLMENT_TIME`, `CHANGE_CONTACT`).
 * Only `REMOVE_LINES` stays declared and refused by name at the server
 * (`AmendmentCommandType.built() == false` — no ADR 0017 return/write-off
 * primitive exists yet), so {@link BUILT_AMENDMENT_COMMAND_TYPES} still omits
 * it: `order-amend-menu.ts`'s own trap ("never offer a command the server
 * refuses") applies to it exactly as it used to apply to all seven.
 */
import { MessageKey } from '../../core/i18n/messages.en';

export const BUILT_AMENDMENT_COMMAND_TYPES = [
  'SET_KITCHEN_NOTE',
  'SET_CALLBACK_REQUESTED',
  'SET_CASH_TENDERED',
  'SET_COURIER_NOTE',
  'SET_INTERNAL_NOTE',
  // ---------------------------------------------------- wave 10 financial commands
  'ADD_LINES',
  'CHANGE_LINE_QUANTITY',
  'CHANGE_PAYMENT_METHOD',
  'CHANGE_DELIVERY_ADDRESS',
  'CHANGE_FULFILLMENT_TIME',
  'CHANGE_CONTACT',
] as const;
export type BuiltAmendmentCommandType = (typeof BUILT_AMENDMENT_COMMAND_TYPES)[number];

/** The six wave-10 financial commands, in the amend menu's own display order. */
export const FINANCIAL_AMENDMENT_COMMAND_TYPES = [
  'ADD_LINES',
  'CHANGE_LINE_QUANTITY',
  'CHANGE_PAYMENT_METHOD',
  'CHANGE_DELIVERY_ADDRESS',
  'CHANGE_FULFILLMENT_TIME',
  'CHANGE_CONTACT',
] as const satisfies readonly BuiltAmendmentCommandType[];

/**
 * Every command type ADR 0039 names, built or not — for rendering a history
 * entry's label honestly rather than only the eleven this client can
 * propose. `REMOVE_LINES` can still appear in a history entry once a future
 * wave builds it; this client just never sends one.
 */
export const AMENDMENT_COMMAND_TYPES = [...BUILT_AMENDMENT_COMMAND_TYPES, 'REMOVE_LINES'] as const;

/**
 * `AmendmentResponse` — what `amend`, `confirmAmendment` and the amendment
 * history `GET` (§3.6/§3.10, `:713`) all answer with, one shape for all three
 * (`OperationsOrderController`'s own doc explains the alternative this
 * rejected: a second, GET-only shape carrying the same fields plus note text,
 * which nearly shipped before `OpenApiContractTests` refused the backward
 * -incompatible field removal it would have been on the already-baselined
 * `GET` response).
 *
 * @property commands the command type names an amendment carried
 * @property commandDetails the same commands again, each paired with the free
 *   text a kitchen/courier/internal-note command set — `null` for every other
 *   type. The nested field is named `text`, not `note`: this whole record is
 *   also what the idempotency-tracked `amend`/`confirmAmendment` answer with,
 *   and a "note"-named field there would classify as ADR 0029 personal data
 *   under the platform's name heuristic, for text that is never a fact about
 *   a customer (§3.6).
 * @property warnings things the operator is told and not blocked by —
 *   `CASH_TENDERED_INSUFFICIENT` is the one this wave's dialog renders (§3.5,
 *   §4.4): change due fell short of the total after a later amendment, and the
 *   customer can simply hand over more.
 * @property actions wave 10: `["RESOLVE"]` when this amendment is open and
 *   blocked on either the customer's recorded agreement (an increase awaiting
 *   `POST .../confirmation`) or an ADR 0027 approval still pending, `[]`
 *   otherwise — including for every amendment a pre-wave-10 server answered
 *   with, since the field is simply absent there. Absent, not empty, is what
 *   this client treats as "no action", the same rule `OrderSummaryResponse
 *   .actions` already documents for the order-level actions array.
 */
export interface AmendmentResponse {
  readonly amendmentId: string;
  readonly orderId: string;
  readonly status: string;
  readonly baseRevision: number;
  readonly appliedRevision?: number | null;
  readonly deltaTotalMinor: number;
  readonly requiresApproval: boolean;
  readonly confirmationChannel?: string | null;
  readonly expiresAt: string;
  readonly amendmentVersion: number;
  readonly orderVersion: number;
  readonly commands: readonly string[];
  readonly warnings: readonly string[];
  readonly replayed: boolean;
  readonly commandDetails: readonly AmendmentCommandDetail[];
  readonly createdAt: string;
  readonly createdByActorType: string;
  readonly createdByActorId?: string | null;
  readonly actions?: readonly string[];
}

/** `AmendmentCommandDetail` — one command's type, and its free text where it set one. */
export interface AmendmentCommandDetail {
  readonly type: string;
  readonly text?: string | null;
}

const COMMAND_LABEL_KEYS: Readonly<Record<string, MessageKey>> = {
  SET_KITCHEN_NOTE: 'orders.detail.details.kitchenNote',
  SET_CALLBACK_REQUESTED: 'orders.detail.details.callback',
  SET_CASH_TENDERED: 'orders.detail.details.cashTendered',
  SET_COURIER_NOTE: 'orders.detail.comments.courier',
  SET_INTERNAL_NOTE: 'orders.detail.comments.internal',
  // ---------------------------------------------------- wave 10 financial commands
  ADD_LINES: 'orders.amendment.command.ADD_LINES',
  CHANGE_LINE_QUANTITY: 'orders.amendment.command.CHANGE_LINE_QUANTITY',
  REMOVE_LINES: 'orders.amendment.command.REMOVE_LINES',
  CHANGE_PAYMENT_METHOD: 'orders.amendment.command.CHANGE_PAYMENT_METHOD',
  CHANGE_DELIVERY_ADDRESS: 'orders.amendment.command.CHANGE_DELIVERY_ADDRESS',
  CHANGE_FULFILLMENT_TIME: 'orders.amendment.command.CHANGE_FULFILLMENT_TIME',
  CHANGE_CONTACT: 'orders.amendment.command.CHANGE_CONTACT',
};

/**
 * The operator-facing label for one history entry's command type (orders.md
 * §3.6/§3.10, §4.4). Falls back to the raw code for one of the seven
 * financial commands this client never proposes but might still see recorded
 * — the same forward-compatible rule `order-actions.ts`'s own `actionLabel`
 * states, applied to a history row instead of a button.
 */
export function amendmentCommandLabel(
  type: string,
  translate: (key: MessageKey) => string,
): string {
  const key = COMMAND_LABEL_KEYS[type];
  return key ? translate(key) : type;
}

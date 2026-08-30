import { isTerminalOrderStatus } from './order-status';

/**
 * Severity, derived per render — never stored (§2.7: "a stored flag is wrong
 * five seconds after it is written").
 *
 * **What this implements, and why it is smaller than §2.6–2.7.** The full
 * model ranks six tiers — process failure, a breached delivery/pickup promise,
 * an approval deadline about to pass, a failed payment, a *predicted* breach,
 * an unresolved callback — against `promised_delivery_end` /
 * `estimated_ready_at`, both **not built — ADR 0014**. Nothing on this board
 * can compute a promise breach or predict one, because there is no promise.
 * So this module implements exactly the subset the spec's own fallback policy
 * already defines without that data, in strict precedence order:
 *
 *   1. `BLOCKED` — any `ordering.order_process_states` row is
 *      `MANUAL_ACTION_REQUIRED`. Highest rank: a human must act and nothing
 *      else will (§2.6 tier 0). `OrderSummaryResponse` — the entire wire shape
 *      `GET .../orders` returns today — carries no process state, so every
 *      caller in this application passes `hasBlockedProcess: false` until the
 *      backend adds it. The predicate is implemented and tested regardless,
 *      so the board picks it up the moment the field exists with no change
 *      here.
 *   2. `AWAITING_APPROVAL_DEADLINE` — status is `AWAITING_APPROVAL` and under
 *      two minutes remain before `approval_deadline_at`. This is §2.6 tier
 *      2's own named threshold, the one number in the full comparator that
 *      does not depend on ADR 0014 — `approval_deadline_at` is a real column,
 *      on the wire today.
 *   3. `NO_PROMISE_FALLBACK` — non-terminal and older than
 *      `no_promise_fallback_seconds` (§2.7's `ordering.lateness` policy,
 *      default 2700s / 45 min), which is the policy's own documented stand-in
 *      for a promise that does not exist: "45 min from created_at when no
 *      plan exists". `late_after_seconds` defaults to 0, so once the 45
 *      minutes pass there is no further grace before this fires.
 *   4. `NORMAL` — none of the above.
 *
 * `PAYMENT_FAILED` (tier 3 of the full comparator) and the predictive
 * `AT_RISK` (tier 4) are not modelled as their own severity levels — both need
 * data this board does not have (a payment aggregate read, and a promise to
 * predict against). `PAYMENT_FAILED` still surfaces through tab membership
 * (`order-tabs.ts`'s Внимание rule) and, once it has sat for 45 minutes,
 * through `NO_PROMISE_FALLBACK` like any other stalled order.
 *
 * **Terminal orders are never flagged, whatever their history** (§2.7,
 * verbatim) — checked first, unconditionally, before any other predicate. A
 * `BLOCKED` process flag on an order that was cancelled through a compensating
 * action must not resurrect it as a severity row.
 */
export type OrderSeverityLevel =
  'BLOCKED' | 'AWAITING_APPROVAL_DEADLINE' | 'NO_PROMISE_FALLBACK' | 'NORMAL';

/**
 * Rail colour and row tint move together (§2.7's table pairs them on every
 * row) — this application never sets one without the other, so one field
 * carries both. `'warning'` exists for forward compatibility with the full
 * `AT_RISK` tier; nothing in this module produces it yet.
 */
export type OrderSeverityTone = 'danger' | 'warning' | 'none';

export interface OrderSeverityInput {
  readonly status: string;
  readonly createdAt: Date;
  /** Null when the order never entered `AWAITING_APPROVAL`. */
  readonly approvalDeadlineAt: Date | null;
  /**
   * Any `ordering.order_process_states` row `MANUAL_ACTION_REQUIRED` for this
   * order. Not on `OrderSummaryResponse` yet (see `order-summary.ts`) — pass
   * `false` until it is.
   */
  readonly hasBlockedProcess: boolean;
}

export interface OrderSeverity {
  readonly level: OrderSeverityLevel;
  readonly tone: OrderSeverityTone;
  /** Time left before the approval deadline, in ms. Set only at `AWAITING_APPROVAL_DEADLINE`. */
  readonly remainingMs: number | null;
  /** Time since `createdAt`, in ms. Set only at `NO_PROMISE_FALLBACK`. */
  readonly elapsedMs: number | null;
}

/** §2.6's own number: "AWAITING_APPROVAL with < 2 min to deadline". */
export const APPROVAL_DEADLINE_THRESHOLD_MS = 2 * 60 * 1000;

/** `ordering.lateness.no_promise_fallback_seconds`, default 2700 (§2.7). */
export const NO_PROMISE_FALLBACK_MS = 45 * 60 * 1000;

const NORMAL_SEVERITY: OrderSeverity = {
  level: 'NORMAL',
  tone: 'none',
  remainingMs: null,
  elapsedMs: null,
};

/** The pure function itself. `now` is a parameter, never read from the clock internally. */
export function computeOrderSeverity(input: OrderSeverityInput, now: Date): OrderSeverity {
  if (isTerminalOrderStatus(input.status)) {
    return NORMAL_SEVERITY;
  }

  if (input.hasBlockedProcess) {
    return { level: 'BLOCKED', tone: 'danger', remainingMs: null, elapsedMs: null };
  }

  if (input.status === 'AWAITING_APPROVAL' && input.approvalDeadlineAt !== null) {
    const remainingMs = input.approvalDeadlineAt.getTime() - now.getTime();
    if (remainingMs < APPROVAL_DEADLINE_THRESHOLD_MS) {
      return {
        level: 'AWAITING_APPROVAL_DEADLINE',
        tone: 'danger',
        remainingMs,
        elapsedMs: null,
      };
    }
  }

  const elapsedMs = now.getTime() - input.createdAt.getTime();
  if (elapsedMs > NO_PROMISE_FALLBACK_MS) {
    return { level: 'NO_PROMISE_FALLBACK', tone: 'danger', remainingMs: null, elapsedMs };
  }

  return NORMAL_SEVERITY;
}

const LEVEL_RANK: Readonly<Record<OrderSeverityLevel, number>> = {
  BLOCKED: 0,
  AWAITING_APPROVAL_DEADLINE: 1,
  NO_PROMISE_FALLBACK: 2,
  NORMAL: 3,
};

export interface SeverityRankable {
  readonly severity: OrderSeverity;
  readonly createdAt: Date;
}

/**
 * §2.6's comparator, reduced to what {@link computeOrderSeverity} can
 * produce: severity rank ascending (worst first), then `created_at`
 * ascending — "the person who waited longest", which the spec calls out by
 * name as the opposite of newest-first.
 */
export function compareOrderSeverity(a: SeverityRankable, b: SeverityRankable): number {
  const rankDiff = LEVEL_RANK[a.severity.level] - LEVEL_RANK[b.severity.level];
  if (rankDiff !== 0) {
    return rankDiff;
  }
  return a.createdAt.getTime() - b.createdAt.getTime();
}

/** `created_at` descending — the sort §2.6 keeps for the log tabs (Завершены, Отменены, Все). */
export function compareNewestFirst(
  a: { readonly createdAt: Date },
  b: { readonly createdAt: Date },
): number {
  return b.createdAt.getTime() - a.createdAt.getTime();
}

/** `"01:12"` — the countdown format §2.6's own example row uses ("подтвердить за 01:12"). */
export function formatCountdown(remainingMs: number): string {
  const totalSeconds = Math.max(0, Math.round(remainingMs / 1000));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
}

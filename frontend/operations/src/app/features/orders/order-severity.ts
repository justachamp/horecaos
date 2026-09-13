import { LatenessPolicy, evaluateLateness } from '../../core/lateness-policy';
import { formatDuration } from '../../core/format/datetime';
import { MessageKey } from '../../core/i18n/messages.en';
import { isTerminalOrderStatus } from './order-status';

/**
 * Severity, derived per render — never stored (§2.7: "a stored flag is wrong
 * five seconds after it is written").
 *
 * **What this implements, and how it changed under wave P06.** The full
 * model ranks six tiers — process failure, a breached delivery/pickup promise,
 * an approval deadline about to pass, a failed payment, a *predicted* breach,
 * an unresolved callback — against `promised_at`. Before this wave, nothing on
 * this board could compute a promise breach or predict one, because
 * `promisedAt` was not on the wire; `LATE` and `AT_RISK` were dead code and
 * `NO_PROMISE_FALLBACK` stood in for both, at a flat 45-minute mark this file
 * invented itself. ADR 0102 put `promisedAt` and `processAttention` on
 * `OrderSummaryResponse`, and this wave reads them, resolving the two live
 * tiers this board can now compute against `core/lateness-policy.ts`'s shared
 * evaluator — the same one `kitchen-ticket.ts` calls, so the two boards agree
 * on the AT_RISK/LATE boundary by construction. In strict precedence order
 * (§2.6):
 *
 *   1. `BLOCKED` — `processAttention === 'MANUAL_ACTION_REQUIRED'` (§2.6 tier
 *      0). Highest rank: a human must act and nothing else will.
 *   2. `LATE` — non-terminal, and either the promise is breached past the
 *      resolved policy's `late_after_seconds` grace, or there is no promise
 *      at all and `no_promise_fallback_seconds` has elapsed since
 *      `created_at` (§2.7's own documented stand-in for a missing promise).
 *   3. `AWAITING_APPROVAL_DEADLINE` — status is `AWAITING_APPROVAL` and under
 *      two minutes remain before `approval_deadline_at`. §2.6 tier 2's own
 *      named threshold — a fixed spec number, not part of `ordering.lateness`,
 *      unaffected by this wave.
 *   4. `AT_RISK` — non-terminal and inside the resolved policy's
 *      `at_risk_before_seconds` warning window ahead of the promise.
 *   5. `NORMAL` — none of the above.
 *
 * `PAYMENT_FAILED` (tier 3 of the full comparator) is not modelled as its own
 * severity level — it still surfaces through tab membership
 * (`order-tabs.ts`'s Внимание rule) and, once it has sat long enough with no
 * promise, through `LATE`'s own fallback like any other stalled order.
 *
 * **Terminal orders are never flagged, whatever their history** (§2.7,
 * verbatim) — checked first, unconditionally, before any other predicate. A
 * `BLOCKED` process flag on an order that was cancelled through a compensating
 * action must not resurrect it as a severity row.
 */
export type OrderSeverityLevel =
  'BLOCKED' | 'LATE' | 'AWAITING_APPROVAL_DEADLINE' | 'AT_RISK' | 'NORMAL';

/**
 * Rail colour and row tint move together (§2.7's table pairs them on every
 * row) — this application never sets one without the other, so one field
 * carries both. `BLOCKED` and `LATE` share `'danger'`, exactly as §2.7's
 * Levels table pairs them.
 */
export type OrderSeverityTone = 'danger' | 'warning' | 'none';

export interface OrderSeverityInput {
  readonly status: string;
  readonly createdAt: Date;
  /** Null when the order never entered `AWAITING_APPROVAL`. */
  readonly approvalDeadlineAt: Date | null;
  /** `DELIVERY` | `PICKUP` | `DINE_IN`, or unset — selects the resolved policy's per-mode thresholds. */
  readonly fulfillmentMode: string | null | undefined;
  /** ADR 0036's promise (`OrderSummaryResponse.promisedAt`). Null means no promise was ever made. */
  readonly promisedAt: Date | null;
  /**
   * `processAttention === 'MANUAL_ACTION_REQUIRED'` for this order (ADR
   * 0102). Absent from a summary read outside the board (the detail screen's
   * own header) reads as `false` there, exactly as before this wave.
   */
  readonly hasBlockedProcess: boolean;
}

export interface OrderSeverity {
  readonly level: OrderSeverityLevel;
  readonly tone: OrderSeverityTone;
  /** Time left before the approval deadline, in ms. Set only at `AWAITING_APPROVAL_DEADLINE`. */
  readonly remainingMs: number | null;
  /** Time since `createdAt`, in ms. Set only at `LATE` via the no-promise fallback. */
  readonly elapsedMs: number | null;
}

/** §2.6's own number: "AWAITING_APPROVAL with < 2 min to deadline". Not part of `ordering.lateness`. */
export const APPROVAL_DEADLINE_THRESHOLD_MS = 2 * 60 * 1000;

const NORMAL_SEVERITY: OrderSeverity = {
  level: 'NORMAL',
  tone: 'none',
  remainingMs: null,
  elapsedMs: null,
};

/** The pure function itself. `now` is a parameter, never read from the clock internally. */
export function computeOrderSeverity(
  input: OrderSeverityInput,
  now: Date,
  policy: LatenessPolicy,
): OrderSeverity {
  if (isTerminalOrderStatus(input.status)) {
    return NORMAL_SEVERITY;
  }

  if (input.hasBlockedProcess) {
    return { level: 'BLOCKED', tone: 'danger', remainingMs: null, elapsedMs: null };
  }

  const latenessLevel = evaluateLateness(
    {
      fulfilmentMode: input.fulfillmentMode,
      promisedAt: input.promisedAt,
      createdAt: input.createdAt,
      isTerminal: false, // already excluded above
    },
    policy,
    now,
  );
  if (latenessLevel === 'LATE') {
    // How overdue, measured from whichever baseline applied: the promise
    // itself when one was made, `created_at` when §2.7's no-promise fallback
    // is what fired instead.
    const sinceMs =
      input.promisedAt !== null ? input.promisedAt.getTime() : input.createdAt.getTime();
    return {
      level: 'LATE',
      tone: 'danger',
      remainingMs: null,
      elapsedMs: now.getTime() - sinceMs,
    };
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

  if (latenessLevel === 'AT_RISK') {
    return { level: 'AT_RISK', tone: 'warning', remainingMs: null, elapsedMs: null };
  }

  return NORMAL_SEVERITY;
}

const LEVEL_RANK: Readonly<Record<OrderSeverityLevel, number>> = {
  BLOCKED: 0,
  LATE: 1,
  AWAITING_APPROVAL_DEADLINE: 2,
  AT_RISK: 3,
  NORMAL: 4,
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

/**
 * The severity caption (§2.7: "under the order number, in the severity
 * colour, one line of real text") — shared between `order-queue.ts`'s rows
 * and `order-detail-pane.ts`'s header, which both need the identical
 * sentence for the identical `OrderSeverity`.
 */
export function formatSeverityCaption(
  severity: OrderSeverity,
  translate: (key: MessageKey, values?: Readonly<Record<string, string | number>>) => string,
): string | null {
  switch (severity.level) {
    case 'BLOCKED':
      return translate('orders.severity.blocked');
    case 'LATE':
      return translate('orders.severity.late', {
        duration: formatDuration(Math.floor((severity.elapsedMs ?? 0) / 60_000), {
          hour: translate('orders.duration.hour'),
          minute: translate('orders.duration.minute'),
        }),
      });
    case 'AWAITING_APPROVAL_DEADLINE':
      return translate('orders.severity.approvalDeadline', {
        mmss: formatCountdown(severity.remainingMs ?? 0),
      });
    case 'AT_RISK':
      return translate('orders.severity.atRisk');
    case 'NORMAL':
      return null;
  }
}

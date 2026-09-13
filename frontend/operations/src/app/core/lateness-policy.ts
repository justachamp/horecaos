/**
 * The `ordering.lateness` policy (ADR 0030, orders.md §2.7, gap map rows
 * `1.1g`/`X.39`) and the one severity derivation both the order board
 * (`order-severity.ts`) and the kitchen ticket queue (`kitchen-ticket.ts`)
 * call, instead of each hard-coding its own thresholds — `order-severity.ts`
 * used to carry `APPROVAL_DEADLINE_THRESHOLD_MS`/`NO_PROMISE_FALLBACK_MS` and
 * `kitchen-ticket.ts` a separate `AT_RISK_THRESHOLD_MS`, and the two never
 * agreed because neither read the other, or anything server-side.
 *
 * **Owns exactly the LATE/AT_RISK boundary, and nothing else.** `BLOCKED` is
 * a process-state fact (`ordering.order_process_states`), not a promise fact,
 * and `AWAITING_APPROVAL_DEADLINE` is a fixed, unrelated spec number
 * (orders.md §2.6's own "under two minutes to `approval_deadline_at`") — both
 * stay in `order-severity.ts`, computed as before. This module answers one
 * question: given a promise (or its absence) and the resolved policy, is the
 * order `LATE`, `AT_RISK`, or `NORMAL` right now.
 */

/** `uz.horecaos.platform.tenancy.api.FulfillmentMode`, as it arrives on the wire. */
export type FulfilmentMode = 'DELIVERY' | 'PICKUP' | 'DINE_IN';

/** One fulfilment mode's own numbers, in seconds — the wire shape, unconverted. */
export interface LatenessThresholds {
  readonly atRiskBeforeSeconds: number;
  readonly lateAfterSeconds: number;
  readonly noPromiseFallbackSeconds: number;
}

/** The resolved `ordering.lateness` document: one threshold set per fulfilment mode. */
export interface LatenessPolicy {
  readonly delivery: LatenessThresholds;
  readonly pickup: LatenessThresholds;
  readonly dineIn: LatenessThresholds;
}

/**
 * orders.md §2.7's own numbers, mirroring
 * `OrderLatenessPolicy.platformDefault()` on the server exactly: 300s (5 min)
 * before the promise to warn, no grace past it, 45 minutes from `created_at`
 * when there is no promise at all. Used before the resolved policy has
 * loaded, and whenever the read fails — never a blank board over a policy
 * that could not be fetched.
 */
const PLATFORM_DEFAULT_THRESHOLDS: LatenessThresholds = {
  atRiskBeforeSeconds: 300,
  lateAfterSeconds: 0,
  noPromiseFallbackSeconds: 2700,
};

export const PLATFORM_DEFAULT_LATENESS_POLICY: LatenessPolicy = {
  delivery: PLATFORM_DEFAULT_THRESHOLDS,
  pickup: PLATFORM_DEFAULT_THRESHOLDS,
  dineIn: PLATFORM_DEFAULT_THRESHOLDS,
};

/**
 * The threshold set for one fulfilment mode. An absent or unrecognised mode
 * (a fixture, or a future value this client does not know yet) falls back to
 * `delivery`'s own numbers — safe under the platform default, where every
 * mode's numbers are identical, and no worse than silently picking one of the
 * other two once a tenant does narrow a mode.
 */
export function latenessThresholdsForMode(
  policy: LatenessPolicy,
  fulfilmentMode: string | null | undefined,
): LatenessThresholds {
  switch (fulfilmentMode) {
    case 'PICKUP':
      return policy.pickup;
    case 'DINE_IN':
      return policy.dineIn;
    case 'DELIVERY':
    default:
      return policy.delivery;
  }
}

/** orders.md §2.7's Levels table, minus `BLOCKED` (a process-state fact, not a promise fact). */
export type LatenessLevel = 'LATE' | 'AT_RISK' | 'NORMAL';

export interface LatenessInput {
  readonly fulfilmentMode: string | null | undefined;
  /** The promise to measure against — `promisedAt` on an order, `targetReadyAt` on a kitchen ticket. Null when unset. */
  readonly promisedAt: Date | null;
  /** When the order/ticket was created — the no-promise fallback measures from here. */
  readonly createdAt: Date;
  /** Whichever board is calling decides terminality; this module knows nothing of a status enum. */
  readonly isTerminal: boolean;
}

/**
 * orders.md §2.7's Levels table, minus `BLOCKED`. Mirrors
 * `OrderLatenessPolicy.evaluate` on the server field for field — the same
 * shifted-clock trick that method uses to reuse `OrderPromise.lateAt` twice
 * (`now + atRiskBefore` for the warning edge, `now − lateAfter` for the
 * breach edge) reappears here as plain millisecond comparisons, so "both
 * boards compute the same AT_RISK boundary from one policy" is true by
 * construction: one function, called by both.
 *
 * **Terminal orders are never flagged, whatever their history** (orders.md
 * §2.7, verbatim) — checked first, unconditionally, exactly as
 * `computeOrderSeverity` already did before this wave.
 */
export function evaluateLateness(
  input: LatenessInput,
  policy: LatenessPolicy,
  now: Date,
): LatenessLevel {
  if (input.isTerminal) {
    return 'NORMAL';
  }

  const thresholds = latenessThresholdsForMode(policy, input.fulfilmentMode);
  const nowMs = now.getTime();

  if (input.promisedAt !== null) {
    const promisedMs = input.promisedAt.getTime();
    if (nowMs > promisedMs + thresholds.lateAfterSeconds * 1000) {
      return 'LATE';
    }
    if (nowMs > promisedMs - thresholds.atRiskBeforeSeconds * 1000) {
      return 'AT_RISK';
    }
    return 'NORMAL';
  }

  // No promise at all: §2.7's own documented stand-in, measured from creation.
  const fallbackDeadlineMs = input.createdAt.getTime() + thresholds.noPromiseFallbackSeconds * 1000;
  return nowMs > fallbackDeadlineMs ? 'LATE' : 'NORMAL';
}

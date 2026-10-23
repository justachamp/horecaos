import { toApiError, type ApiError } from '../api/problem-details';
import type {
  CourierDelivery,
  GateCheck,
  GateCheckKind,
  GateStepResult,
} from '../courier/courier-work';

/**
 * Every reason a rule of the courier policy can give for refusing a step (`GateRefusal`,
 * couriers.md section 16), each the `reason` of a `422`. The set is closed on the platform's side
 * and pinned there to the exact set (`DeliveryGateTests`); here it is the list the screens have a
 * sentence for. It is a copy, not generated -- the `reason` is a problem-document extension and the
 * OpenAPI baseline does not carry it -- so `gate-refusal-drift.spec.ts` holds it to the enum's source.
 */
export const GATE_REFUSAL_REASONS = [
  'GPS_POSITION_REQUIRED',
  'GPS_ACCURACY_INSUFFICIENT',
  'GPS_REFERENCE_UNAVAILABLE',
  'TOO_FAR_FROM_PICKUP',
  'TOO_FAR_FROM_DROPOFF',
  'KITCHEN_NOT_READY',
  'PAYMENT_CONFIRMATION_REQUIRED',
  'PAYMENT_AMOUNT_MISMATCH',
  'LOCATION_NOT_YET_REVEALED',
  'COURIER_NOT_ELIGIBLE',
  'STEP_NOT_ALLOWED',
  'PAYMENT_NOT_CONFIRMABLE',
] as const;

export type GateRefusalReason = (typeof GATE_REFUSAL_REASONS)[number];

const CHECK_KINDS: readonly GateCheckKind[] = ['GEO', 'PHOTO', 'PAYMENT'];

/**
 * What became of a courier's step -- accepting, arriving, picking up, handing over, confirming
 * cash -- as the screen has to tell it. The platform answers a step in one of four ways and a
 * request that never arrived is a fifth; the screen branches on these and never on a message.
 *
 * - `done`: the step worked. In SOFT mode the answer carries `checks` and `unconfirmed` names the
 *   ones the platform could not confirm, which the courier is told rather than refused for.
 * - `gate-failed`: HARD mode refused a step with `409 GATE_FAILED`; `failed` lists the check kinds
 *   the platform named (cross-wave contract with the delivery-maps wave, which builds the gate).
 * - `refused`: a rule refused it with `422` and a `reason` from {@link GATE_REFUSAL_REASONS};
 *   `reason` is null for one this build has no sentence for.
 * - `stale`: someone else moved the delivery first (`STALE_VERSION`), or the platform saw the step's
 *   key with another body (`IDEMPOTENCY_KEY_REUSED`) and the first request may have been taken; the
 *   screen reloads and says it changed.
 * - `failed`: anything else, including no connection.
 */
export type StepOutcome<T = CourierDelivery> =
  | {
      readonly kind: 'done';
      readonly delivery: T;
      readonly checks: readonly GateCheck[];
      readonly unconfirmed: readonly GateCheckKind[];
    }
  | { readonly kind: 'gate-failed'; readonly failed: readonly GateCheckKind[] }
  | { readonly kind: 'refused'; readonly reason: GateRefusalReason | null }
  | { readonly kind: 'stale' }
  | { readonly kind: 'failed'; readonly error: ApiError };

/** A step that worked, with whatever checks the platform reported (none from an endpoint that reports none). */
export function describeSuccess<T extends CourierDelivery>(
  result: T | GateStepResult,
): Extract<StepOutcome<T>, { kind: 'done' }> {
  const reported = (result as GateStepResult).checks;
  const checks = Array.isArray(reported) ? (reported as readonly GateCheck[]) : [];
  return {
    kind: 'done',
    delivery: result as T,
    checks,
    unconfirmed: checks.filter((check) => check.outcome !== 'PASSED').map((check) => check.kind),
  };
}

/**
 * The check kinds named in a `GATE_FAILED` detail, once each, in the order the platform gave them.
 * Whole words only (`GEOGRAPHY` is not `GEO`), and case-insensitive, because the contract says the
 * detail lists the failed kinds and not how it separates them.
 */
export function failedKinds(detail: string): GateCheckKind[] {
  const found: GateCheckKind[] = [];
  for (const match of detail.toUpperCase().matchAll(/[A-Z_]+/g)) {
    const word = match[0] as GateCheckKind;
    if (CHECK_KINDS.includes(word) && !found.includes(word)) {
      found.push(word);
    }
  }
  return found;
}

function knownReason(reason: string | null): GateRefusalReason | null {
  return GATE_REFUSAL_REASONS.find((known) => known === reason) ?? null;
}

/** Sorts a failed step into the branches above. */
export function classifyStepFailure(failure: unknown): Exclude<StepOutcome, { kind: 'done' }> {
  const error = toApiError(failure);
  if (error.status === 409 && error.code === 'GATE_FAILED') {
    return { kind: 'gate-failed', failed: failedKinds(error.detail) };
  }
  // A key the platform saw with another body is also a delivery that may have moved: the first
  // request could have been taken, so the screen reloads and says it changed rather than
  // reporting an error the courier can do nothing about.
  if (error.code === 'STALE_VERSION' || error.code === 'IDEMPOTENCY_KEY_REUSED') {
    return { kind: 'stale' };
  }
  if (error.status === 422) {
    return { kind: 'refused', reason: knownReason(error.reason) };
  }
  return { kind: 'failed', error };
}

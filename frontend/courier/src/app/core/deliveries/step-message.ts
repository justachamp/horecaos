import { errorMessage } from '../i18n/error-message';
import type { I18n } from '../i18n/i18n';
import type { MessageKey } from '../i18n/message-keys';
import type { OfferOutcome } from '../offers/offers.store';
import type { GateCheckKind } from '../courier/courier-work';
import { type GateRefusalReason, type StepOutcome } from './step-outcome';

/** A sentence for the courier and how loudly to say it. */
export interface StepMessage {
  readonly tone: 'info' | 'warning' | 'error';
  readonly text: string;
}

/**
 * Typed maps, not built keys: a reason added to `GATE_REFUSAL_REASONS` without its sentence is a
 * compile error here. A member added to the platform's `GateRefusal` enum is not something the
 * compiler can see; `gate-refusal-drift.spec.ts` reads the enum's source and fails the build until
 * the list (and so this map and the catalogues) follows.
 */
const REFUSAL_KEYS: Readonly<Record<GateRefusalReason, MessageKey>> = {
  GPS_POSITION_REQUIRED: 'work.refusal.GPS_POSITION_REQUIRED',
  GPS_ACCURACY_INSUFFICIENT: 'work.refusal.GPS_ACCURACY_INSUFFICIENT',
  GPS_REFERENCE_UNAVAILABLE: 'work.refusal.GPS_REFERENCE_UNAVAILABLE',
  TOO_FAR_FROM_PICKUP: 'work.refusal.TOO_FAR_FROM_PICKUP',
  TOO_FAR_FROM_DROPOFF: 'work.refusal.TOO_FAR_FROM_DROPOFF',
  KITCHEN_NOT_READY: 'work.refusal.KITCHEN_NOT_READY',
  PAYMENT_CONFIRMATION_REQUIRED: 'work.refusal.PAYMENT_CONFIRMATION_REQUIRED',
  PAYMENT_AMOUNT_MISMATCH: 'work.refusal.PAYMENT_AMOUNT_MISMATCH',
  LOCATION_NOT_YET_REVEALED: 'work.refusal.LOCATION_NOT_YET_REVEALED',
  COURIER_NOT_ELIGIBLE: 'work.refusal.COURIER_NOT_ELIGIBLE',
  STEP_NOT_ALLOWED: 'work.refusal.STEP_NOT_ALLOWED',
  PAYMENT_NOT_CONFIRMABLE: 'work.refusal.PAYMENT_NOT_CONFIRMABLE',
};

const KIND_KEYS: Readonly<Record<GateCheckKind, MessageKey>> = {
  GEO: 'work.gate.kind.GEO',
  PHOTO: 'work.gate.kind.PHOTO',
  PAYMENT: 'work.gate.kind.PAYMENT',
};

function kindNames(i18n: I18n, kinds: readonly GateCheckKind[]): string {
  return kinds.map((kind) => i18n.t(KIND_KEYS[kind])).join(', ');
}

/**
 * What a courier is told about the outcome of a step or an offer decision.
 *
 * The screens branch on `kind` and never on a sentence; this is the one place the branches become
 * words. A refusal by a rule is the sentence for its reason; a hard gate that refused names the
 * checks (cross-wave contract: `409 GATE_FAILED`); a soft gate that let the step through says what
 * the platform could not confirm, as a warning and not as a failure, because the step happened.
 * Null means there is nothing to add.
 */
export function stepMessage(
  i18n: I18n,
  outcome: StepOutcome | OfferOutcome | null,
): StepMessage | null {
  if (outcome === null) {
    return null;
  }
  switch (outcome.kind) {
    case 'refused':
      return {
        tone: 'error',
        text: i18n.t(
          outcome.reason === null ? 'work.refusal.unknown' : REFUSAL_KEYS[outcome.reason],
        ),
      };
    case 'gate-failed':
      return {
        tone: 'error',
        text:
          outcome.failed.length === 0
            ? i18n.t('work.gate.failed.unnamed')
            : i18n.t('work.gate.failed', { kinds: kindNames(i18n, outcome.failed) }),
      };
    case 'done':
      if (outcome.unconfirmed.length > 0) {
        return {
          tone: 'warning',
          text: i18n.t('work.gate.unconfirmed', { kinds: kindNames(i18n, outcome.unconfirmed) }),
        };
      }
      return outcome.checks.length > 0 ? { tone: 'info', text: i18n.t('work.gate.passed') } : null;
    case 'stale':
      return { tone: 'warning', text: i18n.t('work.stale') };
    case 'gone':
      return { tone: 'warning', text: i18n.t('offers.gone') };
    case 'declined':
      return { tone: 'info', text: i18n.t('offers.declined') };
    case 'failed':
      return { tone: 'error', text: errorMessage(i18n, outcome.error, 'work.error') ?? '' };
    default:
      return null;
  }
}

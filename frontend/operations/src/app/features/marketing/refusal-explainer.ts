import { MessageKey } from '../../core/i18n/messages.en';

/**
 * Why a guest did not get a message, and what happens next (ADR 0044, ADR 0112).
 *
 * Every refusal is a row with a reason, never a silent filter: a marketer asking "why did this
 * guest not get step 2" gets an answer from the decision log, in words. This module is the words.
 * The engine writes a {@link RefusalReason} name; what the console owes the reader is what it
 * means, whether it is the end of the guest's run or only a hold, and what, if anything, a person
 * can do about it.
 *
 * **The effect is read from the engine, not guessed.** `ScenarioRunner#handleRefusal` stops the
 * run for consent, suppression and an inactive account, and holds the step (asked again later,
 * never dropped) for a cap, a missing endpoint, a conflict and a lost priority race. A
 * `SCENARIO_STOPPED` row is the one that is usually the end and occasionally a one-hour retry
 * (the delivery path accepted nothing), which is why its effect is {@code VARIES} and the
 * sentence the engine recorded beside it is the thing to read.
 *
 * The contact policy is the part that is the tenant's own: a cap and quiet hours, per channel,
 * purpose and period, tighter than the platform's and never looser. A `FREQUENCY_CAP_REACHED`
 * row's sentence names which of the two stopped the send.
 */
export type RefusalEffect = 'ENDS' | 'HOLDS' | 'VARIES' | 'BROADCAST';

export interface RefusalExplanation {
  readonly reason: string;
  readonly effect: RefusalEffect;
  /** Whether the brand's own contact policy can be what produced it. */
  readonly governedByContactPolicy: boolean;
  readonly labelKey: MessageKey;
  readonly meaningKey: MessageKey;
  readonly remedyKey: MessageKey;
}

const EXPLANATIONS: readonly RefusalExplanation[] = [
  {
    reason: 'CONSENT_WITHHELD',
    effect: 'ENDS',
    governedByContactPolicy: false,
    labelKey: 'marketing.refusal.CONSENT_WITHHELD',
    meaningKey: 'marketing.refusal.meaning.CONSENT_WITHHELD',
    remedyKey: 'marketing.refusal.remedy.CONSENT_WITHHELD',
  },
  {
    reason: 'SUPPRESSED',
    effect: 'ENDS',
    governedByContactPolicy: false,
    labelKey: 'marketing.refusal.SUPPRESSED',
    meaningKey: 'marketing.refusal.meaning.SUPPRESSED',
    remedyKey: 'marketing.refusal.remedy.SUPPRESSED',
  },
  {
    reason: 'ACCOUNT_NOT_ACTIVE',
    effect: 'ENDS',
    governedByContactPolicy: false,
    labelKey: 'marketing.refusal.ACCOUNT_NOT_ACTIVE',
    meaningKey: 'marketing.refusal.meaning.ACCOUNT_NOT_ACTIVE',
    remedyKey: 'marketing.refusal.remedy.ACCOUNT_NOT_ACTIVE',
  },
  {
    reason: 'FREQUENCY_CAP_REACHED',
    effect: 'HOLDS',
    governedByContactPolicy: true,
    labelKey: 'marketing.refusal.FREQUENCY_CAP_REACHED',
    meaningKey: 'marketing.refusal.meaning.FREQUENCY_CAP_REACHED',
    remedyKey: 'marketing.refusal.remedy.FREQUENCY_CAP_REACHED',
  },
  {
    reason: 'NO_VERIFIED_ENDPOINT',
    effect: 'HOLDS',
    governedByContactPolicy: false,
    labelKey: 'marketing.refusal.NO_VERIFIED_ENDPOINT',
    meaningKey: 'marketing.refusal.meaning.NO_VERIFIED_ENDPOINT',
    remedyKey: 'marketing.refusal.remedy.NO_VERIFIED_ENDPOINT',
  },
  {
    reason: 'SCENARIO_CONFLICT',
    effect: 'HOLDS',
    governedByContactPolicy: false,
    labelKey: 'marketing.refusal.SCENARIO_CONFLICT',
    meaningKey: 'marketing.refusal.meaning.SCENARIO_CONFLICT',
    remedyKey: 'marketing.refusal.remedy.SCENARIO_CONFLICT',
  },
  {
    reason: 'SCENARIO_PRIORITY_LOST',
    effect: 'HOLDS',
    governedByContactPolicy: false,
    labelKey: 'marketing.refusal.SCENARIO_PRIORITY_LOST',
    meaningKey: 'marketing.refusal.meaning.SCENARIO_PRIORITY_LOST',
    remedyKey: 'marketing.refusal.remedy.SCENARIO_PRIORITY_LOST',
  },
  {
    reason: 'SCENARIO_STOPPED',
    effect: 'VARIES',
    governedByContactPolicy: false,
    labelKey: 'marketing.refusal.SCENARIO_STOPPED',
    meaningKey: 'marketing.refusal.meaning.SCENARIO_STOPPED',
    remedyKey: 'marketing.refusal.remedy.SCENARIO_STOPPED',
  },
  {
    reason: 'CAMPAIGN_HALTED',
    effect: 'BROADCAST',
    governedByContactPolicy: false,
    labelKey: 'marketing.refusal.CAMPAIGN_HALTED',
    meaningKey: 'marketing.refusal.meaning.CAMPAIGN_HALTED',
    remedyKey: 'marketing.refusal.remedy.CAMPAIGN_HALTED',
  },
];

/** Every reason, in the order the engine meets them, for a reference list. */
export const REFUSAL_EXPLANATIONS: readonly RefusalExplanation[] = EXPLANATIONS;

/** The effect's sentence key; one per effect, so a reader learns the four words once. */
export const REFUSAL_EFFECT_KEYS: Readonly<Record<RefusalEffect, MessageKey>> = {
  ENDS: 'marketing.refusal.effect.ENDS',
  HOLDS: 'marketing.refusal.effect.HOLDS',
  VARIES: 'marketing.refusal.effect.VARIES',
  BROADCAST: 'marketing.refusal.effect.BROADCAST',
};

/** A reason this build knows; null for one a newer server added, which the caller shows as written. */
export function explainRefusal(reason: string | null): RefusalExplanation | null {
  return EXPLANATIONS.find((explanation) => explanation.reason === reason) ?? null;
}

/**
 * The reason's name in words, or the reason itself when it is not one this build knows.
 * `CHANNEL_NOT_WIRED` is an automation firing's own reason: no delivery path existed for the
 * rule's channel at the moment it fired.
 */
export function refusalLabelKey(reason: string): MessageKey | null {
  if (reason === 'CHANNEL_NOT_WIRED') {
    return 'marketing.refusal.CHANNEL_NOT_WIRED';
  }
  return explainRefusal(reason)?.labelKey ?? null;
}

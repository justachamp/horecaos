import { MessageKey } from '../../../core/i18n/messages.en';
import { WiringSentence, viewOf, wiringSentence } from '../channel-wiring';
import { ChannelView } from '../marketing-api';
import { OfferView } from '../offers/offers-api';
import {
  ContinuationCondition,
  ScenarioChannel,
  ScenarioStepRequest,
  ScenarioStepView,
  StopCondition,
} from './scenarios-api';

/**
 * More steps than this is a program, not a scenario. `ScenarioService.MAX_STEPS`: the server
 * refuses an eleventh, and the editor stops offering one rather than letting an author build
 * what cannot be saved.
 */
export const SCENARIO_MAX_STEPS = 10;

/**
 * A guest is never held on one wait for longer than a quarter (`ScenarioService.MAX_WAIT`). This
 * is ADR 0112's default for the open input about how long a wait may hold a guest, taken on the
 * default the record proposes; it is longer than every contact-policy window (at most 30 days), so
 * a wait can never starve a step of the slot its cap would admit.
 */
export const SCENARIO_MAX_WAIT_DAYS = 90;

/** The six places a step can reach a guest, in the order the editor lists them. */
export const SCENARIO_CHANNELS: readonly ScenarioChannel[] = [
  'MESSAGING_APP',
  'SMS',
  'EMAIL',
  'PUSH',
  'IN_APP',
  'CALL_CENTRE',
];

/** The four that send a message through ADR 0020. In-app and call-centre steps send none. */
const MESSAGING: ReadonlySet<ScenarioChannel> = new Set(['SMS', 'EMAIL', 'PUSH', 'MESSAGING_APP']);

export function isMessagingChannel(channel: ScenarioChannel): boolean {
  return MESSAGING.has(channel);
}

/** Each channel's name in words: the `marketing.channel.*` keys, every one a literal the dead-key check can see. */
export const SCENARIO_CHANNEL_KEYS: Readonly<Record<ScenarioChannel, MessageKey>> = {
  SMS: 'marketing.channel.SMS',
  EMAIL: 'marketing.channel.EMAIL',
  PUSH: 'marketing.channel.PUSH',
  MESSAGING_APP: 'marketing.channel.MESSAGING_APP',
  IN_APP: 'marketing.channel.IN_APP',
  CALL_CENTRE: 'marketing.channel.CALL_CENTRE',
};

/** The two closed sets a step's conditions are drawn from, in words. */
export const SCENARIO_CONDITION_KEYS: Readonly<
  Record<ContinuationCondition | StopCondition, MessageKey>
> = {
  ALWAYS: 'marketing.scenario.condition.ALWAYS',
  NO_ORDER_SINCE_ENTRY: 'marketing.scenario.condition.NO_ORDER_SINCE_ENTRY',
  NONE: 'marketing.scenario.condition.NONE',
  ORDER_PLACED_SINCE_ENTRY: 'marketing.scenario.condition.ORDER_PLACED_SINCE_ENTRY',
};

export type WaitUnit = 'MINUTES' | 'HOURS' | 'DAYS';

export const WAIT_UNITS: readonly WaitUnit[] = ['MINUTES', 'HOURS', 'DAYS'];

const UNIT_SECONDS: Readonly<Record<WaitUnit, number>> = {
  MINUTES: 60,
  HOURS: 3_600,
  DAYS: 86_400,
};

/** One step as the author is writing it: the form's own shape, converted to the wire's on save. */
export interface StepDraft {
  /** Identity for the list while it is being reordered; never sent. */
  readonly uid: number;
  readonly channel: ScenarioChannel;
  readonly offerId: string | null;
  /** Empty means "the offer's own template". */
  readonly templateKey: string;
  readonly waitValue: number;
  readonly waitUnit: WaitUnit;
  readonly continuation: ContinuationCondition;
  readonly stop: StopCondition;
}

export function newStepDraft(uid: number, channel: ScenarioChannel = 'MESSAGING_APP'): StepDraft {
  return {
    uid,
    channel,
    offerId: null,
    templateKey: '',
    waitValue: 0,
    waitUnit: 'DAYS',
    continuation: 'ALWAYS',
    stop: 'NONE',
  };
}

/** The wait in seconds, or NaN for a value that is not a non-negative number. */
export function waitSeconds(step: StepDraft): number {
  if (!Number.isFinite(step.waitValue) || step.waitValue < 0) {
    return Number.NaN;
  }
  return Math.round(step.waitValue * UNIT_SECONDS[step.waitUnit]);
}

/** The largest unit that says a wait without a fraction, so 172800 s reads "2 days", not "2880 minutes". */
export function stepFromView(view: ScenarioStepView, uid: number): StepDraft {
  const seconds = view.waitAfterPreviousSeconds;
  let unit: WaitUnit = 'MINUTES';
  if (seconds > 0 && seconds % UNIT_SECONDS.DAYS === 0) {
    unit = 'DAYS';
  } else if (seconds > 0 && seconds % UNIT_SECONDS.HOURS === 0) {
    unit = 'HOURS';
  }
  return {
    uid,
    channel: view.channel,
    offerId: view.offerId,
    templateKey: view.templateKey,
    waitValue: Math.round((seconds / UNIT_SECONDS[unit]) * 100) / 100,
    waitUnit: unit,
    continuation: view.continuationCondition,
    stop: view.stopCondition,
  };
}

/**
 * The step as the server takes it. A step carries a channel, an offer, a template, a wait and two
 * conditions from closed sets, and has no field for an amount, a percentage or a number of points
 * (ADR 0112: marketing never authors a benefit); this function cannot send one because the draft
 * cannot hold one.
 */
export function toStepRequest(step: StepDraft): ScenarioStepRequest {
  return {
    channel: step.channel,
    offerId: step.offerId,
    templateKey: step.templateKey.trim() === '' ? null : step.templateKey.trim(),
    waitAfterPreviousSeconds: waitSeconds(step),
    continuationCondition: step.continuation,
    stopCondition: step.stop,
  };
}

/** The template a step will actually use: its own, else its offer's. Empty when neither names one. */
export function effectiveTemplateKey(step: StepDraft, offers: readonly OfferView[]): string {
  const own = step.templateKey.trim();
  if (own !== '') {
    return own;
  }
  return offers.find((offer) => offer.offerId === step.offerId)?.templateKey ?? '';
}

/** The first step that sends a message: its channel prices and consents the whole scenario. */
export function primaryStep(
  steps: readonly StepDraft[],
  offers: readonly OfferView[],
): { readonly step: StepDraft; readonly templateKey: string } | null {
  const step = steps.find((candidate) => isMessagingChannel(candidate.channel));
  return step ? { step, templateKey: effectiveTemplateKey(step, offers) } : null;
}

/**
 * Whether an offer is in force: published, and not over. A window that has not started yet counts
 * as in force, because `ScenarioService` refuses only an offer that is not published or whose
 * window has ended; a step may be written against an offer that opens next week.
 */
export function offerInForce(offer: OfferView, now: number): boolean {
  return (
    offer.status === 'PUBLISHED' &&
    (offer.validUntil === null || Date.parse(offer.validUntil) > now)
  );
}

/** Whether an offer may be offered on a channel: in force, and allowed in that channel. */
export function offerEligible(offer: OfferView, channel: ScenarioChannel, now: number): boolean {
  return offerInForce(offer, now) && offer.allowedChannels.includes(channel);
}

/** Everything the server would refuse a step for, which the editor can see before it asks. */
export type ScenarioProblemCode =
  | 'NO_STEPS'
  | 'TOO_MANY_STEPS'
  | 'NO_MESSAGING_STEP'
  | 'CALL_CENTRE_NOT_WIRED'
  | 'IN_APP_NEEDS_OFFER'
  | 'NEEDS_TEMPLATE'
  | 'WAIT_INVALID'
  | 'WAIT_TOO_LONG'
  | 'OFFER_NOT_IN_FORCE'
  | 'OFFER_CHANNEL';

export interface ScenarioProblem {
  readonly code: ScenarioProblemCode;
  /** The step's number, 1-based; absent for a problem of the scenario as a whole. */
  readonly step?: number;
}

export const SCENARIO_PROBLEM_KEYS: Readonly<Record<ScenarioProblemCode, MessageKey>> = {
  NO_STEPS: 'marketing.scenario.problem.NO_STEPS',
  TOO_MANY_STEPS: 'marketing.scenario.problem.TOO_MANY_STEPS',
  NO_MESSAGING_STEP: 'marketing.scenario.problem.NO_MESSAGING_STEP',
  CALL_CENTRE_NOT_WIRED: 'marketing.scenario.problem.CALL_CENTRE_NOT_WIRED',
  IN_APP_NEEDS_OFFER: 'marketing.scenario.problem.IN_APP_NEEDS_OFFER',
  NEEDS_TEMPLATE: 'marketing.scenario.problem.NEEDS_TEMPLATE',
  WAIT_INVALID: 'marketing.scenario.problem.WAIT_INVALID',
  WAIT_TOO_LONG: 'marketing.scenario.problem.WAIT_TOO_LONG',
  OFFER_NOT_IN_FORCE: 'marketing.scenario.problem.OFFER_NOT_IN_FORCE',
  OFFER_CHANNEL: 'marketing.scenario.problem.OFFER_CHANNEL',
};

/**
 * What `ScenarioService.resolve` would refuse, in the order it would meet it, so an author sees it
 * beside the field rather than in a 400 after pressing save. The server stays the authority: this
 * is the same rule read earlier, never a replacement for it.
 */
export function scenarioProblems(
  steps: readonly StepDraft[],
  offers: readonly OfferView[],
  now: number,
): readonly ScenarioProblem[] {
  const problems: ScenarioProblem[] = [];
  if (steps.length === 0) {
    return [{ code: 'NO_STEPS' }];
  }
  if (steps.length > SCENARIO_MAX_STEPS) {
    problems.push({ code: 'TOO_MANY_STEPS' });
  }
  steps.forEach((step, index) => {
    const number = index + 1;
    if (step.channel === 'CALL_CENTRE') {
      problems.push({ code: 'CALL_CENTRE_NOT_WIRED', step: number });
    }
    const seconds = waitSeconds(step);
    if (Number.isNaN(seconds)) {
      problems.push({ code: 'WAIT_INVALID', step: number });
    } else if (seconds > SCENARIO_MAX_WAIT_DAYS * UNIT_SECONDS.DAYS) {
      problems.push({ code: 'WAIT_TOO_LONG', step: number });
    }
    const offer =
      step.offerId === null ? undefined : offers.find((o) => o.offerId === step.offerId);
    if (step.offerId !== null) {
      // An offer that is in force but not allowed in this channel is a different sentence from
      // one that is retired or over: the author fixes them in different places.
      if (offer === undefined || !offerInForce(offer, now)) {
        problems.push({ code: 'OFFER_NOT_IN_FORCE', step: number });
      } else if (!offer.allowedChannels.includes(step.channel)) {
        problems.push({ code: 'OFFER_CHANNEL', step: number });
      }
    } else if (step.channel === 'IN_APP') {
      problems.push({ code: 'IN_APP_NEEDS_OFFER', step: number });
    }
    if (effectiveTemplateKey(step, offers) === '' && step.channel !== 'CALL_CENTRE') {
      problems.push({ code: 'NEEDS_TEMPLATE', step: number });
    }
  });
  if (!steps.some((step) => isMessagingChannel(step.channel))) {
    problems.push({ code: 'NO_MESSAGING_STEP' });
  }
  return problems;
}

/** A messaging step whose channel cannot deliver for this brand today, and why, in words. */
export interface UnwiredStep {
  readonly step: number;
  readonly channel: ScenarioChannel;
  readonly sentence: WiringSentence;
}

/**
 * Steps that will be refused at launch. Authoring one is allowed (a draft is a plan, and the
 * channel may be connected by the time it is approved) but saying nothing until the second
 * signature has been spent is how a scenario ends up approved and unlaunchable.
 */
export function unwiredSteps(
  steps: readonly StepDraft[],
  channels: readonly ChannelView[],
): readonly UnwiredStep[] {
  const out: UnwiredStep[] = [];
  steps.forEach((step, index) => {
    if (!isMessagingChannel(step.channel)) {
      return;
    }
    const view = viewOf(channels, step.channel);
    if (view !== undefined && !view.isWired) {
      out.push({
        step: index + 1,
        channel: step.channel,
        sentence: wiringSentence(step.channel, view.notWiredReason),
      });
    }
  });
  return out;
}

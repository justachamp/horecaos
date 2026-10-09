import { describe, expect, it } from 'vitest';

import { ChannelView } from '../marketing-api';
import { OfferView } from '../offers/offers-api';
import {
  SCENARIO_MAX_STEPS,
  SCENARIO_MAX_WAIT_DAYS,
  StepDraft,
  effectiveTemplateKey,
  newStepDraft,
  offerEligible,
  primaryStep,
  scenarioProblems,
  stepFromView,
  toStepRequest,
  unwiredSteps,
  waitSeconds,
} from './scenario-draft';

const NOW = Date.parse('2026-10-07T10:00:00Z');

function offer(overrides: Partial<OfferView> = {}): OfferView {
  return {
    offerId: 'offer-1',
    lineageId: 'lineage-1',
    versionNumber: 1,
    status: 'PUBLISHED',
    displayName: 'Free delivery week',
    pricingPromotionId: 'promo-1',
    loyaltyAccrualRuleId: null,
    validFrom: '2026-10-01T00:00:00Z',
    validUntil: null,
    audienceId: null,
    allowedChannels: ['MESSAGING_APP', 'SMS', 'IN_APP'],
    templateKey: 'OFFER_FREE_DELIVERY',
    templateVersion: null,
    bannerImageReference: null,
    createdBy: 'actor-1',
    publishedBy: 'actor-2',
    publishedAt: '2026-10-01T00:00:00Z',
    version: 2,
    createdAt: '2026-09-30T00:00:00Z',
    updatedAt: '2026-10-01T00:00:00Z',
    ...overrides,
  };
}

function step(overrides: Partial<StepDraft> = {}): StepDraft {
  return { ...newStepDraft(1, 'MESSAGING_APP'), templateKey: 'WELCOME_BACK', ...overrides };
}

const CHANNELS: readonly ChannelView[] = [
  {
    channel: 'SMS',
    carriesMarginalCost: true,
    isWired: false,
    notWiredReason: 'SMS_PURPOSE_NOT_PERMITTED',
  },
  {
    channel: 'EMAIL',
    carriesMarginalCost: true,
    isWired: false,
    notWiredReason: 'NO_DELIVERY_ADAPTER',
  },
  {
    channel: 'PUSH',
    carriesMarginalCost: false,
    isWired: false,
    notWiredReason: 'NO_DELIVERY_ADAPTER',
  },
  { channel: 'MESSAGING_APP', carriesMarginalCost: false, isWired: true, notWiredReason: null },
];

describe('waits', () => {
  it('says a wait in seconds, whichever unit the author chose', () => {
    expect(waitSeconds(step({ waitValue: 30, waitUnit: 'MINUTES' }))).toBe(1_800);
    expect(waitSeconds(step({ waitValue: 2, waitUnit: 'HOURS' }))).toBe(7_200);
    expect(waitSeconds(step({ waitValue: 3, waitUnit: 'DAYS' }))).toBe(259_200);
  });

  it('is not a number for a value nobody can wait: negative, empty or NaN', () => {
    expect(waitSeconds(step({ waitValue: -1 }))).toBeNaN();
    expect(waitSeconds(step({ waitValue: Number.NaN }))).toBeNaN();
  });

  it('reads a stored wait back in the largest unit that has no fraction', () => {
    const view = {
      sequence: 2,
      channel: 'SMS',
      offerId: null,
      templateKey: 'T',
      continuationCondition: 'ALWAYS',
      stopCondition: 'NONE',
    } as const;
    expect(stepFromView({ ...view, waitAfterPreviousSeconds: 172_800 }, 1)).toMatchObject({
      waitValue: 2,
      waitUnit: 'DAYS',
    });
    expect(stepFromView({ ...view, waitAfterPreviousSeconds: 7_200 }, 1)).toMatchObject({
      waitValue: 2,
      waitUnit: 'HOURS',
    });
    expect(stepFromView({ ...view, waitAfterPreviousSeconds: 5_400 }, 1)).toMatchObject({
      waitValue: 90,
      waitUnit: 'MINUTES',
    });
    expect(stepFromView({ ...view, waitAfterPreviousSeconds: 0 }, 1)).toMatchObject({
      waitValue: 0,
      waitUnit: 'MINUTES',
    });
  });

  it('round-trips through the wire shape', () => {
    const original = step({
      waitValue: 3,
      waitUnit: 'DAYS',
      channel: 'SMS',
      offerId: 'offer-1',
      stop: 'ORDER_PLACED_SINCE_ENTRY',
      continuation: 'NO_ORDER_SINCE_ENTRY',
    });
    const request = toStepRequest(original);
    const back = stepFromView(
      {
        sequence: 1,
        channel: request.channel,
        offerId: request.offerId,
        templateKey: request.templateKey ?? '',
        waitAfterPreviousSeconds: request.waitAfterPreviousSeconds,
        continuationCondition: request.continuationCondition,
        stopCondition: request.stopCondition,
      },
      1,
    );
    expect(back).toMatchObject({
      waitValue: 3,
      waitUnit: 'DAYS',
      channel: 'SMS',
      offerId: 'offer-1',
      stop: 'ORDER_PLACED_SINCE_ENTRY',
      continuation: 'NO_ORDER_SINCE_ENTRY',
    });
  });
});

describe('the wire shape of a step', () => {
  it('carries a channel, an offer, a template, a wait and two conditions, and nothing that states a benefit', () => {
    const request = toStepRequest(step({ offerId: 'offer-1' }));

    expect(Object.keys(request).sort()).toEqual([
      'channel',
      'continuationCondition',
      'offerId',
      'stopCondition',
      'templateKey',
      'waitAfterPreviousSeconds',
    ]);
  });

  it('sends a blank template as null, so the offer’s own template is used', () => {
    expect(toStepRequest(step({ templateKey: '   ', offerId: 'offer-1' })).templateKey).toBeNull();
    expect(toStepRequest(step({ templateKey: ' WELCOME_BACK ' })).templateKey).toBe('WELCOME_BACK');
  });
});

describe('which template a step sends', () => {
  it('prefers the step’s own, then the offer’s', () => {
    const offers = [offer()];
    expect(effectiveTemplateKey(step({ templateKey: 'MINE', offerId: 'offer-1' }), offers)).toBe(
      'MINE',
    );
    expect(effectiveTemplateKey(step({ templateKey: '', offerId: 'offer-1' }), offers)).toBe(
      'OFFER_FREE_DELIVERY',
    );
    expect(effectiveTemplateKey(step({ templateKey: '', offerId: null }), offers)).toBe('');
  });

  it('prices and consents the scenario by its first step that sends a message', () => {
    const steps = [
      step({ uid: 1, channel: 'IN_APP', offerId: 'offer-1' }),
      step({ uid: 2, channel: 'SMS' }),
      step({ uid: 3, channel: 'EMAIL' }),
    ];
    expect(primaryStep(steps, [offer()])?.step.uid).toBe(2);
    expect(primaryStep([step({ channel: 'IN_APP', offerId: 'offer-1' })], [offer()])).toBeNull();
  });
});

describe('offers a step may name', () => {
  it('accepts a published offer allowed in the channel', () => {
    expect(offerEligible(offer(), 'SMS', NOW)).toBe(true);
  });

  it('refuses a draft, a retired, a superseded and an ended one', () => {
    expect(offerEligible(offer({ status: 'DRAFT' }), 'SMS', NOW)).toBe(false);
    expect(offerEligible(offer({ status: 'RETIRED' }), 'SMS', NOW)).toBe(false);
    expect(offerEligible(offer({ status: 'SUPERSEDED' }), 'SMS', NOW)).toBe(false);
    expect(offerEligible(offer({ validUntil: '2026-10-07T09:59:59Z' }), 'SMS', NOW)).toBe(false);
  });

  it('refuses a channel the offer is not allowed in', () => {
    expect(offerEligible(offer(), 'PUSH', NOW)).toBe(false);
  });

  it('accepts one whose window has not opened yet: a step may be written for next week’s offer', () => {
    expect(offerEligible(offer({ validFrom: '2026-10-20T00:00:00Z' }), 'SMS', NOW)).toBe(true);
  });
});

describe('what the server would refuse', () => {
  const codes = (steps: readonly StepDraft[], offers: readonly OfferView[] = [offer()]) =>
    scenarioProblems(steps, offers, NOW).map((p) => (p.step ? `${p.code}@${p.step}` : p.code));

  it('has no problem with one well-formed Telegram step', () => {
    expect(codes([step()])).toEqual([]);
  });

  it('wants at least one step, and no more than ten', () => {
    expect(codes([])).toEqual(['NO_STEPS']);
    const eleven = Array.from({ length: SCENARIO_MAX_STEPS + 1 }, (_, i) => step({ uid: i }));
    expect(codes(eleven)).toContain('TOO_MANY_STEPS');
    expect(codes(eleven.slice(0, SCENARIO_MAX_STEPS))).not.toContain('TOO_MANY_STEPS');
  });

  it('refuses a call-centre step, because the lead queue it hands off to does not exist', () => {
    expect(codes([step(), step({ channel: 'CALL_CENTRE', templateKey: '' })])).toContain(
      'CALL_CENTRE_NOT_WIRED@2',
    );
  });

  it('wants a scenario that sends a message at all: in-app alone has no consent, cost or estimate to stand on', () => {
    expect(codes([step({ channel: 'IN_APP', offerId: 'offer-1', templateKey: '' })])).toEqual([
      'NO_MESSAGING_STEP',
    ]);
  });

  it('wants an in-app step to name the offer it shows', () => {
    expect(codes([step(), step({ channel: 'IN_APP', offerId: null })])).toContain(
      'IN_APP_NEEDS_OFFER@2',
    );
  });

  it('wants a template, or an offer that names one', () => {
    expect(codes([step({ templateKey: '' })])).toEqual(['NEEDS_TEMPLATE@1']);
    expect(codes([step({ templateKey: '', offerId: 'offer-1' })])).toEqual([]);
  });

  it('keeps a wait between nothing and ninety days', () => {
    expect(codes([step({ waitValue: -2 })])).toEqual(['WAIT_INVALID@1']);
    expect(codes([step({ waitValue: SCENARIO_MAX_WAIT_DAYS, waitUnit: 'DAYS' })])).toEqual([]);
    expect(codes([step({ waitValue: SCENARIO_MAX_WAIT_DAYS + 1, waitUnit: 'DAYS' })])).toEqual([
      'WAIT_TOO_LONG@1',
    ]);
    expect(
      codes([step({ waitValue: SCENARIO_MAX_WAIT_DAYS * 24 + 1, waitUnit: 'HOURS' })]),
    ).toEqual(['WAIT_TOO_LONG@1']);
  });

  it('tells an offer that is over from an offer that is not allowed in the step’s channel', () => {
    expect(codes([step({ offerId: 'offer-1' })], [offer({ status: 'RETIRED' })])).toEqual([
      'OFFER_NOT_IN_FORCE@1',
    ]);
    expect(codes([step({ offerId: 'gone' })], [offer()])).toEqual(['OFFER_NOT_IN_FORCE@1']);
    expect(codes([step({ channel: 'PUSH', offerId: 'offer-1' })], [offer()])).toEqual([
      'OFFER_CHANNEL@1',
    ]);
  });
});

describe('steps that will be refused at launch', () => {
  it('names each messaging step on a channel with no delivery path, with the reason in words', () => {
    const unwired = unwiredSteps(
      [
        step({ channel: 'MESSAGING_APP' }),
        step({ channel: 'SMS' }),
        step({ channel: 'EMAIL' }),
        step({ channel: 'PUSH' }),
      ],
      CHANNELS,
    );

    expect(unwired.map((u) => [u.step, u.channel, u.sentence.key])).toEqual([
      [2, 'SMS', 'marketing.wiring.SMS_PURPOSE_NOT_PERMITTED'],
      [3, 'EMAIL', 'marketing.wiring.NO_DELIVERY_ADAPTER.EMAIL'],
      [4, 'PUSH', 'marketing.wiring.NO_DELIVERY_ADAPTER.PUSH'],
    ]);
  });

  it('says nothing about a step that sends no message, or about a channel the server did not list', () => {
    expect(unwiredSteps([step({ channel: 'IN_APP', offerId: 'offer-1' })], CHANNELS)).toEqual([]);
    expect(unwiredSteps([step({ channel: 'SMS' })], [])).toEqual([]);
  });
});

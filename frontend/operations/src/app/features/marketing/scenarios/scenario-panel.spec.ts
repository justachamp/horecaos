import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../../core/api/catalog-paths';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { ChannelView, MarketingApi } from '../marketing-api';
import { OfferView, OffersApi } from '../offers/offers-api';
import { ScenarioPanel } from './scenario-panel';
import {
  ScenarioDecisionView,
  ScenarioResultsView,
  ScenarioView,
  ScenariosApi,
} from './scenarios-api';

const SCOPE: BrandScope = { tenantId: 't1', brandId: 'b1' };
const GUEST = '0b1c2d3e-4f50-4a6b-8c7d-9e0f1a2b3c4d';

function scenario(overrides: Partial<ScenarioView> = {}, status = 'SENDING'): ScenarioView {
  return {
    campaign: {
      campaignId: 'c-1',
      name: 'Win back',
      status,
      consentPurpose: 'MARKETING_WINBACK',
      controlGroupPercent: 10,
      supersedesCampaignId: null,
      createdAt: '2026-10-01T00:00:00Z',
    },
    steps: [
      {
        sequence: 1,
        channel: 'MESSAGING_APP',
        offerId: 'offer-1',
        templateKey: 'WIN_BACK_TG',
        waitAfterPreviousSeconds: 0,
        continuationCondition: 'ALWAYS',
        stopCondition: 'NONE',
      },
      {
        sequence: 2,
        channel: 'SMS',
        offerId: null,
        templateKey: 'WIN_BACK_SMS',
        waitAfterPreviousSeconds: 172_800,
        continuationCondition: 'NO_ORDER_SINCE_ENTRY',
        stopCondition: 'ORDER_PLACED_SINCE_ENTRY',
      },
    ],
    participants: { IN_PROGRESS: 30, CONTROL: 4, COMPLETED: 2, STOPPED_BY_SUPPRESSION: 1 },
    decisions: { SENT: 28, FREQUENCY_CAP_REACHED: 3 },
    ...overrides,
  };
}

const OFFER: OfferView = {
  offerId: 'offer-1',
  lineageId: 'l-1',
  versionNumber: 3,
  status: 'PUBLISHED',
  displayName: 'Free delivery week',
  pricingPromotionId: 'promo-1',
  loyaltyAccrualRuleId: null,
  validFrom: '2026-10-01T00:00:00Z',
  validUntil: null,
  audienceId: null,
  allowedChannels: ['MESSAGING_APP'],
  templateKey: 'WIN_BACK_TG',
  templateVersion: null,
  bannerImageReference: null,
  createdBy: 'a',
  publishedBy: 'b',
  publishedAt: '2026-10-01T00:00:00Z',
  version: 2,
  createdAt: '2026-09-30T00:00:00Z',
  updatedAt: '2026-10-01T00:00:00Z',
};

const CHANNELS: readonly ChannelView[] = [
  { channel: 'MESSAGING_APP', carriesMarginalCost: false, isWired: true, notWiredReason: null },
  {
    channel: 'SMS',
    carriesMarginalCost: true,
    isWired: false,
    notWiredReason: 'SMS_PURPOSE_NOT_PERMITTED',
  },
];

function decision(overrides: Partial<ScenarioDecisionView> = {}): ScenarioDecisionView {
  return {
    decisionId: 'd-1',
    customerAccountId: GUEST,
    stepSequence: 1,
    decision: 'SENT',
    refusalReason: null,
    reasonText: null,
    resolvedChannel: 'MESSAGING_APP',
    attemptId: 'attempt-1',
    acknowledgedAt: null,
    decidedAt: '2026-10-05T07:00:00Z',
    ...overrides,
  };
}

function results(overrides: Partial<ScenarioResultsView> = {}): ScenarioResultsView {
  return {
    model: 'FIRST_TOUCH',
    windowDays: 14,
    treatedParticipants: 30,
    treatedConverted: 9,
    controlParticipants: 4,
    controlConverted: 1,
    treatedRate: 0.3,
    controlRate: 0.25,
    lift: 0.05,
    hasControlGroup: true,
    participantsWithOpenWindow: 12,
    ...overrides,
  };
}

async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('ScenarioPanel', () => {
  let fixture: ComponentFixture<ScenarioPanel>;
  let host: HTMLElement;
  let api: Record<string, ReturnType<typeof vi.fn>>;

  async function render(
    options: {
      view?: ScenarioView;
      decisions?: readonly ScenarioDecisionView[];
      results?: ScenarioResultsView;
      status?: string;
    } = {},
  ): Promise<{ edits: number; revised: string[] }> {
    api = {
      get: vi.fn().mockResolvedValue(options.view ?? scenario()),
      decisions: vi.fn().mockResolvedValue(options.decisions ?? []),
      results: vi.fn().mockResolvedValue(options.results ?? results()),
      revise: vi.fn().mockResolvedValue(scenario({}, 'DRAFT')),
    };
    await TestBed.configureTestingModule({
      imports: [ScenarioPanel],
      providers: [
        { provide: ScenariosApi, useValue: api },
        { provide: OffersApi, useValue: { list: vi.fn().mockResolvedValue([OFFER]) } },
        { provide: MarketingApi, useValue: { listChannels: vi.fn().mockResolvedValue(CHANNELS) } },
        {
          provide: CurrentBrand,
          useValue: {
            scope: signal<BrandScope | null>(SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(ScenarioPanel);
    fixture.componentRef.setInput('campaignId', 'c-1');
    fixture.componentRef.setInput('status', options.status ?? 'SENDING');
    const seen = { edits: 0, revised: [] as string[] };
    fixture.componentInstance.editRequested.subscribe(() => seen.edits++);
    fixture.componentInstance.revised.subscribe((id) => seen.revised.push(id));
    host = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
    return seen;
  }

  const text = (testId: string) =>
    host.querySelector(`[data-testid="${testId}"]`)?.textContent?.replace(/\s+/g, ' ').trim() ?? '';

  /** A definition list or a counts list as label → number, so a test reads what a person reads. */
  const pairs = (testId: string): Record<string, string> => {
    const root = host.querySelector(`[data-testid="${testId}"]`)!;
    const out: Record<string, string> = {};
    const terms = [...root.querySelectorAll('dt')];
    if (terms.length > 0) {
      terms.forEach(
        (dt) => (out[dt.textContent!.trim()] = dt.nextElementSibling!.textContent!.trim()),
      );
    } else {
      root.querySelectorAll('li').forEach((li) => {
        const [label, count] = [...li.querySelectorAll('span')].map((span) =>
          span.textContent!.trim(),
        );
        out[label] = count;
      });
    }
    return out;
  };

  describe('the steps', () => {
    it('reads each step as an operator would: channel, wait in the largest unit, offer by name, template, conditions', async () => {
      await render();

      const rows = [...host.querySelectorAll('[data-testid="scenario-step-row"]')].map((r) =>
        r.textContent!.replace(/\s+/g, ' '),
      );
      expect(rows[0]).toContain('1. Telegram');
      expect(rows[0]).toContain('Straight away');
      expect(rows[0]).toContain('Free delivery week · v3');
      expect(rows[1]).toContain('2. SMS');
      expect(rows[1]).toContain('2 day(s)');
      expect(rows[1]).toContain('Only if the guest has not ordered since entering');
      expect(rows[1]).toContain('As soon as the guest orders');
    });

    it('says beside a step that its channel is not connected, and why', async () => {
      await render();

      const notes = host.querySelectorAll('[data-testid="scenario-step-unwired-note"]');
      expect(notes).toHaveLength(1);
      expect(notes[0].textContent).toContain('Not connected');
      expect(notes[0].textContent).toContain('not cleared to carry marketing messages');
    });

    it('names the version this one replaces', async () => {
      const view = scenario();
      await render({
        view: { ...view, campaign: { ...view.campaign, supersedesCampaignId: 'old-1' } },
      });

      expect(text('scenario-supersedes')).toContain('old-1');
    });
  });

  describe('where the guests are', () => {
    it('counts them by state, with the control group on its own line', async () => {
      await render();

      expect(pairs('scenario-participants')).toEqual({
        'In progress': '30',
        'In the control group': '4',
        Completed: '2',
        'Stopped: suppressed': '1',
      });
      expect(text('scenario-control')).toContain('10% of the audience is withheld');
    });

    it('says a scenario without a control group has no baseline', async () => {
      const view = scenario();
      await render({
        view: { ...view, campaign: { ...view.campaign, controlGroupPercent: null } },
      });

      expect(text('scenario-control')).toContain('No control group');
      expect(text('scenario-control')).toContain('no lift');
    });

    it('says nobody has entered yet, and measures nothing, before the scenario starts', async () => {
      await render({
        view: scenario({ participants: {}, decisions: {} }, 'APPROVED'),
        status: 'APPROVED',
      });

      expect(text('scenario-guests')).toContain('No guest has entered yet');
      expect(api['results']).not.toHaveBeenCalled();
      expect(text('scenario-results')).toContain('nothing to measure');
    });
  });

  describe('why a guest was blocked', () => {
    it('counts decisions by their outcome, naming each refusal in words', async () => {
      await render();

      expect(pairs('scenario-decision-counts')).toEqual({
        Sent: '28',
        'Already at the frequency cap': '3',
      });
    });

    it('explains a blocked row: what it means, whether it ends the run, the sentence recorded and what to do', async () => {
      await render({
        decisions: [
          decision({
            decisionId: 'd-2',
            decision: 'BLOCKED',
            refusalReason: 'FREQUENCY_CAP_REACHED',
            reasonText:
              'Contact policy for SMS messages about MARKETING_PROMOTIONS: 1 already sent in the WEEKLY window, and this brand allows 1',
            stepSequence: 2,
            resolvedChannel: 'SMS',
          }),
        ],
      });

      const why = host.querySelector('[data-testid="decision-why"]')!;
      expect(why.querySelector('summary')!.textContent).toContain('Blocked');
      expect(why.querySelector('summary')!.textContent).toContain('Already at the frequency cap');
      expect(why.textContent).toContain('contact policy for this channel and purpose');
      expect(text('decision-effect')).toContain('Holds the step and asks again later');
      expect(text('decision-recorded')).toContain('this brand allows 1');
      expect(why.textContent).toContain('The platform’s cap cannot be raised');
    });

    it('says that a consent or suppression block ends the guest’s run', async () => {
      await render({
        decisions: [
          decision({
            decision: 'BLOCKED',
            refusalReason: 'SUPPRESSED',
            reasonText: 'An active suppression covers this guest on SMS',
          }),
        ],
      });

      expect(text('decision-effect')).toContain('Ends this guest’s run');
    });

    it('shows a reason a newer server added as written, with the sentence recorded beside it', async () => {
      await render({
        decisions: [
          decision({
            decision: 'BLOCKED',
            refusalReason: 'SOMETHING_NEW',
            reasonText: 'A new rule applied',
          }),
        ],
      });

      const why = host.querySelector('[data-testid="decision-why"]')!;
      expect(why.querySelector('summary')!.textContent).toContain('SOMETHING_NEW');
      expect(why.textContent).toContain('A new rule applied');
      expect(host.querySelector('[data-testid="decision-effect"]')).toBeNull();
    });

    it('shows the note on a step that was sent but held, for example by quiet hours', async () => {
      await render({
        decisions: [
          decision({
            reasonText:
              'Held to 2026-10-06T05:00:00Z: quiet hours 21:00 to 10:00 (Asia/Tashkent) apply to this channel',
          }),
        ],
      });

      expect(text('decision-note')).toContain('quiet hours 21:00 to 10:00');
    });

    it('shows a guest as a short account id, never a name, a phone or an email', async () => {
      await render({ decisions: [decision()] });

      const row = host.querySelector('[data-testid="decision-row"]')!;
      expect(row.textContent).toContain('0b1c2d3e');
      expect(row.textContent).not.toContain(GUEST);
      expect(row.querySelector('[title]')!.getAttribute('title')).toBe(GUEST);
    });

    it('answers "why did this guest not get a step" by asking for that guest’s decisions only', async () => {
      await render({ decisions: [decision()] });
      const input = host.querySelector('[data-testid="scenario-guest-input"]') as HTMLInputElement;
      input.value = GUEST;
      input.dispatchEvent(new Event('input'));
      (host.querySelector('[data-testid="scenario-guest-lookup"]') as HTMLButtonElement).click();
      await flush();
      fixture.detectChanges();

      expect(api['decisions']).toHaveBeenLastCalledWith(SCOPE, 'c-1', {
        accountId: GUEST,
        limit: 100,
      });
      expect(host.querySelector('[data-testid="scenario-guest-clear"]')).not.toBeNull();

      (host.querySelector('[data-testid="scenario-guest-clear"]') as HTMLButtonElement).click();
      await flush();
      expect(api['decisions']).toHaveBeenLastCalledWith(SCOPE, 'c-1', {
        accountId: undefined,
        limit: 100,
      });
    });

    it('does not spend a request on something that cannot be an account id', async () => {
      await render();
      const calls = api['decisions'].mock.calls.length;
      const input = host.querySelector('[data-testid="scenario-guest-input"]') as HTMLInputElement;
      input.value = 'Ayub';
      input.dispatchEvent(new Event('input'));
      (host.querySelector('[data-testid="scenario-guest-lookup"]') as HTMLButtonElement).click();
      await flush();
      fixture.detectChanges();

      expect(api['decisions'].mock.calls.length).toBe(calls);
      expect(text('scenario-decisions-error')).toContain('not an account id');
    });

    it('says when a guest has no decisions yet', async () => {
      await render({ decisions: [] });
      api['decisions'].mockResolvedValue([]);
      const input = host.querySelector('[data-testid="scenario-guest-input"]') as HTMLInputElement;
      input.value = GUEST;
      input.dispatchEvent(new Event('input'));
      (host.querySelector('[data-testid="scenario-guest-lookup"]') as HTMLButtonElement).click();
      await flush();
      fixture.detectChanges();

      expect(text('scenario-decisions-empty')).toContain('nothing for that guest');
    });
  });

  describe('did it work', () => {
    it('shows both groups’ rates and the lift in percentage points', async () => {
      await render();

      expect(text('scenario-treated-rate')).toBe('30.0%');
      expect(text('scenario-control-rate')).toBe('25.0%');
      expect(text('scenario-lift')).toContain('+5.0 percentage points');
      expect(api['results']).toHaveBeenCalledWith(SCOPE, 'c-1', 'FIRST_TOUCH', 14);
    });

    it('states no lift for a scenario that ran without a control group, and says why', async () => {
      await render({
        results: results({
          hasControlGroup: false,
          controlParticipants: 0,
          controlConverted: 0,
          controlRate: null,
          lift: null,
        }),
      });

      expect(host.querySelector('[data-testid="scenario-lift"]')).toBeNull();
      expect(text('scenario-no-lift')).toContain('no baseline');
    });

    it('warns that figures will move while guests are still inside their window', async () => {
      await render();

      expect(text('scenario-open-windows')).toContain('12 guest(s) are still inside their window');
    });

    it('measures again under the other attribution model', async () => {
      await render();
      const select = host.querySelector('[data-testid="scenario-model"]') as HTMLSelectElement;
      select.value = 'LAST_TOUCH';
      select.dispatchEvent(new Event('change'));
      await flush();

      expect(api['results']).toHaveBeenLastCalledWith(SCOPE, 'c-1', 'LAST_TOUCH', 14);
    });

    it('refuses a window outside 1 to 90 days without asking', async () => {
      await render();
      const calls = api['results'].mock.calls.length;
      const input = host.querySelector('[data-testid="scenario-window"]') as HTMLInputElement;
      input.value = '120';
      input.dispatchEvent(new Event('input'));
      input.dispatchEvent(new Event('change'));
      await flush();
      fixture.detectChanges();

      expect(api['results'].mock.calls.length).toBe(calls);
      expect(text('scenario-results-error')).toContain('1 to 90');
    });
  });

  describe('changing a scenario', () => {
    it('offers to edit the steps of a draft', async () => {
      const seen = await render({ view: scenario({}, 'DRAFT'), status: 'DRAFT' });

      expect(host.querySelector('[data-testid="scenario-revise"]')).toBeNull();
      (host.querySelector('[data-testid="scenario-edit"]') as HTMLButtonElement).click();
      expect(seen.edits).toBe(1);
    });

    it('offers only a new version once it is past draft, and moves to it', async () => {
      const seen = await render({ status: 'SENDING' });

      expect(host.querySelector('[data-testid="scenario-edit"]')).toBeNull();
      (host.querySelector('[data-testid="scenario-revise"]') as HTMLButtonElement).click();
      await flush();

      expect(api['revise']).toHaveBeenCalledWith(SCOPE, 'c-1');
      expect(seen.revised).toEqual(['c-1']);
    });

    it('shows a refused revision and stays', async () => {
      const seen = await render({ status: 'SENDING' });
      api['revise'].mockRejectedValue(
        new ApiError(ApiErrorCode.UNPROCESSABLE_STATE, 422, null, null),
      );
      (host.querySelector('[data-testid="scenario-revise"]') as HTMLButtonElement).click();
      await flush();
      fixture.detectChanges();

      expect(host.querySelector('[data-testid="scenario-action-error"]')).not.toBeNull();
      expect(seen.revised).toEqual([]);
    });
  });

  it('shows why it could not load a scenario, rather than an empty panel', async () => {
    api = {
      get: vi
        .fn()
        .mockRejectedValue(new ApiError(ApiErrorCode.RESOURCE_NOT_FOUND, 404, null, null)),
    };
    await TestBed.configureTestingModule({
      imports: [ScenarioPanel],
      providers: [
        { provide: ScenariosApi, useValue: api },
        { provide: OffersApi, useValue: { list: vi.fn().mockResolvedValue([]) } },
        { provide: MarketingApi, useValue: { listChannels: vi.fn().mockResolvedValue([]) } },
        {
          provide: CurrentBrand,
          useValue: {
            scope: signal<BrandScope | null>(SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(ScenarioPanel);
    fixture.componentRef.setInput('campaignId', 'c-1');
    fixture.componentRef.setInput('status', 'DRAFT');
    host = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="scenario-load-error"]')).not.toBeNull();
  });
});

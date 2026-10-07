import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../../core/api/catalog-paths';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { ContactPolicyApi } from '../contact-policy/contact-policy-api';
import {
  AudienceSummary,
  ChannelView,
  MarketingApi,
  MarketingTemplateView,
} from '../marketing-api';
import { OfferView, OffersApi } from '../offers/offers-api';
import { ScenarioEditor } from './scenario-editor';
import { ScenarioView, ScenariosApi } from './scenarios-api';

const SCOPE: BrandScope = { tenantId: 't1', brandId: 'b1' };

const AUDIENCES: readonly AudienceSummary[] = [
  {
    audienceId: 'aud-1',
    name: 'Lapsed guests',
    description: null,
    status: 'ACTIVE',
    definitionVersion: 1,
    createdBy: 'a',
    createdAt: '2026-09-01T00:00:00Z',
    updatedAt: '2026-09-01T00:00:00Z',
  },
];

function channel(name: string, wired: boolean, reason: string | null, cost: boolean): ChannelView {
  return { channel: name, carriesMarginalCost: cost, isWired: wired, notWiredReason: reason };
}

/** Telegram works; SMS is gated; email and push have no delivery. The state of this brand today. */
const CHANNELS: readonly ChannelView[] = [
  channel('SMS', false, 'SMS_PURPOSE_NOT_PERMITTED', true),
  channel('EMAIL', false, 'NO_DELIVERY_ADAPTER', true),
  channel('PUSH', false, 'NO_DELIVERY_ADAPTER', false),
  channel('MESSAGING_APP', true, null, false),
];

const TEMPLATES: readonly MarketingTemplateView[] = [
  {
    id: 't-1',
    brandId: 'b1',
    templateKey: 'WIN_BACK_TG',
    notificationClass: 'MARKETING',
    channel: 'MESSAGING_APP',
    consentPurpose: 'MARKETING_WINBACK',
    status: 'ACTIVE',
    activeVersion: 1,
    version: 1,
  },
  {
    id: 't-2',
    brandId: 'b1',
    templateKey: 'WIN_BACK_SMS',
    notificationClass: 'MARKETING',
    channel: 'SMS',
    consentPurpose: 'MARKETING_PROMOTIONS',
    status: 'ACTIVE',
    activeVersion: 1,
    version: 1,
  },
];

function offer(overrides: Partial<OfferView> = {}): OfferView {
  return {
    offerId: 'offer-1',
    lineageId: 'l-1',
    versionNumber: 1,
    status: 'PUBLISHED',
    displayName: 'Free delivery week',
    pricingPromotionId: 'promo-1',
    loyaltyAccrualRuleId: null,
    validFrom: '2026-10-01T00:00:00Z',
    validUntil: null,
    audienceId: null,
    allowedChannels: ['MESSAGING_APP', 'IN_APP'],
    templateKey: 'WIN_BACK_TG',
    templateVersion: null,
    bannerImageReference: null,
    createdBy: 'a',
    publishedBy: 'b',
    publishedAt: '2026-10-01T00:00:00Z',
    version: 2,
    createdAt: '2026-09-30T00:00:00Z',
    updatedAt: '2026-10-01T00:00:00Z',
    ...overrides,
  };
}

function scenarioView(status = 'DRAFT'): ScenarioView {
  return {
    campaign: {
      campaignId: 'c-1',
      name: 'Win back',
      status,
      consentPurpose: 'MARKETING_WINBACK',
      controlGroupPercent: null,
      supersedesCampaignId: null,
      createdAt: '2026-10-01T00:00:00Z',
    },
    steps: [
      {
        sequence: 1,
        channel: 'MESSAGING_APP',
        offerId: null,
        templateKey: 'WIN_BACK_TG',
        waitAfterPreviousSeconds: 172_800,
        continuationCondition: 'ALWAYS',
        stopCondition: 'NONE',
      },
    ],
    participants: {},
    decisions: {},
  };
}

async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('ScenarioEditor', () => {
  let fixture: ComponentFixture<ScenarioEditor>;
  let host: HTMLElement;
  let scenarios: Record<string, ReturnType<typeof vi.fn>>;
  let saved: string[];

  async function render(
    options: {
      campaignId?: string | null;
      channels?: readonly ChannelView[];
      offers?: readonly OfferView[];
      existing?: ScenarioView;
    } = {},
  ): Promise<void> {
    scenarios = {
      create: vi.fn().mockResolvedValue(scenarioView()),
      replaceSteps: vi.fn().mockResolvedValue(scenarioView()),
      get: vi.fn().mockResolvedValue(options.existing ?? scenarioView()),
    };
    await TestBed.configureTestingModule({
      imports: [ScenarioEditor],
      providers: [
        { provide: ScenariosApi, useValue: scenarios },
        { provide: OffersApi, useValue: { list: vi.fn().mockResolvedValue(options.offers ?? []) } },
        {
          provide: ContactPolicyApi,
          useValue: {
            defaults: vi.fn().mockResolvedValue({
              channelPriorityOrder: [],
              inAppShowCapPerDay: 3,
              controlGroupPercentDefault: 10,
            }),
          },
        },
        {
          provide: MarketingApi,
          useValue: {
            listChannels: vi.fn().mockResolvedValue(options.channels ?? CHANNELS),
            listTemplates: vi.fn().mockResolvedValue(TEMPLATES),
          },
        },
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
    fixture = TestBed.createComponent(ScenarioEditor);
    fixture.componentRef.setInput('audiences', AUDIENCES);
    fixture.componentRef.setInput('channels', options.channels ?? CHANNELS);
    fixture.componentRef.setInput('templates', TEMPLATES);
    fixture.componentRef.setInput('campaignId', options.campaignId ?? null);
    saved = [];
    fixture.componentInstance.saved.subscribe((id) => saved.push(id));
    host = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
  }

  function type(testId: string, value: string, index = 0): void {
    const input = host.querySelectorAll(`[data-testid="${testId}"]`)[index] as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function choose(testId: string, value: string, index = 0): void {
    const select = host.querySelectorAll(`[data-testid="${testId}"]`)[index] as HTMLSelectElement;
    select.value = value;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  function click(testId: string, index = 0): void {
    (host.querySelectorAll(`[data-testid="${testId}"]`)[index] as HTMLElement).click();
    fixture.detectChanges();
  }

  /** A Telegram step with a template, a name and the audience: the smallest scenario that saves. */
  function fillSmallest(): void {
    type('scenario-name', 'Win back');
    choose('scenario-step-template', 'WIN_BACK_TG');
  }

  const saveButton = () => host.querySelector('[data-testid="scenario-save"]') as HTMLButtonElement;

  it('saves a one-step Telegram scenario as a draft, with the consent purpose of its template', async () => {
    await render();
    fillSmallest();

    expect(saveButton().disabled).toBe(false);
    saveButton().click();
    await flush();

    expect(scenarios['create']).toHaveBeenCalledTimes(1);
    const [scope, request] = scenarios['create'].mock.calls[0];
    expect(scope).toEqual(SCOPE);
    expect(request).toEqual({
      name: 'Win back',
      audienceId: 'aud-1',
      consentPurpose: 'MARKETING_WINBACK',
      recipientCap: 1000,
      costCeilingMinor: null,
      currency: 'UZS',
      controlGroupPercent: null,
      scheduledAt: null,
      steps: [
        {
          channel: 'MESSAGING_APP',
          offerId: null,
          templateKey: 'WIN_BACK_TG',
          waitAfterPreviousSeconds: 0,
          continuationCondition: 'ALWAYS',
          stopCondition: 'NONE',
        },
      ],
    });
    expect(saved).toEqual(['c-1']);
  });

  it('says what is missing and will not save without it', async () => {
    await render();

    expect(saveButton().disabled).toBe(true);
    expect(host.querySelector('[data-testid="scenario-form-problem"]')!.textContent).toContain(
      'name',
    );
    expect(host.querySelector('[data-testid="scenario-step-problem"]')!.textContent).toContain(
      'template',
    );
  });

  it('gives a step no field in which to state a discount: the only number it takes is a wait', async () => {
    await render();

    const step = host.querySelector('[data-testid="scenario-step"]')!;
    expect(step.querySelectorAll('input[type="number"]')).toHaveLength(1);
    expect(step.querySelector('[data-testid="scenario-step-wait"]')).not.toBeNull();
  });

  describe('steps', () => {
    it('adds steps up to ten and no further', async () => {
      await render();
      for (let i = 0; i < 12; i++) {
        click('scenario-add-step');
      }

      expect(host.querySelectorAll('[data-testid="scenario-step"]')).toHaveLength(10);
      expect(
        (host.querySelector('[data-testid="scenario-add-step"]') as HTMLButtonElement).disabled,
      ).toBe(true);
    });

    it('removes a step and reorders two, in the order the guest will meet them', async () => {
      await render();
      click('scenario-add-step');
      choose('scenario-step-channel', 'MESSAGING_APP', 0);
      choose('scenario-step-template', 'WIN_BACK_TG', 0);
      choose('scenario-step-channel', 'MESSAGING_APP', 1);
      type('scenario-step-wait', '3', 1);
      // Step 2 (a three-day wait) moves above step 1 (no wait).
      click('scenario-step-up', 1);
      type('scenario-name', 'Order');
      choose('scenario-step-template', 'WIN_BACK_TG', 0);
      saveButton().click();
      await flush();

      const steps = scenarios['create'].mock.calls[0][1].steps;
      expect(
        steps.map((s: { waitAfterPreviousSeconds: number }) => s.waitAfterPreviousSeconds),
      ).toEqual([259_200, 0]);

      click('scenario-step-remove', 0);
      expect(host.querySelectorAll('[data-testid="scenario-step"]')).toHaveLength(1);
    });

    it('turns a wait in days into seconds, and refuses one past ninety days', async () => {
      await render();
      fillSmallest();
      type('scenario-step-wait', '2');
      saveButton().click();
      await flush();
      expect(scenarios['create'].mock.calls[0][1].steps[0].waitAfterPreviousSeconds).toBe(172_800);

      type('scenario-step-wait', '91');
      expect(saveButton().disabled).toBe(true);
      expect(host.querySelector('[data-testid="scenario-step-problem"]')!.textContent).toContain(
        '90 days',
      );
    });

    it('carries the continuation and stop conditions from their closed sets', async () => {
      await render();
      fillSmallest();
      choose('scenario-step-continuation', 'NO_ORDER_SINCE_ENTRY');
      choose('scenario-step-stop', 'ORDER_PLACED_SINCE_ENTRY');
      saveButton().click();
      await flush();

      const step = scenarios['create'].mock.calls[0][1].steps[0];
      expect([step.continuationCondition, step.stopCondition]).toEqual([
        'NO_ORDER_SINCE_ENTRY',
        'ORDER_PLACED_SINCE_ENTRY',
      ]);
    });
  });

  describe('channels, honestly', () => {
    it('marks SMS, email and push as not connected, and call-centre as impossible, in the channel list', async () => {
      await render();
      const options = [
        ...host.querySelectorAll('[data-testid="scenario-step-channel"] option'),
      ] as HTMLOptionElement[];
      const text = (value: string) => options.find((o) => o.value === value)!.textContent!;

      expect(text('MESSAGING_APP')).not.toContain('not connected');
      expect(text('SMS')).toContain('not connected');
      expect(text('EMAIL')).toContain('not connected');
      expect(text('PUSH')).toContain('not connected');
      expect(options.find((o) => o.value === 'CALL_CENTRE')!.disabled).toBe(true);
      expect(text('CALL_CENTRE')).toContain('call-centre queue');
    });

    it('says the SMS gate beside a step that sends SMS, and again before saving, and still lets the draft be saved', async () => {
      await render();
      fillSmallest();
      choose('scenario-step-channel', 'SMS');
      choose('scenario-step-template', 'WIN_BACK_SMS');
      type('scenario-ceiling', '500000');

      const note = host.querySelector('[data-testid="scenario-step-unwired"]')!.textContent!;
      expect(note).toContain('not cleared to carry marketing messages');
      const band = host.querySelector('[data-testid="scenario-unwired"]')!.textContent!;
      expect(band).toContain('cannot be launched');
      expect(band).toContain('Step 1');
      // A draft is a plan; the channel may be connected by the time it is approved.
      expect(saveButton().disabled).toBe(false);
    });

    it('says plainly that email and push are not connected, and why', async () => {
      await render();
      choose('scenario-step-channel', 'EMAIL');
      expect(host.querySelector('[data-testid="scenario-step-unwired"]')!.textContent).toContain(
        'mail service sends staff invitations',
      );

      choose('scenario-step-channel', 'PUSH');
      expect(host.querySelector('[data-testid="scenario-step-unwired"]')!.textContent).toContain(
        'no push provider',
      );
    });

    it('wants a cost ceiling for a channel billed per message, and sends it with the currency', async () => {
      await render();
      fillSmallest();
      choose('scenario-step-channel', 'SMS');
      choose('scenario-step-template', 'WIN_BACK_SMS');

      expect(host.querySelector('[data-testid="scenario-ceiling"]')).not.toBeNull();
      expect(saveButton().disabled).toBe(true);
      expect(host.querySelector('[data-testid="scenario-form-problem"]')!.textContent).toContain(
        'cost ceiling',
      );

      type('scenario-ceiling', '500000');
      saveButton().click();
      await flush();

      const request = scenarios['create'].mock.calls[0][1];
      expect(request.costCeilingMinor).toBe(500_000);
      expect(request.consentPurpose).toBe('MARKETING_PROMOTIONS');
    });

    it('asks for no ceiling when the first message goes by Telegram', async () => {
      await render();

      expect(host.querySelector('[data-testid="scenario-ceiling"]')).toBeNull();
    });
  });

  describe('offers', () => {
    it('lets an in-app step show a published offer and nothing else, and sends the offer id', async () => {
      await render({ offers: [offer()] });
      fillSmallest();
      click('scenario-add-step');
      choose('scenario-step-channel', 'IN_APP', 1);

      expect(
        host
          .querySelectorAll('[data-testid="scenario-step"]')[1]
          .querySelector('[data-testid="scenario-step-template"]'),
      ).toBeNull();
      expect(saveButton().disabled).toBe(true);
      expect(host.querySelector('[data-testid="scenario-step-problem"]')!.textContent).toContain(
        'names no offer',
      );

      const pickers = host.querySelectorAll('[data-testid="offer-picker-select"]');
      (pickers[1] as HTMLSelectElement).value = 'offer-1';
      pickers[1].dispatchEvent(new Event('change'));
      fixture.detectChanges();
      saveButton().click();
      await flush();

      const steps = scenarios['create'].mock.calls[0][1].steps;
      expect(steps[1]).toMatchObject({ channel: 'IN_APP', offerId: 'offer-1', templateKey: null });
    });

    it('uses the offer’s own template when the step names none', async () => {
      await render({ offers: [offer()] });
      type('scenario-name', 'With offer');
      const picker = host.querySelector('[data-testid="offer-picker-select"]') as HTMLSelectElement;
      picker.value = 'offer-1';
      picker.dispatchEvent(new Event('change'));
      fixture.detectChanges();

      expect(saveButton().disabled).toBe(false);
      expect(
        host.querySelector('[data-testid="scenario-step-template"] option')!.textContent,
      ).toContain('WIN_BACK_TG');
      saveButton().click();
      await flush();
      expect(scenarios['create'].mock.calls[0][1].steps[0]).toMatchObject({
        offerId: 'offer-1',
        templateKey: null,
      });
    });

    it('drops an offer that the new channel is not allowed in', async () => {
      await render({ offers: [offer()] });
      const picker = host.querySelector('[data-testid="offer-picker-select"]') as HTMLSelectElement;
      picker.value = 'offer-1';
      picker.dispatchEvent(new Event('change'));
      fixture.detectChanges();

      choose('scenario-step-channel', 'SMS');

      const after = host.querySelector('[data-testid="offer-picker-select"]') as HTMLSelectElement;
      expect(after.value).toBe('');
    });
  });

  describe('the control group', () => {
    it('is off unless asked for, and says what that costs the results', async () => {
      await render();
      fillSmallest();

      expect(host.querySelector('[data-testid="scenario-control-off"]')!.textContent).toContain(
        'cannot state a lift',
      );
      saveButton().click();
      await flush();
      expect(scenarios['create'].mock.calls[0][1].controlGroupPercent).toBeNull();
    });

    it('offers the tenant’s default percentage, says how many guests it would withhold, and sends it', async () => {
      await render();
      fillSmallest();
      click('scenario-control-on');

      const percent = host.querySelector(
        '[data-testid="scenario-control-percent"]',
      ) as HTMLInputElement;
      expect(percent.value).toBe('10');
      expect(host.querySelector('[data-testid="scenario-control-hint"]')!.textContent).toContain(
        'Up to 100 of the 1000 guests',
      );
      saveButton().click();
      await flush();
      expect(scenarios['create'].mock.calls[0][1].controlGroupPercent).toBe(10);
    });

    it('refuses a share outside 0 to 100', async () => {
      await render();
      fillSmallest();
      click('scenario-control-on');
      type('scenario-control-percent', '150');

      expect(saveButton().disabled).toBe(true);
    });
  });

  describe('editing a draft', () => {
    it('loads the steps and saves only the steps, as a replacement', async () => {
      await render({ campaignId: 'c-1' });

      expect(host.querySelector('[data-testid="scenario-name"]')).toBeNull();
      expect(
        (host.querySelector('[data-testid="scenario-step-wait"]') as HTMLInputElement).value,
      ).toBe('2');
      expect(
        (host.querySelector('[data-testid="scenario-step-unit"]') as HTMLSelectElement).value,
      ).toBe('DAYS');
      type('scenario-step-wait', '3');
      saveButton().click();
      await flush();

      expect(scenarios['replaceSteps']).toHaveBeenCalledTimes(1);
      const [scope, id, steps] = scenarios['replaceSteps'].mock.calls[0];
      expect([scope, id, steps[0].waitAfterPreviousSeconds]).toEqual([SCOPE, 'c-1', 259_200]);
      expect(scenarios['create']).not.toHaveBeenCalled();
      expect(saved).toEqual(['c-1']);
    });

    it('refuses to edit a scenario that has left draft, and points at a new version', async () => {
      await render({ campaignId: 'c-1', existing: scenarioView('APPROVED') });

      expect(host.querySelector('[data-testid="scenario-not-draft"]')!.textContent).toContain(
        'new version',
      );
      expect(saveButton().disabled).toBe(true);
    });
  });

  it('shows the server’s refusal and keeps what was typed', async () => {
    await render();
    fillSmallest();
    scenarios['create'].mockRejectedValue(
      new ApiError(
        ApiErrorCode.VALIDATION_FAILED,
        400,
        { status: 400, detail: 'No audience aud-1 belongs to this brand' } as never,
        null,
      ),
    );
    saveButton().click();
    await flush();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="scenario-submit-error"]')!.textContent).toContain(
      'No audience',
    );
    expect(saved).toEqual([]);
    expect((host.querySelector('[data-testid="scenario-name"]') as HTMLInputElement).value).toBe(
      'Win back',
    );
  });

  it('can be abandoned', async () => {
    await render();
    let cancelled = 0;
    fixture.componentInstance.cancelled.subscribe(() => cancelled++);

    click('scenario-cancel');

    expect(cancelled).toBe(1);
  });
});

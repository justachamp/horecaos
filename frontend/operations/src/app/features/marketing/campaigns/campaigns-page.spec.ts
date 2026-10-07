import { Provider, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../../core/api/catalog-paths';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { applyRegionalFormats, resetRegionalFormats } from '../../../core/format/regional-format';
import { I18n } from '../../../core/i18n/i18n';
import { ContactPolicyApi } from '../contact-policy/contact-policy-api';
import { LoyaltyApi } from '../loyalty/loyalty-api';
import { AudienceSummary, CampaignView, ChannelView, MarketingApi } from '../marketing-api';
import { OffersApi } from '../offers/offers-api';
import { PromotionsApi } from '../promotions/promotions-api';
import { ScenariosApi } from '../scenarios/scenarios-api';
import { CampaignsPage } from './campaigns-page';

const SCOPE: BrandScope = { tenantId: 'tenant-1', brandId: 'brand-1' };

/** T18: isWired true for every channel this fixture knows, unless a test overrides it. */
const CHANNELS: readonly ChannelView[] = [
  { channel: 'SMS', carriesMarginalCost: true, isWired: true, notWiredReason: null },
  { channel: 'EMAIL', carriesMarginalCost: true, isWired: true, notWiredReason: null },
  { channel: 'PUSH', carriesMarginalCost: false, isWired: true, notWiredReason: null },
  { channel: 'MESSAGING_APP', carriesMarginalCost: false, isWired: true, notWiredReason: null },
];

const AUDIENCE: AudienceSummary = {
  audienceId: 'audience-1',
  name: 'Everybody registered',
  description: null,
  status: 'ACTIVE',
  definitionVersion: 1,
  createdBy: 'actor-1',
  createdAt: '2026-09-01T08:00:00Z',
  updatedAt: '2026-09-01T08:00:00Z',
};

function campaign(overrides: Partial<CampaignView> = {}): CampaignView {
  return {
    campaignId: 'campaign-1',
    name: 'Autumn promotion',
    channel: 'SMS',
    consentPurpose: 'MARKETING_PROMOTIONS',
    status: 'DRAFT',
    audienceId: 'audience-1',
    snapshotId: null,
    templateKey: 'MARKETING_PROMOTION',
    timezone: 'Asia/Tashkent',
    recipientCap: 1000,
    estimatedRecipients: null,
    estimatedCostLowMinor: null,
    estimatedCostHighMinor: null,
    estimatedDeliverySeconds: null,
    costCeilingMinor: 100_000,
    reservedCostMinor: 0,
    spentCostMinor: 0,
    reservedRecipients: 0,
    currency: 'UZS',
    benefitOfferId: null,
    loyaltyAccrualRuleId: null,
    createdBy: 'actor-1',
    approvedBy: null,
    blockedCount: 0,
    pausedAt: null,
    scheduledAt: null,
    haltedReason: null,
    isWired: true,
    notWiredReason: null,
    createdAt: '2026-09-01T08:00:00Z',
    updatedAt: '2026-09-01T08:00:00Z',
    version: 1,
    kind: 'BROADCAST',
    controlGroupPercent: null,
    supersedesCampaignId: null,
    ...overrides,
  };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('CampaignsPage', () => {
  let fixture: ComponentFixture<CampaignsPage>;
  let api: Record<string, ReturnType<typeof vi.fn>>;

  afterEach(() => resetRegionalFormats());

  async function render(
    campaigns: readonly CampaignView[],
    channels: readonly ChannelView[] = CHANNELS,
    extraProviders: readonly Provider[] = [],
  ): Promise<void> {
    api = {
      listCampaigns: vi.fn().mockResolvedValue(campaigns),
      listAudiences: vi.fn().mockResolvedValue([AUDIENCE]),
      listChannels: vi.fn().mockResolvedValue(channels),
      listSuppressions: vi.fn().mockResolvedValue([]),
      listTemplates: vi.fn().mockResolvedValue([]),
    };
    await TestBed.configureTestingModule({
      imports: [CampaignsPage],
      providers: [
        provideRouter([]),
        { provide: MarketingApi, useValue: api },
        {
          provide: CurrentBrand,
          useValue: {
            scope: signal<BrandScope | null>(SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        ...extraProviders,
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(CampaignsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  /** What the Offers and Contact policy tabs, and the scenario editor, read: nothing, successfully. */
  const SIDE_APIS: readonly Provider[] = [
    {
      provide: OffersApi,
      useValue: { list: vi.fn().mockResolvedValue([]) },
    },
    { provide: PromotionsApi, useValue: { list: vi.fn().mockResolvedValue([]) } },
    { provide: LoyaltyApi, useValue: { listAccrualRules: vi.fn().mockResolvedValue([]) } },
    {
      provide: ContactPolicyApi,
      useValue: {
        read: vi.fn().mockResolvedValue({
          platform: {
            quietHoursStartNoLaterThan: '21:00:00',
            quietHoursEndNoEarlierThan: '10:00:00',
            dailyCapCeiling: 3,
            weeklyCapCeiling: 3,
            rolling7DayCapCeiling: 3,
            rolling30DayCapCeiling: 8,
          },
          overrides: [],
        }),
        defaults: vi.fn().mockResolvedValue({
          channelPriorityOrder: [],
          inAppShowCapPerDay: 3,
          controlGroupPercentDefault: 10,
        }),
      },
    },
    { provide: ScenariosApi, useValue: { get: vi.fn(), create: vi.fn(), replaceSteps: vi.fn() } },
  ];

  it('lists a campaign with its channel and status', async () => {
    await render([campaign()]);
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelectorAll('[data-testid="campaign-row"]')).toHaveLength(1);
    expect(host.textContent).toContain('Autumn promotion');
  });

  // Row 10.12: the Formats card says it controls how operators read amounts across the console.
  it('writes a campaign’s cost estimate the way the brand chose', async () => {
    applyRegionalFormats({ moneySymbolPlacement: 'BEFORE', moneyGrouping: 'COMMA' });
    await render([campaign({ estimatedCostLowMinor: 42_000, estimatedCostHighMinor: 63_000 })]);
    const row = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="campaign-row"]',
    )!;

    expect(row.textContent?.replace(/\s/g, ' ')).toContain('UZS 42,000–63,000');
  });

  it('hints a maker that their own campaign is awaiting a second signature', async () => {
    await render([campaign({ status: 'IN_REVIEW' })]);
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('.row-hint')?.textContent?.toLowerCase()).toContain('awaiting');
  });

  it('6.4: hints that a scheduled send did not go out, without opening the campaign', async () => {
    await render([
      campaign({
        status: 'SCHEDULED',
        scheduledAt: null,
        haltedReason: 'channel unwired at due moment',
      }),
    ]);
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('[data-testid="campaign-row-halted-hint"]')).not.toBeNull();
  });

  it('6.4: shows no halted hint for a campaign armed and waiting, not halted', async () => {
    await render([campaign({ status: 'SCHEDULED', scheduledAt: '2026-10-01T10:00:00Z' })]);
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('[data-testid="campaign-row-halted-hint"]')).toBeNull();
  });

  it('T18: disables a channel option the read model says is not wired', async () => {
    await render(
      [],
      [
        {
          channel: 'SMS',
          carriesMarginalCost: true,
          isWired: false,
          notWiredReason: 'NO_DELIVERY_ADAPTER',
        },
        {
          channel: 'MESSAGING_APP',
          carriesMarginalCost: false,
          isWired: true,
          notWiredReason: null,
        },
      ],
    );
    (fixture.componentInstance as unknown as { openCreateCampaign(): void }).openCreateCampaign();
    fixture.detectChanges();

    // The form opens on a channel that can deliver, so the one that cannot has to be picked
    // for its hint to show.
    (
      fixture.componentInstance as unknown as { newCampaignChannel: { set(v: string): void } }
    ).newCampaignChannel.set('SMS');
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    const select = host.querySelector<HTMLSelectElement>(
      '[data-testid="campaign-channel-select"]',
    )!;
    const smsOption = Array.from(select.options).find((o) => o.value === 'SMS')!;
    expect(smsOption.disabled).toBe(true);
    expect(host.querySelector('[data-testid="channel-unwired-hint"]')).not.toBeNull();
  });

  it('T18: sends scheduledAt as an ISO instant when a create form value is set', async () => {
    await render([]);
    const api2 = api as unknown as {
      createCampaign: ReturnType<typeof vi.fn>;
      listTemplates: ReturnType<typeof vi.fn>;
    };
    api2.createCampaign = vi.fn().mockResolvedValue(campaign());

    const component = fixture.componentInstance as unknown as {
      openCreateCampaign(): void;
      newCampaignName: { set(v: string): void };
      newCampaignChannel: { set(v: string): void };
      newCampaignTemplateKey: { set(v: string): void };
      newCampaignScheduledAt: { set(v: string): void };
      submitCreateCampaign(): Promise<void>;
    };
    component.openCreateCampaign();
    fixture.detectChanges();
    component.newCampaignName.set('A scheduled send');
    // MESSAGING_APP carries no marginal cost, so this test needs no cost
    // ceiling to make canCreateCampaign() true.
    component.newCampaignChannel.set('MESSAGING_APP');
    component.newCampaignTemplateKey.set('MARKETING_PROMOTION');
    component.newCampaignScheduledAt.set('2026-10-01T10:00');
    await component.submitCreateCampaign();

    expect(api2.createCampaign).toHaveBeenCalledWith(
      SCOPE,
      expect.objectContaining({ scheduledAt: new Date('2026-10-01T10:00').toISOString() }),
    );
  });

  it('T18: switching to the courier broadcasts tab lists broadcasts and can send a draft', async () => {
    await render([]);
    const api2 = api as unknown as {
      listCourierBroadcasts: ReturnType<typeof vi.fn>;
      sendCourierBroadcast: ReturnType<typeof vi.fn>;
    };
    const broadcast = {
      broadcastId: 'broadcast-1',
      channel: 'SMS',
      targetKind: 'ALL_ACTIVE',
      targetGroupId: null,
      message: 'Shift change at 18:00',
      status: 'DRAFT',
      recipientCount: 0,
      refusalReason: null,
      createdBy: 'actor-1',
      createdAt: '2026-09-14T08:00:00Z',
      sentAt: null,
    };
    api2.listCourierBroadcasts = vi
      .fn()
      .mockResolvedValueOnce([broadcast])
      .mockResolvedValueOnce([
        { ...broadcast, status: 'SENT', recipientCount: 3, sentAt: '2026-09-14T09:00:00Z' },
      ]);
    api2.sendCourierBroadcast = vi.fn().mockResolvedValue({ ...broadcast, status: 'SENT' });

    const host = fixture.nativeElement as HTMLElement;
    host.querySelector<HTMLButtonElement>('[data-testid="tab-courier-broadcasts"]')!.click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.querySelectorAll('[data-testid="courier-broadcast-row"]')).toHaveLength(1);
    expect(host.textContent).toContain('Shift change at 18:00');

    host.querySelector<HTMLButtonElement>('[data-testid="courier-broadcast-send"]')!.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api2.sendCourierBroadcast).toHaveBeenCalledWith(SCOPE, 'broadcast-1');
    expect(api2.listCourierBroadcasts).toHaveBeenCalledTimes(2);
  });

  it('T18: records a suppression from the suppressions tab and reloads the list', async () => {
    await render([]);
    const api2 = api as unknown as {
      suppress: ReturnType<typeof vi.fn>;
      listSuppressions: ReturnType<typeof vi.fn>;
    };
    api2.suppress = vi.fn().mockResolvedValue(undefined);
    api2.listSuppressions = vi.fn().mockResolvedValue([]);

    const component = fixture.componentInstance as unknown as {
      openRecordSuppression(): void;
      newSuppressionAccountId: { set(v: string): void };
      submitRecordSuppression(): Promise<void>;
    };
    component.openRecordSuppression();
    component.newSuppressionAccountId.set('11111111-1111-1111-1111-111111111111');
    await component.submitRecordSuppression();

    expect(api2.suppress).toHaveBeenCalledWith(
      SCOPE,
      expect.objectContaining({
        customerAccountId: '11111111-1111-1111-1111-111111111111',
        reason: 'UNSUBSCRIBE',
      }),
    );
  });

  it('T18: viewing an audience loads its predicates, and redefining sends the edited set', async () => {
    await render([]);
    const api2 = api as unknown as {
      getAudience: ReturnType<typeof vi.fn>;
      redefineAudience: ReturnType<typeof vi.fn>;
    };
    const detail = {
      audienceId: 'audience-1',
      name: 'Everybody registered',
      description: null,
      status: 'ACTIVE',
      definitionVersion: 1,
      createdBy: 'actor-1',
      createdAt: '2026-09-01T08:00:00Z',
      updatedAt: '2026-09-01T08:00:00Z',
      predicates: [{ type: 'ORDER_COUNT', operator: 'AT_LEAST', numericLow: 0, numericHigh: null }],
    };
    api2.getAudience = vi.fn().mockResolvedValue(detail);
    api2.redefineAudience = vi.fn().mockResolvedValue({ ...detail, definitionVersion: 2 });

    const component = fixture.componentInstance as unknown as {
      openAudienceDetail(a: AudienceSummary): Promise<void>;
      startEditingAudience(): void;
      submitRedefineAudience(): Promise<void>;
    };
    await component.openAudienceDetail(AUDIENCE);
    fixture.detectChanges();

    expect(api2.getAudience).toHaveBeenCalledWith(SCOPE, 'audience-1');
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="audience-detail-dialog"]')).not.toBeNull();

    component.startEditingAudience();
    await component.submitRedefineAudience();

    expect(api2.redefineAudience).toHaveBeenCalledWith(
      SCOPE,
      'audience-1',
      expect.objectContaining({
        predicates: [expect.objectContaining({ type: 'ORDER_COUNT', operator: 'AT_LEAST' })],
      }),
    );
  });

  // ------------------------------------------------------ ADR 0112: scenarios, offers, policy

  describe('scenario campaigns', () => {
    it('says which campaigns are broadcasts and which are scenarios', async () => {
      await render([
        campaign({ campaignId: 'b', name: 'One-off' }),
        campaign({ campaignId: 's', name: 'Win back', kind: 'SCENARIO', channel: 'MESSAGING_APP' }),
      ]);
      const rows = [
        ...(fixture.nativeElement as HTMLElement).querySelectorAll('[data-testid="campaign-row"]'),
      ].map((row) => row.textContent!);

      expect(rows[0]).toContain('Broadcast');
      expect(rows[1]).toContain('Scenario');
    });

    it('opens the scenario editor, not the broadcast form, from "New scenario"', async () => {
      await render([], CHANNELS, SIDE_APIS);
      const host = fixture.nativeElement as HTMLElement;

      host.querySelector<HTMLButtonElement>('[data-testid="new-scenario"]')!.click();
      await flushMicrotasks();
      fixture.detectChanges();

      expect(host.querySelector('q-scenario-editor')).not.toBeNull();
      expect(host.querySelector('[data-testid="campaign-channel-select"]')).toBeNull();
      // The list and the tabs give way to the editor: one thing at a time.
      expect(host.querySelector('[data-testid="campaign-row"]')).toBeNull();
    });

    it('goes back to the list when the editor is abandoned', async () => {
      await render([campaign()], CHANNELS, SIDE_APIS);
      const host = fixture.nativeElement as HTMLElement;
      host.querySelector<HTMLButtonElement>('[data-testid="new-scenario"]')!.click();
      await flushMicrotasks();
      fixture.detectChanges();

      host.querySelector<HTMLButtonElement>('[data-testid="scenario-cancel"]')!.click();
      await flushMicrotasks();
      fixture.detectChanges();

      expect(host.querySelector('q-scenario-editor')).toBeNull();
      expect(host.querySelector('[data-testid="campaign-row"]')).not.toBeNull();
    });

    it('opens the scenario editor on a draft when the address says so, and the new-scenario form on "new"', async () => {
      await render([campaign()], CHANNELS, SIDE_APIS);
      const router = TestBed.inject(Router);

      await router.navigateByUrl('/?scenario=new');
      fixture.detectChanges();
      await flushMicrotasks();
      fixture.detectChanges();
      expect(
        (fixture.nativeElement as HTMLElement).querySelector('q-scenario-editor'),
      ).not.toBeNull();
    });

    it('opens the editor on an existing draft when the address names it', async () => {
      const get = vi.fn().mockResolvedValue({
        campaign: {
          campaignId: 'c-9',
          name: 'Win back',
          status: 'DRAFT',
          consentPurpose: 'MARKETING_PROMOTIONS',
          controlGroupPercent: null,
          supersedesCampaignId: null,
          createdAt: '2026-10-01T00:00:00Z',
        },
        steps: [],
        participants: {},
        decisions: {},
      });
      await render([], CHANNELS, [...SIDE_APIS, { provide: ScenariosApi, useValue: { get } }]);

      await TestBed.inject(Router).navigateByUrl('/?scenario=c-9');
      fixture.detectChanges();
      await flushMicrotasks();
      fixture.detectChanges();

      expect(get).toHaveBeenCalledWith(SCOPE, 'c-9');
      expect(
        (fixture.nativeElement as HTMLElement).querySelector('q-scenario-editor'),
      ).not.toBeNull();
    });

    it('moves to the saved scenario, so it can be estimated and sent for approval', async () => {
      await render([], CHANNELS, SIDE_APIS);
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
      const host = fixture.nativeElement as HTMLElement;
      host.querySelector<HTMLButtonElement>('[data-testid="new-scenario"]')!.click();
      await flushMicrotasks();
      fixture.detectChanges();

      (
        fixture.componentInstance as unknown as { onScenarioSaved(id: string): void }
      ).onScenarioSaved('s-1');

      expect(navigate).toHaveBeenCalledWith(['/marketing/campaigns', 's-1']);
    });
  });

  describe('offers and contact policy', () => {
    it('has a tab for each, beside audiences and suppressions', async () => {
      await render([]);
      const host = fixture.nativeElement as HTMLElement;

      expect(host.querySelector('[data-testid="tab-offers"]')).not.toBeNull();
      expect(host.querySelector('[data-testid="tab-contact-policy"]')).not.toBeNull();
    });

    it('switches tab without leaving the address it is on: a docked campaign stays open', async () => {
      await render([], CHANNELS, SIDE_APIS);
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

      (fixture.nativeElement as HTMLElement)
        .querySelector<HTMLButtonElement>('[data-testid="tab-offers"]')!
        .click();

      expect(navigate).not.toHaveBeenCalled();
    });

    it('shows the offers screen on its tab', async () => {
      await render([], CHANNELS, SIDE_APIS);
      const host = fixture.nativeElement as HTMLElement;

      host.querySelector<HTMLButtonElement>('[data-testid="tab-offers"]')!.click();
      fixture.detectChanges();
      await flushMicrotasks();
      fixture.detectChanges();

      expect(host.querySelector('q-offers-panel')).not.toBeNull();
      expect(host.querySelector('[data-testid="offers-create"]')).not.toBeNull();
    });

    it('shows the contact policy on its tab, with its explainer of why a guest is blocked', async () => {
      await render([], CHANNELS, SIDE_APIS);
      const host = fixture.nativeElement as HTMLElement;

      host.querySelector<HTMLButtonElement>('[data-testid="tab-contact-policy"]')!.click();
      fixture.detectChanges();
      await flushMicrotasks();
      fixture.detectChanges();

      expect(host.querySelector('q-contact-policy-panel')).not.toBeNull();
      expect(host.querySelector('[data-testid="policy-explainer"]')).not.toBeNull();
    });

    it('opens on the tab the address names, so another screen can link to one', async () => {
      await render([], CHANNELS, SIDE_APIS);
      await TestBed.inject(Router).navigateByUrl('/?view=contactPolicy');
      fixture.detectChanges();
      await flushMicrotasks();
      fixture.detectChanges();

      expect(
        (fixture.nativeElement as HTMLElement).querySelector('q-contact-policy-panel'),
      ).not.toBeNull();
    });
  });

  describe('channels, honestly', () => {
    const GATED: readonly ChannelView[] = [
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

    it('opens the broadcast form on a channel that can deliver, not on SMS when SMS is gated', async () => {
      await render([], GATED);
      (fixture.componentInstance as unknown as { openCreateCampaign(): void }).openCreateCampaign();
      fixture.detectChanges();

      const select = (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>(
        '[data-testid="campaign-channel-select"]',
      )!;
      expect(select.value).toBe('MESSAGING_APP');
    });

    it('explains the SMS gate when SMS is picked, not just that it is unwired', async () => {
      await render([], GATED);
      (fixture.componentInstance as unknown as { openCreateCampaign(): void }).openCreateCampaign();
      fixture.detectChanges();
      (
        fixture.componentInstance as unknown as { newCampaignChannel: { set(v: string): void } }
      ).newCampaignChannel.set('SMS');
      fixture.detectChanges();

      const hint = (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="channel-unwired-hint"]',
      )!;
      expect(hint.textContent).toContain('not cleared to carry marketing messages');
    });

    it('says email and push are not connected, each in its own words', async () => {
      await render([], GATED);
      (fixture.componentInstance as unknown as { openCreateCampaign(): void }).openCreateCampaign();
      fixture.detectChanges();
      const component = fixture.componentInstance as unknown as {
        newCampaignChannel: { set(v: string): void };
      };
      const hint = () =>
        (fixture.nativeElement as HTMLElement).querySelector(
          '[data-testid="channel-unwired-hint"]',
        )!.textContent;

      component.newCampaignChannel.set('EMAIL');
      fixture.detectChanges();
      expect(hint()).toContain('mail service sends staff invitations');

      component.newCampaignChannel.set('PUSH');
      fixture.detectChanges();
      expect(hint()).toContain('no push provider');
    });

    it('marks each unconnected channel in the list itself', async () => {
      await render([], GATED);
      (fixture.componentInstance as unknown as { openCreateCampaign(): void }).openCreateCampaign();
      fixture.detectChanges();

      const options = [
        ...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLOptionElement>(
          '[data-testid="campaign-channel-select"] option',
        ),
      ];
      expect(options.find((o) => o.value === 'SMS')!.textContent).toContain('not connected');
      expect(options.find((o) => o.value === 'MESSAGING_APP')!.textContent).not.toContain(
        'not connected',
      );
    });
  });

  it('shows the denied state when the brand grant is missing', async () => {
    api = {
      listCampaigns: vi.fn(),
      listAudiences: vi.fn(),
      listSuppressions: vi.fn(),
    };
    await TestBed.configureTestingModule({
      imports: [CampaignsPage],
      providers: [
        provideRouter([]),
        { provide: MarketingApi, useValue: api },
        {
          provide: CurrentBrand,
          useValue: {
            scope: signal<BrandScope | null>(null),
            denied: signal(true),
            ensureLoaded: () => Promise.resolve(),
          },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(CampaignsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="campaigns-denied"]'),
    ).not.toBeNull();
  });
});

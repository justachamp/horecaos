import { Provider, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../../core/api/catalog-paths';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { ApiError } from '../../../core/api/problem-details';
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
  { channel: 'EMAIL', carriesMarginalCost: false, isWired: true, notWiredReason: null },
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

  it('hints the languages a customer can be written to from the registry, not from a list of its own (ADR 0149)', async () => {
    await render([campaign()]);
    const page = fixture.componentInstance as unknown as {
      fixedValuesOfRow(row: { type: string }): readonly string[] | null;
    };

    expect(page.fixedValuesOfRow({ type: 'PREFERRED_LOCALE' })).toEqual(['ru', 'uz-Latn', 'en']);
    // A predicate with no closed set of its own has no hint.
    expect(page.fixedValuesOfRow({ type: 'ORDER_COUNT' })).toBeNull();
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

  // ------------------------------------------------ courier broadcasts (ADR 0182, D15)

  const BROADCAST = {
    broadcastId: 'broadcast-1',
    channel: 'SMS',
    targetKind: 'ALL_ACTIVE',
    targetGroupId: null,
    message: null,
    templateKey: 'COURIER_SHIFT_CHANGE',
    variables: { note: 'Shift change', time: '18:00' },
    status: 'DRAFT',
    recipientCount: 0,
    refusalReason: null,
    createdBy: 'actor-1',
    createdAt: '2026-09-14T08:00:00Z',
    sentAt: null,
  } as const;

  const READY = {
    isWired: true,
    reason: null,
    templates: [
      {
        templateKey: 'COURIER_SHIFT_CHANGE',
        variables: ['note', 'time'],
        bodiesByLocale: { ru: 'Смена: {{note}} в {{time}}', en: 'Shift: {{note}} at {{time}}' },
        awaitingGateway: false,
      },
      {
        templateKey: 'COURIER_ROAD_CLOSED',
        variables: ['place'],
        bodiesByLocale: { en: 'Road closed: {{place}}' },
        awaitingGateway: true,
      },
    ],
  } as const;

  type CourierApi = {
    listCourierBroadcasts: ReturnType<typeof vi.fn>;
    sendCourierBroadcast: ReturnType<typeof vi.fn>;
    draftCourierBroadcast: ReturnType<typeof vi.fn>;
    courierReadiness: ReturnType<typeof vi.fn>;
    courierBroadcastRecipients: ReturnType<typeof vi.fn>;
  };

  async function openCourierTab(setup: (api: CourierApi) => void): Promise<HTMLElement> {
    await render([]);
    const courier = api as unknown as CourierApi;
    courier.listCourierBroadcasts = vi.fn().mockResolvedValue([BROADCAST]);
    courier.courierReadiness = vi.fn().mockResolvedValue(READY);
    courier.sendCourierBroadcast = vi.fn().mockResolvedValue({ ...BROADCAST, status: 'SENT' });
    courier.draftCourierBroadcast = vi.fn().mockResolvedValue(BROADCAST);
    courier.courierBroadcastRecipients = vi.fn().mockResolvedValue([]);
    setup(courier);
    const host = fixture.nativeElement as HTMLElement;
    host.querySelector<HTMLButtonElement>('[data-testid="tab-courier-broadcasts"]')!.click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
    return host;
  }

  it('T18: switching to the courier broadcasts tab lists broadcasts and can send a draft', async () => {
    const host = await openCourierTab((api) => {
      api.listCourierBroadcasts = vi
        .fn()
        .mockResolvedValueOnce([BROADCAST])
        .mockResolvedValueOnce([
          { ...BROADCAST, status: 'SENT', recipientCount: 3, sentAt: '2026-09-14T09:00:00Z' },
        ]);
    });
    const courier = api as unknown as CourierApi;

    expect(host.querySelectorAll('[data-testid="courier-broadcast-row"]')).toHaveLength(1);
    expect(host.querySelector('[data-testid="courier-broadcast-wording"]')?.textContent).toContain(
      'COURIER_SHIFT_CHANGE: Shift change · 18:00',
    );

    host.querySelector<HTMLButtonElement>('[data-testid="courier-broadcast-send"]')!.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(courier.sendCourierBroadcast).toHaveBeenCalledWith(SCOPE, 'broadcast-1');
    expect(courier.listCourierBroadcasts).toHaveBeenCalledTimes(2);
  });

  it('6.4b: says in a sentence, before anything is drafted, why a courier SMS cannot leave for this brand', async () => {
    const host = await openCourierTab((courier) => {
      courier.courierReadiness = vi
        .fn()
        .mockResolvedValue({ ...READY, isWired: false, reason: 'COURIER_CONTACTS_NOT_WIRED' });
    });

    const banner = host.querySelector('[data-testid="courier-wiring-problem"]') as HTMLElement;
    expect(banner).not.toBeNull();
    expect(banner.textContent).toContain('Courier SMS cannot be sent for this brand yet');
    expect(banner.textContent).toContain('may not yet use a courier’s own number');
  });

  it('6.4b: names the gateway account when it is not cleared for courier traffic', async () => {
    const host = await openCourierTab((courier) => {
      courier.courierReadiness = vi
        .fn()
        .mockResolvedValue({ ...READY, isWired: false, reason: 'SMS_PURPOSE_NOT_PERMITTED' });
    });

    expect(host.querySelector('[data-testid="courier-wiring-problem"]')?.textContent).toContain(
      'not cleared to carry courier messages',
    );
  });

  it('6.4b: says nothing when a courier SMS can leave', async () => {
    const host = await openCourierTab(() => undefined);

    expect(host.querySelector('[data-testid="courier-wiring-problem"]')).toBeNull();
  });

  it('6.4b: a refused broadcast is explained in words, not as the English sentence the server recorded', async () => {
    const host = await openCourierTab((courier) => {
      courier.listCourierBroadcasts = vi.fn().mockResolvedValue([
        {
          ...BROADCAST,
          status: 'FAILED',
          refusalReason:
            'No SMS delivery path can reach couriers for this brand (COURIER_CONTACTS_NOT_WIRED)',
        },
      ]);
    });

    const hint = host.querySelector('[data-testid="courier-broadcast-refusal"]') as HTMLElement;
    expect(hint.textContent).toContain('may not yet use a courier’s own number');
    expect(hint.textContent).not.toContain('No SMS delivery path can reach couriers');
  });

  it('6.4b: a broadcast is drafted from an approved wording and its variables, never free text', async () => {
    const host = await openCourierTab(() => undefined);
    const courier = api as unknown as CourierApi;
    const component = fixture.componentInstance as unknown as {
      openCreateBroadcast(): void;
      chooseBroadcastTemplate(key: string): void;
      setBroadcastValue(name: string, value: string): void;
      canCreateBroadcast(): boolean;
      submitCreateBroadcast(): Promise<void>;
    };

    component.openCreateBroadcast();
    fixture.detectChanges();
    expect(host.querySelector('textarea')).toBeNull();
    const options = Array.from(
      host.querySelectorAll<HTMLOptionElement>('[data-testid="courier-template"] option'),
    );
    expect(options.map((option) => option.value)).toEqual([
      '',
      'COURIER_SHIFT_CHANGE',
      'COURIER_ROAD_CLOSED',
    ]);
    expect(options[2].disabled).toBe(true);
    expect(options[2].textContent).toContain('waiting for the SMS gateway’s approval');

    component.chooseBroadcastTemplate('COURIER_SHIFT_CHANGE');
    fixture.detectChanges();
    expect(component.canCreateBroadcast()).toBe(false);
    component.setBroadcastValue('note', 'Shift change');
    expect(component.canCreateBroadcast()).toBe(false);
    component.setBroadcastValue('time', '18:00');
    fixture.detectChanges();
    expect(component.canCreateBroadcast()).toBe(true);
    expect(host.querySelector('[data-testid="courier-preview"]')?.textContent).toContain(
      'Shift: Shift change at 18:00',
    );

    await component.submitCreateBroadcast();

    expect(courier.draftCourierBroadcast).toHaveBeenCalledWith(SCOPE, {
      targetKind: 'ALL_ACTIVE',
      targetGroupId: null,
      templateKey: 'COURIER_SHIFT_CHANGE',
      variables: { note: 'Shift change', time: '18:00' },
    });
  });

  it('6.4b: a value over eighty characters, or on two lines, cannot be drafted; nor can a wording the gateway has not approved', async () => {
    await openCourierTab(() => undefined);
    const component = fixture.componentInstance as unknown as {
      chooseBroadcastTemplate(key: string): void;
      setBroadcastValue(name: string, value: string): void;
      canCreateBroadcast(): boolean;
    };

    component.chooseBroadcastTemplate('COURIER_SHIFT_CHANGE');
    component.setBroadcastValue('time', '18:00');
    component.setBroadcastValue('note', 'x'.repeat(81));
    expect(component.canCreateBroadcast()).toBe(false);
    component.setBroadcastValue('note', 'line one\nline two');
    expect(component.canCreateBroadcast()).toBe(false);
    component.setBroadcastValue('note', 'fine');
    expect(component.canCreateBroadcast()).toBe(true);

    component.chooseBroadcastTemplate('COURIER_ROAD_CLOSED');
    component.setBroadcastValue('place', 'Amir Temur');
    expect(component.canCreateBroadcast()).toBe(false);
  });

  it('6.4b: with no approved wording the dialog says how to get one instead of offering an empty form', async () => {
    const host = await openCourierTab((courier) => {
      courier.courierReadiness = vi.fn().mockResolvedValue({ ...READY, templates: [] });
    });
    (fixture.componentInstance as unknown as { openCreateBroadcast(): void }).openCreateBroadcast();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="courier-no-templates"]')?.textContent).toContain(
      'COURIER_DISPATCH',
    );
    expect(host.querySelector('[data-testid="courier-template"]')).toBeNull();
  });

  it('6.4b: after a send, each courier’s result is shown by reference, with the reason a courier had no message', async () => {
    const host = await openCourierTab((courier) => {
      courier.listCourierBroadcasts = vi
        .fn()
        .mockResolvedValue([{ ...BROADCAST, status: 'SENT', recipientCount: 2 }]);
      courier.courierBroadcastRecipients = vi.fn().mockResolvedValue([
        {
          courierReference: 'K-001',
          state: 'HANDED_TO_OPERATOR',
          reason: null,
          receiptState: null,
          segmentsBilled: 1,
        },
        {
          courierReference: 'K-002',
          state: 'NO_CONTACT',
          reason: 'COURIER_HAS_NO_NUMBER',
          receiptState: null,
          segmentsBilled: null,
        },
        {
          courierReference: 'K-003',
          state: 'FAILED',
          reason: 'RECEIVER_UNREACHABLE',
          receiptState: null,
          segmentsBilled: 1,
        },
      ]);
    });

    host.querySelector<HTMLButtonElement>('[data-testid="courier-broadcast-results"]')!.click();
    await flushMicrotasks();
    fixture.detectChanges();

    const rows = Array.from(host.querySelectorAll('[data-testid="courier-result-row"]')).map(
      (row) => row.textContent ?? '',
    );
    expect((api as unknown as CourierApi).courierBroadcastRecipients).toHaveBeenCalledWith(
      SCOPE,
      'broadcast-1',
    );
    expect(rows).toHaveLength(3);
    expect(rows[0]).toContain('K-001');
    expect(rows[0]).toContain('Handed to the operator');
    expect(rows[1]).toContain('No number');
    expect(rows[1]).toContain('holds no number the platform sends to');
    expect(rows[2]).toContain('Failed');
    expect(rows[2]).toContain('cannot be reached');
    expect(host.textContent).not.toContain('+998');
  });

  it('6.4b: an identity provider that could not be asked is said as such and leaves the draft to send again', async () => {
    const host = await openCourierTab((courier) => {
      courier.sendCourierBroadcast = vi
        .fn()
        .mockRejectedValue(
          new ApiError('IDENTITY_PROVIDER_UNAVAILABLE', 503, { status: 503 }, null),
        );
    });

    host.querySelector<HTMLButtonElement>('[data-testid="courier-broadcast-send"]')!.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.textContent).toContain('could not be asked for the couriers’ numbers');
    expect(host.querySelector('[data-testid="courier-broadcast-send"]')).not.toBeNull();
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
        carriesMarginalCost: false,
        isWired: false,
        notWiredReason: 'EMAIL_NOT_CONFIGURED',
      },
      {
        channel: 'PUSH',
        carriesMarginalCost: false,
        isWired: false,
        notWiredReason: 'PUSH_NOT_CONFIGURED',
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

    it('says email and push are not set up on this deployment, each in its own words', async () => {
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
      expect(hint()).toContain('Email is not set up on this deployment');

      component.newCampaignChannel.set('PUSH');
      fixture.detectChanges();
      expect(hint()).toContain('Push is not set up on this deployment');
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

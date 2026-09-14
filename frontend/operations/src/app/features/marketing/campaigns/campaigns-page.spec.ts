import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../../core/api/catalog-paths';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { AudienceSummary, CampaignView, ChannelView, MarketingApi } from '../marketing-api';
import { CampaignsPage } from './campaigns-page';

const SCOPE: BrandScope = { tenantId: 'tenant-1', brandId: 'brand-1' };

/** T18: isWired true for every channel this fixture knows, unless a test overrides it. */
const CHANNELS: readonly ChannelView[] = [
  { channel: 'SMS', carriesMarginalCost: true, isWired: true },
  { channel: 'EMAIL', carriesMarginalCost: true, isWired: true },
  { channel: 'PUSH', carriesMarginalCost: false, isWired: true },
  { channel: 'MESSAGING_APP', carriesMarginalCost: false, isWired: true },
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
    isWired: true,
    createdAt: '2026-09-01T08:00:00Z',
    updatedAt: '2026-09-01T08:00:00Z',
    version: 1,
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

  async function render(
    campaigns: readonly CampaignView[],
    channels: readonly ChannelView[] = CHANNELS,
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
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(CampaignsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('lists a campaign with its channel and status', async () => {
    await render([campaign()]);
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelectorAll('[data-testid="campaign-row"]')).toHaveLength(1);
    expect(host.textContent).toContain('Autumn promotion');
  });

  it('hints a maker that their own campaign is awaiting a second signature', async () => {
    await render([campaign({ status: 'IN_REVIEW' })]);
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('.row-hint')?.textContent?.toLowerCase()).toContain('awaiting');
  });

  it('T18: disables a channel option the read model says is not wired', async () => {
    await render(
      [],
      [
        { channel: 'SMS', carriesMarginalCost: true, isWired: false },
        { channel: 'MESSAGING_APP', carriesMarginalCost: false, isWired: true },
      ],
    );
    (fixture.componentInstance as unknown as { openCreateCampaign(): void }).openCreateCampaign();
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

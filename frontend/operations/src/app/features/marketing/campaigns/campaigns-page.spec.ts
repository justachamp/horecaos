import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../../core/api/catalog-paths';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { AudienceSummary, CampaignView, MarketingApi } from '../marketing-api';
import { CampaignsPage } from './campaigns-page';

const SCOPE: BrandScope = { tenantId: 'tenant-1', brandId: 'brand-1' };

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

  async function render(campaigns: readonly CampaignView[]): Promise<void> {
    api = {
      listCampaigns: vi.fn().mockResolvedValue(campaigns),
      listAudiences: vi.fn().mockResolvedValue([AUDIENCE]),
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

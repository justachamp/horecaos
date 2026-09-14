import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { Page } from '../../core/api/page';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { CustomerSummary, CustomersApi } from '../customers/customers-api';
import { CampaignView, MarketingApi, RecipientCountsView } from '../marketing/marketing-api';
import { CustomerDiscountHistory, MarketingReportApi } from './marketing-report-api';
import { MarketingReportPage } from './marketing-report-page';

const SCOPE: LocationScope = { tenantId: 'tenant-1', brandId: 'brand-1', locationId: 'location-1' };

const CUSTOMER: CustomerSummary = {
  id: 'customer-1',
  status: 'ACTIVE',
  displayName: 'Dilnoza Karimova',
  createdAt: '2026-08-20T09:00:00Z',
};

function history(overrides: Partial<CustomerDiscountHistory> = {}): CustomerDiscountHistory {
  return {
    redemptions: [
      {
        redemptionId: 'r-1',
        brandId: 'brand-1',
        couponId: 'coupon-1',
        codeHint: 'ME10',
        promotionId: 'promo-1',
        promotionName: 'Welcome 10%',
        orderId: 'order-1',
        status: 'REDEEMED',
        amountMinor: 5_000,
        currency: 'UZS',
        reservedAt: '2026-09-01T10:00:00Z',
        redeemedAt: '2026-09-01T10:00:00Z',
        releasedAt: null,
      },
    ],
    totalsRedeemed: [{ currency: 'UZS', amountMinor: 5_000 }],
    ...overrides,
  };
}

const CAMPAIGN: CampaignView = {
  campaignId: 'campaign-1',
  name: 'September blast',
  channel: 'SMS',
  consentPurpose: 'MARKETING_PROMOTIONS',
  status: 'SENT',
  audienceId: 'audience-1',
  snapshotId: 'snapshot-1',
  templateKey: 'MARKETING_PROMOTION',
  timezone: 'Asia/Tashkent',
  recipientCap: 1000,
  estimatedRecipients: 500,
  estimatedCostLowMinor: null,
  estimatedCostHighMinor: null,
  estimatedDeliverySeconds: null,
  costCeilingMinor: null,
  reservedCostMinor: 0,
  spentCostMinor: 0,
  reservedRecipients: 0,
  currency: 'UZS',
  benefitOfferId: null,
  loyaltyAccrualRuleId: null,
  createdBy: 'creator-1',
  approvedBy: null,
  blockedCount: 0,
  pausedAt: null,
  createdAt: '2026-09-01T00:00:00Z',
  updatedAt: '2026-09-02T00:00:00Z',
  version: 1,
};

const COUNTS: RecipientCountsView = { pending: 1, queued: 40, deferred: 2, refused: 3, total: 46 };

class FakeCurrentLocation {
  readonly scope = signal<LocationScope | null>(SCOPE);
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

/** Past `Combobox.DEBOUNCE_MS` (250ms) — the `search` output this page listens on. */
async function waitForSearchDebounce(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 300));
}

describe('MarketingReportPage', () => {
  let fixture: ComponentFixture<MarketingReportPage>;
  let customersApi: { list: ReturnType<typeof vi.fn> };
  let marketingApi: {
    listCampaigns: ReturnType<typeof vi.fn>;
    recipientCounts: ReturnType<typeof vi.fn>;
  };
  let discountApi: { discountHistory: ReturnType<typeof vi.fn> };

  async function render(scope: LocationScope | null = SCOPE): Promise<void> {
    customersApi = {
      list: vi
        .fn()
        .mockResolvedValue({ items: [CUSTOMER], nextCursor: null } satisfies Page<CustomerSummary>),
    };
    marketingApi = {
      listCampaigns: vi.fn().mockResolvedValue([CAMPAIGN]),
      recipientCounts: vi.fn().mockResolvedValue(COUNTS),
    };
    discountApi = { discountHistory: vi.fn().mockResolvedValue(history()) };

    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [MarketingReportPage],
      providers: [
        {
          provide: CurrentLocation,
          useValue: new (class {
            readonly scope = signal<LocationScope | null>(scope);
            readonly denied = signal(scope === null);
            ensureLoaded = vi.fn().mockResolvedValue(undefined);
          })(),
        },
        { provide: CustomersApi, useValue: customersApi },
        { provide: MarketingApi, useValue: marketingApi },
        { provide: MarketingReportApi, useValue: discountApi },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(MarketingReportPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('names the deferred promo-code summary rather than rendering an empty tab', async () => {
    await render();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Promo-code summary');
  });

  it('row 7.9a: finds a customer through the combobox and shows their discount history and total', async () => {
    await render();
    const host: HTMLElement = fixture.nativeElement;
    const combobox = host.querySelector('[data-testid="marketing-report-customer-search"]')!;
    const search = combobox.querySelector('[data-testid="q-combobox-input"]') as HTMLInputElement;
    search.value = 'Karimova';
    search.dispatchEvent(new Event('input'));
    await waitForSearchDebounce();
    fixture.detectChanges();

    expect(customersApi.list).toHaveBeenCalledWith(
      SCOPE,
      { cursor: null, limit: 8 },
      { status: undefined, query: 'Karimova' },
    );

    search.dispatchEvent(new Event('focus'));
    fixture.detectChanges();
    (combobox.querySelector('[data-testid="q-combobox-option"]') as HTMLElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(discountApi.discountHistory).toHaveBeenCalledWith('tenant-1', 'customer-1');
    const text = host.textContent ?? '';
    expect(text).toContain('Dilnoza Karimova');
    expect(text).toContain('Welcome 10%');
    expect(text).toContain('ME10');
  });

  it('row 7.9a: a customer with no redemptions shows the empty state, not a zero row', async () => {
    await render();
    discountApi.discountHistory.mockResolvedValue(history({ redemptions: [], totalsRedeemed: [] }));
    const host: HTMLElement = fixture.nativeElement;
    const combobox = host.querySelector('[data-testid="marketing-report-customer-search"]')!;
    const search = combobox.querySelector('[data-testid="q-combobox-input"]') as HTMLInputElement;
    search.value = 'Karimova';
    search.dispatchEvent(new Event('input'));
    await waitForSearchDebounce();
    fixture.detectChanges();
    search.dispatchEvent(new Event('focus'));
    fixture.detectChanges();
    (combobox.querySelector('[data-testid="q-combobox-option"]') as HTMLElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.textContent).toContain('never redeemed a coupon');
    expect(host.querySelector('[data-testid="marketing-report-discount-totals"]')).toBeNull();
  });

  it('row 7.9b: lists campaigns and, once one is picked, shows its recipient counts and the read-receipts caveat', async () => {
    await render();
    const host: HTMLElement = fixture.nativeElement;
    const campaignsTab = Array.from(host.querySelectorAll('[role="tab"]')).find(
      (el) => el.textContent?.trim() === 'Campaigns',
    ) as HTMLButtonElement;
    campaignsTab.click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(marketingApi.listCampaigns).toHaveBeenCalledWith(SCOPE);
    expect(host.textContent).toContain('September blast');

    (host.querySelector('[data-testid="marketing-report-campaign-row"]') as HTMLElement).click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(marketingApi.recipientCounts).toHaveBeenCalledWith(SCOPE, 'campaign-1');
    const counts = host.querySelector('[data-testid="marketing-report-recipient-counts"]');
    expect(counts?.textContent).toContain('40');
    expect(counts?.textContent).toContain('46');
    expect(host.textContent).toContain('Read receipts are not shown');
  });

  it('shows the "no access" state and never calls a campaign or customer read', async () => {
    await render(null);
    const host: HTMLElement = fixture.nativeElement;
    expect(host.textContent).toContain('No access to reports');
    expect(marketingApi.listCampaigns).not.toHaveBeenCalled();
  });
});

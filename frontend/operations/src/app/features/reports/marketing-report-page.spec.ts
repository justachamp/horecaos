import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { Page } from '../../core/api/page';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { CustomerSummary, CustomersApi } from '../customers/customers-api';
import { CampaignView, MarketingApi, RecipientCountsView } from '../marketing/marketing-api';
import {
  CustomerDiscountHistory,
  MarketingReportApi,
  PromotionRedemptionLog,
  PromotionSummary,
} from './marketing-report-api';
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
  scheduledAt: null,
  haltedReason: null,
  isWired: true,
  createdAt: '2026-09-01T00:00:00Z',
  updatedAt: '2026-09-02T00:00:00Z',
  version: 1,
};

const COUNTS: RecipientCountsView = {
  pending: 1,
  queued: 40,
  deferred: 2,
  refused: 3,
  total: 46,
  refusedByReason: { SUPPRESSED: 2, CONSENT_WITHHELD: 1 },
};

const PROVENANCE = {
  asOf: '2026-10-02T09:00:00Z',
  closedThrough: '2026-10-01',
  lastCloseCompletedAt: '2026-10-02T01:00:00Z',
  businessDayStart: '04:00',
  timezone: 'Asia/Tashkent',
  boundaryVersion: 1,
  metricVersions: [],
  provisionalMetrics: [],
  openDivergences: 0,
};

const PROMOTION_SUMMARY: PromotionSummary = {
  rows: [
    {
      brandId: 'brand-1',
      promotionId: 'promo-click',
      promotionCode: 'CLICK5',
      sourceKind: 'AUTOMATIC',
      redemptions: 12,
      uniqueCustomers: 9,
      discountSom: 54_000,
      markupSom: 0,
      revenueWithSom: 1_200_000,
      averageCheckWithSom: 100_000,
      averageCheckWithoutSom: 85_000,
    },
    {
      brandId: 'brand-1',
      promotionId: 'promo-save',
      promotionCode: 'SAVE10',
      sourceKind: 'COUPON',
      redemptions: 1,
      uniqueCustomers: 1,
      discountSom: 9_000,
      markupSom: 0,
      revenueWithSom: 90_000,
      averageCheckWithSom: 90_000,
      averageCheckWithoutSom: null,
    },
  ],
  provenance: PROVENANCE,
};

function promotionLog(promotionId: string | null): PromotionRedemptionLog {
  const all = [
    {
      redemptionId: 'red-1',
      businessDate: '2026-10-01',
      brandId: 'brand-1',
      promotionId: 'promo-click',
      promotionCode: 'CLICK5',
      definitionVersion: 2,
      sourceKind: 'AUTOMATIC',
      orderId: 'order-aaa',
      customerSubject: 'k1:0123456789abcdef',
      discountSom: 4_500,
      markupSom: 0,
      currency: 'UZS',
      redeemedAt: '2026-10-01T08:00:00Z',
      orderStatus: 'COMPLETED',
      channelCode: 'WEB',
    },
    {
      redemptionId: 'red-2',
      businessDate: '2026-10-01',
      brandId: 'brand-1',
      promotionId: 'promo-save',
      promotionCode: 'SAVE10',
      definitionVersion: 1,
      sourceKind: 'COUPON',
      orderId: 'order-bbb',
      customerSubject: null,
      discountSom: 9_000,
      markupSom: 0,
      currency: 'UZS',
      redeemedAt: '2026-10-01T09:00:00Z',
      orderStatus: 'CANCELLED',
      channelCode: null,
    },
  ];
  return {
    rows: promotionId === null ? all : all.filter((row) => row.promotionId === promotionId),
    provenance: PROVENANCE,
  };
}

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
  let discountApi: {
    discountHistory: ReturnType<typeof vi.fn>;
    promotionSummary: ReturnType<typeof vi.fn>;
    promotionRedemptions: ReturnType<typeof vi.fn>;
  };

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
    discountApi = {
      discountHistory: vi.fn().mockResolvedValue(history()),
      promotionSummary: vi.fn().mockResolvedValue(PROMOTION_SUMMARY),
      promotionRedemptions: vi
        .fn()
        .mockImplementation(async (_tenant: string, _range: unknown, promotionId: string | null) =>
          promotionLog(promotionId),
        ),
    };

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

  it('no longer says the promotion summary is deferred', async () => {
    await render();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).not.toContain('Promo-code summary and per-code redemption detail are not shown');
    expect(discountApi.promotionSummary).not.toHaveBeenCalled();
  });

  describe('row 7.9: the promotion summary and redemption log', () => {
    async function openPromotions(): Promise<HTMLElement> {
      await render();
      const host: HTMLElement = fixture.nativeElement;
      const tab = [...host.querySelectorAll<HTMLButtonElement>('[role="tab"]')].find((button) =>
        button.textContent?.includes('Promotions'),
      )!;
      tab.click();
      await flushMicrotasks();
      fixture.detectChanges();
      return host;
    }

    function rows(host: HTMLElement, testId: string): string[] {
      return [...host.querySelectorAll(`[data-testid="${testId}"]`)].map((row) =>
        (row.textContent ?? '').replace(/\s+/g, ' ').trim(),
      );
    }

    it('loads the week up to today for the operator’s brand, only once the tab is opened', async () => {
      await openPromotions();
      expect(discountApi.promotionSummary).toHaveBeenCalledTimes(1);
      const [tenantId, range, brandId] = discountApi.promotionSummary.mock.calls[0];
      expect(tenantId).toBe('tenant-1');
      expect(brandId).toBe('brand-1');
      const days = (new Date(range.to).getTime() - new Date(range.from).getTime()) / 86_400_000;
      expect(days).toBe(6);
      expect(discountApi.promotionRedemptions).toHaveBeenCalledTimes(1);
    });

    it('shows redemptions, unique customers, what was given, revenue and the two average checks per promotion', async () => {
      const host = await openPromotions();
      const cells = (index: number): string[] =>
        [
          ...host
            .querySelectorAll('[data-testid="promotion-summary-row"]')
            [index].querySelectorAll('td'),
        ].map((cell) => (cell.textContent ?? '').replace(/[\u00a0\u202f]/g, ' ').trim());
      expect(cells(0)).toEqual([
        'CLICK5',
        'Automatic',
        '12',
        '9',
        '54 000 UZS',
        '0 UZS',
        '1 200 000 UZS',
        '100 000 UZS',
        '85 000 UZS',
      ]);
      // No order without it to compare against: a dash, never a zero that reads as a figure.
      expect(cells(1)).toEqual([
        'SAVE10',
        'Promo code',
        '1',
        '1',
        '9 000 UZS',
        '0 UZS',
        '90 000 UZS',
        '90 000 UZS',
        '—',
      ]);
      expect(host.textContent).toContain('not an uplift');
    });

    it('lists every redemption with its order status, including a cancelled order', async () => {
      const host = await openPromotions();
      const log = rows(host, 'promotion-log-row');
      expect(log).toHaveLength(2);
      expect(log[0]).toContain('order-aaa');
      expect(log[0]).toContain('Completed');
      expect(log[0]).toContain('WEB');
      expect(log[1]).toContain('order-bbb');
      expect(log[1]).toContain('Cancelled');
    });

    it('shows only a short form of the customer pseudonym, the full value in the tooltip, and never an account id', async () => {
      const host = await openPromotions();
      const cell = host.querySelector<HTMLElement>('[data-testid="promotion-log-row"] td[title]')!;
      expect(cell.getAttribute('title')).toBe('k1:0123456789abcdef');
      expect(cell.textContent?.trim()).toBe('k1:0123456');
      expect(host.textContent).not.toContain('0123456789abcdef');
    });

    it('narrows the log to a clicked promotion and back to every promotion', async () => {
      const host = await openPromotions();
      host.querySelectorAll<HTMLElement>('[data-testid="promotion-summary-row"]')[1].click();
      await flushMicrotasks();
      fixture.detectChanges();
      expect(discountApi.promotionRedemptions.mock.calls[1][2]).toBe('promo-save');
      expect(rows(host, 'promotion-log-row')).toHaveLength(1);
      expect(host.textContent).toContain('Redemption log for SAVE10');

      [...host.querySelectorAll<HTMLButtonElement>('.log-title button')]
        .find((button) => button.textContent?.includes('every promotion'))!
        .click();
      await flushMicrotasks();
      fixture.detectChanges();
      expect(discountApi.promotionRedemptions.mock.calls[2][2]).toBeNull();
      expect(rows(host, 'promotion-log-row')).toHaveLength(2);
    });

    it('reloads both for another range and forgets the selected promotion', async () => {
      const host = await openPromotions();
      host.querySelectorAll<HTMLElement>('[data-testid="promotion-summary-row"]')[0].click();
      await flushMicrotasks();
      const start = host.querySelector<HTMLInputElement>('q-date-range-picker input[type="date"]')!;
      start.value = '2026-09-01';
      start.dispatchEvent(new Event('input'));
      await flushMicrotasks();
      fixture.detectChanges();
      const last = discountApi.promotionSummary.mock.calls.at(-1)!;
      expect(last[1].from).toBe('2026-09-01');
      expect(discountApi.promotionRedemptions.mock.calls.at(-1)![2]).toBeNull();
    });

    it('says so when nothing was redeemed in the range', async () => {
      await render();
      discountApi.promotionSummary.mockResolvedValue({ rows: [], provenance: PROVENANCE });
      discountApi.promotionRedemptions.mockResolvedValue({ rows: [], provenance: PROVENANCE });
      const host: HTMLElement = fixture.nativeElement;
      [...host.querySelectorAll<HTMLButtonElement>('[role="tab"]')]
        .find((button) => button.textContent?.includes('Promotions'))!
        .click();
      await flushMicrotasks();
      fixture.detectChanges();
      expect(host.querySelector('[data-testid="promotion-summary-empty"]')).not.toBeNull();
    });

    it('offers a retry when the report cannot be loaded', async () => {
      await render();
      discountApi.promotionSummary.mockRejectedValueOnce(new Error('boom'));
      const host: HTMLElement = fixture.nativeElement;
      [...host.querySelectorAll<HTMLButtonElement>('[role="tab"]')]
        .find((button) => button.textContent?.includes('Promotions'))!
        .click();
      await flushMicrotasks();
      fixture.detectChanges();
      expect(host.querySelector('.promotions-tab .error-band')).not.toBeNull();
      host.querySelector<HTMLButtonElement>('.promotions-tab .error-band button')!.click();
      await flushMicrotasks();
      fixture.detectChanges();
      expect(host.querySelector('[data-testid="promotion-summary"]')).not.toBeNull();
    });
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

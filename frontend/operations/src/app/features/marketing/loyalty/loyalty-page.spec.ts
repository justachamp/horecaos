import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../../core/api/catalog-paths';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { LocationsApi } from '../../settings/locations/locations-api';
import { SalesChannelsApi } from '../../settings/sales-channels/sales-channels-api';
import { AccrualRuleView, LoyaltyApi, RedemptionPolicyView } from './loyalty-api';
import { LoyaltyPage } from './loyalty-page';

const BRAND_SCOPE: BrandScope = { tenantId: 't1', brandId: 'b1' };

const DRAFT_RULE: AccrualRuleView = {
  id: 'rule-1',
  scopeType: 'BRAND',
  scopeId: null,
  rateBasisPoints: 300,
  maxAccrualMinor: 30_000,
  earnDelayHours: 24,
  lotLifetimeDays: 180,
  expiryWarningDays: 14,
  status: 'DRAFT',
  version: 1,
  validFrom: '2026-08-24T00:00:00Z',
  validUntil: null,
};

const ACTIVE_POLICY: RedemptionPolicyView = {
  id: 'policy-1',
  maxShareBasisPoints: 5_000,
  minOrderMinor: 50_000,
  excludesDeliveryFee: true,
  allowedChannels: [],
  status: 'ACTIVE',
  version: 1,
  validFrom: '2026-08-24T00:00:00Z',
  validUntil: null,
};

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

function fakeApi(overrides: Partial<LoyaltyApi> = {}): Partial<LoyaltyApi> {
  return {
    listAccrualRules: vi.fn().mockResolvedValue([]),
    listRedemptionPolicies: vi.fn().mockResolvedValue([]),
    liability: vi.fn().mockResolvedValue([]),
    draftAccrualRule: vi.fn(),
    activateAccrualRule: vi.fn(),
    retireAccrualRule: vi.fn(),
    draftRedemptionPolicy: vi.fn(),
    activateRedemptionPolicy: vi.fn(),
    retireRedemptionPolicy: vi.fn(),
    ...overrides,
  };
}

describe('LoyaltyPage', () => {
  let fixture: ComponentFixture<LoyaltyPage>;

  async function render(api: Partial<LoyaltyApi>): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [LoyaltyPage],
      providers: [
        {
          provide: CurrentBrand,
          useValue: {
            scope: signal<BrandScope | null>(BRAND_SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: LoyaltyApi, useValue: api },
        { provide: LocationsApi, useValue: { list: vi.fn().mockResolvedValue([]) } },
        { provide: SalesChannelsApi, useValue: { list: vi.fn().mockResolvedValue([]) } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(LoyaltyPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('lists an authored accrual rule and redemption policy', async () => {
    await render(
      fakeApi({
        listAccrualRules: vi.fn().mockResolvedValue([DRAFT_RULE]),
        listRedemptionPolicies: vi.fn().mockResolvedValue([ACTIVE_POLICY]),
      }),
    );
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelectorAll('[data-testid="accrual-rule-row"]')).toHaveLength(1);
    expect(host.textContent).toContain('3.00%');
    expect(host.querySelectorAll('[data-testid="redemption-policy-row"]')).toHaveLength(1);
    expect(host.textContent).toContain('50.00%');
  });

  it('always renders the honest not-built panels for deposit accounts and POS balance sync', async () => {
    await render(fakeApi());
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('[data-testid="loyalty-deposit-not-built"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="loyalty-pos-sync-not-built"]')).not.toBeNull();
    // ADR 0046's own decision is named, not just "not built".
    expect(host.textContent).toContain('ADR 0046');
  });

  it('shows the denied state when the brand grant is missing', async () => {
    await TestBed.configureTestingModule({
      imports: [LoyaltyPage],
      providers: [
        {
          provide: CurrentBrand,
          useValue: {
            scope: signal<BrandScope | null>(null),
            denied: signal(true),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: LoyaltyApi, useValue: fakeApi() },
        { provide: LocationsApi, useValue: { list: vi.fn().mockResolvedValue([]) } },
        { provide: SalesChannelsApi, useValue: { list: vi.fn().mockResolvedValue([]) } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(LoyaltyPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="loyalty-denied"]'),
    ).not.toBeNull();
  });

  it('drafts a new accrual rule from the form and reloads the list', async () => {
    const draftAccrualRule = vi.fn().mockResolvedValue({ ...DRAFT_RULE, id: 'rule-2' });
    const listAccrualRules = vi
      .fn()
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([{ ...DRAFT_RULE, id: 'rule-2' }]);
    await render(fakeApi({ draftAccrualRule, listAccrualRules }));

    const page = fixture.componentInstance;
    page['openAccrualForm']();
    fixture.detectChanges();

    // The default 0% rate is a valid but pointless rule; type a real one.
    page['accrualRatePercent'].set(3);
    page['accrualLotLifetimeDays'].set(180);
    page['accrualExpiryWarningDays'].set(14);
    expect(page['canSubmitAccrual']()).toBe(true);

    await page['submitAccrualForm']();
    fixture.detectChanges();

    expect(draftAccrualRule).toHaveBeenCalledWith(
      BRAND_SCOPE,
      expect.objectContaining({ scopeType: 'BRAND', scopeId: null, rateBasisPoints: 300 }),
    );
    expect(listAccrualRules).toHaveBeenCalledTimes(2);
    expect(page['showAccrualForm']()).toBe(false);
  });

  it('refuses to submit an accrual rule whose expiry warning does not fit inside the lot lifetime', async () => {
    await render(fakeApi());
    const page = fixture.componentInstance;
    page['openAccrualForm']();
    page['accrualLotLifetimeDays'].set(180);
    page['accrualExpiryWarningDays'].set(180);

    expect(page['canSubmitAccrual']()).toBe(false);
  });

  it('activates a drafted accrual rule and reloads the list', async () => {
    const activateAccrualRule = vi.fn().mockResolvedValue(undefined);
    const listAccrualRules = vi
      .fn()
      .mockResolvedValueOnce([DRAFT_RULE])
      .mockResolvedValueOnce([{ ...DRAFT_RULE, status: 'ACTIVE' }]);
    await render(fakeApi({ activateAccrualRule, listAccrualRules }));

    await fixture.componentInstance['activateAccrualRule'](DRAFT_RULE);
    fixture.detectChanges();

    expect(activateAccrualRule).toHaveBeenCalledWith(BRAND_SCOPE, 'rule-1');
    expect(listAccrualRules).toHaveBeenCalledTimes(2);
  });

  it('refuses a redemption share above 90% before it ever reaches the server', async () => {
    await render(fakeApi());
    const page = fixture.componentInstance;
    page['openRedemptionForm']();
    page['redemptionSharePercent'].set(95);

    expect(page['canSubmitRedemption']()).toBe(false);
  });
});

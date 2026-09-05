import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../../core/api/catalog-paths';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { ReferralProgramView, ReferralRedemptionView, ReferralsApi } from './referrals-api';
import { ReferralsPage } from './referrals-page';

const BRAND_SCOPE: BrandScope = { tenantId: 't1', brandId: 'b1' };

const DRAFT_PROGRAM: ReferralProgramView = {
  id: 'program-1',
  rewardShape: 'BOTH_SIDES',
  referrerRewardMinor: 10_000,
  refereeRewardMinor: 5_000,
  rewardCurrency: 'UZS',
  maxRewardedReferralsPerReferrer: null,
  redemptionWindowDays: 14,
  rewardLotLifetimeDays: 90,
  status: 'DRAFT',
  version: 1,
  validFrom: '2026-09-05T00:00:00Z',
  validUntil: null,
};

const REWARDED_REDEMPTION: ReferralRedemptionView = {
  id: 'redemption-1',
  referrerCustomerAccountId: 'referrer-account-uuid',
  refereeCustomerAccountId: 'referee-account-uuid',
  status: 'REWARDED',
  redeemedAt: '2026-09-01T00:00:00Z',
  expiresAt: '2026-09-15T00:00:00Z',
  qualifyingOrderId: 'order-1',
  rewardedAt: '2026-09-03T00:00:00Z',
  referrerRewardMinor: 10_000,
  refereeRewardMinor: 5_000,
  referrerPaid: true,
  refereePaid: true,
  referrerSkipReason: null,
};

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

function fakeApi(overrides: Partial<ReferralsApi> = {}): Partial<ReferralsApi> {
  return {
    listPrograms: vi.fn().mockResolvedValue([]),
    summary: vi.fn().mockResolvedValue(null),
    redemptions: vi.fn().mockResolvedValue([]),
    draftProgram: vi.fn(),
    activateProgram: vi.fn(),
    retireProgram: vi.fn(),
    ...overrides,
  };
}

describe('ReferralsPage', () => {
  let fixture: ComponentFixture<ReferralsPage>;

  async function render(api: Partial<ReferralsApi>): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [ReferralsPage],
      providers: [
        {
          provide: CurrentBrand,
          useValue: {
            scope: signal<BrandScope | null>(BRAND_SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: ReferralsApi, useValue: api },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(ReferralsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('lists an authored referral program', async () => {
    await render(fakeApi({ listPrograms: vi.fn().mockResolvedValue([DRAFT_PROGRAM]) }));
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelectorAll('[data-testid="program-row"]')).toHaveLength(1);
    // formatMoney always groups with U+00A0 (no-break space), never a comma.
    expect(host.textContent).toContain('10\u00A0000');
  });

  it('shows the redemptions actually happening at this brand, and the total points paid out', async () => {
    await render(
      fakeApi({
        redemptions: vi.fn().mockResolvedValue([REWARDED_REDEMPTION]),
        summary: vi.fn().mockResolvedValue({
          codesIssued: 3,
          pendingRedemptions: 1,
          rewardedRedemptions: 1,
          closedRedemptions: 0,
          pointsPaidOutMinor: 15_000,
        }),
      }),
    );
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelectorAll('[data-testid="redemption-row"]')).toHaveLength(1);
    // formatMoney always groups with U+00A0 (no-break space), never a comma.
    expect(host.querySelector('[data-testid="referrals-summary"]')?.textContent).toContain(
      '15\u00A0000',
    );
  });

  it('always renders the honest not-built panel for acquisition links', async () => {
    await render(fakeApi());
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('[data-testid="referrals-links-not-built"]')).not.toBeNull();
    // ADR 0044's own checklist item is named, not just "not built".
    expect(host.textContent).toContain('ADR 0044');
  });

  it('shows the denied state when the brand grant is missing', async () => {
    await TestBed.configureTestingModule({
      imports: [ReferralsPage],
      providers: [
        {
          provide: CurrentBrand,
          useValue: {
            scope: signal<BrandScope | null>(null),
            denied: signal(true),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: ReferralsApi, useValue: fakeApi() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(ReferralsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="referrals-denied"]'),
    ).not.toBeNull();
  });

  it('drafts a new referral program from the form and reloads the list', async () => {
    const draftProgram = vi.fn().mockResolvedValue({ ...DRAFT_PROGRAM, id: 'program-2' });
    const listPrograms = vi
      .fn()
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([{ ...DRAFT_PROGRAM, id: 'program-2' }]);
    await render(fakeApi({ draftProgram, listPrograms }));

    const page = fixture.componentInstance;
    page['openForm']();
    fixture.detectChanges();
    expect(page['canSubmit']()).toBe(true);

    await page['submitForm']();
    fixture.detectChanges();

    expect(draftProgram).toHaveBeenCalledWith(
      BRAND_SCOPE,
      expect.objectContaining({ rewardShape: 'BOTH_SIDES', referrerRewardMinor: 10_000 }),
    );
    expect(listPrograms).toHaveBeenCalledTimes(2);
    expect(page['showForm']()).toBe(false);
  });

  it('a REFERRER_ONLY draft carries no referee reward field and submits with zero', async () => {
    const draftProgram = vi.fn().mockResolvedValue({ ...DRAFT_PROGRAM, id: 'program-3' });
    await render(fakeApi({ draftProgram }));

    const page = fixture.componentInstance;
    page['openForm']();
    page['formShape'].set('REFERRER_ONLY');
    fixture.detectChanges();

    expect(page['canSubmit']()).toBe(true);
    await page['submitForm']();

    expect(draftProgram).toHaveBeenCalledWith(
      BRAND_SCOPE,
      expect.objectContaining({ rewardShape: 'REFERRER_ONLY', refereeRewardMinor: 0 }),
    );
  });

  it('activates a drafted program and reloads the list', async () => {
    const activateProgram = vi.fn().mockResolvedValue(undefined);
    const listPrograms = vi
      .fn()
      .mockResolvedValueOnce([DRAFT_PROGRAM])
      .mockResolvedValueOnce([{ ...DRAFT_PROGRAM, status: 'ACTIVE' }]);
    await render(fakeApi({ activateProgram, listPrograms }));

    await fixture.componentInstance['activateProgram'](DRAFT_PROGRAM);
    fixture.detectChanges();

    expect(activateProgram).toHaveBeenCalledWith(BRAND_SCOPE, 'program-1');
    expect(listPrograms).toHaveBeenCalledTimes(2);
  });

  it('a program with a positive referrer reward but no referee reward under BOTH_SIDES cannot be submitted', async () => {
    await render(fakeApi());
    const page = fixture.componentInstance;
    page['openForm']();
    page['formShape'].set('BOTH_SIDES');
    page['formRefereeRewardMinor'].set(0);

    expect(page['canSubmit']()).toBe(false);
  });
});

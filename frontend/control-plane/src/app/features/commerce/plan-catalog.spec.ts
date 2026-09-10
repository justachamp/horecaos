import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { SessionContextService } from '../../core/auth/session-context.service';
import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ru } from '../../core/i18n/messages.ru';
import { CommerceApi, EntitlementKeyView, PlanDetail, PlanVersionDetail } from './commerce-api';
import { PlanCatalog } from './plan-catalog';

const CONFIG: AppConfig = { apiBaseUrl: 'https://api.test.horecaos.uz', displayTimeZone: 'Asia/Tashkent' };

const LIVE: PlanVersionDetail = {
  planVersionId: 'v1',
  versionNumber: 1,
  price: { amountMinor: 9_000_000, currency: 'UZS' },
  billingPeriod: 'MONTHLY',
  status: 'ACTIVE',
  termsReference: 'TERMS-2026',
  createdBy: 'author-a',
  approvedBy: 'approver-b',
  activatedAt: '2026-09-01T09:00:00Z',
  entitlements: [
    {
      entitlementKey: 'locations.max_count',
      limit: 20,
      enabled: null,
      enforcementMode: 'SOFT',
      resetPeriod: 'NONE',
      warnThresholdBasisPoints: null,
      overageUnitPrice: { amountMinor: 250_000, currency: 'UZS' },
    },
  ],
  terms: {
    trialDays: 14,
    activationDeposit: { amountMinor: 500_000, currency: 'UZS' },
    termDiscounts: [{ termMonths: 12, discountBasisPoints: 1_000 }],
  },
};

const DRAFT: PlanVersionDetail = {
  ...LIVE,
  planVersionId: 'v2',
  versionNumber: 2,
  status: 'DRAFT',
  createdBy: 'colleague',
  approvedBy: null,
  activatedAt: null,
};

const NETWORK: PlanDetail = { planId: 'plan-1', code: 'NETWORK', name: 'Network', status: 'ACTIVE', versions: [DRAFT, LIVE] };

const KEYS: EntitlementKeyView[] = [
  { code: 'locations.max_count', counted: true, unit: 'location', defaultMode: 'METER_ONLY', resetPeriod: 'NONE' },
  { code: 'pos.integrations.enabled', counted: false, unit: 'feature', defaultMode: 'METER_ONLY', resetPeriod: 'NONE' },
];

class FakeCommerceApi {
  readonly listPlansWithDrafts = vi.fn<() => Promise<PlanDetail[]>>();
  readonly entitlementKeys = vi.fn<() => Promise<EntitlementKeyView[]>>().mockResolvedValue(KEYS);
  readonly createPlan = vi.fn().mockResolvedValue({ planId: 'plan-2' });
  readonly draftVersion = vi.fn().mockResolvedValue({ planVersionId: 'v3' });
  readonly activateVersion = vi.fn().mockResolvedValue(undefined);
}

describe('PlanCatalog', () => {
  let fixture: ComponentFixture<PlanCatalog>;
  let api: FakeCommerceApi;

  async function create(subject: string, plans: PlanDetail[] = [NETWORK]): Promise<void> {
    api = new FakeCommerceApi();
    api.listPlansWithDrafts.mockResolvedValue(plans);
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [PlanCatalog],
      providers: [
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: CommerceApi, useValue: api },
        { provide: SessionContextService, useValue: { has: () => true, current: () => ({ subject }) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(PlanCatalog);
    await settle();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
  }

  function el<T extends HTMLElement>(selector: string): T {
    return fixture.nativeElement.querySelector(selector) as T;
  }

  function button(label: string): HTMLButtonElement {
    const found = (Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[]).find(
      (candidate) => candidate.textContent?.trim() === label,
    );
    if (!found) {
      throw new Error(`no button "${label}"`);
    }
    return found;
  }

  async function type(selector: string, value: string, event = 'input'): Promise<void> {
    const input = el<HTMLInputElement>(selector);
    input.value = value;
    input.dispatchEvent(new Event(event));
    await settle();
  }

  it('shows a draft beside the live version it would replace, with who drafted and who approved', async () => {
    await create('someone-else');

    const rows = Array.from(fixture.nativeElement.querySelectorAll('.plan tbody tr')) as HTMLElement[];
    expect(rows[0].textContent).toContain('v2');
    expect(rows[0].textContent).toContain(ru['planCatalog.status.DRAFT']);
    expect(rows[0].textContent).toContain('colleague');
    expect(rows[1].textContent).toContain(ru['planCatalog.status.ACTIVE']);
    expect(rows[1].textContent).toContain('approver-b');
    expect(rows[1].textContent).toContain('9 000 000');
  });

  it('will not offer the author their own draft to activate', async () => {
    await create('colleague');

    button(ru['planCatalog.activate.open']).click();
    await settle();

    expect(fixture.nativeElement.textContent).toContain(ru['planCatalog.activate.ownDraft']);
    expect(el('.activateConfirm')).toBeNull();
  });

  it('activates someone else’s draft only once a reason is given, then re-reads the catalogue', async () => {
    await create('approver-b');

    button(ru['planCatalog.activate.open']).click();
    await settle();
    expect(el<HTMLButtonElement>('.activateConfirm').disabled).toBe(true);
    await type('input[name="activationReason"]', 'finance signed the 2026 prices');
    el<HTMLButtonElement>('.activateConfirm').click();
    await settle();

    expect(api.activateVersion).toHaveBeenCalledWith('v2', 'finance signed the 2026 prices');
    expect(api.listPlansWithDrafts).toHaveBeenCalledTimes(2);
    expect(fixture.nativeElement.textContent).toContain('NETWORK v2');
  });

  it('registers a plan under an upper-cased code, and refuses a code the server would', async () => {
    await create('me', []);

    button(ru['planCatalog.register.open']).click();
    await settle();
    await type('input[name="planCode"]', 'net work');
    await type('input[name="planName"]', 'Network');
    await type('input[name="planReason"]', 'the 2026 price list');
    const submit = el<HTMLButtonElement>('.registerForm button[type="submit"]');
    expect(submit.disabled).toBe(true);

    await type('input[name="planCode"]', 'network_2026');
    expect(submit.disabled).toBe(false);
    submit.click();
    await settle();

    expect(api.createPlan).toHaveBeenCalledWith('NETWORK_2026', 'Network', 'the 2026 price list');
  });

  it('drafts the next version from the newest one, sending whole som and only the lines kept', async () => {
    await create('me');

    button(ru['planCatalog.draft.open']).click();
    await settle();
    expect(el<HTMLInputElement>('input[name="price"]').value).toBe('9 000 000');
    expect(el<HTMLInputElement>('input[name="limit-locations.max_count"]').value).toBe('20');
    expect(el<HTMLInputElement>('input[name="include-pos.integrations.enabled"]').checked).toBe(false);

    await type('input[name="price"]', '9 500 000');
    await type('input[name="limit-locations.max_count"]', '25');
    const submit = el<HTMLButtonElement>('.draftForm button[type="submit"]');
    expect(submit.disabled).toBe(true);
    await type('input[name="draftReason"]', 'repriced for 2027');
    submit.click();
    await settle();

    expect(api.draftVersion).toHaveBeenCalledWith('plan-1', {
      currency: 'UZS',
      priceMinor: 9_500_000,
      billingPeriod: 'MONTHLY',
      termsReference: 'TERMS-2026',
      entitlements: [
        {
          entitlementKey: 'locations.max_count',
          limit: 25,
          enforcementMode: 'SOFT',
          resetPeriod: 'NONE',
          overageUnitPriceMinor: 250_000,
        },
      ],
      // Carried over from the newest version: the trial, the deposit and the yearly discount.
      trialDays: 14,
      activationDepositMinor: 500_000,
      termDiscounts: [{ termMonths: 12, discountBasisPoints: 1_000 }],
      reason: 'repriced for 2027',
    });
  });

  it('offers a term discount on a monthly plan only, and reads 2.5 as 250 basis points', async () => {
    await create('me');

    button(ru['planCatalog.draft.open']).click();
    await settle();
    await type('input[name="discount6"]', '2,5');
    await type('input[name="draftReason"]', 'a half-year term');
    el<HTMLButtonElement>('.draftForm button[type="submit"]').click();
    await settle();
    expect(api.draftVersion).toHaveBeenLastCalledWith(
      'plan-1',
      expect.objectContaining({
        termDiscounts: [
          { termMonths: 6, discountBasisPoints: 250 },
          { termMonths: 12, discountBasisPoints: 1_000 },
        ],
      }),
    );

    button(ru['planCatalog.draft.open']).click();
    await settle();
    const period = el<HTMLSelectElement>('select[name="billingPeriod"]');
    period.value = 'YEARLY';
    period.dispatchEvent(new Event('change'));
    await settle();
    expect(fixture.nativeElement.textContent).toContain(ru['planCatalog.draft.termsInvalid']);
  });

  it('keeps a draft unsendable while its price cannot be read exactly', async () => {
    await create('me');

    button(ru['planCatalog.draft.open']).click();
    await settle();
    await type('input[name="price"]', '9 500 000,50');
    await type('input[name="draftReason"]', 'repriced');

    expect(el<HTMLButtonElement>('.draftForm button[type="submit"]').disabled).toBe(true);
    expect(fixture.nativeElement.textContent).toContain(ru['planCatalog.draft.priceInvalid']);
  });
});

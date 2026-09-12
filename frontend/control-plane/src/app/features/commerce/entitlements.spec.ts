import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { SessionContextService } from '../../core/auth/session-context.service';
import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ru } from '../../core/i18n/messages.ru';
import { Colleagues } from '../../shared/colleagues';
import { TenantsApi } from '../tenants/tenants-api';
import { CommerceApi, EntitlementSnapshot, PlanDetail, SubscriptionView } from './commerce-api';
import { Entitlements } from './entitlements';

const CONFIG: AppConfig = { apiBaseUrl: 'https://api.test.horecaos.uz', displayTimeZone: 'Asia/Tashkent' };

const SNAPSHOT: EntitlementSnapshot = {
  tenantId: 'tenant-1',
  subscriptionId: 'sub-1',
  hash: 'h',
  resolvedAt: '2026-09-10T00:00:00Z',
  entitlements: [
    {
      entitlementKey: 'locations.max_count',
      limit: 20,
      enabled: null,
      declaredMode: 'HARD',
      effectiveMode: 'METER_ONLY',
      resetPeriod: 'NONE',
      overageUnitPrice: null,
      source: 'PLAN_VERSION',
    },
    {
      entitlementKey: 'pos.integrations.enabled',
      limit: null,
      enabled: false,
      declaredMode: 'METER_ONLY',
      effectiveMode: 'METER_ONLY',
      resetPeriod: 'NONE',
      overageUnitPrice: null,
      source: 'CATALOGUE_DEFAULT',
    },
  ],
};

const SUBSCRIPTION: SubscriptionView = {
  subscriptionId: 'sub-1',
  planVersionId: 'v1',
  status: 'ACTIVE',
  startAt: '2026-09-01T00:00:00Z',
  trialEndAt: null,
  currentPeriodStart: '2026-09-01T00:00:00Z',
  currentPeriodEnd: '2026-10-01T00:00:00Z',
  suspensionReason: null,
  version: 4,
  allowedNext: ['CANCELLATION_SCHEDULED', 'PAST_DUE', 'SUSPENDED', 'TERMINATED'],
  termMonths: 1,
  activationDepositDueMinor: 0,
};

const version = (id: string, n: number, status: string) => ({
  planVersionId: id,
  versionNumber: n,
  price: { amountMinor: 9_000_000, currency: 'UZS' },
  billingPeriod: 'MONTHLY',
  status,
  termsReference: null,
  createdBy: 'a',
  approvedBy: status === 'ACTIVE' ? 'b' : null,
  activatedAt: status === 'ACTIVE' ? '2026-09-01T00:00:00Z' : null,
  entitlements: [],
  terms: {
    trialDays: null,
    activationDeposit: { amountMinor: 0, currency: 'UZS' },
    termDiscounts: n === 1 ? [{ termMonths: 12, discountBasisPoints: 1_000 }] : [],
  },
});

const PLANS: PlanDetail[] = [
  { planId: 'p1', code: 'NETWORK', name: 'Network', status: 'ACTIVE', versions: [version('v2', 2, 'DRAFT'), version('v1', 1, 'ACTIVE')] },
];

class FakeCommerceApi {
  readonly getEntitlements = vi.fn().mockResolvedValue(SNAPSHOT);
  readonly getSubscription = vi.fn<() => Promise<SubscriptionView | null>>();
  readonly listPlansWithDrafts = vi.fn().mockResolvedValue(PLANS);
  readonly startSubscription = vi.fn().mockResolvedValue({ subscriptionId: 'sub-2' });
  readonly transitionSubscription = vi.fn().mockResolvedValue(undefined);
  readonly grantOverride = vi.fn().mockResolvedValue({ overrideId: 'o-1' });
}

describe('Entitlements', () => {
  let fixture: ComponentFixture<Entitlements>;
  let api: FakeCommerceApi;

  async function create(subscription: SubscriptionView | null): Promise<void> {
    api = new FakeCommerceApi();
    api.getSubscription.mockResolvedValue(subscription);
    await TestBed.configureTestingModule({
      imports: [Entitlements],
      providers: [
        provideRouter([]),
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: CommerceApi, useValue: api },
        { provide: Colleagues, useValue: { others: vi.fn().mockResolvedValue(['approver-b']) } },
        { provide: TenantsApi, useValue: { listTenants: vi.fn().mockResolvedValue({ items: [], nextCursor: null }) } },
        { provide: SessionContextService, useValue: { has: () => true, current: () => ({ subject: 'me' }) } },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({ tenantId: 'tenant-1' }) } },
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Entitlements);
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

  async function set(selector: string, value: string, event = 'input'): Promise<void> {
    const input = el<HTMLInputElement>(selector);
    input.value = value;
    input.dispatchEvent(new Event(event));
    await settle();
  }

  it('names the plan the tenant is on and shows a counted limit as a number, not as switched off', async () => {
    await create(SUBSCRIPTION);

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Network v1');
    expect(text).toContain(ru['commerce.subscription.ACTIVE']);
    const firstRow = el('.table tbody tr');
    expect(firstRow.textContent).toContain('20');
    expect(firstRow.textContent).not.toContain(ru['commerce.off']);
    expect(firstRow.textContent).toContain(ru['commerce.mode.METER_ONLY']);
    expect(firstRow.textContent).toContain(ru['commerce.mode.HARD']);
  });

  it('says what the subscription still owes as its deposit, and says nothing when it owes none', async () => {
    // The deposit was stored on the subscription and read by no screen, so
    // finance had no way to tell a tenant what to pay, or to see that it had
    // paid. It is in the plan version's currency, which this screen holds.
    await create({ ...SUBSCRIPTION, activationDepositDueMinor: 5_000_000 });

    expect(el('.depositDue').textContent).toContain('5 000 000');
    expect(fixture.nativeElement.textContent).toContain(ru['entitlements.subscription.depositDue']);

    TestBed.resetTestingModule();
    await create(SUBSCRIPTION);
    expect(el('.depositDue')).toBeNull();
    expect(fixture.nativeElement.textContent).not.toContain(ru['entitlements.subscription.depositDue']);
  });

  it('offers only the moves the server allows, and sends the version it read', async () => {
    await create(SUBSCRIPTION);

    const options = Array.from(el<HTMLSelectElement>('select[name="nextStatus"]').options).map((o) => o.value);
    expect(options).toEqual(['', 'CANCELLATION_SCHEDULED', 'PAST_DUE', 'SUSPENDED', 'TERMINATED']);

    await set('select[name="nextStatus"]', 'SUSPENDED', 'change');
    await set('input[name="transitionReason"]', 'unpaid for two months');
    const submit = el<HTMLButtonElement>('.transitionForm button[type="submit"]');
    expect(submit.disabled).toBe(true);
    await set('input[name="suspensionReason"]', 'invoice 2026-07 unpaid');
    submit.click();
    await settle();

    expect(api.transitionSubscription).toHaveBeenCalledWith('tenant-1', {
      status: 'SUSPENDED',
      expectedVersion: 4,
      suspensionReason: 'invoice 2026-07 unpaid',
      cancelAt: undefined,
      reason: 'unpaid for two months',
    });
  });

  it('warns before a move that ends the subscription for good', async () => {
    await create(SUBSCRIPTION);

    await set('select[name="nextStatus"]', 'TERMINATED', 'change');

    expect(fixture.nativeElement.textContent).toContain(ru['entitlements.transition.terminalWarning']);
  });

  it('puts a tenant with no subscription on a live version only', async () => {
    await create(null);

    const options = Array.from(el<HTMLSelectElement>('select[name="startPlan"]').options).map((o) => o.value);
    expect(options).toEqual(['', 'v1']);

    await set('select[name="startPlan"]', 'v1', 'change');
    await set('input[name="trialDays"]', '14');
    await set('input[name="startReason"]', 'signed the Network contract');
    el<HTMLButtonElement>('.startForm button[type="submit"]').click();
    await settle();

    expect(api.startSubscription).toHaveBeenCalledWith('tenant-1', 'v1', 'signed the Network contract', 14, 1);
  });

  it('offers the terms the chosen version sells and sends the one picked', async () => {
    await create(null);

    await set('select[name="startPlan"]', 'v1', 'change');
    const terms = Array.from(el<HTMLSelectElement>('select[name="startTerm"]').options).map((o) => o.value);
    expect(terms).toEqual(['1', '12']);
    await set('select[name="startTerm"]', '12', 'change');
    await set('input[name="startReason"]', 'a year up front');
    el<HTMLButtonElement>('.startForm button[type="submit"]').click();
    await settle();

    expect(api.startSubscription).toHaveBeenCalledWith('tenant-1', 'v1', 'a year up front', undefined, 12);
  });

  it('grants an override with the operator’s own reason and a colleague as the second name', async () => {
    await create(SUBSCRIPTION);

    await set('select[name="overrideKey"]', 'locations.max_count', 'change');
    await set('input[name="overrideValidUntil"]', '2026-12-31');
    await set('select[name="overrideApprovedBy"]', 'approver-b', 'change');
    await set('input[name="overrideReason"]', 'opening three branches before the plan changes');
    const submit = el<HTMLButtonElement>('.overrideForm button[type="submit"]');
    expect(submit.disabled).toBe(true);
    await set('input[name="overrideLimit"]', '23');
    submit.click();
    await settle();

    expect(api.grantOverride).toHaveBeenCalledWith('tenant-1', {
      entitlementKey: 'locations.max_count',
      limit: 23,
      enabled: undefined,
      validUntil: new Date('2026-12-31').toISOString(),
      approvedBy: 'approver-b',
      reason: 'opening three branches before the plan changes',
    });
  });
});

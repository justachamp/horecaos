import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { CurrentTenant } from '../../../core/auth/current-tenant';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import {
  CommercialApi,
  EntitlementSnapshotView,
  SellableModuleView,
  StatementView,
  SubscriptionView,
  TenantArrearsView,
  TenantModuleView,
  UsageView,
} from '../commercial-api';
import { SubscriptionPage } from './subscription-page';

const TENANT_ID = 'tenant-1';

const SUBSCRIPTION: SubscriptionView = {
  subscriptionId: 'sub-1',
  planVersionId: 'plan-v1',
  planCode: 'BASIC',
  planVersionNumber: 1,
  price: { amountMinor: 1_200_000, currency: 'UZS' },
  billingPeriod: 'MONTHLY',
  status: 'ACTIVE',
  startAt: '2026-01-01T00:00:00Z',
  trialEndAt: null,
  currentPeriodStart: '2026-08-01T00:00:00Z',
  currentPeriodEnd: '2026-09-01T00:00:00Z',
  suspensionReason: null,
  version: 3,
};

const ENTITLEMENTS: EntitlementSnapshotView = {
  tenantId: TENANT_ID,
  subscriptionId: 'sub-1',
  hash: 'h-1',
  resolvedAt: '2026-08-01T00:00:00Z',
  entitlements: [],
};

const USAGE: readonly UsageView[] = [];

// Newest month first, void ones included — the same order CommercialStatementController.list serves.
const ISSUED_STATEMENT: StatementView = {
  statementId: 'st-2',
  number: 'S-2026-08-000001',
  periodKey: '2026-08',
  periodStart: '2026-07-31T19:00:00Z',
  periodEnd: '2026-08-31T19:00:00Z',
  status: 'ISSUED',
  total: { amountMinor: 1_500_000, currency: 'UZS' },
  issuedBy: 'finance',
  issuedAt: '2026-09-01T05:00:00Z',
  issueReason: 'August close',
  voidedBy: null,
  voidedAt: null,
  voidReason: null,
  lines: [],
};

const VOID_STATEMENT: StatementView = {
  statementId: 'st-1',
  number: 'S-2026-07-000001',
  periodKey: '2026-07',
  periodStart: '2026-06-30T19:00:00Z',
  periodEnd: '2026-07-31T19:00:00Z',
  status: 'VOID',
  total: { amountMinor: 1_200_000, currency: 'UZS' },
  issuedBy: 'finance',
  issuedAt: '2026-08-01T05:00:00Z',
  issueReason: 'July close',
  voidedBy: 'finance',
  voidedAt: '2026-08-02T05:00:00Z',
  voidReason: 'Wrong module count',
  lines: [],
};

const ISSUED_STATEMENT_DETAIL: StatementView = {
  ...ISSUED_STATEMENT,
  lines: [
    {
      lineNumber: 1,
      kind: 'PLAN',
      referenceCode: 'BASIC@v1',
      description: 'BASIC v1, MONTHLY',
      quantity: 1,
      unitPrice: { amountMinor: 1_200_000, currency: 'UZS' },
      amount: { amountMinor: 1_200_000, currency: 'UZS' },
    },
    {
      lineNumber: 2,
      kind: 'MODULE',
      referenceCode: 'kds',
      description: 'Kitchen display, PER_LOCATION',
      quantity: 3,
      unitPrice: { amountMinor: 100_000, currency: 'UZS' },
      amount: { amountMinor: 300_000, currency: 'UZS' },
    },
  ],
};

const ON_SALE_MODULE: SellableModuleView = {
  moduleId: 'module-kiosk',
  code: 'kiosk',
  name: 'Self-service kiosk',
  description: 'An ordering screen at the counter.',
  billingUnit: 'PER_TENANT',
  unitPrice: { amountMinor: 150_000, currency: 'UZS' },
  featureKeys: [],
  status: 'ACTIVE',
  createdBy: 'commercial-author',
  approvedBy: 'commercial-approver',
  activatedAt: '2026-08-01T00:00:00Z',
  retiredAt: null,
};

const ON_SALE_AND_ALREADY_HELD_MODULE: SellableModuleView = {
  ...ON_SALE_MODULE,
  moduleId: 'module-analytics',
  code: 'analytics',
  name: 'Analytics',
};

const ALREADY_HELD_MODULE: TenantModuleView = {
  tenantModuleId: 'tm-1',
  moduleId: 'module-analytics',
  moduleCode: 'analytics',
  moduleName: 'Analytics',
  billingUnit: 'PER_TENANT',
  unitPrice: { amountMinor: 90_000, currency: 'UZS' },
  quantity: null,
  startedAt: '2026-07-01T00:00:00Z',
  startedBy: 'finance',
  startReason: 'sold with the pilot',
  endedAt: null,
  endedBy: null,
  endReason: null,
};

const ARREARS_HEALTHY: TenantArrearsView = {
  status: 'ACTIVE',
  planEntitlementsApply: true,
  additionsBlocked: false,
  allowedNext: ['PAST_DUE', 'SUSPENDED', 'CANCELLATION_SCHEDULED', 'TERMINATED'],
  since: '2026-08-01T00:00:00Z',
  daysInStatus: 14,
  suspensionReason: null,
  latestStatement: null,
};

const ARREARS_RESTRICTED: TenantArrearsView = {
  status: 'SUSPENDED',
  planEntitlementsApply: false,
  additionsBlocked: true,
  allowedNext: ['ACTIVE', 'TERMINATED'],
  since: '2026-09-01T00:00:00Z',
  daysInStatus: 5,
  suspensionReason: 'three months unpaid',
  latestStatement: {
    statementId: 'st-2',
    number: 'S-2026-08-000001',
    periodKey: '2026-08',
    total: { amountMinor: 1_500_000, currency: 'UZS' },
    issuedAt: '2026-09-01T05:00:00Z',
  },
};

class FakeCurrentTenant {
  readonly tenantId = signal<string | null>(TENANT_ID);
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('SubscriptionPage', () => {
  let fixture: ComponentFixture<SubscriptionPage>;
  let api: {
    subscription: ReturnType<typeof vi.fn>;
    entitlements: ReturnType<typeof vi.fn>;
    usage: ReturnType<typeof vi.fn>;
    statements: ReturnType<typeof vi.fn>;
    statement: ReturnType<typeof vi.fn>;
    statementExport: ReturnType<typeof vi.fn>;
    modulesOnSale: ReturnType<typeof vi.fn>;
    modulesHeld: ReturnType<typeof vi.fn>;
    purchaseModule: ReturnType<typeof vi.fn>;
    arrears: ReturnType<typeof vi.fn>;
  };

  beforeEach(async () => {
    api = {
      subscription: vi.fn().mockResolvedValue(SUBSCRIPTION),
      entitlements: vi.fn().mockResolvedValue(ENTITLEMENTS),
      usage: vi.fn().mockResolvedValue(USAGE),
      statements: vi.fn().mockResolvedValue([ISSUED_STATEMENT, VOID_STATEMENT]),
      statement: vi.fn().mockResolvedValue(ISSUED_STATEMENT_DETAIL),
      statementExport: vi
        .fn()
        .mockResolvedValue('number,period\r\n"S-2026-08-000001","2026-08"\r\n'),
      modulesOnSale: vi.fn().mockResolvedValue([ON_SALE_MODULE, ON_SALE_AND_ALREADY_HELD_MODULE]),
      modulesHeld: vi.fn().mockResolvedValue([ALREADY_HELD_MODULE]),
      purchaseModule: vi.fn().mockResolvedValue({ tenantModuleId: 'tm-2' }),
      arrears: vi.fn().mockResolvedValue(ARREARS_HEALTHY),
    };

    await TestBed.configureTestingModule({
      imports: [SubscriptionPage],
      providers: [
        { provide: CommercialApi, useValue: api },
        { provide: CurrentTenant, useValue: new FakeCurrentTenant() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(SubscriptionPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  });

  it('reads every statement this tenant has been issued, alongside the plan', () => {
    expect(api.statements).toHaveBeenCalledWith(TENANT_ID);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('S-2026-08-000001');
    expect(text).toContain('S-2026-07-000001');
    // Void ones are included, not hidden — the row's own point (ADR 0088).
    expect(text).toContain('Void');
  });

  it('renders an issued statement’s lines and totals only once it is opened', async () => {
    const host: HTMLElement = fixture.nativeElement;
    expect(host.textContent).not.toContain('Kitchen display');
    expect(api.statement).not.toHaveBeenCalled();

    const numberButton = [...host.querySelectorAll('button.link')].find((button) =>
      button.textContent?.includes('S-2026-08-000001'),
    ) as HTMLButtonElement;
    numberButton.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.statement).toHaveBeenCalledWith(TENANT_ID, 'st-2');
    expect(host.textContent).toContain('Kitchen display, PER_LOCATION');
    expect(host.textContent).toContain('BASIC v1, MONTHLY');

    // Clicking the same statement again collapses it without a second read.
    numberButton.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.statement).toHaveBeenCalledTimes(1);
    expect(host.textContent).not.toContain('Kitchen display');
  });

  it('downloads the statement CSV under its number, not the tenant’s internal id', async () => {
    const host: HTMLElement = fixture.nativeElement;
    const downloadButton = [...host.querySelectorAll('button')].find((button) =>
      button.textContent?.includes('Download CSV'),
    ) as HTMLButtonElement;

    downloadButton.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.statementExport).toHaveBeenCalledWith(TENANT_ID, 'st-2');
  });

  it('surfaces a failed statement read as an alert rather than staying silent', async () => {
    api.statement.mockRejectedValueOnce(
      new ApiError(ApiErrorCode.INTERNAL_ERROR, 500, null, 'corr-1'),
    );
    const host: HTMLElement = fixture.nativeElement;
    const numberButton = [...host.querySelectorAll('button.link')].find((button) =>
      button.textContent?.includes('S-2026-08-000001'),
    ) as HTMLButtonElement;

    numberButton.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.querySelector('[role="alert"]')).not.toBeNull();
  });

  it('says plainly that no statement has been issued yet, rather than an empty table', async () => {
    api.statements.mockResolvedValue([]);
    fixture = TestBed.createComponent(SubscriptionPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'No statements have been issued yet.',
    );
  });

  // -------------------------------------------------- Finance 8.6, ADR 0127

  it('lists the on-sale module catalogue and shows an already-held module as added, not purchasable', () => {
    const host: HTMLElement = fixture.nativeElement;
    expect(api.modulesOnSale).toHaveBeenCalledWith(TENANT_ID);
    expect(api.modulesHeld).toHaveBeenCalledWith(TENANT_ID);
    expect(host.textContent).toContain('Self-service kiosk');
    expect(host.textContent).toContain('Analytics');

    const rows = [...host.querySelectorAll('.table tbody tr')];
    const analyticsRow = rows.find((row) => row.textContent?.includes('Analytics'));
    expect(analyticsRow?.textContent).toContain('Added');
    expect(analyticsRow?.querySelector('button')).toBeNull();

    const kioskRow = rows.find((row) => row.textContent?.includes('Self-service kiosk'));
    expect(kioskRow?.querySelector('button')?.textContent?.trim()).toBe('Add');
  });

  // -------------------------------------------------- gap map row 8.6: purchase confirmation

  function clickAdd(host: HTMLElement): void {
    const addButton = [...host.querySelectorAll('button')].find(
      (button) => button.textContent?.trim() === 'Add',
    ) as HTMLButtonElement;
    expect(addButton).toBeTruthy();
    addButton.click();
  }

  it('clicking Add opens a confirm dialog naming the price and what activates, without purchasing yet', () => {
    const host: HTMLElement = fixture.nativeElement;
    clickAdd(host);
    fixture.detectChanges();

    expect(api.purchaseModule).not.toHaveBeenCalled();
    const dialog = host.querySelector('[data-testid="q-confirm-dialog"]');
    expect(dialog).not.toBeNull();
    expect(dialog?.textContent).toContain('Self-service kiosk');
    // ON_SALE_MODULE.unitPrice is 150 000 UZS — money.ts groups with U+00A0 (NBSP).
    expect(dialog?.textContent).toContain('150 000');
    expect(dialog?.textContent).toContain('Per tenant');
    // ON_SALE_MODULE carries a description — that is what "activates" names.
    expect(dialog?.textContent).toContain('An ordering screen at the counter.');
  });

  it('names the quantity in the confirm dialog for a PER_UNIT module', async () => {
    api.modulesOnSale.mockResolvedValueOnce([
      {
        ...ON_SALE_MODULE,
        billingUnit: 'PER_UNIT',
        featureKeys: ['kds.orders.accept'],
        description: null,
      },
    ]);
    fixture = TestBed.createComponent(SubscriptionPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
    const host: HTMLElement = fixture.nativeElement;
    const quantityInput = host.querySelector('[aria-label="Quantity"]') as HTMLInputElement;
    quantityInput.value = '3';
    quantityInput.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    clickAdd(host);
    fixture.detectChanges();

    const dialog = host.querySelector('[data-testid="q-confirm-dialog"]');
    // Quantity folded into the price line, and no description on this
    // module falls back to naming the raw feature key.
    expect(dialog?.textContent).toContain('× 3');
    expect(dialog?.textContent).toContain('kds.orders.accept');
  });

  it('Cancel closes the dialog without purchasing', async () => {
    const host: HTMLElement = fixture.nativeElement;
    clickAdd(host);
    fixture.detectChanges();

    (host.querySelector('[data-testid="q-confirm-cancel"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.purchaseModule).not.toHaveBeenCalled();
    expect(host.querySelector('[data-testid="q-confirm-dialog"]')).toBeNull();
  });

  it('confirming the dialog purchases the module and refreshes what the tenant holds and is entitled to', async () => {
    const host: HTMLElement = fixture.nativeElement;
    api.modulesHeld.mockResolvedValueOnce([
      ALREADY_HELD_MODULE,
      { ...ALREADY_HELD_MODULE, tenantModuleId: 'tm-2', moduleId: ON_SALE_MODULE.moduleId },
    ]);
    clickAdd(host);
    fixture.detectChanges();
    expect(api.purchaseModule).not.toHaveBeenCalled();

    (host.querySelector('[data-testid="q-confirm-confirm"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.purchaseModule).toHaveBeenCalledWith(TENANT_ID, ON_SALE_MODULE.moduleId, null);
    // Re-read after a successful purchase, so the catalogue reflects what just happened.
    expect(api.modulesHeld).toHaveBeenCalledTimes(2);
    expect(api.entitlements).toHaveBeenCalledTimes(2);
    // The dialog closes once the purchase settles.
    expect(host.querySelector('[data-testid="q-confirm-dialog"]')).toBeNull();
  });

  it('shows a purchase failure as an alert rather than staying silent, and closes the dialog', async () => {
    api.purchaseModule.mockRejectedValueOnce(
      new ApiError(ApiErrorCode.INTERNAL_ERROR, 500, null, 'corr-2'),
    );
    const host: HTMLElement = fixture.nativeElement;
    clickAdd(host);
    fixture.detectChanges();

    (host.querySelector('[data-testid="q-confirm-confirm"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.querySelector('[role="alert"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="q-confirm-dialog"]')).toBeNull();
  });

  it('renders no restriction banner while the subscription is in good standing', () => {
    const host: HTMLElement = fixture.nativeElement;
    expect(api.arrears).toHaveBeenCalledWith(TENANT_ID);
    expect(host.textContent).not.toContain('Account standing');
  });

  it('renders the restricted-feature banner, with the latest statement, once additions are blocked', async () => {
    api.arrears.mockResolvedValue(ARREARS_RESTRICTED);
    fixture = TestBed.createComponent(SubscriptionPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    const host: HTMLElement = fixture.nativeElement;
    expect(host.textContent).toContain('Account standing');
    expect(host.textContent).toContain('Suspended');
    expect(host.textContent).toContain('5 days');
    expect(host.textContent).toContain('S-2026-08-000001');
  });
});

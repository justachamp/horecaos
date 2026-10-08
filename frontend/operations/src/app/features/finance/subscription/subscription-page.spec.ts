import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
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
import { StatementPaymentView, TenantWalletView, WalletApi } from '../wallet/wallet-api';
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
  acquiredVia: 'PLATFORM',
  endableByTenant: false,
  endedAt: null,
  endedBy: null,
  endReason: null,
};

/** A module this tenant bought itself from the catalogue: the only kind it may end. */
const SELF_BOUGHT_MODULE: TenantModuleView = {
  ...ALREADY_HELD_MODULE,
  tenantModuleId: 'tm-9',
  moduleId: 'module-kiosk',
  moduleCode: 'kiosk',
  moduleName: 'Self-service kiosk',
  unitPrice: { amountMinor: 150_000, currency: 'UZS' },
  startReason: 'Purchased from the operations console',
  acquiredVia: 'SELF_SERVICE',
  endableByTenant: true,
};

/** A module the tenant bought and has since ended: history, not a row to act on. */
const ENDED_SELF_BOUGHT_MODULE: TenantModuleView = {
  ...SELF_BOUGHT_MODULE,
  tenantModuleId: 'tm-8',
  moduleId: 'module-old',
  moduleCode: 'old-addon',
  moduleName: 'Old add-on',
  endableByTenant: false,
  endedAt: '2026-08-20T00:00:00Z',
  endedBy: 'finance',
  endReason: 'Ended from the operations console',
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
  owed: null,
  waysToPay: { cardOnFile: false, cardPaymentsAvailable: false, bankTransferAvailable: true },
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
  owed: { due: { amountMinor: 1_500_000, currency: 'UZS' }, openStatements: 1 },
  waysToPay: { cardOnFile: false, cardPaymentsAvailable: false, bankTransferAvailable: true },
};

/** What the wallet says of the two statements above: the August one part paid, the July one void (absent). */
const STATEMENT_PAYMENTS: readonly StatementPaymentView[] = [
  {
    statementId: 'st-2',
    number: 'S-2026-08-000001',
    periodKey: '2026-08',
    total: { amountMinor: 1_500_000, currency: 'UZS' },
    paid: { amountMinor: 1_000_000, currency: 'UZS' },
    due: { amountMinor: 500_000, currency: 'UZS' },
  },
];

/** The wallet at a glance: money in it, nothing about to lapse. */
const WALLET_OVERVIEW: TenantWalletView = {
  paidBalance: { amountMinor: 2_000_000, currency: 'UZS' },
  bonusBalance: { amountMinor: 300_000, currency: 'UZS' },
  bonusSpendableBalance: { amountMinor: 300_000, currency: 'UZS' },
  paymentMethod: 'WALLET',
  card: null,
  lapsingGrants: [],
  pendingTopUp: null,
  cardPaymentsAvailable: false,
  bankTransferAvailable: true,
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
    endModule: ReturnType<typeof vi.fn>;
    arrears: ReturnType<typeof vi.fn>;
  };
  let wallet: {
    statementPayments: ReturnType<typeof vi.fn>;
    overview: ReturnType<typeof vi.fn>;
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
      endModule: vi.fn().mockResolvedValue({
        tenantModuleId: 'tm-9',
        endedAt: '2026-09-30T10:00:00Z',
        lastBilledPeriod: '2026-09',
      }),
      arrears: vi.fn().mockResolvedValue(ARREARS_HEALTHY),
    };
    wallet = {
      statementPayments: vi.fn().mockResolvedValue(STATEMENT_PAYMENTS),
      overview: vi.fn().mockResolvedValue(WALLET_OVERVIEW),
    };

    await TestBed.configureTestingModule({
      imports: [SubscriptionPage],
      providers: [
        provideRouter([]),
        { provide: CommercialApi, useValue: api },
        { provide: WalletApi, useValue: wallet },
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

  // -------------------------------------------------- ADR 0127 status note: ending one's own purchase

  /** Reloads the page with a held list that has one module of each kind the End button distinguishes. */
  async function loadWithHeld(held: readonly TenantModuleView[]): Promise<HTMLElement> {
    api.modulesHeld.mockResolvedValue(held);
    fixture = TestBed.createComponent(SubscriptionPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  function heldRows(host: HTMLElement): HTMLElement[] {
    return [...host.querySelectorAll('[data-testid="subscription-held-modules"] tbody tr')].map(
      (row) => row as HTMLElement,
    );
  }

  function clickEnd(host: HTMLElement): void {
    const endButton = host.querySelector('[data-testid="subscription-module-end"]');
    expect(endButton).not.toBeNull();
    (endButton as HTMLButtonElement).click();
  }

  it('offers End only on a module the tenant bought itself, and says who assigned the rest', async () => {
    const host = await loadWithHeld([SELF_BOUGHT_MODULE, ALREADY_HELD_MODULE]);

    const rows = heldRows(host);
    expect(rows).toHaveLength(2);
    const bought = rows.find((row) => row.textContent?.includes('Self-service kiosk'));
    expect(bought?.textContent).toContain('Bought by you');
    expect(
      bought?.querySelector('[data-testid="subscription-module-end"]')?.textContent?.trim(),
    ).toBe('End');

    const assigned = rows.find((row) => row.textContent?.includes('Analytics'));
    expect(assigned?.textContent).toContain('Assigned by HorecaOS');
    expect(assigned?.querySelector('button')).toBeNull();
    expect(assigned?.textContent).toContain('Contact HorecaOS to remove');
  });

  it('lists only the modules the tenant holds now, not the ones it has ended', async () => {
    const host = await loadWithHeld([SELF_BOUGHT_MODULE, ENDED_SELF_BOUGHT_MODULE]);

    const text = heldRows(host)
      .map((row) => row.textContent)
      .join(' ');
    expect(text).toContain('Self-service kiosk');
    expect(text).not.toContain('Old add-on');
  });

  it('says plainly that the tenant holds no modules yet', async () => {
    const host = await loadWithHeld([]);

    expect(heldRows(host)[0]?.textContent).toContain('You have no modules yet.');
  });

  it('clicking End opens a confirm step that says nothing is prorated, without ending yet', async () => {
    const host = await loadWithHeld([SELF_BOUGHT_MODULE]);

    clickEnd(host);
    fixture.detectChanges();

    expect(api.endModule).not.toHaveBeenCalled();
    const dialog = host.querySelector('[data-testid="q-confirm-dialog"]');
    expect(dialog).not.toBeNull();
    expect(dialog?.textContent).toContain('Self-service kiosk');
    expect(dialog?.textContent).toContain('Nothing is prorated');
    expect(dialog?.textContent).toContain('billed in full');
  });

  it('a one-off module’s confirm step says ending it refunds nothing', async () => {
    const host = await loadWithHeld([{ ...SELF_BOUGHT_MODULE, billingUnit: 'ONE_OFF' }]);

    clickEnd(host);
    fixture.detectChanges();

    const dialog = host.querySelector('[data-testid="q-confirm-dialog"]');
    expect(dialog?.textContent).toContain('billed once');
    expect(dialog?.textContent).toContain('does not refund');
  });

  it('Cancel on the End step closes it and sends nothing', async () => {
    const host = await loadWithHeld([SELF_BOUGHT_MODULE]);
    clickEnd(host);
    fixture.detectChanges();

    (host.querySelector('[data-testid="q-confirm-cancel"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.endModule).not.toHaveBeenCalled();
    expect(host.querySelector('[data-testid="q-confirm-dialog"]')).toBeNull();
  });

  it('confirming ends the module, re-reads what the tenant holds, and says the last month that bills it', async () => {
    const host = await loadWithHeld([SELF_BOUGHT_MODULE, ALREADY_HELD_MODULE]);
    clickEnd(host);
    fixture.detectChanges();

    api.modulesHeld.mockResolvedValue([
      { ...SELF_BOUGHT_MODULE, endableByTenant: false, endedAt: '2026-09-30T10:00:00Z' },
      ALREADY_HELD_MODULE,
    ]);
    api.modulesHeld.mockClear();
    api.entitlements.mockClear();
    (host.querySelector('[data-testid="q-confirm-confirm"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.endModule).toHaveBeenCalledWith(TENANT_ID, 'tm-9');
    // The held list and the entitlements are read again, once: the module's features are off.
    expect(api.modulesHeld).toHaveBeenCalledTimes(1);
    expect(api.entitlements).toHaveBeenCalledTimes(1);
    expect(host.querySelector('[data-testid="q-confirm-dialog"]')).toBeNull();
    // The row is gone; the server's answer, not this screen's guess, names the last billed month.
    expect(
      heldRows(host)
        .map((row) => row.textContent)
        .join(' '),
    ).not.toContain('Self-service kiosk');
    const notice = host.querySelector('[data-testid="subscription-module-ended"]');
    expect(notice?.textContent).toContain('Self-service kiosk');
    expect(notice?.textContent).toContain('2026-09');
    expect(host.querySelector('[role="alert"]')).toBeNull();
  });

  it('a refused End shows an alert, closes the step, and reads the list again so the stale button goes', async () => {
    api.endModule.mockRejectedValueOnce(
      new ApiError(ApiErrorCode.RESOURCE_CONFLICT, 409, null, 'corr-9'),
    );
    const host = await loadWithHeld([SELF_BOUGHT_MODULE]);
    clickEnd(host);
    fixture.detectChanges();

    api.modulesHeld.mockResolvedValue([
      { ...SELF_BOUGHT_MODULE, endableByTenant: false, endedAt: '2026-09-30T09:59:00Z' },
    ]);
    api.modulesHeld.mockClear();
    (host.querySelector('[data-testid="q-confirm-confirm"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.querySelector('[role="alert"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="q-confirm-dialog"]')).toBeNull();
    expect(host.querySelector('[data-testid="subscription-module-ended"]')).toBeNull();
    expect(api.modulesHeld).toHaveBeenCalledTimes(1);
    expect(host.querySelector('[data-testid="subscription-module-end"]')).toBeNull();
  });

  it('a successful End stays a success when the re-read afterwards fails', async () => {
    const host = await loadWithHeld([SELF_BOUGHT_MODULE]);
    clickEnd(host);
    fixture.detectChanges();

    api.modulesHeld.mockRejectedValue(
      new ApiError(ApiErrorCode.INTERNAL_ERROR, 500, null, 'corr-3'),
    );
    (host.querySelector('[data-testid="q-confirm-confirm"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.endModule).toHaveBeenCalledTimes(1);
    expect(host.querySelector('[data-testid="subscription-module-ended"]')).not.toBeNull();
    expect(host.querySelector('[role="alert"]')).toBeNull();
  });

  it('a successful End whose re-read fails still stops showing the module as live, with no End button', async () => {
    const host = await loadWithHeld([SELF_BOUGHT_MODULE, ALREADY_HELD_MODULE]);
    clickEnd(host);
    fixture.detectChanges();

    api.modulesHeld.mockRejectedValue(
      new ApiError(ApiErrorCode.INTERNAL_ERROR, 500, null, 'corr-4'),
    );
    (host.querySelector('[data-testid="q-confirm-confirm"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    // The server said it ended; the screen must not contradict the notice above the table.
    const held = heldRows(host)
      .map((row) => row.textContent)
      .join(' ');
    expect(held).not.toContain('Self-service kiosk');
    expect(held).toContain('Analytics');
    expect(host.querySelector('[data-testid="subscription-module-end"]')).toBeNull();
    expect(host.querySelector('[data-testid="subscription-module-ended"]')).not.toBeNull();
    // The catalogue offers the module again rather than saying it is still added.
    const kioskRow = [...host.querySelectorAll('.table tbody tr')].find(
      (row) =>
        row.textContent?.includes('Self-service kiosk') &&
        !row.closest('[data-testid="subscription-held-modules"]'),
    );
    expect(kioskRow?.textContent).not.toContain('Added');
    expect(kioskRow?.querySelector('button')?.textContent?.trim()).toBe('Add');
    expect(host.querySelector('[role="alert"]')).toBeNull();
  });

  it('will not open the End step for a module the server says the tenant cannot end', async () => {
    const host = await loadWithHeld([ALREADY_HELD_MODULE]);
    const page = fixture.componentInstance as unknown as {
      requestEnd(held: TenantModuleView): void;
      endTarget(): TenantModuleView | null;
    };

    page.requestEnd(ALREADY_HELD_MODULE);
    fixture.detectChanges();

    expect(page.endTarget()).toBeNull();
    expect(host.querySelector('[data-testid="q-confirm-dialog"]')).toBeNull();
    expect(api.endModule).not.toHaveBeenCalled();
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

  // ------------------------------------------- ADR 0095: the banner offers a way out

  async function reopenWith(arrears: TenantArrearsView): Promise<HTMLElement> {
    api.arrears.mockResolvedValue(arrears);
    fixture = TestBed.createComponent(SubscriptionPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
    return fixture.nativeElement;
  }

  const text = (host: HTMLElement, testid: string): string =>
    (host.querySelector(`[data-testid="${testid}"]`)?.textContent ?? '').replace(/\u00a0/g, ' ');

  it('says what is owed and offers bank transfer, with a link to the wallet, to a restricted tenant', async () => {
    const host = await reopenWith(ARREARS_RESTRICTED);
    expect(text(host, 'subscription-owed')).toContain('You owe 1 500 000');
    expect(text(host, 'subscription-owed')).toContain('Statements with an amount due: 1');
    expect(text(host, 'subscription-way-to-pay')).toContain('bank transfer');
    expect(host.textContent).not.toContain('You can pay by card');
    const link = host.querySelector(
      '[data-testid="subscription-open-wallet"]',
    ) as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/finance/wallet');
  });

  it('offers the card too once the server says it is connected and a card is on file', async () => {
    const host = await reopenWith({
      ...ARREARS_RESTRICTED,
      waysToPay: { cardOnFile: true, cardPaymentsAvailable: true, bankTransferAvailable: true },
    });
    expect(host.textContent).toContain('You can pay by card');
    expect(host.textContent).toContain('You can pay by bank transfer');
  });

  it('does not offer a card that is not connected, even with a card on file', async () => {
    const host = await reopenWith({
      ...ARREARS_RESTRICTED,
      waysToPay: { cardOnFile: true, cardPaymentsAvailable: false, bankTransferAvailable: true },
    });
    expect(host.textContent).not.toContain('You can pay by card');
  });

  it('says to contact HorecaOS when neither way of paying is available, rather than offering one that would be refused', async () => {
    const host = await reopenWith({
      ...ARREARS_RESTRICTED,
      waysToPay: { cardOnFile: false, cardPaymentsAvailable: false, bankTransferAvailable: false },
    });
    expect(text(host, 'subscription-way-to-pay')).toContain('Contact HorecaOS to arrange payment');
  });

  it('shows a past-due tenant that nothing is restricted yet, instead of nothing at all', async () => {
    const host = await reopenWith({
      ...ARREARS_RESTRICTED,
      status: 'PAST_DUE',
      planEntitlementsApply: true,
      additionsBlocked: false,
    });
    expect(text(host, 'subscription-standing')).toContain(
      'Payment is late. Nothing is restricted yet',
    );
    expect(text(host, 'subscription-standing')).not.toContain('cannot be added');
  });

  it('shows a tenant in good standing that owes a statement what it owes, without calling it restricted', async () => {
    const host = await reopenWith({ ...ARREARS_HEALTHY, owed: ARREARS_RESTRICTED.owed });
    expect(text(host, 'subscription-owed')).toContain('You owe 1 500 000');
    expect(text(host, 'subscription-standing')).not.toContain('cannot be added');
    expect(host.querySelector('[data-testid="subscription-standing"]')?.getAttribute('role')).toBe(
      'status',
    );
  });

  it('never offers to restore the subscription: paying is a signal to staff, not a switch (ADR 0089)', async () => {
    const host = await reopenWith(ARREARS_RESTRICTED);
    const labels = [...host.querySelectorAll('[data-testid="subscription-standing"] button')];
    expect(labels).toHaveLength(0);
  });

  it('keeps showing nothing extra for a tenant in good standing that owes nothing', async () => {
    const host = await reopenWith(ARREARS_HEALTHY);
    expect(host.querySelector('[data-testid="subscription-standing"]')).toBeNull();
  });

  // ------------------------------------------- statements: what the wallet has paid

  it('shows what the wallet has paid of each statement and what is still due', () => {
    const host: HTMLElement = fixture.nativeElement;
    expect(wallet.statementPayments).toHaveBeenCalledWith(TENANT_ID);
    const augustRow = [...host.querySelectorAll('tr.statement-row')].find((row) =>
      row.textContent?.includes('S-2026-08-000001'),
    ) as HTMLElement;
    expect(
      augustRow
        .querySelector('[data-testid="statement-paid"]')
        ?.textContent?.replace(/\u00a0/g, ' '),
    ).toContain('1 000 000');
    expect(
      augustRow
        .querySelector('[data-testid="statement-due"]')
        ?.textContent?.replace(/\u00a0/g, ' '),
    ).toContain('500 000');
    // A void statement is not in the wallet's list: a dash, not a made-up zero.
    const julyRow = [...host.querySelectorAll('tr.statement-row')].find((row) =>
      row.textContent?.includes('S-2026-07-000001'),
    ) as HTMLElement;
    expect(julyRow.querySelector('[data-testid="statement-due"]')?.textContent?.trim()).toBe('—');
  });

  it('shows dashes, not an error, when this role may not read the wallet', async () => {
    wallet.statementPayments.mockRejectedValue(
      new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null),
    );
    fixture = TestBed.createComponent(SubscriptionPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
    const host: HTMLElement = fixture.nativeElement;
    expect(host.querySelector('[data-testid="statement-due"]')?.textContent?.trim()).toBe('—');
    expect(host.textContent).toContain('S-2026-08-000001');
  });
  // ------------------------------------------- the wallet at a glance (IA 8.6)

  it('shows both balances and a link to the wallet, so this tab is not blind to the prepaid half', () => {
    const host: HTMLElement = fixture.nativeElement;
    expect(wallet.overview).toHaveBeenCalledWith(TENANT_ID);
    const balances = (
      host.querySelector('[data-testid="subscription-wallet-balances"]')?.textContent ?? ''
    ).replace(/\u00a0/g, ' ');
    expect(balances).toContain('2 000 000');
    expect(balances).toContain('300 000');
    expect(
      (
        host.querySelector('[data-testid="subscription-wallet-link"]') as HTMLAnchorElement
      ).getAttribute('href'),
    ).toBe('/finance/wallet');
    expect(host.querySelector('[data-testid="subscription-wallet-lapsing"]')).toBeNull();
  });

  it('warns here too about bonus credit that is about to lapse, with the amount and the day', async () => {
    wallet.overview.mockResolvedValue({
      ...WALLET_OVERVIEW,
      lapsingGrants: [
        {
          grantId: 'g1',
          remaining: { amountMinor: 200_000, currency: 'UZS' },
          expiresAt: '2026-10-14T00:00:00Z',
        },
      ],
    });
    fixture = TestBed.createComponent(SubscriptionPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
    const warning = (
      fixture.nativeElement.querySelector('[data-testid="subscription-wallet-lapsing"]')
        ?.textContent ?? ''
    ).replace(/\u00a0/g, ' ');
    expect(warning).toContain('Bonus credit that is about to lapse');
    expect(warning).toContain('200 000');
    expect(warning).toContain('14.10.2026');
  });

  it('shows no wallet card, and no error, to a role that may not read the wallet', async () => {
    wallet.overview.mockRejectedValue(
      new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null),
    );
    fixture = TestBed.createComponent(SubscriptionPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
    const host: HTMLElement = fixture.nativeElement;
    expect(host.querySelector('[data-testid="subscription-wallet"]')).toBeNull();
    expect(host.textContent).toContain('S-2026-08-000001');
  });
});

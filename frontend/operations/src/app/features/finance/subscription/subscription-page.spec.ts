import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { CurrentTenant } from '../../../core/auth/current-tenant';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import {
  CommercialApi,
  EntitlementSnapshotView,
  StatementView,
  SubscriptionView,
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
});

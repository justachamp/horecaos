import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { CurrentTenant } from '../../../core/auth/current-tenant';
import { OrderLookupApi } from '../../../core/api/order-lookup-api';
import { I18n } from '../../../core/i18n/i18n';
import { SessionCapabilities } from '../../../core/auth/session-capabilities';
import { FiscalApi, FiscalCoverageView, FiscalDocumentView } from './fiscal-api';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { FiscalCorrectionRequestView, FiscalEvidenceApi } from './fiscal-evidence-api';
import { FiscalPage } from './fiscal-page';

const TENANT_ID = 'tenant-1';

const BLOCKED_DOCUMENT: FiscalDocumentView = {
  documentId: 'doc-1',
  orderId: 'order-7',
  publicOrderNumber: '0055',
  legalEntityId: 'entity-1',
  documentType: 'SALE',
  responsibility: 'PLATFORM',
  providerType: 'CLICK',
  status: 'BLOCKED',
  reasonCode: 'PROVIDER_REPORT_OVERDUE',
  reasonNote: null,
  hasEvidence: false,
  attemptCount: 2,
  version: 3,
  submittedAt: '2026-08-20T10:00:00Z',
  reportingDeadlineAt: '2026-08-27T10:00:00Z',
  blockedAt: '2026-08-27T10:00:01Z',
};

const COVERAGE: FiscalCoverageView = {
  from: '2026-08-01T00:00:00Z',
  to: '2026-08-30T00:00:00Z',
  saleDocuments: 100,
  issued: 20,
  notApplicable: 70,
  notApplicableCash: 70,
  blocked: 5,
  failed: 3,
  awaitingProvider: 2,
  unreceipted: 10,
  issuedShareBasisPoints: 2000,
  notApplicableShareBasisPoints: 7000,
  unreceiptedShareBasisPoints: 1000,
  providerPathIsMinority: true,
  warning: null,
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

describe('FiscalPage', () => {
  let fixture: ComponentFixture<FiscalPage>;
  let api: {
    blocked: ReturnType<typeof vi.fn>;
    forOrder: ReturnType<typeof vi.fn>;
    coverage: ReturnType<typeof vi.fn>;
    retry: ReturnType<typeof vi.fn>;
    unblock: ReturnType<typeof vi.fn>;
  };
  let orderLookup: { byNumber: ReturnType<typeof vi.fn> };
  let evidenceApi: {
    forOrder: ReturnType<typeof vi.fn>;
    openCorrections: ReturnType<typeof vi.fn>;
  };

  beforeEach(async () => {
    api = {
      blocked: vi
        .fn()
        .mockResolvedValue({ count: 1, documents: [BLOCKED_DOCUMENT], warning: null }),
      forOrder: vi.fn().mockResolvedValue([]),
      coverage: vi.fn().mockResolvedValue(COVERAGE),
      retry: vi.fn().mockResolvedValue({
        documentId: 'doc-1',
        outcome: 'SUBMITTED',
        version: 4,
        warning: null,
      }),
      unblock: vi
        .fn()
        .mockResolvedValue({ documentId: 'doc-1', outcome: 'PENDING', version: 4, warning: null }),
    };
    orderLookup = { byNumber: vi.fn().mockResolvedValue([]) };
    evidenceApi = {
      openCorrections: vi.fn().mockResolvedValue([]),
      forOrder: vi.fn().mockResolvedValue({
        orderId: 'order-42',
        publicOrderNumber: '0099',
        retention: { years: 7, provisional: true },
        marks: { state: 'NOT_BUILT', required: null, captured: null },
        revealed: false,
        purpose: null,
        documents: [],
      }),
    };

    await TestBed.configureTestingModule({
      imports: [FiscalPage],
      providers: [
        { provide: FiscalApi, useValue: api },
        { provide: OrderLookupApi, useValue: orderLookup },
        { provide: CurrentTenant, useValue: new FakeCurrentTenant() },
        { provide: FiscalEvidenceApi, useValue: evidenceApi },
        { provide: SessionCapabilities, useValue: { has: () => false } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(FiscalPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  });

  it('lists the blocked worklist and the coverage report on load', () => {
    expect(api.blocked).toHaveBeenCalledWith(TENANT_ID, undefined);
    expect(api.coverage).toHaveBeenCalledWith(TENANT_ID, expect.any(String), expect.any(String));
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('0055');
    expect(text).not.toContain('order-7');
    expect(text).toContain('PROVIDER_REPORT_OVERDUE');
  });

  it('falls back to the raw order id when the order-number projection is null', async () => {
    api.blocked.mockResolvedValue({
      count: 1,
      documents: [{ ...BLOCKED_DOCUMENT, publicOrderNumber: null }],
      warning: null,
    });
    fixture = TestBed.createComponent(FiscalPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('order-7');
  });

  it('never renders a fiscal sign, receipt URL or marking code', () => {
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).not.toContain('qrCodeURL');
    expect(text).not.toContain('markingCode');
  });

  it('reloads the worklist filtered by reason when a reason code is chosen', async () => {
    const select = fixture.nativeElement.querySelector('select.filter') as HTMLSelectElement;
    select.value = 'MARKS_INCOMPLETE';
    select.dispatchEvent(new Event('change'));
    await flushMicrotasks();

    expect(api.blocked).toHaveBeenLastCalledWith(TENANT_ID, 'MARKS_INCOMPLETE');
  });

  it('retries a blocked document with the reason and its version as If-Match', async () => {
    const retryButton = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((button) => button.textContent?.trim() === 'Ask again') as HTMLButtonElement;
    retryButton.click();
    fixture.detectChanges();

    const reasonInput = fixture.nativeElement.querySelector('#resolve-reason') as HTMLInputElement;
    reasonInput.value = 'Payme reporting window reopened';
    reasonInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const submit = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.form__actions button'),
    ).find((button) => button.textContent?.trim() === 'Ask again') as HTMLButtonElement;
    submit.click();
    await flushMicrotasks();

    expect(api.retry).toHaveBeenCalledWith(
      TENANT_ID,
      'doc-1',
      3,
      'Payme reporting window reopened',
    );
  });

  it('reports coverage as counts and shares, never as one collapsed figure', () => {
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('20');
    expect(text).toContain('20.0%');
    expect(text).toContain('70');
    expect(text).toContain('70.0%');
    expect(text).toContain('minority');
  });

  it('looks up an order by number and calls the previously unwired FiscalApi.forOrder', async () => {
    orderLookup.byNumber.mockResolvedValue([
      {
        orderId: 'order-42',
        publicOrderNumber: '0099',
        brandId: 'brand-1',
        locationId: 'loc-1',
        status: 'CONFIRMED',
        total: { amountMinor: 30_000, currency: 'UZS' },
        createdAt: '2026-08-30T09:00:00Z',
      },
    ]);
    api.forOrder.mockResolvedValue([{ ...BLOCKED_DOCUMENT, orderId: 'order-42' }]);

    const input = fixture.nativeElement.querySelector('#fiscal-order-number') as HTMLInputElement;
    input.value = '0099';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (
      Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find(
        (button) => button.textContent?.trim() === 'Find',
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(orderLookup.byNumber).toHaveBeenCalledWith(TENANT_ID, '0099');
    expect(api.forOrder).toHaveBeenCalledWith(TENANT_ID, 'order-42');
  });

  it('draws the evidence of the order that was looked up beneath its documents (row X.2)', async () => {
    orderLookup.byNumber.mockResolvedValue([
      {
        orderId: 'order-42',
        publicOrderNumber: '0099',
        brandId: 'brand-1',
        locationId: 'loc-1',
        status: 'CONFIRMED',
        total: { amountMinor: 30_000, currency: 'UZS' },
        createdAt: '2026-08-30T09:00:00Z',
      },
    ]);
    api.forOrder.mockResolvedValue([{ ...BLOCKED_DOCUMENT, orderId: 'order-42' }]);

    const input = fixture.nativeElement.querySelector('#fiscal-order-number') as HTMLInputElement;
    input.value = '0099';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (
      Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find(
        (button) => button.textContent?.trim() === 'Find',
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(evidenceApi.forOrder).toHaveBeenCalledWith(TENANT_ID, 'order-42');
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="fiscal-evidence"]'),
    ).not.toBeNull();
  });

  describe('the correction and void requests waiting for the fiscal agent', () => {
    const DAY = 86_400_000;

    function request(
      overrides: Partial<FiscalCorrectionRequestView> = {},
    ): FiscalCorrectionRequestView {
      return {
        requestId: 'req-1',
        documentId: 'doc-1',
        orderId: 'order-42',
        publicOrderNumber: '0099',
        kind: 'CORRECTION',
        reasonCode: 'WRONG_AMOUNT',
        status: 'REQUESTED',
        requestedBy: 'finance-1',
        approvalId: 'approval-1',
        requestedAt: new Date(Date.now() - 3 * DAY - 3_600_000).toISOString(),
        attachedDocumentId: null,
        attachedAt: null,
        version: 1,
        ...overrides,
      };
    }

    async function reload(): Promise<void> {
      fixture = TestBed.createComponent(FiscalPage);
      fixture.detectChanges();
      await flushMicrotasks();
      fixture.detectChanges();
    }

    const byTestId = (id: string): HTMLElement | null =>
      (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${id}"]`);

    it('reads them for the tenant on load, and says when there are none', () => {
      expect(evidenceApi.openCorrections).toHaveBeenCalledWith(TENANT_ID);
      expect(byTestId('fiscal-requests-empty')).not.toBeNull();
      expect(byTestId('fiscal-request-row')).toBeNull();
    });

    it('names each request by the order’s number, in words, with the days it has waited', async () => {
      evidenceApi.openCorrections.mockResolvedValue([
        request(),
        request({
          requestId: 'req-2',
          orderId: 'order-77',
          publicOrderNumber: '0100',
          kind: 'VOID',
          reasonCode: 'DUPLICATE_RECEIPT',
          requestedAt: new Date(Date.now() - 20 * 60_000).toISOString(),
        }),
      ]);
      await reload();

      const rows = Array.from(
        (fixture.nativeElement as HTMLElement).querySelectorAll(
          '[data-testid="fiscal-request-row"]',
        ),
      ) as HTMLElement[];
      expect(rows).toHaveLength(2);
      const text = (row: HTMLElement) => (row.textContent ?? '').replace(/\s+/g, ' ');
      expect(text(rows[0])).toContain('0099');
      expect(text(rows[0])).not.toContain('order-42');
      expect(text(rows[0])).toContain('Correct the receipt');
      expect(text(rows[0])).toContain('Wrong amount');
      expect(rows[0].querySelector('[data-testid="fiscal-request-age"]')?.textContent?.trim()).toBe(
        '3 d',
      );
      expect(text(rows[1])).toContain('Void the receipt');
      expect(text(rows[1])).toContain('Duplicate receipt');
      expect(
        rows[1].querySelector('[data-testid="fiscal-request-age"]')?.textContent?.trim(),
        'a request raised twenty minutes ago has waited no days',
      ).toBe('0 d');
    });

    it('shows a reason this client does not know as it came, rather than hiding the request', async () => {
      evidenceApi.openCorrections.mockResolvedValue([request({ reasonCode: 'SOMETHING_NEW' })]);
      await reload();

      expect(byTestId('fiscal-request-row')?.textContent).toContain('SOMETHING_NEW');
    });

    it('opens that order’s documents and evidence, where the agent’s reference is attached', async () => {
      evidenceApi.openCorrections.mockResolvedValue([request()]);
      api.forOrder.mockResolvedValue([{ ...BLOCKED_DOCUMENT, orderId: 'order-42' }]);
      await reload();

      (byTestId('fiscal-request-open') as HTMLButtonElement).click();
      await flushMicrotasks();
      fixture.detectChanges();
      await flushMicrotasks();
      fixture.detectChanges();

      expect(api.forOrder).toHaveBeenCalledWith(TENANT_ID, 'order-42');
      expect(evidenceApi.forOrder).toHaveBeenCalledWith(TENANT_ID, 'order-42');
      expect(
        (fixture.nativeElement as HTMLElement).querySelector('[data-testid="fiscal-evidence"]'),
      ).not.toBeNull();
      expect((fixture.nativeElement as HTMLElement).textContent).toContain('Order 0099');
    });

    it('says it plainly when the role cannot read them, and when the read fails', async () => {
      evidenceApi.openCorrections.mockRejectedValue(
        new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null),
      );
      await reload();
      expect(byTestId('fiscal-requests-denied')).not.toBeNull();

      evidenceApi.openCorrections.mockRejectedValue(
        new ApiError('INTERNAL_ERROR', 500, null, null),
      );
      await reload();
      expect(byTestId('fiscal-requests-error')).not.toBeNull();
      expect(byTestId('fiscal-requests-denied')).toBeNull();
    });
  });

  it('reads no evidence until an order has been looked up', () => {
    expect(evidenceApi.forOrder).not.toHaveBeenCalled();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="fiscal-evidence"]'),
    ).toBeNull();
  });

  it('shows a denied state when the session names no tenant', async () => {
    await TestBed.resetTestingModule()
      .configureTestingModule({
        imports: [FiscalPage],
        providers: [
          { provide: FiscalApi, useValue: api },
          { provide: OrderLookupApi, useValue: orderLookup },
          { provide: FiscalEvidenceApi, useValue: evidenceApi },
          { provide: SessionCapabilities, useValue: { has: () => false } },
          {
            provide: CurrentTenant,
            useValue: {
              tenantId: signal(null),
              denied: signal(true),
              ensureLoaded: vi.fn().mockResolvedValue(undefined),
            },
          },
        ],
      })
      .compileComponents();
    TestBed.inject(I18n).setLocale('en');
    const deniedFixture = TestBed.createComponent(FiscalPage);
    deniedFixture.detectChanges();
    await flushMicrotasks();
    deniedFixture.detectChanges();

    expect((deniedFixture.nativeElement as HTMLElement).textContent).toContain('not permitted');
  });
});

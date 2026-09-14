import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { CurrentTenant } from '../../../core/auth/current-tenant';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import {
  CourierFinanceApi,
  DeliveryCostReport,
  PartnerInvoiceDetailView,
  PartnerInvoiceLineView,
  PartnerInvoiceView,
} from '../courier-finance-api';
import { DeliveryCostPage } from './delivery-cost-page';

function report(overrides: Partial<DeliveryCostReport> = {}): DeliveryCostReport {
  return {
    basis: 'ACCRUED',
    from: '2026-08-01',
    to: '2026-08-31',
    internalMinor: 400_000,
    partnerMinor: 220_000,
    totalMinor: 620_000,
    shipmentsWithoutThisBasis: 0,
    byPath: [],
    ...overrides,
  };
}

function invoice(overrides: Partial<PartnerInvoiceView> = {}): PartnerInvoiceView {
  return {
    invoiceId: 'invoice-1',
    providerCode: 'NOOR',
    providerInvoiceRef: 'INV-100',
    legalEntityId: null,
    periodStart: '2026-08-01',
    periodEnd: '2026-08-31',
    totalMinor: 45_000,
    currency: 'UZS',
    status: 'MATCHED',
    ...overrides,
  };
}

function invoiceLine(overrides: Partial<PartnerInvoiceLineView> = {}): PartnerInvoiceLineView {
  return {
    lineId: 'line-1',
    providerShipmentRef: 'NOOR-1',
    shipmentId: null,
    amountMinor: 25_000,
    currency: 'UZS',
    chargeType: 'DELIVERY',
    matchStatus: 'UNMATCHED_LINE',
    varianceMinor: null,
    reasonCode: null,
    varianceResolution: null,
    ...overrides,
  };
}

class FakeCurrentTenant {
  readonly tenantId = signal<string | null>('tenant-1');
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('DeliveryCostPage', () => {
  let fixture: ComponentFixture<DeliveryCostPage>;
  let api: {
    deliveryCosts: ReturnType<typeof vi.fn>;
    partnerInvoices: ReturnType<typeof vi.fn>;
    partnerInvoiceDetail: ReturnType<typeof vi.fn>;
    importInvoice: ReturnType<typeof vi.fn>;
    matchInvoice: ReturnType<typeof vi.fn>;
    disputeInvoice: ReturnType<typeof vi.fn>;
    resolveVariance: ReturnType<typeof vi.fn>;
  };

  async function render(
    invoices: readonly PartnerInvoiceView[],
    reportResult: DeliveryCostReport = report(),
    tenant: FakeCurrentTenant = new FakeCurrentTenant(),
  ): Promise<void> {
    api = {
      deliveryCosts: vi.fn().mockResolvedValue(reportResult),
      partnerInvoices: vi.fn().mockResolvedValue(invoices),
      partnerInvoiceDetail: vi.fn(),
      importInvoice: vi.fn().mockResolvedValue('new-invoice'),
      matchInvoice: vi
        .fn()
        .mockResolvedValue({ matchedLines: 1, varianceLineIds: [], unmatchedLineIds: [] }),
      disputeInvoice: vi.fn().mockResolvedValue(undefined),
      resolveVariance: vi
        .fn()
        .mockResolvedValue(
          invoiceLine({ matchStatus: 'VARIANCE', varianceResolution: 'ACCEPTED' }),
        ),
    };
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [DeliveryCostPage],
      providers: [
        { provide: CourierFinanceApi, useValue: api },
        { provide: CurrentTenant, useValue: tenant },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(DeliveryCostPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  function host(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  it('renders the internal, partner and total cost tiles', async () => {
    await render([]);
    const text = host().textContent ?? '';
    expect(text).toContain('400'); // internal
    expect(text).toContain('220'); // partner
    expect(text).toContain('620'); // total
  });

  it('computes the charged-vs-cost margin from ACCRUED and INVOICED over the same range, not a new endpoint', async () => {
    await render(
      [],
      // deliveryCosts is called for the main basis and, separately, for the
      // margin's own ACCRUED/INVOICED pair — the fake always answers with
      // this one report, so the margin call is proven by the call count.
      report(),
    );
    // basis (ACCRUED) + margin's ACCRUED + margin's INVOICED = 3 calls.
    expect(api.deliveryCosts).toHaveBeenCalledTimes(3);
    expect(api.deliveryCosts).toHaveBeenCalledWith(
      'tenant-1',
      'ACCRUED',
      expect.any(String),
      expect.any(String),
    );
    expect(api.deliveryCosts).toHaveBeenCalledWith(
      'tenant-1',
      'INVOICED',
      expect.any(String),
      expect.any(String),
    );
  });

  describe('matchStatus renders localized, never the raw Java enum name', () => {
    async function openDetail(matchStatus: PartnerInvoiceLineView['matchStatus']): Promise<void> {
      await render([invoice()]);
      const detail: PartnerInvoiceDetailView = {
        invoice: invoice(),
        lines: [invoiceLine({ matchStatus, providerShipmentRef: 'NOOR-XYZ' })],
      };
      api.partnerInvoiceDetail.mockResolvedValue(detail);
      (host().querySelector('tr.row td') as HTMLElement).click();
      await flushMicrotasks();
      fixture.detectChanges();
    }

    it('renders UNMATCHED_LINE as its localized label, not the raw enum', async () => {
      await openDetail('UNMATCHED_LINE');
      const text = host().textContent ?? '';
      expect(text).toContain('No matching shipment');
      expect(text).not.toContain('UNMATCHED_LINE');
    });

    it('renders VARIANCE as its localized label, not the raw enum', async () => {
      await openDetail('VARIANCE');
      const text = host().textContent ?? '';
      expect(text).toContain('Variance');
      expect(text).not.toContain('"VARIANCE"');
      expect(host().querySelector('.match-status')?.textContent?.trim()).toBe('Variance');
    });

    it('renders MATCHED as its localized label, not the raw enum', async () => {
      await openDetail('MATCHED');
      expect(host().querySelector('.match-status')?.textContent?.trim()).toBe('Matched');
    });
  });

  describe('resolving an UNMATCHED_LINE — the resolution UI for a caller-supplied match', () => {
    it('calls match with only the resolved line’s reference, not the whole invoice map', async () => {
      await render([invoice()]);
      const detail: PartnerInvoiceDetailView = {
        invoice: invoice(),
        lines: [invoiceLine({ matchStatus: 'UNMATCHED_LINE', providerShipmentRef: 'NOOR-9' })],
      };
      api.partnerInvoiceDetail.mockResolvedValue(detail);
      (host().querySelector('tr.row td') as HTMLElement).click();
      await flushMicrotasks();
      fixture.detectChanges();

      (host().querySelector('.detail-row button.secondary') as HTMLButtonElement).click();
      fixture.detectChanges();

      const shipmentInput = host().querySelector('.panel input[type="text"]') as HTMLInputElement;
      shipmentInput.value = 'shipment-42';
      shipmentInput.dispatchEvent(new Event('input'));
      const reasonInput = host().querySelectorAll(
        '.panel input[type="text"]',
      )[1] as HTMLInputElement;
      reasonInput.value = 'found the shipment';
      reasonInput.dispatchEvent(new Event('input'));
      fixture.detectChanges();

      (host().querySelector('.panel .panel__actions .primary') as HTMLButtonElement).click();
      await flushMicrotasks();
      fixture.detectChanges();

      expect(api.matchInvoice).toHaveBeenCalledWith(
        'tenant-1',
        'invoice-1',
        { 'NOOR-9': 'shipment-42' },
        'found the shipment',
      );
    });
  });

  describe('importing a partner invoice', () => {
    it('submits the form with an integer total and its lines, never a string amount', async () => {
      await render([]);
      (host().querySelector('.invoices-header button') as HTMLButtonElement).click();
      fixture.detectChanges();

      const inputs = Array.from(
        host().querySelectorAll('.import-panel input'),
      ) as HTMLInputElement[];
      const [providerInput, refInput, currencyInput, totalInput] = inputs;
      providerInput.value = 'YANDEX';
      providerInput.dispatchEvent(new Event('input'));
      refInput.value = 'Y-500';
      refInput.dispatchEvent(new Event('input'));
      currencyInput.value = 'UZS';
      currencyInput.dispatchEvent(new Event('input'));
      totalInput.value = '10000';
      totalInput.dispatchEvent(new Event('input'));

      const lineRef = host().querySelector(
        '.lines-table tbody input[type="text"]',
      ) as HTMLInputElement;
      lineRef.value = 'Y-500-1';
      lineRef.dispatchEvent(new Event('input'));
      const lineAmount = host().querySelector(
        '.lines-table tbody input[type="number"]',
      ) as HTMLInputElement;
      lineAmount.value = '10000';
      lineAmount.dispatchEvent(new Event('input'));

      const reasonInput = host().querySelector(
        '.import-panel > label.field input',
      ) as HTMLInputElement;
      reasonInput.value = 'monthly settlement file';
      reasonInput.dispatchEvent(new Event('input'));
      fixture.detectChanges();

      (host().querySelector('.import-panel .panel__actions .primary') as HTMLButtonElement).click();
      await flushMicrotasks();
      fixture.detectChanges();

      expect(api.importInvoice).toHaveBeenCalledWith(
        'tenant-1',
        expect.objectContaining({
          providerCode: 'YANDEX',
          providerInvoiceRef: 'Y-500',
          totalMinor: 10_000,
          currency: 'UZS',
          lines: [{ providerShipmentRef: 'Y-500-1', amountMinor: 10_000, chargeType: 'DELIVERY' }],
          reason: 'monthly settlement file',
        }),
      );
    });
  });

  it('disputes an invoice with the typed reason', async () => {
    await render([invoice({ status: 'MATCHED' })]);
    (host().querySelector('td.actions button') as HTMLButtonElement).click();
    fixture.detectChanges();

    const reasonInput = host().querySelector('.panel input[type="text"]') as HTMLInputElement;
    reasonInput.value = 'amount disagrees with our booking';
    reasonInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    (host().querySelector('.panel .panel__actions .primary') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.disputeInvoice).toHaveBeenCalledWith(
      'tenant-1',
      'invoice-1',
      'amount disagrees with our booking',
    );
  });

  it('surfaces a dispute failure honestly, never silently', async () => {
    await render([invoice({ status: 'MATCHED' })]);
    api.disputeInvoice.mockRejectedValue(
      new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null),
    );
    (host().querySelector('td.actions button') as HTMLButtonElement).click();
    fixture.detectChanges();

    const reasonInput = host().querySelector('.panel input[type="text"]') as HTMLInputElement;
    reasonInput.value = 'reason';
    reasonInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (host().querySelector('.panel .panel__actions .primary') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    const message = host().querySelector('.panel .error-text')?.textContent ?? '';
    expect(message.length).toBeGreaterThan(0);
    expect(message).not.toContain('INSUFFICIENT_CAPABILITY');
  });
});

import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { WalletApi } from './wallet-api';
import { WalletInvoicesPanel } from './wallet-invoices-panel';
import { INVOICE_OPEN, INVOICE_PART_PAID, TENANT_ID } from './wallet-fixtures.testing';

async function settle(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

@Component({
  selector: 'q-host',
  imports: [WalletInvoicesPanel],
  template: `<q-wallet-invoices-panel
    [tenantId]="tenantId"
    [revision]="revision()"
    (changed)="changedCount = changedCount + 1"
  />`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
class Host {
  tenantId = TENANT_ID;
  revision = signal(0);
  changedCount = 0;
}

describe('WalletInvoicesPanel', () => {
  let fixture: ComponentFixture<Host>;
  let host: Host;
  let api: {
    invoices: ReturnType<typeof vi.fn>;
    invoiceExport: ReturnType<typeof vi.fn>;
    cancelInvoice: ReturnType<typeof vi.fn>;
  };

  const root = (): HTMLElement => fixture.nativeElement;
  const q = (testid: string): HTMLElement | null =>
    root().querySelector(`[data-testid="${testid}"]`);
  const row = (number: string): HTMLElement =>
    root().querySelector(`tr[data-invoice="${number}"]`) as HTMLElement;
  const flush = async (): Promise<void> => {
    await settle();
    fixture.detectChanges();
  };

  beforeEach(async () => {
    api = {
      invoices: vi.fn().mockResolvedValue([INVOICE_OPEN, INVOICE_PART_PAID]),
      invoiceExport: vi.fn().mockResolvedValue('number\r\n"PI-1"\r\n'),
      cancelInvoice: vi.fn().mockResolvedValue({ ...INVOICE_OPEN, status: 'CANCELLED' }),
    };
    await TestBed.configureTestingModule({
      imports: [Host],
      providers: [{ provide: WalletApi, useValue: api }],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
    fixture.detectChanges();
    await flush();
  });

  it('lists each invoice with what the ledger says it has been paid, and its status in words', () => {
    expect(api.invoices).toHaveBeenCalledWith(TENANT_ID);
    const open = (row('PI-202610-000001').textContent ?? '').replace(/[\u00a0]/g, ' ');
    expect(open).toContain('Waiting for payment');
    expect(open).toContain('1 000 000');
    const part = row('PI-202609-000007').textContent ?? '';
    expect(part).toContain('Partly paid');
    expect(part.replace(/[ ]/g, ' ')).toContain('400 000');
    expect(part.replace(/[ ]/g, ' ')).toContain('600 000');
  });

  it('says every figure is before tax, and that an invoice here is not a tax invoice', () => {
    expect(root().textContent).toContain('not a tax invoice');
  });

  it('opens an invoice to show the bank details it was frozen with and the purpose to write', () => {
    (
      row('PI-202610-000001').querySelector('[data-testid="wallet-invoice-toggle"]') as HTMLElement
    ).click();
    fixture.detectChanges();
    const details = root().querySelector('.invoice-details')?.textContent ?? '';
    expect(details).toContain('PI-202610-000001');
    expect(details).toContain('20208000100000000001');
    expect(details).toContain('HorecaOS LLC');
  });

  it('offers Withdraw only on an open invoice with nothing paid', () => {
    expect(
      row('PI-202610-000001').querySelector('[data-testid="wallet-invoice-cancel"]'),
    ).not.toBeNull();
    expect(
      row('PI-202609-000007').querySelector('[data-testid="wallet-invoice-cancel"]'),
    ).toBeNull();
  });

  it('asks before withdrawing, then withdraws and tells the page', async () => {
    (
      row('PI-202610-000001').querySelector('[data-testid="wallet-invoice-cancel"]') as HTMLElement
    ).click();
    fixture.detectChanges();
    expect(api.cancelInvoice).not.toHaveBeenCalled();
    expect(root().querySelector('[data-testid="q-confirm-dialog"]')?.textContent).toContain(
      'PI-202610-000001',
    );

    (root().querySelector('[data-testid="q-confirm-confirm"]') as HTMLElement).click();
    await flush();
    expect(api.cancelInvoice).toHaveBeenCalledWith(TENANT_ID, 'inv-1');
    expect(host.changedCount).toBe(1);
    expect(root().querySelector('[data-testid="q-confirm-dialog"]')).toBeNull();
  });

  it('keeping the invoice sends nothing', async () => {
    (
      row('PI-202610-000001').querySelector('[data-testid="wallet-invoice-cancel"]') as HTMLElement
    ).click();
    fixture.detectChanges();
    (root().querySelector('[data-testid="q-confirm-cancel"]') as HTMLElement).click();
    await flush();
    expect(api.cancelInvoice).not.toHaveBeenCalled();
  });

  it('shows a refused withdrawal and reads the list again, so a paid invoice stops offering the button', async () => {
    api.cancelInvoice.mockRejectedValueOnce(
      new ApiError(
        ApiErrorCode.RESOURCE_CONFLICT,
        409,
        { status: 409, code: ApiErrorCode.RESOURCE_CONFLICT, reason: 'INVOICE_HAS_PAYMENTS' },
        'corr-1',
      ),
    );
    api.invoices.mockResolvedValue([INVOICE_PART_PAID]);
    (
      row('PI-202610-000001').querySelector('[data-testid="wallet-invoice-cancel"]') as HTMLElement
    ).click();
    fixture.detectChanges();
    (root().querySelector('[data-testid="q-confirm-confirm"]') as HTMLElement).click();
    await flush();

    expect(q('wallet-invoices-error')?.textContent).toContain('Money has already been recorded');
    expect(api.invoices).toHaveBeenCalledTimes(2);
    expect(row('PI-202610-000001')).toBeNull();
    expect(host.changedCount).toBe(0);
  });

  it('downloads the CSV under the invoice’s number', async () => {
    const created: string[] = [];
    const originalCreate = URL.createObjectURL;
    const originalRevoke = URL.revokeObjectURL;
    URL.createObjectURL = () => 'blob:test';
    URL.revokeObjectURL = () => undefined;
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      created.push(this.download);
    });
    try {
      (
        row('PI-202610-000001').querySelector(
          '[data-testid="wallet-invoice-download"]',
        ) as HTMLElement
      ).click();
      await flush();
      expect(api.invoiceExport).toHaveBeenCalledWith(TENANT_ID, 'inv-1');
      expect(created).toEqual(['invoice-PI-202610-000001.csv']);
    } finally {
      click.mockRestore();
      URL.createObjectURL = originalCreate;
      URL.revokeObjectURL = originalRevoke;
    }
  });

  it('says plainly when there is no invoice, and reads again when the page asks', async () => {
    api.invoices.mockResolvedValue([]);
    host.revision.set(1);
    fixture.detectChanges();
    await flush();
    expect(root().textContent).toContain('No invoices yet.');
  });

  it('shows a failed read as an alert', async () => {
    api.invoices.mockRejectedValueOnce(
      new ApiError(ApiErrorCode.INTERNAL_ERROR, 500, null, 'corr-5'),
    );
    host.revision.set(2);
    fixture.detectChanges();
    await flush();
    expect(root().querySelector('[role="alert"]')).not.toBeNull();
  });
});

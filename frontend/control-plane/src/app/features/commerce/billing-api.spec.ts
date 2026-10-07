import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { CommerceApi } from './commerce-api';

const CONFIG: AppConfig = {
  apiBaseUrl: 'https://api.test.horecaos.uz',
  displayTimeZone: 'Asia/Tashkent',
};

const url = (path: string): string => `${CONFIG.apiBaseUrl}${path}`;

/**
 * The staff end of what a tenant pays by (ADR 0095), through a real `HttpClient` against literal URLs:
 * the platform serves reads under `/control-plane` and every write under `/platform-admin`, and a spec that
 * compared a request to the builder that made it could not fail on a wrong prefix.
 */
describe('CommerceApi billing setup, invoices and top-ups', () => {
  let api: CommerceApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: APP_CONFIG, useValue: CONFIG },
      ],
    });
    api = TestBed.inject(CommerceApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('reads a tenant’s prepayment invoices and card top-ups under control-plane', async () => {
    const invoices = api.prepaymentInvoices('t1');
    http.expectOne(url('/api/v1/control-plane/tenants/t1/wallet/invoices')).flush([]);
    await expect(invoices).resolves.toEqual([]);

    const topUps = api.cardTopUps('t1');
    http.expectOne(url('/api/v1/control-plane/tenants/t1/wallet/top-ups')).flush([]);
    await expect(topUps).resolves.toEqual([]);
  });

  it('withdraws an invoice with a reason, under platform-admin, keyed', async () => {
    const promise = api.cancelPrepaymentInvoice('t1', 'inv-1', 'asked twice');
    const request = http.expectOne(
      url('/api/v1/platform-admin/commercial/tenants/t1/wallet/invoices/inv-1/cancel'),
    );
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ reason: 'asked twice' });
    expect(request.request.headers.has('Idempotency-Key')).toBe(true);
    request.flush({ status: 'CANCELLED' });
    await promise;
  });

  it('records a transfer against the invoice it pays, and says nothing of an invoice when it pays none', async () => {
    const paying = api.recordTransfer('t1', {
      amountMinor: 1_000_000,
      bankReference: 'MT103-9001',
      reason: 'August transfer',
      prepaymentInvoiceNumber: 'PI-202610-000001',
    });
    const request = http.expectOne(
      url('/api/v1/platform-admin/commercial/tenants/t1/wallet/transfers'),
    );
    expect(request.request.body).toEqual({
      amountMinor: 1_000_000,
      bankReference: 'MT103-9001',
      reason: 'August transfer',
      prepaymentInvoiceNumber: 'PI-202610-000001',
    });
    request.flush({ entryId: 'w-1' });
    await paying;

    const plain = api.recordTransfer('t1', { amountMinor: 5, bankReference: 'x', reason: 'y' });
    const second = http.expectOne(
      url('/api/v1/platform-admin/commercial/tenants/t1/wallet/transfers'),
    );
    expect('prepaymentInvoiceNumber' in (second.request.body as object)).toBe(false);
    second.flush({ entryId: 'w-2' });
    await plain;
  });

  it('reads the bank details under control-plane and proposes them under platform-admin', async () => {
    const read = api.bankDetails();
    http.expectOne(url('/api/v1/control-plane/billing/bank-details')).flush({ configured: false });
    await expect(read).resolves.toEqual({ configured: false });

    const proposal = {
      beneficiary: 'HorecaOS LLC',
      bankName: 'Example Bank',
      account: '20208000100000000001',
      mfo: '00014',
      taxId: '300000001',
      reason: 'the account was opened',
    };
    const proposed = api.proposeBankDetails(proposal);
    const request = http.expectOne(url('/api/v1/platform-admin/commercial/billing/bank-details'));
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual(proposal);
    expect(request.request.headers.has('Idempotency-Key')).toBe(true);
    request.flush({ status: 'AWAITING_APPROVAL', approvalRequestId: 'ap-1' });
    await expect(proposed).resolves.toEqual({
      status: 'AWAITING_APPROVAL',
      approvalRequestId: 'ap-1',
    });
  });

  it('sends a fresh key for each proposal, so the identical call after approval is executed and not replayed', async () => {
    const proposal = {
      beneficiary: 'HorecaOS LLC',
      bankName: 'Example Bank',
      account: '20208000100000000001',
      mfo: '00014',
      taxId: '300000001',
      reason: 'the account was opened',
    };
    const first = api.proposeBankDetails(proposal);
    const one = http.expectOne(url('/api/v1/platform-admin/commercial/billing/bank-details'));
    one.flush({ status: 'AWAITING_APPROVAL', approvalRequestId: 'ap-1' });
    await first;
    const second = api.proposeBankDetails(proposal);
    const two = http.expectOne(url('/api/v1/platform-admin/commercial/billing/bank-details'));
    two.flush({ status: 'CHANGED', approvalRequestId: 'ap-1' });
    await second;
    expect(two.request.headers.get('Idempotency-Key')).not.toBe(
      one.request.headers.get('Idempotency-Key'),
    );
  });

  it('manages card merchant accounts: read, declare, activate and suspend at their own paths', async () => {
    const list = api.cardInstallations();
    http.expectOne(url('/api/v1/control-plane/billing/card-installations')).flush([]);
    await expect(list).resolves.toEqual([]);

    const created = api.createCardInstallation({
      providerType: 'FAKE_CARD',
      displayName: 'Test account',
      reason: 'local walk-through',
    });
    const create = http.expectOne(
      url('/api/v1/platform-admin/commercial/billing/card-installations'),
    );
    expect(create.request.body).toEqual({
      providerType: 'FAKE_CARD',
      displayName: 'Test account',
      reason: 'local walk-through',
    });
    create.flush({ installationId: 'ci-1' });
    await created;

    const activated = api.activateCardInstallation('ci-1', 4, 'go live');
    const activation = http.expectOne(
      url('/api/v1/platform-admin/commercial/billing/card-installations/ci-1/activation'),
    );
    expect(activation.request.body).toEqual({ expectedVersion: 4, reason: 'go live' });
    activation.flush({ status: 'ACTIVE' });
    await activated;

    const suspended = api.suspendCardInstallation('ci-1', 5, 'provider outage');
    const suspension = http.expectOne(
      url('/api/v1/platform-admin/commercial/billing/card-installations/ci-1/suspension'),
    );
    expect(suspension.request.body).toEqual({ expectedVersion: 5, reason: 'provider outage' });
    suspension.flush({ status: 'SUSPENDED' });
    await suspended;
  });
});

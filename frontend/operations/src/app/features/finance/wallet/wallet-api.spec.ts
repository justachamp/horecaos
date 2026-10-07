import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../../environments/environment';
import { command } from '../../../core/api/idempotency';
import { WalletApi } from './wallet-api';

function url(path: string): string {
  return `${environment.apiBaseUrl}${path}`;
}

/**
 * Through a real `HttpClient` against literal expected URLs, for the reason `commercial-api.spec.ts` gives:
 * a spec that compares a request to the builder that made it cannot fail when the prefix is wrong.
 */
describe('WalletApi', () => {
  let api: WalletApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), WalletApi],
    });
    api = TestBed.inject(WalletApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('reads the overview from the tenant’s own wallet path', async () => {
    const promise = api.overview('t1');
    const request = http.expectOne(url('/api/v1/tenants/t1/commercial/wallet'));
    expect(request.request.method).toBe('GET');
    request.flush({ paymentMethod: 'WALLET' });
    await expect(promise).resolves.toEqual({ paymentMethod: 'WALLET' });
  });

  it('pages the ledger by the cursor the server gave', async () => {
    const first = api.ledger('t1');
    http.expectOne(url('/api/v1/tenants/t1/commercial/wallet/ledger')).flush({
      items: [{ entryId: 'e1' }],
      nextCursor: 'c1',
    });
    await expect(first).resolves.toEqual({ items: [{ entryId: 'e1' }], nextCursor: 'c1' });

    const second = api.ledger('t1', 'c1');
    const request = http.expectOne(url('/api/v1/tenants/t1/commercial/wallet/ledger?cursor=c1'));
    request.flush({ items: [], nextCursor: null });
    await expect(second).resolves.toEqual({ items: [], nextCursor: null });
  });

  it('tops up under the command it was given, so a retry replays rather than charges twice', async () => {
    const intent = command({ amountMinor: 150_000 });
    const promise = api.topUp('t1', intent);
    const request = http.expectOne(url('/api/v1/tenants/t1/commercial/wallet/top-ups'));
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('Idempotency-Key')).toBe(intent.key);
    expect(request.request.body).toEqual({ amountMinor: 150_000 });
    request.flush({ outcome: 'SUCCEEDED' });
    await promise;

    const again = api.topUp('t1', intent);
    const retry = http.expectOne(url('/api/v1/tenants/t1/commercial/wallet/top-ups'));
    expect(retry.request.headers.get('Idempotency-Key')).toBe(intent.key);
    retry.flush({ outcome: 'SUCCEEDED' });
    await again;
  });

  it('asks for an invoice under its own command, at the invoices path', async () => {
    const intent = command({ amountMinor: 500_000 });
    const promise = api.issueInvoice('t1', intent);
    const request = http.expectOne(url('/api/v1/tenants/t1/commercial/wallet/invoices'));
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('Idempotency-Key')).toBe(intent.key);
    request.flush({ number: 'PI-202610-000001' });
    await promise;
  });

  it('reads an invoice export as text and withdraws one with a keyed, body-less POST', async () => {
    const csv = api.invoiceExport('t1', 'inv/1');
    http
      .expectOne(url('/api/v1/tenants/t1/commercial/wallet/invoices/inv%2F1/export'))
      .flush('number\r\n"PI-1"\r\n');
    await expect(csv).resolves.toBe('number\r\n"PI-1"\r\n');

    const cancel = api.cancelInvoice('t1', 'inv-1');
    const request = http.expectOne(
      url('/api/v1/tenants/t1/commercial/wallet/invoices/inv-1/cancel'),
    );
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toBeNull();
    expect(request.request.headers.has('Idempotency-Key')).toBe(true);
    request.flush({ status: 'CANCELLED' });
    await cancel;
  });

  it('puts a card on file in two steps and never sends a card number', async () => {
    const begin = api.beginCardEnrolment('t1');
    const opened = http.expectOne(url('/api/v1/tenants/t1/commercial/wallet/card/enrolments'));
    expect(opened.request.method).toBe('POST');
    expect(opened.request.body).toBeNull();
    opened.flush({
      sessionReference: 's1',
      hostedFormUrl: null,
      clientParameters: {},
      expiresAt: 'x',
    });
    await begin;

    const intent = command({
      sessionReference: 's1',
      providerToken: 'tok',
      verificationCode: '000000',
    });
    const confirm = api.confirmCard('t1', intent);
    const confirmed = http.expectOne(
      url('/api/v1/tenants/t1/commercial/wallet/card/confirmations'),
    );
    expect(confirmed.request.headers.get('Idempotency-Key')).toBe(intent.key);
    expect(Object.keys(confirmed.request.body as object).sort()).toEqual([
      'providerToken',
      'sessionReference',
      'verificationCode',
    ]);
    confirmed.flush({ last4: '4242' });
    await confirm;
  });

  it('removes the card and chooses a method at their own paths', async () => {
    const removal = api.removeCard('t1');
    const removed = http.expectOne(url('/api/v1/tenants/t1/commercial/wallet/card/removal'));
    expect(removed.request.method).toBe('POST');
    removed.flush({ paymentMethod: 'INVOICE' });
    await expect(removal).resolves.toEqual({ paymentMethod: 'INVOICE' });

    const choice = api.choosePaymentMethod('t1', 'CARD');
    const chosen = http.expectOne(url('/api/v1/tenants/t1/commercial/wallet/payment-method'));
    expect(chosen.request.body).toEqual({ paymentMethod: 'CARD' });
    chosen.flush({ paymentMethod: 'CARD' });
    await choice;
  });

  it('reads statements’ paid and due, the payment details and the top-up history', async () => {
    const statements = api.statementPayments('t1');
    http.expectOne(url('/api/v1/tenants/t1/commercial/wallet/statements')).flush([]);
    await expect(statements).resolves.toEqual([]);

    const details = api.paymentDetails('t1');
    http
      .expectOne(url('/api/v1/tenants/t1/commercial/wallet/payment-details'))
      .flush({ configured: true });
    await details;

    const topUps = api.topUps('t1');
    http.expectOne(url('/api/v1/tenants/t1/commercial/wallet/top-ups')).flush(null);
    await expect(topUps).resolves.toEqual([]);
  });
});

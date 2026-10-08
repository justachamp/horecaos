import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { CurrentTenant } from '../../../core/auth/current-tenant';
import { I18n } from '../../../core/i18n/i18n';
import { CommercialApi, TenantArrearsView } from '../commercial-api';
import { TenantWalletView, WalletApi } from './wallet-api';
import { WalletPage } from './wallet-page';
import {
  PAYMENT_DETAILS,
  STATEMENT_PAYMENTS,
  TENANT_ID,
  TOP_UP_PENDING,
  WALLET,
  WALLET_NOT_CONNECTED,
  uzs,
} from './wallet-fixtures.testing';

class FakeCurrentTenant {
  readonly tenantId = signal<string | null>(TENANT_ID);
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

async function settle(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

const ARREARS_OWING: TenantArrearsView = {
  status: 'PAST_DUE',
  planEntitlementsApply: true,
  additionsBlocked: false,
  allowedNext: ['ACTIVE', 'SUSPENDED', 'TERMINATED'],
  since: '2026-09-20T00:00:00Z',
  daysInStatus: 17,
  suspensionReason: null,
  latestStatement: null,
  owed: { due: uzs(500_000), openStatements: 1 },
  waysToPay: { cardOnFile: true, cardPaymentsAvailable: true, bankTransferAvailable: true },
};

describe('WalletPage', () => {
  let fixture: ComponentFixture<WalletPage>;
  let api: {
    overview: ReturnType<typeof vi.fn>;
    paymentDetails: ReturnType<typeof vi.fn>;
    topUps: ReturnType<typeof vi.fn>;
    invoices: ReturnType<typeof vi.fn>;
    statementPayments: ReturnType<typeof vi.fn>;
    ledger: ReturnType<typeof vi.fn>;
  };
  let commercial: { arrears: ReturnType<typeof vi.fn> };
  let tenant: FakeCurrentTenant;

  const root = (): HTMLElement => fixture.nativeElement;
  const q = (testid: string): HTMLElement | null =>
    root().querySelector(`[data-testid="${testid}"]`);
  const plain = (text: string | null | undefined): string => (text ?? '').replace(/[ ]/g, ' ');

  async function open(wallet: TenantWalletView = WALLET): Promise<void> {
    api.overview.mockResolvedValue(wallet);
    fixture = TestBed.createComponent(WalletPage);
    fixture.detectChanges();
    await settle();
    fixture.detectChanges();
  }

  beforeEach(async () => {
    tenant = new FakeCurrentTenant();
    api = {
      overview: vi.fn().mockResolvedValue(WALLET),
      paymentDetails: vi.fn().mockResolvedValue(PAYMENT_DETAILS),
      topUps: vi.fn().mockResolvedValue([]),
      invoices: vi.fn().mockResolvedValue([]),
      statementPayments: vi.fn().mockResolvedValue(STATEMENT_PAYMENTS),
      ledger: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
    };
    commercial = {
      arrears: vi.fn().mockResolvedValue({ ...ARREARS_OWING, owed: null, status: 'ACTIVE' }),
    };
    await TestBed.configureTestingModule({
      imports: [WalletPage],
      providers: [
        provideRouter([]),
        { provide: WalletApi, useValue: api },
        { provide: CommercialApi, useValue: commercial },
        { provide: CurrentTenant, useValue: tenant },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
  });

  it('shows the paid balance, the bonus credit and how the tenant is collected', async () => {
    await open();
    expect(api.overview).toHaveBeenCalledWith(TENANT_ID);
    expect(plain(q('wallet-paid-tile')?.textContent)).toContain('2 000 000');
    expect(plain(q('wallet-bonus-tile')?.textContent)).toContain('300 000');
    expect(q('wallet-collected-tile')?.textContent).toContain('Prepaid wallet');
  });

  it('says nothing is not connected when both ways of paying are, and shows no warning that is not true', async () => {
    await open();
    expect(q('wallet-card-not-connected-banner')).toBeNull();
    expect(q('wallet-bank-not-connected-banner')).toBeNull();
    expect(q('wallet-lapsing')).toBeNull();
    expect(q('wallet-owed')).toBeNull();
    expect(q('wallet-pending')).toBeNull();
  });

  it('says honestly that card payments and bank transfer are not connected, and offers no entry point that would only be refused', async () => {
    await open(WALLET_NOT_CONNECTED);
    expect(q('wallet-card-not-connected-banner')?.textContent).toContain(
      'Card payments are not connected yet',
    );
    expect(q('wallet-bank-not-connected-banner')?.textContent).toContain(
      'Bank transfer is not set up yet',
    );
    expect((q('wallet-card-topup-submit') as HTMLButtonElement).disabled).toBe(true);
    expect((q('wallet-card-add') as HTMLButtonElement).disabled).toBe(true);
    expect((q('wallet-invoice-submit') as HTMLButtonElement).disabled).toBe(true);
    // The placeholder bank details are never read, let alone shown as an account.
    expect(api.paymentDetails).not.toHaveBeenCalled();
    expect(q('wallet-bank-details')).toBeNull();
  });

  it('reads the bank details only once they are published, and shows them', async () => {
    await open();
    expect(api.paymentDetails).toHaveBeenCalledWith(TENANT_ID);
    expect(q('wallet-bank-details')?.textContent).toContain('Example Bank');
  });

  it('says the details could not be read, and still lets the tenant ask for an invoice that carries them', async () => {
    api.paymentDetails.mockRejectedValue(
      new ApiError(ApiErrorCode.INTERNAL_ERROR, 500, null, 'corr-8'),
    );
    await open();
    expect(q('wallet-bank-details')).toBeNull();
    expect(q('wallet-bank-details-unread')?.textContent).toContain(
      'Every invoice you ask for carries them',
    );
    expect(q('wallet-bank-not-connected-banner')).toBeNull();
    (q('wallet-invoice-amount') as HTMLInputElement).value = '500000';
    (q('wallet-invoice-amount') as HTMLInputElement).dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect((q('wallet-invoice-submit') as HTMLButtonElement).disabled).toBe(false);
  });

  it('does not show details the server still marks as the placeholder', async () => {
    api.paymentDetails.mockResolvedValue({
      ...PAYMENT_DETAILS,
      configured: false,
      account: '[account: set by HorecaOS finance]',
    });
    await open();
    expect(root().textContent).not.toContain('[account: set by HorecaOS finance]');
  });

  it('warns about credit that is about to lapse, naming the amount and the day', async () => {
    await open({
      ...WALLET,
      lapsingGrants: [
        { grantId: 'g1', remaining: uzs(200_000), expiresAt: '2026-10-14T00:00:00Z' },
        { grantId: 'g2', remaining: uzs(50_000), expiresAt: '2026-10-20T00:00:00Z' },
      ],
    });
    const warning = plain(q('wallet-lapsing')?.textContent);
    expect(warning).toContain('Bonus credit that is about to lapse');
    expect(warning).toContain('200 000');
    expect(warning).toContain('14.10.2026');
    expect(warning).toContain('50 000');
    expect(warning).toContain('20.10.2026');
  });

  it('says what is owed on issued statements', async () => {
    commercial.arrears.mockResolvedValue(ARREARS_OWING);
    await open();
    const owed = plain(q('wallet-owed')?.textContent);
    expect(owed).toContain('You owe 500 000');
    expect(owed).toContain('Statements with an amount due: 1');
  });

  it('still shows the wallet when the arrears read is refused', async () => {
    commercial.arrears.mockRejectedValue(
      new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null),
    );
    await open();
    expect(q('wallet-paid-tile')).not.toBeNull();
    expect(q('wallet-owed')).toBeNull();
  });

  it('tells the tenant a top-up is waiting for the provider, and not to repeat it', async () => {
    await open({ ...WALLET, pendingTopUp: TOP_UP_PENDING });
    expect(q('wallet-pending')?.textContent).toContain('do not repeat it');
  });

  it('shows the bonus ledger sum beside the spendable figure only while they differ', async () => {
    await open({ ...WALLET, bonusBalance: uzs(500_000), bonusSpendableBalance: uzs(300_000) });
    expect(plain(q('wallet-bonus-tile')?.textContent)).toContain('The ledger sums 500 000');
  });

  it('re-reads the balances, then every panel, when a panel says something changed', async () => {
    await open();
    const invoicesBefore = api.invoices.mock.calls.length;
    const ledgerBefore = api.ledger.mock.calls.length;
    api.overview.mockResolvedValue({ ...WALLET, paidBalance: uzs(2_150_000) });

    // A panel's `changed` output is wired to the page's handler; the handler is what is driven here.
    await (fixture.componentInstance as unknown as { onChanged(): Promise<void> }).onChanged();
    await settle();
    fixture.detectChanges();

    expect(plain(q('wallet-paid-tile')?.textContent)).toContain('2 150 000');
    expect(api.invoices.mock.calls.length).toBeGreaterThan(invoicesBefore);
    expect(api.ledger.mock.calls.length).toBeGreaterThan(ledgerBefore);
  });

  it('shows denied for a role that may not read the wallet', async () => {
    api.overview.mockRejectedValue(
      new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null),
    );
    fixture = TestBed.createComponent(WalletPage);
    fixture.detectChanges();
    await settle();
    fixture.detectChanges();
    expect(q('wallet-denied')?.textContent).toContain('do not have access to the wallet');
    expect(q('wallet-paid-tile')).toBeNull();
  });

  it('shows a failed read as an alert with a retry that reads again', async () => {
    api.overview.mockRejectedValueOnce(
      new ApiError(ApiErrorCode.INTERNAL_ERROR, 500, null, 'corr-1'),
    );
    api.overview.mockResolvedValue(WALLET);
    fixture = TestBed.createComponent(WalletPage);
    fixture.detectChanges();
    await settle();
    fixture.detectChanges();
    expect(root().querySelector('[role="alert"]')).not.toBeNull();
    (root().querySelector('[role="alert"] button') as HTMLElement).click();
    await settle();
    fixture.detectChanges();
    expect(q('wallet-paid-tile')).not.toBeNull();
  });

  it('shows denied when the session names no tenant', async () => {
    tenant.tenantId.set(null);
    tenant.denied.set(true);
    fixture = TestBed.createComponent(WalletPage);
    fixture.detectChanges();
    await settle();
    fixture.detectChanges();
    expect(api.overview).not.toHaveBeenCalled();
    expect(q('wallet-denied')?.textContent).toContain('do not have access to the wallet');
  });

  it('links back to the plan, modules and statements', async () => {
    await open();
    expect((q('wallet-subscription-link') as HTMLAnchorElement).getAttribute('href')).toBe(
      '/finance/subscription',
    );
  });
});

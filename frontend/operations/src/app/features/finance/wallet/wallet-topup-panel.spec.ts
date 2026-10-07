import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { PaymentDetailsView, TenantWalletView, TopUpView, WalletApi } from './wallet-api';
import { WalletTopUpPanel } from './wallet-topup-panel';
import {
  CARD,
  INVOICE_OPEN,
  PAYMENT_DETAILS,
  TENANT_ID,
  TOP_UP_DECLINED,
  TOP_UP_PENDING,
  TOP_UP_SUCCEEDED,
  WALLET,
  WALLET_NOT_CONNECTED,
} from './wallet-fixtures.testing';

async function settle(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

@Component({
  selector: 'q-host',
  imports: [WalletTopUpPanel],
  template: `<q-wallet-topup-panel
    [tenantId]="tenantId"
    [wallet]="wallet()"
    [paymentDetails]="details()"
    [revision]="revision()"
    (changed)="changedCount = changedCount + 1"
  />`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
class Host {
  tenantId = TENANT_ID;
  wallet = signal<TenantWalletView>(WALLET);
  details = signal<PaymentDetailsView | null>(PAYMENT_DETAILS);
  revision = signal(0);
  changedCount = 0;
}

describe('WalletTopUpPanel', () => {
  let fixture: ComponentFixture<Host>;
  let host: Host;
  let api: {
    topUps: ReturnType<typeof vi.fn>;
    topUp: ReturnType<typeof vi.fn>;
    issueInvoice: ReturnType<typeof vi.fn>;
  };

  const root = (): HTMLElement => fixture.nativeElement;
  const q = (testid: string): HTMLElement | null =>
    root().querySelector(`[data-testid="${testid}"]`);
  const type = (testid: string, value: string): void => {
    const input = q(testid) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  };
  const submit = async (testid: string): Promise<void> => {
    (q(testid) as HTMLElement).click();
    await settle();
    fixture.detectChanges();
  };

  beforeEach(async () => {
    api = {
      topUps: vi.fn().mockResolvedValue([TOP_UP_SUCCEEDED, TOP_UP_DECLINED]),
      topUp: vi.fn().mockResolvedValue(TOP_UP_SUCCEEDED),
      issueInvoice: vi.fn().mockResolvedValue(INVOICE_OPEN),
    };
    await TestBed.configureTestingModule({
      imports: [Host],
      providers: [{ provide: WalletApi, useValue: api }],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
    fixture.detectChanges();
    await settle();
    fixture.detectChanges();
  });

  async function show(wallet: TenantWalletView): Promise<void> {
    host.wallet.set(wallet);
    fixture.detectChanges();
    await settle();
    fixture.detectChanges();
  }

  it('lists recent card top-ups with what the provider said, and words a decline’s reason', () => {
    const rows = [...root().querySelectorAll('[data-testid="wallet-topups-table"] tbody tr')];
    expect(rows).toHaveLength(2);
    expect(rows[0].textContent).toContain('Added');
    expect(rows[1].textContent).toContain('Declined');
    expect(rows[1].textContent).toContain('insufficient funds');
  });

  it('reads the history again when the page says something changed', async () => {
    expect(api.topUps).toHaveBeenCalledTimes(1);
    host.revision.set(1);
    fixture.detectChanges();
    await settle();
    expect(api.topUps).toHaveBeenCalledTimes(2);
  });

  describe('by card', () => {
    it('charges the typed amount in whole som and thanks the tenant only for what arrived', async () => {
      type('wallet-card-topup-amount', '150 000');
      await submit('wallet-card-topup-submit');

      expect(api.topUp).toHaveBeenCalledTimes(1);
      const [tenantId, intent] = api.topUp.mock.calls[0];
      expect(tenantId).toBe(TENANT_ID);
      expect(intent.body).toEqual({ amountMinor: 150_000 });
      expect(q('wallet-card-topup-result')?.textContent).toContain('was added to your wallet');
      expect(host.changedCount).toBe(1);
      // The amount is cleared once it has arrived, so a second click is a visibly new decision.
      expect((q('wallet-card-topup-amount') as HTMLInputElement).value).toBe('');
    });

    it('does nothing for an amount that is not a positive number of som', async () => {
      for (const text of ['', '0', '-100', '12.5', 'abc']) {
        type('wallet-card-topup-amount', text);
        expect((q('wallet-card-topup-submit') as HTMLButtonElement).disabled, text).toBe(true);
      }
      expect(api.topUp).not.toHaveBeenCalled();
    });

    it('says a decline is a decline and that nothing was charged', async () => {
      api.topUp.mockResolvedValueOnce(TOP_UP_DECLINED);
      type('wallet-card-topup-amount', '90000');
      await submit('wallet-card-topup-submit');
      const result = q('wallet-card-topup-result');
      expect(result?.getAttribute('data-outcome')).toBe('FAILED');
      expect(result?.textContent).toContain(
        'The card was declined (insufficient funds). Nothing was charged.',
      );
    });

    it('tells the tenant not to repeat a charge the provider has not answered yet', async () => {
      api.topUp.mockResolvedValueOnce(TOP_UP_PENDING);
      type('wallet-card-topup-amount', '70000');
      await submit('wallet-card-topup-submit');
      expect(q('wallet-card-topup-result')?.textContent).toContain('Do not repeat it');
    });

    it('says card payments are not connected when the account is not, and when the server says so', async () => {
      api.topUp.mockResolvedValueOnce({ ...TOP_UP_SUCCEEDED, outcome: 'NOT_CONFIGURED' });
      type('wallet-card-topup-amount', '70000');
      await submit('wallet-card-topup-submit');
      expect(q('wallet-card-topup-result')?.textContent).toContain(
        'not connected yet. Nothing was charged',
      );
    });

    it('retries after a lost answer under the SAME key, so the server replays instead of charging twice', async () => {
      api.topUp.mockRejectedValueOnce(
        new ApiError(ApiErrorCode.NETWORK_UNREACHABLE, 0, null, null),
      );
      type('wallet-card-topup-amount', '150000');
      await submit('wallet-card-topup-submit');
      expect(q('wallet-card-topup-error')).not.toBeNull();
      expect(host.changedCount).toBe(0);

      await submit('wallet-card-topup-submit');
      const keys = api.topUp.mock.calls.map((call) => call[1].key);
      expect(keys).toHaveLength(2);
      expect(keys[0]).toBe(keys[1]);
      expect(q('wallet-card-topup-error')).toBeNull();
    });

    it('mints a new key when the amount changes, and a new one again once the server has answered', async () => {
      api.topUp.mockRejectedValueOnce(
        new ApiError(ApiErrorCode.NETWORK_UNREACHABLE, 0, null, null),
      );
      type('wallet-card-topup-amount', '150000');
      await submit('wallet-card-topup-submit');
      type('wallet-card-topup-amount', '200000');
      await submit('wallet-card-topup-submit');
      type('wallet-card-topup-amount', '200000');
      await submit('wallet-card-topup-submit');

      const keys = api.topUp.mock.calls.map((call) => call[1].key);
      expect(keys[1]).not.toBe(keys[0]);
      // The second request was answered, so the same amount again is a new intent.
      expect(keys[2]).not.toBe(keys[1]);
    });

    it('is disabled, with the reason beside it, while no card merchant account is connected', async () => {
      await show({ ...WALLET, cardPaymentsAvailable: false });
      expect((q('wallet-card-topup-amount') as HTMLInputElement).disabled).toBe(true);
      expect(q('wallet-card-topup-blocked')?.textContent).toContain('not connected yet');
      expect((q('wallet-card-topup-submit') as HTMLButtonElement).disabled).toBe(true);
    });

    it('asks for a card first when there is none, and for a new one when it has expired', async () => {
      await show({ ...WALLET, card: null });
      expect(q('wallet-card-topup-blocked')?.textContent).toContain('Put a card on file first');
      await show({ ...WALLET, card: { ...CARD, lapsed: true } });
      expect(q('wallet-card-topup-blocked')?.textContent).toContain('has expired');
    });

    it('will not start a second top-up while one waits for the provider', async () => {
      await show({ ...WALLET, pendingTopUp: TOP_UP_PENDING });
      expect(q('wallet-card-topup-blocked')?.textContent).toContain(
        'waiting for the card provider’s answer',
      );
      type('wallet-card-topup-amount', '50000');
      expect((q('wallet-card-topup-submit') as HTMLButtonElement).disabled).toBe(true);
    });

    it('refuses to read an amount in a currency this console has no scale for', async () => {
      await show({
        ...WALLET,
        paidBalance: { amountMinor: 0, currency: 'KZT' },
      });
      expect(q('wallet-card-topup-blocked')?.textContent).toContain('KZT');
      expect((q('wallet-card-topup-submit') as HTMLButtonElement).disabled).toBe(true);
    });
  });

  describe('by bank transfer', () => {
    it('shows where to pay and asks for an invoice, then says the wallet is credited when the money arrives', async () => {
      expect(q('wallet-bank-details')?.textContent).toContain('HorecaOS LLC');
      expect(q('wallet-bank-details')?.textContent).toContain('20208000100000000001');
      type('wallet-invoice-amount', '1 000 000');
      await submit('wallet-invoice-submit');

      expect(api.issueInvoice).toHaveBeenCalledTimes(1);
      const [tenantId, intent] = api.issueInvoice.mock.calls[0];
      expect(tenantId).toBe(TENANT_ID);
      expect(intent.body).toEqual({ amountMinor: 1_000_000 });
      const issued = q('wallet-invoice-issued')?.textContent ?? '';
      expect(issued).toContain('PI-202610-000001');
      expect(issued).toContain('credited when the money arrives');
      expect(host.changedCount).toBe(1);
    });

    it('says the bank details are not published, and offers no invoice form that would only be refused', async () => {
      await show({ ...WALLET, bankTransferAvailable: false });
      host.details.set(null);
      fixture.detectChanges();
      expect(q('wallet-bank-not-available')).not.toBeNull();
      expect(q('wallet-bank-details')).toBeNull();
      type('wallet-invoice-amount', '1000000');
      expect((q('wallet-invoice-submit') as HTMLButtonElement).disabled).toBe(true);
      expect(api.issueInvoice).not.toHaveBeenCalled();
    });

    it('words the server’s refusal and retries under the same key', async () => {
      api.issueInvoice.mockRejectedValueOnce(
        new ApiError(
          ApiErrorCode.RESOURCE_CONFLICT,
          409,
          { status: 409, code: ApiErrorCode.RESOURCE_CONFLICT, reason: 'TOO_MANY_OPEN_INVOICES' },
          'corr-1',
        ),
      );
      type('wallet-invoice-amount', '1000000');
      await submit('wallet-invoice-submit');
      expect(q('wallet-invoice-error')?.textContent).toContain('Too many invoices are waiting');
      await submit('wallet-invoice-submit');
      const keys = api.issueInvoice.mock.calls.map((call) => call[1].key);
      expect(keys[0]).toBe(keys[1]);
    });

    it('is also unavailable for an unscaled currency', async () => {
      await show({ ...WALLET, paidBalance: { amountMinor: 0, currency: 'KZT' } });
      type('wallet-invoice-amount', '1000000');
      expect((q('wallet-invoice-submit') as HTMLButtonElement).disabled).toBe(true);
    });
  });

  it('shows a not-connected tenant both forms disabled at once', async () => {
    await show(WALLET_NOT_CONNECTED);
    host.details.set(null);
    fixture.detectChanges();
    expect((q('wallet-card-topup-submit') as HTMLButtonElement).disabled).toBe(true);
    expect((q('wallet-invoice-submit') as HTMLButtonElement).disabled).toBe(true);
  });

  it('shows a history read that failed as an alert', async () => {
    api.topUps.mockRejectedValueOnce(
      new ApiError(ApiErrorCode.INTERNAL_ERROR, 500, null, 'corr-9'),
    );
    host.revision.set(2);
    fixture.detectChanges();
    await settle();
    fixture.detectChanges();
    expect(root().querySelector('[role="alert"]')).not.toBeNull();
  });
});

// A top-up view the panel never produced must still render: an outcome this build has no word for shows itself.
describe('WalletTopUpPanel with an outcome it has no word for', () => {
  it('shows the raw outcome rather than a blank', async () => {
    const odd: TopUpView = { ...TOP_UP_SUCCEEDED, outcome: 'REVERSED' };
    await TestBed.configureTestingModule({
      imports: [Host],
      providers: [
        {
          provide: WalletApi,
          useValue: {
            topUps: vi.fn().mockResolvedValue([odd]),
            topUp: vi.fn(),
            issueInvoice: vi.fn(),
          },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    await settle();
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('REVERSED');
  });
});

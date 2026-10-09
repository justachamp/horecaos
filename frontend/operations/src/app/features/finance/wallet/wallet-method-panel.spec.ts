import { Component, ChangeDetectionStrategy, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { TenantWalletView, WalletApi } from './wallet-api';
import { WalletMethodPanel } from './wallet-method-panel';
import {
  CARD,
  ENROLMENT,
  TENANT_ID,
  TOP_UP_PENDING,
  WALLET,
  WALLET_NOT_CONNECTED,
} from './wallet-fixtures.testing';

async function settle(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

function refusal(reason: string): ApiError {
  return new ApiError(
    ApiErrorCode.UNPROCESSABLE_STATE,
    422,
    { status: 422, code: ApiErrorCode.UNPROCESSABLE_STATE, reason },
    'corr-1',
  );
}

@Component({
  selector: 'q-host',
  imports: [WalletMethodPanel],
  template: `<q-wallet-method-panel
    [tenantId]="tenantId"
    [wallet]="wallet()"
    (changed)="changedCount = changedCount + 1"
  />`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
class Host {
  tenantId = TENANT_ID;
  wallet = signal<TenantWalletView>(WALLET);
  changedCount = 0;
}

describe('WalletMethodPanel', () => {
  let fixture: ComponentFixture<Host>;
  let host: Host;
  let api: {
    beginCardEnrolment: ReturnType<typeof vi.fn>;
    confirmCard: ReturnType<typeof vi.fn>;
    removeCard: ReturnType<typeof vi.fn>;
    choosePaymentMethod: ReturnType<typeof vi.fn>;
  };

  const root = (): HTMLElement => fixture.nativeElement;
  const q = (testid: string): HTMLElement | null =>
    root().querySelector(`[data-testid="${testid}"]`);
  const click = async (testid: string): Promise<void> => {
    (q(testid) as HTMLElement).click();
    await settle();
    fixture.detectChanges();
  };
  const type = (testid: string, value: string): void => {
    const input = q(testid) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  };

  beforeEach(async () => {
    api = {
      beginCardEnrolment: vi.fn().mockResolvedValue(ENROLMENT),
      confirmCard: vi.fn().mockResolvedValue(CARD),
      removeCard: vi.fn().mockResolvedValue({ paymentMethod: 'INVOICE' }),
      choosePaymentMethod: vi.fn().mockResolvedValue({ paymentMethod: 'CARD' }),
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

  it('shows the card as its last four digits, brand and expiry, and never anything else', () => {
    const card = q('wallet-card-on-file')!.textContent ?? '';
    expect(card).toContain('HUMO ending 4242');
    expect(card).toContain('03/2029');
  });

  it('flags a card that has expired and one that is about to', async () => {
    await show({ ...WALLET, card: { ...CARD, lapsed: true } });
    expect(q('wallet-card-lapsed')).not.toBeNull();
    await show({ ...WALLET, card: { ...CARD, lapsesSoon: true } });
    expect(q('wallet-card-lapses-soon')).not.toBeNull();
    expect(q('wallet-card-lapsed')).toBeNull();
  });

  it('says plainly that cards are not connected and does not offer to add one', async () => {
    await show(WALLET_NOT_CONNECTED);
    expect((q('wallet-card-add') as HTMLButtonElement).disabled).toBe(true);
    expect(q('wallet-card-not-connected')?.textContent).toContain(
      'has not connected its card payment account',
    );
    await click('wallet-card-add');
    expect(api.beginCardEnrolment).not.toHaveBeenCalled();
  });

  it('puts a card on file in two steps, sending the form’s code and the bank’s code trimmed', async () => {
    await show({ ...WALLET, card: null });
    await click('wallet-card-add');
    expect(api.beginCardEnrolment).toHaveBeenCalledWith(TENANT_ID);
    expect(q('wallet-enrolment')).not.toBeNull();
    // The provider's form is a link, and only because it is https.
    expect((q('wallet-enrolment-form-link') as HTMLAnchorElement).href).toBe(
      ENROLMENT.hostedFormUrl,
    );
    expect((q('wallet-enrolment-confirm') as HTMLButtonElement).disabled).toBe(true);

    type('wallet-enrolment-token', '  tok_one  ');
    type('wallet-enrolment-code', ' 000000 ');
    await click('wallet-enrolment-confirm');

    expect(api.confirmCard).toHaveBeenCalledTimes(1);
    const [tenantId, intent] = api.confirmCard.mock.calls[0];
    expect(tenantId).toBe(TENANT_ID);
    expect(intent.body).toEqual({
      sessionReference: 'session-1',
      providerToken: 'tok_one',
      verificationCode: '000000',
    });
    expect(q('wallet-enrolment')).toBeNull();
    expect(q('wallet-method-notice')?.textContent).toContain('4242');
    expect(host.changedCount).toBe(1);
  });

  it('does not link a form that is not https', async () => {
    api.beginCardEnrolment.mockResolvedValueOnce({
      ...ENROLMENT,
      hostedFormUrl: 'http://pay.example.test/x',
    });
    await click('wallet-card-add');
    expect(q('wallet-enrolment')).not.toBeNull();
    expect(q('wallet-enrolment-form-link')).toBeNull();
  });

  it('tells a person using the built-in test provider what to enter, and no one else', async () => {
    api.beginCardEnrolment.mockResolvedValueOnce({
      ...ENROLMENT,
      clientParameters: { provider: 'FAKE_CARD' },
    });
    await click('wallet-card-add');
    expect(q('wallet-enrolment-test-hint')?.textContent).toContain('tok_fake_approve');
    await click('wallet-enrolment-cancel');
    await click('wallet-card-add');
    expect(q('wallet-enrolment-test-hint')).toBeNull();
  });

  it('keeps the form open on a wrong bank code, says so, and retries under the same key', async () => {
    api.confirmCard.mockRejectedValueOnce(refusal('WRONG_CODE'));
    await click('wallet-card-add');
    type('wallet-enrolment-token', 'tok_one');
    type('wallet-enrolment-code', '111111');
    await click('wallet-enrolment-confirm');

    expect(q('wallet-method-error')?.textContent).toContain('The code from your bank is wrong');
    expect(q('wallet-enrolment')).not.toBeNull();

    await click('wallet-enrolment-confirm');
    const keys = api.confirmCard.mock.calls.map((call) => call[1].key);
    expect(keys[0]).toBe(keys[1]);
  });

  it('drops the form when the provider says its session is spent', async () => {
    api.confirmCard.mockRejectedValueOnce(refusal('SESSION_EXPIRED'));
    await click('wallet-card-add');
    type('wallet-enrolment-token', 'tok_one');
    type('wallet-enrolment-code', '000000');
    await click('wallet-enrolment-confirm');
    expect(q('wallet-enrolment')).toBeNull();
    expect(q('wallet-method-error')?.textContent).toContain('has expired');
  });

  it('asks before removing the card, and says a card-collected tenant falls back to invoice', async () => {
    await show({ ...WALLET, paymentMethod: 'CARD' });
    await click('wallet-card-remove');
    expect(api.removeCard).not.toHaveBeenCalled();
    const dialog = root().querySelector('[data-testid="q-confirm-dialog"]');
    expect(dialog?.textContent).toContain('you are collected by card today');
    (root().querySelector('[data-testid="q-confirm-confirm"]') as HTMLButtonElement).click();
    await settle();
    fixture.detectChanges();
    expect(api.removeCard).toHaveBeenCalledWith(TENANT_ID);
    expect(q('wallet-method-notice')?.textContent).toContain(
      'collected by invoice and bank transfer',
    );
    expect(host.changedCount).toBe(1);
  });

  it('cancelling the removal sends nothing', async () => {
    await click('wallet-card-remove');
    (root().querySelector('[data-testid="q-confirm-cancel"]') as HTMLButtonElement).click();
    await settle();
    fixture.detectChanges();
    expect(api.removeCard).not.toHaveBeenCalled();
    expect(root().querySelector('[data-testid="q-confirm-dialog"]')).toBeNull();
  });

  it('will not offer to remove a card while a top-up is waiting for the provider', async () => {
    await show({ ...WALLET, pendingTopUp: TOP_UP_PENDING });
    expect((q('wallet-card-remove') as HTMLButtonElement).disabled).toBe(true);
  });

  it('saves INVOICE or WALLET at once, without a consent step', async () => {
    (q('wallet-method-INVOICE') as HTMLInputElement).click();
    fixture.detectChanges();
    await click('wallet-method-save');
    expect(root().querySelector('[data-testid="q-confirm-dialog"]')).toBeNull();
    expect(api.choosePaymentMethod).toHaveBeenCalledWith(TENANT_ID, 'INVOICE');
    expect(q('wallet-method-notice')?.textContent).toContain('Invoice and bank transfer');
  });

  it('asks for the tenant’s consent in so many words before collecting by card, and sends nothing until given', async () => {
    (q('wallet-method-CARD') as HTMLInputElement).click();
    fixture.detectChanges();
    await click('wallet-method-save');

    expect(api.choosePaymentMethod).not.toHaveBeenCalled();
    const dialog = root().querySelector('[data-testid="q-confirm-dialog"]');
    expect(dialog?.textContent).toContain('You agree that HorecaOS charges HUMO ending 4242');

    (root().querySelector('[data-testid="q-confirm-cancel"]') as HTMLButtonElement).click();
    await settle();
    fixture.detectChanges();
    expect(api.choosePaymentMethod).not.toHaveBeenCalled();

    await click('wallet-method-save');
    (root().querySelector('[data-testid="q-confirm-confirm"]') as HTMLButtonElement).click();
    await settle();
    fixture.detectChanges();
    expect(api.choosePaymentMethod).toHaveBeenCalledWith(TENANT_ID, 'CARD');
    expect(host.changedCount).toBe(1);
  });

  it('will not let a tenant without a card choose CARD', async () => {
    await show({ ...WALLET, card: null });
    expect((q('wallet-method-CARD') as HTMLInputElement).disabled).toBe(true);
  });

  it('says a CARD choice works like an invoice until card payments are connected', async () => {
    await show({ ...WALLET_NOT_CONNECTED, card: CARD, paymentMethod: 'CARD' });
    expect(q('wallet-collect-not-connected')).not.toBeNull();
  });

  it('shows a refused save as an alert and keeps the choice', async () => {
    api.choosePaymentMethod.mockRejectedValueOnce(refusal('NO_CARD_ON_FILE'));
    (q('wallet-method-INVOICE') as HTMLInputElement).click();
    fixture.detectChanges();
    await click('wallet-method-save');
    expect(q('wallet-method-error')?.textContent).toContain('There is no card on file');
    expect(host.changedCount).toBe(0);
  });
});

import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import {
  OrderPaymentView,
  PaymentSessionView,
  PaymentsApi,
} from '../finance/payments/payments-api';
import { OrderPaymentPanel } from './order-payment-panel';

const TENANT = 't1';
const ORDER = 'order-1';

function payment(overrides: Partial<OrderPaymentView> = {}): OrderPaymentView {
  return {
    orderId: ORDER,
    publicOrderNumber: 'A-1001',
    orderStatus: 'CONFIRMED',
    orderTotal: { amountMinor: 50000, currency: 'UZS' },
    intent: {
      intentId: 'intent-1',
      tender: 'PROVIDER',
      method: 'CLICK',
      providerType: 'CLICK',
      amount: { amountMinor: 50000, currency: 'UZS' },
      status: 'PAID',
      createdAt: '2026-09-15T09:00:00Z',
      settledAt: '2026-09-15T09:01:00Z',
    },
    payment: [
      {
        tenderId: 'tender-1',
        sequence: 1,
        methodCode: 'CLICK',
        methodDisplayName: 'Click',
        settlesFromBalance: false,
        amount: { amountMinor: 50000, currency: 'UZS' },
        status: 'SETTLED',
        refunded: { amountMinor: 0, currency: 'UZS' },
      },
    ],
    attempts: [
      {
        attemptId: 'attempt-1',
        providerType: 'CLICK',
        status: 'CAPTURED',
        presentationKind: 'PAYMENT_LINK',
        amount: { amountMinor: 50000, currency: 'UZS' },
        live: false,
        createdAt: '2026-09-15T09:00:30Z',
        settledAt: '2026-09-15T09:01:00Z',
      },
    ],
    captured: { amountMinor: 50000, currency: 'UZS' },
    returned: { amountMinor: 0, currency: 'UZS' },
    ...overrides,
  };
}

function configure(options: { paymentsApi?: Partial<PaymentsApi> }): void {
  TestBed.configureTestingModule({
    providers: [{ provide: PaymentsApi, useValue: options.paymentsApi ?? {} }],
  });
  TestBed.inject(I18n).setLocale('en');
}

async function render() {
  const fixture = TestBed.createComponent(OrderPaymentPanel);
  fixture.componentRef.setInput('tenantId', TENANT);
  fixture.componentRef.setInput('orderId', ORDER);
  fixture.detectChanges();
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
  return fixture;
}

describe('OrderPaymentPanel: reads the order payment (row 1.2l)', () => {
  it('renders localized status labels, never the raw enum token', async () => {
    configure({ paymentsApi: { orderPayment: vi.fn().mockResolvedValue(payment()) } });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    const intentStatus = host.querySelector('[data-testid="order-payment-intent-status"]');
    expect(intentStatus?.textContent).toContain('Paid');
    expect(intentStatus?.textContent).not.toContain('PAID');

    const tenderStatus = host.querySelector('[data-testid="order-payment-tender-status"]');
    expect(tenderStatus?.textContent).toContain('Settled');
    expect(tenderStatus?.textContent).not.toContain('SETTLED');

    const attemptStatus = host.querySelector('[data-testid="order-payment-attempt-status"]');
    expect(attemptStatus?.textContent).toContain('Captured');
    expect(attemptStatus?.textContent).not.toContain('CAPTURED');
  });

  it('shows a settlement tender by its display name and refunded amount', async () => {
    configure({ paymentsApi: { orderPayment: vi.fn().mockResolvedValue(payment()) } });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    expect(host.querySelector('[data-testid="order-payment-tender-row"]')?.textContent).toContain(
      'Click',
    );
  });

  it('shows the no-intent note for an order never checked out for payment', async () => {
    configure({
      paymentsApi: {
        orderPayment: vi.fn().mockResolvedValue(payment({ intent: null, attempts: [] })),
      },
    });
    const fixture = await render();

    expect(
      fixture.nativeElement.querySelector('[data-testid="order-payment-no-intent"]'),
    ).not.toBeNull();
    expect(
      fixture.nativeElement.querySelector('[data-testid="order-payment-intent-status"]'),
    ).toBeNull();
  });

  it('shows a denied state on a 403 rather than a generic error', async () => {
    configure({
      paymentsApi: {
        orderPayment: vi
          .fn()
          .mockRejectedValue(new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null)),
      },
    });
    const fixture = await render();

    expect(
      fixture.nativeElement.querySelector('[data-testid="order-payment-denied"]'),
    ).not.toBeNull();
  });

  it('shows an error state rather than crashing on any other failure', async () => {
    configure({
      paymentsApi: {
        orderPayment: vi
          .fn()
          .mockRejectedValue(new ApiError(ApiErrorCode.INTERNAL_ERROR, 500, null, null)),
      },
    });
    const fixture = await render();

    expect(
      fixture.nativeElement.querySelector('[data-testid="order-payment-error"]'),
    ).not.toBeNull();
  });

  it('never offers to re-issue checkout for a cash order', async () => {
    configure({
      paymentsApi: {
        orderPayment: vi.fn().mockResolvedValue(
          payment({
            intent: {
              intentId: 'intent-2',
              tender: 'CASH',
              method: 'CASH',
              providerType: null,
              amount: { amountMinor: 50000, currency: 'UZS' },
              status: 'PAID',
              createdAt: '2026-09-15T09:00:00Z',
              settledAt: '2026-09-15T09:01:00Z',
            },
          }),
        ),
      },
    });
    const fixture = await render();

    expect(
      fixture.nativeElement.querySelector('[data-testid="order-payment-reissue-toggle"]'),
    ).toBeNull();
  });
});

describe('OrderPaymentPanel: re-presentation (ADR 0013)', () => {
  it('sends a payment link and shows the checkout URL and its QR code', async () => {
    const reissuePayment = vi.fn().mockResolvedValue({
      attemptId: 'attempt-2',
      merchantTransId: 'mt-1',
      provider: 'CLICK',
      presentation: 'PAYMENT_LINK',
      checkoutUrl: 'https://pay.click.uz/checkout/abc',
      qrPayload: 'https://pay.click.uz/checkout/abc',
      expiresAt: null,
      amountMinor: 50000,
      currency: 'UZS',
      rePresented: true,
      presentationCount: 2,
    } satisfies PaymentSessionView);
    configure({
      paymentsApi: { orderPayment: vi.fn().mockResolvedValue(payment()), reissuePayment },
    });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    (
      host.querySelector('[data-testid="order-payment-reissue-toggle"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    (
      host.querySelector('[data-testid="order-payment-reissue-submit"]') as HTMLButtonElement
    ).click();
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();

    expect(reissuePayment).toHaveBeenCalledWith(TENANT, ORDER, {
      presentation: 'PAYMENT_LINK',
      pushRecipient: undefined,
    });
    expect(
      host.querySelector('[data-testid="order-payment-reissue-result"]')?.textContent,
    ).toContain('https://pay.click.uz/checkout/abc');
    expect(host.querySelector('[data-testid="order-payment-reissue-qr"]')).not.toBeNull();
  });

  it('refuses to submit an invoice push without a valid phone', async () => {
    configure({
      paymentsApi: { orderPayment: vi.fn().mockResolvedValue(payment()) },
    });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    (
      host.querySelector('[data-testid="order-payment-reissue-toggle"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    (host.querySelector('[data-testid="order-payment-reissue-kind"]') as HTMLSelectElement).value =
      'INVOICE_PUSH';
    host
      .querySelector('[data-testid="order-payment-reissue-kind"]')
      ?.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(
      (host.querySelector('[data-testid="order-payment-reissue-submit"]') as HTMLButtonElement)
        .disabled,
    ).toBe(true);
  });

  it('shows the refusal reason rather than a generic error on a conflict', async () => {
    const reissuePayment = vi
      .fn()
      .mockRejectedValue(
        new ApiError(
          ApiErrorCode.RESOURCE_CONFLICT,
          409,
          { status: 409, reason: 'ALREADY_PAID' },
          null,
        ),
      );
    configure({
      paymentsApi: { orderPayment: vi.fn().mockResolvedValue(payment()), reissuePayment },
    });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    (
      host.querySelector('[data-testid="order-payment-reissue-toggle"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    (
      host.querySelector('[data-testid="order-payment-reissue-submit"]') as HTMLButtonElement
    ).click();
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();

    expect(
      host.querySelector('[data-testid="order-payment-reissue-error"]')?.textContent,
    ).toContain('already paid');
  });
});

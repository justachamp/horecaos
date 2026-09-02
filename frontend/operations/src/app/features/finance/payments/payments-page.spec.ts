import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { CurrentTenant } from '../../../core/auth/current-tenant';
import { I18n } from '../../../core/i18n/i18n';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { OrderPaymentView, PaymentsApi, RemedyView } from './payments-api';
import { PaymentsPage } from './payments-page';

const TENANT_ID = 'tenant-1';

const CASH_ORDER: OrderPaymentView = {
  orderId: 'order-1',
  publicOrderNumber: '482',
  orderStatus: 'CONFIRMED',
  orderTotal: { amountMinor: 45_000, currency: 'UZS' },
  intent: null,
  attempts: [],
  captured: null,
  returned: null,
};

const PROVIDER_ORDER: OrderPaymentView = {
  orderId: 'order-2',
  publicOrderNumber: '483',
  orderStatus: 'CONFIRMED',
  orderTotal: { amountMinor: 82_000, currency: 'UZS' },
  intent: {
    intentId: 'intent-1',
    tender: 'PROVIDER',
    method: 'CLICK',
    providerType: 'CLICK',
    amount: { amountMinor: 82_000, currency: 'UZS' },
    status: 'AUTHORIZING',
    createdAt: '2026-08-30T09:00:00Z',
    settledAt: null,
  },
  attempts: [
    {
      attemptId: 'attempt-1',
      providerType: 'CLICK',
      status: 'PRESENTED',
      presentationKind: 'PAYMENT_LINK',
      amount: { amountMinor: 82_000, currency: 'UZS' },
      live: false,
      createdAt: '2026-08-30T09:00:05Z',
      settledAt: null,
    },
  ],
  captured: { amountMinor: 0, currency: 'UZS' },
  returned: { amountMinor: 0, currency: 'UZS' },
};

const UNVERIFIED_REMEDY: RemedyView = {
  approvalStatus: 'NOT_REQUIRED',
  approvalRequestId: null,
  remedyId: 'remedy-1',
  remedyType: 'ORDER_REFUND',
  orderId: 'order-9',
  amount: { amountMinor: 15_000, currency: 'UZS' },
  attestedMoney: { amountMinor: 15_000, currency: 'UZS' },
  platformSettledMoney: { amountMinor: 0, currency: 'UZS' },
  settlementBasis: 'OPERATOR_ATTESTED',
  verificationState: 'UNVERIFIED',
  executionChannel: 'CASH_DRAWER',
  providerReference: null,
  executedBy: 'operator-1',
  executedAt: '2026-08-29T10:00:00Z',
  recordedBy: 'operator-1',
  recordedAt: '2026-08-29T10:01:00Z',
  deliveryFeeBasis: null,
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

describe('PaymentsPage', () => {
  let fixture: ComponentFixture<PaymentsPage>;
  let api: {
    orderPayment: ReturnType<typeof vi.fn>;
    reissuePayment: ReturnType<typeof vi.fn>;
    remediesOfOrder: ReturnType<typeof vi.fn>;
    recordRefund: ReturnType<typeof vi.fn>;
    reimburseDeliveryFee: ReturnType<typeof vi.fn>;
    unverifiedRemedies: ReturnType<typeof vi.fn>;
    remedyTotals: ReturnType<typeof vi.fn>;
    verifyRemedy: ReturnType<typeof vi.fn>;
  };

  beforeEach(async () => {
    api = {
      orderPayment: vi.fn().mockResolvedValue(CASH_ORDER),
      reissuePayment: vi.fn().mockResolvedValue({
        attemptId: 'attempt-2',
        merchantTransId: 'mt-1',
        provider: 'CLICK',
        presentation: 'PAYMENT_LINK',
        checkoutUrl: 'https://pay.click.uz/x',
        qrPayload: null,
        expiresAt: null,
        amountMinor: 82_000,
        currency: 'UZS',
        rePresented: false,
        presentationCount: 1,
      }),
      remediesOfOrder: vi.fn().mockResolvedValue([]),
      recordRefund: vi
        .fn()
        .mockResolvedValue({ ...UNVERIFIED_REMEDY, approvalStatus: 'NOT_REQUIRED' }),
      reimburseDeliveryFee: vi.fn().mockResolvedValue({ ...UNVERIFIED_REMEDY }),
      unverifiedRemedies: vi
        .fn()
        .mockResolvedValue({ items: [UNVERIFIED_REMEDY], nextCursor: null }),
      remedyTotals: vi.fn().mockResolvedValue([]),
      verifyRemedy: vi.fn().mockResolvedValue({ recorded: true }),
    };

    await TestBed.configureTestingModule({
      imports: [PaymentsPage],
      providers: [
        { provide: PaymentsApi, useValue: api },
        { provide: CurrentTenant, useValue: new FakeCurrentTenant() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(PaymentsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  });

  it('loads the unverified worklist on init, without an order lookup', () => {
    expect(api.unverifiedRemedies).toHaveBeenCalledWith(TENANT_ID);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('order-9');
  });

  it('shows the honest split-tender note and no intent for a cash order looked up', async () => {
    const input = fixture.nativeElement.querySelector('#order-id') as HTMLInputElement;
    input.value = 'order-1';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const lookupButton = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((button) => button.textContent?.trim() === 'Look up') as HTMLButtonElement;
    lookupButton.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.orderPayment).toHaveBeenCalledWith(TENANT_ID, 'order-1');
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('no live payment intent');
  });

  it('offers re-issue only for a PROVIDER intent, and calls the API on submit', async () => {
    api.orderPayment.mockResolvedValue(PROVIDER_ORDER);

    const input = fixture.nativeElement.querySelector('#order-id') as HTMLInputElement;
    input.value = 'order-2';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (
      Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find(
        (button) => button.textContent?.trim() === 'Look up',
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    const reissueToggle = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((button) => button.textContent?.includes('Re-issue payment link')) as HTMLButtonElement;
    expect(reissueToggle).toBeTruthy();
    reissueToggle.click();
    fixture.detectChanges();

    const submit = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.form__actions button'),
    ).find((button) => button.textContent?.trim() === 'Send') as HTMLButtonElement;
    submit.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.reissuePayment).toHaveBeenCalledWith(TENANT_ID, 'order-2', {
      presentation: 'PAYMENT_LINK',
      pushRecipient: undefined,
    });
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('https://pay.click.uz/x');
  });

  it('records a refund with the order’s own currency and reloads the order', async () => {
    api.orderPayment.mockResolvedValue(CASH_ORDER);

    const input = fixture.nativeElement.querySelector('#order-id') as HTMLInputElement;
    input.value = 'order-1';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (
      Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find(
        (button) => button.textContent?.trim() === 'Look up',
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    const remedyToggle = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((button) => button.textContent?.trim() === 'Record a remedy') as HTMLButtonElement;
    remedyToggle.click();
    fixture.detectChanges();

    const amount = fixture.nativeElement.querySelector('#remedy-amount') as HTMLInputElement;
    amount.value = '5000';
    amount.dispatchEvent(new Event('input'));
    const reasonCode = fixture.nativeElement.querySelector(
      '#remedy-reason-code',
    ) as HTMLInputElement;
    reasonCode.value = 'ITEM_MISSING';
    reasonCode.dispatchEvent(new Event('input'));
    const reason = fixture.nativeElement.querySelector('#remedy-reason') as HTMLInputElement;
    reason.value = 'Missing side dish';
    reason.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const submit = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.form__actions button'),
    ).find((button) => button.textContent?.trim() === 'Record') as HTMLButtonElement;
    expect(submit.disabled).toBe(false);
    submit.click();
    await flushMicrotasks();

    expect(api.recordRefund).toHaveBeenCalledWith(
      TENANT_ID,
      'order-1',
      expect.objectContaining({
        amountMinor: 5000,
        currency: 'UZS',
        reasonCode: 'ITEM_MISSING',
        reason: 'Missing side dish',
        channel: 'CASH_DRAWER',
      }),
    );
  });

  it('confirms an unverified attestation through the verify form', async () => {
    const verifyButton = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((button) => button.textContent?.trim() === 'Verify') as HTMLButtonElement;
    verifyButton.click();
    fixture.detectChanges();

    const source = fixture.nativeElement.querySelector('#verify-source') as HTMLInputElement;
    source.value = 'Click settlement file 2026-08-30';
    source.dispatchEvent(new Event('input'));
    const reason = fixture.nativeElement.querySelector('#verify-reason') as HTMLInputElement;
    reason.value = 'Matches the amount and date';
    reason.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const confirm = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((button) => button.textContent?.trim() === 'Confirm') as HTMLButtonElement;
    confirm.click();
    await flushMicrotasks();

    expect(api.verifyRemedy).toHaveBeenCalledWith(TENANT_ID, 'remedy-1', {
      state: 'CONFIRMED',
      source: 'Click settlement file 2026-08-30',
      reason: 'Matches the amount and date',
    });
  });

  it('shows a denied state when the session names no tenant', async () => {
    await TestBed.resetTestingModule()
      .configureTestingModule({
        imports: [PaymentsPage],
        providers: [
          { provide: PaymentsApi, useValue: api },
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
    const deniedFixture = TestBed.createComponent(PaymentsPage);
    deniedFixture.detectChanges();
    await flushMicrotasks();
    deniedFixture.detectChanges();

    expect((deniedFixture.nativeElement as HTMLElement).textContent).toContain('not permitted');
  });

  it('describes a refused re-issue by its specific reason, not a generic conflict', async () => {
    api.orderPayment.mockResolvedValue(PROVIDER_ORDER);
    api.reissuePayment.mockRejectedValue(
      new ApiError(
        ApiErrorCode.RESOURCE_CONFLICT,
        409,
        { status: 409, reason: 'ALREADY_PAID' },
        null,
      ),
    );

    const input = fixture.nativeElement.querySelector('#order-id') as HTMLInputElement;
    input.value = 'order-2';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (
      Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find(
        (button) => button.textContent?.trim() === 'Look up',
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    (
      Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find((button) =>
        button.textContent?.includes('Re-issue payment link'),
      ) as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    (
      Array.from(
        (fixture.nativeElement as HTMLElement).querySelectorAll('.form__actions button'),
      ).find((button) => button.textContent?.trim() === 'Send') as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('already paid');
  });
});

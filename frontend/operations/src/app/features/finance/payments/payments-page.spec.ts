import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { CurrentTenant } from '../../../core/auth/current-tenant';
import { OrderLookupApi } from '../../../core/api/order-lookup-api';
import { I18n } from '../../../core/i18n/i18n';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { SessionCapabilities } from '../../../core/auth/session-capabilities';
import {
  FiscalEvidenceApi,
  FiscalEvidenceDocumentView,
  FiscalEvidenceView,
  FiscalPaymentTypeView,
} from '../fiscal/fiscal-evidence-api';
import { OrderPaymentView, PaymentsApi, RemedyView } from './payments-api';
import { PaymentsPage } from './payments-page';

const TENANT_ID = 'tenant-1';

const CASH_ORDER: OrderPaymentView = {
  orderId: 'order-1',
  publicOrderNumber: '482',
  orderStatus: 'CONFIRMED',
  orderTotal: { amountMinor: 45_000, currency: 'UZS' },
  intent: null,
  payment: [],
  attempts: [],
  captured: null,
  returned: null,
};

const SPLIT_TENDER_ORDER: OrderPaymentView = {
  orderId: 'order-3',
  publicOrderNumber: '484',
  orderStatus: 'CONFIRMED',
  orderTotal: { amountMinor: 100_000, currency: 'UZS' },
  intent: null,
  payment: [
    {
      tenderId: 'tender-1',
      sequence: 1,
      methodCode: 'LOYALTY_POINTS',
      methodDisplayName: 'Balance',
      settlesFromBalance: true,
      amount: { amountMinor: 12_000, currency: 'UZS' },
      status: 'SETTLED',
      refunded: { amountMinor: 0, currency: 'UZS' },
    },
    {
      tenderId: 'tender-2',
      sequence: 2,
      methodCode: 'CLICK',
      methodDisplayName: 'CLICK',
      settlesFromBalance: false,
      amount: { amountMinor: 88_000, currency: 'UZS' },
      status: 'SETTLED',
      refunded: { amountMinor: 20_000, currency: 'UZS' },
    },
  ],
  attempts: [],
  captured: { amountMinor: 88_000, currency: 'UZS' },
  returned: { amountMinor: 20_000, currency: 'UZS' },
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
  payment: [
    {
      tenderId: 'tender-3',
      sequence: 1,
      methodCode: 'CLICK',
      methodDisplayName: 'CLICK',
      settlesFromBalance: false,
      amount: { amountMinor: 82_000, currency: 'UZS' },
      status: 'RESERVED',
      refunded: { amountMinor: 0, currency: 'UZS' },
    },
  ],
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

const PAYMENT_TYPES: readonly FiscalPaymentTypeView[] = [
  {
    code: 'CLICK',
    displayName: 'Click',
    methodStatus: 'ACTIVE',
    responsibility: 'PARTNER',
    path: 'PARTNER_ACCOUNT',
    live: true,
    reasonCode: null,
    note: null,
    entries: [
      {
        legalEntityId: 'entity-1',
        tin: '123456789',
        legalName: 'Fiskal A MCHJ',
        bindingId: 'binding-1',
        accountReferenceMasked: '****e-77',
        capableTerminals: 0,
        supportsPartnerFiscalization: true,
      },
    ],
  },
  {
    code: 'CASH',
    displayName: 'Cash',
    methodStatus: 'ACTIVE',
    responsibility: 'TERMINAL',
    path: 'TERMINAL',
    live: false,
    reasonCode: 'NO_FISCAL_PATH',
    note: null,
    entries: [],
  },
  {
    code: 'LOYALTY_POINTS',
    displayName: 'Balance',
    methodStatus: 'ACTIVE',
    responsibility: 'OPERATOR',
    path: 'NOT_A_FISCAL_PATH',
    live: false,
    reasonCode: null,
    note: 'BALANCE_IS_A_LINE_DISCOUNT',
    entries: [],
  },
];

function fiscalDocument(
  overrides: Partial<FiscalEvidenceDocumentView> = {},
): FiscalEvidenceDocumentView {
  return {
    documentId: 'doc-2',
    documentType: 'SALE',
    correctsDocumentId: null,
    status: 'ISSUED',
    reasonCode: 'PARTNER_FISCALIZED',
    reasonNote: 'issued',
    responsibility: 'PARTNER',
    providerType: 'CLICK',
    seller: {
      legalEntityId: 'entity-1',
      tin: '123456789',
      name: 'Fiskal A MCHJ',
      source: 'OPENING',
    },
    merchantAccount: null,
    tenderId: 'tender-2',
    paymentIntentId: null,
    hasEvidence: true,
    identifiers: {
      revealed: false,
      fiscalSign: '****B6D8',
      receiptNumber: '****1234',
      receiptUrl: '****6D8',
      terminalId: '****0042',
    },
    registeredAt: '2026-10-08T09:00:00Z',
    providerStatusCode: null,
    lines: { state: 'NOT_SENT', synthetic: false, items: [], markingCodesTransmitted: null },
    tenderSplit: null,
    correction: null,
    submittedAt: null,
    issuedAt: '2026-10-08T09:00:00Z',
    blockedAt: null,
    attemptCount: 1,
    version: 2,
    createdAt: '2026-10-08T09:00:00Z',
    ...overrides,
  };
}

function evidenceOf(...documents: FiscalEvidenceDocumentView[]): FiscalEvidenceView {
  return {
    orderId: 'order-3',
    publicOrderNumber: '484',
    retention: { years: 7, provisional: true },
    marks: { state: 'NOT_BUILT', required: null, captured: null },
    revealed: false,
    purpose: null,
    documents,
  };
}

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
    grantFutureDiscount: ReturnType<typeof vi.fn>;
    unverifiedRemedies: ReturnType<typeof vi.fn>;
    remedyTotals: ReturnType<typeof vi.fn>;
    verifyRemedy: ReturnType<typeof vi.fn>;
  };
  let orderLookup: { byNumber: ReturnType<typeof vi.fn> };
  let evidenceApi: {
    paymentTypes: ReturnType<typeof vi.fn>;
    forOrder: ReturnType<typeof vi.fn>;
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
      grantFutureDiscount: vi
        .fn()
        .mockResolvedValue({ ...UNVERIFIED_REMEDY, remedyType: 'FUTURE_DISCOUNT' }),
      unverifiedRemedies: vi
        .fn()
        .mockResolvedValue({ items: [UNVERIFIED_REMEDY], nextCursor: null }),
      remedyTotals: vi.fn().mockResolvedValue([]),
      verifyRemedy: vi.fn().mockResolvedValue({ recorded: true }),
    };
    orderLookup = { byNumber: vi.fn().mockResolvedValue([]) };
    evidenceApi = {
      paymentTypes: vi.fn().mockResolvedValue(PAYMENT_TYPES),
      forOrder: vi.fn().mockResolvedValue(evidenceOf()),
    };

    await TestBed.configureTestingModule({
      imports: [PaymentsPage],
      providers: [
        { provide: PaymentsApi, useValue: api },
        { provide: OrderLookupApi, useValue: orderLookup },
        { provide: CurrentTenant, useValue: new FakeCurrentTenant() },
        { provide: FiscalEvidenceApi, useValue: evidenceApi },
        { provide: SessionCapabilities, useValue: { has: () => false } },
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

  it('renders the payment[] array -- every tender in sequence, with its own status and refunded amount', async () => {
    api.orderPayment.mockResolvedValue(SPLIT_TENDER_ORDER);

    const input = fixture.nativeElement.querySelector('#order-id') as HTMLInputElement;
    input.value = 'order-3';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (
      Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find(
        (button) => button.textContent?.trim() === 'Look up',
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    // The old "one tender only, split settlement has not shipped" note is gone.
    expect(text).not.toContain('has not shipped');

    // The tenders table, found by the cell only it has: the page now carries other tables that name a
    // payment method (the fiscal payment types), and a text match would count them too.
    const rows = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('[data-testid="tender-fiscal"]'),
    ).map((cell) => cell.closest('tr') as HTMLElement);
    expect(rows).toHaveLength(2);

    const firstCells = Array.from(rows[0].querySelectorAll('td')).map((cell) =>
      cell.textContent?.trim(),
    );
    const secondCells = Array.from(rows[1].querySelectorAll('td')).map((cell) =>
      cell.textContent?.trim(),
    );

    // Sequence 1, the balance tender: settled, and untouched while money remains.
    expect(firstCells[0]).toBe('1');
    expect(firstCells[1]).toBe('Balance');
    expect(firstCells[3]).toContain('Settled');
    expect(firstCells[4]).toMatch(/^0\b/);

    // Sequence 2, the money tender: settled, partially refunded.
    expect(secondCells[0]).toBe('2');
    expect(secondCells[1]).toBe('CLICK');
    expect(secondCells[3]).toContain('Settled');
    expect(secondCells[4]).toMatch(/20\s000/);
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
    ) as HTMLSelectElement;
    reasonCode.value = 'ITEM_MISSING';
    reasonCode.dispatchEvent(new Event('change'));
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
          { provide: OrderLookupApi, useValue: orderLookup },
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

  it('finds an order by its public number and loads it when there is exactly one match', async () => {
    orderLookup.byNumber.mockResolvedValue([
      {
        orderId: 'order-1',
        publicOrderNumber: '482',
        brandId: 'brand-1',
        locationId: 'loc-1',
        status: 'CONFIRMED',
        total: { amountMinor: 45_000, currency: 'UZS' },
        createdAt: '2026-08-30T09:00:00Z',
      },
    ]);

    const input = fixture.nativeElement.querySelector('#order-number') as HTMLInputElement;
    input.value = '482';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (
      Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find(
        (button) => button.textContent?.trim() === 'Find',
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(orderLookup.byNumber).toHaveBeenCalledWith(TENANT_ID, '482');
    expect(api.orderPayment).toHaveBeenCalledWith(TENANT_ID, 'order-1');
  });

  it('lists every candidate when the number is ambiguous, and picking one loads it', async () => {
    orderLookup.byNumber.mockResolvedValue([
      {
        orderId: 'order-1',
        publicOrderNumber: '0042',
        brandId: 'brand-1',
        locationId: 'loc-1',
        status: 'CONFIRMED',
        total: { amountMinor: 45_000, currency: 'UZS' },
        createdAt: '2026-08-30T09:00:00Z',
      },
      {
        orderId: 'order-2',
        publicOrderNumber: '0042',
        brandId: 'brand-1',
        locationId: 'loc-2',
        status: 'CONFIRMED',
        total: { amountMinor: 82_000, currency: 'UZS' },
        createdAt: '2026-08-30T10:00:00Z',
      },
    ]);

    const input = fixture.nativeElement.querySelector('#order-number') as HTMLInputElement;
    input.value = '0042';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (
      Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find(
        (button) => button.textContent?.trim() === 'Find',
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.orderPayment).not.toHaveBeenCalled();
    const pickButtons = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).filter((button) => button.textContent?.trim() === 'Use this order');
    expect(pickButtons).toHaveLength(2);

    (pickButtons[1] as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.orderPayment).toHaveBeenCalledWith(TENANT_ID, 'order-2');
  });

  it('grants a future discount through its own endpoint, reaching the third RemedyType', async () => {
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

    const kind = fixture.nativeElement.querySelector('#remedy-kind') as HTMLSelectElement;
    kind.value = 'FUTURE_DISCOUNT';
    kind.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    const percent = fixture.nativeElement.querySelector('#discount-percent') as HTMLInputElement;
    percent.value = '1000';
    percent.dispatchEvent(new Event('input'));
    const max = fixture.nativeElement.querySelector('#discount-maximum') as HTMLInputElement;
    max.value = '20000';
    max.dispatchEvent(new Event('input'));
    const reasonCode = fixture.nativeElement.querySelector(
      '#remedy-reason-code',
    ) as HTMLSelectElement;
    reasonCode.value = 'GOODWILL';
    reasonCode.dispatchEvent(new Event('change'));
    const reason = fixture.nativeElement.querySelector('#remedy-reason') as HTMLInputElement;
    reason.value = 'Apology for the wait';
    reason.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const submit = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.form__actions button'),
    ).find((button) => button.textContent?.trim() === 'Record') as HTMLButtonElement;
    expect(submit.disabled).toBe(false);
    submit.click();
    await flushMicrotasks();

    expect(api.grantFutureDiscount).toHaveBeenCalledWith(
      TENANT_ID,
      'order-1',
      expect.objectContaining({
        appliesTo: 'SUBTOTAL',
        benefit: 'PERCENT',
        percentBasisPoints: 1000,
        maximumMinor: 20000,
        uses: 1,
        validForDays: 30,
        reasonCode: 'GOODWILL',
        reason: 'Apology for the wait',
      }),
    );
    expect(api.recordRefund).not.toHaveBeenCalled();
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

  // ---------------------------------------------------------------- fiscal evidence (row X.2, ADR 0166)

  async function lookUp(orderId: string): Promise<void> {
    const input = fixture.nativeElement.querySelector('#order-id') as HTMLInputElement;
    input.value = orderId;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (
      Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find(
        (button) => button.textContent?.trim() === 'Look up',
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  const byTestId = (id: string): HTMLElement | null =>
    (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${id}"]`);

  describe('which payment types are fiscalized', () => {
    it('reads the derived model for the tenant on init, not an order', () => {
      expect(evidenceApi.paymentTypes).toHaveBeenCalledWith(TENANT_ID);
    });

    it('says, per method, who issues the receipt, under which path, and whether it can be issued today', () => {
      const click = byTestId('fiscal-payment-type-CLICK');
      expect(click?.textContent).toContain('Issued through the provider’s merchant account');
      expect(click?.textContent).toContain('Provider merchant account');
      expect(
        click?.querySelector('[data-testid="fiscal-payment-type-state"]')?.textContent,
      ).toContain('A receipt can be issued');
      // The entity and the account it is issued under, the INN a tax inspector asks for first.
      const entry = click?.querySelector('[data-testid="fiscal-payment-type-entry"]');
      expect(entry?.textContent).toContain('Fiskal A MCHJ');
      expect(entry?.textContent).toContain('123456789');
      expect(entry?.textContent).toContain('****e-77');
    });

    it('says a registered method with no terminal behind it has no fiscal path, rather than that it is fiscalized', () => {
      const cash = byTestId('fiscal-payment-type-CASH');
      expect(
        cash?.querySelector('[data-testid="fiscal-payment-type-state"]')?.textContent,
      ).toContain('No fiscal path');
    });

    it('says a balance tender has nothing to receipt, and why', () => {
      const balance = byTestId('fiscal-payment-type-LOYALTY_POINTS');
      expect(
        balance?.querySelector('[data-testid="fiscal-payment-type-state"]')?.textContent,
      ).toContain('Nothing to receipt');
      expect(balance?.textContent).toContain('per-line discount');
    });

    it('offers no way to set it: it is a table, not a form', () => {
      const section = byTestId('fiscal-payment-types') as HTMLElement;
      expect(section.querySelectorAll('input, select, textarea, button')).toHaveLength(0);
    });

    it('says plainly when the role cannot read it', async () => {
      evidenceApi.paymentTypes.mockRejectedValue(
        new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null),
      );
      fixture = TestBed.createComponent(PaymentsPage);
      fixture.detectChanges();
      await flushMicrotasks();
      fixture.detectChanges();

      expect(byTestId('fiscal-payment-types-denied')).not.toBeNull();
    });

    it('says it failed on any other error', async () => {
      evidenceApi.paymentTypes.mockRejectedValue(new ApiError('INTERNAL_ERROR', 500, null, null));
      fixture = TestBed.createComponent(PaymentsPage);
      fixture.detectChanges();
      await flushMicrotasks();
      fixture.detectChanges();

      expect(byTestId('fiscal-payment-types-error')).not.toBeNull();
    });
  });

  describe('a tender links to its fiscal document', () => {
    it('reads the fiscal evidence of the order that was looked up, for this tenant', async () => {
      api.orderPayment.mockResolvedValue(SPLIT_TENDER_ORDER);
      evidenceApi.forOrder.mockResolvedValue(evidenceOf(fiscalDocument()));
      await lookUp('order-3');

      expect(evidenceApi.forOrder).toHaveBeenCalledWith(TENANT_ID, 'order-3');
      expect(byTestId('fiscal-evidence')).not.toBeNull();
    });

    it('shows the receipt status beside the tender that document is, and none beside a tender it is not', async () => {
      api.orderPayment.mockResolvedValue(SPLIT_TENDER_ORDER);
      evidenceApi.forOrder.mockResolvedValue(evidenceOf(fiscalDocument()));
      await lookUp('order-3');

      const cells = Array.from(
        (fixture.nativeElement as HTMLElement).querySelectorAll('[data-testid="tender-fiscal"]'),
      );
      expect(cells).toHaveLength(2);
      // tender-1 is the balance tender: a discount on the money tender's receipt, so no document of its own.
      expect(cells[0].textContent).toContain('No receipt document');
      expect(cells[1].textContent).toContain('Issued');
      expect(cells[1].querySelector('[data-testid="tender-fiscal-open"]')).not.toBeNull();
    });

    it('takes the operator to that document’s evidence card and puts the focus on it', async () => {
      api.orderPayment.mockResolvedValue(SPLIT_TENDER_ORDER);
      evidenceApi.forOrder.mockResolvedValue(evidenceOf(fiscalDocument()));
      await lookUp('order-3');

      (byTestId('tender-fiscal-open') as HTMLButtonElement).click();
      fixture.detectChanges();

      const card = (fixture.nativeElement as HTMLElement).querySelector('#fiscal-doc-doc-2');
      expect(card).not.toBeNull();
      expect(document.activeElement).toBe(card);
    });

    it('drops the previous order’s documents when another order is looked up', async () => {
      api.orderPayment.mockResolvedValue(SPLIT_TENDER_ORDER);
      evidenceApi.forOrder.mockResolvedValue(evidenceOf(fiscalDocument()));
      await lookUp('order-3');
      expect(byTestId('tender-fiscal-open')).not.toBeNull();

      api.orderPayment.mockResolvedValue(SPLIT_TENDER_ORDER);
      evidenceApi.forOrder.mockResolvedValue(evidenceOf());
      await lookUp('order-3');

      expect(byTestId('tender-fiscal-open')).toBeNull();
    });
  });
});

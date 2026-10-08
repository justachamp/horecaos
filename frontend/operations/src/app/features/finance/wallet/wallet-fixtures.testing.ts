import {
  CardOnFileView,
  EnrolmentStartedView,
  LedgerEntryView,
  PaymentDetailsView,
  PrepaymentInvoiceView,
  StatementPaymentView,
  TenantWalletView,
  TopUpView,
} from './wallet-api';

/**
 * Plain data for the wallet specs: shapes the server answers, in UZS, the way
 * `CommercialOperationsWalletController` serialises them. No Vitest import, because
 * `tsconfig.app.json` type-checks every non-spec `.ts` under `src/` with no test types.
 */
export const TENANT_ID = 'tenant-1';

const uzs = (amountMinor: number) => ({ amountMinor, currency: 'UZS' });
export { uzs };

export const CARD: CardOnFileView = {
  last4: '4242',
  brand: 'HUMO',
  expiryMonth: 3,
  expiryYear: 2029,
  lapsed: false,
  lapsesSoon: false,
  boundAt: '2026-10-01T09:00:00Z',
};

/** A tenant with a card on file, both ways of paying connected, and nothing about to lapse. */
export const WALLET: TenantWalletView = {
  paidBalance: uzs(2_000_000),
  bonusBalance: uzs(300_000),
  bonusSpendableBalance: uzs(300_000),
  paymentMethod: 'WALLET',
  card: CARD,
  lapsingGrants: [],
  pendingTopUp: null,
  cardPaymentsAvailable: true,
  bankTransferAvailable: true,
};

/** HorecaOS has connected neither a card merchant account nor published bank details. */
export const WALLET_NOT_CONNECTED: TenantWalletView = {
  ...WALLET,
  card: null,
  cardPaymentsAvailable: false,
  bankTransferAvailable: false,
};

export const PAYMENT_DETAILS: PaymentDetailsView = {
  configured: true,
  beneficiary: 'HorecaOS LLC',
  bankName: 'Example Bank',
  account: '20208000100000000001',
  mfo: '00014',
  taxId: '300000001',
};

export const ENROLMENT: EnrolmentStartedView = {
  sessionReference: 'session-1',
  hostedFormUrl: 'https://pay.example.test/enrol/session-1',
  clientParameters: {},
  expiresAt: '2026-10-07T10:15:00Z',
};

export const TOP_UP_SUCCEEDED: TopUpView = {
  topUpId: 'tu-1',
  amount: uzs(150_000),
  outcome: 'SUCCEEDED',
  reason: null,
  walletEntryId: 'entry-1',
  requestedAt: '2026-10-07T09:00:00Z',
  settledAt: '2026-10-07T09:00:02Z',
};

export const TOP_UP_DECLINED: TopUpView = {
  topUpId: 'tu-2',
  amount: uzs(90_000),
  outcome: 'FAILED',
  reason: 'INSUFFICIENT_FUNDS',
  walletEntryId: null,
  requestedAt: '2026-10-06T09:00:00Z',
  settledAt: '2026-10-06T09:00:03Z',
};

export const TOP_UP_PENDING: TopUpView = {
  topUpId: 'tu-3',
  amount: uzs(70_000),
  outcome: 'PENDING',
  reason: null,
  walletEntryId: null,
  requestedAt: '2026-10-07T09:30:00Z',
  settledAt: null,
};

export const INVOICE_OPEN: PrepaymentInvoiceView = {
  invoiceId: 'inv-1',
  number: 'PI-202610-000001',
  status: 'OPEN',
  amount: uzs(1_000_000),
  paid: uzs(0),
  due: uzs(1_000_000),
  validUntil: '2026-10-21T09:00:00Z',
  issuedAt: '2026-10-07T09:00:00Z',
  cancelledAt: null,
  paymentDetails: PAYMENT_DETAILS,
  paymentPurpose: 'PI-202610-000001',
  beforeTax: true,
};

export const INVOICE_PART_PAID: PrepaymentInvoiceView = {
  ...INVOICE_OPEN,
  invoiceId: 'inv-2',
  number: 'PI-202609-000007',
  status: 'PARTIALLY_PAID',
  paid: uzs(400_000),
  due: uzs(600_000),
};

export const STATEMENT_PAYMENTS: readonly StatementPaymentView[] = [
  {
    statementId: 'st-2',
    number: 'S-2026-09-000002',
    periodKey: '2026-09',
    total: uzs(1_500_000),
    paid: uzs(1_000_000),
    due: uzs(500_000),
  },
  {
    statementId: 'st-1',
    number: 'S-2026-08-000001',
    periodKey: '2026-08',
    total: uzs(1_200_000),
    paid: uzs(1_200_000),
    due: uzs(0),
  },
];

export function ledgerEntry(
  index: number,
  overrides: Partial<LedgerEntryView> = {},
): LedgerEntryView {
  return {
    entryId: `entry-${index}`,
    moneyKind: 'PAID',
    entryType: 'TOP_UP',
    amount: uzs(100_000 * index),
    statementId: null,
    grantId: null,
    expiresAt: null,
    createdAt: '2026-10-07T09:00:00Z',
    ...overrides,
  };
}

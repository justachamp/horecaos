import { MessageKey } from '../../core/i18n/messages.en';
import {
  EntitlementBenefit,
  EntitlementScope,
  ExecutionChannel,
  PaymentAttemptStatus,
  PaymentIntentStatus,
  RemedyType,
  SettlementBasis,
  TenderStatus,
  VerificationState,
} from './payments/payments-api';

/**
 * Enum-to-message-key maps for the Finance section.
 *
 * A template cannot build `'finance.remedyType.' + value` and pipe it through
 * `t` — {@link TPipe}'s whole point is that Angular's strict template checker
 * resolves the literal key against the {@link MessageKey} union, and a
 * concatenated string is not a literal. These maps do the lookup in
 * TypeScript instead, where the same union still catches a missing case (a
 * `Record<RemedyType, MessageKey>` with a value omitted is a `tsc` error).
 */
export const REMEDY_TYPE_KEYS: Readonly<Record<RemedyType, MessageKey>> = {
  ORDER_REFUND: 'finance.remedyType.ORDER_REFUND',
  DELIVERY_FEE_REIMBURSEMENT: 'finance.remedyType.DELIVERY_FEE_REIMBURSEMENT',
  FUTURE_DISCOUNT: 'finance.remedyType.FUTURE_DISCOUNT',
};

export const SETTLEMENT_BASIS_KEYS: Readonly<Record<SettlementBasis, MessageKey>> = {
  OPERATOR_ATTESTED: 'finance.settlementBasis.OPERATOR_ATTESTED',
  PLATFORM_SETTLED: 'finance.settlementBasis.PLATFORM_SETTLED',
  MIXED: 'finance.settlementBasis.MIXED',
  NOT_MONEY: 'finance.settlementBasis.NOT_MONEY',
};

export const VERIFICATION_STATE_KEYS: Readonly<Record<VerificationState, MessageKey>> = {
  UNVERIFIED: 'finance.verificationState.UNVERIFIED',
  CONFIRMED: 'finance.verificationState.CONFIRMED',
  DISPUTED: 'finance.verificationState.DISPUTED',
};

export const EXECUTION_CHANNEL_KEYS: Readonly<Record<ExecutionChannel, MessageKey>> = {
  PROVIDER_CONSOLE: 'finance.executionChannel.PROVIDER_CONSOLE',
  CASH_DRAWER: 'finance.executionChannel.CASH_DRAWER',
  BANK_TRANSFER: 'finance.executionChannel.BANK_TRANSFER',
};

/** `order.orderStatus`'s own twelve values are `orders/order-status.ts`'s -- see `orderStatusLabel`. */
export const PAYMENT_INTENT_STATUS_KEYS: Readonly<Record<PaymentIntentStatus, MessageKey>> = {
  PENDING: 'finance.paymentIntentStatus.PENDING',
  AUTHORIZING: 'finance.paymentIntentStatus.AUTHORIZING',
  PAID: 'finance.paymentIntentStatus.PAID',
  CANCELLED: 'finance.paymentIntentStatus.CANCELLED',
  EXPIRED: 'finance.paymentIntentStatus.EXPIRED',
  FAILED: 'finance.paymentIntentStatus.FAILED',
};

export const PAYMENT_ATTEMPT_STATUS_KEYS: Readonly<Record<PaymentAttemptStatus, MessageKey>> = {
  INITIATED: 'finance.paymentAttemptStatus.INITIATED',
  PRESENTED: 'finance.paymentAttemptStatus.PRESENTED',
  RESERVED: 'finance.paymentAttemptStatus.RESERVED',
  CAPTURED: 'finance.paymentAttemptStatus.CAPTURED',
  CANCELLED: 'finance.paymentAttemptStatus.CANCELLED',
  EXPIRED: 'finance.paymentAttemptStatus.EXPIRED',
  REVERSED: 'finance.paymentAttemptStatus.REVERSED',
  FAILED: 'finance.paymentAttemptStatus.FAILED',
  UNCERTAIN: 'finance.paymentAttemptStatus.UNCERTAIN',
};

/** W05: one settlement tender's own status (ADR 0046), distinct from `PaymentIntentStatus`. */
export const TENDER_STATUS_KEYS: Readonly<Record<TenderStatus, MessageKey>> = {
  PLANNED: 'finance.tenderStatus.PLANNED',
  RESERVED: 'finance.tenderStatus.RESERVED',
  SETTLED: 'finance.tenderStatus.SETTLED',
  RELEASED: 'finance.tenderStatus.RELEASED',
  REVERSED: 'finance.tenderStatus.REVERSED',
  FAILED: 'finance.tenderStatus.FAILED',
};

export const ENTITLEMENT_SCOPE_KEYS: Readonly<Record<EntitlementScope, MessageKey>> = {
  SUBTOTAL: 'finance.entitlementScope.SUBTOTAL',
  DELIVERY_FEE: 'finance.entitlementScope.DELIVERY_FEE',
  BOTH: 'finance.entitlementScope.BOTH',
};

export const ENTITLEMENT_BENEFIT_KEYS: Readonly<Record<EntitlementBenefit, MessageKey>> = {
  PERCENT: 'finance.entitlementBenefit.PERCENT',
  FIXED_AMOUNT: 'finance.entitlementBenefit.FIXED_AMOUNT',
};

/**
 * A provider-mappable vocabulary for a refund or void's `reasonCode`,
 * replacing the 48-character free-text box (`operations-spec/finance.md`
 * §8.1's own gap: "a refund's reasonCode is free text an operator types, not
 * a taxonomy mapped per provider"). `OrderRemedyService` still stores
 * whatever string arrives here -- ADR 0048 never calls a provider to reverse
 * anything, so there is no provider API to validate against yet -- but a
 * fixed list is the one-lookup-table-away shape a future mapping needs,
 * exactly the way `FiscalReasonCode.BLOCKING` already constrains the fiscal
 * queue's own filter instead of accepting anything typed.
 */
export const REFUND_REASON_CODES = [
  'CUSTOMER_CANCELLED',
  'ITEM_MISSING',
  'ITEM_QUALITY',
  'WRONG_ITEM',
  'LATE_DELIVERY',
  'NOT_DELIVERED',
  'DUPLICATE_CHARGE',
  'PRICING_ERROR',
  'GOODWILL',
  'OTHER',
] as const;

export type RefundReasonCode = (typeof REFUND_REASON_CODES)[number];

export const REFUND_REASON_CODE_KEYS: Readonly<Record<RefundReasonCode, MessageKey>> = {
  CUSTOMER_CANCELLED: 'finance.reasonCode.CUSTOMER_CANCELLED',
  ITEM_MISSING: 'finance.reasonCode.ITEM_MISSING',
  ITEM_QUALITY: 'finance.reasonCode.ITEM_QUALITY',
  WRONG_ITEM: 'finance.reasonCode.WRONG_ITEM',
  LATE_DELIVERY: 'finance.reasonCode.LATE_DELIVERY',
  NOT_DELIVERED: 'finance.reasonCode.NOT_DELIVERED',
  DUPLICATE_CHARGE: 'finance.reasonCode.DUPLICATE_CHARGE',
  PRICING_ERROR: 'finance.reasonCode.PRICING_ERROR',
  GOODWILL: 'finance.reasonCode.GOODWILL',
  OTHER: 'finance.reasonCode.OTHER',
};

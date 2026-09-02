import { MessageKey } from '../../core/i18n/messages.en';
import {
  ExecutionChannel,
  RemedyType,
  SettlementBasis,
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

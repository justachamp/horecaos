import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { MessageKey } from '../../core/i18n/messages.en';
import { describeApiError } from '../orders/order-errors';

/**
 * The `reason` code `PaymentCheckoutService.CheckoutRefusedException` and
 * `PresentationFailure` carry, mapped to an operator-facing sentence.
 *
 * `describeApiError` (reused from Orders — see that module's own file for why
 * this app shares it across features) only knows the generic ADR 0031 codes;
 * every one of these arrives as `RESOURCE_CONFLICT` or `RESOURCE_NOT_FOUND`
 * with a *specific* reason in `problem.reason`, and showing "somebody
 * changed this" for "this order is already paid" would send an operator
 * looking for a conflict that is not there.
 */
const REISSUE_REASON_KEYS: Readonly<Partial<Record<string, MessageKey>>> = {
  ORDER_NOT_FOUND: 'error.RESOURCE_NOT_FOUND',
  NO_PAYMENT_INTENT: 'finance.payments.reissue.reason.noPaymentIntent',
  NOT_PAYABLE_ONLINE: 'finance.payments.reissue.reason.notPayableOnline',
  ALREADY_PAID: 'finance.payments.reissue.reason.alreadyPaid',
  PAYMENT_CLOSED: 'finance.payments.reissue.reason.paymentClosed',
  SELLER_UNRESOLVED: 'finance.payments.reissue.reason.sellerUnresolved',
  BINDING_UNAVAILABLE: 'finance.payments.reissue.reason.bindingUnavailable',
  BINDING_CHANGED: 'finance.payments.reissue.reason.bindingChanged',
  PAYMENT_IN_DOUBT: 'finance.payments.reissue.reason.paymentInDoubt',
  PRESENTATION_NOT_REPEATABLE: 'finance.payments.reissue.reason.presentationNotRepeatable',
  PRESENTATION_UNAVAILABLE: 'finance.payments.reissue.reason.presentationUnavailable',
  PAYMENT_OUTCOME_UNCERTAIN: 'finance.payments.reissue.reason.outcomeUncertain',
};

/** The sentence to show for a refused re-presentation, falling back to the generic ADR 0031 mapping. */
export function describeReissueRefusal(
  error: ApiError,
  translate: (key: MessageKey, values?: Readonly<Record<string, string | number>>) => string,
): string {
  const reason = error.problem?.['reason'];
  const key = typeof reason === 'string' ? REISSUE_REASON_KEYS[reason] : undefined;
  if (key) {
    return translate(key);
  }
  if (error.code === ApiErrorCode.VALIDATION_FAILED) {
    return translate('finance.payments.reissue.reason.malformed');
  }
  return describeApiError(error, translate);
}

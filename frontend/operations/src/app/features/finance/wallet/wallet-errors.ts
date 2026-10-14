import { ApiError } from '../../../core/api/problem-details';
import { MessageKey } from '../../../core/i18n/messages.en';
import { describeApiError } from '../../orders/order-errors';

/**
 * The `reason` a refused wallet call carries, mapped to a sentence this screen owns.
 *
 * Every one of these arrives as `UNPROCESSABLE_STATE`, `RESOURCE_CONFLICT` or
 * `VALIDATION_FAILED` with the specific cause in `problem.reason`; the generic
 * ADR 0031 mapping would show the server's English `detail` for them, which is
 * right for a code nobody planned for and wrong for the ones a tenant meets
 * every day, in a language they did not choose. The provider's own refusals
 * (`WRONG_CODE`, `SESSION_EXPIRED`...) are the cardholder's to fix, so each says
 * what to do next.
 */
const WALLET_REASON_KEYS: Readonly<Partial<Record<string, MessageKey>>> = {
  CARDS_NOT_AVAILABLE: 'finance.wallet.reason.CARDS_NOT_AVAILABLE',
  NOT_CONFIGURED: 'finance.wallet.reason.CARDS_NOT_AVAILABLE',
  BANK_DETAILS_NOT_CONFIGURED: 'finance.wallet.reason.BANK_DETAILS_NOT_CONFIGURED',
  TOO_MANY_OPEN_INVOICES: 'finance.wallet.reason.TOO_MANY_OPEN_INVOICES',
  INVOICE_HAS_PAYMENTS: 'finance.wallet.reason.INVOICE_HAS_PAYMENTS',
  INVOICE_CANCELLED: 'finance.wallet.reason.INVOICE_CANCELLED',
  NO_CARD_ON_FILE: 'finance.wallet.reason.NO_CARD_ON_FILE',
  CHARGE_IN_FLIGHT: 'finance.wallet.reason.CHARGE_IN_FLIGHT',
  TOP_UP_IN_FLIGHT: 'finance.wallet.reason.TOP_UP_IN_FLIGHT',
  SESSION_UNKNOWN: 'finance.wallet.reason.SESSION_UNKNOWN',
  SESSION_EXPIRED: 'finance.wallet.reason.SESSION_EXPIRED',
  WRONG_CODE: 'finance.wallet.reason.WRONG_CODE',
  UNKNOWN_CARD_TOKEN: 'finance.wallet.reason.UNKNOWN_CARD_TOKEN',
  CARD_EXPIRED: 'finance.wallet.reason.CARD_EXPIRED',
  CARD_EXPIRY_REQUIRED: 'finance.wallet.reason.CARD_EXPIRY_REQUIRED',
  PROVIDER_UNAVAILABLE: 'finance.wallet.reason.PROVIDER_UNAVAILABLE',
  PROVIDER_NOT_CONNECTED: 'finance.wallet.reason.PROVIDER_NOT_CONNECTED',
};

/** A provider's own numbered refusal (`CLICK_ERROR_-301`): its table is unpublished, so the number is not worded. */
const PROVIDER_ERROR_PREFIX = 'CLICK_ERROR_';

type Translate = (key: MessageKey, values?: Readonly<Record<string, string | number>>) => string;

/** The sentence for a refused wallet call, falling back to the generic ADR 0031 mapping. */
export function describeWalletError(error: unknown, translate: Translate): string {
  if (!(error instanceof ApiError)) {
    return translate('error.unknown.noReference');
  }
  const reason = error.problem?.['reason'];
  const key =
    typeof reason === 'string'
      ? reason.startsWith(PROVIDER_ERROR_PREFIX)
        ? 'finance.wallet.reason.PROVIDER_REFUSED'
        : WALLET_REASON_KEYS[reason]
      : undefined;
  return key ? translate(key) : describeApiError(error, translate);
}

/**
 * The provider's decline code on a failed top-up, as a sentence, or null for one this screen has no
 * wording for: the table then shows the code itself, never a blank. Only a code something in this
 * repository actually emits is listed; a real provider's codes are added when its adapter exists.
 */
export function declineReasonKey(reason: string | null): MessageKey | null {
  if (reason === null) {
    return null;
  }
  if (reason.startsWith(PROVIDER_ERROR_PREFIX)) {
    return 'finance.wallet.decline.PROVIDER_ERROR';
  }
  switch (reason) {
    case 'INSUFFICIENT_FUNDS':
      return 'finance.wallet.decline.INSUFFICIENT_FUNDS';
    case 'PAYMENT_DECLINED':
      return 'finance.wallet.decline.PAYMENT_DECLINED';
    case 'PROVIDER_REJECTED':
      return 'finance.wallet.decline.PROVIDER_REJECTED';
    default:
      return null;
  }
}

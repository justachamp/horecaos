import { MessageKey } from '../../core/i18n/messages.en';

/**
 * Why a message did not arrive, or was never sent, in words (ADR 0146, ADR 0182).
 *
 * The platform answers with a bounded code, never a sentence and never anything a provider said:
 * a suppression reason for a message that was withheld (`TEMPLATE_AWAITING_PROVIDER`,
 * `CONSENT_WITHHELD`, `EXPIRED`...), or the code an attempt closed with for one that failed or was
 * rejected (`RECEIVER_UNREACHABLE`, `ADDRESS_REJECTED`...). The sentence belongs to whoever reads it.
 * A code a newer server added is shown as «reason code: X» rather than guessed at.
 *
 * Shared by the campaign's recipient list, its delivery counts and a courier broadcast's results, so
 * the same cause is said the same way in each.
 */
const REASON_KEYS: Readonly<Record<string, MessageKey>> = {
  // Withheld: the message was never sent.
  TEMPLATE_AWAITING_PROVIDER: 'marketing.delivery.withheld.TEMPLATE_AWAITING_PROVIDER',
  TEMPLATE_REFUSED_BY_PROVIDER: 'marketing.delivery.withheld.TEMPLATE_REFUSED_BY_PROVIDER',
  CONSENT_WITHHELD: 'marketing.delivery.withheld.CONSENT_WITHHELD',
  PREFERENCE_DISABLED: 'marketing.delivery.withheld.PREFERENCE_DISABLED',
  NO_RECIPIENT_ENDPOINT: 'marketing.delivery.withheld.NO_RECIPIENT_ENDPOINT',
  NO_RECIPIENT_ACCOUNT: 'marketing.delivery.withheld.NO_RECIPIENT_ACCOUNT',
  NO_ACTIVE_TEMPLATE: 'marketing.delivery.withheld.NO_ACTIVE_TEMPLATE',
  NO_TEMPLATE_FOR_LOCALE: 'marketing.delivery.withheld.NO_TEMPLATE_FOR_LOCALE',
  CHANNEL_NOT_AVAILABLE: 'marketing.delivery.withheld.CHANNEL_NOT_AVAILABLE',
  CAMPAIGN_NOT_SENDING: 'marketing.delivery.withheld.CAMPAIGN_NOT_SENDING',
  EXPIRED: 'marketing.delivery.withheld.EXPIRED',
  // Failed or rejected: the message was tried.
  RECEIVER_UNREACHABLE: 'marketing.delivery.reason.RECEIVER_UNREACHABLE',
  PROVIDER_REPORTED_FAILURE: 'marketing.delivery.reason.PROVIDER_REPORTED_FAILURE',
  SMS_PURPOSE_NOT_PERMITTED: 'marketing.delivery.reason.SMS_PURPOSE_NOT_PERMITTED',
  RECIPIENT_UNRESOLVABLE: 'marketing.delivery.reason.RECIPIENT_UNRESOLVABLE',
  IDENTITY_PROVIDER_UNAVAILABLE: 'marketing.delivery.reason.IDENTITY_PROVIDER_UNAVAILABLE',
  EMAIL_NOT_CONFIGURED: 'marketing.delivery.reason.EMAIL_NOT_CONFIGURED',
  ADDRESS_REJECTED: 'marketing.delivery.reason.ADDRESS_REJECTED',
  ADDRESS_INVALID: 'marketing.delivery.reason.ADDRESS_INVALID',
  MESSAGE_INVALID: 'marketing.delivery.reason.MESSAGE_INVALID',
  SMTP_UNAVAILABLE: 'marketing.delivery.reason.SMTP_UNAVAILABLE',
  // Web Push (ADR 0182, D14): what a push service said, as a bounded code.
  PUSH_SUBSCRIPTION_GONE: 'marketing.delivery.reason.PUSH_SUBSCRIPTION_GONE',
  PUSH_VAPID_REJECTED: 'marketing.delivery.reason.PUSH_VAPID_REJECTED',
  PUSH_NOT_CONFIGURED: 'marketing.delivery.reason.PUSH_NOT_CONFIGURED',
  PUSH_PAYLOAD_TOO_LARGE: 'marketing.delivery.reason.PUSH_PAYLOAD_TOO_LARGE',
  PUSH_REQUEST_REFUSED: 'marketing.delivery.reason.PUSH_REQUEST_REFUSED',
  PUSH_PAYLOAD_UNUSABLE: 'marketing.delivery.reason.PUSH_PAYLOAD_UNUSABLE',
  PUSH_REDIRECT_REFUSED: 'marketing.delivery.reason.PUSH_REDIRECT_REFUSED',
  PUSH_SERVICE_ERROR: 'marketing.delivery.reason.PUSH_SERVICE_ERROR',
  PUSH_RATE_LIMITED: 'marketing.delivery.reason.PUSH_RATE_LIMITED',
  PUSH_SERVICE_UNREACHABLE: 'marketing.delivery.reason.PUSH_SERVICE_UNREACHABLE',
  PUSH_SECRET_MISSING: 'marketing.delivery.reason.PUSH_SECRET_UNAVAILABLE',
  PUSH_SECRET_INVALID: 'marketing.delivery.reason.PUSH_SECRET_UNAVAILABLE',
  // A courier broadcast's own.
  COURIER_HAS_NO_NUMBER: 'marketing.delivery.reason.COURIER_HAS_NO_NUMBER',
};

/** The sentence for a reason code; one this build does not know is said as «reason code: X». */
export function explainDeliveryReason(
  code: string,
  t: (key: MessageKey, values?: Readonly<Record<string, string | number>>) => string,
): string {
  const key = REASON_KEYS[code];
  return key ? t(key) : t('marketing.delivery.reason.other', { code });
}

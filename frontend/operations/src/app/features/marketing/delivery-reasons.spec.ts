import { describe, expect, it } from 'vitest';

import { messagesEn } from '../../core/i18n/messages.en';
import { MessageKey } from '../../core/i18n/messages.en';
import { explainDeliveryReason } from './delivery-reasons';

/** A translator that is the English catalogue with `{name}` placeholders filled, as the app's own is. */
function english(key: MessageKey, values?: Readonly<Record<string, string | number>>): string {
  let text: string = messagesEn[key];
  for (const [name, value] of Object.entries(values ?? {})) {
    text = text.replace(`{${name}}`, String(value));
  }
  return text;
}

describe('explainDeliveryReason', () => {
  it('says a withheld message’s reason in words', () => {
    expect(explainDeliveryReason('TEMPLATE_AWAITING_PROVIDER', english)).toContain(
      'awaiting the gateway',
    );
    expect(explainDeliveryReason('CONSENT_WITHHELD', english)).toContain('Consent withdrawn');
    expect(explainDeliveryReason('EXPIRED', english)).toContain('Expired before it was sent');
  });

  it('says why a tried message failed, for the gateway and for the mail relay', () => {
    expect(explainDeliveryReason('RECEIVER_UNREACHABLE', english)).toContain('cannot be reached');
    expect(explainDeliveryReason('ADDRESS_REJECTED', english)).toContain('refused this address');
    expect(explainDeliveryReason('SMTP_UNAVAILABLE', english)).toContain('could not be reached');
  });

  it('says why a courier was not messaged', () => {
    expect(explainDeliveryReason('COURIER_HAS_NO_NUMBER', english)).toContain('holds no number');
  });

  it('shows a code a newer server added as a reason code, never a guess', () => {
    expect(explainDeliveryReason('SOMETHING_NEW', english)).toBe('Reason code: SOMETHING_NEW');
  });
});

import { MessageKey } from '../../../core/i18n/messages.en';

/**
 * The closed set of `action_code` values this platform's personal-data
 * reveal and export call sites actually write today — grepped against
 * `CustomerProfileService`, `CustomerBlacklistService`,
 * `CustomerListQueryService`, `AudienceService` and `OrderQueryService`, not
 * guessed from naming. A code not in this map is not shown on the egress
 * log at all (see `data-privacy-page.ts`'s own doc for why filtering
 * narrower than the raw audit search is the honest choice here), so this
 * list is deliberately exhaustive against what exists rather than
 * illustrative.
 */
const PII_ACTION_LABEL_KEYS: Readonly<Record<string, MessageKey>> = {
  'customer.contact.revealed': 'settings.dataPrivacy.egress.action.contactRevealed',
  'customer.address.revealed': 'settings.dataPrivacy.egress.action.addressRevealed',
  'customer.dateOfBirth.revealed': 'settings.dataPrivacy.egress.action.dateOfBirthRevealed',
  'customer.blacklist.revealed': 'settings.dataPrivacy.egress.action.blacklistRevealed',
  'customer.list.exported': 'settings.dataPrivacy.egress.action.listExported',
  MARKETING_AUDIENCE_EXPORTED: 'settings.dataPrivacy.egress.action.audienceExported',
  'order.line_note.revealed': 'settings.dataPrivacy.egress.action.lineNoteRevealed',
  'order.customer_phone.revealed': 'settings.dataPrivacy.egress.action.orderPhoneRevealed',
  'order.customer_address.revealed': 'settings.dataPrivacy.egress.action.orderAddressRevealed',
};

/** The known personal-data action codes, for filtering a broader audit search down to this log. */
export const PII_ACTION_CODES: readonly string[] = Object.keys(PII_ACTION_LABEL_KEYS);

/** The label key for a known PII-egress action code, or `null` — callers should not render an unlisted code here at all. */
export function piiEgressLabelKey(actionCode: string): MessageKey | null {
  return PII_ACTION_LABEL_KEYS[actionCode] ?? null;
}

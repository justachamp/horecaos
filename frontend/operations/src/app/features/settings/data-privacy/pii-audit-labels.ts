import { MessageKey } from '../../../core/i18n/messages.en';

/**
 * The words for the `action_code` values the platform registers as personal data leaving (`PiiEgressActions`).
 *
 * This is a table of labels and nothing else. Which actions belong on the egress log is decided by the backend: the
 * log asks the audit search for `piiEgress=true` and shows what comes back, so an action registered after this file was
 * written still appears, under its raw code, until a label is added here. That is the point of the change: this file used to
 * be the filter as well, written by hand against the call sites that existed that day, and the merchant's log was missing
 * six real emitters (a lead's number, a booking guest, a promotion redemption's customer, a report export, two
 * courier-side reveals) without anyone being able to tell.
 */
const PII_ACTION_LABEL_KEYS: Readonly<Record<string, MessageKey>> = {
  'customer.contact.revealed': 'settings.dataPrivacy.egress.action.contactRevealed',
  'customer.address.revealed': 'settings.dataPrivacy.egress.action.addressRevealed',
  'customer.dateOfBirth.revealed': 'settings.dataPrivacy.egress.action.dateOfBirthRevealed',
  'customer.blacklist.revealed': 'settings.dataPrivacy.egress.action.blacklistRevealed',
  'customer.lead.revealed': 'settings.dataPrivacy.egress.action.leadRevealed',
  'customer.list.exported': 'settings.dataPrivacy.egress.action.listExported',
  MARKETING_AUDIENCE_EXPORTED: 'settings.dataPrivacy.egress.action.audienceExported',
  'order.line_note.revealed': 'settings.dataPrivacy.egress.action.lineNoteRevealed',
  'order.customer_phone.revealed': 'settings.dataPrivacy.egress.action.orderPhoneRevealed',
  'order.customer_address.revealed': 'settings.dataPrivacy.egress.action.orderAddressRevealed',
  'order.map_points.revealed': 'settings.dataPrivacy.egress.action.orderMapPointsRevealed',
  'pricing.promotion.redemption.customer_revealed':
    'settings.dataPrivacy.egress.action.redemptionCustomerRevealed',
  'fulfillment.grouping.dropoff-revealed': 'settings.dataPrivacy.egress.action.dropoffRevealed',
  'courier.customer_location.revealed':
    'settings.dataPrivacy.egress.action.courierCustomerLocationRevealed',
  'fiscal.evidence.revealed': 'settings.dataPrivacy.egress.action.fiscalEvidenceRevealed',
  'dinein.reservation.guest_revealed':
    'settings.dataPrivacy.egress.action.reservationGuestRevealed',
  'voice.screen_pop.caller_number_revealed':
    'settings.dataPrivacy.egress.action.callerNumberRevealed',
  'report.export.completed': 'settings.dataPrivacy.egress.action.reportExported',
  'telemetry.courier_track.revealed': 'settings.dataPrivacy.egress.action.courierTrackRevealed',
  'courier.registration.revealed': 'settings.dataPrivacy.egress.action.courierRegistrationRevealed',
  'courier.compliance.revealed': 'settings.dataPrivacy.egress.action.courierComplianceRevealed',
  'tenant.owner_invitation.recipient_revealed':
    'settings.dataPrivacy.egress.action.invitationRecipientRevealed',
};

/** Every code this console has words for, so a spec can hold the table against what the platform registers. */
export const LABELLED_EGRESS_CODES: readonly string[] = Object.keys(PII_ACTION_LABEL_KEYS);

/** The label key for a registered egress action code, or `null` for one this console has no words for yet. */
export function piiEgressLabelKey(actionCode: string): MessageKey | null {
  return PII_ACTION_LABEL_KEYS[actionCode] ?? null;
}

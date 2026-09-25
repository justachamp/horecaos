import { Locale } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { ACTIVITY_ACTION_SENTENCES } from './activity-log-action-sentences';

/**
 * A plain-language label for the action codes an operator sees most often on
 * the activity log (Staff 9.3): grants, tenant/brand/location administration,
 * audit reads themselves.
 *
 * Unlike `approval-action-labels.ts`'s closed set — every code
 * `requireApproval` can ever raise — the activity log spans every module's
 * own action codes. Full coverage of the rest lives in
 * `activity-log-action-sentences.ts` instead of more entries here — that
 * file's own doc explains why it is a separate, lazy-loaded table rather
 * than more `messages.*.ts` keys — and {@link bulkActivityLogActionSentence}
 * reads it. {@link humanizeActionCode} is the last resort for a code neither
 * table names, so a new producer never renders a blank cell or forces a
 * translation entry before it can be read at all.
 */
const ACTION_LABEL_KEYS: Readonly<Record<string, MessageKey>> = {
  'iam.grant.granted': 'staff.activity.action.grantGranted',
  'iam.grant.revoked': 'staff.activity.action.grantRevoked',
  'audit.read': 'staff.activity.action.auditRead',
  'brand.revised': 'staff.activity.action.brandRevised',
  'brand.activated': 'staff.activity.action.brandActivated',
  'brand.deleted': 'staff.activity.action.brandDeleted',
  'location.revised': 'staff.activity.action.locationRevised',
  'location.deleted': 'staff.activity.action.locationDeleted',
  'tenant.suspended': 'staff.activity.action.tenantSuspended',
  'tenant.reactivated': 'staff.activity.action.tenantReactivated',
  // Was 'tenant.activate' (present tense) until Staff 9.3's coverage pass —
  // TenantControlPlaneService.OnboardingService actually emits
  // 'tenant.activated' (past tense, matching every sibling in this map), so
  // the old key never matched a single real event.
  'tenant.activated': 'staff.activity.action.tenantActivated',
  'order.cancel': 'staff.activity.action.orderCancel',
};

/** The label key for a known action code, or `null` for one this map does not (yet) name. */
export function activityLogActionLabelKey(actionCode: string): MessageKey | null {
  return ACTION_LABEL_KEYS[actionCode] ?? null;
}

/**
 * The mechanically-generated sentence for {@code actionCode} in {@code locale},
 * or `null` for a code neither this table nor {@link activityLogActionLabelKey}
 * names — see `activity-log-action-sentences.ts`'s own doc for what "generated"
 * means here and why it is a separate table.
 */
export function bulkActivityLogActionSentence(actionCode: string, locale: Locale): string | null {
  return ACTIVITY_ACTION_SENTENCES[actionCode]?.[locale] ?? null;
}

/**
 * `iam.grants.revoke` → `Iam grants revoke` — readable without waiting on a
 * translation entry. Not a sentence, just enough structure that an operator
 * is not reading a dotted machine code.
 */
export function humanizeActionCode(actionCode: string): string {
  const words = actionCode.split(/[._-]/).filter((word) => word.length > 0);
  if (words.length === 0) {
    return actionCode;
  }
  const [first, ...rest] = words;
  return [capitalize(first), ...rest].join(' ');
}

function capitalize(word: string): string {
  return word.length === 0 ? word : word[0].toUpperCase() + word.slice(1);
}

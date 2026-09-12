import { MessageKey } from '../../core/i18n/messages.en';

/**
 * A plain-language label for the action codes an operator sees most often on
 * the activity log (Staff 9.3): grants, tenant/brand/location administration,
 * audit reads themselves.
 *
 * Unlike `approval-action-labels.ts`'s closed set — every code
 * `requireApproval` can ever raise — the activity log spans every module's
 * own action codes, and there is no complete list to maintain here. {@link
 * activityLogActionLabel} therefore falls back to {@link humanizeActionCode}
 * for anything this map does not name, so a new producer never renders a
 * blank cell or forces a translation entry before it can be read at all.
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
  'tenant.activate': 'staff.activity.action.tenantActivate',
  'order.cancel': 'staff.activity.action.orderCancel',
};

/** The label key for a known action code, or `null` for one this map does not (yet) name. */
export function activityLogActionLabelKey(actionCode: string): MessageKey | null {
  return ACTION_LABEL_KEYS[actionCode] ?? null;
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

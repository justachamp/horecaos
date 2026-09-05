import { MessageKey } from '../../core/i18n/messages.en';

/**
 * A plain-language label for the handful of `action_code` values this
 * platform's `requireApproval` call sites actually raise today (per
 * `ApprovalAction`): refunds and future-order discounts above threshold,
 * manual courier ledger adjustments and penalties, courier payout
 * authorisation, tenant activation, integration-failure resolution, and
 * loyalty balance adjustments.
 *
 * Deliberately a small, named map rather than the general code-to-sentence
 * dictionary `activity-log-page.ts` declines to build (its own doc explains
 * why: "a full code-to-sentence dictionary spans every module's own action
 * codes and is not one screen's translation table to invent"). This map is
 * narrower — it only ever needs to cover what actually reaches
 * `audit.approval_policies.action_code`, a closed, small set this module
 * itself defines — and {@link approvalActionLabel} falls back to the raw
 * code for anything not listed, so a new producer never renders blank.
 */
const ACTION_LABEL_KEYS: Readonly<Record<string, MessageKey>> = {
  'payments.remedy.record': 'staff.approvals.action.paymentsRemedyRecord',
  'payments.remedy.future-discount': 'staff.approvals.action.paymentsRemedyFutureDiscount',
  'courier.adjustment.create': 'staff.approvals.action.courierAdjustmentCreate',
  'courier.adjustment.create.manual-penalty': 'staff.approvals.action.courierManualPenalty',
  'courier.payout.authorise': 'staff.approvals.action.courierPayoutAuthorise',
  'tenant.activate': 'staff.approvals.action.tenantActivate',
  'integration.failure.resolve': 'staff.approvals.action.integrationFailureResolve',
  'loyalty.balance.adjust': 'staff.approvals.action.loyaltyBalanceAdjust',
};

/** The label key for a known action code, or `null` for one this map does not (yet) name. */
export function approvalActionLabelKey(actionCode: string): MessageKey | null {
  return ACTION_LABEL_KEYS[actionCode] ?? null;
}

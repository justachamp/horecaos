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
  /** Staff 9.4: `ApprovalAction.CUSTOMER_PII_EXPORT` — a filtered customer export above the tenant's own row threshold. */
  'customer.pii.export': 'staff.approvals.action.customerPiiExport',
  /** ADR 0140: a promotion's activation above the configured thresholds, or any markup. */
  'pricing.promotion.activate': 'staff.approvals.action.pricingPromotionActivate',
  /** ADR 0166: a correction or a void of an issued fiscal receipt, asked of the tenant's own fiscal agent. */
  'fiscal.correction.request': 'staff.approvals.action.fiscalCorrectionRequest',
  /** ADR 0166: attaching the reference the agent returned, which becomes a linked document the customer is shown. */
  'fiscal.correction.attach': 'staff.approvals.action.fiscalCorrectionAttach',
  /** ADR 0029: placing a legal hold on a customer, which refuses her erasure and suspends her data's expiry. */
  'customer.legal-hold.place': 'staff.approvals.action.customerLegalHoldPlace',
  /** ADR 0029: lifting that hold, which releases her to the erasure waiting behind it. */
  'customer.legal-hold.lift': 'staff.approvals.action.customerLegalHoldLift',
  /** ADR 0039 §3.11: a decrease to a placed order above the configured amount, the guard on an unreviewed refund path. */
  'ordering.amendment.decrease': 'staff.approvals.action.orderingAmendmentDecrease',
  /** ADR 0170: moving the tenant's multi-order run pay to a new rule. */
  'courier.run-pay.change': 'staff.approvals.action.courierRunPayChange',
  /** ADR 0043: moving the business-day boundary re-files every recorded fact until a recut catches up. */
  'reporting.business-day-boundary.change':
    'staff.approvals.action.reportingBusinessDayBoundaryChange',
  /** ADR 0025: granting or revoking a PLATFORM-scope role. */
  'iam.platform-grant.manage': 'staff.approvals.action.iamPlatformGrantManage',
};

/**
 * Every code of {@link ApprovalAction} that is listed on a tenant's worklist, with the label it is shown under.
 * The platform's `ApprovalActionLabelMirrorTests` compares this list with the enum, so a producer added to the
 * registry without a label here fails the build instead of reaching a checker as a dotted code (row 9.4, ADR 0181).
 */
export const LABELLED_APPROVAL_ACTIONS: readonly string[] = Object.keys(ACTION_LABEL_KEYS);

/** The label key for a known action code, or `null` for one this map does not (yet) name. */
export function approvalActionLabelKey(actionCode: string): MessageKey | null {
  return ACTION_LABEL_KEYS[actionCode] ?? null;
}

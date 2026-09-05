import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';
import { Page } from '../../core/api/page';
import { staffPaths } from '../../core/api/staff-paths';

/**
 * Mirrors `ApprovalRequestController.PendingApprovalResponse` exactly.
 *
 * The maker's free-text `reason` is deliberately absent from this shape: the
 * server never returns it on the list (ADR 0029 — unclassified prose about a
 * named customer), and the action that raised the request already holds the
 * detail behind its own capability.
 */
export interface PendingApproval {
  readonly id: string;
  readonly actionCode: string;
  readonly parametersHash: string;
  readonly scopeType: 'PLATFORM' | 'TENANT' | 'BRAND' | 'LOCATION';
  readonly scopeId: string | null;
  readonly thresholdDescription: string;
  readonly policyVersion: number;
  readonly requiredApproverCapability: string;
  readonly requestedBy: string;
  readonly requestedAt: string;
  readonly expiresAt: string;
  /** False for the caller's own requests, whatever they hold — never a button that would 403. */
  readonly mayDecide: boolean;
}

export type ApprovalDecision = 'APPROVE' | 'DECLINE';

/** Mirrors `ApprovalRequestController.DecisionResponse`. */
export interface DecidedApproval {
  readonly id: string;
  readonly actionCode: string;
  readonly status: 'APPROVED' | 'DECLINED';
  readonly decidedBy: string;
  readonly decidedAt: string;
}

/**
 * Staff 9.4 Approvals — the maker-checker worklist over `audit.approval_requests`
 * (ADR 0027), reused as-is rather than reinvented: every producer across the
 * platform (refunds and future-order discounts above threshold, manual
 * courier ledger adjustments and penalties, courier payout authorisation,
 * tenant activation, integration-failure resolution, loyalty balance
 * adjustments) raises a row here, and this is the one place a manager sees
 * everything waiting on them and signs it.
 *
 * `ApprovalRequestController.operationsPending`/`.operationsDecide` (wave 45)
 * mirror the pre-existing control-plane routes onto the operations surface —
 * see `staff-paths.ts`'s own doc for why a second mapping was needed rather
 * than calling the control-plane path from this app.
 */
@Injectable({ providedIn: 'root' })
export class ApprovalsApi {
  private readonly api = inject(ApiClient);

  async pending(tenantId: string, actionCode?: string): Promise<readonly PendingApproval[]> {
    const page = await firstValueFrom(
      this.api.get<Page<PendingApproval>>(staffPaths.approvalRequests(tenantId), {
        params: { actionCode, limit: 200 },
      }),
    );
    return page.value.items;
  }

  /**
   * Approve or decline one request. `reason` is required and travels to the
   * audit trail — `ApprovalDecisionService.decide`'s own guard refuses a
   * blank one before anything else is checked.
   */
  async decide(
    tenantId: string,
    requestId: string,
    decision: ApprovalDecision,
    reason: string,
  ): Promise<DecidedApproval> {
    return firstValueFrom(
      this.api.post<{ decision: ApprovalDecision; reason: string }, DecidedApproval>(
        staffPaths.approvalDecision(tenantId, requestId),
        command({ decision, reason }),
      ),
    );
  }
}

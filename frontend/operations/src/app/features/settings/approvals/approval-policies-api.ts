import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { command } from '../../../core/api/idempotency';
import { settingsPaths } from '../../../core/api/settings-paths';

export type PolicyScopeType = 'TENANT' | 'BRAND' | 'LOCATION';

/** What an action does when no valid policy resolves (`ApprovalAction.MissingPolicyMode`). */
export type MissingPolicyMode = 'ALLOW_WITHOUT_APPROVAL' | 'REQUIRE_CONFIGURED_POLICY';

/** Mirrors `ApprovalPolicyController.PolicyResponse`: one version of one policy. */
export interface PolicyVersion {
  readonly id: string;
  readonly actionCode: string;
  readonly scopeType: PolicyScopeType;
  readonly brandId: string | null;
  readonly locationId: string | null;
  readonly legacyScopeWide: boolean;
  /** The sentence the approver reads on the request, frozen onto it. */
  readonly thresholdDescription: string;
  readonly requiredApproverCapability: string;
  readonly validFrom: string;
  readonly validUntil: string | null;
  readonly version: number;
  readonly authoredBy: string;
  readonly createdAt: string;
  /** Whether this version is the one an action resolving right now would find. */
  readonly governsNow: boolean;
}

/** Mirrors `ApprovalPolicyController.PolicyCoverageResponse`. */
export interface PolicyCoverage {
  readonly actionCode: string;
  readonly missingPolicyMode: MissingPolicyMode;
  /** `TENANT` for an action the tenant's approvers decide; `PLATFORM` for one HorecaOS decides about a tenant. */
  readonly worklist: 'TENANT' | 'PLATFORM';
  readonly configuredAnywhere: boolean;
  readonly configuredScopes: readonly PolicyVersion[];
}

export interface AuthorPolicyInput {
  readonly actionCode: string;
  readonly scopeType: PolicyScopeType;
  readonly brandId: string | null;
  readonly locationId: string | null;
  readonly thresholdDescription: string;
  readonly requiredApproverCapability: string;
  /** ISO instant, or null to take effect now. Never in the past: the server refuses a backdated control. */
  readonly validFrom: string | null;
  readonly reason: string;
}

/**
 * Who signs (ADR 0050, ADR 0027, ADR 0181): the tenant's approval policies, over the operations twin of the
 * control-plane routes. The owner alone holds `approval.policy.manage` and reads and writes all three; a
 * published version is never edited, only superseded or end-dated.
 */
@Injectable({ providedIn: 'root' })
export class ApprovalPoliciesApi {
  private readonly api = inject(ApiClient);

  async coverage(tenantId: string): Promise<readonly PolicyCoverage[]> {
    const result = await firstValueFrom(
      this.api.get<readonly PolicyCoverage[]>(settingsPaths.approvalPolicyCoverage(tenantId)),
    );
    return result.value ?? [];
  }

  author(tenantId: string, input: AuthorPolicyInput): Promise<PolicyVersion> {
    return firstValueFrom(
      this.api.post<AuthorPolicyInput, PolicyVersion>(
        settingsPaths.approvalPolicies(tenantId),
        command(input),
      ),
    );
  }

  endDate(tenantId: string, policyId: string, reason: string): Promise<PolicyVersion> {
    const body = { effectiveAt: null, reason };
    return firstValueFrom(
      this.api.post<typeof body, PolicyVersion>(
        settingsPaths.approvalPolicyExpiry(tenantId, policyId),
        command(body),
      ),
    );
  }
}

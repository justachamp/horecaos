import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { BrandScope } from '../../../core/api/catalog-paths';
import { command } from '../../../core/api/idempotency';
import { referralPaths } from '../../../core/api/referral-paths';

/** Mirrors `ReferralPolicyController.ProgramResponse`. */
export interface ReferralProgramView {
  readonly id: string;
  readonly rewardShape: 'BOTH_SIDES' | 'REFERRER_ONLY';
  readonly referrerRewardMinor: number;
  readonly refereeRewardMinor: number;
  readonly rewardCurrency: string;
  readonly maxRewardedReferralsPerReferrer: number | null;
  readonly redemptionWindowDays: number;
  readonly rewardLotLifetimeDays: number;
  readonly status: 'DRAFT' | 'ACTIVE' | 'RETIRED';
  readonly version: number;
  readonly validFrom: string;
  readonly validUntil: string | null;
}

/** Mirrors `ReferralPolicyController.DraftProgramRequest`. */
export interface DraftReferralProgramRequest {
  readonly rewardShape: 'BOTH_SIDES' | 'REFERRER_ONLY';
  readonly referrerRewardMinor: number;
  readonly refereeRewardMinor: number;
  readonly rewardCurrency: string;
  readonly maxRewardedReferralsPerReferrer?: number | null;
  readonly redemptionWindowDays: number;
  readonly rewardLotLifetimeDays: number;
  readonly validFrom?: string | null;
  readonly validUntil?: string | null;
}

/** Mirrors `ReferralOperationsController.SummaryResponse`. */
export interface ReferralSummaryView {
  readonly codesIssued: number;
  readonly pendingRedemptions: number;
  readonly rewardedRedemptions: number;
  readonly closedRedemptions: number;
  readonly pointsPaidOutMinor: number;
}

/** Mirrors `ReferralOperationsController.RedemptionResponse`. */
export interface ReferralRedemptionView {
  readonly id: string;
  readonly referrerCustomerAccountId: string;
  readonly refereeCustomerAccountId: string;
  readonly status: 'PENDING' | 'REWARDED' | 'EXPIRED' | 'VOIDED';
  readonly redeemedAt: string;
  readonly expiresAt: string;
  readonly qualifyingOrderId: string | null;
  readonly rewardedAt: string | null;
  readonly referrerRewardMinor: number;
  readonly refereeRewardMinor: number;
  readonly referrerPaid: boolean;
  readonly refereePaid: boolean;
  readonly referrerSkipReason: string | null;
}

/**
 * A brand's own referral program, and the redemptions it has actually
 * produced (operations §6.6 Referrals, a new ADR riding on ADR 0046).
 *
 * Draft, then activate — never one call, the identical shape `LoyaltyApi`
 * already gives a brand's accrual rate and redemption cap. A drafted program
 * rewards nobody until a separate {@link activateProgram} call promotes it,
 * and activation retires whichever program currently holds the brand in the
 * same transaction.
 */
@Injectable({ providedIn: 'root' })
export class ReferralsApi {
  private readonly api = inject(ApiClient);

  async listPrograms(scope: BrandScope): Promise<readonly ReferralProgramView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly ReferralProgramView[]>(referralPaths.programs(scope)),
    );
    return result.value ?? [];
  }

  async draftProgram(
    scope: BrandScope,
    request: DraftReferralProgramRequest,
  ): Promise<ReferralProgramView> {
    return firstValueFrom(
      this.api.post<DraftReferralProgramRequest, ReferralProgramView>(
        referralPaths.programs(scope),
        command(request),
      ),
    );
  }

  async activateProgram(scope: BrandScope, programId: string): Promise<void> {
    await firstValueFrom(
      this.api.post<null, void>(referralPaths.programActivation(scope, programId), command(null)),
    );
  }

  async retireProgram(scope: BrandScope, programId: string): Promise<void> {
    await firstValueFrom(
      this.api.post<null, void>(referralPaths.programRetirement(scope, programId), command(null)),
    );
  }

  async summary(scope: BrandScope): Promise<ReferralSummaryView | null> {
    const result = await firstValueFrom(
      this.api.get<ReferralSummaryView>(referralPaths.summary(scope)),
    );
    return result.value ?? null;
  }

  async redemptions(scope: BrandScope): Promise<readonly ReferralRedemptionView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly ReferralRedemptionView[]>(referralPaths.redemptions(scope)),
    );
    return result.value ?? [];
  }
}

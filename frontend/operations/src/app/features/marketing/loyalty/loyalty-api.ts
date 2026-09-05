import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { BrandScope } from '../../../core/api/catalog-paths';
import { Money } from '../../../core/format/money';
import { command } from '../../../core/api/idempotency';
import { loyaltyPolicyPaths } from '../../../core/api/loyalty-paths';
import { operationsPaths } from '../../../core/api/operations-paths';

/** Mirrors `LoyaltyOperationsController.LiabilityResponse`. Never pooled across brands. */
export interface LoyaltyLiabilityView {
  readonly brandId: string;
  readonly outstanding: Money;
  readonly held: Money;
  readonly accountCount: number;
}

/** Mirrors `LoyaltyPolicyController.AccrualRuleResponse`. */
export interface AccrualRuleView {
  readonly id: string;
  readonly scopeType: 'BRAND' | 'LOCATION' | 'CHANNEL';
  readonly scopeId: string | null;
  readonly rateBasisPoints: number;
  readonly maxAccrualMinor: number | null;
  readonly earnDelayHours: number;
  readonly lotLifetimeDays: number;
  readonly expiryWarningDays: number;
  readonly status: 'DRAFT' | 'ACTIVE' | 'RETIRED';
  readonly version: number;
  readonly validFrom: string;
  readonly validUntil: string | null;
}

/** Mirrors `LoyaltyPolicyController.DraftAccrualRuleRequest`. */
export interface DraftAccrualRuleRequest {
  readonly scopeType: 'BRAND' | 'LOCATION' | 'CHANNEL';
  readonly scopeId?: string | null;
  readonly rateBasisPoints: number;
  readonly maxAccrualMinor?: number | null;
  readonly earnDelayHours: number;
  readonly lotLifetimeDays: number;
  readonly expiryWarningDays: number;
  readonly validFrom?: string | null;
  readonly validUntil?: string | null;
}

/** Mirrors `LoyaltyPolicyController.RedemptionPolicyResponse`. */
export interface RedemptionPolicyView {
  readonly id: string;
  readonly maxShareBasisPoints: number;
  readonly minOrderMinor: number;
  readonly excludesDeliveryFee: boolean;
  readonly allowedChannels: readonly string[];
  readonly status: 'DRAFT' | 'ACTIVE' | 'RETIRED';
  readonly version: number;
  readonly validFrom: string;
  readonly validUntil: string | null;
}

/** Mirrors `LoyaltyPolicyController.DraftRedemptionPolicyRequest`. */
export interface DraftRedemptionPolicyRequest {
  readonly maxShareBasisPoints: number;
  readonly minOrderMinor: number;
  readonly excludesDeliveryFee: boolean;
  readonly allowedChannels?: readonly string[] | null;
  readonly validFrom?: string | null;
  readonly validUntil?: string | null;
}

/**
 * A brand's own accrual rate and redemption cap (operations §6.3 Loyalty,
 * `LoyaltyPolicyController`, ADR 0046).
 *
 * Draft, then activate — never one call. A drafted row does not accrue or
 * redeem anything; {@link activateAccrualRule}/{@link activateRedemptionPolicy}
 * is the separate, explicit act that puts it in force, and it retires
 * whichever row currently holds the same scope in the same transaction.
 */
@Injectable({ providedIn: 'root' })
export class LoyaltyApi {
  private readonly api = inject(ApiClient);

  /**
   * Outstanding points per brand, for context beside this brand's own policy
   * form. `operationsPaths.loyaltyLiability` takes a `LocationScope` for a
   * tenant-scoped report that never reads `locationId` — the same padding
   * `marketing-api.ts` would need if it read a `LocationScope`-typed path
   * from a brand-only screen, spelled out here because this is the first
   * caller to actually do it.
   */
  async liability(scope: BrandScope): Promise<readonly LoyaltyLiabilityView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly LoyaltyLiabilityView[]>(
        operationsPaths.loyaltyLiability({ ...scope, locationId: '' }),
      ),
    );
    return result.value ?? [];
  }

  // ------------------------------------------------------------- accrual

  async listAccrualRules(scope: BrandScope): Promise<readonly AccrualRuleView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly AccrualRuleView[]>(loyaltyPolicyPaths.accrualRules(scope)),
    );
    return result.value ?? [];
  }

  async draftAccrualRule(
    scope: BrandScope,
    request: DraftAccrualRuleRequest,
  ): Promise<AccrualRuleView> {
    return firstValueFrom(
      this.api.post<DraftAccrualRuleRequest, AccrualRuleView>(
        loyaltyPolicyPaths.accrualRules(scope),
        command(request),
      ),
    );
  }

  async activateAccrualRule(scope: BrandScope, ruleId: string): Promise<void> {
    await firstValueFrom(
      this.api.post<null, void>(
        loyaltyPolicyPaths.accrualRuleActivation(scope, ruleId),
        command(null),
      ),
    );
  }

  async retireAccrualRule(scope: BrandScope, ruleId: string): Promise<void> {
    await firstValueFrom(
      this.api.post<null, void>(
        loyaltyPolicyPaths.accrualRuleRetirement(scope, ruleId),
        command(null),
      ),
    );
  }

  // --------------------------------------------------------- redemption

  async listRedemptionPolicies(scope: BrandScope): Promise<readonly RedemptionPolicyView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly RedemptionPolicyView[]>(loyaltyPolicyPaths.redemptionPolicies(scope)),
    );
    return result.value ?? [];
  }

  async draftRedemptionPolicy(
    scope: BrandScope,
    request: DraftRedemptionPolicyRequest,
  ): Promise<RedemptionPolicyView> {
    return firstValueFrom(
      this.api.post<DraftRedemptionPolicyRequest, RedemptionPolicyView>(
        loyaltyPolicyPaths.redemptionPolicies(scope),
        command(request),
      ),
    );
  }

  async activateRedemptionPolicy(scope: BrandScope, policyId: string): Promise<void> {
    await firstValueFrom(
      this.api.post<null, void>(
        loyaltyPolicyPaths.redemptionPolicyActivation(scope, policyId),
        command(null),
      ),
    );
  }

  async retireRedemptionPolicy(scope: BrandScope, policyId: string): Promise<void> {
    await firstValueFrom(
      this.api.post<null, void>(
        loyaltyPolicyPaths.redemptionPolicyRetirement(scope, policyId),
        command(null),
      ),
    );
  }
}

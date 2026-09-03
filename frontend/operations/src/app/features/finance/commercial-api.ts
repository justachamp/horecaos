import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { financePaths } from '../../core/api/finance-paths';
import { Money } from '../../core/format/money';

/** Mirrors `CommercialOperationsController.SubscriptionResponse`. */
export interface SubscriptionView {
  readonly subscriptionId: string;
  readonly planVersionId: string;
  readonly planCode: string;
  readonly planVersionNumber: number;
  readonly price: Money;
  readonly billingPeriod: string;
  /** `SubscriptionStatus.java`. */
  readonly status:
    | 'DRAFT'
    | 'TRIALING'
    | 'ACTIVE'
    | 'PAST_DUE'
    | 'SUSPENDED'
    | 'CANCELLATION_SCHEDULED'
    | 'EXPIRED'
    | 'TERMINATED';
  readonly startAt: string;
  readonly trialEndAt: string | null;
  readonly currentPeriodStart: string;
  readonly currentPeriodEnd: string;
  readonly suspensionReason: string | null;
  readonly version: number;
}

/** Mirrors `CommercialOperationsController.ResolvedEntitlement`. */
export interface ResolvedEntitlementView {
  readonly entitlementKey: string;
  readonly limit: number | null;
  readonly enabled: boolean | null;
  readonly declaredMode: string;
  readonly effectiveMode: string;
  readonly resetPeriod: string;
  readonly overageUnitPrice: Money | null;
  readonly source: string;
}

export interface EntitlementSnapshotView {
  readonly tenantId: string;
  readonly subscriptionId: string | null;
  readonly hash: string;
  readonly resolvedAt: string;
  readonly entitlements: readonly ResolvedEntitlementView[];
}

/** Mirrors `CommercialOperationsController.UsageResponse`. */
export interface UsageView {
  readonly entitlementKey: string;
  readonly periodKey: string;
  readonly periodStart: string;
  readonly periodEnd: string;
  readonly measuredQuantity: number;
  readonly adjustedQuantity: number;
  readonly consumedQuantity: number;
  readonly movementCount: number;
  readonly lastEventAt: string | null;
}

/**
 * Finance 8.6 — the merchant's own read of its HorecaOS account (ADR 0021).
 *
 * **What is real.** Plan, term, entitlements and metered usage — the exact
 * reads `CommercialControlPlaneController` already serves platform staff,
 * reachable from this console for the first time via
 * `CommercialOperationsController` (wave 39).
 *
 * **What is not.** Period close, invoice export and the prepaid wallet do not
 * exist yet (ADR 0021's own status line), and the platform-wide plan
 * catalogue an "inline purchase" would browse is a `ScopeType.PLATFORM` read
 * no tenant grant can satisfy — see `finance.md` §0 and this API's
 * server-side doc for why this screen does not pretend otherwise.
 */
@Injectable({ providedIn: 'root' })
export class CommercialApi {
  private readonly api = inject(ApiClient);

  async subscription(tenantId: string): Promise<SubscriptionView> {
    const result = await firstValueFrom(
      this.api.get<SubscriptionView>(financePaths.commercialSubscription(tenantId)),
    );
    return result.value;
  }

  async entitlements(tenantId: string): Promise<EntitlementSnapshotView> {
    const result = await firstValueFrom(
      this.api.get<EntitlementSnapshotView>(financePaths.commercialEntitlements(tenantId)),
    );
    return result.value;
  }

  async usage(tenantId: string): Promise<readonly UsageView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly UsageView[]>(financePaths.commercialUsage(tenantId)),
    );
    return result.value ?? [];
  }
}

import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { Money } from '../../core/api/money';

/** Everything a tenant is entitled to, with where each value came from. */
export interface EntitlementSnapshot {
  readonly tenantId: string;
  readonly subscriptionId: string | null;
  readonly hash: string;
  readonly resolvedAt: string;
  readonly entitlements: readonly ResolvedEntitlement[];
}

export interface ResolvedEntitlement {
  readonly entitlementKey: string;
  readonly limit: number | null;
  readonly enabled: boolean | null;
  readonly declaredMode: string;
  readonly effectiveMode: string;
  readonly resetPeriod: string;
  readonly overageUnitPrice: Money | null;
  readonly source: string;
}

/** A tenant's live subscription, with the statuses the server allows next. */
export interface SubscriptionView {
  readonly subscriptionId: string;
  readonly planVersionId: string;
  readonly status: string;
  readonly startAt: string;
  readonly trialEndAt: string | null;
  readonly currentPeriodStart: string;
  readonly currentPeriodEnd: string;
  readonly suspensionReason: string | null;
  readonly version: number;
  readonly allowedNext: readonly string[];
}

/** One line of a plan version: the limit, the boundary behaviour, the overage rate. */
export interface PlanEntitlementLineView {
  readonly entitlementKey: string;
  readonly limit: number | null;
  readonly enabled: boolean | null;
  readonly enforcementMode: string;
  readonly resetPeriod: string;
  readonly warnThresholdBasisPoints: number | null;
  readonly overageUnitPrice: Money | null;
}

/** A live plan version as the price list shows it. */
export interface PlanVersionView {
  readonly planVersionId: string;
  readonly planCode: string;
  readonly versionNumber: number;
  readonly price: Money;
  readonly billingPeriod: string;
  readonly entitlements: readonly PlanEntitlementLineView[];
}

/** A version in the authoring view: drafts included, with who drafted and who approved. */
export interface PlanVersionDetail {
  readonly planVersionId: string;
  readonly versionNumber: number;
  readonly price: Money;
  readonly billingPeriod: string;
  readonly status: string;
  readonly termsReference: string | null;
  readonly createdBy: string;
  readonly approvedBy: string | null;
  readonly activatedAt: string | null;
  readonly entitlements: readonly PlanEntitlementLineView[];
}

/** A plan and every version of it, newest first. */
export interface PlanDetail {
  readonly planId: string;
  readonly code: string;
  readonly name: string;
  readonly status: string;
  readonly versions: readonly PlanVersionDetail[];
}

/** An entitlement key a plan or override may name: counted (a limit) or a feature (on/off). */
export interface EntitlementKeyView {
  readonly code: string;
  readonly counted: boolean;
  readonly unit: string;
  readonly defaultMode: string;
  readonly resetPeriod: string;
}

/** One entitlement line of a draft, as sent. */
export interface EntitlementLineRequest {
  readonly entitlementKey: string;
  readonly limit?: number;
  readonly enabled?: boolean;
  readonly enforcementMode: string;
  readonly resetPeriod: string;
  readonly overageUnitPriceMinor?: number;
}

export interface DraftVersionRequest {
  readonly currency: string;
  readonly priceMinor: number;
  readonly billingPeriod: string;
  readonly termsReference?: string;
  readonly entitlements: readonly EntitlementLineRequest[];
  readonly reason: string;
}

export interface TransitionRequest {
  readonly status: string;
  readonly expectedVersion: number;
  readonly suspensionReason?: string;
  readonly cancelAt?: string;
  readonly reason: string;
}

/** One metered period, measured and adjusted quantities kept apart. */
export interface UsagePeriodView {
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

/** A cached usage total that disagreed with the ledger when recomputed. */
export interface UsageDivergence {
  readonly entitlementKey: string;
  readonly periodKey: string;
  readonly stored: number | null;
  readonly recomputed: number;
}

export const BILLING_PERIODS = ['MONTHLY', 'QUARTERLY', 'YEARLY', 'NONE'] as const;
export const ENFORCEMENT_MODES = ['METER_ONLY', 'SOFT', 'HARD', 'DISABLED'] as const;

/**
 * Plans, subscriptions, entitlements and usage. Reads that tenants may also
 * see live under `/control-plane`; everything only HorecaOS staff may do,
 * and the authoring reads behind it, live under `/platform-admin/commercial`.
 */
@Injectable({ providedIn: 'root' })
export class CommerceApi {
  private readonly api = inject(ApiClient);

  async getEntitlements(tenantId: string): Promise<EntitlementSnapshot> {
    return firstValueFrom(
      this.api.get<EntitlementSnapshot>(`/api/v1/control-plane/tenants/${tenantId}/entitlements`),
    );
  }

  /** The live subscription, or null when the tenant has none. */
  async getSubscription(tenantId: string): Promise<SubscriptionView | null> {
    try {
      return await firstValueFrom(
        this.api.get<SubscriptionView>(`/api/v1/control-plane/tenants/${tenantId}/subscription`),
      );
    } catch (error) {
      if ((error as { code?: string }).code === 'RESOURCE_NOT_FOUND') {
        return null;
      }
      throw error;
    }
  }

  /** The price list: live plan versions only. */
  async listPlanCatalogue(): Promise<PlanVersionView[]> {
    return firstValueFrom(this.api.get<PlanVersionView[]>('/api/v1/control-plane/plans'));
  }

  /** Every metered period for a tenant. */
  async listUsage(tenantId: string): Promise<UsagePeriodView[]> {
    return firstValueFrom(
      this.api.get<UsagePeriodView[]>(`/api/v1/control-plane/tenants/${tenantId}/usage`),
    );
  }

  // ------------------------------------------------------- platform-admin

  /** Every plan with every version, drafts included. */
  async listPlansWithDrafts(): Promise<PlanDetail[]> {
    return firstValueFrom(this.api.get<PlanDetail[]>('/api/v1/platform-admin/commercial/plans'));
  }

  async entitlementKeys(): Promise<EntitlementKeyView[]> {
    return firstValueFrom(
      this.api.get<EntitlementKeyView[]>('/api/v1/platform-admin/commercial/entitlement-keys'),
    );
  }

  async createPlan(code: string, name: string, reason: string): Promise<{ planId: string }> {
    return firstValueFrom(
      this.api.post<{ planId: string }>('/api/v1/platform-admin/commercial/plans', { code, name, reason }),
    );
  }

  async draftVersion(planId: string, request: DraftVersionRequest): Promise<{ planVersionId: string }> {
    return firstValueFrom(
      this.api.post<{ planVersionId: string }>(
        `/api/v1/platform-admin/commercial/plans/${planId}/versions`,
        request,
      ),
    );
  }

  /** Irreversible, and refused when the approver drafted the version. */
  async activateVersion(planVersionId: string, reason: string): Promise<void> {
    await firstValueFrom(
      this.api.post<void>(`/api/v1/platform-admin/commercial/plan-versions/${planVersionId}/activation`, {
        reason,
      }),
    );
  }

  async startSubscription(
    tenantId: string,
    planVersionId: string,
    reason: string,
    trialDays?: number,
  ): Promise<{ subscriptionId: string }> {
    return firstValueFrom(
      this.api.post<{ subscriptionId: string }>(
        `/api/v1/platform-admin/commercial/tenants/${tenantId}/subscriptions`,
        { planVersionId, trialDays, reason },
      ),
    );
  }

  async transitionSubscription(tenantId: string, request: TransitionRequest): Promise<void> {
    await firstValueFrom(
      this.api.post<void>(
        `/api/v1/platform-admin/commercial/tenants/${tenantId}/subscription-transitions`,
        request,
      ),
    );
  }

  async grantOverride(
    tenantId: string,
    request: {
      readonly entitlementKey: string;
      readonly limit?: number;
      readonly enabled?: boolean;
      readonly validUntil: string;
      readonly approvedBy: string;
      readonly reason: string;
    },
  ): Promise<{ overrideId: string }> {
    return firstValueFrom(
      this.api.post<{ overrideId: string }>(
        `/api/v1/platform-admin/commercial/tenants/${tenantId}/entitlement-overrides`,
        request,
      ),
    );
  }

  async adjustUsage(
    tenantId: string,
    request: {
      readonly entitlementKey: string;
      readonly periodKey: string;
      readonly quantityDelta: number;
      readonly sourceReference?: string;
      readonly approvedBy: string;
      readonly reason: string;
    },
  ): Promise<{ adjustmentId: string }> {
    return firstValueFrom(
      this.api.post<{ adjustmentId: string }>(
        `/api/v1/platform-admin/commercial/tenants/${tenantId}/usage-adjustments`,
        request,
      ),
    );
  }

  /** Recomputes every cached total from the ledger; answers the periods that disagreed. */
  async rebuildUsage(tenantId: string): Promise<UsageDivergence[]> {
    return firstValueFrom(
      this.api.post<UsageDivergence[]>(
        `/api/v1/platform-admin/commercial/tenants/${tenantId}/usage-rebuilds`,
        {},
      ),
    );
  }
}

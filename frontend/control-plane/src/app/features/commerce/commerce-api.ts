import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';

/** CommercialControlPlaneController.EntitlementSnapshotResponse (read side, ADR 0021). */
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
  readonly enabled: boolean;
  readonly declaredMode: string;
  readonly effectiveMode: string;
  readonly resetPeriod: string | null;
  readonly overageUnitPrice: { readonly amountMinor: number; readonly currency: string } | null;
  readonly source: string;
}

export interface SubscriptionView {
  readonly id: string;
  readonly tenantId: string;
  readonly planVersionId: string;
  readonly status: string;
  readonly startedAt: string;
}

export interface UsageView {
  readonly tenantId: string;
  readonly period: string;
  readonly counters: Readonly<Record<string, number>>;
}

export interface PlanView {
  readonly id: string;
  readonly name: string;
  readonly status: string;
}

/**
 * Reads over `CommercialControlPlaneController` (ADR 0021) -- entitlements,
 * subscription, usage, and the activated plan catalogue -- shared by IA 2.2's
 * quick-view panel and IA 5.3's own screen so the two never diverge on the
 * shape of the same object.
 *
 * Writes (subscribe/transition/override/adjust, `CommercialAdminController`)
 * are declared in `entitlements.ts` itself, next to the one screen that
 * performs them, rather than duplicated here.
 */
@Injectable({ providedIn: 'root' })
export class CommerceApi {
  private readonly api = inject(ApiClient);

  async getEntitlements(tenantId: string): Promise<EntitlementSnapshot> {
    return firstValueFrom(
      this.api.get<EntitlementSnapshot>(`/api/v1/control-plane/tenants/${tenantId}/entitlements`),
    );
  }

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

  async getUsage(tenantId: string): Promise<UsageView | null> {
    try {
      return await firstValueFrom(
        this.api.get<UsageView>(`/api/v1/control-plane/tenants/${tenantId}/usage`),
      );
    } catch (error) {
      if ((error as { code?: string }).code === 'RESOURCE_NOT_FOUND') {
        return null;
      }
      throw error;
    }
  }

  async listPlans(): Promise<PlanView[]> {
    return firstValueFrom(this.api.get<PlanView[]>('/api/v1/control-plane/plans'));
  }

  // ------------------------------------------------------- platform-admin writes
  // CommercialAdminController -- every declaration is PLATFORM scope, ADR 0021.

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
}

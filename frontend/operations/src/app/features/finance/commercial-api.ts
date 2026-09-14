import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { financePaths } from '../../core/api/finance-paths';
import { command } from '../../core/api/idempotency';
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

/** One charge on a statement. Mirrors `CommercialStatementController.StatementLineView`. */
export interface StatementLineView {
  readonly lineNumber: number;
  /** `StatementLine.java`'s constants: `PLAN`, `MODULE`, `OVERAGE`, `DEPOSIT`, `EARLY_EXIT`. */
  readonly kind: string;
  readonly referenceCode: string;
  readonly description: string;
  readonly quantity: number;
  readonly unitPrice: Money;
  readonly amount: Money;
}

/**
 * A month of what the tenant owes under its plan and modules, before tax
 * (ADR 0088). Mirrors `CommercialStatementController.StatementView`, read
 * here through `CommercialOperationsController`'s tenant-reachable mirror
 * (Finance 8/X.4).
 */
export interface StatementView {
  readonly statementId: string | null;
  readonly number: string | null;
  readonly periodKey: string;
  readonly periodStart: string;
  readonly periodEnd: string;
  /** `Statement.java`'s constants: `DRAFT`, `ISSUED`, `VOID`. */
  readonly status: string;
  readonly total: Money | null;
  readonly issuedBy: string | null;
  readonly issuedAt: string | null;
  readonly issueReason: string | null;
  readonly voidedBy: string | null;
  readonly voidedAt: string | null;
  readonly voidReason: string | null;
  readonly lines: readonly StatementLineView[];
}

/** Mirrors `CommercialModuleController.ModuleView`, read through the tenant-reachable mirror. */
export interface SellableModuleView {
  readonly moduleId: string;
  readonly code: string;
  readonly name: string;
  readonly description: string | null;
  /** `BillingUnit.java`'s constants: `PER_TENANT`, `PER_BRAND`, `PER_LOCATION`, `PER_UNIT`, `ONE_OFF`. */
  readonly billingUnit: string;
  readonly unitPrice: Money;
  readonly featureKeys: readonly string[];
  readonly status: string;
  readonly createdBy: string;
  readonly approvedBy: string | null;
  readonly activatedAt: string | null;
  readonly retiredAt: string | null;
}

/** Mirrors `CommercialModuleController.TenantModuleView`. */
export interface TenantModuleView {
  readonly tenantModuleId: string;
  readonly moduleId: string;
  readonly moduleCode: string;
  readonly moduleName: string;
  readonly billingUnit: string;
  readonly unitPrice: Money;
  readonly quantity: number | null;
  readonly startedAt: string;
  readonly startedBy: string;
  readonly startReason: string;
  readonly endedAt: string | null;
  readonly endedBy: string | null;
  readonly endReason: string | null;
}

/** Mirrors `ArrearsController.TenantArrearsView` (ADR 0127). */
export interface TenantArrearsView {
  /** `SubscriptionStatus.java`'s constants — same set as `SubscriptionView.status`. */
  readonly status: string;
  readonly planEntitlementsApply: boolean;
  readonly additionsBlocked: boolean;
  readonly allowedNext: readonly string[];
  readonly since: string;
  readonly daysInStatus: number;
  readonly suspensionReason: string | null;
  readonly latestStatement: {
    readonly statementId: string;
    readonly number: string;
    readonly periodKey: string;
    readonly total: Money;
    readonly issuedAt: string;
  } | null;
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
 * **Statements too, as of Finance 8/X.4** — `statements`/`statement`/
 * `statementExport` below. The read was already tenant-scoped
 * (`CommercialStatementController`, `COMMERCIAL_USAGE_READ`, ADR 0088); it
 * had simply never been given a client. `CommercialOperationsController`
 * mirrors the same `StatementService` read at a path this console's own
 * `OpenApiSurface` reaches (ADR 0057), rather than this app calling
 * `/api/v1/control-plane/**` for its own invoices.
 *
 * **The purchasable-module catalogue and arrears state, as of ADR 0127** —
 * `modulesOnSale`/`modulesHeld`/`purchaseModule` and `arrears` below. The
 * catalogue was a `ScopeType.PLATFORM` read no tenant grant could satisfy;
 * `CommercialOperationsController` now serves a tenant-scoped mirror under
 * the new `COMMERCIAL_MODULE_READ` capability, and `purchaseModule` gives the
 * calling tenant one of those modules under `COMMERCIAL_SUBSCRIPTION_MANAGE`
 * — the same capability `TENANT_OWNER`/`TENANT_FINANCE` already hold for
 * refund execution. `arrears` is the tenant-reachable, single-row mirror of
 * the platform's cross-tenant arrears board, under the new
 * `COMMERCIAL_ARREARS_READ` capability.
 *
 * **What is still not.** Period close is HorecaOS-staff work — ADR 0088
 * decided a month is closed by issuing its statement, deliberately manual
 * until tax and invoicing are approved — so there is nothing for this screen
 * to add for it. The prepaid wallet stays blocked on ADR 0095.
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

  /** Newest month first, void ones included. */
  async statements(tenantId: string): Promise<readonly StatementView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly StatementView[]>(financePaths.commercialStatements(tenantId)),
    );
    return result.value ?? [];
  }

  /** One issued statement with its lines. */
  async statement(tenantId: string, statementId: string): Promise<StatementView> {
    const result = await firstValueFrom(
      this.api.get<StatementView>(financePaths.commercialStatement(tenantId, statementId)),
    );
    return result.value;
  }

  /** The statement as CSV — for the accounting system an invoice is made in. */
  async statementExport(tenantId: string, statementId: string): Promise<string> {
    return firstValueFrom(
      this.api.text(financePaths.commercialStatementExport(tenantId, statementId)),
    );
  }

  /** Modules HorecaOS sells that this tenant could add. Activated and not retired. */
  async modulesOnSale(tenantId: string): Promise<readonly SellableModuleView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly SellableModuleView[]>(financePaths.commercialModules(tenantId)),
    );
    return result.value ?? [];
  }

  /** Every module this tenant has had, live ones first. */
  async modulesHeld(tenantId: string): Promise<readonly TenantModuleView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly TenantModuleView[]>(financePaths.commercialModulesHeld(tenantId)),
    );
    return result.value ?? [];
  }

  /**
   * The inline purchase: adds an on-sale module to this tenant. `quantity` is
   * required exactly when the module is billed `PER_UNIT`; every other
   * billing unit takes none. Billed on the next statement (ADR 0088) — this
   * does not move money by itself.
   */
  async purchaseModule(
    tenantId: string,
    moduleId: string,
    quantity: number | null,
  ): Promise<{ tenantModuleId: string }> {
    return firstValueFrom(
      this.api.post(financePaths.commercialModules(tenantId), command({ moduleId, quantity })),
    );
  }

  /**
   * This tenant's own place in the arrears lifecycle: where the subscription
   * is right now, how long it has been there, and what that stage restricts.
   */
  async arrears(tenantId: string): Promise<TenantArrearsView> {
    const result = await firstValueFrom(
      this.api.get<TenantArrearsView>(financePaths.commercialArrears(tenantId)),
    );
    return result.value;
  }
}

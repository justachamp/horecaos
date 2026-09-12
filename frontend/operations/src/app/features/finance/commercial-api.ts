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
 * **What is not.** Period close and the prepaid wallet do not exist yet
 * (ADR 0021's own status line), and the platform-wide plan catalogue an
 * "inline purchase" would browse is a `ScopeType.PLATFORM` read no tenant
 * grant can satisfy — see `finance.md` §0 and this API's server-side doc for
 * why this screen does not pretend otherwise.
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
}

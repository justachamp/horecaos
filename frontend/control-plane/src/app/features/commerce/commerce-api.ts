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
  readonly termMonths: number;
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

/** What a plan version sells beside its price: a trial, a deposit, discounts for longer terms. */
export interface PlanTermsView {
  readonly trialDays: number | null;
  readonly activationDeposit: Money;
  readonly termDiscounts: readonly { termMonths: number; discountBasisPoints: number }[];
}

/** A live plan version as the price list shows it. */
export interface PlanVersionView {
  readonly planVersionId: string;
  readonly planCode: string;
  readonly versionNumber: number;
  readonly price: Money;
  readonly billingPeriod: string;
  readonly entitlements: readonly PlanEntitlementLineView[];
  readonly terms: PlanTermsView;
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
  readonly terms: PlanTermsView;
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
  readonly trialDays?: number;
  readonly activationDepositMinor?: number;
  readonly termDiscounts?: readonly { termMonths: number; discountBasisPoints: number }[];
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

/** A module sold beside the plans, billed on its own unit. */
export interface ModuleView {
  readonly moduleId: string;
  readonly code: string;
  readonly name: string;
  readonly description: string | null;
  readonly billingUnit: string;
  readonly unitPrice: Money;
  readonly featureKeys: readonly string[];
  readonly status: 'DRAFT' | 'ACTIVE' | 'RETIRED' | (string & {});
  readonly createdBy: string;
  readonly approvedBy: string | null;
  readonly activatedAt: string | null;
  readonly retiredAt: string | null;
}

/** One module one tenant has had. */
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

export interface DraftModuleRequest {
  readonly code: string;
  readonly name: string;
  readonly description?: string;
  readonly billingUnit: string;
  readonly currency: string;
  readonly unitPriceMinor: number;
  readonly featureKeys: readonly string[];
  readonly reason: string;
}

/** One charge on a statement. */
export interface StatementLineView {
  readonly lineNumber: number;
  readonly kind: 'PLAN' | 'MODULE' | 'OVERAGE' | 'DEPOSIT' | 'EARLY_EXIT' | (string & {});
  readonly referenceCode: string;
  readonly description: string;
  readonly quantity: number;
  readonly unitPrice: Money;
  readonly amount: Money;
}

/** A month of what a tenant owes, drafted or issued. */
export interface StatementView {
  readonly statementId: string | null;
  readonly number: string | null;
  readonly periodKey: string;
  readonly periodStart: string;
  readonly periodEnd: string;
  readonly status: 'DRAFT' | 'ISSUED' | 'VOID' | (string & {});
  readonly total: Money | null;
  readonly issuedBy: string | null;
  readonly issuedAt: string | null;
  readonly issueReason: string | null;
  readonly voidedBy: string | null;
  readonly voidedAt: string | null;
  readonly voidReason: string | null;
  readonly lines: readonly StatementLineView[];
}

/** A tenant's two balances and how it is collected (ADR 0095). */
export interface WalletOverviewView {
  readonly paidBalance: Money;
  readonly bonusBalance: Money;
  readonly paymentMethod: 'INVOICE' | 'WALLET' | 'CARD' | (string & {});
  readonly cardTokenReference: string | null;
}

/** One entry of the append-only ledger. Nothing ever edits or deletes one. */
export interface WalletEntryView {
  readonly entryId: string;
  readonly moneyKind: 'PAID' | 'BONUS' | (string & {});
  readonly entryType:
    | 'TOP_UP'
    | 'DEPOSIT'
    | 'BONUS_GRANT'
    | 'BONUS_EXPIRY'
    | 'STATEMENT_PAYMENT'
    | 'STATEMENT_REVERSAL'
    | 'ADJUSTMENT'
    | 'REFUND'
    | (string & {});
  readonly amount: Money;
  readonly statementId: string | null;
  readonly grantId: string | null;
  readonly expiresAt: string | null;
  readonly externalReference: string | null;
  readonly reason: string;
  readonly recordedBy: string;
  readonly approvedBy: string | null;
  readonly createdAt: string;
}

/** A live bonus grant with what is left of it, earliest expiry first. */
export interface BonusGrantView {
  readonly grantId: string;
  readonly granted: Money;
  readonly remaining: Money;
  readonly expiresAt: string;
  readonly reason: string;
}

/** One issued statement's paid and due amounts, derived from the ledger. */
export interface StatementPaymentView {
  readonly statementId: string;
  readonly number: string;
  readonly periodKey: string;
  readonly total: Money;
  readonly paid: Money;
  readonly due: Money;
}

/** What a proposed wallet change did: applied, waiting for a second signature, or declined. */
export interface WalletChangeResponse {
  readonly status: 'CHANGED' | 'AWAITING_APPROVAL' | 'DECLINED' | (string & {});
  readonly approvalRequestId: string | null;
}

export const PAYMENT_METHODS = ['INVOICE', 'WALLET', 'CARD'] as const;

/** What one arrears stage does to a tenant. */
export interface ArrearsStageView {
  readonly status: string;
  readonly planEntitlementsApply: boolean;
  readonly additionsBlocked: boolean;
  readonly allowedNext: readonly string[];
}

/** One tenant in arrears. */
export interface ArrearView {
  readonly tenantId: string;
  readonly tenantName: string;
  readonly subscriptionId: string;
  readonly status: string;
  readonly since: string;
  readonly daysInStatus: number;
  readonly planCode: string;
  readonly planVersionNumber: number;
  readonly version: number;
  readonly allowedNext: readonly string[];
  readonly suspensionReason: string | null;
  readonly latestStatement: {
    readonly statementId: string;
    readonly number: string;
    readonly periodKey: string;
    readonly total: Money;
    readonly issuedAt: string;
  } | null;
}

export interface ArrearsBoardView {
  readonly stages: readonly ArrearsStageView[];
  readonly subscriptions: readonly ArrearView[];
}

export const BILLING_UNITS = ['PER_TENANT', 'PER_BRAND', 'PER_LOCATION', 'PER_UNIT', 'ONE_OFF'] as const;

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
    termMonths?: number,
  ): Promise<{ subscriptionId: string }> {
    return firstValueFrom(
      this.api.post<{ subscriptionId: string }>(
        `/api/v1/platform-admin/commercial/tenants/${tenantId}/subscriptions`,
        { planVersionId, trialDays, termMonths, reason },
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

  // ------------------------------------------------------------- modules

  /** Every module, drafts and retired ones included (the authoring view). */
  async listModules(): Promise<ModuleView[]> {
    return firstValueFrom(this.api.get<ModuleView[]>('/api/v1/platform-admin/commercial/modules'));
  }

  async draftModule(request: DraftModuleRequest): Promise<{ moduleId: string }> {
    return firstValueFrom(this.api.post<{ moduleId: string }>('/api/v1/platform-admin/commercial/modules', request));
  }

  /** Irreversible, and refused when the approver drafted the module. */
  async activateModule(moduleId: string, reason: string): Promise<void> {
    await firstValueFrom(
      this.api.post<void>(`/api/v1/platform-admin/commercial/modules/${moduleId}/activation`, { reason }),
    );
  }

  async retireModule(moduleId: string, reason: string): Promise<void> {
    await firstValueFrom(
      this.api.post<void>(`/api/v1/platform-admin/commercial/modules/${moduleId}/retirement`, { reason }),
    );
  }

  async tenantModules(tenantId: string): Promise<TenantModuleView[]> {
    return firstValueFrom(
      this.api.get<TenantModuleView[]>(`/api/v1/control-plane/tenants/${tenantId}/modules`),
    );
  }

  async addTenantModule(
    tenantId: string,
    moduleId: string,
    reason: string,
    quantity?: number,
  ): Promise<{ tenantModuleId: string }> {
    return firstValueFrom(
      this.api.post<{ tenantModuleId: string }>(
        `/api/v1/platform-admin/commercial/tenants/${tenantId}/modules`,
        { moduleId, quantity, reason },
      ),
    );
  }

  async endTenantModule(tenantId: string, tenantModuleId: string, reason: string): Promise<void> {
    await firstValueFrom(
      this.api.post<void>(
        `/api/v1/platform-admin/commercial/tenants/${tenantId}/modules/${tenantModuleId}/end`,
        { reason },
      ),
    );
  }

  // ---------------------------------------------------------- statements

  async listStatements(tenantId: string): Promise<StatementView[]> {
    return firstValueFrom(
      this.api.get<StatementView[]>(`/api/v1/control-plane/tenants/${tenantId}/statements`),
    );
  }

  /** What a month would be billed if it were issued now. */
  async draftStatement(tenantId: string, periodKey: string): Promise<StatementView> {
    return firstValueFrom(
      this.api.get<StatementView>(`/api/v1/control-plane/tenants/${tenantId}/statements/draft`, {
        query: { periodKey },
      }),
    );
  }

  async statement(tenantId: string, statementId: string): Promise<StatementView> {
    return firstValueFrom(
      this.api.get<StatementView>(`/api/v1/control-plane/tenants/${tenantId}/statements/${statementId}`),
    );
  }

  /** The issued statement as CSV text, for the accounting system. */
  async exportStatement(tenantId: string, statementId: string): Promise<string> {
    return firstValueFrom(
      this.api.getText(`/api/v1/control-plane/tenants/${tenantId}/statements/${statementId}/export`),
    );
  }

  async issueStatement(tenantId: string, periodKey: string, reason: string): Promise<{ statementId: string; number: string }> {
    return firstValueFrom(
      this.api.post<{ statementId: string; number: string }>(
        `/api/v1/platform-admin/commercial/tenants/${tenantId}/statements`,
        { periodKey, reason },
      ),
    );
  }

  async voidStatement(tenantId: string, statementId: string, reason: string): Promise<void> {
    await firstValueFrom(
      this.api.post<void>(
        `/api/v1/platform-admin/commercial/tenants/${tenantId}/statements/${statementId}/void`,
        { reason },
      ),
    );
  }

  // -------------------------------------------------------------- wallet

  /** Both balances and how the tenant is collected. */
  async wallet(tenantId: string): Promise<WalletOverviewView> {
    return firstValueFrom(this.api.get<WalletOverviewView>(`/api/v1/control-plane/tenants/${tenantId}/wallet`));
  }

  /** The ledger, newest first. One page is enough for a screen; the cursor is there for more. */
  async walletLedger(tenantId: string, cursor?: string): Promise<{ items: WalletEntryView[]; nextCursor: string | null }> {
    return firstValueFrom(
      this.api.get<{ items: WalletEntryView[]; nextCursor: string | null }>(
        `/api/v1/control-plane/tenants/${tenantId}/wallet/ledger`,
        cursor === undefined ? undefined : { query: { cursor } },
      ),
    );
  }

  /** Live bonus grants with something left, earliest expiry first. */
  async bonusGrants(tenantId: string): Promise<BonusGrantView[]> {
    return firstValueFrom(
      this.api.get<BonusGrantView[]>(`/api/v1/control-plane/tenants/${tenantId}/wallet/grants`),
    );
  }

  /** Each issued statement's paid and due amounts, derived from the ledger. */
  async statementPayments(tenantId: string): Promise<StatementPaymentView[]> {
    return firstValueFrom(
      this.api.get<StatementPaymentView[]>(`/api/v1/control-plane/tenants/${tenantId}/wallet/statements`),
    );
  }

  /** One person's audited act: the bank reference is what proves it. */
  async recordTransfer(
    tenantId: string,
    request: { readonly amountMinor: number; readonly bankReference: string; readonly reason: string },
  ): Promise<{ entryId: string }> {
    return firstValueFrom(
      this.api.post<{ entryId: string }>(
        `/api/v1/platform-admin/commercial/tenants/${tenantId}/wallet/transfers`,
        request,
      ),
    );
  }

  /** Pays exactly what the live subscription's activation deposit still owes. */
  async recordDeposit(
    tenantId: string,
    request: { readonly bankReference: string; readonly reason: string },
  ): Promise<{ entryId: string }> {
    return firstValueFrom(
      this.api.post<{ entryId: string }>(
        `/api/v1/platform-admin/commercial/tenants/${tenantId}/wallet/deposit`,
        request,
      ),
    );
  }

  /** Proposed by one person; the identical call again after a different person approves applies it. */
  async proposeWalletAdjustment(
    tenantId: string,
    request: {
      readonly moneyKind: string;
      readonly grantId?: string;
      readonly amountMinor: number;
      readonly reason: string;
    },
  ): Promise<WalletChangeResponse> {
    return firstValueFrom(
      this.api.post<WalletChangeResponse>(
        `/api/v1/platform-admin/commercial/tenants/${tenantId}/wallet/adjustments`,
        request,
      ),
    );
  }

  async proposeBonusGrant(
    tenantId: string,
    request: { readonly amountMinor: number; readonly expiresAt: string; readonly reason: string },
  ): Promise<WalletChangeResponse> {
    return firstValueFrom(
      this.api.post<WalletChangeResponse>(
        `/api/v1/platform-admin/commercial/tenants/${tenantId}/wallet/bonus-grants`,
        request,
      ),
    );
  }

  async proposeRefund(
    tenantId: string,
    request: { readonly amountMinor: number; readonly payoutReference: string; readonly reason: string },
  ): Promise<WalletChangeResponse> {
    return firstValueFrom(
      this.api.post<WalletChangeResponse>(
        `/api/v1/platform-admin/commercial/tenants/${tenantId}/wallet/refunds`,
        request,
      ),
    );
  }

  async setPaymentMethod(
    tenantId: string,
    request: { readonly paymentMethod: string; readonly cardTokenReference?: string; readonly reason: string },
  ): Promise<void> {
    await firstValueFrom(
      this.api.post<void>(
        `/api/v1/platform-admin/commercial/tenants/${tenantId}/wallet/payment-method`,
        request,
      ),
    );
  }

  // ------------------------------------------------------------- arrears

  async arrears(): Promise<ArrearsBoardView> {
    return firstValueFrom(this.api.get<ArrearsBoardView>('/api/v1/control-plane/arrears'));
  }
}

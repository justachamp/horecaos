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
  /**
   * What this subscription still owes as its activation deposit (ADR 0093),
   * zero once it is paid or when the plan version sold none. Minor units of
   * the plan version's currency, which the subscription does not carry: read
   * the currency from the version this subscription is on.
   */
  readonly activationDepositDueMinor: number;
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

// ------------------------------------------------- e-invoicing (ADR 0096)

/** The operators an issued statement can be sent to. */
export const EINVOICING_OPERATORS = ['DIDOX', 'FAKTURA_UZ'] as const;

/** The line kinds a statement can carry, and so the kinds that have a classification. */
export const EINVOICING_LINE_KINDS = [
  'PLAN',
  'MODULE',
  'OVERAGE',
  'EARLY_EXIT',
  'DEPOSIT',
] as const;

/**
 * HorecaOS's own account with one operator. `connected` is the one fact that
 * matters; `missing` says what is still needed while it is false. The secret
 * reference is never returned: only whether one is bound.
 */
export interface EInvoicingAccountView {
  readonly installationId: string;
  readonly provider: 'DIDOX' | 'FAKTURA_UZ' | (string & {});
  readonly displayName: string;
  readonly status: 'DRAFT' | 'ACTIVE' | 'SUSPENDED' | (string & {});
  readonly connected: boolean;
  readonly secretBound: boolean;
  readonly missing: readonly string[];
  readonly adapterWired: boolean;
  readonly adapterVersion: string;
  readonly environmentCode: string;
  readonly baseUrl: string | null;
  readonly production: boolean;
  readonly config: Readonly<Record<string, string>>;
  readonly version: number;
  readonly updatedBy: string;
  readonly updatedAt: string;
}

/** What one kind of statement line is invoiced as; provisional until finance confirms it. */
export interface EInvoicingClassificationView {
  readonly lineKind: string;
  readonly itemLabel: string;
  readonly catalogCode: string;
  readonly catalogName: string;
  readonly packageCode: string;
  readonly packageName: string;
  readonly vatRateBp: number;
  readonly provisional: boolean;
  readonly confirmedBy: string | null;
  readonly confirmedAt: string | null;
  readonly version: number;
  readonly updatedBy: string;
  readonly updatedAt: string;
}

export interface EInvoiceLineView {
  readonly lineNumber: number;
  readonly name: string;
  readonly classificationCode: string;
  readonly quantity: number;
  readonly unitPrice: Money;
  readonly net: Money;
  readonly vatRateBp: number;
  readonly vat: Money;
  readonly gross: Money;
}

/** One attempt to send a statement to an operator, and what the operator reports of it. */
export interface EInvoiceView {
  readonly einvoiceId: string;
  readonly statementId: string;
  readonly tenantId: string;
  readonly provider: string;
  readonly documentNumber: string;
  readonly documentDate: string;
  readonly buyerLegalEntityId: string;
  readonly buyerName: string;
  readonly buyerTaxpayerNumber: string;
  readonly sellerTaxpayerNumber: string;
  readonly net: Money;
  readonly vat: Money;
  readonly total: Money;
  readonly classificationProvisional: boolean;
  readonly delivery: 'PENDING' | 'SUBMITTED' | 'FAILED' | 'UNCERTAIN' | (string & {});
  readonly failureCode: string | null;
  readonly failureDetail: string | null;
  readonly operatorDocumentId: string | null;
  readonly operatorState: 'DRAFT' | 'SENT' | 'SIGNED' | 'REFUSED' | 'CANCELLED' | 'UNKNOWN' | null;
  readonly operatorStatus: string | null;
  readonly stateCheckedAt: string | null;
  readonly stateChangedAt: string | null;
  readonly live: boolean;
  readonly sendReason: string;
  readonly sentBy: string;
  readonly createdAt: string;
  readonly version: number;
  readonly lines: readonly EInvoiceLineView[] | null;
}

/** The fields an account is saved with: the reference to its login, and the seller's public identity. */
export interface EInvoicingAccountUpdate {
  readonly displayName: string;
  readonly secretReference: string | null;
  readonly config: Readonly<Record<string, string>>;
  readonly reason: string;
}

export interface EInvoicingClassificationUpdate {
  readonly itemLabel: string;
  readonly catalogCode: string;
  readonly catalogName: string;
  readonly packageCode: string;
  readonly packageName: string;
  readonly vatRateBp: number;
  readonly confirmed: boolean;
  readonly reason: string;
}

export interface EInvoiceSendRequest {
  readonly provider: string;
  readonly legalEntityId: string | null;
  readonly reason: string;
}

/** A tenant's two balances and how it is collected (ADR 0095). */
export interface WalletOverviewView {
  readonly paidBalance: Money;
  /**
   * The ledger's sum of every BONUS entry. A grant past its expiry date is
   * still in it until the hourly sweep writes the entry that lapses it, so
   * this can stand briefly above what a statement could draw on.
   */
  readonly bonusBalance: Money;
  /** What the live grants have left between them: the bonus money a statement can actually spend. */
  readonly bonusSpendableBalance: Money;
  readonly paymentMethod: 'INVOICE' | 'WALLET' | 'CARD' | (string & {});
  /**
   * Always null. The field stays so no client breaks, but the reference is the card adapter's alone:
   * for a card the tenant bound itself it carries the merchant installation and the provider's vault
   * token, and no screen or log line may reach it.
   */
  readonly cardTokenReference: string | null;
  /** Whether a card is on file, which is all a card typed in by staff can say about itself. */
  readonly hasCard: boolean;
  /** Which card, as the provider's own form reported it; null when none is on file. */
  readonly card: WalletCardView | null;
}

/** What is safe to say about a card on file: never which reference it is. */
export interface WalletCardView {
  readonly last4: string | null;
  readonly brand: string | null;
  readonly expiryMonth: number | null;
  readonly expiryYear: number | null;
  readonly lapsed: boolean;
  readonly lapsesSoon: boolean;
  readonly boundAt: string | null;
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
    | 'DEPOSIT_REVERSAL'
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

/** `PrepaymentInvoice.java`'s statuses; read from the ledger, never stored. */
export type PrepaymentInvoiceStatus =
  'OPEN' | 'PARTIALLY_PAID' | 'PAID' | 'EXPIRED' | 'CANCELLED' | (string & {});

/** The five things an invoice prints about where to pay. */
export interface PaymentDetailsView {
  readonly configured: boolean;
  readonly beneficiary: string;
  readonly bankName: string;
  readonly account: string;
  readonly mfo: string;
  readonly taxId: string;
}

/**
 * A request for payment of money to be held in a tenant's wallet (ADR 0095),
 * frozen at issue with the bank details of that moment. Before tax, and not a
 * tax invoice.
 */
export interface PrepaymentInvoiceView {
  readonly invoiceId: string;
  readonly number: string;
  readonly status: PrepaymentInvoiceStatus;
  readonly amount: Money;
  readonly paid: Money;
  readonly due: Money;
  readonly validUntil: string;
  readonly issuedAt: string;
  readonly cancelledAt: string | null;
  readonly paymentDetails: PaymentDetailsView;
  readonly paymentPurpose: string;
  readonly beforeTax: boolean;
}

/** One attempt to charge a tenant's card for money to hold in its wallet. */
export interface CardTopUpView {
  readonly topUpId: string;
  readonly amount: Money;
  readonly outcome: 'PENDING' | 'SUCCEEDED' | 'FAILED' | 'NOT_CONFIGURED' | (string & {});
  /** The provider's reason code, on a decline only. */
  readonly reason: string | null;
  readonly walletEntryId: string | null;
  readonly requestedAt: string;
  readonly settledAt: string | null;
}

/** The bank details every invoice carries now, and who put them there. */
export interface BankDetailsView {
  /** False while the row still holds the placeholder; no invoice is issued until it is replaced. */
  readonly configured: boolean;
  readonly beneficiary: string;
  readonly bankName: string;
  readonly account: string;
  readonly mfo: string;
  readonly taxId: string;
  readonly version: number;
  readonly updatedBy: string;
  readonly updatedAt: string;
  readonly approvedBy: string | null;
}

export interface BankDetailsProposal {
  readonly beneficiary: string;
  readonly bankName: string;
  readonly account: string;
  readonly mfo: string;
  readonly taxId: string;
  readonly reason: string;
}

/** `PlatformCardInstallation.java`'s statuses. */
export type CardInstallationStatus = 'DRAFT' | 'ACTIVE' | 'SUSPENDED' | (string & {});

/**
 * HorecaOS's own card merchant account in the shape of an ADR 0026 installation.
 * The secret reference is never returned: `secretConfigured` says whether one is named.
 */
export interface CardInstallationView {
  readonly installationId: string;
  readonly providerType: string;
  readonly environmentCode: string | null;
  readonly displayName: string;
  readonly status: CardInstallationStatus;
  readonly secretConfigured: boolean;
  readonly externalAccountReference: string | null;
  readonly configuration: Readonly<Record<string, unknown>>;
  readonly version: number;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface CardInstallationRequest {
  readonly providerType: string;
  readonly environmentCode?: string;
  readonly displayName: string;
  /** A `provider_payment` secret reference. Never a credential value. */
  readonly secretReference?: string;
  readonly externalAccountReference?: string;
  readonly reason: string;
}

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
  /**
   * What the tenant still owes on issued statements and on how many, or null
   * when it owes nothing.
   */
  readonly owed: { readonly due: Money; readonly openStatements: number } | null;
  /**
   * The activation deposit the tenant still owes beside its statements, which is
   * on none of them, or null when none is due.
   */
  readonly depositDue: Money | null;
  /**
   * True when the tenant owes neither a statement nor its deposit. For a
   * PAST_DUE tenant, whose stage is about money by definition, that is the cue
   * that whoever restores it can now do so. For a SUSPENDED one it is a fact and
   * not a cue: the suspension's reason is free text, so nothing can say whether
   * paying addressed it. Nothing moves a subscription by itself (ADR 0089), so
   * this is only a signal.
   */
  readonly paidInFull: boolean;
}

export interface ArrearsBoardView {
  readonly stages: readonly ArrearsStageView[];
  readonly subscriptions: readonly ArrearView[];
}

export const BILLING_UNITS = [
  'PER_TENANT',
  'PER_BRAND',
  'PER_LOCATION',
  'PER_UNIT',
  'ONE_OFF',
] as const;

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
      this.api.post<{ planId: string }>('/api/v1/platform-admin/commercial/plans', {
        code,
        name,
        reason,
      }),
    );
  }

  async draftVersion(
    planId: string,
    request: DraftVersionRequest,
  ): Promise<{ planVersionId: string }> {
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
      this.api.post<void>(
        `/api/v1/platform-admin/commercial/plan-versions/${planVersionId}/activation`,
        {
          reason,
        },
      ),
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
    return firstValueFrom(
      this.api.post<{ moduleId: string }>('/api/v1/platform-admin/commercial/modules', request),
    );
  }

  /** Irreversible, and refused when the approver drafted the module. */
  async activateModule(moduleId: string, reason: string): Promise<void> {
    await firstValueFrom(
      this.api.post<void>(`/api/v1/platform-admin/commercial/modules/${moduleId}/activation`, {
        reason,
      }),
    );
  }

  async retireModule(moduleId: string, reason: string): Promise<void> {
    await firstValueFrom(
      this.api.post<void>(`/api/v1/platform-admin/commercial/modules/${moduleId}/retirement`, {
        reason,
      }),
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
      this.api.get<StatementView>(
        `/api/v1/control-plane/tenants/${tenantId}/statements/${statementId}`,
      ),
    );
  }

  /** The issued statement as CSV text, for the accounting system. */
  async exportStatement(tenantId: string, statementId: string): Promise<string> {
    return firstValueFrom(
      this.api.getText(
        `/api/v1/control-plane/tenants/${tenantId}/statements/${statementId}/export`,
      ),
    );
  }

  async issueStatement(
    tenantId: string,
    periodKey: string,
    reason: string,
  ): Promise<{ statementId: string; number: string }> {
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
    return firstValueFrom(
      this.api.get<WalletOverviewView>(`/api/v1/control-plane/tenants/${tenantId}/wallet`),
    );
  }

  /** The ledger, newest first. One page is enough for a screen; the cursor is there for more. */
  async walletLedger(
    tenantId: string,
    cursor?: string,
  ): Promise<{ items: WalletEntryView[]; nextCursor: string | null }> {
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
      this.api.get<StatementPaymentView[]>(
        `/api/v1/control-plane/tenants/${tenantId}/wallet/statements`,
      ),
    );
  }

  /** The tenant's prepayment invoices, newest first. */
  async prepaymentInvoices(tenantId: string): Promise<PrepaymentInvoiceView[]> {
    return firstValueFrom(
      this.api.get<PrepaymentInvoiceView[]>(
        `/api/v1/control-plane/tenants/${tenantId}/wallet/invoices`,
      ),
    );
  }

  /** The tenant's recent card top-ups, newest first. */
  async cardTopUps(tenantId: string): Promise<CardTopUpView[]> {
    return firstValueFrom(
      this.api.get<CardTopUpView[]>(`/api/v1/control-plane/tenants/${tenantId}/wallet/top-ups`),
    );
  }

  /** Withdraws an invoice nothing has paid; refused once any money names it. */
  async cancelPrepaymentInvoice(
    tenantId: string,
    invoiceId: string,
    reason: string,
  ): Promise<PrepaymentInvoiceView> {
    return firstValueFrom(
      this.api.post<PrepaymentInvoiceView>(
        `/api/v1/platform-admin/commercial/tenants/${tenantId}/wallet/invoices/${invoiceId}/cancel`,
        { reason },
      ),
    );
  }

  /**
   * One person's audited act: the bank reference is what proves it. Naming the
   * prepayment invoice it pays makes the ledger entry say so, and the invoice's
   * paid figure is then the ledger's own sum.
   */
  async recordTransfer(
    tenantId: string,
    request: {
      readonly amountMinor: number;
      readonly bankReference: string;
      readonly reason: string;
      readonly prepaymentInvoiceNumber?: string;
    },
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
    request: {
      readonly amountMinor: number;
      readonly payoutReference: string;
      readonly reason: string;
    },
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
    request: {
      readonly paymentMethod: string;
      readonly cardTokenReference?: string;
      readonly reason: string;
    },
  ): Promise<void> {
    await firstValueFrom(
      this.api.post<void>(
        `/api/v1/platform-admin/commercial/tenants/${tenantId}/wallet/payment-method`,
        request,
      ),
    );
  }

  // ------------------------------------------------------- billing setup

  /** The bank details every invoice carries now. */
  async bankDetails(): Promise<BankDetailsView> {
    return firstValueFrom(
      this.api.get<BankDetailsView>('/api/v1/control-plane/billing/bank-details'),
    );
  }

  /**
   * Proposed by one person; the identical call again after a different person
   * approves it under Approvals writes the details and spends the signature.
   */
  async proposeBankDetails(request: BankDetailsProposal): Promise<WalletChangeResponse> {
    return firstValueFrom(
      this.api.post<WalletChangeResponse>(
        '/api/v1/platform-admin/commercial/billing/bank-details',
        request,
      ),
    );
  }

  /** HorecaOS's own card merchant accounts. At most one is ACTIVE. */
  async cardInstallations(): Promise<CardInstallationView[]> {
    return firstValueFrom(
      this.api.get<CardInstallationView[]>('/api/v1/control-plane/billing/card-installations'),
    );
  }

  async createCardInstallation(
    request: CardInstallationRequest,
  ): Promise<{ installationId: string }> {
    return firstValueFrom(
      this.api.post<{ installationId: string }>(
        '/api/v1/platform-admin/commercial/billing/card-installations',
        request,
      ),
    );
  }

  /** Refused while another is active, and for a test double outside a local or test run. */
  async activateCardInstallation(
    installationId: string,
    expectedVersion: number,
    reason: string,
  ): Promise<CardInstallationView> {
    return firstValueFrom(
      this.api.post<CardInstallationView>(
        `/api/v1/platform-admin/commercial/billing/card-installations/${installationId}/activation`,
        { expectedVersion, reason },
      ),
    );
  }

  /**
   * Every card tenant is then collected like an invoice tenant until an account is active.
   *
   * Refused with `UNRESOLVED_CARD_CHARGES` while top-ups or statement charges asked through the account
   * have no answer yet: the account that replaces it cannot say whether they took the money.
   * `acknowledgeUnresolvedCharges` suspends anyway, for an account that cannot answer; the charges then
   * stay pending, never declined, and resolve when the account is active again.
   */
  async suspendCardInstallation(
    installationId: string,
    expectedVersion: number,
    reason: string,
    acknowledgeUnresolvedCharges = false,
  ): Promise<CardInstallationView> {
    return firstValueFrom(
      this.api.post<CardInstallationView>(
        `/api/v1/platform-admin/commercial/billing/card-installations/${installationId}/suspension`,
        // Absent, not false: the console's ordinary body stays the version and the reason.
        {
          expectedVersion,
          reason,
          ...(acknowledgeUnresolvedCharges ? { acknowledgeUnresolvedCharges: true } : {}),
        },
      ),
    );
  }

  // ---------------------------------------------------------- e-invoicing

  /** HorecaOS's own accounts with the operators, bound or not. */
  async einvoicingAccounts(): Promise<EInvoicingAccountView[]> {
    return firstValueFrom(
      this.api.get<EInvoicingAccountView[]>('/api/v1/control-plane/einvoicing/installations'),
    );
  }

  async saveEInvoicingAccount(
    installationId: string,
    version: number,
    request: EInvoicingAccountUpdate,
  ): Promise<EInvoicingAccountView> {
    return firstValueFrom(
      this.api.put<EInvoicingAccountView>(
        `/api/v1/platform-admin/commercial/einvoicing/installations/${installationId}`,
        request,
        { expectedVersion: version },
      ),
    );
  }

  async activateEInvoicingAccount(
    installationId: string,
    version: number,
    reason: string,
  ): Promise<EInvoicingAccountView> {
    return firstValueFrom(
      this.api.post<EInvoicingAccountView>(
        `/api/v1/platform-admin/commercial/einvoicing/installations/${installationId}/activation`,
        { reason },
        { expectedVersion: version },
      ),
    );
  }

  async suspendEInvoicingAccount(
    installationId: string,
    version: number,
    reason: string,
  ): Promise<EInvoicingAccountView> {
    return firstValueFrom(
      this.api.post<EInvoicingAccountView>(
        `/api/v1/platform-admin/commercial/einvoicing/installations/${installationId}/suspension`,
        { reason },
        { expectedVersion: version },
      ),
    );
  }

  async einvoicingClassifications(): Promise<EInvoicingClassificationView[]> {
    return firstValueFrom(
      this.api.get<EInvoicingClassificationView[]>(
        '/api/v1/control-plane/einvoicing/line-classifications',
      ),
    );
  }

  async saveEInvoicingClassification(
    lineKind: string,
    version: number,
    request: EInvoicingClassificationUpdate,
  ): Promise<EInvoicingClassificationView> {
    return firstValueFrom(
      this.api.put<EInvoicingClassificationView>(
        `/api/v1/platform-admin/commercial/einvoicing/line-classifications/${lineKind}`,
        request,
        { expectedVersion: version },
      ),
    );
  }

  /** Every attempt to send one of the tenant's statements, newest first. */
  async tenantEInvoices(tenantId: string): Promise<EInvoiceView[]> {
    return firstValueFrom(
      this.api.get<EInvoiceView[]>(`/api/v1/control-plane/tenants/${tenantId}/einvoices`),
    );
  }

  /** One attempt with the document that was sent, line by line. */
  async eInvoice(tenantId: string, einvoiceId: string): Promise<EInvoiceView> {
    return firstValueFrom(
      this.api.get<EInvoiceView>(
        `/api/v1/control-plane/tenants/${tenantId}/einvoices/${einvoiceId}`,
      ),
    );
  }

  async sendEInvoice(
    tenantId: string,
    statementId: string,
    request: EInvoiceSendRequest,
  ): Promise<EInvoiceView> {
    return firstValueFrom(
      this.api.post<EInvoiceView>(
        `/api/v1/platform-admin/commercial/tenants/${tenantId}/statements/${statementId}/einvoices`,
        request,
      ),
    );
  }

  async refreshEInvoice(
    tenantId: string,
    einvoiceId: string,
  ): Promise<{ readonly einvoice: EInvoiceView; readonly unavailableCode: string | null }> {
    return firstValueFrom(
      this.api.post<{ readonly einvoice: EInvoiceView; readonly unavailableCode: string | null }>(
        `/api/v1/platform-admin/commercial/tenants/${tenantId}/einvoices/${einvoiceId}/state-refresh`,
        {},
      ),
    );
  }

  // ------------------------------------------------------------- arrears

  async arrears(): Promise<ArrearsBoardView> {
    return firstValueFrom(this.api.get<ArrearsBoardView>('/api/v1/control-plane/arrears'));
  }
}

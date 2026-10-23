import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { BrandScope } from '../../../core/api/catalog-paths';
import { command } from '../../../core/api/idempotency';
import { loyaltyPolicyPaths } from '../../../core/api/loyalty-paths';

/** The vendor ceiling for keeping a till's bonus balance a mirror of the ledger (ADR 0171 Decision 8). */
export type PosSyncCapabilityState = 'SUPPORTED' | 'PARTIAL' | 'UNSUPPORTED' | 'UNASSESSED';

/** How far a binding is staged: report only, then pull (apply what the till did), then push. */
export type PosSyncMode = 'REPORT_ONLY' | 'PULL' | 'PULL_AND_PUSH';

export type PosSyncDiscrepancyKind =
  | 'TILL_HIGHER'
  | 'TILL_LOWER'
  | 'TILL_ONLY'
  | 'PLATFORM_ONLY'
  | 'AMBIGUOUS_MATCH'
  | 'SPEND_EXCEEDS_BALANCE'
  | 'CREDIT_HELD'
  | 'RATIO_MISMATCH'
  | 'NOT_CONSENTED'
  | 'UNVERIFIABLE';

export type PosSyncResolutionCode = 'ACCEPT_TILL' | 'ACCEPT_PLATFORM' | 'ABSORB' | 'SUSPEND';

/** Mirrors `LoyaltyPosSyncController.RunSummary`. */
export interface PosSyncRunSummary {
  readonly runId: string;
  readonly kind: 'PULL' | 'PUSH' | 'RECONCILE';
  readonly status: 'RUNNING' | 'SUCCEEDED' | 'FAILED';
  readonly startedAt: string;
  readonly finishedAt: string | null;
}

/** Mirrors `LoyaltyPosSyncController.BindingResponse`. */
export interface PosSyncBindingView {
  readonly bindingId: string;
  readonly installationId: string;
  readonly providerType: string;
  readonly locationId: string | null;
  readonly bindingStatus: string;
  /** The `If-Match` of a change to the binding's mode. */
  readonly version: number;
  readonly capabilityState: PosSyncCapabilityState;
  readonly enabled: boolean;
  readonly syncing: boolean;
  readonly mode: PosSyncMode;
  readonly limits: Readonly<Record<string, unknown>>;
  readonly matchedAccounts: number;
  readonly openDiscrepancies: number;
  readonly lastRun: PosSyncRunSummary | null;
}

/** Mirrors `LoyaltyPosSyncController.OverviewResponse`. */
export interface PosSyncOverview {
  readonly tillKeepsBalance: boolean;
  /** The `If-Match` of the statement above; 0 for a brand that has never made it. */
  readonly tillKeepsBalanceVersion: number;
  /** The tenant says the till keeps its own balance and no binding mirrors it: the two can differ. */
  readonly standingNotice: boolean;
  readonly openDiscrepancies: number;
  readonly bindings: readonly PosSyncBindingView[];
}

/** Mirrors `LoyaltyPosSyncController.RunResponse` — one row of the reconciliation report. */
export interface PosSyncRunView {
  readonly runId: string;
  readonly bindingId: string;
  readonly kind: 'PULL' | 'PUSH' | 'RECONCILE';
  readonly status: 'RUNNING' | 'SUCCEEDED' | 'FAILED';
  readonly failureCode: string | null;
  readonly startedAt: string;
  readonly finishedAt: string | null;
  readonly compared: number;
  readonly matched: number;
  readonly tillHigher: number;
  readonly tillLower: number;
  readonly tillOnly: number;
  readonly platformOnly: number;
  readonly ambiguous: number;
  readonly imported: number;
  readonly held: number;
  readonly unverifiable: number;
  readonly pushed: number;
}

/**
 * Mirrors `LoyaltyPosSyncController.DiscrepancyResponse`. Names a points account and two balances: never a name
 * or a phone. `accountId` and `externalCustomerId` are opaque ids.
 */
export interface PosSyncDiscrepancyView {
  readonly discrepancyId: string;
  readonly bindingId: string;
  readonly accountId: string | null;
  readonly externalCustomerId: string | null;
  readonly kind: PosSyncDiscrepancyKind;
  readonly platformBalanceMinor: number | null;
  readonly tillBalanceMinor: number | null;
  readonly deltaMinor: number | null;
  readonly status: 'OPEN' | 'RESOLVED';
  readonly resolutionCode: PosSyncResolutionCode | null;
  readonly resolutionReason: string | null;
  readonly resolvedBy: string | null;
  readonly resolvedAt: string | null;
  readonly observedAt: string;
  readonly version: number;
}

/** Mirrors `LoyaltyPosSyncController.RunReportResponse`. */
export interface PosSyncRunReport {
  readonly run: PosSyncRunView;
  readonly discrepancies: readonly PosSyncDiscrepancyView[];
}

/** Mirrors `LoyaltyPosSyncController.ResolutionResponse`. `approval` is PENDING while a second signature is awaited. */
export interface PosSyncResolutionResult {
  readonly discrepancy: PosSyncDiscrepancyView;
  readonly applied: boolean;
  readonly approval: 'NOT_REQUIRED' | 'PENDING' | 'APPROVED' | 'DECLINED';
  readonly approvalRequestId: string | null;
}

/**
 * The till-balance sync's reads and its two writes (ADR 0171, row 6.3b), over `LoyaltyPosSyncController`.
 *
 * Reads declare `loyalty.read`; resolving a difference and asking for a reconciliation declare
 * `loyalty.sync.manage` at brand scope, because a resolution can credit points.
 */
@Injectable({ providedIn: 'root' })
export class PosSyncApi {
  private readonly api = inject(ApiClient);

  async overview(scope: BrandScope): Promise<PosSyncOverview> {
    return firstValueFrom(this.api.get<PosSyncOverview>(loyaltyPolicyPaths.posSync(scope))).then(
      (result) => result.value,
    );
  }

  async runs(scope: BrandScope): Promise<readonly PosSyncRunView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly PosSyncRunView[]>(loyaltyPolicyPaths.posSyncRuns(scope)),
    );
    return result.value ?? [];
  }

  async run(scope: BrandScope, runId: string): Promise<PosSyncRunReport> {
    const result = await firstValueFrom(
      this.api.get<PosSyncRunReport>(loyaltyPolicyPaths.posSyncRun(scope, runId)),
    );
    return result.value;
  }

  async discrepancies(
    scope: BrandScope,
    status: 'OPEN' | 'RESOLVED' | null = 'OPEN',
  ): Promise<readonly PosSyncDiscrepancyView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly PosSyncDiscrepancyView[]>(
        loyaltyPolicyPaths.posSyncDiscrepancies(scope),
        {
          params: status === null ? {} : { status },
        },
      ),
    );
    return result.value ?? [];
  }

  /** Reconcile one binding now; answers with its report row. A binding that cannot sync answers 422. */
  async reconcile(scope: BrandScope, bindingId: string): Promise<PosSyncRunView> {
    return firstValueFrom(
      this.api.post<{ readonly bindingId: string }, PosSyncRunView>(
        loyaltyPolicyPaths.posSyncRuns(scope),
        command({ bindingId }),
      ),
    );
  }

  /**
   * Stages a binding's mode one step (ADR 0171 rollout), against the binding's version. A step up that skips a stage, and any
   * mode above report-only for a binding that cannot sync, answer 422; a binding that moved on since is `STALE_VERSION`.
   */
  async setMode(
    scope: BrandScope,
    binding: PosSyncBindingView,
    mode: PosSyncMode,
    reason: string,
  ): Promise<PosSyncBindingView> {
    return firstValueFrom(
      this.api.put<{ readonly mode: PosSyncMode; readonly reason: string }, PosSyncBindingView>(
        loyaltyPolicyPaths.posSyncBindingMode(scope, binding.bindingId),
        command({ mode, reason }),
        { expectedVersion: binding.version },
      ),
    );
  }

  /** Says whether this brand's till keeps a bonus balance of its own, against the version of the brand's own row. */
  async setTillKeepsBalance(
    scope: BrandScope,
    value: boolean,
    expectedVersion: number,
    reason: string,
  ): Promise<PosSyncOverview> {
    return firstValueFrom(
      this.api.put<{ readonly value: boolean; readonly reason: string }, PosSyncOverview>(
        loyaltyPolicyPaths.posSyncTillKeepsBalance(scope),
        command({ value, reason }),
        { expectedVersion },
      ),
    );
  }

  /** Settle one difference against the version the operator read (`If-Match`). */
  async resolve(
    scope: BrandScope,
    discrepancy: PosSyncDiscrepancyView,
    resolution: PosSyncResolutionCode,
    reason: string,
  ): Promise<PosSyncResolutionResult> {
    return firstValueFrom(
      this.api.post<
        { readonly resolution: PosSyncResolutionCode; readonly reason: string },
        PosSyncResolutionResult
      >(
        loyaltyPolicyPaths.posSyncResolution(scope, discrepancy.discrepancyId),
        command({ resolution, reason }),
        { expectedVersion: discrepancy.version },
      ),
    );
  }
}

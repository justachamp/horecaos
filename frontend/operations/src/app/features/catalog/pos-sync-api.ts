import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';
import { CursorState, Page } from '../../core/api/page';
import { TenantScope, posPaths } from '../../core/api/pos-paths';

/** One row of the run-history list — `PosSyncRunController.RunSummaryView`. */
export interface SyncRunSummary {
  readonly runId: string;
  readonly bindingId: string;
  readonly status: string;
  readonly triggerType: string;
  readonly dryRun: boolean;
  readonly startedAt: string;
  readonly completedAt: string | null;
  readonly additionCount: number;
  readonly changeCount: number;
  readonly removalCount: number;
  readonly conflictCount: number;
  readonly lastErrorCode: string | null;
}

/** A run's full detail — `PosSyncRunController.RunDetailView`. */
export interface SyncRunDetail {
  readonly runId: string;
  readonly bindingId: string;
  readonly status: string;
  readonly triggerType: string;
  readonly dryRun: boolean;
  readonly adapterVersion: string;
  readonly fieldPolicyVersion: number;
  readonly startedAt: string;
  readonly fetchedAt: string | null;
  readonly normalizedAt: string | null;
  readonly comparedAt: string | null;
  readonly appliedAt: string | null;
  readonly completedAt: string | null;
  readonly receivedCount: number;
  readonly validCount: number;
  readonly invalidCount: number;
  readonly additionCount: number;
  readonly changeCount: number;
  readonly removalCount: number;
  readonly conflictCount: number;
  readonly pageCount: number;
  readonly walkKind: string;
  readonly lastErrorCode: string | null;
  readonly lastError: string | null;
}

/** One planned apply item's actual outcome — `PosSyncRunController.ApplyItemView`. */
export interface ApplyItemOutcome {
  readonly id: string;
  readonly differenceId: string | null;
  readonly idempotencyKey: string;
  readonly action: string;
  readonly targetType: string;
  readonly targetId: string | null;
  readonly status: string;
  readonly appliedAt: string | null;
  readonly failureReason: string | null;
}

/** One row of the difference report — `PosSyncRunController.DifferenceView`. */
export interface SyncDifferenceView {
  readonly entityType: string;
  readonly externalEntityId: string;
  readonly horecaosEntityId: string | null;
  readonly category: string;
  readonly fieldPath: string | null;
  readonly currentValue: string | null;
  readonly importedValue: string | null;
  readonly authority: string;
  readonly severity: string;
  readonly recommendedAction: string;
}

export interface StartRunResult {
  readonly runId: string;
  readonly status: string;
  readonly differenceCount: number;
  readonly conflictCount: number;
  readonly detail: string;
}

/**
 * `PosSyncRunController` (ADR 0012, gap-map row 4.5a): a binding's import run
 * history and detail, its per-item apply outcomes, and starting a run with an
 * import language and a price-re-import choice.
 */
@Injectable({ providedIn: 'root' })
export class PosSyncApi {
  private readonly api = inject(ApiClient);

  listRuns(
    scope: TenantScope,
    bindingId: string,
    page: CursorState,
  ): Observable<Page<SyncRunSummary>> {
    return this.api.page<SyncRunSummary>(posPaths.syncRuns(scope), page, { bindingId });
  }

  runDetail(scope: TenantScope, runId: string): Observable<SyncRunDetail> {
    return this.api
      .get<SyncRunDetail>(posPaths.syncRun(scope, runId))
      .pipe(map((result) => asValue(result.value)));
  }

  differences(
    scope: TenantScope,
    runId: string,
    page: CursorState,
  ): Observable<Page<SyncDifferenceView>> {
    return this.api.page<SyncDifferenceView>(posPaths.syncRunDifferences(scope, runId), page);
  }

  applyItems(
    scope: TenantScope,
    runId: string,
    page: CursorState,
  ): Observable<Page<ApplyItemOutcome>> {
    return this.api.page<ApplyItemOutcome>(posPaths.syncRunApplyItems(scope, runId), page);
  }

  start(
    scope: TenantScope,
    bindingId: string,
    dryRun: boolean,
    importLanguage: string | null,
    priceReImport: boolean,
  ): Observable<StartRunResult> {
    return this.api.post<
      { bindingId: string; importLanguage: string | null; priceReImport: boolean },
      StartRunResult
    >(posPaths.syncRuns(scope), command({ bindingId, importLanguage, priceReImport }), {
      params: { dryRun },
    });
  }

  apply(scope: TenantScope, runId: string): Observable<Readonly<Record<string, string>>> {
    return this.api.post(posPaths.syncRunApply(scope, runId), command({}));
  }

  resume(scope: TenantScope, runId: string): Observable<Readonly<Record<string, string>>> {
    return this.api.post(posPaths.syncRunResume(scope, runId), command({}));
  }

  recordReviewDecision(
    scope: TenantScope,
    runId: string,
    differenceId: string,
    outcome: 'APPROVED' | 'REJECTED' | 'DEFERRED',
    note: string | null,
  ): Observable<Readonly<Record<string, string>>> {
    return this.api.post(
      posPaths.syncRunReviewDecisions(scope, runId),
      command({ differenceId, outcome, note }),
    );
  }
}

function asValue<T>(value: T | undefined): T {
  if (value === undefined) {
    throw new Error('Expected a response body');
  }
  return value;
}

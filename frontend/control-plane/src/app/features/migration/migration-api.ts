import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { Page } from '../../core/api/page';

export type ProgramStatus = 'PLANNING' | 'ACTIVE' | 'COMPLETED' | 'ABANDONED';

/** ProgramView. */
export interface ProgramView {
  readonly id: string;
  readonly name: string;
  readonly status: ProgramStatus;
  readonly sourceEnvironment: string;
  readonly targetEnvironment: string;
  readonly policyVersion: number;
  readonly startedAt: string | null;
  readonly completedAt: string | null;
  readonly version: number;
}

/** ScopeView. */
export interface ScopeView {
  readonly id: string;
  readonly programId: string;
  readonly tenantId: string;
  readonly brandId: string | null;
  readonly locationId: string | null;
  readonly capability: string;
  readonly sourceOwner: string;
  readonly targetOwner: string;
  readonly writeMode: string;
  readonly readMode: string;
  readonly state: string;
  readonly stateEnteredAt: string;
  readonly version: number;
  /** Where the scope may move next; each target has its own action (see ScopeActions). */
  readonly nextStates: readonly string[];
}

/** One run over a scope, with the counters it alone produced. */
export interface RunView {
  readonly id: string;
  readonly scopeId: string;
  readonly runType: RunType;
  readonly status: RunStatus;
  readonly sourceWatermark: string | null;
  readonly targetWatermark: string | null;
  readonly transformationVersion: number;
  readonly counters: {
    readonly scanned: number;
    readonly created: number;
    readonly updated: number;
    readonly skipped: number;
    readonly quarantined: number;
  };
  readonly checksum: string | null;
  readonly startedBy: string;
  readonly version: number;
  readonly startedAt: string;
  readonly finishedAt: string | null;
}

export type RunType = 'BACKFILL' | 'CATCH_UP' | 'REMEDIATION' | 'RECONCILIATION';
export type RunStatus = 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
export const RUN_TYPES: readonly RunType[] = ['BACKFILL', 'CATCH_UP', 'REMEDIATION', 'RECONCILIATION'];

/** A legacy row that could not be migrated: its legacy id, a reason code, and a pointer to sanitized evidence. */
export interface QuarantineItemView {
  readonly id: string;
  readonly runId: string;
  readonly entityType: string;
  readonly legacyId: string;
  readonly reasonCode: string;
  readonly sanitizedEvidenceReference: string | null;
  readonly status: string;
  readonly resolutionCode: string | null;
  readonly resolvedBy: string | null;
  readonly resolvedAt: string | null;
}

/** How a quarantined row is settled; the server takes any upper-snake code, these are the three it names. */
export const RESOLUTION_CODES = ['REIMPORTED_AFTER_SOURCE_FIX', 'MAPPED_BY_HAND', 'ACCEPTED_NOT_MIGRATABLE'] as const;

/** Everything a scope may cover, in the platform's own order. */
export const MIGRATION_CAPABILITIES = [
  'TENANCY',
  'IDENTITY',
  'CUSTOMERS',
  'MEDIA',
  'CATALOG',
  'INVENTORY',
  'PRICING',
  'ORDERS',
  'PAYMENTS',
  'FULFILLMENT',
] as const;

/** Holding states: entered by suspending, left by resuming. */
export const HOLDING_STATES: readonly string[] = ['PAUSED', 'BLOCKED_RECONCILIATION'];

/** MigrationEvidenceController.EntityMappingResponse (IA 9.2 ID mapping explorer). */
export interface EntityMappingView {
  readonly mappingId: string;
  readonly entityType: string;
  readonly legacyId: string;
  readonly targetId: string | null;
  readonly status: 'MAPPED' | 'QUARANTINED' | 'SUPERSEDED' | (string & {});
  readonly supersededByMappingId: string | null;
  readonly runId: string;
  readonly createdAt: string;
}

/** MigrationEvidenceController.ReconciliationResultResponse (IA 9.3 Dual-run comparison). */
export interface ReconciliationResultView {
  readonly resultId: string;
  readonly ruleCode: string;
  readonly ruleVersion: number;
  readonly dimensionKey: string;
  readonly severity: 'CRITICAL' | 'WARNING' | 'INFO' | (string & {});
  readonly measureKind: string;
  readonly expected: number | null;
  readonly actual: number | null;
  readonly difference: number | null;
  readonly status: 'OPEN' | 'APPROVED' | 'RESOLVED' | (string & {});
  readonly approvedBy: string | null;
  readonly resolvedAt: string | null;
}

/**
 * Migration programs, their scopes, runs, quarantine and cutover decisions.
 * Every mutation carries a reason; scope moves also carry the version read,
 * so two operators deciding at once settle at one outcome.
 */
@Injectable({ providedIn: 'root' })
export class MigrationApi {
  private readonly api = inject(ApiClient);

  async createOrFindProgram(
    name: string,
    sourceEnvironment: string,
    targetEnvironment: string,
    policyVersion: number,
    reason: string,
  ): Promise<ProgramView> {
    return firstValueFrom(
      this.api.post<ProgramView>('/api/v1/platform-admin/migration/programs', {
        name,
        sourceEnvironment,
        targetEnvironment,
        policyVersion,
        reason,
      }),
    );
  }

  /** Every program, by name. */
  async listPrograms(cursor: string | null = null, limit = 200): Promise<Page<ProgramView>> {
    return firstValueFrom(
      this.api.getPage<ProgramView>('/api/v1/platform-admin/migration/programs', { cursor, limit }),
    );
  }

  async changeProgramStatus(programId: string, status: ProgramStatus, expectedVersion: number, reason: string): Promise<void> {
    await firstValueFrom(
      this.api.post<void>(`/api/v1/platform-admin/migration/programs/${programId}/status`, {
        status,
        expectedVersion,
        reason,
      }),
    );
  }

  async getProgram(programId: string): Promise<ProgramView> {
    return firstValueFrom(
      this.api.get<ProgramView>(`/api/v1/platform-admin/migration/programs/${programId}`),
    );
  }

  async listScopes(programId: string, cursor: string | null = null, limit = 50): Promise<Page<ScopeView>> {
    return firstValueFrom(
      this.api.getPage<ScopeView>(`/api/v1/platform-admin/migration/programs/${programId}/scopes`, {
        cursor,
        limit,
      }),
    );
  }

  /** IA 9.4 Cutover checklist -- one scope's own detail, for the go/no-go read. */
  async getScope(scopeId: string, tenantId: string): Promise<ScopeView> {
    return firstValueFrom(
      this.api.get<ScopeView>(`/api/v1/platform-admin/migration/scopes/${scopeId}`, {
        query: { tenantId },
      }),
    );
  }

  /** IA 9.2 ID mapping explorer -- one scope's legacy-to-target crosswalk for one entity type. */
  async listEntityMappings(
    scopeId: string,
    tenantId: string,
    entityType: string,
    limit = 50,
  ): Promise<Page<EntityMappingView>> {
    return firstValueFrom(
      this.api.getPage<EntityMappingView>(
        `/api/v1/platform-admin/migration/scopes/${scopeId}/entity-mappings`,
        { limit },
        { query: { tenantId, entityType } },
      ),
    );
  }

  /** IA 9.3 Dual-run comparison -- one reconciliation run's per-rule diff. */
  async listReconciliationResults(
    runId: string,
    tenantId: string,
    limit = 50,
  ): Promise<Page<ReconciliationResultView>> {
    return firstValueFrom(
      this.api.getPage<ReconciliationResultView>(
        `/api/v1/platform-admin/migration/runs/${runId}/reconciliation-results`,
        { limit },
        { query: { tenantId } },
      ),
    );
  }

  async openScope(
    programId: string,
    request: {
      readonly tenantId: string;
      readonly brandId?: string;
      readonly locationId?: string;
      readonly capability: string;
      readonly sourceOwner: string;
      readonly targetOwner: string;
      readonly reason: string;
    },
  ): Promise<ScopeView> {
    return firstValueFrom(
      this.api.post<ScopeView>(
        `/api/v1/platform-admin/migration/programs/${programId}/scopes`,
        request,
      ),
    );
  }

  // ------------------------------------------------------------ scope moves

  private scopePath(scopeId: string, action: string): string {
    return `/api/v1/platform-admin/migration/scopes/${scopeId}/${action}`;
  }

  async advanceScope(scope: ScopeView, targetState: string, reason: string): Promise<void> {
    await firstValueFrom(
      this.api.post<void>(
        this.scopePath(scope.id, 'transitions'),
        { targetState, expectedVersion: scope.version, reason },
        { query: { tenantId: scope.tenantId } },
      ),
    );
  }

  async suspendScope(scope: ScopeView, holdingState: string, reason: string): Promise<void> {
    await firstValueFrom(
      this.api.post<void>(
        this.scopePath(scope.id, 'suspensions'),
        { holdingState, expectedVersion: scope.version, reason },
        { query: { tenantId: scope.tenantId } },
      ),
    );
  }

  async resumeScope(scope: ScopeView, reason: string): Promise<void> {
    await firstValueFrom(
      this.api.post<void>(
        this.scopePath(scope.id, 'resumptions'),
        { expectedVersion: scope.version, reason },
        { query: { tenantId: scope.tenantId } },
      ),
    );
  }

  async rollBackScope(scope: ScopeView, reason: string): Promise<void> {
    await firstValueFrom(
      this.api.post<void>(
        this.scopePath(scope.id, 'rollbacks'),
        { expectedVersion: scope.version, reason },
        { query: { tenantId: scope.tenantId } },
      ),
    );
  }

  /** Approves (or refuses) taking target ownership; the gates are re-checked by the server at that moment. */
  async decideCutover(
    scope: ScopeView,
    decision: 'approve' | 'refuse',
    request: { readonly requestedBy: string; readonly evidence: Readonly<Record<string, string>>; readonly reason: string },
  ): Promise<void> {
    await firstValueFrom(
      this.api.post<void>(
        this.scopePath(scope.id, decision === 'approve' ? 'cutover' : 'cutover-refusals'),
        { targetState: 'TARGET_OWNED', expectedVersion: scope.version, ...request },
        { query: { tenantId: scope.tenantId } },
      ),
    );
  }

  // ------------------------------------------------------------ runs

  async listRuns(scope: ScopeView, limit = 50): Promise<Page<RunView>> {
    return firstValueFrom(
      this.api.getPage<RunView>(this.scopePath(scope.id, 'runs'), { limit }, { query: { tenantId: scope.tenantId } }),
    );
  }

  async startRun(
    scope: ScopeView,
    request: { readonly runType: RunType; readonly transformationVersion: number; readonly startedBy: string; readonly reason: string },
  ): Promise<RunView> {
    return firstValueFrom(
      this.api.post<RunView>(this.scopePath(scope.id, 'runs'), request, { query: { tenantId: scope.tenantId } }),
    );
  }

  async finishRun(
    tenantId: string,
    run: RunView,
    status: Exclude<RunStatus, 'RUNNING'>,
    reason: string,
    checksum?: string,
  ): Promise<RunView> {
    return firstValueFrom(
      this.api.post<RunView>(
        `/api/v1/platform-admin/migration/runs/${run.id}/outcome`,
        { status, expectedVersion: run.version, reason, checksum },
        { query: { tenantId } },
      ),
    );
  }

  // ------------------------------------------------------------ quarantine

  async openQuarantine(scope: ScopeView, limit = 50): Promise<Page<QuarantineItemView>> {
    return firstValueFrom(
      this.api.getPage<QuarantineItemView>(
        this.scopePath(scope.id, 'quarantine-items'),
        { limit },
        { query: { tenantId: scope.tenantId } },
      ),
    );
  }

  async resolveQuarantine(tenantId: string, itemId: string, resolutionCode: string, reason: string): Promise<void> {
    await firstValueFrom(
      this.api.post<void>(
        `/api/v1/platform-admin/migration/quarantine-items/${itemId}/resolution`,
        { resolutionCode, reason },
        { query: { tenantId } },
      ),
    );
  }
}

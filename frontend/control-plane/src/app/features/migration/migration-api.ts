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
}

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
 * IA 9.1 Migration runs -- `MigrationProgramController` (ADR 0024), now
 * reachable from control-plane after ADR 0066 moved `/api/v1/platform-admin/**`
 * into this app's own OpenAPI surface.
 *
 * A program has no list-all endpoint (`POST` is idempotent by `name`, its own
 * javadoc says so: "a retry asking for the same program in the same words
 * gets the program it already created"), so this screen finds a program by
 * re-submitting its name rather than browsing a directory that does not
 * exist. Individual runs within a scope (`MigrationRunController`) are not
 * drilled into here -- programs and their scopes are the core "per-tenant
 * import, resumability" concept this row needs, and a further drill-down
 * into run history is a reasonable follow-up, not this wave's scope.
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
}

import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';
import { CursorState, Page } from '../../core/api/page';
import { TenantScope, posPaths } from '../../core/api/pos-paths';

/** The six pairings the mapping pane offers — `MappingEntityType`. */
export type MappingEntityType =
  'PRODUCT' | 'PAYMENT_TYPE' | 'DISCOUNT' | 'COURIER' | 'CANCELLATION_REASON' | 'CHANNEL_POS_CODE';

/** `provider_entity_mappings.status`. */
export type MappingStatus = 'PROPOSED' | 'ACTIVE' | 'CONFLICTED' | 'RETIRED';

/** One mapping row — `PosMappingController.MappingView`. */
export interface MappingView {
  readonly mappingId: string;
  readonly bindingId: string;
  readonly entityType: MappingEntityType;
  readonly horecaosEntityId: string;
  /** Resolved for display; null only when the linked HorecaOS row itself no longer exists. */
  readonly horecaosName: string | null;
  readonly externalEntityId: string;
  readonly externalParentId: string | null;
  readonly status: MappingStatus;
  readonly mappingSource: 'DISCOVERED' | 'OPERATOR' | 'IMPORTED';
  readonly lastSeenAt: string | null;
  readonly version: number;
  readonly updatedAt: string;
}

/** One provider-side candidate not yet mapped — `PosMappingController.UnmappedExternalView`. */
export interface UnmappedExternalEntity {
  readonly externalId: string;
  readonly name: string | null;
  readonly externalParentId: string | null;
}

/** One HorecaOS-side candidate not yet mapped — `PosMappingController.HorecaosCandidateView`, the dual list's left column. */
export interface HorecaosCandidate {
  readonly id: string;
  readonly name: string | null;
}

/**
 * Both unmapped sides of the pane's dual list. `sourced=false` on the
 * external side means this build cannot read the provider's list for this
 * entity type at all; the HorecaOS side is always readable regardless.
 */
export interface UnmappedExternalResponse {
  readonly sourced: boolean;
  readonly detail: string | null;
  readonly entities: readonly UnmappedExternalEntity[];
  readonly horecaosCandidates: readonly HorecaosCandidate[];
}

/** The two-sided conflict card's own data — `PosMappingController.MappingConflictView`. */
export interface MappingConflict {
  readonly name: string;
  readonly externalIds: readonly string[];
  readonly horecaosEntityIds: readonly string[];
}

export interface BulkAutoMatchResponse {
  readonly sourced: boolean;
  readonly detail: string | null;
  readonly matchedCount: number;
  readonly conflicts: readonly MappingConflict[];
}

/**
 * `PosMappingController` (ADR 0012/0026, gap-map row 10.8b): the tenant-facing
 * mapping pane over `integration.provider_entity_mappings` — list, unmapped,
 * create, retire, bulk auto-match.
 */
@Injectable({ providedIn: 'root' })
export class PosMappingApi {
  private readonly api = inject(ApiClient);

  list(
    scope: TenantScope,
    bindingId: string,
    entityType: MappingEntityType,
    status: MappingStatus | null,
    page: CursorState,
  ): Observable<Page<MappingView>> {
    return this.api.page<MappingView>(posPaths.mappings(scope), page, {
      bindingId,
      entityType,
      status: status ?? undefined,
    });
  }

  unmapped(
    scope: TenantScope,
    bindingId: string,
    entityType: MappingEntityType,
  ): Observable<UnmappedExternalResponse> {
    return this.api
      .get<UnmappedExternalResponse>(posPaths.mappingsUnmapped(scope), {
        params: { bindingId, entityType },
      })
      .pipe(map((result) => asValue(result.value)));
  }

  create(
    scope: TenantScope,
    bindingId: string,
    entityType: MappingEntityType,
    horecaosEntityId: string,
    externalEntityId: string,
    externalParentId: string | null,
  ): Observable<{ mappingId: string; status: string }> {
    return this.api.post(
      posPaths.mappings(scope),
      command({ bindingId, entityType, horecaosEntityId, externalEntityId, externalParentId }),
    );
  }

  retire(
    scope: TenantScope,
    mappingId: string,
    expectedVersion: number,
  ): Observable<{ mappingId: string; status: string }> {
    return this.api.post(posPaths.mappingRetire(scope, mappingId), command({ expectedVersion }));
  }

  bulkAutoMatch(
    scope: TenantScope,
    bindingId: string,
    entityType: MappingEntityType,
  ): Observable<BulkAutoMatchResponse> {
    return this.api.post(posPaths.mappingsBulkAutoMatch(scope), command({ bindingId, entityType }));
  }
}

function asValue<T>(value: T | undefined): T {
  if (value === undefined) {
    throw new Error('Expected a response body');
  }
  return value;
}

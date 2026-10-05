import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { command } from '../../../core/api/idempotency';
import { LocationScope } from '../../../core/api/operations-paths';
import { settingsPaths } from '../../../core/api/settings-paths';
import {
  SlaBucketDefinition,
  SlaBucketSetApi,
  SlaBucketSetView,
} from '../../../core/api/sla-bucket-set-api';

export type OutcomeReasonKind = 'CANCELLATION' | 'COMPLETION';
export type StockDisposition = 'RELEASE' | 'RETURN_TO_STOCK' | 'WRITE_OFF' | 'NO_EFFECT';
export type LiabilityParty = 'TENANT' | 'CUSTOMER' | 'COURIER_PARTNER' | 'PLATFORM';
export type CustomerRefund = 'FULL' | 'NONE' | 'DISCRETIONARY';

/** Mirrors uz.horecaos.platform.ordering.web.OrderOutcomeReasonController.ReasonResponse. */
export interface ReasonResponse {
  readonly id: string;
  readonly kind: OutcomeReasonKind;
  readonly systemCategory: string;
  readonly internalName: string;
  readonly stockDisposition: StockDisposition | null;
  readonly liabilityParty: LiabilityParty | null;
  readonly customerRefund: CustomerRefund | null;
  readonly allowedFulfillmentModes: readonly string[] | null;
  readonly customerTexts: Readonly<Record<string, string>>;
  readonly status: string;
  readonly version: number;
  readonly updatedAt: string;
}

export interface ReasonRequest {
  readonly kind: OutcomeReasonKind;
  readonly systemCategory: string;
  readonly internalName: string;
  readonly stockDisposition?: StockDisposition;
  readonly liabilityParty?: LiabilityParty;
  readonly customerRefund?: CustomerRefund;
  readonly allowedFulfillmentModes?: readonly string[];
  readonly customerTexts: Readonly<Record<string, string>>;
}

// 10.10c's read-only version card. The types and the read live in `core/api` now, because the
// branch and courier reports print the same version from the same endpoint.
export type { SlaBucketDefinition, SlaBucketSetView };

/**
 * 10.10 Reference data — the cancellation/completion reason registry
 * (`OrderOutcomeReasonController`, ADR 0039). Cross-surface — see
 * `settings-paths.ts`'s own doc comment.
 */
@Injectable({ providedIn: 'root' })
export class ReferenceDataApi {
  private readonly api = inject(ApiClient);
  private readonly slaBuckets = inject(SlaBucketSetApi);

  async list(scope: LocationScope, kind: OutcomeReasonKind): Promise<readonly ReasonResponse[]> {
    const result = await firstValueFrom(
      this.api.get<readonly ReasonResponse[]>(settingsPaths.orderOutcomeReasons(scope), {
        params: { kind, activeOnly: true },
      }),
    );
    return result.value ?? [];
  }

  async categories(scope: LocationScope, kind: OutcomeReasonKind): Promise<readonly string[]> {
    const result = await firstValueFrom(
      this.api.get<readonly string[]>(settingsPaths.orderOutcomeReasonCategories(scope), {
        params: { kind },
      }),
    );
    return result.value ?? [];
  }

  async create(scope: LocationScope, request: ReasonRequest): Promise<string> {
    const response = await firstValueFrom(
      this.api.post<ReasonRequest, { id: string }>(
        settingsPaths.orderOutcomeReasons(scope),
        command(request),
      ),
    );
    return response.id;
  }

  /**
   * Rewrites a reason and bumps its version. The caller is responsible for
   * the "this creates a new version" warning settings.md 10.10 requires
   * before this is ever called — see `reference-data-page.ts`'s edit dialog.
   */
  async update(
    scope: LocationScope,
    reasonId: string,
    request: ReasonRequest,
    expectedVersion: number,
  ): Promise<number> {
    const response = await firstValueFrom(
      this.api.put<ReasonRequest, { version: number }>(
        settingsPaths.orderOutcomeReason(scope, reasonId),
        command(request),
        { expectedVersion },
      ),
    );
    return response.version;
  }

  /**
   * Row 10.10a — ranks every active reason of one kind, in the order given.
   * `expectedVersion` is the **sum** of `version` across the reasons being
   * reordered, not the highest one — the caller (the drag-reorder list,
   * which already holds every row's own version from `list`) computes it
   * with `reasons.reduce((sum, r) => sum + r.version, 0)`, the same "there
   * is no separate list aggregate to version" rule the endpoint's own doc
   * names. A sum, not a max: `version` only ever increments, so the sum
   * strictly increases whenever any one reason changes underneath the
   * caller, whichever row it is — the max alone misses a concurrent edit to
   * a reason that never held the highest version.
   * Returns the reordered list, versions already bumped, so the caller
   * never needs a second read before its next write.
   */
  async reorder(
    scope: LocationScope,
    kind: OutcomeReasonKind,
    orderedReasonIds: readonly string[],
    expectedVersion: number,
  ): Promise<readonly ReasonResponse[]> {
    const response = await firstValueFrom(
      this.api.put<
        { kind: OutcomeReasonKind; orderedReasonIds: readonly string[] },
        readonly ReasonResponse[]
      >(settingsPaths.orderOutcomeReasonReorder(scope), command({ kind, orderedReasonIds }), {
        expectedVersion,
      }),
    );
    return response;
  }

  async archive(scope: LocationScope, reasonId: string, expectedVersion: number): Promise<void> {
    await firstValueFrom(
      this.api.send<null, void>(
        'DELETE',
        settingsPaths.orderOutcomeReason(scope, reasonId),
        command(null),
        {
          expectedVersion,
        },
      ),
    );
  }

  /** 10.10c: which bucket definitions the SLA reports are computed under, read-only. */
  slaBucketSet(scope: LocationScope): Promise<SlaBucketSetView> {
    return this.slaBuckets.get(scope.tenantId);
  }
}

import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';
import { PolicyScope } from './headcount-api';

/** `KitchenPaidHoldPolicy.Mode` (ADR 0168 Decision 8). */
export type PaidHoldMode = 'SHADOW' | 'HOLD_UNTIL_PAID' | 'OFF';

export const PAID_HOLD_MODES: readonly PaidHoldMode[] = ['SHADOW', 'HOLD_UNTIL_PAID', 'OFF'];

/** The bounds `KitchenPaidHoldPolicy.violations()` enforces. */
export const PAID_HOLD_OVERDUE_MIN = 1;
export const PAID_HOLD_OVERDUE_MAX = 1440;

/** Mirrors `PolicyEditorView.Level`: one rung of the resolution ladder, the platform's included. */
export interface PaidHoldLevel {
  readonly scopeType: PolicyScope | 'PLATFORM';
  readonly authored: boolean;
}

/** Mirrors `KitchenPaidHoldController.PaidHoldResponse`. */
export interface PaidHoldPolicy {
  readonly schema: number;
  readonly mode: PaidHoldMode;
  readonly overdueAfterMinutes: number;
  /** Nothing was published anywhere in the chain: the built-in trial mode applies. */
  readonly isDefault: boolean;
  readonly winningScope: PolicyScope | 'PLATFORM' | null;
  readonly policyId: string | null;
  readonly policyVersion: number;
  /** The version authored at exactly the asked scope (0 when it only inherits): the `If-Match`. */
  readonly versionAtScope: number;
  readonly levels: readonly PaidHoldLevel[];
}

export interface PaidHoldInput {
  readonly mode: PaidHoldMode;
  readonly overdueAfterMinutes: number;
  readonly reason: string;
}

/**
 * Whether a buffered ticket waits for its order's money (ADR 0168, `KitchenPaidHoldController`). The platform
 * level is not written here: the owner publishes it by an operator-run start-up and no endpoint exists for it.
 */
@Injectable({ providedIn: 'root' })
export class PaidHoldApi {
  private readonly api = inject(ApiClient);

  /** The policy in force at the asked scope, and the version a save is checked against. */
  async policy(scope: LocationScope, level: PolicyScope): Promise<PaidHoldPolicy> {
    const result = await firstValueFrom(
      this.api.get<PaidHoldPolicy>(operationsPaths.kitchenPaidHold(scope.tenantId), {
        params: scopeParams(scope, level),
      }),
    );
    return result.value;
  }

  /** Whole-document replace at exactly one scope; a stale `expectedVersion` is refused with `STALE_VERSION`. */
  async publish(
    scope: LocationScope,
    level: PolicyScope,
    input: PaidHoldInput,
    expectedVersion: number,
  ): Promise<PaidHoldPolicy> {
    return firstValueFrom(
      this.api.put<PaidHoldInput, PaidHoldPolicy>(
        operationsPaths.kitchenPaidHold(scope.tenantId),
        command(input),
        { expectedVersion, params: scopeParams(scope, level) },
      ),
    );
  }
}

function scopeParams(scope: LocationScope, level: PolicyScope): Record<string, string> {
  switch (level) {
    case 'TENANT':
      return {};
    case 'BRAND':
      return { brandId: scope.brandId };
    case 'LOCATION':
      return { brandId: scope.brandId, locationId: scope.locationId };
  }
}

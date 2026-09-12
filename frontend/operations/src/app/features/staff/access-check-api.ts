import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { staffPaths } from '../../core/api/staff-paths';
import { GrantView, ScopeType } from './staff-api';

export type AccessCheckVerdict = 'ALLOWED' | 'INSUFFICIENT_CAPABILITY' | 'ENTITLEMENT_REQUIRED';

/** Mirrors `GrantController.EntitlementAnswer`. */
export interface AccessCheckEntitlementAnswer {
  readonly description: string;
  readonly upgradePath: string;
}

/** Mirrors `GrantController.AccessCheckResponse`. */
export interface AccessCheckResponse {
  readonly verdict: AccessCheckVerdict;
  readonly capability: string;
  readonly scopeType: ScopeType;
  readonly scopeId: string | null;
  readonly heldElsewhere: readonly GrantView[];
  readonly entitlement: AccessCheckEntitlementAnswer | null;
}

export interface AccessCheckRequest {
  readonly subject: string;
  readonly capability: string;
  readonly scopeType: 'TENANT' | 'BRAND' | 'LOCATION';
  readonly brandId?: string;
  readonly locationId?: string;
  readonly entitlementKey?: string;
}

/**
 * Staff 9.5 Проверка доступа (ADR 0109) — `GrantController.accessCheck`, the
 * tenant-facing sibling of the platform-admin-only access debugger.
 */
@Injectable({ providedIn: 'root' })
export class AccessCheckApi {
  private readonly api = inject(ApiClient);

  async check(tenantId: string, request: AccessCheckRequest): Promise<AccessCheckResponse> {
    const result = await firstValueFrom(
      this.api.get<AccessCheckResponse>(staffPaths.accessCheck(tenantId), {
        params: {
          subject: request.subject,
          capability: request.capability,
          scopeType: request.scopeType,
          brandId: request.brandId,
          locationId: request.locationId,
          entitlementKey: request.entitlementKey,
        },
      }),
    );
    return result.value;
  }
}

import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from './api/api-client';
import { LocationScope, operationsPaths } from './api/operations-paths';
import {
  LatenessPolicy,
  LatenessThresholds,
  PLATFORM_DEFAULT_LATENESS_POLICY,
} from './lateness-policy';

/** `OrderLatenessPolicyController.LatenessThresholdsResponse`, verbatim. */
interface LatenessThresholdsResponse {
  readonly atRiskBeforeSeconds: number;
  readonly lateAfterSeconds: number;
  readonly noPromiseFallbackSeconds: number;
}

/** `OrderLatenessPolicyController.LatenessPolicyResponse`, verbatim. */
interface LatenessPolicyResponse {
  readonly delivery: LatenessThresholdsResponse;
  readonly pickup: LatenessThresholdsResponse;
  readonly dineIn: LatenessThresholdsResponse;
  readonly isPlatformDefault: boolean;
}

/**
 * `GET .../orders/lateness-policy` — the resolved `ordering.lateness`
 * document (wave P06). Read-only: authoring is `TENANT_CONFIGURATION_WRITE`
 * and lands with wave P31's settings surface.
 *
 * Falls back to {@link PLATFORM_DEFAULT_LATENESS_POLICY} on any read failure
 * — a denied capability, a network error, or a server that has not yet
 * deployed the endpoint — the same documented fallback `OrderCounts` and
 * `RejectReasonsApi` already use for their own reads: a queue with a slightly
 * generic ramp is a better failure than a queue that will not render.
 */
@Injectable({ providedIn: 'root' })
export class LatenessPolicyApi {
  private readonly api = inject(ApiClient);

  async resolve(scope: LocationScope): Promise<LatenessPolicy> {
    try {
      const result = await firstValueFrom(
        this.api.get<LatenessPolicyResponse>(operationsPaths.orderLatenessPolicy(scope)),
      );
      return isLatenessPolicyResponse(result.value)
        ? toPolicy(result.value)
        : PLATFORM_DEFAULT_LATENESS_POLICY;
    } catch {
      return PLATFORM_DEFAULT_LATENESS_POLICY;
    }
  }
}

function isThresholds(value: unknown): value is LatenessThresholdsResponse {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const candidate = value as Partial<LatenessThresholdsResponse>;
  return (
    typeof candidate.atRiskBeforeSeconds === 'number' &&
    typeof candidate.lateAfterSeconds === 'number' &&
    typeof candidate.noPromiseFallbackSeconds === 'number'
  );
}

function isLatenessPolicyResponse(value: unknown): value is LatenessPolicyResponse {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const candidate = value as Partial<LatenessPolicyResponse>;
  return (
    isThresholds(candidate.delivery) &&
    isThresholds(candidate.pickup) &&
    isThresholds(candidate.dineIn)
  );
}

function toThresholds(response: LatenessThresholdsResponse): LatenessThresholds {
  return {
    atRiskBeforeSeconds: response.atRiskBeforeSeconds,
    lateAfterSeconds: response.lateAfterSeconds,
    noPromiseFallbackSeconds: response.noPromiseFallbackSeconds,
  };
}

function toPolicy(response: LatenessPolicyResponse): LatenessPolicy {
  return {
    delivery: toThresholds(response.delivery),
    pickup: toThresholds(response.pickup),
    dineIn: toThresholds(response.dineIn),
  };
}

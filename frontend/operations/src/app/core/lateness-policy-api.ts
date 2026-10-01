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
  /** The tenant's `#rrggbb` for a late order (row `X.39`), or absent/null for the design-system token. */
  readonly lateColour?: string | null;
}

/**
 * `GET .../orders/lateness-policy` — the resolved `ordering.lateness`
 * document (wave P06). Read-only: authoring is `TENANT_CONFIGURATION_WRITE`
 * and lands with wave P31's settings surface.
 *
 * {@link resolve} falls back to {@link PLATFORM_DEFAULT_LATENESS_POLICY} on any
 * read failure — a denied capability, a network error, or a server that has not
 * yet deployed the endpoint — the same documented fallback `OrderCounts` and
 * `RejectReasonsApi` already use for their own reads: a queue with a slightly
 * generic ramp is a better failure than a queue that will not render.
 *
 * A caller that keeps the answer for longer than one render uses {@link
 * tryResolve} instead, which says when there was none: a fallback that is
 * cached is a branch judged by the platform default for as long as it is kept,
 * against a server that filters by the branch's real policy.
 */
@Injectable({ providedIn: 'root' })
export class LatenessPolicyApi {
  private readonly api = inject(ApiClient);

  /** The branch's policy, or the platform default when it could not be read. */
  async resolve(scope: LocationScope): Promise<LatenessPolicy> {
    return (await this.tryResolve(scope)) ?? PLATFORM_DEFAULT_LATENESS_POLICY;
  }

  /**
   * The branch's policy, or `null` when it could not be read — a failed or
   * malformed response. Never the fallback: the caller decides what to show
   * meanwhile, and does not remember the absence.
   */
  async tryResolve(scope: LocationScope): Promise<LatenessPolicy | null> {
    try {
      const result = await firstValueFrom(
        this.api.get<LatenessPolicyResponse>(operationsPaths.orderLatenessPolicy(scope)),
      );
      return isLatenessPolicyResponse(result.value) ? toPolicy(result.value) : null;
    } catch {
      return null;
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

const HEX_COLOUR = /^#[0-9a-fA-F]{6}$/;

/**
 * The server only ever serves an exact `#rrggbb`, and this checks again: the
 * value ends up in a style binding on the board, so a string of any other
 * shape is dropped here rather than trusted because the endpoint is ours.
 */
function toLateColour(value: unknown): string | null {
  return typeof value === 'string' && HEX_COLOUR.test(value) ? value.toLowerCase() : null;
}

function toPolicy(response: LatenessPolicyResponse): LatenessPolicy {
  return {
    delivery: toThresholds(response.delivery),
    pickup: toThresholds(response.pickup),
    dineIn: toThresholds(response.dineIn),
    lateColour: toLateColour(response.lateColour),
  };
}

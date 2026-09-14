import { Injectable, inject } from '@angular/core';
import { Observable, firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';

/**
 * Mirrors `KitchenStationController.StationCapacityResponse` (IA §2.6, ADR
 * 0041). `windowStart`/`windowEnd` are `HH:mm:ss` — a plain local time of day,
 * not a date.
 */
export interface CapacityWindowResponse {
  readonly capacityWindowId: string;
  readonly stationId: string;
  /** ISO-8601: 1 = Monday .. 7 = Sunday. */
  readonly weekday: number;
  readonly windowStart: string;
  readonly windowEnd: string;
  readonly portionsPerHour: number;
  readonly version: number;
}

export interface NewCapacityWindow {
  readonly stationId: string;
  readonly weekday: number;
  readonly windowStart: string;
  readonly windowEnd: string;
  readonly portionsPerHour: number;
}

/**
 * `KitchenStationController.RoutingRuleRequest`/`RoutingRuleResponse` — row
 * 4.2g's kitchen department. `stationId` names the location layer;
 * `stationRole` (this file's own product-editor caller) names the brand
 * layer, which the location resolves for itself at every branch.
 */
export interface NewRoutingRule {
  readonly variantId?: string | null;
  readonly productId?: string | null;
  readonly categoryId?: string | null;
  readonly stationRole?: string | null;
  readonly stationId?: string | null;
}

export interface RoutingRuleResponse {
  readonly ruleId: string;
  /** `BRAND` or `LOCATION`. */
  readonly layer: string;
}

/**
 * IA §2.6 — Capacity & buffer settings (`KitchenStationController`, new this
 * wave). Read today only by this settings screen: the release scheduler does
 * not shift on a ceiling yet — see `CapacityPage`'s own doc for the full
 * honesty accounting.
 */
@Injectable({ providedIn: 'root' })
export class CapacityApi {
  private readonly api = inject(ApiClient);

  async list(scope: LocationScope): Promise<readonly CapacityWindowResponse[]> {
    const result = await firstValueFrom(
      this.api.get<readonly CapacityWindowResponse[]>(
        operationsPaths.kitchenStationCapacity(scope),
      ),
    );
    return result.value ?? [];
  }

  /** Refused (409) when the window overlaps one already stored for that station and weekday. There is no edit or delete. */
  create(scope: LocationScope, body: NewCapacityWindow): Observable<CapacityWindowResponse> {
    return this.api.post<NewCapacityWindow, CapacityWindowResponse>(
      operationsPaths.kitchenStationCapacity(scope),
      command(body),
    );
  }

  /**
   * Routes one catalogue node — row 4.2g's kitchen department. Refused (409)
   * when that node is already routed at the layer this call would write; a
   * second save for the same product is a real "already routed" state, not a
   * bug, since `KitchenStationController` keeps no edit path on this table.
   */
  route(scope: LocationScope, body: NewRoutingRule): Observable<RoutingRuleResponse> {
    return this.api.post<NewRoutingRule, RoutingRuleResponse>(
      operationsPaths.kitchenRoutingRules(scope),
      command(body),
    );
  }
}

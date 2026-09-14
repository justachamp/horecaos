import { Injectable, inject } from '@angular/core';
import { Observable, firstValueFrom, map } from 'rxjs';

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

/** The station and weekday do not move — delete and re-create for that. */
export interface CapacityWindowEdit {
  readonly windowStart: string;
  readonly windowEnd: string;
  readonly portionsPerHour: number;
  readonly expectedVersion: number;
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
 * IA §2.6 — Capacity & buffer settings (`KitchenStationController`).
 * `KitchenTicketService.decideRelease` shifts a ticket's release earlier when
 * a station's board is already committed past a ceiling for the slot — see
 * `CapacityPage`'s own doc for the full honesty accounting on what else §2.6
 * still does not build.
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

  /** Refused (409) when the window overlaps one already stored for that station and weekday. */
  create(scope: LocationScope, body: NewCapacityWindow): Observable<CapacityWindowResponse> {
    return this.api.post<NewCapacityWindow, CapacityWindowResponse>(
      operationsPaths.kitchenStationCapacity(scope),
      command(body),
    );
  }

  /**
   * Corrects a stored window's own time range or rate (wave T02, gap map row
   * 2.6). Refused (409) when the edit would overlap another window already
   * stored for that station and weekday, the same rule {@link create}
   * enforces — checked excluding this window's own row.
   */
  update(
    scope: LocationScope,
    capacityWindowId: string,
    body: CapacityWindowEdit,
  ): Observable<CapacityWindowResponse> {
    return this.api.put<CapacityWindowEdit, CapacityWindowResponse>(
      operationsPaths.kitchenStationCapacityWindow(scope, capacityWindowId),
      command(body),
    );
  }

  /**
   * Removes a throughput ceiling (wave T02, gap map row 2.6) — the escape
   * from a mistyped or overlapping window that used to be permanent, since
   * {@link create} refuses a second window over the same slot.
   */
  remove(
    scope: LocationScope,
    capacityWindowId: string,
    expectedVersion: number,
  ): Observable<void> {
    return this.api
      .send<{ expectedVersion: number }, void>(
        'DELETE',
        operationsPaths.kitchenStationCapacityWindow(scope, capacityWindowId),
        command({ expectedVersion }),
      )
      .pipe(map(() => undefined));
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

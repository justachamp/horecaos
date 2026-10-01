import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';

/**
 * ADR 0141 — how far a stop reaches. `TERMINAL` is deliberately absent: the platform
 * knows the name and refuses it (a device that takes orders is a sales channel, so a stop
 * "for the kiosk" is a `CHANNEL` stop).
 */
export type StopScope = 'LOCATION' | 'BRAND' | 'MENU' | 'CHANNEL';

/** Who said the dish is stopped. `KITCHEN_DEVICE` and `RULE` are reserved and never arrive today. */
export type StopSourceName = 'OPERATOR' | 'BOT' | 'POS' | 'KITCHEN_DEVICE' | 'RULE';

/**
 * A stop in force on one dish, as the stop list's rows carry it. Mirrors
 * `CatalogAuthoringController.StopInForceResponse`. `everyChannel` false means a partial
 * stop: the dish still sells on the channels it does not name. `reasonCode` is an
 * enumerated code, never free text (ADR 0029).
 */
export interface StopInForce {
  readonly stopId: string;
  readonly scopeType: StopScope;
  readonly source: StopSourceName;
  readonly reasonCode: string;
  readonly endsAt?: string | null;
  readonly createdAt: string;
  readonly locationId?: string | null;
  readonly menuId?: string | null;
  readonly channelId?: string | null;
  readonly everyChannel: boolean;
  readonly version: number;
}

/** Mirrors `InventoryStopController.CreateStopRequest`. Optional fields are omitted, not null, as the console always sent them. */
export interface CreateStopRequest {
  readonly variantIds: readonly string[];
  readonly scope: StopScope;
  readonly menuId?: string;
  readonly channelId?: string;
  readonly reasonCode: string;
  readonly endsAt?: string;
  readonly untilEndOfTradingDay?: boolean;
}

/** Mirrors `InventoryStopController.StopItemOutcome`. */
export interface StopItemOutcome {
  readonly variantId: string;
  readonly status: string;
  readonly changed: boolean;
  readonly stopId?: string | null;
  readonly problemCode?: string | null;
}

/** Mirrors `InventoryStopController.StopGestureResponse`. */
export interface StopGestureResponse {
  readonly groupId: string;
  readonly requestedCount: number;
  readonly appliedCount: number;
  readonly failedCount: number;
  readonly items: readonly StopItemOutcome[];
}

export type PropagationMode = 'AUTOMATIC' | 'MANUAL' | 'SUSPENDED';

/** Mirrors `MarketplacePropagationController.ItemResponse`. */
export interface PropagationItem {
  readonly variantId: string;
  readonly desiredAvailable: boolean;
  readonly confirmedAvailable?: boolean | null;
  readonly state: string;
  readonly since?: string | null;
  readonly lastFailureCode?: string | null;
}

/** Mirrors `MarketplacePropagationController.BindingResponse`. */
export interface PropagationBinding {
  readonly bindingId: string;
  readonly providerType: string;
  readonly displayName: string;
  readonly mode: PropagationMode;
  readonly inSync: number;
  readonly pending: number;
  readonly uncertain: number;
  readonly rejectedUnmapped: number;
  /** pending + uncertain + rejectedUnmapped: what the platform has not been able to confirm. */
  readonly unconfirmed: number;
  readonly oldestUnconfirmedSince?: string | null;
  readonly lastSuccessAt?: string | null;
  readonly lastFailureAt?: string | null;
  readonly lastFailureCode?: string | null;
  readonly unconfirmedItems: readonly PropagationItem[];
}

interface PropagationResponse {
  readonly bindings?: readonly PropagationBinding[];
}

/** The scopes a branch manager may stop at with `inventory.availability.manage` alone. */
export function isBranchScoped(scope: StopScope, channelHere: boolean): boolean {
  return scope === 'LOCATION' || (scope === 'CHANNEL' && channelHere);
}

/**
 * The stop list's client for ADR 0141: stopping with a scope, lifting, and the marketplace
 * propagation status. Two create routes because the platform has two capabilities: a
 * branch-level stop needs `inventory.availability.manage` at the branch, a brand-level one
 * `inventory.stop.manage` at the brand, and one endpoint declares only one.
 */
@Injectable({ providedIn: 'root' })
export class StopsApi {
  private readonly api = inject(ApiClient);

  /** Stops dishes at a scope; the route is chosen by the reach the stop has. */
  stop(
    scope: LocationScope,
    request: CreateStopRequest,
    atThisBranch: boolean,
  ): Promise<StopGestureResponse> {
    const path = atThisBranch
      ? operationsPaths.inventoryStopsAtLocation(scope)
      : operationsPaths.inventoryStopsAtBrand(scope);
    return firstValueFrom(
      this.api.post<CreateStopRequest, StopGestureResponse>(path, command(request)),
    );
  }

  /**
   * Lifts one stop, quoting the version the list showed (`If-Match`): a stop somebody else
   * changed in the meantime is refused rather than lifted blind. A stop that touches this
   * branch alone goes through the branch route; anything wider through the brand route.
   */
  async lift(scope: LocationScope, stop: StopInForce): Promise<void> {
    const atThisBranch =
      stop.scopeType === 'LOCATION' || (stop.scopeType === 'CHANNEL' && !!stop.locationId);
    const path = atThisBranch
      ? operationsPaths.inventoryStopAtLocation(scope, stop.stopId)
      : operationsPaths.inventoryStopAtBrand(scope, stop.stopId);
    await firstValueFrom(
      this.api.send<void, unknown>('DELETE', path, command(undefined as void), {
        expectedVersion: stop.version,
      }),
    );
  }

  /** What each connected marketplace has and has not been told. Tolerates a body without bindings. */
  async propagation(scope: LocationScope): Promise<readonly PropagationBinding[]> {
    const result = await firstValueFrom(
      this.api.get<PropagationResponse>(operationsPaths.inventoryMarketplacePropagation(scope)),
    );
    const bindings = result.value?.bindings;
    return Array.isArray(bindings) ? bindings : [];
  }
}

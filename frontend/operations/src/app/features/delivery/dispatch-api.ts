import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';

export interface ShipmentView {
  readonly shipmentId: string;
  /** `PENDING` | `ASSIGNED` | `PICKUP_PENDING` | `PICKED_UP` | `DELIVERED` | `CANCELLED`. */
  readonly status: string;
  /** `INTERNAL` | `PARTNER`. */
  readonly sourceType: string;
  readonly courierId?: string | null;
  readonly providerBindingId?: string | null;
  readonly version: number;
}

/**
 * Mirrors `DispatchController.PlanQueueResponse`. No customer name, address
 * or order total — see `dispatch-api.ts`'s own doc for why the frontend
 * joins this against the order board's own read by `orderId` instead.
 */
export interface PlanQueueResponse {
  readonly planId: string;
  readonly orderId: string;
  /** `PLANNED` | `WAITING_TO_SOURCE` | `SOURCING` | `BOOKING` | `RETRY_PENDING` | `SCHEDULED` | `ASSIGNED` | `IN_PROGRESS` | `COMPLETED` | `MANUAL_ACTION_REQUIRED` | `CANCELLED`. */
  readonly status: string;
  readonly distanceMeters?: number | null;
  readonly customerDeliveryFeeMinor: number;
  readonly currency: string;
  readonly sourceAt: string;
  readonly estimatedReadyAt: string;
  readonly promisedDeliveryStart?: string | null;
  readonly promisedDeliveryEnd?: string | null;
  readonly version: number;
  readonly shipment?: ShipmentView | null;
}

export interface DispatchResponse {
  readonly applied: boolean;
  readonly planStatus: string;
  readonly planVersion: number;
  readonly shipmentId?: string | null;
  /** Set only when `applied` is false: `STALE_VERSION` | `ALREADY_BEING_SOURCED` | `ALREADY_ASSIGNED` | `CANNOT_UNASSIGN`. */
  readonly reason?: string | null;
}

/**
 * Mirrors `DispatchController.ExceptionResponse`. Why a `MANUAL_ACTION_REQUIRED`
 * plan needs a human — no provider, an uncertain booking outcome, a promise
 * sourcing could not meet. Never a customer name, address or phone (same
 * reason `PlanQueueResponse` carries none — see this file's own doc).
 */
export interface ExceptionResponse {
  readonly exceptionId: string;
  readonly reasonCode: string;
  readonly severity: string;
  readonly status: string;
  readonly detail?: string | null;
  readonly raisedAt: string;
}

/**
 * The dispatch board (operations §3.1) — `DispatchController` (ADR 0014,
 * wave 30). The fleet rail reuses {@link CouriersApi.roster} rather than a
 * second endpoint — see that class's own doc.
 */
@Injectable({ providedIn: 'root' })
export class DispatchApi {
  private readonly api = inject(ApiClient);

  async queue(scope: LocationScope): Promise<readonly PlanQueueResponse[]> {
    const result = await firstValueFrom(
      this.api.get<readonly PlanQueueResponse[]>(operationsPaths.dispatchQueue(scope)),
    );
    return result.value ?? [];
  }

  async assign(
    scope: LocationScope,
    planId: string,
    courierId: string,
    expectedVersion: number,
    reasonCode: string,
  ): Promise<DispatchResponse> {
    return firstValueFrom(
      this.api.post<
        { courierId: string; expectedVersion: number; reasonCode: string },
        DispatchResponse
      >(
        operationsPaths.dispatchAssign(scope, planId),
        command({ courierId, expectedVersion, reasonCode }),
      ),
    );
  }

  async unassign(
    scope: LocationScope,
    planId: string,
    expectedShipmentVersion: number,
    reasonCode: string,
  ): Promise<DispatchResponse> {
    return firstValueFrom(
      this.api.post<{ expectedShipmentVersion: number; reasonCode: string }, DispatchResponse>(
        operationsPaths.dispatchUnassign(scope, planId),
        command({ expectedShipmentVersion, reasonCode }),
      ),
    );
  }

  /** Built by `DispatchController` and never called before this wave (operations gap map, row `3.1`). */
  async exceptions(scope: LocationScope, planId: string): Promise<readonly ExceptionResponse[]> {
    const result = await firstValueFrom(
      this.api.get<readonly ExceptionResponse[]>(operationsPaths.dispatchExceptions(scope, planId)),
    );
    return result.value ?? [];
  }
}

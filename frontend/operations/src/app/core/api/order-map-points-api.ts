import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from './api-client';
import { command } from './idempotency';
import { LocationScope, operationsPaths } from './operations-paths';

/** One delivery order as a pin: which order, how it stands, when it came in, where it is going. No person. */
export interface OrderMapPoint {
  readonly orderId: string;
  readonly publicOrderNumber: string;
  readonly status: string;
  readonly createdAt: string;
  readonly latitude: number;
  readonly longitude: number;
}

/** Mirrors `OperationsOrderMapPointController.MapPointRevealResponse`. */
export interface OrderMapPointsResponse {
  readonly windowFrom: string;
  readonly windowTo: string;
  readonly points: readonly OrderMapPoint[];
  /** Delivery orders of the day with no point to show: counted, so the map never under-reports in silence. */
  readonly withoutPoint: number;
  /** The day held more than the server's cap; these are the newest. */
  readonly truncated: boolean;
}

/** `OrderStatus.terminal()`: an order in one of these is over. */
export const TERMINAL_ORDER_STATUSES: ReadonlySet<string> = new Set([
  'PAYMENT_FAILED',
  'REJECTED',
  'EXPIRED',
  'COMPLETED',
  'CANCELLED',
]);

/**
 * Today's delivery orders of a branch as points on a map (rows `7.10a`, `3.1`; ADR 0145 decision 8).
 *
 * A point is a doorstep and a doorstep is a customer's home, which is why this is a `POST` that
 * states a purpose and leaves one audit fact for the whole call, and why it is never called on a
 * timer: opening the day's doorsteps is something a person does, once, and does again when they
 * want it fresher. The answer holds no name, no phone and no address text.
 */
@Injectable({ providedIn: 'root' })
export class OrderMapPointsApi {
  private readonly api = inject(ApiClient);

  /** @param purpose why the doorsteps are being opened; recorded by the server, never shown on the map */
  async reveal(scope: LocationScope, purpose: string): Promise<OrderMapPointsResponse> {
    return firstValueFrom(
      this.api.post<{ purpose: string }, OrderMapPointsResponse>(
        operationsPaths.orderMapPointReveals(scope),
        command({ purpose }),
      ),
    );
  }
}

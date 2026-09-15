import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';
import { OrderDeliveryResponse } from './order-detail';

/**
 * `GET .../orders/{orderId}/delivery` (`OrderDeliveryController`, wave P11,
 * gap map rows 1.2e/1.2n/2.1a) — the order-to-fulfilment seam. The order
 * detail pane's Money panel Доставка rows and its assign/unassign control
 * both read from here; assign/unassign themselves are `DispatchApi.assign`/
 * `unassign` against the `planId` and versions this read returns.
 */
@Injectable({ providedIn: 'root' })
export class OrderDeliveryApi {
  private readonly api = inject(ApiClient);

  /**
   * `null` for an order fulfilled some other way (pickup, dine-in, a
   * cancelled plan) — a `RESOURCE_NOT_FOUND` here is not an error the caller
   * needs to surface, unlike every other panel this pane loads.
   */
  async delivery(scope: LocationScope, orderId: string): Promise<OrderDeliveryResponse | null> {
    try {
      const result = await firstValueFrom(
        this.api.get<OrderDeliveryResponse>(operationsPaths.orderDelivery(scope, orderId)),
      );
      return result.value;
    } catch (error) {
      if (error instanceof ApiError && error.code === ApiErrorCode.RESOURCE_NOT_FOUND) {
        return null;
      }
      throw error;
    }
  }
}

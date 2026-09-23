import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';
import { ExternalBookResponse, ExternalQuoteResponse } from '../delivery/dispatch-api';
import { OrderDeliveryResponse } from './order-detail';

/**
 * Mirrors `OrderDeliveryController.ExternalCourierResponse` — never read
 * directly by a caller; {@link OrderDeliveryApi.requestExternalCourierQuote}/
 * {@link OrderDeliveryApi.decideExternalCourier} each adapt it into the same
 * `ExternalQuoteResponse`/`ExternalBookResponse` shape `DispatchApi`'s own
 * plan-keyed `externalQuote`/`externalBook` already return, so `
 * q-external-courier-dialog` (gap map row 1.2f) is reusable verbatim by
 * whichever screen resolved the plan by `orderId` instead of `planId`.
 */
interface ExternalCourierApiResponse {
  readonly phase: 'QUOTED' | 'BOOKED';
  readonly quoted?: {
    readonly priced: boolean;
    readonly quoteId?: string | null;
    readonly providerType?: string | null;
    readonly priceMinor?: number | null;
    readonly currency?: string | null;
    readonly customerDeliveryFeeMinor: number;
    readonly deltaMinor?: number | null;
    readonly failureCode?: string | null;
  } | null;
  readonly booked?: {
    readonly applied: boolean;
    readonly abandoned: boolean;
    readonly planVersion?: number | null;
    readonly shipmentId?: string | null;
    readonly reason?: string | null;
  } | null;
}

/**
 * `GET .../orders/{orderId}/delivery` (`OrderDeliveryController`, wave P11,
 * gap map rows 1.2e/1.2n/2.1a) — the order-to-fulfilment seam. The order
 * detail pane's Money panel Доставка rows and its assign/unassign control
 * both read from here; assign/unassign themselves are `DispatchApi.assign`/
 * `unassign` against the `planId` and versions this read returns.
 *
 * <p>{@link requestExternalCourierQuote}/{@link decideExternalCourier} are
 * `POST .../orders/{orderId}/external-courier` (gap map rows 1.2e/2.1c) —
 * the order-keyed action over the Millenium pattern's own quote/accept
 * services, shared by the order detail pane and the KDS pass.
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

  /**
   * A non-binding price from one partner, against this order's own customer
   * fee — never books anything (see `ManualExternalBookingService.quote`'s
   * own doc). Never carries `quoteId`, which is what puts the server in the
   * QUOTED phase.
   */
  async requestExternalCourierQuote(
    scope: LocationScope,
    orderId: string,
    bindingId: string,
  ): Promise<ExternalQuoteResponse> {
    const response = await firstValueFrom(
      this.api.post<{ bindingId: string }, ExternalCourierApiResponse>(
        operationsPaths.orderExternalCourier(scope, orderId),
        command({ bindingId }),
      ),
    );
    const quoted = response.quoted;
    return {
      priced: quoted?.priced ?? false,
      quoteId: quoted?.quoteId,
      bindingId,
      providerType: quoted?.providerType,
      priceMinor: quoted?.priceMinor,
      currency: quoted?.currency,
      customerDeliveryFeeMinor: quoted?.customerDeliveryFeeMinor ?? 0,
      deltaMinor: quoted?.deltaMinor,
      failureCode: quoted?.failureCode,
    };
  }

  /**
   * Accept or abandon a quote {@link requestExternalCourierQuote} already
   * recorded. Carrying `quoteId` and `decision` is what puts the server in
   * the BOOKED phase — the price booked is always the one the server's own
   * `fulfillment.delivery_quotes` row holds under `quoteId`, never a figure
   * this call supplies.
   */
  async decideExternalCourier(
    scope: LocationScope,
    orderId: string,
    bindingId: string,
    quoteId: string,
    decision: 'ACCEPT' | 'ABANDON',
    reasonCode: string,
  ): Promise<ExternalBookResponse> {
    const response = await firstValueFrom(
      this.api.post<
        { bindingId: string; quoteId: string; decision: string; reasonCode: string },
        ExternalCourierApiResponse
      >(
        operationsPaths.orderExternalCourier(scope, orderId),
        command({ bindingId, quoteId, decision, reasonCode }),
      ),
    );
    const booked = response.booked;
    return {
      applied: booked?.applied ?? false,
      abandoned: booked?.abandoned ?? false,
      planVersion: booked?.planVersion,
      shipmentId: booked?.shipmentId,
      reason: booked?.reason,
    };
  }
}

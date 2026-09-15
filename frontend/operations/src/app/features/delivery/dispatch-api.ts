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

/** Mirrors `DispatchController.ExternalPartnerResponse` — the picker behind «Вызвать курьера» (gap map row 1.2f). */
export interface ExternalPartnerResponse {
  readonly bindingId: string;
  readonly providerType: string;
  readonly supportsHold: boolean;
}

/**
 * Mirrors `DispatchController.ExternalQuoteResponse` — the Millenium
 * pattern's own price-against-the-customer's-fee read (gap map row 1.2f).
 * `deltaMinor` is `priceMinor - customerDeliveryFeeMinor`: positive is the
 * increase a dialog must confirm before anything books.
 */
export interface ExternalQuoteResponse {
  readonly priced: boolean;
  readonly quoteId?: string | null;
  readonly bindingId?: string | null;
  readonly providerType?: string | null;
  readonly priceMinor?: number | null;
  readonly currency?: string | null;
  readonly customerDeliveryFeeMinor: number;
  readonly deltaMinor?: number | null;
  readonly failureCode?: string | null;
}

/** Mirrors `DispatchController.ExternalBookResponse`. */
export interface ExternalBookResponse {
  readonly applied: boolean;
  readonly abandoned: boolean;
  readonly planVersion?: number | null;
  readonly shipmentId?: string | null;
  /** Set only on a refused ACCEPT: `QUOTE_EXPIRED` | `ALREADY_ASSIGNED` | `ALREADY_BEING_SOURCED` | a booking status name. */
  readonly reason?: string | null;
}

/**
 * Mirrors `DispatchController.ShipmentCancelResponse` (`Capability
 * .SHIPMENT_CANCEL`, gap map row 1.2g) — what the provider actually said
 * when this shipment was cancelled directly, independent of unassign.
 */
export interface ShipmentCancelResponse {
  readonly applied: boolean;
  /** Present only when `applied`: `INTERNAL_CANCELLED` | `PROVIDER_CANCELLED` | `PROVIDER_CANCELLED_CHARGEABLE` | `PROVIDER_UNCERTAIN` | `PROVIDER_FAILED`. */
  readonly outcome?: string | null;
  readonly providerType?: string | null;
  /** Present only when `!applied`: `STALE_VERSION` | `ALREADY_CANCELLED` | `ALREADY_DELIVERED`. */
  readonly conflictReason?: string | null;
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

  /** «Вызвать курьера»'s picker (gap map row 1.2f). Empty for a tenant running an in-house fleet only. */
  async externalPartners(
    scope: LocationScope,
    planId: string,
  ): Promise<readonly ExternalPartnerResponse[]> {
    const result = await firstValueFrom(
      this.api.get<readonly ExternalPartnerResponse[]>(
        operationsPaths.dispatchExternalPartners(scope, planId),
      ),
    );
    return result.value ?? [];
  }

  /**
   * The Millenium pattern's own non-binding quote (gap map row 1.2f). Never
   * books anything — the partner is only asked to create a live delivery once
   * the operator calls {@link externalBook} with `ACCEPT`.
   */
  async externalQuote(
    scope: LocationScope,
    planId: string,
    bindingId: string,
  ): Promise<ExternalQuoteResponse> {
    return firstValueFrom(
      this.api.post<{ bindingId: string }, ExternalQuoteResponse>(
        operationsPaths.dispatchExternalQuote(scope, planId),
        command({ bindingId }),
      ),
    );
  }

  /**
   * Accept or abandon a quote {@link externalQuote} already recorded.
   * `quoteId` is what makes a price increase impossible to accept implicitly:
   * the server books whatever price it persisted under that id, never one
   * this call could carry.
   */
  async externalBook(
    scope: LocationScope,
    planId: string,
    bindingId: string,
    quoteId: string,
    decision: 'ACCEPT' | 'ABANDON',
    reasonCode: string,
  ): Promise<ExternalBookResponse> {
    return firstValueFrom(
      this.api.post<
        { bindingId: string; quoteId: string; decision: string; reasonCode: string },
        ExternalBookResponse
      >(
        operationsPaths.dispatchExternalBook(scope, planId),
        command({ bindingId, quoteId, decision, reasonCode }),
      ),
    );
  }

  /**
   * The dedicated, provider-notifying shipment cancel (`Capability
   * .SHIPMENT_CANCEL`, gap map row 1.2g) — distinct from {@link unassign},
   * which never tells a PARTNER shipment's provider anything.
   */
  async cancelShipment(
    scope: LocationScope,
    shipmentId: string,
    expectedVersion: number,
    reasonCode: string,
  ): Promise<ShipmentCancelResponse> {
    return firstValueFrom(
      this.api.post<{ expectedVersion: number; reasonCode: string }, ShipmentCancelResponse>(
        operationsPaths.dispatchShipmentCancel(scope, shipmentId),
        command({ expectedVersion, reasonCode }),
      ),
    );
  }
}

import { Injectable, inject } from '@angular/core';

import { ApiClient } from '../api/api-client';
import { APP_CONFIG } from '../config/app-config';
import type {
  CurrentDuty,
  DutySession,
  NoticeAcknowledgement,
  ObservationBatchRequest,
  ObservationBatchResult,
} from './courier-duty';
import type { CourierProfile } from './courier-profile';
import type { BranchRef, ClosedShift, OpenedShift } from './courier-shift';
import type {
  AcceptOfferResult,
  AdvanceStep,
  CourierDelivery,
  CourierOffer,
  CustomerLocation,
  DeclineOfferResult,
  GateStepResult,
  PositionBody,
} from './courier-work';

/**
 * The courier's own surface: `/api/v1/courier/tenants/{tenantId}/...`. Every call here resolves
 * the courier from the signed token and never from a parameter, so there is no id in this class
 * that could name somebody else.
 *
 * The mutating calls take an optional `key`: the `Idempotency-Key` of the courier's intent
 * (`IntentKeys`), supplied by whoever is retrying the same tap. The observation batch takes none;
 * it is the one endpoint ADR 0045 exempts, idempotent on its own natural key.
 */
@Injectable({ providedIn: 'root' })
export class CourierApi {
  private readonly api = inject(ApiClient);
  private readonly config = inject(APP_CONFIG);

  me(): Promise<CourierProfile> {
    return this.api.get<CourierProfile>(`${this.base()}/me`);
  }

  /** The tenant prefix every courier call shares. */
  base(): string {
    return `/courier/tenants/${this.config.tenantId}`;
  }

  /** The branch prefix: brand and location are in the path, from the courier's own record. */
  branch(where: BranchRef): string {
    return `${this.base()}/brands/${where.brandId}/locations/${where.locationId}`;
  }

  // ------------------------------------------------------------------- shift

  /** `point` is `"latitude,longitude"` when the handset has a fix, which the platform stores encrypted. */
  openShift(where: BranchRef, point: string | null, key?: string): Promise<OpenedShift> {
    return this.api.post<OpenedShift>(`${this.branch(where)}/shifts`, {
      body: { point, currency: this.config.currency },
      idempotencyKey: key,
    });
  }

  closeShift(
    where: BranchRef,
    shiftId: string,
    point: string | null,
    key?: string,
  ): Promise<ClosedShift> {
    return this.api.post<ClosedShift>(`${this.branch(where)}/shifts/${shiftId}/close`, {
      body: { point, currency: this.config.currency },
      idempotencyKey: key,
    });
  }

  startBreak(where: BranchRef, shiftId: string, key?: string): Promise<void> {
    return this.api.post<void>(`${this.branch(where)}/shifts/${shiftId}/breaks`, {
      idempotencyKey: key,
    });
  }

  endBreak(where: BranchRef, shiftId: string, key?: string): Promise<void> {
    return this.api.post<void>(`${this.branch(where)}/shifts/${shiftId}/breaks/end`, {
      idempotencyKey: key,
    });
  }

  // -------------------------------------------------------------------- duty

  currentDuty(where: BranchRef): Promise<CurrentDuty> {
    return this.api.get<CurrentDuty>(`${this.branch(where)}/duty-session`);
  }

  openDuty(where: BranchRef, deviceId: string, key?: string): Promise<DutySession> {
    return this.api.post<DutySession>(`${this.branch(where)}/duty-sessions`, {
      body: { deviceId },
      idempotencyKey: key,
    });
  }

  closeDuty(where: BranchRef, sessionId: string, key?: string): Promise<void> {
    return this.api.post<void>(`${this.branch(where)}/duty-sessions/${sessionId}/closures`, {
      idempotencyKey: key,
    });
  }

  /** A batch of buffered readings. The body is a position: it is never logged, and never in a URL. */
  postObservations(
    where: BranchRef,
    batch: ObservationBatchRequest,
  ): Promise<ObservationBatchResult> {
    return this.api.post<ObservationBatchResult, ObservationBatchRequest>(
      `${this.branch(where)}/telemetry/observations`,
      { body: batch },
    );
  }

  /** Records that this courier saw this version of the transparency notice (a tenant-level fact). */
  acknowledgeNotice(version: string, key?: string): Promise<NoticeAcknowledgement> {
    return this.api.post<NoticeAcknowledgement>(
      `${this.base()}/telemetry-notice-acknowledgements`,
      {
        body: { version },
        idempotencyKey: key,
      },
    );
  }

  // ------------------------------------------------------------------ offers

  offers(where: BranchRef): Promise<CourierOffer[]> {
    return this.api.get<CourierOffer[]>(`${this.branch(where)}/offers`);
  }

  acceptOffer(
    where: BranchRef,
    offer: CourierOffer,
    position: PositionBody | null,
    key?: string,
  ): Promise<AcceptOfferResult> {
    return this.api.post<AcceptOfferResult>(
      `${this.branch(where)}/offers/${offer.offerId}/accept`,
      {
        body: position === null ? {} : { position },
        expectedVersion: offer.version,
        idempotencyKey: key,
      },
    );
  }

  declineOffer(where: BranchRef, offer: CourierOffer, key?: string): Promise<DeclineOfferResult> {
    return this.api.post<DeclineOfferResult>(
      `${this.branch(where)}/offers/${offer.offerId}/decline`,
      { expectedVersion: offer.version, idempotencyKey: key },
    );
  }

  /** Audited: the platform records that this courier opened this address, never the address. */
  revealOfferLocation(where: BranchRef, offerId: string, key?: string): Promise<CustomerLocation> {
    return this.api.post<CustomerLocation>(
      `${this.branch(where)}/offers/${offerId}/customer-location-reveals`,
      { idempotencyKey: key },
    );
  }

  // -------------------------------------------------------------- deliveries

  deliveries(where: BranchRef): Promise<CourierDelivery[]> {
    return this.api.get<CourierDelivery[]>(`${this.branch(where)}/deliveries`);
  }

  delivery(where: BranchRef, shipmentId: string): Promise<CourierDelivery> {
    return this.api.get<CourierDelivery>(`${this.branch(where)}/deliveries/${shipmentId}`);
  }

  /** Arrival at the pickup. Pickup and handover are their own calls: {@link pickUp}, {@link handOver}. */
  advance(
    where: BranchRef,
    delivery: CourierDelivery,
    step: AdvanceStep,
    position: PositionBody | null,
    key?: string,
  ): Promise<CourierDelivery> {
    return this.api.post<CourierDelivery>(
      `${this.branch(where)}/deliveries/${delivery.shipmentId}/advance`,
      {
        body: position === null ? { step } : { step, position },
        expectedVersion: delivery.version,
        idempotencyKey: key,
      },
    );
  }

  /**
   * The pickup gate (cross-wave contract with the delivery-maps wave, which builds the server
   * side). HARD mode refuses a failed check with `409 GATE_FAILED`; SOFT mode succeeds and the
   * answer carries `checks`.
   */
  pickUp(
    where: BranchRef,
    delivery: CourierDelivery,
    position: PositionBody | null,
    key?: string,
  ): Promise<GateStepResult> {
    return this.gateStep(where, delivery, 'pickup', position, key);
  }

  /** The handover gate, the same contract as {@link pickUp}. */
  handOver(
    where: BranchRef,
    delivery: CourierDelivery,
    position: PositionBody | null,
    key?: string,
  ): Promise<GateStepResult> {
    return this.gateStep(where, delivery, 'handover', position, key);
  }

  /** The cash the courier collected at the door, in minor units; the platform compares it to what is due. */
  confirmPayment(
    where: BranchRef,
    delivery: CourierDelivery,
    collectedMinor: number,
    key?: string,
  ): Promise<CourierDelivery> {
    return this.api.post<CourierDelivery>(
      `${this.branch(where)}/deliveries/${delivery.shipmentId}/payment-confirmation`,
      { body: { collectedMinor }, expectedVersion: delivery.version, idempotencyKey: key },
    );
  }

  revealDeliveryLocation(
    where: BranchRef,
    shipmentId: string,
    key?: string,
  ): Promise<CustomerLocation> {
    return this.api.post<CustomerLocation>(
      `${this.branch(where)}/deliveries/${shipmentId}/customer-location-reveals`,
      { idempotencyKey: key },
    );
  }

  private gateStep(
    where: BranchRef,
    delivery: CourierDelivery,
    step: 'pickup' | 'handover',
    position: PositionBody | null,
    key?: string,
  ): Promise<GateStepResult> {
    return this.api.post<GateStepResult>(
      `${this.branch(where)}/deliveries/${delivery.shipmentId}/${step}`,
      {
        body: position === null ? {} : { position },
        expectedVersion: delivery.version,
        idempotencyKey: key,
      },
    );
  }
}

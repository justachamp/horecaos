/**
 * Offers and deliveries: the courier's work (`CourierDeliveryController`).
 *
 * Money is integer minor units plus a currency, never a float. The customer's name and telephone
 * number are in no type here -- the platform sends neither -- and the customer's door exists only
 * as {@link CustomerLocation}, returned by an audited reveal and held in memory for as long as the
 * courier needs it.
 */
export interface CourierPickup {
  readonly name: string;
  readonly addressLine: string;
  readonly landmark: string | null;
  readonly latitude: number | null;
  readonly longitude: number | null;
}

export interface CourierOffer {
  readonly offerId: string;
  /** The offer's version: the `If-Match` of accepting or declining it. */
  readonly version: number;
  readonly orderReference: string;
  readonly offeredAt: string;
  /** When the offer lapses; polled, not pushed, so the clock below is what the courier sees. */
  readonly expiresAt: string;
  readonly currency: string;
  readonly orderTotalMinor: number;
  readonly prepaid: boolean;
  readonly kitchenReady: boolean;
  readonly destinationLabel: string | null;
  readonly distanceMeters: number | null;
  readonly pickupWindowStart: string;
  readonly pickupWindowEnd: string;
  readonly promisedDeliveryEnd: string | null;
  readonly pickup: CourierPickup;
  /** The tenant reveals the customer's address before acceptance, so the offer's own reveal will answer. */
  readonly customerLocationRevealable: boolean;
  /** ADR 0170: `DIRECT`, or `RUN_INCREMENT` when the order joins a run the courier already carries. */
  readonly payBasis: string;
  readonly quotedEarningMinor: number | null;
  readonly quoteCurrency: string | null;
  readonly quotedDistanceMeters: number | null;
}

export type DeliveryStatus =
  'ASSIGNED' | 'PICKUP_PENDING' | 'PICKED_UP' | 'DELIVERED' | 'CANCELLED';

export interface CourierDelivery {
  readonly shipmentId: string;
  /** The delivery's version: the `If-Match` of every step. */
  readonly version: number;
  readonly status: DeliveryStatus;
  readonly orderReference: string;
  readonly assignedAt: string;
  readonly pickedUpAt: string | null;
  readonly deliveredAt: string | null;
  readonly paymentConfirmedAt: string | null;
  readonly paymentConfirmedMinor: number | null;
  readonly currency: string;
  readonly orderTotalMinor: number;
  /** What the courier collects at the door; zero for an order the platform already holds the money for. */
  readonly cashDueMinor: number;
  readonly prepaid: boolean;
  readonly kitchenReady: boolean;
  readonly destinationLabel: string | null;
  readonly distanceMeters: number | null;
  readonly pickupWindowStart: string;
  readonly pickupWindowEnd: string;
  readonly promisedDeliveryEnd: string | null;
  readonly pickup: CourierPickup;
  /** The tenant requires the cash to be confirmed before this delivery can be marked delivered. */
  readonly paymentConfirmationRequired: boolean;
}

export interface AcceptOfferResult {
  readonly outcome: 'ACCEPTED' | 'NO_LONGER_AVAILABLE';
  readonly delivery: CourierDelivery | null;
}

export interface DeclineOfferResult {
  readonly outcome: 'DECLINED' | 'NO_LONGER_AVAILABLE';
}

/** Personal data throughout: held in a component signal, shown, and dropped when the screen goes. */
export interface CustomerLocation {
  readonly latitude: number;
  readonly longitude: number;
  readonly addressLine: string;
  readonly entrance: string | null;
  readonly floor: string | null;
  readonly apartment: string | null;
  readonly instructions: string | null;
}

/** The courier's own position, sent only so a gate can measure it; measured and discarded by the platform. */
export interface PositionBody {
  readonly latitude: number;
  readonly longitude: number;
  readonly accuracyMeters: number;
}

/**
 * The steps `POST .../advance` still takes. Pickup and handover have their own endpoints and their
 * own answer (cross-wave contract, see {@link GateStepResult}).
 */
export type AdvanceStep = 'PICKUP_PENDING';

/**
 * The checks a pickup or handover ran. In HARD mode a failed check is `409 GATE_FAILED` and no
 * result exists; in SOFT mode the call succeeds and this is where the courier learns which checks
 * the platform could not confirm (the persisted outcome feeds `GEO_UNVERIFIED_RATE`).
 */
export type GateCheckKind = 'GEO' | 'PHOTO' | 'PAYMENT';
export type GateCheckOutcome = 'PASSED' | 'FAILED' | 'SKIPPED';

export interface GateCheck {
  readonly kind: GateCheckKind;
  readonly outcome: GateCheckOutcome;
}

/** A delivery after pickup or handover, with `checks` when the platform reports them. */
export type GateStepResult = CourierDelivery & { readonly checks?: readonly GateCheck[] };

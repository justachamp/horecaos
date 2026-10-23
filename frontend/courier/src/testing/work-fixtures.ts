import type { CourierDelivery, CourierOffer } from '../app/core/courier/courier-work';

/** An offer with a pickup, a quote and a minute to live. Override what a case is about. */
export function offer(overrides: Partial<CourierOffer> = {}): CourierOffer {
  return {
    offerId: '018fe100-4000-7000-8000-0000000000a1',
    version: 1,
    orderReference: 'A-1042',
    offeredAt: '2026-10-14T05:00:00Z',
    expiresAt: '2026-10-14T05:01:00Z',
    currency: 'UZS',
    orderTotalMinor: 12_500_000,
    prepaid: false,
    kitchenReady: true,
    destinationLabel: 'Mirzo Ulugbek',
    distanceMeters: 3200,
    pickupWindowStart: '2026-10-14T05:05:00Z',
    pickupWindowEnd: '2026-10-14T05:20:00Z',
    promisedDeliveryEnd: '2026-10-14T05:50:00Z',
    pickup: {
      name: 'Chilonzor kitchen',
      addressLine: 'Bunyodkor 12',
      landmark: 'Next to the metro',
      latitude: 41.2753,
      longitude: 69.2037,
    },
    customerLocationRevealable: false,
    payBasis: 'DIRECT',
    quotedEarningMinor: 1_800_000,
    quoteCurrency: 'UZS',
    quotedDistanceMeters: 3200,
    ...overrides,
  };
}

/** A delivery just assigned: cash due, nothing confirmed. Override what a case is about. */
export function delivery(overrides: Partial<CourierDelivery> = {}): CourierDelivery {
  return {
    shipmentId: '018fe100-4000-7000-8000-0000000000d1',
    version: 1,
    status: 'ASSIGNED',
    orderReference: 'A-1042',
    assignedAt: '2026-10-14T05:00:30Z',
    pickedUpAt: null,
    deliveredAt: null,
    paymentConfirmedAt: null,
    paymentConfirmedMinor: null,
    currency: 'UZS',
    orderTotalMinor: 12_500_000,
    cashDueMinor: 12_500_000,
    prepaid: false,
    kitchenReady: true,
    destinationLabel: 'Mirzo Ulugbek',
    distanceMeters: 3200,
    pickupWindowStart: '2026-10-14T05:05:00Z',
    pickupWindowEnd: '2026-10-14T05:20:00Z',
    promisedDeliveryEnd: '2026-10-14T05:50:00Z',
    pickup: {
      name: 'Chilonzor kitchen',
      addressLine: 'Bunyodkor 12',
      landmark: null,
      latitude: 41.2753,
      longitude: 69.2037,
    },
    paymentConfirmationRequired: false,
    ...overrides,
  };
}

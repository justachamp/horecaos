import { describe, expect, it } from 'vitest';

import { deliveryLifecycleSteps } from './delivery-lifecycle-steps';
import { OrderDeliveryShipment } from './order-detail';

function shipment(overrides: Partial<OrderDeliveryShipment> = {}): OrderDeliveryShipment {
  return {
    shipmentId: 'shipment-1',
    status: 'ASSIGNED',
    sourceType: 'INTERNAL',
    version: 1,
    ...overrides,
  };
}

const label = (stage: string): string => stage;
const elapsed = (from: string, to: string): string => {
  const minutes = Math.round((new Date(to).getTime() - new Date(from).getTime()) / 60_000);
  return `${minutes}min`;
};

describe('deliveryLifecycleSteps', () => {
  it('answers [] before a shipment exists at all', () => {
    expect(deliveryLifecycleSteps(null, label, elapsed)).toEqual([]);
  });

  it('marks the first stage with no timestamp current, and the rest upcoming', () => {
    const steps = deliveryLifecycleSteps(shipment(), label, elapsed);

    expect(steps.map((s) => [s.id, s.state])).toEqual([
      ['ASSIGNED', 'current'],
      ['PICKED_UP', 'upcoming'],
      ['DELIVERED', 'upcoming'],
    ]);
  });

  it('marks every reached stage complete, appending the elapsed time since the previous one to its own label', () => {
    const steps = deliveryLifecycleSteps(
      shipment({
        status: 'DELIVERED',
        assignedAt: '2026-09-12T08:00:00Z',
        pickedUpAt: '2026-09-12T08:08:00Z',
        deliveredAt: '2026-09-12T08:20:00Z',
      }),
      label,
      elapsed,
    );

    expect(steps.map((s) => [s.id, s.state, s.label])).toEqual([
      ['ASSIGNED', 'complete', 'ASSIGNED'],
      ['PICKED_UP', 'complete', 'PICKED_UP · 8min'],
      ['DELIVERED', 'complete', 'DELIVERED · 12min'],
    ]);
  });

  it('marks every stage upcoming, with no current one, once the shipment is cancelled', () => {
    const steps = deliveryLifecycleSteps(
      shipment({ status: 'CANCELLED', assignedAt: '2026-09-12T08:00:00Z' }),
      label,
      elapsed,
    );

    // ASSIGNED is complete (it was reached); PICKED_UP and DELIVERED never
    // happened and the shipment will not reach them now, so neither is
    // marked "current" the way an active shipment's next stage would be.
    expect(steps.map((s) => s.state)).toEqual(['complete', 'upcoming', 'upcoming']);
  });

  it('marks the next unreached stage current for a shipment still in flight, the rest upcoming', () => {
    const steps = deliveryLifecycleSteps(
      shipment({
        status: 'PICKED_UP',
        assignedAt: '2026-09-12T08:00:00Z',
        pickedUpAt: '2026-09-12T08:08:00Z',
      }),
      label,
      elapsed,
    );

    expect(steps.map((s) => s.state)).toEqual(['complete', 'complete', 'current']);
  });
});

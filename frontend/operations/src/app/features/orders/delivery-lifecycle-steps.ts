import { StepItem } from '../../shared/ui/steps';
import { OrderDeliveryShipment } from './order-detail';

const HAPPY_PATH: readonly (readonly [
  id: string,
  at: (s: OrderDeliveryShipment) => string | null | undefined,
])[] = [
  ['ASSIGNED', (s) => s.assignedAt],
  ['PICKED_UP', (s) => s.pickedUpAt],
  ['DELIVERED', (s) => s.deliveredAt],
];

/**
 * The order detail's delivery lane (row `1.2b`) — `fulfillment.shipments`'
 * own three V0054 custody timestamps as a position on a known sequence, the
 * same idea {@link import('./kitchen-lifecycle-steps').kitchenLifecycleSteps}
 * renders for the production lane. No events table backs this one; the three
 * timestamps on the shipment itself are the whole of what ADR 0014 records.
 *
 * **Elapsed durations are between stages**, exactly as the production lane's
 * own doc states, and for the same reason.
 *
 * `[]` before a shipment exists at all — an order still waiting to be
 * assigned has nothing on this lane yet, which the pane's own empty state
 * covers; the assign/unassign control (row `1.2e`) is what changes that.
 */
export function deliveryLifecycleSteps(
  shipment: OrderDeliveryShipment | null,
  stageLabel: (stage: string) => string,
  formatElapsed: (fromIso: string, toIso: string) => string,
): readonly StepItem[] {
  if (!shipment) {
    return [];
  }

  const cancelled = shipment.status === 'CANCELLED';
  let previousAt: string | null = null;
  let currentAssigned = false;

  return HAPPY_PATH.map(([id, at]) => {
    const reachedAt = at(shipment);
    let label = stageLabel(id);
    let state: StepItem['state'];
    if (reachedAt) {
      state = 'complete';
      if (previousAt) {
        label = `${label} · ${formatElapsed(previousAt, reachedAt)}`;
      }
      previousAt = reachedAt;
    } else if (!currentAssigned && !cancelled) {
      state = 'current';
      currentAssigned = true;
    } else {
      state = 'upcoming';
    }
    return { id, label, state };
  });
}

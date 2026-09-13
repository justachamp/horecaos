import { StepItem } from '../../shared/ui/steps';
import { OrderTimelineEntry } from './order-detail';

/**
 * The six of the twelve canonical statuses (`order-status.ts`'s own
 * `ORDER_STATUSES`) a normally-fulfilled order passes through, in order.
 * The other six — `PAYMENT_AUTHORIZING`, `AWAITING_APPROVAL`,
 * `PAYMENT_FAILED`, `REJECTED`, `EXPIRED`, `CANCELLED` — are exceptions from
 * this path, never a position on it.
 */
const HAPPY_PATH: readonly string[] = [
  'RECEIVED',
  'CONFIRMED',
  'PREPARING',
  'READY',
  'FULFILLING',
  'COMPLETED',
];

/**
 * The order lifecycle rail (row `X.33`) — "an operator infers where an order
 * sits in its lifecycle by reading a status word and scanning past events,
 * instead of seeing the position." Derived entirely from data
 * `OrderDetailPane` already holds: the order's current status and the §3.10
 * timeline it already fetches. No new endpoint.
 *
 * **A status on the happy path is a position on the rail.** Six of the
 * twelve statuses are drawn in {@link HAPPY_PATH} order, with everything
 * before the current one `complete`, the current one itself `current`, and
 * the rest `upcoming`.
 *
 * **An exception status is where the rail stopped, not where it broke.** A
 * rejection, an expiry or a cancellation leaves the happy path entirely — the
 * rail draws every happy-path step the order is known to have reached
 * (scanning the timeline's own `toStatus` values, not re-deriving them) as
 * `complete`, then appends the exception itself as a final `current` step in
 * the `danger` tone, so the rail reads "reached READY, then CANCELLED" rather
 * than silently resetting to the start or freezing on a step that never
 * happened. An order with no happy-path status in its timeline yet (the
 * timeline failed to load, or genuinely has none) is treated as having
 * reached `RECEIVED` — the one status `OrderStateMachine` guarantees every
 * order starts at.
 */
export function orderLifecycleSteps(
  currentStatus: string,
  timeline: readonly OrderTimelineEntry[],
  statusLabel: (status: string) => string,
): readonly StepItem[] {
  const happyIndex = HAPPY_PATH.indexOf(currentStatus);
  if (happyIndex >= 0) {
    return HAPPY_PATH.map((status, index) => ({
      id: status,
      label: statusLabel(status),
      state: index < happyIndex ? 'complete' : index === happyIndex ? 'current' : 'upcoming',
    }));
  }

  const reached = new Set(timeline.map((entry) => entry.toStatus));
  let reachedIndex = 0;
  for (let index = HAPPY_PATH.length - 1; index >= 0; index -= 1) {
    if (reached.has(HAPPY_PATH[index])) {
      reachedIndex = index;
      break;
    }
  }

  const steps: StepItem[] = HAPPY_PATH.slice(0, reachedIndex + 1).map((status) => ({
    id: status,
    label: statusLabel(status),
    state: 'complete',
  }));
  steps.push({
    id: currentStatus,
    label: statusLabel(currentStatus),
    state: 'current',
    tone: 'danger',
  });
  return steps;
}

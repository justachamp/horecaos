import { StepItem } from '../../shared/ui/steps';
import { KitchenEventResponse } from '../kitchen/kitchen-api';

/**
 * `TicketStatus`'s own happy path (`kitchen/domain/TicketStatus.java`). `HELD`
 * is the buffer, before this lane has anything to show; `VOIDED` is the
 * exception, exactly as `CANCELLED` is for {@link
 * import('./order-lifecycle-steps').orderLifecycleSteps}'s own happy path.
 */
const HAPPY_PATH: readonly string[] = ['FIRED', 'IN_PRODUCTION', 'READY', 'HANDED_OVER'];

/**
 * The order detail's production lane (row `1.2b`) — the same "position on a
 * known sequence" idea {@link import('./order-lifecycle-steps').orderLifecycleSteps}
 * already renders for the commercial lane, over `kitchen.ticket_events`
 * instead of `ordering.order_state_history`. Ticket-level events only
 * (`ticketItemId` absent) — a per-line station advance belongs to a station,
 * not to the ticket's own four stages.
 *
 * **Elapsed durations are between stages, not from ticket creation.** The
 * first reached stage carries no duration of its own; each stage after it
 * carries the time since the previous one was reached, appended to that
 * stage's own label.
 *
 * `[]` while the ticket has not loaded (`ticketStatus` null) — the pane
 * renders its own loading/empty state around this, the same split {@link
 * import('./order-lifecycle-steps').orderLifecycleSteps} draws for the order
 * lifecycle rail.
 */
export function kitchenLifecycleSteps(
  ticketStatus: string | null,
  events: readonly KitchenEventResponse[],
  stageLabel: (stage: string) => string,
  formatElapsed: (fromIso: string, toIso: string) => string,
): readonly StepItem[] {
  if (!ticketStatus) {
    return [];
  }

  const reachedAt = new Map<string, string>();
  for (const event of events) {
    if (!event.ticketItemId && !reachedAt.has(event.toStatus)) {
      reachedAt.set(event.toStatus, event.occurredAt);
    }
  }

  const withElapsedLabel = (stage: string, previousAt: string | null): string => {
    const at = reachedAt.get(stage);
    const label = stageLabel(stage);
    return at && previousAt ? `${label} · ${formatElapsed(previousAt, at)}` : label;
  };

  const happyIndex = HAPPY_PATH.indexOf(ticketStatus);
  if (happyIndex >= 0) {
    let previousAt: string | null = null;
    return HAPPY_PATH.map((stage, index) => {
      const label = withElapsedLabel(stage, previousAt);
      if (reachedAt.has(stage)) {
        previousAt = reachedAt.get(stage) ?? null;
      }
      return {
        id: stage,
        label,
        state: index < happyIndex ? 'complete' : index === happyIndex ? 'current' : 'upcoming',
      };
    });
  }

  // HELD (nothing reached yet) or VOIDED (an exception, same treatment as a
  // rejected/cancelled order's own timeline rail).
  let reachedIndex = -1;
  for (let index = HAPPY_PATH.length - 1; index >= 0; index -= 1) {
    if (reachedAt.has(HAPPY_PATH[index])) {
      reachedIndex = index;
      break;
    }
  }

  const steps: StepItem[] = [];
  let previousAt: string | null = null;
  for (let index = 0; index <= reachedIndex; index += 1) {
    const stage = HAPPY_PATH[index];
    steps.push({ id: stage, label: withElapsedLabel(stage, previousAt), state: 'complete' });
    previousAt = reachedAt.get(stage) ?? previousAt;
  }
  if (ticketStatus !== 'HELD' || reachedIndex >= 0) {
    steps.push({
      id: ticketStatus,
      label: stageLabel(ticketStatus),
      state: 'current',
      tone: ticketStatus === 'VOIDED' ? 'danger' : undefined,
    });
  }
  return steps;
}

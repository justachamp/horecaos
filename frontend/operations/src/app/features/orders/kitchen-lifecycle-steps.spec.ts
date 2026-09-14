import { describe, expect, it } from 'vitest';

import { kitchenLifecycleSteps } from './kitchen-lifecycle-steps';
import { KitchenEventResponse } from '../kitchen/kitchen-api';

function event(overrides: Partial<KitchenEventResponse>): KitchenEventResponse {
  return {
    id: 'e1',
    toStatus: 'FIRED',
    trigger: 'SYSTEM',
    actorType: 'SYSTEM',
    actorId: 'system',
    occurredAt: '2026-09-12T08:00:00Z',
    ...overrides,
  };
}

const label = (stage: string): string => stage;
const elapsed = (from: string, to: string): string => {
  const minutes = Math.round((new Date(to).getTime() - new Date(from).getTime()) / 60_000);
  return `${minutes}min`;
};

describe('kitchenLifecycleSteps', () => {
  it('answers [] before the ticket has loaded', () => {
    expect(kitchenLifecycleSteps(null, [], label, elapsed)).toEqual([]);
  });

  it('marks every happy-path stage before the current one complete, the current one current, the rest upcoming', () => {
    const steps = kitchenLifecycleSteps('IN_PRODUCTION', [], label, elapsed);

    expect(steps.map((s) => [s.id, s.state])).toEqual([
      ['FIRED', 'complete'],
      ['IN_PRODUCTION', 'current'],
      ['READY', 'upcoming'],
      ['HANDED_OVER', 'upcoming'],
    ]);
  });

  it('appends the elapsed time between consecutive reached stages to the later stage’s own label, never to the first', () => {
    const events = [
      event({ id: 'e1', toStatus: 'FIRED', occurredAt: '2026-09-12T08:00:00Z' }),
      event({
        id: 'e2',
        fromStatus: 'FIRED',
        toStatus: 'IN_PRODUCTION',
        occurredAt: '2026-09-12T08:05:00Z',
      }),
      event({
        id: 'e3',
        fromStatus: 'IN_PRODUCTION',
        toStatus: 'READY',
        occurredAt: '2026-09-12T08:15:00Z',
      }),
    ];

    const steps = kitchenLifecycleSteps('READY', events, label, elapsed);

    expect(steps.map((s) => s.label)).toEqual([
      'FIRED',
      'IN_PRODUCTION · 5min',
      'READY · 10min',
      // Not yet reached, so no elapsed time to show — same rule
      // `orderLifecycleSteps` follows for an upcoming happy-path status.
      'HANDED_OVER',
    ]);
  });

  it('ignores a per-line station advance (ticketItemId set) when building the ticket-level stages', () => {
    const events = [
      event({ id: 'e1', toStatus: 'FIRED', occurredAt: '2026-09-12T08:00:00Z' }),
      // A station advance for one line — must not be read as the ticket
      // itself reaching IN_PRODUCTION.
      event({
        id: 'e2',
        ticketItemId: 'item-1',
        fromStatus: 'QUEUED',
        toStatus: 'STARTED',
        occurredAt: '2026-09-12T08:01:00Z',
      }),
    ];

    const steps = kitchenLifecycleSteps('FIRED', events, label, elapsed);

    expect(steps.map((s) => s.state)).toEqual(['current', 'upcoming', 'upcoming', 'upcoming']);
  });

  it('answers [] for a buffered (HELD) ticket that has reached nothing yet', () => {
    expect(kitchenLifecycleSteps('HELD', [], label, elapsed)).toEqual([]);
  });

  it('appends VOIDED as a final danger-toned step, after every stage the ticket actually reached', () => {
    const events = [event({ id: 'e1', toStatus: 'FIRED', occurredAt: '2026-09-12T08:00:00Z' })];

    const steps = kitchenLifecycleSteps('VOIDED', events, label, elapsed);

    expect(steps.map((s) => s.id)).toEqual(['FIRED', 'VOIDED']);
    expect(steps[0].state).toBe('complete');
    const last = steps.at(-1)!;
    expect(last.state).toBe('current');
    expect(last.tone).toBe('danger');
  });
});

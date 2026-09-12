import { describe, expect, it } from 'vitest';

import { orderLifecycleSteps } from './order-lifecycle-steps';
import { OrderTimelineEntry } from './order-detail';

function entry(overrides: Partial<OrderTimelineEntry>): OrderTimelineEntry {
  return {
    sequence: 1,
    fromStatus: 'RECEIVED',
    toStatus: 'CONFIRMED',
    trigger: 'OPERATIONS_ACTION',
    actorType: 'USER',
    occurredAt: '2026-09-12T08:00:00Z',
    ...overrides,
  };
}

const label = (status: string): string => status;

describe('orderLifecycleSteps', () => {
  it('marks every happy-path status before the current one complete, the current one current, the rest upcoming', () => {
    const steps = orderLifecycleSteps('PREPARING', [], label);

    expect(steps.map((s) => [s.id, s.state])).toEqual([
      ['RECEIVED', 'complete'],
      ['CONFIRMED', 'complete'],
      ['PREPARING', 'current'],
      ['READY', 'upcoming'],
      ['FULFILLING', 'upcoming'],
      ['COMPLETED', 'upcoming'],
    ]);
  });

  it('marks the first happy-path status current with nothing yet complete', () => {
    const steps = orderLifecycleSteps('RECEIVED', [], label);

    expect(steps[0].state).toBe('current');
    expect(steps.slice(1).every((s) => s.state === 'upcoming')).toBe(true);
  });

  it('marks every step complete when the order finished', () => {
    const steps = orderLifecycleSteps('COMPLETED', [], label);

    expect(steps.slice(0, -1).every((s) => s.state === 'complete')).toBe(true);
    expect(steps.at(-1)?.state).toBe('current');
  });

  it('appends a cancellation as a final danger-toned step, after every happy-path status the order actually reached', () => {
    const timeline = [
      entry({ sequence: 1, fromStatus: 'RECEIVED', toStatus: 'CONFIRMED' }),
      entry({ sequence: 2, fromStatus: 'CONFIRMED', toStatus: 'PREPARING' }),
      entry({ sequence: 3, fromStatus: 'PREPARING', toStatus: 'READY' }),
    ];

    const steps = orderLifecycleSteps('CANCELLED', timeline, label);

    expect(steps.map((s) => s.id)).toEqual([
      'RECEIVED',
      'CONFIRMED',
      'PREPARING',
      'READY',
      'CANCELLED',
    ]);
    expect(steps.slice(0, -1).every((s) => s.state === 'complete')).toBe(true);
    const last = steps.at(-1)!;
    expect(last.state).toBe('current');
    expect(last.tone).toBe('danger');
  });

  it('treats an exception with no happy-path timeline entry as having reached RECEIVED only', () => {
    // Every order starts at RECEIVED (`OrderStateMachine`'s own guarantee),
    // so a rail with an empty or not-yet-loaded timeline must not read as
    // having reached nothing at all.
    const steps = orderLifecycleSteps('PAYMENT_FAILED', [], label);

    expect(steps.map((s) => s.id)).toEqual(['RECEIVED', 'PAYMENT_FAILED']);
    expect(steps[0].state).toBe('complete');
    expect(steps[1].tone).toBe('danger');
  });

  it('rejects after reaching PREPARING, not resetting to the start', () => {
    const timeline = [
      entry({ sequence: 1, fromStatus: 'RECEIVED', toStatus: 'CONFIRMED' }),
      entry({ sequence: 2, fromStatus: 'CONFIRMED', toStatus: 'PREPARING' }),
    ];

    const steps = orderLifecycleSteps('REJECTED', timeline, label);

    expect(steps.map((s) => s.id)).toEqual(['RECEIVED', 'CONFIRMED', 'PREPARING', 'REJECTED']);
  });
});

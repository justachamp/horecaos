import { describe, expect, it } from 'vitest';

import {
  LatenessInput,
  LatenessPolicy,
  PLATFORM_DEFAULT_LATENESS_POLICY,
  evaluateLateness,
  latenessThresholdsForMode,
} from './lateness-policy';

const NOW = new Date('2026-09-30T10:00:00Z');

function minutesFromNow(minutes: number): Date {
  return new Date(NOW.getTime() + minutes * 60_000);
}

/**
 * Rows X.39 / 10.3b: what the server serves after a tenant edits the `ordering.lateness` document —
 * each fulfilment mode with its own numbers. Delivery warns 10 minutes ahead and allows a minute of
 * grace, pickup warns 2 minutes ahead, dine-in keeps the default 5 and calls an unpromised order late
 * after 20 minutes.
 */
const PER_MODE_POLICY: LatenessPolicy = {
  delivery: { atRiskBeforeSeconds: 600, lateAfterSeconds: 60, noPromiseFallbackSeconds: 3600 },
  pickup: { atRiskBeforeSeconds: 120, lateAfterSeconds: 0, noPromiseFallbackSeconds: 1800 },
  dineIn: { atRiskBeforeSeconds: 300, lateAfterSeconds: 0, noPromiseFallbackSeconds: 1200 },
};

function input(overrides: Partial<LatenessInput>): LatenessInput {
  return {
    fulfilmentMode: 'DELIVERY',
    promisedAt: minutesFromNow(8),
    createdAt: minutesFromNow(-20),
    isTerminal: false,
    ...overrides,
  };
}

describe('evaluateLateness, per fulfilment mode', () => {
  it('judges the same promise against each mode’s own at-risk window', () => {
    // Promised 8 minutes from now.
    expect(evaluateLateness(input({ fulfilmentMode: 'DELIVERY' }), PER_MODE_POLICY, NOW)).toBe(
      'AT_RISK',
    ); // inside delivery's 10 minutes
    expect(evaluateLateness(input({ fulfilmentMode: 'PICKUP' }), PER_MODE_POLICY, NOW)).toBe(
      'NORMAL',
    ); // outside pickup's 2 minutes
    expect(evaluateLateness(input({ fulfilmentMode: 'DINE_IN' }), PER_MODE_POLICY, NOW)).toBe(
      'NORMAL',
    ); // outside dine-in's 5 minutes
  });

  it('moves a mode to AT_RISK exactly when the promise comes inside its own window', () => {
    const pickup = (minutes: number) =>
      evaluateLateness(
        input({ fulfilmentMode: 'PICKUP', promisedAt: minutesFromNow(minutes) }),
        PER_MODE_POLICY,
        NOW,
      );

    expect(pickup(3)).toBe('NORMAL');
    expect(pickup(2.5)).toBe('NORMAL');
    expect(pickup(1.9)).toBe('AT_RISK');
    expect(pickup(0.1)).toBe('AT_RISK');
  });

  it('gives only the mode that has grace its grace before calling an order late', () => {
    const overdueByThirtySeconds = { promisedAt: new Date(NOW.getTime() - 30_000) };

    expect(
      evaluateLateness(
        input({ ...overdueByThirtySeconds, fulfilmentMode: 'DELIVERY' }),
        PER_MODE_POLICY,
        NOW,
      ),
    ).toBe('AT_RISK'); // 60 s of grace: not late yet
    expect(
      evaluateLateness(
        input({ ...overdueByThirtySeconds, fulfilmentMode: 'PICKUP' }),
        PER_MODE_POLICY,
        NOW,
      ),
    ).toBe('LATE');
    expect(
      evaluateLateness(
        input({ promisedAt: new Date(NOW.getTime() - 61_000), fulfilmentMode: 'DELIVERY' }),
        PER_MODE_POLICY,
        NOW,
      ),
    ).toBe('LATE'); // past the grace
  });

  it('measures an unpromised order against its own mode’s fallback', () => {
    const unpromisedFor25Minutes = { promisedAt: null, createdAt: minutesFromNow(-25) };

    expect(
      evaluateLateness(
        input({ ...unpromisedFor25Minutes, fulfilmentMode: 'DELIVERY' }),
        PER_MODE_POLICY,
        NOW,
      ),
    ).toBe('NORMAL'); // delivery allows an hour
    expect(
      evaluateLateness(
        input({ ...unpromisedFor25Minutes, fulfilmentMode: 'PICKUP' }),
        PER_MODE_POLICY,
        NOW,
      ),
    ).toBe('NORMAL'); // pickup allows 30 minutes
    expect(
      evaluateLateness(
        input({ ...unpromisedFor25Minutes, fulfilmentMode: 'DINE_IN' }),
        PER_MODE_POLICY,
        NOW,
      ),
    ).toBe('LATE'); // dine-in allows 20
  });

  it('a zero-minute window warns only at the promise itself', () => {
    const noEarlyWarning: LatenessPolicy = {
      ...PER_MODE_POLICY,
      pickup: { atRiskBeforeSeconds: 0, lateAfterSeconds: 0, noPromiseFallbackSeconds: 1800 },
    };

    expect(
      evaluateLateness(
        input({ fulfilmentMode: 'PICKUP', promisedAt: minutesFromNow(0.1) }),
        noEarlyWarning,
        NOW,
      ),
    ).toBe('NORMAL');
    expect(
      evaluateLateness(
        input({ fulfilmentMode: 'PICKUP', promisedAt: new Date(NOW.getTime() - 1000) }),
        noEarlyWarning,
        NOW,
      ),
    ).toBe('LATE');
  });

  it('never flags a terminal order, whatever its mode’s numbers', () => {
    for (const fulfilmentMode of ['DELIVERY', 'PICKUP', 'DINE_IN']) {
      expect(
        evaluateLateness(
          input({ fulfilmentMode, promisedAt: minutesFromNow(-500), isTerminal: true }),
          PER_MODE_POLICY,
          NOW,
        ),
      ).toBe('NORMAL');
    }
  });

  it('the platform default is the same for every mode, so nothing changes until a tenant edits one', () => {
    for (const fulfilmentMode of ['DELIVERY', 'PICKUP', 'DINE_IN']) {
      expect(
        evaluateLateness(input({ fulfilmentMode }), PLATFORM_DEFAULT_LATENESS_POLICY, NOW),
      ).toBe('NORMAL'); // 8 minutes out, 5-minute window
      expect(
        evaluateLateness(
          input({ fulfilmentMode, promisedAt: minutesFromNow(4) }),
          PLATFORM_DEFAULT_LATENESS_POLICY,
          NOW,
        ),
      ).toBe('AT_RISK');
    }
  });
});

describe('latenessThresholdsForMode', () => {
  it('selects each mode’s own numbers', () => {
    expect(latenessThresholdsForMode(PER_MODE_POLICY, 'DELIVERY')).toBe(PER_MODE_POLICY.delivery);
    expect(latenessThresholdsForMode(PER_MODE_POLICY, 'PICKUP')).toBe(PER_MODE_POLICY.pickup);
    expect(latenessThresholdsForMode(PER_MODE_POLICY, 'DINE_IN')).toBe(PER_MODE_POLICY.dineIn);
  });

  it('falls back to delivery’s numbers for an absent or unknown mode rather than guessing another', () => {
    expect(latenessThresholdsForMode(PER_MODE_POLICY, null)).toBe(PER_MODE_POLICY.delivery);
    expect(latenessThresholdsForMode(PER_MODE_POLICY, undefined)).toBe(PER_MODE_POLICY.delivery);
    expect(latenessThresholdsForMode(PER_MODE_POLICY, 'DRONE')).toBe(PER_MODE_POLICY.delivery);
  });
});

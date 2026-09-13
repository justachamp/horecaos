import { describe, expect, it } from 'vitest';

import { LatenessPolicy, PLATFORM_DEFAULT_LATENESS_POLICY } from '../../core/lateness-policy';
import { computeTicketSeverity } from '../kitchen/kitchen-ticket';
import {
  APPROVAL_DEADLINE_THRESHOLD_MS,
  OrderSeverityInput,
  compareNewestFirst,
  compareOrderSeverity,
  computeOrderSeverity,
  formatCountdown,
  formatSeverityCaption,
} from './order-severity';
import { TERMINAL_ORDER_STATUSES } from './order-status';

const NOW = new Date('2026-08-30T12:00:00Z');
const POLICY = PLATFORM_DEFAULT_LATENESS_POLICY; // at_risk 300s, late_after 0s, no_promise_fallback 2700s

/** A fresh, unremarkable order: non-terminal, just created, no promise, no deadline, no blocked process. */
function baseInput(overrides: Partial<OrderSeverityInput> = {}): OrderSeverityInput {
  return {
    status: 'RECEIVED',
    createdAt: NOW,
    approvalDeadlineAt: null,
    fulfillmentMode: 'DELIVERY',
    promisedAt: null,
    hasBlockedProcess: false,
    ...overrides,
  };
}

function minutesAgo(minutes: number): Date {
  return new Date(NOW.getTime() - minutes * 60 * 1000);
}

function minutesFromNow(minutes: number): Date {
  return new Date(NOW.getTime() + minutes * 60 * 1000);
}

describe('computeOrderSeverity', () => {
  it('flags nothing for a fresh, ordinary order', () => {
    const severity = computeOrderSeverity(baseInput(), NOW, POLICY);
    expect(severity).toEqual({ level: 'NORMAL', tone: 'none', remainingMs: null, elapsedMs: null });
  });

  describe('terminal orders are never flagged, regardless of history', () => {
    for (const status of TERMINAL_ORDER_STATUSES) {
      it(`never flags a ${status} order, even one that looks blocked, overdue for approval, promise-breached, and stale`, () => {
        const severity = computeOrderSeverity(
          baseInput({
            status,
            createdAt: minutesAgo(500), // ancient — would trip the no-promise fallback
            approvalDeadlineAt: minutesAgo(10), // deadline long passed
            promisedAt: minutesAgo(400), // promise long breached
            hasBlockedProcess: true, // would trip BLOCKED
          }),
          NOW,
          POLICY,
        );
        expect(severity.level).toBe('NORMAL');
        expect(severity.tone).toBe('none');
      });
    }
  });

  describe('BLOCKED', () => {
    it('flags a non-terminal order with a process stuck on manual action', () => {
      const severity = computeOrderSeverity(baseInput({ hasBlockedProcess: true }), NOW, POLICY);
      expect(severity.level).toBe('BLOCKED');
      expect(severity.tone).toBe('danger');
    });

    it('ranks above every other live signal, even when they also apply', () => {
      const severity = computeOrderSeverity(
        baseInput({
          status: 'AWAITING_APPROVAL',
          approvalDeadlineAt: minutesFromNow(1), // would be AWAITING_APPROVAL_DEADLINE
          promisedAt: minutesAgo(10), // would also be LATE
          hasBlockedProcess: true,
        }),
        NOW,
        POLICY,
      );
      expect(severity.level).toBe('BLOCKED');
    });
  });

  describe('LATE (a real promise, breached)', () => {
    it('flags a non-terminal order past its promise, with zero grace under the platform default', () => {
      const severity = computeOrderSeverity(
        baseInput({ status: 'PREPARING', promisedAt: minutesAgo(1) }),
        NOW,
        POLICY,
      );
      expect(severity.level).toBe('LATE');
      expect(severity.tone).toBe('danger');
      expect(severity.elapsedMs).toBe(60_000); // one minute past the promise
    });

    it('is not yet LATE exactly at the promise — only after it, since late_after_seconds is 0', () => {
      const severity = computeOrderSeverity(
        baseInput({ status: 'PREPARING', promisedAt: NOW }),
        NOW,
        POLICY,
      );
      expect(severity.level).not.toBe('LATE');
    });

    it('respects a configured grace period past the promise before flagging LATE', () => {
      const policy: LatenessPolicy = {
        ...POLICY,
        delivery: {
          atRiskBeforeSeconds: 300,
          lateAfterSeconds: 120,
          noPromiseFallbackSeconds: 2700,
        },
      };
      const promisedAt = minutesAgo(1); // 60s past — inside the 120s grace
      expect(
        computeOrderSeverity(baseInput({ status: 'PREPARING', promisedAt }), NOW, policy).level,
      ).not.toBe('LATE');

      const wellPast = new Date(promisedAt.getTime() - 61_000); // 121s past — outside the grace
      expect(
        computeOrderSeverity(baseInput({ status: 'PREPARING', promisedAt: wellPast }), NOW, policy)
          .level,
      ).toBe('LATE');
    });

    it('selects the thresholds for the order-s own fulfilment mode', () => {
      const policy: LatenessPolicy = {
        delivery: {
          atRiskBeforeSeconds: 300,
          lateAfterSeconds: 600,
          noPromiseFallbackSeconds: 2700,
        },
        pickup: { atRiskBeforeSeconds: 300, lateAfterSeconds: 0, noPromiseFallbackSeconds: 2700 },
        dineIn: { atRiskBeforeSeconds: 300, lateAfterSeconds: 0, noPromiseFallbackSeconds: 2700 },
      };
      const promisedAt = minutesAgo(1); // 60s past

      // Delivery's own 600s grace absorbs it.
      expect(
        computeOrderSeverity(
          baseInput({ status: 'PREPARING', fulfillmentMode: 'DELIVERY', promisedAt }),
          NOW,
          policy,
        ).level,
      ).not.toBe('LATE');

      // Pickup has no grace at all.
      expect(
        computeOrderSeverity(
          baseInput({ status: 'PREPARING', fulfillmentMode: 'PICKUP', promisedAt }),
          NOW,
          policy,
        ).level,
      ).toBe('LATE');
    });
  });

  describe('LATE (no promise at all — §2.7s documented fallback)', () => {
    it('flags a non-terminal order once it has been open longer than the fallback', () => {
      const severity = computeOrderSeverity(
        baseInput({ status: 'PREPARING', createdAt: minutesAgo(46) }),
        NOW,
        POLICY,
      );
      expect(severity.level).toBe('LATE');
      expect(severity.tone).toBe('danger');
      expect(severity.elapsedMs).toBe(46 * 60 * 1000);
    });

    it('does not flag it at exactly the fallback — only past it', () => {
      const exactlyAtFallback = new Date(
        NOW.getTime() - POLICY.delivery.noPromiseFallbackSeconds * 1000,
      );
      const severity = computeOrderSeverity(
        baseInput({ createdAt: exactlyAtFallback }),
        NOW,
        POLICY,
      );
      expect(severity.level).toBe('NORMAL');
    });

    it('applies across every non-terminal status, not only RECEIVED', () => {
      const statuses = [
        'PAYMENT_AUTHORIZING',
        'PAYMENT_FAILED',
        'CONFIRMED',
        'PREPARING',
        'READY',
        'FULFILLING',
      ];
      const levels = statuses.map(
        (status) =>
          computeOrderSeverity(baseInput({ status, createdAt: minutesAgo(50) }), NOW, POLICY).level,
      );
      expect(levels).toEqual(statuses.map(() => 'LATE'));
    });
  });

  describe('precedence between LATE and AWAITING_APPROVAL_DEADLINE (§2.6: LATE ranks first)', () => {
    it('LATE via a breached promise wins even with an imminent approval deadline', () => {
      const severity = computeOrderSeverity(
        baseInput({
          status: 'AWAITING_APPROVAL',
          promisedAt: minutesAgo(5),
          approvalDeadlineAt: minutesFromNow(1),
        }),
        NOW,
        POLICY,
      );
      expect(severity.level).toBe('LATE');
    });

    it('LATE via the no-promise fallback also wins over an imminent approval deadline', () => {
      const severity = computeOrderSeverity(
        baseInput({
          status: 'AWAITING_APPROVAL',
          createdAt: minutesAgo(90),
          approvalDeadlineAt: minutesFromNow(1),
        }),
        NOW,
        POLICY,
      );
      expect(severity.level).toBe('LATE');
    });
  });

  describe('AWAITING_APPROVAL_DEADLINE', () => {
    it('flags AWAITING_APPROVAL once under two minutes remain, with no promise in play', () => {
      const severity = computeOrderSeverity(
        baseInput({ status: 'AWAITING_APPROVAL', approvalDeadlineAt: minutesFromNow(1) }),
        NOW,
        POLICY,
      );
      expect(severity.level).toBe('AWAITING_APPROVAL_DEADLINE');
      expect(severity.tone).toBe('danger');
      expect(severity.remainingMs).toBe(60_000);
    });

    it('does not flag it at exactly the two-minute threshold — only under it', () => {
      const atThreshold = new Date(NOW.getTime() + APPROVAL_DEADLINE_THRESHOLD_MS);
      const severity = computeOrderSeverity(
        baseInput({ status: 'AWAITING_APPROVAL', approvalDeadlineAt: atThreshold }),
        NOW,
        POLICY,
      );
      expect(severity.level).toBe('NORMAL');
    });

    it('flags it once the deadline has passed entirely, with a negative remainder', () => {
      const severity = computeOrderSeverity(
        baseInput({ status: 'AWAITING_APPROVAL', approvalDeadlineAt: minutesAgo(1) }),
        NOW,
        POLICY,
      );
      expect(severity.level).toBe('AWAITING_APPROVAL_DEADLINE');
      expect(severity.remainingMs).toBeLessThan(0);
    });

    it('does not apply to any other status even with a near deadline', () => {
      // approval_deadline_at can still be populated on a CONFIRMED order (the
      // decision already happened); the tier is specific to being *in* the
      // AWAITING_APPROVAL wait, not to the field being present.
      const severity = computeOrderSeverity(
        baseInput({ status: 'CONFIRMED', approvalDeadlineAt: minutesFromNow(1) }),
        NOW,
        POLICY,
      );
      expect(severity.level).toBe('NORMAL');
    });

    it('does not apply when AWAITING_APPROVAL has no deadline at all', () => {
      const severity = computeOrderSeverity(
        baseInput({ status: 'AWAITING_APPROVAL', approvalDeadlineAt: null }),
        NOW,
        POLICY,
      );
      expect(severity.level).toBe('NORMAL');
    });
  });

  describe('AT_RISK', () => {
    it('flags a non-terminal order inside the warning window ahead of its promise', () => {
      const promisedAt = minutesFromNow(4); // inside delivery's 300s (5 min) window
      const severity = computeOrderSeverity(
        baseInput({ status: 'PREPARING', promisedAt }),
        NOW,
        POLICY,
      );
      expect(severity.level).toBe('AT_RISK');
      expect(severity.tone).toBe('warning');
    });

    it('is not yet AT_RISK outside the warning window', () => {
      const promisedAt = minutesFromNow(6); // outside the 300s window
      const severity = computeOrderSeverity(
        baseInput({ status: 'PREPARING', promisedAt }),
        NOW,
        POLICY,
      );
      expect(severity.level).toBe('NORMAL');
    });

    it('yields to AWAITING_APPROVAL_DEADLINE when both would fire (§2.6: deadline ranks above AT_RISK)', () => {
      const severity = computeOrderSeverity(
        baseInput({
          status: 'AWAITING_APPROVAL',
          promisedAt: minutesFromNow(1), // inside the at-risk window
          approvalDeadlineAt: minutesFromNow(1), // under the 2-minute threshold
        }),
        NOW,
        POLICY,
      );
      expect(severity.level).toBe('AWAITING_APPROVAL_DEADLINE');
    });
  });
});

describe('computeOrderSeverity and computeTicketSeverity share one AT_RISK boundary', () => {
  it('flags AT_RISK at the identical instant the shared evaluator would', () => {
    // Row X.39's whole point, proven directly: the order board and the
    // kitchen ticket queue call `core/lateness-policy.ts`'s one evaluator, so
    // feeding both the same promise, mode, and policy must agree.
    const promisedAt = minutesFromNow(4); // inside delivery's 300s window, outside a shorter one

    const orderSeverity = computeOrderSeverity(
      baseInput({ status: 'PREPARING', fulfillmentMode: 'DELIVERY', promisedAt }),
      NOW,
      POLICY,
    );
    const ticketSeverity = computeTicketSeverity(
      { targetReadyAt: promisedAt, createdAt: NOW, fulfilmentMode: 'DELIVERY' },
      NOW,
      POLICY,
    );

    expect(orderSeverity.level).toBe('AT_RISK');
    expect(ticketSeverity.level).toBe('AT_RISK');
  });
});

describe('formatSeverityCaption', () => {
  const translate = (key: string, values?: Readonly<Record<string, string | number>>) =>
    values ? `${key}:${JSON.stringify(values)}` : key;

  it('renders nothing for NORMAL', () => {
    expect(
      formatSeverityCaption(
        { level: 'NORMAL', tone: 'none', remainingMs: null, elapsedMs: null },
        translate,
      ),
    ).toBeNull();
  });

  it('renders a caption for every non-NORMAL level', () => {
    const levels: ReadonlyArray<{
      level: 'BLOCKED' | 'LATE' | 'AWAITING_APPROVAL_DEADLINE' | 'AT_RISK';
      remainingMs: number | null;
      elapsedMs: number | null;
    }> = [
      { level: 'BLOCKED', remainingMs: null, elapsedMs: null },
      { level: 'LATE', remainingMs: null, elapsedMs: 60_000 },
      { level: 'AWAITING_APPROVAL_DEADLINE', remainingMs: 60_000, elapsedMs: null },
      { level: 'AT_RISK', remainingMs: null, elapsedMs: null },
    ];
    for (const { level, remainingMs, elapsedMs } of levels) {
      expect(
        formatSeverityCaption({ level, tone: 'danger', remainingMs, elapsedMs }, translate),
      ).not.toBeNull();
    }
  });
});

describe('compareOrderSeverity', () => {
  function rankable(
    level: 'BLOCKED' | 'LATE' | 'AWAITING_APPROVAL_DEADLINE' | 'AT_RISK' | 'NORMAL',
    createdAt: Date,
  ) {
    return {
      severity: { level, tone: 'none' as const, remainingMs: null, elapsedMs: null },
      createdAt,
    };
  }

  it('sorts worse severity first, in §2.6s own order', () => {
    const normal = rankable('NORMAL', NOW);
    const atRisk = rankable('AT_RISK', NOW);
    const deadline = rankable('AWAITING_APPROVAL_DEADLINE', NOW);
    const late = rankable('LATE', NOW);
    const blocked = rankable('BLOCKED', NOW);

    const sorted = [normal, atRisk, deadline, late, blocked].sort(compareOrderSeverity);
    expect(sorted.map((r) => r.severity.level)).toEqual([
      'BLOCKED',
      'LATE',
      'AWAITING_APPROVAL_DEADLINE',
      'AT_RISK',
      'NORMAL',
    ]);
  });

  it('breaks ties within the same level by created_at ascending — oldest first', () => {
    const older = rankable('NORMAL', minutesAgo(10));
    const newer = rankable('NORMAL', minutesAgo(1));

    expect([newer, older].sort(compareOrderSeverity)).toEqual([older, newer]);
  });
});

describe('compareNewestFirst', () => {
  it('sorts created_at descending, for the log tabs', () => {
    const older = { createdAt: minutesAgo(10) };
    const newer = { createdAt: minutesAgo(1) };
    expect([older, newer].sort(compareNewestFirst)).toEqual([newer, older]);
  });
});

describe('formatCountdown', () => {
  it('renders minutes and seconds, zero-padded', () => {
    expect(formatCountdown(72_000)).toBe('01:12');
    expect(formatCountdown(5_000)).toBe('00:05');
  });

  it('does not roll past 59 minutes into hours — it is a countdown, not a duration', () => {
    expect(formatCountdown(60 * 60 * 1000)).toBe('60:00');
  });

  it('clamps a negative or zero remainder to 00:00 rather than showing a negative countdown', () => {
    expect(formatCountdown(-5_000)).toBe('00:00');
    expect(formatCountdown(0)).toBe('00:00');
  });
});

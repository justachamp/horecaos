import { describe, expect, it } from 'vitest';

import {
  APPROVAL_DEADLINE_THRESHOLD_MS,
  NO_PROMISE_FALLBACK_MS,
  OrderSeverityInput,
  compareNewestFirst,
  compareOrderSeverity,
  computeOrderSeverity,
  formatCountdown,
} from './order-severity';
import { TERMINAL_ORDER_STATUSES } from './order-status';

const NOW = new Date('2026-08-30T12:00:00Z');

/** A fresh, unremarkable order: non-terminal, just created, no deadline, no blocked process. */
function baseInput(overrides: Partial<OrderSeverityInput> = {}): OrderSeverityInput {
  return {
    status: 'RECEIVED',
    createdAt: NOW,
    approvalDeadlineAt: null,
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
    const severity = computeOrderSeverity(baseInput(), NOW);
    expect(severity).toEqual({ level: 'NORMAL', tone: 'none', remainingMs: null, elapsedMs: null });
  });

  describe('terminal orders are never flagged, regardless of history', () => {
    for (const status of TERMINAL_ORDER_STATUSES) {
      it(`never flags a ${status} order, even one that looks blocked, overdue for approval, and stale`, () => {
        const severity = computeOrderSeverity(
          baseInput({
            status,
            createdAt: minutesAgo(500), // ancient — would trip NO_PROMISE_FALLBACK
            approvalDeadlineAt: minutesAgo(10), // deadline long passed
            hasBlockedProcess: true, // would trip BLOCKED
          }),
          NOW,
        );
        expect(severity.level).toBe('NORMAL');
        expect(severity.tone).toBe('none');
      });
    }
  });

  describe('BLOCKED', () => {
    it('flags a non-terminal order with a process stuck on manual action', () => {
      const severity = computeOrderSeverity(baseInput({ hasBlockedProcess: true }), NOW);
      expect(severity.level).toBe('BLOCKED');
      expect(severity.tone).toBe('danger');
    });

    it('ranks above every other live signal, even when they also apply', () => {
      const severity = computeOrderSeverity(
        baseInput({
          status: 'AWAITING_APPROVAL',
          approvalDeadlineAt: minutesFromNow(1), // would be AWAITING_APPROVAL_DEADLINE
          createdAt: minutesAgo(90), // would also be NO_PROMISE_FALLBACK
          hasBlockedProcess: true,
        }),
        NOW,
      );
      expect(severity.level).toBe('BLOCKED');
    });
  });

  describe('AWAITING_APPROVAL_DEADLINE', () => {
    it('flags AWAITING_APPROVAL once under two minutes remain', () => {
      const severity = computeOrderSeverity(
        baseInput({ status: 'AWAITING_APPROVAL', approvalDeadlineAt: minutesFromNow(1) }),
        NOW,
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
      );
      expect(severity.level).toBe('NORMAL');
    });

    it('flags it once the deadline has passed entirely, with a negative remainder', () => {
      const severity = computeOrderSeverity(
        baseInput({ status: 'AWAITING_APPROVAL', approvalDeadlineAt: minutesAgo(1) }),
        NOW,
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
      );
      expect(severity.level).toBe('NORMAL');
    });

    it('does not apply when AWAITING_APPROVAL has no deadline at all', () => {
      const severity = computeOrderSeverity(
        baseInput({ status: 'AWAITING_APPROVAL', approvalDeadlineAt: null }),
        NOW,
      );
      expect(severity.level).toBe('NORMAL');
    });
  });

  describe('NO_PROMISE_FALLBACK', () => {
    it('flags a non-terminal order once it has been open for more than 45 minutes', () => {
      const severity = computeOrderSeverity(
        baseInput({ status: 'PREPARING', createdAt: minutesAgo(46) }),
        NOW,
      );
      expect(severity.level).toBe('NO_PROMISE_FALLBACK');
      expect(severity.tone).toBe('danger');
      expect(severity.elapsedMs).toBe(46 * 60 * 1000);
    });

    it('does not flag it at exactly 45 minutes — only past it, since late_after_seconds is 0', () => {
      const exactlyAtFallback = new Date(NOW.getTime() - NO_PROMISE_FALLBACK_MS);
      const severity = computeOrderSeverity(baseInput({ createdAt: exactlyAtFallback }), NOW);
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
          computeOrderSeverity(baseInput({ status, createdAt: minutesAgo(50) }), NOW).level,
      );
      expect(levels).toEqual(statuses.map(() => 'NO_PROMISE_FALLBACK'));
    });

    it('yields to AWAITING_APPROVAL_DEADLINE when both would fire', () => {
      const severity = computeOrderSeverity(
        baseInput({
          status: 'AWAITING_APPROVAL',
          createdAt: minutesAgo(90),
          approvalDeadlineAt: minutesFromNow(1),
        }),
        NOW,
      );
      expect(severity.level).toBe('AWAITING_APPROVAL_DEADLINE');
    });

    it('still fires for a stale AWAITING_APPROVAL order once its deadline is not imminent', () => {
      const severity = computeOrderSeverity(
        baseInput({
          status: 'AWAITING_APPROVAL',
          createdAt: minutesAgo(90),
          approvalDeadlineAt: minutesFromNow(30),
        }),
        NOW,
      );
      expect(severity.level).toBe('NO_PROMISE_FALLBACK');
    });
  });
});

describe('compareOrderSeverity', () => {
  function rankable(
    level: 'BLOCKED' | 'AWAITING_APPROVAL_DEADLINE' | 'NO_PROMISE_FALLBACK' | 'NORMAL',
    createdAt: Date,
  ) {
    return {
      severity: { level, tone: 'none' as const, remainingMs: null, elapsedMs: null },
      createdAt,
    };
  }

  it('sorts worse severity first', () => {
    const normal = rankable('NORMAL', NOW);
    const fallback = rankable('NO_PROMISE_FALLBACK', NOW);
    const deadline = rankable('AWAITING_APPROVAL_DEADLINE', NOW);
    const blocked = rankable('BLOCKED', NOW);

    const sorted = [normal, fallback, deadline, blocked].sort(compareOrderSeverity);
    expect(sorted.map((r) => r.severity.level)).toEqual([
      'BLOCKED',
      'AWAITING_APPROVAL_DEADLINE',
      'NO_PROMISE_FALLBACK',
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

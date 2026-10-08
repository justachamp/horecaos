import { describe, expect, it } from 'vitest';

import { LatenessPolicy, PLATFORM_DEFAULT_LATENESS_POLICY } from '../../core/lateness-policy';
import { computeTicketSeverity, ticketSeverityInput } from '../kitchen/kitchen-ticket';
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
      { promisedAt, createdAt: NOW, fulfilmentMode: 'DELIVERY' },
      NOW,
      POLICY,
    );

    expect(orderSeverity.level).toBe('AT_RISK');
    expect(ticketSeverity.level).toBe('AT_RISK');
  });
});

describe('the order board and the kitchen queue agree per fulfilment mode (rows X.39 / 10.3b)', () => {
  // What the server serves once a tenant edits the ordering.lateness document: a window and a
  // no-promise fallback of each mode's own.
  const PER_MODE: LatenessPolicy = {
    delivery: { atRiskBeforeSeconds: 600, lateAfterSeconds: 0, noPromiseFallbackSeconds: 3600 },
    pickup: { atRiskBeforeSeconds: 120, lateAfterSeconds: 0, noPromiseFallbackSeconds: 1800 },
    dineIn: { atRiskBeforeSeconds: 0, lateAfterSeconds: 0, noPromiseFallbackSeconds: 1200 },
  };
  const MODES = [
    { fulfillmentMode: 'DELIVERY', fulfilmentMode: 'DELIVERY' },
    { fulfillmentMode: 'PICKUP', fulfilmentMode: 'PICKUP' },
    { fulfillmentMode: 'DINE_IN', fulfilmentMode: 'DINE_IN' },
  ] as const;

  it('gives one promise a different level per mode, and both boards give the same level for each', () => {
    const promisedAt = minutesFromNow(5); // inside delivery's 10 min, outside pickup's 2 and dine-in's 0
    const expected = { DELIVERY: 'AT_RISK', PICKUP: 'NORMAL', DINE_IN: 'NORMAL' } as const;

    for (const { fulfillmentMode, fulfilmentMode } of MODES) {
      const order = computeOrderSeverity(
        baseInput({ status: 'PREPARING', fulfillmentMode, promisedAt }),
        NOW,
        PER_MODE,
      );
      const ticket = computeTicketSeverity(
        { promisedAt, createdAt: NOW, fulfilmentMode },
        NOW,
        PER_MODE,
      );

      expect(order.level, `order board, ${fulfillmentMode}`).toBe(expected[fulfillmentMode]);
      expect(ticket.level, `kitchen queue, ${fulfillmentMode}`).toBe(expected[fulfillmentMode]);
    }
  });

  it('applies each mode’s own no-promise fallback on both boards', () => {
    const createdAt = minutesAgo(25); // delivery allows 60, pickup 30, dine-in 20

    for (const [mode, late] of [
      ['DELIVERY', false],
      ['PICKUP', false],
      ['DINE_IN', true],
    ] as const) {
      const order = computeOrderSeverity(
        baseInput({ status: 'PREPARING', fulfillmentMode: mode, promisedAt: null, createdAt }),
        NOW,
        PER_MODE,
      );
      const ticket = computeTicketSeverity(
        { promisedAt: null, createdAt, fulfilmentMode: mode },
        NOW,
        PER_MODE,
      );

      expect(order.level, `order board, ${mode}`).toBe(late ? 'LATE' : 'NORMAL');
      expect(ticket.level, `kitchen queue, ${mode}`).toBe(late ? 'BREACHED' : 'NORMAL');
    }
  });
});

describe('the kitchen queue and the walls colour an order exactly as the order board does (ADR 0150)', () => {
  // A branch whose card 2 says 20 minutes: the only number the unpromised orders below can see.
  const TWENTY: LatenessPolicy = {
    delivery: { atRiskBeforeSeconds: 300, lateAfterSeconds: 0, noPromiseFallbackSeconds: 1200 },
    pickup: { atRiskBeforeSeconds: 300, lateAfterSeconds: 0, noPromiseFallbackSeconds: 1200 },
    dineIn: { atRiskBeforeSeconds: 300, lateAfterSeconds: 0, noPromiseFallbackSeconds: 1200 },
  };
  const iso = (date: Date): string => date.toISOString();

  // The order shapes ADR 0150 decides: an aggregator order with no promise that waited for approval, a
  // native delivery whose road time is 20 minutes, a scheduled order taken hours ahead, an order that is over.
  const ORDERS = [
    {
      name: 'an unpromised order accepted late',
      order: { createdAt: minutesAgo(30), promisedAt: null, status: 'PREPARING' },
      ticketOpened: minutesAgo(1),
      travelMinutes: 0,
    },
    {
      name: 'a promised delivery with twenty minutes of road',
      order: { createdAt: minutesAgo(30), promisedAt: minutesFromNow(10), status: 'PREPARING' },
      ticketOpened: minutesAgo(25),
      travelMinutes: 20,
    },
    {
      name: 'a scheduled order taken hours ahead',
      order: { createdAt: minutesAgo(300), promisedAt: minutesFromNow(120), status: 'PREPARING' },
      ticketOpened: minutesAgo(2),
      travelMinutes: 0,
    },
    {
      name: 'a finished order whose ticket is still on the pass',
      order: { createdAt: minutesAgo(500), promisedAt: minutesAgo(400), status: 'COMPLETED' },
      ticketOpened: minutesAgo(450),
      travelMinutes: 0,
    },
  ] as const;

  // Sampled across the lifetime of each order, including the instants either side of every edge.
  const INSTANTS_IN_MINUTES = [-30, 0, 4, 5, 9, 10, 10.01, 25, 45, 119, 121, 600];

  for (const { name, order, ticketOpened, travelMinutes } of ORDERS) {
    it(`agrees with the board about ${name} at every instant`, () => {
      const targetReadyAt =
        order.promisedAt === null
          ? null
          : new Date(order.promisedAt.getTime() - travelMinutes * 60_000);

      for (const offset of INSTANTS_IN_MINUTES) {
        const at = new Date(NOW.getTime() + offset * 60_000);
        const board = computeOrderSeverity(
          baseInput({
            status: order.status,
            createdAt: order.createdAt,
            promisedAt: order.promisedAt,
            fulfillmentMode: 'DELIVERY',
          }),
          at,
          TWENTY,
        );
        // What the server sends for the ticket of that order, queue or wall alike.
        const kitchen = computeTicketSeverity(
          ticketSeverityInput({
            fulfilmentMode: 'DELIVERY',
            createdAt: iso(ticketOpened),
            targetReadyAt: targetReadyAt === null ? null : iso(targetReadyAt),
            orderCreatedAt: iso(order.createdAt),
            orderPromisedAt: order.promisedAt === null ? null : iso(order.promisedAt),
            orderTerminal: order.status === 'COMPLETED',
          }),
          at,
          TWENTY,
        );

        expect(kitchen.level === 'BREACHED', `late at ${offset} minutes`).toBe(
          board.level === 'LATE',
        );
        expect(kitchen.level === 'AT_RISK', `at risk at ${offset} minutes`).toBe(
          board.level === 'AT_RISK',
        );
      }
    });
  }
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

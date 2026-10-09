import { describe, expect, it } from 'vitest';

import { LatenessPolicy, PLATFORM_DEFAULT_LATENESS_POLICY } from '../../core/lateness-policy';
import {
  DEFAULT_KITCHEN_TAB,
  KITCHEN_TABS,
  availableItemActions,
  computeTicketSeverity,
  isKitchenTabId,
  isKitchenTabMember,
  ticketItemRows,
  ticketSeverityInput,
} from './kitchen-ticket';

const POLICY = PLATFORM_DEFAULT_LATENESS_POLICY; // at_risk 300s, late_after 0s, no_promise_fallback 2700s

describe('kitchen tabs', () => {
  it('defaults to all, so the board is never blank on open', () => {
    expect(DEFAULT_KITCHEN_TAB).toBe('all');
  });

  it('accepts every declared tab id and rejects an unrecognised one', () => {
    for (const tab of KITCHEN_TABS) {
      expect(isKitchenTabId(tab)).toBe(true);
    }
    // 'aggregator' became a real tab id in wave P16 — see the fifth tab
    // below rather than this once-rejected string.
    expect(isKitchenTabId('mishmash')).toBe(false);
    expect(isKitchenTabId(null)).toBe(false);
  });

  it('maps fulfilmentMode onto exactly its own tab, and dine-in stands in for hall', () => {
    expect(isKitchenTabMember('delivery', 'DELIVERY')).toBe(true);
    expect(isKitchenTabMember('delivery', 'PICKUP')).toBe(false);
    expect(isKitchenTabMember('pickup', 'PICKUP')).toBe(true);
    expect(isKitchenTabMember('dineIn', 'DINE_IN')).toBe(true);
    expect(isKitchenTabMember('all', 'PICKUP')).toBe(true);
    expect(isKitchenTabMember('all', 'SOMETHING_UNKNOWN')).toBe(true);
  });

  it('maps the aggregator tab onto channelSystemType, typed rather than a raw channel code', () => {
    expect(isKitchenTabMember('aggregator', 'DELIVERY', 'AGGREGATOR')).toBe(true);
    expect(isKitchenTabMember('aggregator', 'DELIVERY', 'WEB')).toBe(false);
    // Undefined/null — a mutation response that did not repeat the board's
    // own resolved chip (TicketResponse's own doc) — never false-positives
    // into the aggregator tab.
    expect(isKitchenTabMember('aggregator', 'DELIVERY', undefined)).toBe(false);
    expect(isKitchenTabMember('aggregator', 'DELIVERY', null)).toBe(false);
    // Every other tab is indifferent to the channel.
    expect(isKitchenTabMember('all', 'DELIVERY', 'AGGREGATOR')).toBe(true);
  });
});

describe('computeTicketSeverity', () => {
  const now = new Date('2026-08-30T12:00:00Z');

  it('is NORMAL well before the target', () => {
    const severity = computeTicketSeverity(
      {
        promisedAt: new Date(now.getTime() + 20 * 60 * 1000),
        createdAt: now,
        fulfilmentMode: 'DELIVERY',
      },
      now,
      POLICY,
    );
    expect(severity).toEqual({ level: 'NORMAL', tone: 'none' });
  });

  it('is AT_RISK inside the resolved at-risk window before the target', () => {
    const windowMs = POLICY.delivery.atRiskBeforeSeconds * 1000;
    const severity = computeTicketSeverity(
      {
        promisedAt: new Date(now.getTime() + windowMs - 1),
        createdAt: now,
        fulfilmentMode: 'DELIVERY',
      },
      now,
      POLICY,
    );
    expect(severity.level).toBe('AT_RISK');
    expect(severity.tone).toBe('warning');
  });

  it('is not yet BREACHED at exactly the target — the boundary is exclusive, matching OrderPromise.lateAt', () => {
    const severity = computeTicketSeverity(
      { promisedAt: now, createdAt: now, fulfilmentMode: 'DELIVERY' },
      now,
      POLICY,
    );
    expect(severity.level).toBe('AT_RISK');
  });

  it('is BREACHED the instant after the target passes, with zero grace under the platform default', () => {
    const severity = computeTicketSeverity(
      { promisedAt: new Date(now.getTime() - 1), createdAt: now, fulfilmentMode: 'DELIVERY' },
      now,
      POLICY,
    );
    expect(severity.level).toBe('BREACHED');
    expect(severity.tone).toBe('danger');
  });

  it('falls back to the resolved no-promise rule when there is no target', () => {
    const fallbackMs = POLICY.delivery.noPromiseFallbackSeconds * 1000;
    const justUnder = computeTicketSeverity(
      {
        promisedAt: null,
        createdAt: new Date(now.getTime() - (fallbackMs - 1)),
        fulfilmentMode: 'DELIVERY',
      },
      now,
      POLICY,
    );
    const justOver = computeTicketSeverity(
      {
        promisedAt: null,
        createdAt: new Date(now.getTime() - (fallbackMs + 1)),
        fulfilmentMode: 'DELIVERY',
      },
      now,
      POLICY,
    );
    expect(justUnder.level).toBe('NORMAL');
    expect(justOver.level).toBe('BREACHED');
  });

  it('selects the resolved policy for the ticket-s own fulfilment mode', () => {
    const policy: LatenessPolicy = {
      delivery: { atRiskBeforeSeconds: 300, lateAfterSeconds: 0, noPromiseFallbackSeconds: 2700 },
      pickup: { atRiskBeforeSeconds: 60, lateAfterSeconds: 0, noPromiseFallbackSeconds: 2700 },
      dineIn: { atRiskBeforeSeconds: 300, lateAfterSeconds: 0, noPromiseFallbackSeconds: 2700 },
    };
    // 90s ahead of the target: inside delivery's 300s window, outside pickup's 60s one.
    const promisedAt = new Date(now.getTime() + 90 * 1000);

    expect(
      computeTicketSeverity({ promisedAt, createdAt: now, fulfilmentMode: 'DELIVERY' }, now, policy)
        .level,
    ).toBe('AT_RISK');
    expect(
      computeTicketSeverity({ promisedAt, createdAt: now, fulfilmentMode: 'PICKUP' }, now, policy)
        .level,
    ).toBe('NORMAL');
  });
});

describe('ticketSeverityInput (ADR 0150: a ticket is coloured by its ORDER, as the board is)', () => {
  const now = new Date('2026-08-30T12:00:00Z');
  const minutes = (n: number): string => new Date(now.getTime() + n * 60_000).toISOString();
  // card 2 set to 20 minutes for the branch: every mode's no-promise fallback is 1200 seconds.
  const TWENTY: LatenessPolicy = {
    delivery: { atRiskBeforeSeconds: 300, lateAfterSeconds: 0, noPromiseFallbackSeconds: 1200 },
    pickup: { atRiskBeforeSeconds: 300, lateAfterSeconds: 0, noPromiseFallbackSeconds: 1200 },
    dineIn: { atRiskBeforeSeconds: 300, lateAfterSeconds: 0, noPromiseFallbackSeconds: 1200 },
  };

  it('measures an unpromised order from its creation, not from the later instant its ticket opened', () => {
    // The order waited half an hour for approval; the kitchen opened its ticket a minute ago.
    const ticket = {
      fulfilmentMode: 'DELIVERY',
      createdAt: minutes(-1),
      targetReadyAt: null,
      orderCreatedAt: minutes(-30),
      orderPromisedAt: null,
      orderTerminal: false,
    };

    expect(computeTicketSeverity(ticketSeverityInput(ticket), now, TWENTY).level).toBe('BREACHED');
    // The same ticket read the old way (from its own opening) is a minute old and calls nothing late:
    // this is the disagreement with the board that the order's clock removes.
    expect(
      computeTicketSeverity(
        ticketSeverityInput({ ...ticket, orderCreatedAt: null, orderPromisedAt: null }),
        now,
        TWENTY,
      ).level,
    ).toBe('NORMAL');
  });

  it('measures a promised order against its promise, not against the promise less the road', () => {
    // Promised in 10 minutes; the road takes 20, so the kitchen's own target passed 10 minutes ago.
    const ticket = {
      fulfilmentMode: 'DELIVERY',
      createdAt: minutes(-30),
      targetReadyAt: minutes(-10),
      orderCreatedAt: minutes(-30),
      orderPromisedAt: minutes(10),
      orderTerminal: false,
    };

    expect(computeTicketSeverity(ticketSeverityInput(ticket), now, POLICY).level).toBe('NORMAL');
    // Ten minutes later the promise itself has passed, and only then is it late, as on the board.
    const later = new Date(now.getTime() + 10 * 60_000 + 1);
    expect(computeTicketSeverity(ticketSeverityInput(ticket), later, POLICY).level).toBe(
      'BREACHED',
    );
  });

  it('never flags the ticket of a finished order, whatever its history', () => {
    const ticket = {
      fulfilmentMode: 'PICKUP',
      createdAt: minutes(-500),
      targetReadyAt: minutes(-400),
      orderCreatedAt: minutes(-500),
      orderPromisedAt: minutes(-400),
      orderTerminal: true,
    };

    expect(computeTicketSeverity(ticketSeverityInput(ticket), now, POLICY).level).toBe('NORMAL');
  });

  it('keeps colouring from the ticket’s own instants when the server sends no order clock', () => {
    const ticket = {
      fulfilmentMode: 'DELIVERY',
      createdAt: minutes(-50),
      targetReadyAt: minutes(-1),
    };

    expect(ticketSeverityInput(ticket)).toEqual({
      promisedAt: new Date(minutes(-1)),
      createdAt: new Date(minutes(-50)),
      fulfilmentMode: 'DELIVERY',
    });
    expect(computeTicketSeverity(ticketSeverityInput(ticket), now, POLICY).level).toBe('BREACHED');
  });
});

describe('availableItemActions', () => {
  it('offers only START on a queued line', () => {
    expect(availableItemActions('QUEUED', 'FIRED')).toEqual(['START']);
  });

  it('offers only READY on a started line', () => {
    expect(availableItemActions('STARTED', 'IN_PRODUCTION')).toEqual(['READY']);
  });

  it('offers RECALL on a ready line, unless the ticket has been handed over', () => {
    expect(availableItemActions('READY', 'READY')).toEqual(['RECALL']);
    expect(availableItemActions('READY', 'HANDED_OVER')).toEqual([]);
  });

  it('offers nothing for a cancelled line or an unrecognised status', () => {
    expect(availableItemActions('CANCELLED', 'FIRED')).toEqual([]);
    expect(availableItemActions('SOMETHING_NEW', 'FIRED')).toEqual([]);
  });
});

describe('ticketItemRows (ADR 0136)', () => {
  const item = (itemId: string, comboSelectionId: string | null = null) => ({
    itemId,
    comboSelectionId,
  });

  it('puts a header ahead of each combo and keeps its components together, in the order they were ordered', () => {
    const rows = ticketItemRows(
      [item('a'), item('b', 'sel-1'), item('c'), item('d', 'sel-1'), item('e', 'sel-2')],
      (it) => (it.comboSelectionId === 'sel-1' ? 'Lunch box' : 'Family box'),
    );

    expect(rows.map((row) => (row.kind === 'combo' ? `[${row.name}]` : row.item.itemId))).toEqual([
      'a',
      '[Lunch box]',
      'b',
      'd',
      'c',
      '[Family box]',
      'e',
    ]);
    expect(rows.filter((row) => row.kind === 'item' && row.inCombo)).toHaveLength(3);
  });

  it('leaves an ordinary ticket exactly as it was: no header', () => {
    const rows = ticketItemRows([item('a'), item('b')], () => null);

    expect(rows.map((row) => row.kind)).toEqual(['item', 'item']);
  });

  it('keeps the grouping, without a name, when the line the item points at cannot be resolved', () => {
    const rows = ticketItemRows([item('a', 'sel-1')], () => null);

    expect(rows[0]).toEqual({ kind: 'combo', key: 'combo:sel-1', name: null });
  });

  it('takes the name from whichever component resolves', () => {
    const rows = ticketItemRows([item('a', 'sel-1'), item('b', 'sel-1')], (it) =>
      it.itemId === 'b' ? 'Lunch box' : null,
    );

    expect(rows[0]).toMatchObject({ kind: 'combo', name: 'Lunch box' });
  });
});

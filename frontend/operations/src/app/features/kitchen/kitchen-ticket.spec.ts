import { describe, expect, it } from 'vitest';

import { LatenessPolicy, PLATFORM_DEFAULT_LATENESS_POLICY } from '../../core/lateness-policy';
import {
  DEFAULT_KITCHEN_TAB,
  KITCHEN_TABS,
  availableItemActions,
  computeTicketSeverity,
  isKitchenTabId,
  isKitchenTabMember,
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
    expect(isKitchenTabId('aggregator')).toBe(false);
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
});

describe('computeTicketSeverity', () => {
  const now = new Date('2026-08-30T12:00:00Z');

  it('is NORMAL well before the target', () => {
    const severity = computeTicketSeverity(
      {
        targetReadyAt: new Date(now.getTime() + 20 * 60 * 1000),
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
        targetReadyAt: new Date(now.getTime() + windowMs - 1),
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
      { targetReadyAt: now, createdAt: now, fulfilmentMode: 'DELIVERY' },
      now,
      POLICY,
    );
    expect(severity.level).toBe('AT_RISK');
  });

  it('is BREACHED the instant after the target passes, with zero grace under the platform default', () => {
    const severity = computeTicketSeverity(
      { targetReadyAt: new Date(now.getTime() - 1), createdAt: now, fulfilmentMode: 'DELIVERY' },
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
        targetReadyAt: null,
        createdAt: new Date(now.getTime() - (fallbackMs - 1)),
        fulfilmentMode: 'DELIVERY',
      },
      now,
      POLICY,
    );
    const justOver = computeTicketSeverity(
      {
        targetReadyAt: null,
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
    const targetReadyAt = new Date(now.getTime() + 90 * 1000);

    expect(
      computeTicketSeverity(
        { targetReadyAt, createdAt: now, fulfilmentMode: 'DELIVERY' },
        now,
        policy,
      ).level,
    ).toBe('AT_RISK');
    expect(
      computeTicketSeverity(
        { targetReadyAt, createdAt: now, fulfilmentMode: 'PICKUP' },
        now,
        policy,
      ).level,
    ).toBe('NORMAL');
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

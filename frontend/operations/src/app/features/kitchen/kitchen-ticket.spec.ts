import { describe, expect, it } from 'vitest';

import {
  AT_RISK_THRESHOLD_MS,
  DEFAULT_KITCHEN_TAB,
  KITCHEN_TABS,
  NO_PROMISE_FALLBACK_MS,
  availableItemActions,
  computeTicketSeverity,
  isKitchenTabId,
  isKitchenTabMember,
} from './kitchen-ticket';

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
      { targetReadyAt: new Date(now.getTime() + 20 * 60 * 1000), createdAt: now },
      now,
    );
    expect(severity).toEqual({ level: 'NORMAL', tone: 'none' });
  });

  it('is AT_RISK inside the five-minute warning window before the target', () => {
    const severity = computeTicketSeverity(
      { targetReadyAt: new Date(now.getTime() + AT_RISK_THRESHOLD_MS - 1), createdAt: now },
      now,
    );
    expect(severity.level).toBe('AT_RISK');
    expect(severity.tone).toBe('warning');
  });

  it('is BREACHED the instant the target passes, not only strictly after it', () => {
    const severity = computeTicketSeverity({ targetReadyAt: now, createdAt: now }, now);
    expect(severity.level).toBe('BREACHED');
    expect(severity.tone).toBe('danger');
  });

  it('falls back to the 45-minute no-promise rule when there is no target', () => {
    const justUnder = computeTicketSeverity(
      { targetReadyAt: null, createdAt: new Date(now.getTime() - (NO_PROMISE_FALLBACK_MS - 1)) },
      now,
    );
    const justOver = computeTicketSeverity(
      { targetReadyAt: null, createdAt: new Date(now.getTime() - (NO_PROMISE_FALLBACK_MS + 1)) },
      now,
    );
    expect(justUnder.level).toBe('NORMAL');
    expect(justOver.level).toBe('BREACHED');
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

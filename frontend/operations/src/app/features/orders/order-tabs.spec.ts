import { describe, expect, it } from 'vitest';

import { DEFAULT_ORDER_TAB, ORDER_TABS, isOrderTabId, isOrderTabMember } from './order-tabs';

describe('order tabs', () => {
  it('defaults to attention, per §0.4 — the board opens on what needs a human, not on the newest order', () => {
    expect(DEFAULT_ORDER_TAB).toBe('attention');
  });

  describe('isOrderTabId', () => {
    it('accepts every declared tab id', () => {
      for (const tab of ORDER_TABS) {
        expect(isOrderTabId(tab)).toBe(true);
      }
    });

    it('rejects null, empty, and an unrecognised value — the query param falls back to the default', () => {
      expect(isOrderTabId(null)).toBe(false);
      expect(isOrderTabId('')).toBe(false);
      expect(isOrderTabId('urgent')).toBe(false);
    });
  });

  describe('isOrderTabMember', () => {
    function member(
      status: string,
      severityLevel:
        'BLOCKED' | 'AWAITING_APPROVAL_DEADLINE' | 'NO_PROMISE_FALLBACK' | 'NORMAL' = 'NORMAL',
    ) {
      return { status, severityLevel };
    }

    it('groups attention by status membership or any severity flag, not by status alone', () => {
      expect(isOrderTabMember('attention', member('AWAITING_APPROVAL'))).toBe(true);
      expect(isOrderTabMember('attention', member('PAYMENT_FAILED'))).toBe(true);
      expect(isOrderTabMember('attention', member('PREPARING', 'NO_PROMISE_FALLBACK'))).toBe(true);
      expect(isOrderTabMember('attention', member('PREPARING'))).toBe(false);
    });

    it('groups new as RECEIVED, PAYMENT_AUTHORIZING and AWAITING_APPROVAL', () => {
      expect(isOrderTabMember('new', member('RECEIVED'))).toBe(true);
      expect(isOrderTabMember('new', member('PAYMENT_AUTHORIZING'))).toBe(true);
      expect(isOrderTabMember('new', member('AWAITING_APPROVAL'))).toBe(true);
      expect(isOrderTabMember('new', member('CONFIRMED'))).toBe(false);
    });

    it('groups preparing as CONFIRMED, PREPARING and READY', () => {
      expect(isOrderTabMember('preparing', member('CONFIRMED'))).toBe(true);
      expect(isOrderTabMember('preparing', member('PREPARING'))).toBe(true);
      expect(isOrderTabMember('preparing', member('READY'))).toBe(true);
      expect(isOrderTabMember('preparing', member('FULFILLING'))).toBe(false);
    });

    it('groups delivering as FULFILLING only', () => {
      expect(isOrderTabMember('delivering', member('FULFILLING'))).toBe(true);
      expect(isOrderTabMember('delivering', member('READY'))).toBe(false);
    });

    it('groups completed as COMPLETED only', () => {
      expect(isOrderTabMember('completed', member('COMPLETED'))).toBe(true);
      expect(isOrderTabMember('completed', member('CANCELLED'))).toBe(false);
    });

    it('groups cancelled as CANCELLED, REJECTED and EXPIRED', () => {
      expect(isOrderTabMember('cancelled', member('CANCELLED'))).toBe(true);
      expect(isOrderTabMember('cancelled', member('REJECTED'))).toBe(true);
      expect(isOrderTabMember('cancelled', member('EXPIRED'))).toBe(true);
      expect(isOrderTabMember('cancelled', member('COMPLETED'))).toBe(false);
    });

    it('puts everything, known status or not, under all', () => {
      expect(isOrderTabMember('all', member('RECEIVED'))).toBe(true);
      expect(isOrderTabMember('all', member('SOMETHING_UNKNOWN'))).toBe(true);
    });

    it('lets an attention-tab order also appear in its own status tab — not a partition', () => {
      const awaitingApproval = member('AWAITING_APPROVAL');
      expect(isOrderTabMember('attention', awaitingApproval)).toBe(true);
      expect(isOrderTabMember('new', awaitingApproval)).toBe(true);
    });
  });
});

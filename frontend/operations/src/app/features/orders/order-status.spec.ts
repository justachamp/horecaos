import { describe, expect, it } from 'vitest';

import {
  ORDER_STATUSES,
  ORDER_STATUS_LABEL_KEYS,
  TERMINAL_ORDER_STATUSES,
  isKnownOrderStatus,
  isTerminalOrderStatus,
  orderStatusLabel,
} from './order-status';

describe('order status vocabulary', () => {
  it('recognises exactly the twelve canonical statuses', () => {
    expect(ORDER_STATUSES).toHaveLength(12);
    for (const status of ORDER_STATUSES) {
      expect(isKnownOrderStatus(status)).toBe(true);
    }
  });

  it('rejects anything not in the twelve, including a lowercase or partial match', () => {
    expect(isKnownOrderStatus('received')).toBe(false);
    expect(isKnownOrderStatus('RECEIVE')).toBe(false);
    expect(isKnownOrderStatus('SOMETHING_NEW_FROM_A_LATER_SERVER')).toBe(false);
    expect(isKnownOrderStatus('')).toBe(false);
  });

  it('has a label key for every known status', () => {
    for (const status of ORDER_STATUSES) {
      expect(ORDER_STATUS_LABEL_KEYS[status]).toBe(`orders.status.${status}`);
    }
  });

  it('treats completed, cancelled, rejected and expired as terminal', () => {
    expect([...TERMINAL_ORDER_STATUSES].sort()).toEqual(
      ['CANCELLED', 'COMPLETED', 'EXPIRED', 'REJECTED'].sort(),
    );
    for (const status of TERMINAL_ORDER_STATUSES) {
      expect(isTerminalOrderStatus(status)).toBe(true);
    }
  });

  it('treats every live status as non-terminal', () => {
    const live = ORDER_STATUSES.filter((status) => !TERMINAL_ORDER_STATUSES.includes(status));
    expect(live).toEqual([
      'RECEIVED',
      'PAYMENT_AUTHORIZING',
      'AWAITING_APPROVAL',
      'PAYMENT_FAILED',
      'CONFIRMED',
      'PREPARING',
      'READY',
      'FULFILLING',
    ]);
    for (const status of live) {
      expect(isTerminalOrderStatus(status)).toBe(false);
    }
  });

  describe('orderStatusLabel', () => {
    const translate = (key: string) => `[${key}]`;

    it('translates a known status through the supplied translator', () => {
      expect(orderStatusLabel('PREPARING', translate)).toBe('[orders.status.PREPARING]');
    });

    it('renders an unknown status harmlessly, as its own raw value', () => {
      // The failure this guards against: a newer server adds a status and this
      // client throws, or blanks the row, instead of just showing the code.
      expect(orderStatusLabel('ON_HOLD_FOR_STOCK', translate)).toBe('ON_HOLD_FOR_STOCK');
      expect(() => orderStatusLabel('', translate)).not.toThrow();
    });
  });
});

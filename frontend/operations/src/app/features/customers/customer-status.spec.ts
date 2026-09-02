import { describe, expect, it } from 'vitest';

import {
  CUSTOMER_STATUSES,
  CUSTOMER_STATUS_LABEL_KEYS,
  customerStatusLabel,
  isKnownCustomerStatus,
} from './customer-status';

describe('customer status vocabulary', () => {
  it('recognises exactly the five canonical statuses', () => {
    expect(CUSTOMER_STATUSES).toHaveLength(5);
    for (const status of CUSTOMER_STATUSES) {
      expect(isKnownCustomerStatus(status)).toBe(true);
    }
  });

  it('rejects anything not in the five, including a lowercase or partial match', () => {
    expect(isKnownCustomerStatus('active')).toBe(false);
    expect(isKnownCustomerStatus('ACTIV')).toBe(false);
    expect(isKnownCustomerStatus('SOMETHING_NEW_FROM_A_LATER_SERVER')).toBe(false);
    expect(isKnownCustomerStatus('')).toBe(false);
  });

  it('has a label key for every known status', () => {
    for (const status of CUSTOMER_STATUSES) {
      expect(CUSTOMER_STATUS_LABEL_KEYS[status]).toBe(`customers.status.${status}`);
    }
  });

  describe('customerStatusLabel', () => {
    const translate = (key: string) => `[${key}]`;

    it('translates a known status through the supplied translator', () => {
      expect(customerStatusLabel('SUSPENDED', translate)).toBe('[customers.status.SUSPENDED]');
    });

    it('renders an unknown status harmlessly, as its own raw value', () => {
      // The failure this guards against: a newer server adds a status and this
      // client throws, or blanks the row, instead of just showing the code.
      expect(customerStatusLabel('ON_HOLD', translate)).toBe('ON_HOLD');
      expect(() => customerStatusLabel('', translate)).not.toThrow();
    });
  });
});

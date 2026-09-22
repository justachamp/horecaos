import { describe, expect, it } from 'vitest';

import {
  ORDER_PAYMENT_STATUS_PROJECTIONS,
  isKnownPaymentStatusProjection,
  paymentStatusProjectionLabel,
} from './order-payment-status';

describe('order payment status projection (ordering.orders.payment_status_projection)', () => {
  it('recognises exactly the seven values ck_order_payment_projection allows', () => {
    expect(ORDER_PAYMENT_STATUS_PROJECTIONS).toHaveLength(7);
    for (const value of ORDER_PAYMENT_STATUS_PROJECTIONS) {
      expect(isKnownPaymentStatusProjection(value)).toBe(true);
    }
  });

  it('rejects a value not in the seven', () => {
    expect(isKnownPaymentStatusProjection('SETTLED')).toBe(false);
    expect(isKnownPaymentStatusProjection('')).toBe(false);
  });

  describe('paymentStatusProjectionLabel', () => {
    const translate = (key: string) => `[${key}]`;

    it('renders NOT_REQUIRED as a dash rather than a fabricated "cash" label — ADR 0013 is not built', () => {
      expect(paymentStatusProjectionLabel('NOT_REQUIRED', translate)).toBe('—');
    });

    it('renders a missing value (a fixture or an interim response minted before this field existed) the same as NOT_REQUIRED', () => {
      expect(paymentStatusProjectionLabel(undefined, translate)).toBe('—');
      expect(paymentStatusProjectionLabel(null, translate)).toBe('—');
    });

    it('translates every one of the six real statuses through the supplied translator', () => {
      expect(paymentStatusProjectionLabel('PENDING', translate)).toBe(
        '[orders.paymentStatus.PENDING]',
      );
      expect(paymentStatusProjectionLabel('AUTHORIZED', translate)).toBe(
        '[orders.paymentStatus.AUTHORIZED]',
      );
      expect(paymentStatusProjectionLabel('CAPTURED', translate)).toBe(
        '[orders.paymentStatus.CAPTURED]',
      );
      expect(paymentStatusProjectionLabel('FAILED', translate)).toBe(
        '[orders.paymentStatus.FAILED]',
      );
      expect(paymentStatusProjectionLabel('VOIDED', translate)).toBe(
        '[orders.paymentStatus.VOIDED]',
      );
      expect(paymentStatusProjectionLabel('REFUNDED', translate)).toBe(
        '[orders.paymentStatus.REFUNDED]',
      );
    });

    it('renders an unrecognised value harmlessly, as its own raw value, never throwing', () => {
      expect(paymentStatusProjectionLabel('SETTLED_LATER', translate)).toBe('SETTLED_LATER');
      expect(() => paymentStatusProjectionLabel('', translate)).not.toThrow();
    });
  });
});

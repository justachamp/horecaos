import { ECOMMERCE_CONTRACT_VERSION, pushEcommerceEvent } from './ecommerce-events';

/**
 * The GA4 ecommerce event contract, v1 (ADR 0106, gap-map row `10.8e`) —
 * `docs/analytics/ga4-ecommerce-event-contract-v1.md` is the full write-up;
 * this proves the one function that ever writes to `window.dataLayer` keeps
 * its promise: every event versioned, `dataLayer` created if absent, and
 * nothing thrown when there is nothing downstream to read it yet.
 */
describe('pushEcommerceEvent', () => {
  beforeEach(() => {
    delete (window as { dataLayer?: unknown[] }).dataLayer;
  });

  it('creates window.dataLayer if nothing has touched it yet', () => {
    expect(window.dataLayer).toBeUndefined();

    pushEcommerceEvent('purchase', { currency: 'UZS', value: 45000, items: [] });

    expect(Array.isArray(window.dataLayer)).toBe(true);
    expect(window.dataLayer?.length).toBe(1);
  });

  it('stamps every event with the current contract version, never left implicit', () => {
    pushEcommerceEvent('purchase', { currency: 'UZS', value: 45000, items: [] });

    expect(window.dataLayer?.[0]).toMatchObject({ contractVersion: ECOMMERCE_CONTRACT_VERSION });
  });

  it('carries the transaction id, the currency, the value and every line item exactly as given', () => {
    pushEcommerceEvent('purchase', {
      currency: 'UZS',
      value: 128000,
      transaction_id: 'order-42',
      items: [{ item_id: 'v-1', item_name: 'Lagman', price: 32000, quantity: 4 }],
    });

    expect(window.dataLayer?.[0]).toEqual({
      contractVersion: ECOMMERCE_CONTRACT_VERSION,
      event: 'purchase',
      ecommerce: {
        currency: 'UZS',
        value: 128000,
        transaction_id: 'order-42',
        items: [{ item_id: 'v-1', item_name: 'Lagman', price: 32000, quantity: 4 }],
      },
    });
  });

  it('appends rather than replaces, so a page pushing several events keeps every one', () => {
    pushEcommerceEvent('view_item', { currency: 'UZS', value: 32000, items: [] });
    pushEcommerceEvent('add_to_cart', { currency: 'UZS', value: 32000, items: [] });

    expect(window.dataLayer?.length).toBe(2);
    expect((window.dataLayer?.[0] as { event: string }).event).toBe('view_item');
    expect((window.dataLayer?.[1] as { event: string }).event).toBe('add_to_cart');
  });
});

import { describe, expect, it } from 'vitest';

import { LocationScope, operationsPaths } from './operations-paths';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

describe('operationsPaths.customerOrderReorder', () => {
  /**
   * Rows 1.3f/1.3a: the New Order screen's own «Повторить» must land on
   * `CustomerOrderReorderController` — `LOCATION`-scoped, naming the
   * operator's own branch — not `CustomerOrderHistoryController.reorderPlan`'s
   * `BRAND`-scoped path, which `LOCATION_STAFF` (this screen's own persona)
   * cannot reach. A regression here would silently 403 the button again
   * while every mocked-`CustomersApi` spec (`new-order-page.spec.ts`) kept
   * passing, because those never inspect the URL this builds.
   */
  it('builds the LOCATION-scoped path, not the brand-scoped Customers-section one', () => {
    expect(operationsPaths.customerOrderReorder(SCOPE, 'acct-1', 'order-1')).toBe(
      '/api/v1/tenants/t1/brands/b1/locations/l1/customers/acct-1/orders/order-1/reorder',
    );
  });

  it('encodes every identifier, including a slash smuggled into one', () => {
    expect(
      operationsPaths.customerOrderReorder(
        { tenantId: 't/1', brandId: 'b1', locationId: 'l1' },
        'acct 1',
        'order-1',
      ),
    ).toBe('/api/v1/tenants/t%2F1/brands/b1/locations/l1/customers/acct%201/orders/order-1/reorder');
  });
});

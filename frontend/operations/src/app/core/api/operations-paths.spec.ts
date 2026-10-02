import { describe, expect, it } from 'vitest';

import { LocationScope, operationsPaths } from './operations-paths';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

/**
 * Batch 11's pre-existing bug: `InventoryController`'s real `@RequestMapping`
 * is `/api/v1/tenants/{t}/brands/{b}/locations/{l}/inventory`, with no
 * `/operations` segment, but `inventoryStockItems`, `inventoryVariantAvailability`
 * and `inventoryBulkAvailability` were built on the `OPERATIONS` prefix from the
 * start — every real call 404'd. These assert the literal path each builder
 * produces, not merely that the builder equals itself (`stop-list-page.spec.ts`
 * carried exactly that self-comparison, which cannot fail regardless of which
 * prefix is wrong).
 */
describe('operationsPaths inventory* group (row 4.4c/4.4d InventoryController path fix)', () => {
  it('inventoryStockItems builds on the legacy tenant prefix, not /operations', () => {
    expect(operationsPaths.inventoryStockItems(SCOPE)).toBe(
      '/api/v1/tenants/t1/brands/b1/locations/l1/inventory/stock-items',
    );
  });

  it('inventoryVariantAvailability builds on the legacy tenant prefix, not /operations', () => {
    expect(operationsPaths.inventoryVariantAvailability(SCOPE, 'v1')).toBe(
      '/api/v1/tenants/t1/brands/b1/locations/l1/inventory/variants/v1/availability',
    );
  });

  it('inventoryBulkAvailability builds on the legacy tenant prefix, not /operations', () => {
    expect(operationsPaths.inventoryBulkAvailability(SCOPE)).toBe(
      '/api/v1/tenants/t1/brands/b1/locations/l1/inventory/variants/bulk-availability',
    );
  });

  it('inventoryAvailability builds on the legacy tenant prefix, not /operations', () => {
    expect(operationsPaths.inventoryAvailability(SCOPE)).toBe(
      '/api/v1/tenants/t1/brands/b1/locations/l1/inventory/availability',
    );
  });

  it('inventoryPositions builds on the legacy tenant prefix, not /operations', () => {
    expect(operationsPaths.inventoryPositions(SCOPE)).toBe(
      '/api/v1/tenants/t1/brands/b1/locations/l1/inventory/positions',
    );
  });

  it('inventoryOnHand builds on the legacy tenant prefix, not /operations', () => {
    expect(operationsPaths.inventoryOnHand(SCOPE, 'v1')).toBe(
      '/api/v1/tenants/t1/brands/b1/locations/l1/inventory/variants/v1/on-hand',
    );
  });

  it('inventoryQuantityDefault builds on the legacy tenant prefix, not /operations', () => {
    expect(operationsPaths.inventoryQuantityDefault(SCOPE, 'v1')).toBe(
      '/api/v1/tenants/t1/brands/b1/locations/l1/inventory/variants/v1/quantity-defaults',
    );
  });

  it('inventoryChannelStopThreshold builds on the legacy tenant prefix, not /operations', () => {
    expect(operationsPaths.inventoryChannelStopThreshold(SCOPE, 'v1', 'AGGREGATOR')).toBe(
      '/api/v1/tenants/t1/brands/b1/locations/l1/inventory/variants/v1/channel-stop-thresholds/AGGREGATOR',
    );
  });

  it('the ADR 0141 stop paths build on the legacy tenant prefix, at the branch and at the brand', () => {
    expect(operationsPaths.inventoryStopsAtLocation(SCOPE)).toBe(
      '/api/v1/tenants/t1/brands/b1/locations/l1/inventory/stops',
    );
    expect(operationsPaths.inventoryStopAtLocation(SCOPE, 's 1')).toBe(
      '/api/v1/tenants/t1/brands/b1/locations/l1/inventory/stops/s%201',
    );
    expect(operationsPaths.inventoryStopsAtBrand(SCOPE)).toBe(
      '/api/v1/tenants/t1/brands/b1/inventory/stops',
    );
    expect(operationsPaths.inventoryStopAtBrand(SCOPE, 's1')).toBe(
      '/api/v1/tenants/t1/brands/b1/inventory/stops/s1',
    );
  });

  it('the marketplace propagation and explainer paths build on the legacy tenant prefix', () => {
    expect(operationsPaths.inventoryMarketplacePropagation(SCOPE)).toBe(
      '/api/v1/tenants/t1/brands/b1/locations/l1/inventory/marketplace-propagation',
    );
    expect(operationsPaths.inventoryAvailabilityExplanation(SCOPE, 'v1')).toBe(
      '/api/v1/tenants/t1/brands/b1/locations/l1/inventory/variants/v1/availability-explanation',
    );
  });

  it('inventoryListingBackfill builds on the legacy tenant prefix, not /operations', () => {
    expect(operationsPaths.inventoryListingBackfill(SCOPE)).toBe(
      '/api/v1/tenants/t1/brands/b1/locations/l1/inventory/listing-backfill',
    );
  });

  it('inventoryUnlistedOfferings builds on the legacy tenant prefix, next to the backfill it feeds', () => {
    expect(operationsPaths.inventoryUnlistedOfferings(SCOPE)).toBe(
      '/api/v1/tenants/t1/brands/b1/locations/l1/inventory/unlisted-offerings',
    );
  });

  it('inventoryVariantListing is brand-scoped, with no location segment', () => {
    expect(operationsPaths.inventoryVariantListing(SCOPE, 'v1')).toBe(
      '/api/v1/tenants/t1/brands/b1/variants/v1/inventory-listing',
    );
  });

  it('inventoryVariantListingBackfill posts to the same brand-scoped path GET reads', () => {
    expect(operationsPaths.inventoryVariantListingBackfill(SCOPE, 'v1')).toBe(
      operationsPaths.inventoryVariantListing(SCOPE, 'v1'),
    );
  });
});

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
    ).toBe(
      '/api/v1/tenants/t%2F1/brands/b1/locations/l1/customers/acct%201/orders/order-1/reorder',
    );
  });
});

/**
 * `TableSessionController`'s real `@RequestMapping` is
 * `/api/v1/tenants/{t}/brands/{b}/locations/{l}/dine-in/sessions` -- the legacy
 * tenant prefix, with no `/operations` segment and no `/reservations` one. The
 * floor plan's "Seat walk-in" and the New Order screen's table picker both call
 * it, so the literal path is what these pin, not the builder against itself.
 */
describe('operationsPaths dine-in sessions (ADR 0047, TableSessionController)', () => {
  it('dineInSessions builds on the legacy tenant prefix', () => {
    expect(operationsPaths.dineInSessions(SCOPE)).toBe(
      '/api/v1/tenants/t1/brands/b1/locations/l1/dine-in/sessions',
    );
  });

  it('dineInSessionRounds names the session, encoded, under the same prefix', () => {
    expect(operationsPaths.dineInSessionRounds(SCOPE, 's1')).toBe(
      '/api/v1/tenants/t1/brands/b1/locations/l1/dine-in/sessions/s1/rounds',
    );
    expect(operationsPaths.dineInSessionRounds(SCOPE, 'a/b')).toBe(
      '/api/v1/tenants/t1/brands/b1/locations/l1/dine-in/sessions/a%2Fb/rounds',
    );
  });
});

/**
 * Wave 16, gap map rows `1.1`/`1.1c`: the brand-scoped board lives on the
 * ADR 0031 `/operations` prefix (a new endpoint, born there), while the branch
 * board it widens still sits on the legacy tenant prefix. Getting the two
 * backwards 404s with no clue why, so the literal paths are pinned.
 */
describe('operationsPaths order board scopes (OperationsBrandOrderController)', () => {
  it('orderBoard is the branch board on the legacy tenant prefix', () => {
    expect(operationsPaths.orderBoard(SCOPE)).toBe(
      '/api/v1/tenants/t1/brands/b1/locations/l1/orders/board',
    );
  });

  it('brandOrderBoard is brand-scoped, on the /operations prefix, with no location segment', () => {
    expect(operationsPaths.brandOrderBoard(SCOPE)).toBe(
      '/api/v1/operations/tenants/t1/brands/b1/orders/board',
    );
  });

  it('the binding options follow the same split', () => {
    expect(operationsPaths.orderMarketplaceBindings(SCOPE)).toBe(
      '/api/v1/tenants/t1/brands/b1/locations/l1/orders/marketplace-bindings',
    );
    expect(operationsPaths.brandOrderMarketplaceBindings(SCOPE)).toBe(
      '/api/v1/operations/tenants/t1/brands/b1/orders/marketplace-bindings',
    );
  });

  it('encodes every identifier, including a slash smuggled into one', () => {
    expect(
      operationsPaths.brandOrderBoard({ tenantId: 'a/b', brandId: 'c/d', locationId: 'l1' }),
    ).toBe('/api/v1/operations/tenants/a%2Fb/brands/c%2Fd/orders/board');
  });
});

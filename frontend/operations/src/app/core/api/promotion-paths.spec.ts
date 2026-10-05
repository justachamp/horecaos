import { describe, expect, it } from 'vitest';

import { promotionPaths } from './promotion-paths';

const SCOPE = { tenantId: 't1', brandId: 'b1' };

/**
 * These assert the literal path each builder produces against
 * `PromotionController`'s `@RequestMapping`, not that a builder equals itself.
 */
describe('promotionPaths: who redeemed it (ADR 0140, row 7.9)', () => {
  it('builds the audited customer reveal on the operations prefix, under the redemption', () => {
    expect(promotionPaths.redemptionCustomerReveal(SCOPE, 'p1', 'r1')).toBe(
      '/api/v1/operations/tenants/t1/brands/b1/promotions/p1/redemptions/r1/customer-reveal',
    );
  });

  it('encodes every segment, so an id cannot escape its place in the path', () => {
    expect(promotionPaths.redemptionCustomerReveal(SCOPE, 'a/b', 'c d')).toBe(
      '/api/v1/operations/tenants/t1/brands/b1/promotions/a%2Fb/redemptions/c%20d/customer-reveal',
    );
  });
});

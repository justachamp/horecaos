import { describe, expect, it } from 'vitest';

import { dispatchRulesPaths } from './delivery-paths';

/**
 * The literal path each builder produces, not that the builder equals itself: a self-comparison
 * passes however wrong the prefix is, which is how `inventoryStockItems` 404'd for a wave.
 */
describe('dispatchRulesPaths (ADR 0142)', () => {
  it('builds the document, its pickers, its usage and its simulator under the tenant', () => {
    expect(dispatchRulesPaths.rules('t1')).toBe('/api/v1/operations/tenants/t1/dispatch-rules');
    expect(dispatchRulesPaths.options('t1')).toBe(
      '/api/v1/operations/tenants/t1/dispatch-rules/options',
    );
    expect(dispatchRulesPaths.usage('t1')).toBe(
      '/api/v1/operations/tenants/t1/dispatch-rules/usage',
    );
    expect(dispatchRulesPaths.simulations('t1')).toBe(
      '/api/v1/operations/tenants/t1/dispatch-rules/simulations',
    );
  });

  it('builds the timings and the payment window beside it, not under it', () => {
    expect(dispatchRulesPaths.timings('t1')).toBe('/api/v1/operations/tenants/t1/sourcing-policy');
    expect(dispatchRulesPaths.paymentWindow('t1')).toBe(
      '/api/v1/operations/tenants/t1/payment-window',
    );
  });

  it('encodes an identifier rather than trusting it', () => {
    expect(dispatchRulesPaths.rules('a/b')).toBe('/api/v1/operations/tenants/a%2Fb/dispatch-rules');
  });
});

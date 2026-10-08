import { describe, expect, it } from 'vitest';

import { marketingPaths } from './marketing-paths';

const SCOPE = { tenantId: 't1', brandId: 'b1' };
const BASE = '/api/v1/tenants/t1/brands/b1/marketing';

/**
 * These assert the literal path each builder produces against the controllers'
 * `@RequestMapping`s (`ScenarioController`, `OfferController`, `ContactPolicyController`),
 * not that a builder equals itself.
 */
describe('marketingPaths: scenarios (ADR 0112)', () => {
  it('builds the scenario collection and one scenario', () => {
    expect(marketingPaths.scenarios(SCOPE)).toBe(`${BASE}/scenarios`);
    expect(marketingPaths.scenario(SCOPE, 'c1')).toBe(`${BASE}/scenarios/c1`);
  });

  it('builds what only a scenario has: steps, revisions, decisions and results', () => {
    expect(marketingPaths.scenarioSteps(SCOPE, 'c1')).toBe(`${BASE}/scenarios/c1/steps`);
    expect(marketingPaths.scenarioRevisions(SCOPE, 'c1')).toBe(`${BASE}/scenarios/c1/revisions`);
    expect(marketingPaths.scenarioDecisions(SCOPE, 'c1')).toBe(`${BASE}/scenarios/c1/decisions`);
    expect(marketingPaths.scenarioResults(SCOPE, 'c1')).toBe(`${BASE}/scenarios/c1/results`);
  });

  it('encodes every segment, so an id cannot escape its place in the path', () => {
    expect(marketingPaths.scenario(SCOPE, 'a/b')).toBe(`${BASE}/scenarios/a%2Fb`);
  });
});

describe('marketingPaths: offers (ADR 0112)', () => {
  it('builds the offer collection, one offer and its lineage', () => {
    expect(marketingPaths.offers(SCOPE)).toBe(`${BASE}/offers`);
    expect(marketingPaths.offer(SCOPE, 'o1')).toBe(`${BASE}/offers/o1`);
    expect(marketingPaths.offerVersions(SCOPE, 'o1')).toBe(`${BASE}/offers/o1/versions`);
  });

  it('builds the two acts that change what is in force: publication and retirement', () => {
    expect(marketingPaths.offerPublications(SCOPE, 'o1')).toBe(`${BASE}/offers/o1/publications`);
    expect(marketingPaths.offerRetirements(SCOPE, 'o1')).toBe(`${BASE}/offers/o1/retirements`);
  });
});

describe('marketingPaths: contact policy (ADR 0112)', () => {
  it('builds the policy, its defaults and the override collection', () => {
    expect(marketingPaths.contactPolicy(SCOPE)).toBe(`${BASE}/contact-policy`);
    expect(marketingPaths.contactPolicyDefaults(SCOPE)).toBe(`${BASE}/contact-policy/defaults`);
    expect(marketingPaths.contactPolicyOverrides(SCOPE)).toBe(`${BASE}/contact-policy/overrides`);
  });

  it('addresses one override by channel, purpose and period, in that order', () => {
    expect(
      marketingPaths.contactPolicyOverride(SCOPE, 'SMS', 'MARKETING_PROMOTIONS', 'DAILY'),
    ).toBe(`${BASE}/contact-policy/overrides/SMS/MARKETING_PROMOTIONS/DAILY`);
  });

  it('encodes a purpose that is not a path-safe word', () => {
    expect(marketingPaths.contactPolicyOverride(SCOPE, 'SMS', 'a b', 'DAILY')).toBe(
      `${BASE}/contact-policy/overrides/SMS/a%20b/DAILY`,
    );
  });
});

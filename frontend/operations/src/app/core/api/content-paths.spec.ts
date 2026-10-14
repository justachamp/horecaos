import { describe, expect, it } from 'vitest';

import { contentPaths } from './content-paths';

const scope = { tenantId: 't-1', brandId: 'b 2' };
const root = '/api/v1/tenants/t-1/brands/b%202/content';

describe('contentPaths (ADR 0157, Staff API)', () => {
  it('addresses an item, its draft and each lifecycle act under the brand', () => {
    expect(contentPaths.items(scope)).toBe(`${root}/items`);
    expect(contentPaths.item(scope, 'i/1')).toBe(`${root}/items/i%2F1`);
    expect(contentPaths.draft(scope, 'i')).toBe(`${root}/items/i/draft`);
    expect(contentPaths.publications(scope, 'i')).toBe(`${root}/items/i/publications`);
    expect(contentPaths.withdrawals(scope, 'i')).toBe(`${root}/items/i/withdrawals`);
    expect(contentPaths.archives(scope, 'i')).toBe(`${root}/items/i/archives`);
    expect(contentPaths.stats(scope, 'i')).toBe(`${root}/items/i/stats`);
  });

  it('addresses revisions and the republication of one', () => {
    expect(contentPaths.revisions(scope, 'i')).toBe(`${root}/items/i/revisions`);
    expect(contentPaths.revision(scope, 'i', 3)).toBe(`${root}/items/i/revisions/3`);
    expect(contentPaths.republications(scope, 'i', 3)).toBe(
      `${root}/items/i/revisions/3/republications`,
    );
  });

  it('addresses a placement order, the preview, the health read, the allowlist and the policies', () => {
    expect(contentPaths.placementOrder(scope, 'HOME_CAROUSEL')).toBe(
      `${root}/placements/HOME_CAROUSEL/order`,
    );
    expect(contentPaths.preview(scope)).toBe(`${root}/preview`);
    expect(contentPaths.health(scope)).toBe(`${root}/health`);
    expect(contentPaths.allowedHosts(scope)).toBe(`${root}/allowed-hosts`);
    expect(contentPaths.allowedHost(scope, 'shop.example.uz')).toBe(
      `${root}/allowed-hosts/shop.example.uz`,
    );
    expect(contentPaths.policies(scope)).toBe(`${root}/policies`);
    expect(contentPaths.publishingPolicy(scope)).toBe(`${root}/policies/publishing`);
    expect(contentPaths.editorialPolicy(scope)).toBe(`${root}/policies/editorial`);
    expect(contentPaths.layoutPolicy(scope)).toBe(`${root}/policies/layout`);
  });

  it('addresses the brand switcher and the storefront branch order (row 6.8)', () => {
    expect(contentPaths.switcherEntries(scope)).toBe(`${root}/brand-switcher/entries`);
    expect(contentPaths.switcherEntry(scope, 'e/1')).toBe(`${root}/brand-switcher/entries/e%2F1`);
    expect(contentPaths.switcherCandidates(scope)).toBe(`${root}/brand-switcher/candidates`);
    expect(contentPaths.switcherOrder(scope)).toBe(`${root}/brand-switcher/order`);
    expect(contentPaths.branchOrder(scope)).toBe(
      '/api/v1/operations/tenants/t-1/brands/b%202/storefront-branch-order',
    );
  });
});

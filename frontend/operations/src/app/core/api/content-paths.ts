import { BrandScope } from './catalog-paths';

/**
 * Where a brand's storefront content is authored (ADR 0157, Staff API: `ContentOperationsController`).
 *
 * Brand-scoped throughout -- content belongs to a brand, never to a location -- and, like the
 * Marketing controller beside it, on the `/api/v1/tenants` prefix. Every segment is encoded; a host
 * in the allowlist path is a DNS name, but nothing here trusts that.
 */
const TENANT = '/api/v1/tenants';

function enc(value: string): string {
  return encodeURIComponent(value);
}

function base(scope: BrandScope): string {
  return `${TENANT}/${enc(scope.tenantId)}/brands/${enc(scope.brandId)}/content`;
}

export const contentPaths = {
  items(scope: BrandScope): string {
    return `${base(scope)}/items`;
  },

  item(scope: BrandScope, itemId: string): string {
    return `${this.items(scope)}/${enc(itemId)}`;
  },

  draft(scope: BrandScope, itemId: string): string {
    return `${this.item(scope, itemId)}/draft`;
  },

  publications(scope: BrandScope, itemId: string): string {
    return `${this.item(scope, itemId)}/publications`;
  },

  withdrawals(scope: BrandScope, itemId: string): string {
    return `${this.item(scope, itemId)}/withdrawals`;
  },

  archives(scope: BrandScope, itemId: string): string {
    return `${this.item(scope, itemId)}/archives`;
  },

  stats(scope: BrandScope, itemId: string): string {
    return `${this.item(scope, itemId)}/stats`;
  },

  revisions(scope: BrandScope, itemId: string): string {
    return `${this.item(scope, itemId)}/revisions`;
  },

  revision(scope: BrandScope, itemId: string, revisionNo: number): string {
    return `${this.revisions(scope, itemId)}/${revisionNo}`;
  },

  republications(scope: BrandScope, itemId: string, revisionNo: number): string {
    return `${this.revision(scope, itemId, revisionNo)}/republications`;
  },

  placementOrder(scope: BrandScope, placement: string): string {
    return `${base(scope)}/placements/${enc(placement)}/order`;
  },

  preview(scope: BrandScope): string {
    return `${base(scope)}/preview`;
  },

  health(scope: BrandScope): string {
    return `${base(scope)}/health`;
  },

  allowedHosts(scope: BrandScope): string {
    return `${base(scope)}/allowed-hosts`;
  },

  allowedHost(scope: BrandScope, host: string): string {
    return `${this.allowedHosts(scope)}/${enc(host)}`;
  },

  policies(scope: BrandScope): string {
    return `${base(scope)}/policies`;
  },

  publishingPolicy(scope: BrandScope): string {
    return `${this.policies(scope)}/publishing`;
  },

  editorialPolicy(scope: BrandScope): string {
    return `${this.policies(scope)}/editorial`;
  },
} as const;

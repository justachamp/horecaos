import { LocationScope } from './operations-paths';

/**
 * `GET /api/v1/operations/tenants/{tenantId}/brands` (`OperationsBrandController.list`) -- the one
 * Settings path the console's **initial bundle** needs.
 *
 * `CurrentBrand` is built before any route opens, so it is eager; `settings-paths.ts`, where this
 * builder used to live, is the path table of the whole Settings section (some twenty-five builders and
 * their long account of which surface each one answers on), and importing it from `CurrentBrand` dragged
 * all of it into the entry chunk for the sake of one template literal. This module holds that literal and
 * nothing else; `settingsPaths.brands` delegates to it, so the two cannot name different URLs, and the
 * rest of the table is now a lazy chunk the Settings routes load.
 */
export function brandListPath(scope: Pick<LocationScope, 'tenantId'>): string {
  return `/api/v1/operations/tenants/${encodeURIComponent(scope.tenantId)}/brands`;
}

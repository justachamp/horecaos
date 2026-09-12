import { describe, expect, it } from 'vitest';

import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { accessRefusal } from './order-errors';

/**
 * Only `accessRefusal` (operations IA §9.1d) — `describeApiError` and its
 * siblings are already exercised indirectly by every page that calls them.
 */
describe('accessRefusal', () => {
  it('reads a missing-capability refusal as denied, naming the capability', () => {
    const error = new ApiError(
      ApiErrorCode.INSUFFICIENT_CAPABILITY,
      403,
      { status: 403, requiredCapability: 'IAM_GRANT_MANAGE', requiredScope: 'TENANT' },
      'corr-1',
    );

    expect(accessRefusal(error)).toEqual({ kind: 'denied', name: 'IAM_GRANT_MANAGE' });
  });

  it('reads a missing-entitlement refusal as locked, naming the entitlement', () => {
    const error = new ApiError(
      ApiErrorCode.ENTITLEMENT_REQUIRED,
      403,
      { status: 403, entitlementKey: 'telegram.broadcasts.enabled' },
      'corr-2',
    );

    expect(accessRefusal(error)).toEqual({ kind: 'locked', name: 'telegram.broadcasts.enabled' });
  });

  it('names nothing when the server refused without naming one', () => {
    const error = new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, { status: 403 }, null);

    expect(accessRefusal(error)).toEqual({ kind: 'denied', name: null });
  });

  it('is null for a refusal that is neither of the two', () => {
    const error = new ApiError(ApiErrorCode.RESOURCE_NOT_FOUND, 404, { status: 404 }, null);

    expect(accessRefusal(error)).toBeNull();
  });
});

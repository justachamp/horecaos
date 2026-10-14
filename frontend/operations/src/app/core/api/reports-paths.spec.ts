import { describe, expect, it } from 'vitest';

import { reportsPaths } from './reports-paths';

/**
 * `ReviewReportController` is mounted at `/api/v1/tenants/{tenantId}/reporting/reviews`, tenant-scoped like every
 * report. These assert the literal path each builder produces, not that the builder equals itself: a builder
 * on the `/operations` prefix would 404 on every call.
 */
describe('reportsPaths review reads (rows 5.4 and 5.5)', () => {
  it('builds the summary and the recovery read under the tenant-scoped reporting tree', () => {
    expect(reportsPaths.reviewSummary('t1')).toBe('/api/v1/tenants/t1/reporting/reviews/summary');
    expect(reportsPaths.reviewRecovery('t1')).toBe('/api/v1/tenants/t1/reporting/reviews/recovery');
  });

  it('encodes the tenant id', () => {
    expect(reportsPaths.reviewSummary('a/b')).toBe(
      '/api/v1/tenants/a%2Fb/reporting/reviews/summary',
    );
  });
});

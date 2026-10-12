import { describe, expect, it } from 'vitest';

import { printPaths } from './print-paths';

const SCOPE = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };
const BASE = '/api/v1/tenants/t1/brands/b1/locations/l1';

/**
 * These assert the literal path each builder produces against the controllers' `@RequestMapping`s
 * (`PrintConsoleController`, `PrintProfileController`), because the same kind of mismatch once made every
 * inventory call a 404 and a builder compared with itself cannot catch it.
 */
describe('printPaths', () => {
  it('builds every branch path under /print on the tenant prefix', () => {
    expect(printPaths.printers(SCOPE)).toBe(`${BASE}/print/printers`);
    expect(printPaths.printer(SCOPE, 'p1')).toBe(`${BASE}/print/printers/p1`);
    expect(printPaths.printerTestPage(SCOPE, 'p1')).toBe(`${BASE}/print/printers/p1/test-page`);
    expect(printPaths.routes(SCOPE)).toBe(`${BASE}/print/routes`);
    expect(printPaths.route(SCOPE, 'r1')).toBe(`${BASE}/print/routes/r1`);
    expect(printPaths.posExport(SCOPE)).toBe(`${BASE}/print/pos-export`);
    expect(printPaths.jobs(SCOPE)).toBe(`${BASE}/print/jobs`);
    expect(printPaths.job(SCOPE, 'j1')).toBe(`${BASE}/print/jobs/j1`);
    expect(printPaths.jobCancel(SCOPE, 'j1')).toBe(`${BASE}/print/jobs/j1/cancel`);
    expect(printPaths.jobReprint(SCOPE, 'j1')).toBe(`${BASE}/print/jobs/j1/reprint`);
    expect(printPaths.preview(SCOPE)).toBe(`${BASE}/print/documents/preview`);
    expect(printPaths.jobPicture(SCOPE, 'j1')).toBe(`${BASE}/print/jobs/j1/picture`);
    expect(printPaths.agents(SCOPE)).toBe(`${BASE}/print/agents`);
    expect(printPaths.agentEnrolment(SCOPE, 'ABCD-1234')).toBe(
      `${BASE}/print/agents/enrolments/ABCD-1234`,
    );
    expect(printPaths.agentApprove(SCOPE, 'ABCD-1234')).toBe(
      `${BASE}/print/agents/enrolments/ABCD-1234/approve`,
    );
    expect(printPaths.agentRevoke(SCOPE, 'd1')).toBe(`${BASE}/print/agents/d1/revoke`);
  });

  it("puts an order's print jobs under the order, not under /print", () => {
    expect(printPaths.orderPrintJobs(SCOPE, 'o1')).toBe(`${BASE}/orders/o1/print-jobs`);
  });

  it('puts the receipt profile on the brand, with no branch in the path', () => {
    expect(printPaths.receiptProfile({ tenantId: 't1', brandId: 'b1' })).toBe(
      '/api/v1/tenants/t1/brands/b1/print/receipt-profile',
    );
  });

  it('encodes what a person types, so a code cannot climb out of its segment', () => {
    expect(printPaths.agentEnrolment(SCOPE, '../x?y')).toBe(
      `${BASE}/print/agents/enrolments/..%2Fx%3Fy`,
    );
  });
});

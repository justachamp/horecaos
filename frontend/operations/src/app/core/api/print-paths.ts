import { BrandScope } from './catalog-paths';
import { LocationScope } from './operations-paths';

/**
 * Where printing and receipts live on the platform (ADR 0154, `PrintConsoleController` and
 * `PrintProfileController`).
 *
 * Every path is under the branch (`/tenants/{t}/brands/{b}/locations/{l}/print`), on the pre-ADR-0031
 * prefix the orders controller also uses, except the receipt profile, which belongs to the brand: a
 * footer and a logo are the brand's face on every branch's paper. What a print agent itself calls
 * (`/claims`, `/jobs/{id}/artifact`, `/jobs/{id}/results`) is deliberately absent: this console never
 * claims a job, and no console role can be made to.
 */
const API = '/api/v1';

function location(scope: LocationScope): string {
  return (
    `${API}/tenants/${encodeURIComponent(scope.tenantId)}` +
    `/brands/${encodeURIComponent(scope.brandId)}` +
    `/locations/${encodeURIComponent(scope.locationId)}`
  );
}

function printRoot(scope: LocationScope): string {
  return `${location(scope)}/print`;
}

export const printPaths = {
  printers(scope: LocationScope): string {
    return `${printRoot(scope)}/printers`;
  },

  printer(scope: LocationScope, printerId: string): string {
    return `${printRoot(scope)}/printers/${encodeURIComponent(printerId)}`;
  },

  /** `POST`: a test page on one printer (`print.reprint`). */
  printerTestPage(scope: LocationScope, printerId: string): string {
    return `${printRoot(scope)}/printers/${encodeURIComponent(printerId)}/test-page`;
  },

  routes(scope: LocationScope): string {
    return `${printRoot(scope)}/routes`;
  },

  route(scope: LocationScope, routeId: string): string {
    return `${printRoot(scope)}/routes/${encodeURIComponent(routeId)}`;
  },

  /** `GET`: whether the branch's point of sale may already print (an active `ORDER_EXPORT` binding). */
  posExport(scope: LocationScope): string {
    return `${printRoot(scope)}/pos-export`;
  },

  /** `GET` (cursor page, optional `status`): the print queue, newest first. */
  jobs(scope: LocationScope): string {
    return `${printRoot(scope)}/jobs`;
  },

  job(scope: LocationScope, jobId: string): string {
    return `${printRoot(scope)}/jobs/${encodeURIComponent(jobId)}`;
  },

  /** `POST`: cancel a queued job. */
  jobCancel(scope: LocationScope, jobId: string): string {
    return `${printRoot(scope)}/jobs/${encodeURIComponent(jobId)}/cancel`;
  },

  /** `POST`: print a finished job's document again, with a reason. */
  jobReprint(scope: LocationScope, jobId: string): string {
    return `${printRoot(scope)}/jobs/${encodeURIComponent(jobId)}/reprint`;
  },

  /** `POST`: print an order's document (the order detail's «Печать»), with a reason. */
  orderPrintJobs(scope: LocationScope, orderId: string): string {
    return `${location(scope)}/orders/${encodeURIComponent(orderId)}/print-jobs`;
  },

  /** `POST` (body, PNG back): the picture of what a job would put on paper. Changes nothing. */
  preview(scope: LocationScope): string {
    return `${printRoot(scope)}/documents/preview`;
  },

  /**
   * `GET` (PNG back): the picture of a job the caller handed to the browser. The preview above is
   * `print.read`, which a cashier does not hold; this is `print.reprint`, which made the job.
   */
  jobPicture(scope: LocationScope, jobId: string): string {
    return `${printRoot(scope)}/jobs/${encodeURIComponent(jobId)}/picture`;
  },

  agents(scope: LocationScope): string {
    return `${printRoot(scope)}/agents`;
  },

  /** `GET`: what a typed code claims, before the manager approves it. */
  agentEnrolment(scope: LocationScope, userCode: string): string {
    return `${printRoot(scope)}/agents/enrolments/${encodeURIComponent(userCode)}`;
  },

  agentApprove(scope: LocationScope, userCode: string): string {
    return `${printRoot(scope)}/agents/enrolments/${encodeURIComponent(userCode)}/approve`;
  },

  agentRevoke(scope: LocationScope, deviceId: string): string {
    return `${printRoot(scope)}/agents/${encodeURIComponent(deviceId)}/revoke`;
  },

  /** The brand's receipt profile: the logo switch and the footer lines (`GET`, `PUT` under `If-Match`). */
  receiptProfile(scope: BrandScope): string {
    return (
      `${API}/tenants/${encodeURIComponent(scope.tenantId)}` +
      `/brands/${encodeURIComponent(scope.brandId)}/print/receipt-profile`
    );
  },
} as const;

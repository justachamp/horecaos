import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { command } from '../../../core/api/idempotency';
import { BrandScope } from '../../../core/api/catalog-paths';
import { LocationScope } from '../../../core/api/operations-paths';
import { Page } from '../../../core/api/page';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { printPaths } from '../../../core/api/print-paths';

/** The closed set ADR 0154 decision 2 names. A tenant cannot add a type. */
export type PrintDocumentType = 'KITCHEN_TICKET' | 'ORDER_SLIP' | 'FISCAL_RECEIPT' | 'TEST_PAGE';

/** The documents a manager can route (a test page is never routed). */
export const ROUTABLE_DOCUMENT_TYPES: readonly PrintDocumentType[] = [
  'KITCHEN_TICKET',
  'ORDER_SLIP',
  'FISCAL_RECEIPT',
];

/** Mirrors `PrintOnDemandService.Reason`. A reprint always says why; the activity log keeps it. */
export type PrintReason =
  | 'CUSTOMER_REQUEST'
  | 'PAPER_JAM'
  | 'PRINTER_FAULT'
  | 'KITCHEN_REQUEST'
  | 'AMENDED_ORDER'
  | 'TEST'
  | 'OTHER';

export const PRINT_REASONS: readonly PrintReason[] = [
  'CUSTOMER_REQUEST',
  'PAPER_JAM',
  'PRINTER_FAULT',
  'KITCHEN_REQUEST',
  'AMENDED_ORDER',
  'OTHER',
];

export type JobStatus =
  'QUEUED' | 'CLAIMED' | 'PRINTED' | 'HANDED_OFF' | 'DEAD' | 'CANCELLED' | 'EXPIRED';

export const JOB_STATUSES: readonly JobStatus[] = [
  'QUEUED',
  'CLAIMED',
  'PRINTED',
  'HANDED_OFF',
  'DEAD',
  'CANCELLED',
  'EXPIRED',
];

export type TapeWidth = 58 | 80;

/** What a print agent last reported for a printer it advertises. */
export type PrinterReportedStatus =
  'ONLINE' | 'OFFLINE' | 'OUT_OF_PAPER' | 'COVER_OPEN' | 'ERROR' | 'UNKNOWN';

/** Mirrors `PrintConsoleController.PrintPrinterResponse`. Never an address: the platform holds none. */
export interface PrinterView {
  readonly id: string;
  readonly displayName: string;
  readonly tapeWidthMm: number;
  readonly connectionKind: 'AGENT' | 'BROWSER';
  readonly agentDeviceId: string | null;
  readonly agentPrinterKey: string | null;
  readonly status: 'ACTIVE' | 'RETIRED';
  readonly lastStatus: PrinterReportedStatus | null;
  readonly lastStatusAt: string | null;
  readonly version: number;
}

export interface PrinterDraft {
  readonly displayName: string;
  readonly tapeWidthMm: TapeWidth;
  /** Both null is a browser printer; both set is a printer an agent can reach. */
  readonly agentDeviceId: string | null;
  readonly agentPrinterKey: string | null;
}

/** Mirrors `PrintConsoleController.PrintRouteResponse`. */
export interface RouteView {
  readonly id: string;
  readonly documentType: PrintDocumentType;
  /** Null on a kitchen-ticket route is the fallback for a station with no route of its own. */
  readonly stationId: string | null;
  readonly printerId: string;
  readonly copies: number;
  readonly autoPrint: boolean;
  readonly status: 'ACTIVE' | 'INACTIVE';
  readonly version: number;
}

export interface RouteDraft {
  readonly documentType: PrintDocumentType;
  readonly stationId: string | null;
  readonly printerId: string;
  readonly copies: number;
  readonly autoPrint: boolean;
  readonly status: 'ACTIVE' | 'INACTIVE';
}

/** Mirrors `PrintConsoleController.PrintJobResponse`. The stored document is never part of it. */
export interface PrintJobView {
  readonly id: string;
  readonly printerId: string;
  readonly routeId: string | null;
  readonly documentType: PrintDocumentType;
  readonly status: JobStatus;
  readonly origin: 'AUTO' | 'MANUAL';
  readonly sourceType: string;
  readonly sourceId: string | null;
  readonly sourceRevision: number;
  readonly stationId: string | null;
  readonly copyNo: number;
  readonly requestedBy: string | null;
  readonly reasonCode: PrintReason | null;
  readonly reprintOf: string | null;
  readonly attempt: number;
  readonly failureCode: string | null;
  readonly locale: string;
  readonly tapeWidthMm: number;
  readonly queuedAt: string;
  readonly expiresAt: string;
  readonly printedAt: string | null;
  readonly documentErased: boolean;
  readonly version: number;
}

export interface AgentDeviceView {
  readonly deviceId: string;
  readonly deviceClass: string;
  readonly displayName: string;
  readonly status: string;
  readonly enrolledBy: string;
  readonly enrolledAt: string;
  readonly revokedBy: string | null;
  readonly revokedAt: string | null;
  readonly revokedReason: string | null;
}

export interface AdvertisedPrinter {
  readonly key: string;
  readonly status: PrinterReportedStatus;
  readonly agentBuild: string | null;
  readonly seenAt: string;
}

/** Mirrors `PrintConsoleController.PrintAgentResponse`. */
export interface AgentView {
  readonly device: AgentDeviceView;
  readonly advertised: readonly AdvertisedPrinter[];
  readonly boundPrinterIds: readonly string[];
}

export interface PendingAgentView {
  readonly requestedClass: string;
  readonly requestedLabel: string | null;
  readonly expiresAt: string;
}

/** Mirrors `PrintProfileController.ReceiptProfileResponse`: the logo switch and up to three footer lines per language. */
export interface ReceiptProfileView {
  readonly printLogo: boolean;
  readonly footerTexts: Readonly<Record<string, readonly string[]>>;
  readonly version: number;
  readonly updatedBy: string | null;
  readonly updatedAt: string | null;
}

export interface ReceiptProfileDraft {
  readonly printLogo: boolean;
  readonly footerTexts: Readonly<Record<string, readonly string[]>>;
}

/** What the server is asked to draw: a stored job's picture, or a document built the way a job would be. */
export interface PreviewRequest {
  readonly jobId?: string;
  readonly documentType?: PrintDocumentType;
  readonly orderId?: string;
  readonly stationId?: string;
  readonly printerId?: string;
  readonly tapeWidthMm?: number;
}

/** The two refusals that carry a `reason` property the screens speak plainly about. */
export type PrintRefusal = 'PRINTING_OFF' | 'NO_ROUTE';

/**
 * Which of the printing module's own refusals an error is. Both arrive as `UNPROCESSABLE_STATE` with a
 * `reason` property (`PrintingGuard`, `PrintOnDemandService`), so a screen that wants to say "printing is not
 * switched on" or "nothing prints this here" must read that property rather than the status.
 */
export function printRefusal(error: unknown): PrintRefusal | null {
  if (!(error instanceof ApiError) || error.code !== ApiErrorCode.UNPROCESSABLE_STATE) {
    return null;
  }
  const reason = error.problem?.['reason'];
  return reason === 'PRINTING_OFF' || reason === 'NO_ROUTE' ? reason : null;
}

/**
 * Printing and receipts for a branch (ADR 0154, `PrintConsoleController` and `PrintProfileController`).
 *
 * Every call here is the manager's: reading needs `print.read`, changing a printer, a route or an agent needs
 * `print.manage`, and printing something on demand needs `print.reprint`, which a cashier holds. What a print
 * agent itself calls (claim, artifact, result) is deliberately absent; this console never claims a job.
 */
@Injectable({ providedIn: 'root' })
export class PrintingApi {
  private readonly api = inject(ApiClient);

  // ------------------------------------------------------------------ printers

  async printers(scope: LocationScope): Promise<readonly PrinterView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly PrinterView[]>(printPaths.printers(scope)),
    );
    return result.value ?? [];
  }

  registerPrinter(scope: LocationScope, draft: PrinterDraft): Promise<PrinterView> {
    return firstValueFrom(
      this.api.post<PrinterDraft, PrinterView>(printPaths.printers(scope), command(draft)),
    );
  }

  /** Under `If-Match`: the version the form was opened at. `RETIRED` retires the printer; its jobs stay. */
  updatePrinter(
    scope: LocationScope,
    printerId: string,
    draft: PrinterDraft,
    status: 'ACTIVE' | 'RETIRED',
    expectedVersion: number,
  ): Promise<PrinterView> {
    return firstValueFrom(
      this.api.put<PrinterDraft & { status: string }, PrinterView>(
        printPaths.printer(scope, printerId),
        command({ ...draft, status }),
        { expectedVersion },
      ),
    );
  }

  /** A test page on one printer. A browser printer's job comes back `HANDED_OFF`; the caller prints its picture. */
  testPage(scope: LocationScope, printerId: string): Promise<PrintJobView> {
    return firstValueFrom(
      this.api.post<Record<string, never>, PrintJobView>(
        printPaths.printerTestPage(scope, printerId),
        command({}),
      ),
    );
  }

  // -------------------------------------------------------------------- routes

  async routes(scope: LocationScope): Promise<readonly RouteView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly RouteView[]>(printPaths.routes(scope)),
    );
    return result.value ?? [];
  }

  createRoute(scope: LocationScope, draft: RouteDraft): Promise<RouteView> {
    return firstValueFrom(
      this.api.post<RouteDraft, RouteView>(printPaths.routes(scope), command(draft)),
    );
  }

  updateRoute(
    scope: LocationScope,
    routeId: string,
    draft: RouteDraft,
    expectedVersion: number,
  ): Promise<RouteView> {
    return firstValueFrom(
      this.api.put<RouteDraft, RouteView>(printPaths.route(scope, routeId), command(draft), {
        expectedVersion,
      }),
    );
  }

  async deleteRoute(scope: LocationScope, routeId: string, expectedVersion: number): Promise<void> {
    await firstValueFrom(
      this.api.send<Record<string, never>, void>(
        'DELETE',
        printPaths.route(scope, routeId),
        command({}),
        { expectedVersion },
      ),
    );
  }

  /** Whether the branch's point of sale may already print (an active `ORDER_EXPORT` binding). A statement only. */
  async posExportActive(scope: LocationScope): Promise<boolean> {
    const result = await firstValueFrom(
      this.api.get<{ orderExportActive: boolean }>(printPaths.posExport(scope)),
    );
    return result.value?.orderExportActive === true;
  }

  // ---------------------------------------------------------------------- jobs

  /** The queue, newest first, optionally one state. A filter change must start from no cursor. */
  jobs(
    scope: LocationScope,
    status: JobStatus | null,
    cursor: string | null,
    limit = 25,
  ): Promise<Page<PrintJobView>> {
    return firstValueFrom(
      this.api.page<PrintJobView>(
        printPaths.jobs(scope),
        { cursor, limit },
        status === null ? {} : { status },
      ),
    );
  }

  cancelJob(scope: LocationScope, jobId: string): Promise<PrintJobView> {
    return firstValueFrom(
      this.api.post<Record<string, never>, PrintJobView>(
        printPaths.jobCancel(scope, jobId),
        command({}),
      ),
    );
  }

  /** A finished job's document again: a new job, marked as a copy, with the person and the reason. */
  reprintJob(
    scope: LocationScope,
    jobId: string,
    reasonCode: PrintReason,
    printerId: string | null,
  ): Promise<PrintJobView> {
    return firstValueFrom(
      this.api.post<{ reasonCode: PrintReason; printerId: string | null }, PrintJobView>(
        printPaths.jobReprint(scope, jobId),
        command({ reasonCode, printerId }),
      ),
    );
  }

  /**
   * An order's document on paper (the order detail's «Печать»). Without a printer the document goes where its
   * route says; a document with no route is refused `NO_ROUTE`. A kitchen ticket makes one job per station.
   */
  printOrderDocument(
    scope: LocationScope,
    orderId: string,
    documentType: PrintDocumentType,
    reasonCode: PrintReason,
    printerId: string | null,
  ): Promise<readonly PrintJobView[]> {
    return firstValueFrom(
      this.api.post<
        { documentType: PrintDocumentType; reasonCode: PrintReason; printerId: string | null },
        readonly PrintJobView[]
      >(
        printPaths.orderPrintJobs(scope, orderId),
        command({ documentType, reasonCode, printerId }),
      ),
    );
  }

  /** The PNG of what a job would put on paper. Changes nothing. */
  preview(scope: LocationScope, request: PreviewRequest): Promise<Blob> {
    return firstValueFrom(this.api.readBlob(printPaths.preview(scope), request));
  }

  // -------------------------------------------------------------------- agents

  async agents(scope: LocationScope): Promise<readonly AgentView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly AgentView[]>(printPaths.agents(scope)),
    );
    return result.value ?? [];
  }

  /** What a typed code claims, before the manager approves it. An unknown, spent or expired code is a 404. */
  async pendingAgent(scope: LocationScope, userCode: string): Promise<PendingAgentView> {
    const result = await firstValueFrom(
      this.api.get<PendingAgentView>(printPaths.agentEnrolment(scope, userCode)),
    );
    return result.value;
  }

  approveAgent(
    scope: LocationScope,
    userCode: string,
    displayName: string,
  ): Promise<AgentDeviceView> {
    return firstValueFrom(
      this.api.post<{ displayName: string }, AgentDeviceView>(
        printPaths.agentApprove(scope, userCode),
        command({ displayName }),
      ),
    );
  }

  async revokeAgent(scope: LocationScope, deviceId: string, reason: string): Promise<void> {
    await firstValueFrom(
      this.api.post<{ reason: string }, { revoked: boolean }>(
        printPaths.agentRevoke(scope, deviceId),
        command({ reason }),
      ),
    );
  }

  // ------------------------------------------------------------ receipt profile

  async receiptProfile(scope: BrandScope): Promise<ReceiptProfileView> {
    const result = await firstValueFrom(
      this.api.get<ReceiptProfileView>(printPaths.receiptProfile(scope)),
    );
    return result.value;
  }

  saveReceiptProfile(
    scope: BrandScope,
    draft: ReceiptProfileDraft,
    expectedVersion: number,
  ): Promise<ReceiptProfileView> {
    return firstValueFrom(
      this.api.put<ReceiptProfileDraft, ReceiptProfileView>(
        printPaths.receiptProfile(scope),
        command(draft),
        { expectedVersion },
      ),
    );
  }
}

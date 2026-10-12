import { MessageKey } from '../../../core/i18n/messages.en';
import { JobStatus, PrintDocumentType, PrintReason, PrinterReportedStatus } from './printing-api';

/** The words for ADR 0154's closed sets. Spelled out, not built from a template, so a missing key fails the build. */
export const DOCUMENT_TYPE_KEYS: Readonly<Record<PrintDocumentType, MessageKey>> = {
  KITCHEN_TICKET: 'settings.printing.doc.kitchenTicket',
  ORDER_SLIP: 'settings.printing.doc.orderSlip',
  FISCAL_RECEIPT: 'settings.printing.doc.fiscalReceipt',
  TEST_PAGE: 'settings.printing.doc.testPage',
};

export const JOB_STATUS_KEYS: Readonly<Record<JobStatus, MessageKey>> = {
  QUEUED: 'settings.printing.job.status.queued',
  CLAIMED: 'settings.printing.job.status.claimed',
  PRINTED: 'settings.printing.job.status.printed',
  HANDED_OFF: 'settings.printing.job.status.handedOff',
  DEAD: 'settings.printing.job.status.dead',
  CANCELLED: 'settings.printing.job.status.cancelled',
  EXPIRED: 'settings.printing.job.status.expired',
};

export const REASON_KEYS: Readonly<Record<PrintReason, MessageKey>> = {
  CUSTOMER_REQUEST: 'settings.printing.reason.customerRequest',
  PAPER_JAM: 'settings.printing.reason.paperJam',
  PRINTER_FAULT: 'settings.printing.reason.printerFault',
  KITCHEN_REQUEST: 'settings.printing.reason.kitchenRequest',
  AMENDED_ORDER: 'settings.printing.reason.amendedOrder',
  TEST: 'settings.printing.reason.test',
  OTHER: 'settings.printing.reason.other',
};

export const PRINTER_STATUS_KEYS: Readonly<Record<PrinterReportedStatus, MessageKey>> = {
  ONLINE: 'settings.printing.printer.status.online',
  OFFLINE: 'settings.printing.printer.status.offline',
  OUT_OF_PAPER: 'settings.printing.printer.status.outOfPaper',
  COVER_OPEN: 'settings.printing.printer.status.coverOpen',
  ERROR: 'settings.printing.printer.status.error',
  UNKNOWN: 'settings.printing.printer.status.unknown',
};

/** What a dead or failed job says went wrong. An unknown code (a newer platform) is shown as the generic line. */
export const FAILURE_KEYS: Readonly<Record<string, MessageKey>> = {
  PRINTER_OFFLINE: 'settings.printing.job.failure.printerOffline',
  OUT_OF_PAPER: 'settings.printing.job.failure.outOfPaper',
  COVER_OPEN: 'settings.printing.job.failure.coverOpen',
  TRANSPORT: 'settings.printing.job.failure.transport',
  RENDER: 'settings.printing.job.failure.render',
  LEASE_LAPSED: 'settings.printing.job.failure.leaseLapsed',
};

/** The states a finished job can be printed again from (the platform refuses the others). */
export const REPRINTABLE: ReadonlySet<JobStatus> = new Set<JobStatus>([
  'PRINTED',
  'HANDED_OFF',
  'DEAD',
  'EXPIRED',
  'CANCELLED',
]);

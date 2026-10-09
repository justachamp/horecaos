import { StationResponse } from '../../kitchen/kitchen-api';
import {
  AgentView,
  PrintJobView,
  PrinterView,
  ReceiptProfileView,
  RouteView,
} from './printing-api';

/** Plain data builders for the printing specs. No test framework is imported, so the app's own type check accepts the file. */

export function printer(overrides: Partial<PrinterView> = {}): PrinterView {
  return {
    id: 'printer-1',
    displayName: 'Grill pass',
    tapeWidthMm: 80,
    connectionKind: 'AGENT',
    agentDeviceId: 'agent-1',
    agentPrinterKey: 'grill',
    status: 'ACTIVE',
    lastStatus: 'ONLINE',
    lastStatusAt: '2026-10-09T08:00:00Z',
    version: 1,
    ...overrides,
  };
}

export function browserPrinter(overrides: Partial<PrinterView> = {}): PrinterView {
  return printer({
    id: 'printer-browser',
    displayName: 'Counter (this browser)',
    connectionKind: 'BROWSER',
    agentDeviceId: null,
    agentPrinterKey: null,
    lastStatus: null,
    lastStatusAt: null,
    ...overrides,
  });
}

export function route(overrides: Partial<RouteView> = {}): RouteView {
  return {
    id: 'route-1',
    documentType: 'KITCHEN_TICKET',
    stationId: null,
    printerId: 'printer-1',
    copies: 1,
    autoPrint: false,
    status: 'ACTIVE',
    version: 1,
    ...overrides,
  };
}

export function job(overrides: Partial<PrintJobView> = {}): PrintJobView {
  return {
    id: 'job-1',
    printerId: 'printer-1',
    routeId: 'route-1',
    documentType: 'KITCHEN_TICKET',
    status: 'PRINTED',
    origin: 'AUTO',
    sourceType: 'KITCHEN_TICKET',
    sourceId: 'ticket-1',
    sourceRevision: 0,
    stationId: null,
    copyNo: 1,
    requestedBy: null,
    reasonCode: null,
    reprintOf: null,
    attempt: 1,
    failureCode: null,
    locale: 'ru',
    tapeWidthMm: 80,
    queuedAt: '2026-10-09T08:00:00Z',
    expiresAt: '2026-10-09T08:30:00Z',
    printedAt: '2026-10-09T08:00:05Z',
    documentErased: false,
    version: 1,
    ...overrides,
  };
}

export function agent(overrides: Partial<AgentView> = {}): AgentView {
  return {
    device: {
      deviceId: 'agent-1',
      deviceClass: 'PRINT_AGENT',
      displayName: 'Pilot branch mini PC',
      status: 'ACTIVE',
      enrolledBy: 'manager@horecaos.uz',
      enrolledAt: '2026-10-08T08:00:00Z',
      revokedBy: null,
      revokedAt: null,
      revokedReason: null,
    },
    advertised: [
      { key: 'grill', status: 'ONLINE', agentBuild: '1.0.0', seenAt: '2026-10-09T08:00:00Z' },
    ],
    boundPrinterIds: ['printer-1'],
    ...overrides,
  };
}

export function profile(overrides: Partial<ReceiptProfileView> = {}): ReceiptProfileView {
  return {
    printLogo: false,
    footerTexts: {},
    version: 0,
    updatedBy: null,
    updatedAt: null,
    ...overrides,
  };
}

export function station(overrides: Partial<StationResponse> = {}): StationResponse {
  return {
    stationId: 'station-grill',
    code: 'GRILL',
    role: 'GRILL',
    displayNameRu: 'Гриль',
    displayNameUz: 'Gril',
    displayNameEn: 'Grill',
    sortOrder: 1,
    fallback: false,
    status: 'ACTIVE',
    version: 1,
    ...overrides,
  };
}

import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { Capability, SessionCapabilities } from '../../core/auth/session-capabilities';
import { I18n } from '../../core/i18n/i18n';
import { ExportCentrePage } from './export-centre-page';
import {
  ReportExportQueuedResponse,
  ReportExportRequest,
  ReportExportStatusResponse,
  ReportingApi,
} from './reporting-api';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function statusRow(
  overrides: Partial<ReportExportStatusResponse> = {},
): ReportExportStatusResponse {
  return {
    exportId: 'export-1',
    reportKey: 'CUSTOMER_DIRECTORY',
    status: 'QUEUED',
    columns: ['accountId', 'status', 'displayName'],
    includesPiiColumns: false,
    rowQuota: 5000,
    rowCount: null,
    truncated: false,
    failureReason: null,
    createdAt: '2026-09-25T00:00:00Z',
    completedAt: null,
    downloadUrl: null,
    ...overrides,
  };
}

function fakeCapabilities(held: readonly Capability[]): SessionCapabilities {
  return { has: (capability: Capability) => held.includes(capability) } as SessionCapabilities;
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('ExportCentrePage (row 7.2e)', () => {
  let fixture: ComponentFixture<ExportCentrePage>;
  let requestExportSpy: ReturnType<typeof vi.fn>;

  async function render(options: {
    history?: readonly ReportExportStatusResponse[];
    capabilities?: readonly Capability[];
    requestExportResult?: ReportExportQueuedResponse;
  }): Promise<void> {
    requestExportSpy = vi
      .fn()
      .mockResolvedValue(
        options.requestExportResult ?? { exportId: 'export-new', status: 'QUEUED' },
      );

    await TestBed.configureTestingModule({
      imports: [ExportCentrePage],
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: () => SCOPE,
            denied: () => false,
            ensureLoaded: () => Promise.resolve(),
          },
        },
        {
          provide: SessionCapabilities,
          useValue: fakeCapabilities(options.capabilities ?? ['REPORT_EXPORT']),
        },
        {
          provide: ReportingApi,
          useValue: {
            recentExports: () => Promise.resolve(options.history ?? []),
            requestExport: requestExportSpy,
            exportStatus: (_tenantId: string, exportId: string) =>
              Promise.resolve(statusRow({ exportId })),
          },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(ExportCentrePage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  function el<T extends Element = HTMLElement>(testId: string): T | null {
    return fixture.nativeElement.querySelector(`[data-testid="${testId}"]`);
  }

  function selectReport(reportKey: string): void {
    const select = el<HTMLSelectElement>('export-centre-report')!;
    select.value = reportKey;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  it('defaults to Customer directory, with its own status/query filters and no date range', async () => {
    await render({});

    expect(el<HTMLSelectElement>('export-centre-report')!.value).toBe('CUSTOMER_DIRECTORY');
    expect(el('export-centre-status')).toBeTruthy();
    expect(el('export-centre-query')).toBeTruthy();
    expect(el('export-centre-from')).toBeNull();
    expect(el('export-centre-to')).toBeNull();
    expect(el('export-column-accountId')).toBeTruthy();
    expect(el('export-column-orderId')).toBeNull();
  });

  it('offers all four reports the registry declares', async () => {
    await render({});

    const options = Array.from(
      el<HTMLSelectElement>('export-centre-report')!.querySelectorAll('option'),
    ).map((option) => (option as HTMLOptionElement).value);

    expect(options).toEqual([
      'CUSTOMER_DIRECTORY',
      'ORDER_CRM_LOG',
      'ORDER_REPORT_LOG',
      'ORDER_REPORT_SUMMARY',
    ]);
  });

  it('switching to Order CRM log swaps in its own columns and a date range instead of status/query', async () => {
    await render({});

    selectReport('ORDER_CRM_LOG');

    expect(el('export-centre-status')).toBeNull();
    expect(el('export-centre-query')).toBeNull();
    expect(el('export-centre-from')).toBeTruthy();
    expect(el('export-centre-to')).toBeTruthy();
    expect(el('export-column-orderId')).toBeTruthy();
    expect(el('export-column-customerName')).toBeNull(); // PII, hidden without the capability
    expect(el('export-column-accountId')).toBeNull();
  });

  it('switching to Order report summary offers its own aggregate columns', async () => {
    await render({});

    selectReport('ORDER_REPORT_SUMMARY');

    expect(el('export-column-orderCount')).toBeTruthy();
    expect(el('export-column-grossSom')).toBeTruthy();
    expect(el('export-column-netSom')).toBeTruthy();
    expect(el('export-column-orderId')).toBeNull();
  });

  it('shows the PII columns for Order CRM log when the operator holds customer.pii.export', async () => {
    await render({ capabilities: ['REPORT_EXPORT', 'CUSTOMER_PII_EXPORT'] });

    selectReport('ORDER_CRM_LOG');

    expect(el('export-column-customerName')).toBeTruthy();
    expect(el('export-column-customerPhone')).toBeTruthy();
  });

  it('refuses to submit Order report log without both a from and a to date', async () => {
    await render({});
    selectReport('ORDER_REPORT_LOG');
    (el('export-centre-purpose') as HTMLInputElement).value = 'Monthly reconciliation';
    el('export-centre-purpose')!.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    el('export-centre-submit')!.dispatchEvent(new Event('click'));
    await flushMicrotasks();
    fixture.detectChanges();

    expect(requestExportSpy).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Choose both a start and an end date');
  });

  it('submits Order report log with the chosen range as UTC day boundaries and no status/query', async () => {
    await render({});
    selectReport('ORDER_REPORT_LOG');

    const purposeInput = el('export-centre-purpose') as HTMLInputElement;
    purposeInput.value = 'Finance close';
    purposeInput.dispatchEvent(new Event('input'));
    const fromInput = el('export-centre-from') as HTMLInputElement;
    fromInput.value = '2026-09-01';
    fromInput.dispatchEvent(new Event('input'));
    const toInput = el('export-centre-to') as HTMLInputElement;
    toInput.value = '2026-09-30';
    toInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    el('export-centre-submit')!.dispatchEvent(new Event('click'));
    await flushMicrotasks();
    fixture.detectChanges();

    expect(requestExportSpy).toHaveBeenCalledTimes(1);
    const [tenantId, request] = requestExportSpy.mock.calls[0] as [string, ReportExportRequest];
    expect(tenantId).toBe('t1');
    expect(request.reportKey).toBe('ORDER_REPORT_LOG');
    expect(request.from).toBe('2026-09-01T00:00:00.000Z');
    expect(request.to).toBe('2026-09-30T23:59:59.999Z');
    expect(request.status).toBeNull();
    expect(request.query).toBeNull();
  });

  it('submits Customer directory with its status/query and no date range', async () => {
    await render({});

    const purposeInput = el('export-centre-purpose') as HTMLInputElement;
    purposeInput.value = 'Marketing list';
    purposeInput.dispatchEvent(new Event('input'));
    const statusSelect = el('export-centre-status') as HTMLSelectElement;
    statusSelect.value = 'ACTIVE';
    statusSelect.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    el('export-centre-submit')!.dispatchEvent(new Event('click'));
    await flushMicrotasks();
    fixture.detectChanges();

    expect(requestExportSpy).toHaveBeenCalledTimes(1);
    const [, request] = requestExportSpy.mock.calls[0] as [string, ReportExportRequest];
    expect(request.reportKey).toBe('CUSTOMER_DIRECTORY');
    expect(request.status).toBe('ACTIVE');
    expect(request.from ?? null).toBeNull();
    expect(request.to ?? null).toBeNull();
  });

  it('resets the column selection and filters to the new report defaults when switching reports', async () => {
    await render({});

    const statusSelect = el('export-centre-status') as HTMLSelectElement;
    statusSelect.value = 'ACTIVE';
    statusSelect.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    selectReport('ORDER_REPORT_SUMMARY');
    selectReport('CUSTOMER_DIRECTORY');

    // Back on Customer directory, the status filter a previous report never
    // used has been cleared rather than silently carried over.
    expect((el('export-centre-status') as HTMLSelectElement).value).toBe('');
  });

  it("shows each history job's own report name, not always Customer directory", async () => {
    await render({
      history: [
        statusRow({ exportId: 'e-1', reportKey: 'ORDER_REPORT_SUMMARY' }),
        statusRow({ exportId: 'e-2', reportKey: 'CUSTOMER_DIRECTORY' }),
      ],
    });

    const row1 = el('export-centre-job-e-1')!;
    const row2 = el('export-centre-job-e-2')!;
    expect(row1.textContent).toContain('Summary');
    expect(row2.textContent).toContain('Customer directory');
  });
});

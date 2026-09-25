import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';

import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { SessionCapabilities } from '../../core/auth/session-capabilities';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { ExportColumnChooser, ExportColumnOption } from './export-column-chooser';
import { ReportExportStatusResponse, ReportingApi } from './reporting-api';

type ExportCentreState = 'loading' | 'ready' | 'denied' | 'error';

/** How often a QUEUED/RUNNING row is re-polled — `ImportWizard`'s own interval, the same job shape. */
const POLL_INTERVAL_MS = 2_000;

/**
 * One report this screen's own picker can queue an export for — mirrors
 * {@code ReportExportRegistry}'s four report keys and each one's own column
 * set exactly (the request path — {@code POST .../exports} — is unchanged;
 * only this screen's own vocabulary grows). {@link requiresRange} and
 * {@link supportsStatusQuery} mirror {@code ReportExportService#requiresRange}
 * and {@code CUSTOMER_DIRECTORY}'s own `status`/`query` filters respectively
 * — the two families of filter these four reports split into, never both at
 * once for the same report.
 */
interface ReportOption {
  readonly key: string;
  readonly labelKey: MessageKey;
  readonly columns: readonly ExportColumnOption[];
  readonly defaultColumns: readonly string[];
  readonly requiresRange: boolean;
  readonly supportsStatusQuery: boolean;
}

const CUSTOMER_DIRECTORY_COLUMNS: readonly ExportColumnOption[] = [
  { key: 'accountId', labelKey: 'reports.exportCentre.column.accountId', pii: false },
  { key: 'status', labelKey: 'reports.exportCentre.column.status', pii: false },
  { key: 'displayName', labelKey: 'reports.exportCentre.column.displayName', pii: false },
  { key: 'phone', labelKey: 'reports.exportCentre.column.phone', pii: true },
];

/** Wave 9 w4-reports-distance-crm (7.2a) — `OrderCrmLogExportPort`, never `reporting.fact_order`. `customerName`/`customerPhone` are the PII group. */
const ORDER_CRM_LOG_COLUMNS: readonly ExportColumnOption[] = [
  { key: 'orderId', labelKey: 'reports.exportCentre.column.orderId', pii: false },
  { key: 'occurredAt', labelKey: 'reports.exportCentre.column.occurredAt', pii: false },
  { key: 'locationId', labelKey: 'reports.exportCentre.column.locationId', pii: false },
  { key: 'customerType', labelKey: 'reports.exportCentre.column.customerType', pii: false },
  { key: 'customerName', labelKey: 'reports.exportCentre.column.customerName', pii: true },
  { key: 'customerPhone', labelKey: 'reports.exportCentre.column.customerPhone', pii: true },
  {
    key: 'operatorPrincipalId',
    labelKey: 'reports.exportCentre.column.operatorPrincipalId',
    pii: false,
  },
  {
    key: 'courierDisplayReference',
    labelKey: 'reports.exportCentre.column.courierDisplayReference',
    pii: false,
  },
];

/** Wave 10 w5-reports-exports (7.2e) — «Заказы», order-grain, off `reporting.fact_order`. No PII column (ADR 0029: reporting carries no PERSONAL field). */
const ORDER_REPORT_LOG_COLUMNS: readonly ExportColumnOption[] = [
  { key: 'orderId', labelKey: 'reports.exportCentre.column.orderId', pii: false },
  { key: 'businessDate', labelKey: 'reports.exportCentre.column.businessDate', pii: false },
  { key: 'locationId', labelKey: 'reports.exportCentre.column.locationId', pii: false },
  { key: 'channelCode', labelKey: 'reports.exportCentre.column.channelCode', pii: false },
  { key: 'fulfilmentType', labelKey: 'reports.exportCentre.column.fulfilmentType', pii: false },
  { key: 'terminalStatus', labelKey: 'reports.exportCentre.column.terminalStatus', pii: false },
  { key: 'isPreorder', labelKey: 'reports.exportCentre.column.isPreorder', pii: false },
  { key: 'grossRevenueSom', labelKey: 'reports.exportCentre.column.grossSom', pii: false },
  { key: 'discountSom', labelKey: 'reports.exportCentre.column.discountSom', pii: false },
  { key: 'deliveryFeeSom', labelKey: 'reports.exportCentre.column.deliveryFeeSom', pii: false },
  { key: 'netRevenueSom', labelKey: 'reports.exportCentre.column.netSom', pii: false },
  { key: 'itemCount', labelKey: 'reports.exportCentre.column.itemCount', pii: false },
];

/** Wave 10 w5-reports-exports (7.2e) — «Сводка», the by-branch/channel/fulfilment revenue rollup. No PII column. */
const ORDER_REPORT_SUMMARY_COLUMNS: readonly ExportColumnOption[] = [
  { key: 'locationId', labelKey: 'reports.exportCentre.column.locationId', pii: false },
  { key: 'channelCode', labelKey: 'reports.exportCentre.column.channelCode', pii: false },
  { key: 'fulfilmentType', labelKey: 'reports.exportCentre.column.fulfilmentType', pii: false },
  { key: 'orderCount', labelKey: 'reports.exportCentre.column.orderCount', pii: false },
  { key: 'grossSom', labelKey: 'reports.exportCentre.column.grossSom', pii: false },
  { key: 'deliveryFeeSom', labelKey: 'reports.exportCentre.column.deliveryFeeSom', pii: false },
  { key: 'netSom', labelKey: 'reports.exportCentre.column.netSom', pii: false },
];

/** Every report this screen's picker offers, in `ReportExportRegistry`'s own declaration order. */
const REPORT_OPTIONS: readonly ReportOption[] = [
  {
    key: 'CUSTOMER_DIRECTORY',
    labelKey: 'reports.exportCentre.reportOption.customerDirectory',
    columns: CUSTOMER_DIRECTORY_COLUMNS,
    defaultColumns: ['accountId', 'status', 'displayName'],
    requiresRange: false,
    supportsStatusQuery: true,
  },
  {
    key: 'ORDER_CRM_LOG',
    labelKey: 'reports.exportCentre.reportOption.orderCrmLog',
    columns: ORDER_CRM_LOG_COLUMNS,
    defaultColumns: ['orderId', 'occurredAt', 'locationId', 'customerType'],
    requiresRange: true,
    supportsStatusQuery: false,
  },
  {
    key: 'ORDER_REPORT_LOG',
    labelKey: 'reports.exportCentre.reportOption.orderReportLog',
    columns: ORDER_REPORT_LOG_COLUMNS,
    defaultColumns: [
      'orderId',
      'businessDate',
      'locationId',
      'channelCode',
      'fulfilmentType',
      'grossRevenueSom',
      'netRevenueSom',
    ],
    requiresRange: true,
    supportsStatusQuery: false,
  },
  {
    key: 'ORDER_REPORT_SUMMARY',
    labelKey: 'reports.exportCentre.reportOption.orderReportSummary',
    columns: ORDER_REPORT_SUMMARY_COLUMNS,
    defaultColumns: [
      'locationId',
      'channelCode',
      'fulfilmentType',
      'orderCount',
      'grossSom',
      'netSom',
    ],
    requiresRange: true,
    supportsStatusQuery: false,
  },
];

const DEFAULT_REPORT_KEY = REPORT_OPTIONS[0].key;

function findReportOption(reportKey: string): ReportOption {
  return REPORT_OPTIONS.find((option) => option.key === reportKey) ?? REPORT_OPTIONS[0];
}

const STATUS_OPTIONS: readonly { readonly id: string; readonly labelKey: MessageKey }[] = [
  { id: '', labelKey: 'reports.exportCentre.statusFilter.all' },
  { id: 'ACTIVE', labelKey: 'reports.exportCentre.statusFilter.active' },
  { id: 'SUSPENDED', labelKey: 'reports.exportCentre.statusFilter.suspended' },
  { id: 'ANONYMIZED', labelKey: 'reports.exportCentre.statusFilter.anonymized' },
  { id: 'CLOSED', labelKey: 'reports.exportCentre.statusFilter.closed' },
];

const STATUS_LABEL_KEYS: Readonly<Record<string, MessageKey>> = {
  QUEUED: 'reports.exportCentre.status.QUEUED',
  RUNNING: 'reports.exportCentre.status.RUNNING',
  COMPLETE: 'reports.exportCentre.status.COMPLETE',
  FAILED: 'reports.exportCentre.status.FAILED',
};

function isPending(row: ReportExportStatusResponse): boolean {
  return row.status === 'QUEUED' || row.status === 'RUNNING';
}

/**
 * Row `7.2e`: the export centre (ADR 0043's export line, ADR 0029's egress
 * checklist; wave P28). `/statistics/exports` — the route the row's own gap
 * text names as missing.
 *
 * All four reports {@code ReportExportRegistry} declares —
 * `CUSTOMER_DIRECTORY`, `ORDER_CRM_LOG`, `ORDER_REPORT_LOG` and
 * `ORDER_REPORT_SUMMARY` — are wired into this screen's own picker; a fifth
 * report joins the same way these three did: a new {@link ReportOption} in
 * {@link REPORT_OPTIONS}, not a new screen. The request path itself —
 * `POST .../exports` — is unchanged; only the picker and each report's own
 * column set and filters are new. The trigger here is the general one;
 * `order-reports-page.html`'s own Export button (row 7.2) links here rather
 * than triggering an order-log export of its own.
 */
@Component({
  selector: 'q-export-centre-page',
  imports: [TPipe, ExportColumnChooser],
  templateUrl: './export-centre-page.html',
  styleUrl: './export-centre-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ExportCentrePage implements OnInit, OnDestroy {
  private readonly api = inject(ReportingApi);
  private readonly location = inject(CurrentLocation);
  protected readonly capabilities = inject(SessionCapabilities);
  protected readonly i18n = inject(I18n);

  protected readonly state = signal<ExportCentreState>('loading');
  protected readonly history = signal<readonly ReportExportStatusResponse[]>([]);

  protected readonly reportOptions = REPORT_OPTIONS;
  protected readonly statusOptions = STATUS_OPTIONS;

  protected readonly selectedReportKey = signal<string>(DEFAULT_REPORT_KEY);
  protected readonly selectedReport = computed(() => findReportOption(this.selectedReportKey()));
  protected readonly columns = computed(() => this.selectedReport().columns);

  protected readonly selectedColumns = signal<readonly string[]>(
    findReportOption(DEFAULT_REPORT_KEY).defaultColumns,
  );
  protected readonly statusFilter = signal('');
  protected readonly queryFilter = signal('');
  protected readonly fromDate = signal('');
  protected readonly toDate = signal('');
  protected readonly purpose = signal('');

  protected readonly submitting = signal(false);
  protected readonly submitError = signal<string | null>(null);

  private pollHandle: ReturnType<typeof setInterval> | null = null;

  async ngOnInit(): Promise<void> {
    await this.load();
  }

  ngOnDestroy(): void {
    this.stopPolling();
  }

  private async load(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      this.state.set(this.location.denied() ? 'denied' : 'error');
      return;
    }
    this.state.set('loading');
    try {
      const rows = await this.api.recentExports(scope.tenantId);
      this.history.set(rows);
      this.state.set('ready');
      this.startPollingIfNeeded();
    } catch (error) {
      this.state.set(error instanceof ApiError && error.status === 403 ? 'denied' : 'error');
    }
  }

  protected onColumnsChange(columns: readonly string[]): void {
    this.selectedColumns.set(columns);
  }

  /**
   * Switches the picker to a different report — resets the column selection
   * to that report's own defaults and clears every filter, since a filter
   * that made sense on the old report (a status, a search query, a date
   * range) rarely still applies to the new one, and a stale `from`/`to`
   * left over from `ORDER_CRM_LOG` silently carrying into `CUSTOMER_DIRECTORY`
   * (which ignores it anyway) would be a worse trap than an empty field.
   */
  protected selectReport(reportKey: string): void {
    const report = findReportOption(reportKey);
    this.selectedReportKey.set(report.key);
    this.selectedColumns.set(report.defaultColumns);
    this.statusFilter.set('');
    this.queryFilter.set('');
    this.fromDate.set('');
    this.toDate.set('');
    this.submitError.set(null);
  }

  protected statusLabelKey(status: string): MessageKey {
    return STATUS_LABEL_KEYS[status] ?? 'reports.exportCentre.status.FAILED';
  }

  /** The job history's own per-row report name — looked up by the row's actual `reportKey`, never assumed to be `CUSTOMER_DIRECTORY`. */
  protected reportLabelKey(reportKey: string): MessageKey {
    return findReportOption(reportKey).labelKey;
  }

  protected async submit(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    const report = this.selectedReport();
    if (this.selectedColumns().length === 0) {
      this.submitError.set(this.i18n.t('reports.exportCentre.error.noSelection'));
      return;
    }
    if (this.purpose().trim().length === 0) {
      this.submitError.set(this.i18n.t('reports.exportCentre.error.purposeRequired'));
      return;
    }
    let from: string | null = null;
    let to: string | null = null;
    if (report.requiresRange) {
      if (!this.fromDate() || !this.toDate()) {
        this.submitError.set(this.i18n.t('reports.exportCentre.error.rangeRequired'));
        return;
      }
      // No per-tenant timezone reaches this filter yet — the same UTC
      // calendar-day simplification `ReportExportService#runOrderReportLog`'s
      // own doc names for the identical wire shape on the server side.
      from = `${this.fromDate()}T00:00:00.000Z`;
      to = `${this.toDate()}T23:59:59.999Z`;
    }

    this.submitting.set(true);
    this.submitError.set(null);
    try {
      const queued = await this.api.requestExport(scope.tenantId, {
        reportKey: report.key,
        columns: this.selectedColumns(),
        status: report.supportsStatusQuery ? this.statusFilter() || null : null,
        query: report.supportsStatusQuery ? this.queryFilter().trim() || null : null,
        from,
        to,
        purpose: this.purpose().trim(),
      });
      const queuedStatus = await this.api.exportStatus(scope.tenantId, queued.exportId);
      this.history.update((current) => [queuedStatus, ...current]);
      this.purpose.set('');
      this.startPollingIfNeeded();
    } catch {
      this.submitError.set(this.i18n.t('reports.exportCentre.error.generic'));
    } finally {
      this.submitting.set(false);
    }
  }

  private startPollingIfNeeded(): void {
    if (this.pollHandle !== null || !this.history().some(isPending)) {
      return;
    }
    this.pollHandle = setInterval(() => void this.refreshPending(), POLL_INTERVAL_MS);
  }

  private stopPolling(): void {
    if (this.pollHandle !== null) {
      clearInterval(this.pollHandle);
      this.pollHandle = null;
    }
  }

  private async refreshPending(): Promise<void> {
    const scope = this.location.scope();
    const pending = this.history().filter(isPending);
    if (!scope || pending.length === 0) {
      this.stopPolling();
      return;
    }
    const updates = await Promise.all(
      pending.map((row) =>
        this.api
          .exportStatus(scope.tenantId, row.exportId)
          .catch((): ReportExportStatusResponse => row),
      ),
    );
    this.history.update((current) =>
      current.map((row) => updates.find((updated) => updated.exportId === row.exportId) ?? row),
    );
    if (!this.history().some(isPending)) {
      this.stopPolling();
    }
  }
}

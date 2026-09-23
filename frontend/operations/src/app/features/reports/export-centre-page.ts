import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
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
 * This screen's own picker still offers only `CUSTOMER_DIRECTORY`. Wave 9
 * w4-reports-distance-crm also wires `ORDER_CRM_LOG` server-side
 * (`ReportExportRegistry`'s own doc) — `orderId`/`occurredAt`/`locationId`/
 * `customerType`/`customerName`/`customerPhone`/`operatorPrincipalId`/
 * `courierDisplayReference`, `customerName`/`customerPhone` the PII group —
 * but this screen was not extended with a report picker to reach it; it
 * remains reachable only via `ReportingApi.requestExport({ reportKey:
 * 'ORDER_CRM_LOG', ... })` directly. This list grows as more reports join
 * this screen's own picker.
 */
const CUSTOMER_DIRECTORY_COLUMNS: readonly ExportColumnOption[] = [
  { key: 'accountId', labelKey: 'reports.exportCentre.column.accountId', pii: false },
  { key: 'status', labelKey: 'reports.exportCentre.column.status', pii: false },
  { key: 'displayName', labelKey: 'reports.exportCentre.column.displayName', pii: false },
  { key: 'phone', labelKey: 'reports.exportCentre.column.phone', pii: true },
];

const STATUS_OPTIONS: readonly { readonly id: string; readonly labelKey: MessageKey }[] = [
  { id: '', labelKey: 'reports.exportCentre.statusFilter.all' },
  { id: 'ACTIVE', labelKey: 'reports.exportCentre.statusFilter.active' },
  { id: 'SUSPENDED', labelKey: 'reports.exportCentre.statusFilter.suspended' },
  { id: 'ANONYMIZED', labelKey: 'reports.exportCentre.statusFilter.anonymized' },
  { id: 'CLOSED', labelKey: 'reports.exportCentre.statusFilter.closed' },
];

const DEFAULT_COLUMNS: readonly string[] = ['accountId', 'status', 'displayName'];

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
 * Only the `CUSTOMER_DIRECTORY` report is wired into this screen's own
 * picker today; `ORDER_CRM_LOG` is wired server-side (see this file's own
 * `CUSTOMER_DIRECTORY_COLUMNS` comment) but has no picker option here yet —
 * a second report joins this screen the same way it joined
 * `ReportExportRegistry` server-side: a new option in `reportKey`, not a
 * new screen. The trigger here is the general one; `order-reports-page.html`'s
 * own Export button (row 7.2) links here rather than triggering an
 * order-log export of its own.
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

  protected readonly columns = CUSTOMER_DIRECTORY_COLUMNS;
  protected readonly statusOptions = STATUS_OPTIONS;

  protected readonly selectedColumns = signal<readonly string[]>(DEFAULT_COLUMNS);
  protected readonly statusFilter = signal('');
  protected readonly queryFilter = signal('');
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

  protected statusLabelKey(status: string): MessageKey {
    return STATUS_LABEL_KEYS[status] ?? 'reports.exportCentre.status.FAILED';
  }

  protected async submit(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    if (this.selectedColumns().length === 0) {
      this.submitError.set(this.i18n.t('reports.exportCentre.error.noSelection'));
      return;
    }
    if (this.purpose().trim().length === 0) {
      this.submitError.set(this.i18n.t('reports.exportCentre.error.purposeRequired'));
      return;
    }

    this.submitting.set(true);
    this.submitError.set(null);
    try {
      const queued = await this.api.requestExport(scope.tenantId, {
        reportKey: 'CUSTOMER_DIRECTORY',
        columns: this.selectedColumns(),
        status: this.statusFilter() || null,
        query: this.queryFilter().trim() || null,
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

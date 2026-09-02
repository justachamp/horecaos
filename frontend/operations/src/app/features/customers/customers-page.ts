import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterOutlet } from '@angular/router';

import { CursorState, firstPage, nextPage, resetOnFilterChange } from '../../core/api/page';
import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { formatDate } from '../../core/format/datetime';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import { CreateCustomerDialog, CreateCustomerSubmission } from './create-customer-dialog';
import { customerStatusLabel } from './customer-status';
import { CustomerCounts, CustomerExportRow, CustomerSummary, CustomersApi } from './customers-api';

/** §5.1's status filter — `MERGED` is excluded, the same reason `CustomerController#requireKnownStatus` excludes it server-side. */
type StatusFilter = 'ALL' | 'ACTIVE' | 'SUSPENDED' | 'ANONYMIZED' | 'CLOSED';

const PLACEHOLDER_TIME_ZONE = 'Asia/Tashkent';

/**
 * 5.1 Customer list — the CRM grid.
 *
 * List + search + status (this component), header counters computed from
 * `CustomerListQueryService#counts` (never the ADR 0043 signed-metric layer —
 * see that service's own doc for why: the dashboard has no customer metrics
 * defined yet, and Statistics itself is not built in this console), manual
 * create, and a filtered export as an audited PII egress event. Bulk CSV
 * import is honestly not built (see the `import` child route) — the closest
 * built precedent is the SendPulse Telegram-contact import, which is a
 * different pipeline entirely.
 *
 * The docked detail (`:accountId`) is a routed child, the same shape
 * `locations-page.ts` already uses in this app.
 */
@Component({
  selector: 'q-customers-page',
  imports: [TPipe, RouterOutlet, CreateCustomerDialog],
  templateUrl: './customers-page.html',
  styleUrl: './customers-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CustomersPage {
  private readonly api = inject(CustomersApi);
  private readonly location = inject(CurrentLocation);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly customers = signal<readonly CustomerSummary[]>([]);
  protected readonly docked = signal(false);

  protected readonly counts = signal<CustomerCounts | null>(null);

  protected readonly statusFilter = signal<StatusFilter>('ALL');
  protected readonly searchQuery = signal('');
  private pageState: CursorState = firstPage();
  protected readonly hasMore = signal(false);
  protected readonly loadingMore = signal(false);

  protected readonly createDialogOpen = signal(false);
  protected readonly creating = signal(false);
  protected readonly createError = signal<string | null>(null);

  protected readonly exporting = signal(false);
  protected readonly exportError = signal<string | null>(null);
  protected readonly exportedRows = signal<readonly CustomerExportRow[] | null>(null);

  constructor() {
    void this.load();
    void this.loadCounts();
  }

  protected openCustomer(customer: CustomerSummary): void {
    void this.router.navigate([customer.id], { relativeTo: this.route });
  }

  /** Bound to `<router-outlet (activate) (deactivate)>` — see `orders-page.ts` for the same idiom. */
  protected onOutletActivate(): void {
    this.docked.set(true);
  }

  protected onOutletDeactivate(): void {
    this.docked.set(false);
    // The docked pane may have changed a name or a status; the list under it
    // should not go on showing a stale row.
    void this.load();
    void this.loadCounts();
  }

  protected onStatusFilterChange(value: string): void {
    this.statusFilter.set(value as StatusFilter);
    this.pageState = resetOnFilterChange(this.pageState);
    void this.load();
  }

  protected onSearchInput(value: string): void {
    this.searchQuery.set(value);
    this.pageState = resetOnFilterChange(this.pageState);
    void this.load();
  }

  protected async loadMore(): Promise<void> {
    const scope = this.location.scope();
    if (!scope || this.loadingMore()) {
      return;
    }
    this.loadingMore.set(true);
    try {
      const page = await this.api.list(scope, this.pageState, this.filters());
      this.customers.update((current) => [...current, ...page.items]);
      const next = nextPage(this.pageState, page);
      this.hasMore.set(next !== null);
      if (next) {
        this.pageState = next;
      }
    } catch (error) {
      this.loadError.set(this.describe(error));
    } finally {
      this.loadingMore.set(false);
    }
  }

  private filters(): { status?: string; query?: string } {
    const status = this.statusFilter();
    const query = this.searchQuery().trim();
    return {
      status: status === 'ALL' ? undefined : status,
      query: query.length > 0 ? query : undefined,
    };
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.loading.set(false);
      return;
    }
    this.denied.set(false);
    this.pageState = firstPage();
    try {
      const page = await this.api.list(scope, this.pageState, this.filters());
      this.customers.set(page.items);
      const next = nextPage(this.pageState, page);
      this.hasMore.set(next !== null);
      if (next) {
        this.pageState = next;
      }
    } catch (error) {
      this.loadError.set(this.describe(error));
    } finally {
      this.loading.set(false);
    }
  }

  private async loadCounts(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    try {
      this.counts.set(await this.api.counts(scope));
    } catch {
      // Non-critical: the grid itself loaded. §2.11's "previously loaded rows
      // stay" applies here too — the header simply shows no counters.
    }
  }

  // ------------------------------------------------------------------ create

  protected openCreateDialog(): void {
    this.createError.set(null);
    this.createDialogOpen.set(true);
  }

  protected async onCreateSubmit(submission: CreateCustomerSubmission): Promise<void> {
    const scope = this.location.scope();
    if (!scope || this.creating()) {
      return;
    }
    this.creating.set(true);
    this.createError.set(null);
    try {
      const accountId = await this.api.create(scope, {
        brandId: scope.brandId,
        phone: submission.phone,
        displayName: submission.displayName || null,
      });
      this.createDialogOpen.set(false);
      await this.load();
      await this.loadCounts();
      void this.router.navigate([accountId], { relativeTo: this.route });
    } catch (error) {
      this.createError.set(this.describe(error));
    } finally {
      this.creating.set(false);
    }
  }

  protected onCreateDismiss(): void {
    this.createDialogOpen.set(false);
  }

  // ------------------------------------------------------------------ export

  /**
   * Fixed, English, machine-facing purpose — not translated, the same reason
   * `order-detail-pane.ts`'s `REVEAL_PURPOSE` is not (its own doc explains
   * why): this is read by whoever reviews the audit log, not the operator.
   */
  private static readonly EXPORT_PURPOSE = 'Operations console: filtered customer export';

  protected async exportFiltered(): Promise<void> {
    const scope = this.location.scope();
    if (!scope || this.exporting()) {
      return;
    }
    this.exporting.set(true);
    this.exportError.set(null);
    try {
      const rows = await this.api.exportFiltered(
        scope,
        this.filters(),
        CustomersPage.EXPORT_PURPOSE,
      );
      this.exportedRows.set(rows);
      downloadCsv(rows, `customers-${new Date().toISOString().slice(0, 10)}.csv`);
    } catch (error) {
      this.exportError.set(this.describe(error));
    } finally {
      this.exporting.set(false);
    }
  }

  protected dismissExportNotice(): void {
    this.exportedRows.set(null);
    this.exportError.set(null);
  }

  // ------------------------------------------------------------------ format

  protected formatRegisteredAt(createdAt: string): string {
    return formatDate(new Date(createdAt), PLACEHOLDER_TIME_ZONE);
  }

  protected statusLabel(status: string): string {
    return customerStatusLabel(status, (key) => this.i18n.t(key));
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    throw error;
  }
}

/**
 * Builds the export as a browser-local CSV download.
 *
 * No server-side file: the export endpoint already returns the decrypted
 * rows as JSON in one audited call, and generating a downloadable file from
 * data already in the client's memory needs no additional round trip or
 * object storage.
 */
function downloadCsv(rows: readonly CustomerExportRow[], filename: string): void {
  const header = 'accountId,status,displayName,phone';
  const lines = rows.map((row) =>
    [row.accountId, row.status, csvCell(row.displayName), csvCell(row.phone)].join(','),
  );
  const blob = new Blob([[header, ...lines].join('\n')], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  try {
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = filename;
    anchor.click();
  } finally {
    URL.revokeObjectURL(url);
  }
}

function csvCell(value: string | null): string {
  if (value === null) {
    return '';
  }
  return `"${value.replaceAll('"', '""')}"`;
}

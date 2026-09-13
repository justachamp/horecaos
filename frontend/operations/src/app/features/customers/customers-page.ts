import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterOutlet } from '@angular/router';

import { CursorState, firstPage, nextPage, resetOnFilterChange } from '../../core/api/page';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { formatDate } from '../../core/format/datetime';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { Combobox, ComboboxOption } from '../../shared/ui/combobox';
import { DeniedState } from '../../shared/ui/denied-state';
import { EmptyState } from '../../shared/ui/empty-state';
import { InlineAlert } from '../../shared/ui/inline-alert';
import { Toasts } from '../../shared/ui/toast';
import { describeApiError } from '../orders/order-errors';
import { CreateCustomerDialog, CreateCustomerSubmission } from './create-customer-dialog';
import { customerStatusLabel } from './customer-status';
import { CustomerCounts, CustomerExportRow, CustomerSummary, CustomersApi } from './customers-api';

/** §5.1's status filter — `MERGED` is excluded, the same reason `CustomerController#requireKnownStatus` excludes it server-side. */
type StatusFilter = 'ALL' | 'ACTIVE' | 'SUSPENDED' | 'ANONYMIZED' | 'CLOSED';

const PLACEHOLDER_TIME_ZONE = 'Asia/Tashkent';

/** The capability `CustomerController`'s list endpoint declares — see `Capability.CUSTOMER_READ`. */
const LIST_CAPABILITY = 'CUSTOMER_READ';

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
  imports: [
    TPipe,
    RouterOutlet,
    CreateCustomerDialog,
    InlineAlert,
    DeniedState,
    EmptyState,
    Combobox,
  ],
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
  private readonly toasts = inject(Toasts);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  /**
   * `null` while the denial is only "this operator has no location in scope"
   * (`CurrentLocation.denied()`) — the server was never asked, so no
   * capability was ever checked, and `q-denied-state` must not name one. Set
   * to {@link LIST_CAPABILITY} only once a real `CUSTOMER_READ` 403 comes
   * back from {@link load}'s own request. See `q-denied-state`'s own doc for
   * why the distinction matters: a manager granting the named capability does
   * nothing for an operator who simply has no location assigned.
   */
  protected readonly deniedCapability = signal<string | null>(null);
  /** Distinct copy for "no location assigned" versus a genuine capability denial. */
  protected readonly deniedAskKey = computed<MessageKey>(() =>
    this.deniedCapability() === null ? 'ui.denied.ask.noLocation' : 'ui.denied.ask',
  );
  protected readonly loadError = signal<string | null>(null);
  protected readonly customers = signal<readonly CustomerSummary[]>([]);
  protected readonly docked = signal(false);

  protected readonly counts = signal<CustomerCounts | null>(null);

  protected readonly statusFilter = signal<StatusFilter>('ALL');
  protected readonly searchQuery = signal('');
  private pageState: CursorState = firstPage();
  protected readonly hasMore = signal(false);
  protected readonly loadingMore = signal(false);

  /**
   * `q-combobox`'s suggestion list — the same rows {@link load} already
   * fetched for the search query, capped, so choosing one is exact rather
   * than a second guess at what the filtered grid already found.
   */
  protected readonly searchOptions = computed<readonly ComboboxOption[]>(() =>
    this.searchQuery().trim().length === 0
      ? []
      : this.customers()
          .slice(0, 8)
          .map((customer) => ({
            id: customer.id,
            label: customer.displayName ?? customer.id,
            sublabel: customerStatusLabel(customer.status, (key) => this.i18n.t(key)),
          })),
  );

  protected readonly searchCreateLabel = computed(() =>
    this.i18n.t('customers.search.createNew', { query: this.searchQuery().trim() }),
  );

  protected readonly createDialogOpen = signal(false);
  /** Prefilled into `q-create-customer-dialog` when opened from the combobox's create-on-miss row. */
  protected readonly createDialogInitialPhone = signal('');
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

  /** `q-combobox`'s `optionSelected` carries only `{id, label}` — the row it came from is looked up here. */
  protected onSearchOptionSelected(option: ComboboxOption): void {
    const customer = this.customers().find((candidate) => candidate.id === option.id);
    if (customer) {
      this.openCustomer(customer);
    }
  }

  /** `q-combobox`'s create-on-miss row — the typed text becomes the dialog's prefilled phone. */
  protected onSearchCreateRequested(query: string): void {
    this.createError.set(null);
    this.createDialogInitialPhone.set(query);
    this.createDialogOpen.set(true);
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
      // No request was made, so no capability was ever checked — see
      // `deniedCapability`'s own doc for why this must stay `null` here.
      this.deniedCapability.set(null);
      this.loading.set(false);
      return;
    }
    this.denied.set(false);
    this.deniedCapability.set(null);
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
      if (error instanceof ApiError && error.code === ApiErrorCode.INSUFFICIENT_CAPABILITY) {
        this.denied.set(true);
        this.deniedCapability.set(LIST_CAPABILITY);
      } else {
        this.loadError.set(this.describe(error));
      }
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
    this.createDialogInitialPhone.set('');
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
      // Announced through the shell's toast host rather than in the dialog: the
      // dialog is already closed by this line, and the navigation two lines
      // below replaces the screen behind it (ADR 0101, row `X.17`). The
      // sentence carries no phone and no name — ADR 0029.
      this.toasts.show({ message: this.i18n.t('customers.create.done'), tone: 'success' });
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

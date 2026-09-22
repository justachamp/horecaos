import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { CurrentLocation } from '../../core/auth/current-location';
import { TimeZone, formatDateTime } from '../../core/format/datetime';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { ApiError } from '../../core/api/problem-details';
import { firstPage } from '../../core/api/page';
import { DeniedState } from '../../shared/ui/denied-state';
import { EmptyState } from '../../shared/ui/empty-state';
import { InlineAlert } from '../../shared/ui/inline-alert';
import { StatusPill } from '../../shared/ui/status-pill';
import {
  MappingPane,
  MappingPaneConflict,
  MappingPaneLinkIntent,
  MappingPaneRow,
} from '../../shared/ui/mapping-pane';
import { ImportWizard } from '../../shared/ui/import-wizard/import-wizard';
import {
  ImportWizardAdapter,
  ImportWizardJobSnapshot,
  ImportWizardRowOutcome,
  ImportWizardRowPreview,
} from '../../shared/ui/import-wizard/import-wizard-types';
import {
  IntegrationsApi,
  InstallationView,
  BindingView,
} from '../settings/integrations/integrations-api';
import { describeApiError } from '../orders/order-errors';
import { CatalogApi } from './catalog-api';
import { CatalogSummary } from './catalog-domain';
import {
  CatalogImportFileApi,
  CatalogImportRowView,
  CatalogImportSubmitRequest,
  downloadCsvText,
} from './catalog-import-file-api';
import { PosSyncApi, SyncRunSummary, SyncRunDetail, ApplyItemOutcome } from './pos-sync-api';
import { PosMappingApi, UnmappedExternalResponse, MappingView } from './pos-mapping-api';

/** See `order-queue.ts`'s identical constant — no location carries a timezone on any response this page reaches yet. */
const PLACEHOLDER_TIME_ZONE: TimeZone = 'Asia/Tashkent';

type Tab = 'runs' | 'mapping' | 'file';

interface BindingOption {
  readonly bindingId: string;
  readonly label: string;
}

const FILE_OUTCOME_KEYS: Record<CatalogImportRowView['outcome'], MessageKey> = {
  CREATED: 'catalog.import.file.outcome.CREATED',
  UPDATED: 'catalog.import.file.outcome.UPDATED',
  SKIPPED: 'catalog.import.file.outcome.SKIPPED',
  ERROR: 'catalog.import.file.outcome.ERROR',
};

const FILE_OUTCOME_TONES: Record<CatalogImportRowView['outcome'], ImportWizardRowOutcome['tone']> =
  {
    CREATED: 'success',
    UPDATED: 'info',
    SKIPPED: 'info',
    ERROR: 'danger',
  };

const FILE_ERROR_KEYS: Record<string, MessageKey> = {
  MISSING_PRODUCT_CODE: 'catalog.import.file.error.MISSING_PRODUCT_CODE',
  MISSING_PRODUCT_NAME: 'catalog.import.file.error.MISSING_PRODUCT_NAME',
  INVALID_STATUS: 'catalog.import.file.error.INVALID_STATUS',
  INVALID_PRICE: 'catalog.import.file.error.INVALID_PRICE',
  PRICE_REFUSED: 'catalog.import.file.error.PRICE_REFUSED',
  DUPLICATE_SKU: 'catalog.import.file.error.DUPLICATE_SKU',
  IMAGE_FETCH_FAILED: 'catalog.import.file.error.IMAGE_FETCH_FAILED',
};

/** A single CSV line, tolerant of quoted fields containing a comma -- mirrors `customer-import-page.ts`'s own. */
function splitCsvLine(line: string): string[] {
  const cells: string[] = [];
  let current = '';
  let inQuotes = false;
  for (let i = 0; i < line.length; i++) {
    const char = line[i];
    if (inQuotes) {
      if (char === '"') {
        if (line[i + 1] === '"') {
          current += '"';
          i++;
        } else {
          inQuotes = false;
        }
      } else {
        current += char;
      }
    } else if (char === '"') {
      inQuotes = true;
    } else if (char === ',') {
      cells.push(current);
      current = '';
    } else {
      current += char;
    }
  }
  cells.push(current);
  return cells.map((cell) => cell.trim());
}

function normalizeHeader(raw: string): string {
  return raw
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '');
}

function readAsText(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result ?? ''));
    reader.onerror = () => reject(reader.error ?? new Error('Could not read the file'));
    reader.readAsText(file);
  });
}

/** The six columns `q-import-wizard`'s client-side preview shows -- a subset of the full template, mirroring `CatalogImportParser.COLUMNS`'s own order. */
const FILE_PREVIEW_COLUMNS = [
  'product_code',
  'category_code',
  'product_name',
  'variant_sku',
  'price_amount_minor',
  'status',
] as const;

/**
 * catalog.md §4.11 (Import: Excel and POS), gap-map rows 4.5a/4.5b/10.8b/X.24.
 *
 * Three tabs over one brand's catalog: **Import runs** and **Соответствия**
 * are `PosSyncRunController` (ADR 0012, row 4.5a) — a provider-driven sync,
 * gated on a POS binding existing at all. **CSV import** (row 4.5b) is a
 * second, independent surface, reusing the shared `q-import-wizard` the way
 * {@code CustomerImportPage} already does: template download, upload,
 * dry-run diff, apply, and this brand's own run history — and it needs no
 * POS binding, because a CSV file is not read from a provider.
 */
@Component({
  selector: 'q-catalog-import-page',
  imports: [TPipe, DeniedState, EmptyState, InlineAlert, StatusPill, MappingPane, ImportWizard],
  templateUrl: './catalog-import-page.html',
  styleUrl: './catalog-import-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CatalogImportPage implements OnInit {
  private readonly location = inject(CurrentLocation);
  private readonly integrationsApi = inject(IntegrationsApi);
  private readonly syncApi = inject(PosSyncApi);
  private readonly mappingApi = inject(PosMappingApi);
  private readonly catalogApi = inject(CatalogApi);
  private readonly fileApi = inject(CatalogImportFileApi);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);

  protected readonly bindingOptions = signal<readonly BindingOption[]>([]);
  protected readonly selectedBindingId = signal<string | null>(null);

  protected readonly runs = signal<readonly SyncRunSummary[]>([]);
  protected readonly runsLoading = signal(false);

  protected readonly selectedRunId = signal<string | null>(null);
  protected readonly runDetail = signal<SyncRunDetail | null>(null);
  protected readonly applyItems = signal<readonly ApplyItemOutcome[]>([]);
  protected readonly detailLoading = signal(false);

  protected readonly starting = signal(false);
  protected readonly startError = signal<string | null>(null);
  protected readonly dryRun = signal(true);
  protected readonly importLanguage = signal('');
  protected readonly priceReImport = signal(false);

  protected readonly activeTab = signal<Tab>('runs');

  protected readonly mappingRows = signal<readonly MappingView[]>([]);
  protected readonly mappingUnmapped = signal<UnmappedExternalResponse | null>(null);
  protected readonly mappingConflicts = signal<readonly MappingPaneConflict[]>([]);
  protected readonly mappingLoading = signal(false);
  protected readonly mappingBusy = signal(false);
  protected readonly mappingError = signal<string | null>(null);

  // ---------------------------------------------------------- row 4.5b: CSV import

  protected readonly catalogOptions = signal<readonly CatalogSummary[]>([]);
  protected readonly selectedCatalogId = signal<string | null>(null);
  protected readonly catalogsLoading = signal(false);
  protected readonly fileError = signal<string | null>(null);
  protected readonly templateDownloading = signal(false);
  protected readonly exportDownloading = signal(false);

  protected readonly hasBinding = computed(() => this.selectedBindingId() !== null);

  async ngOnInit(): Promise<void> {
    await this.location.ensureLoaded();
    await this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.loading.set(false);
      return;
    }
    try {
      const installations = await this.integrationsApi.listInstallations(scope);
      const posInstallations = installations.filter((i: InstallationView) => i.category === 'POS');
      const options: BindingOption[] = [];
      for (const installation of posInstallations) {
        const bindings = await this.integrationsApi.listBindings(scope, installation.id);
        for (const binding of bindings as readonly BindingView[]) {
          options.push({
            bindingId: binding.id,
            label: `${installation.displayName} (${installation.providerType})`,
          });
        }
      }
      this.bindingOptions.set(options);
      const current = this.selectedBindingId();
      const nextBinding =
        current && options.some((o) => o.bindingId === current)
          ? current
          : (options[0]?.bindingId ?? null);
      this.selectedBindingId.set(nextBinding);
      this.denied.set(false);
      if (nextBinding) {
        await this.loadRuns();
      }
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else {
        this.loadError.set(this.describe(error));
      }
    } finally {
      this.loading.set(false);
    }
  }

  protected async selectBinding(bindingId: string): Promise<void> {
    this.selectedBindingId.set(bindingId);
    this.selectedRunId.set(null);
    this.runDetail.set(null);
    this.applyItems.set([]);
    await this.loadRuns();
    if (this.activeTab() === 'mapping') {
      await this.loadMapping();
    }
  }

  private async loadRuns(): Promise<void> {
    const bindingId = this.selectedBindingId();
    const scope = this.location.scope();
    if (!bindingId || !scope) {
      return;
    }
    this.runsLoading.set(true);
    try {
      const page = await firstValueFrom(
        this.syncApi.listRuns({ tenantId: scope.tenantId }, bindingId, firstPage()),
      );
      this.runs.set(page.items);
    } catch (error) {
      this.loadError.set(this.describe(error));
    } finally {
      this.runsLoading.set(false);
    }
  }

  protected async selectRun(runId: string): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    this.selectedRunId.set(runId);
    this.detailLoading.set(true);
    try {
      const [detail, itemsPage] = await Promise.all([
        firstValueFrom(this.syncApi.runDetail({ tenantId: scope.tenantId }, runId)),
        firstValueFrom(this.syncApi.applyItems({ tenantId: scope.tenantId }, runId, firstPage())),
      ]);
      this.runDetail.set(detail);
      this.applyItems.set(itemsPage.items);
    } catch (error) {
      this.loadError.set(this.describe(error));
    } finally {
      this.detailLoading.set(false);
    }
  }

  protected async startImport(): Promise<void> {
    const bindingId = this.selectedBindingId();
    const scope = this.location.scope();
    if (!bindingId || !scope || this.starting()) {
      return;
    }
    this.starting.set(true);
    this.startError.set(null);
    try {
      const language = this.importLanguage().trim();
      await firstValueFrom(
        this.syncApi.start(
          { tenantId: scope.tenantId },
          bindingId,
          this.dryRun(),
          language === '' ? null : language,
          this.priceReImport(),
        ),
      );
      await this.loadRuns();
    } catch (error) {
      this.startError.set(this.describe(error));
    } finally {
      this.starting.set(false);
    }
  }

  protected async selectTab(tab: Tab): Promise<void> {
    this.activeTab.set(tab);
    if (tab === 'mapping' && this.mappingRows().length === 0 && this.mappingUnmapped() === null) {
      await this.loadMapping();
    }
    if (tab === 'file' && this.catalogOptions().length === 0) {
      await this.loadCatalogs();
    }
  }

  private async loadMapping(): Promise<void> {
    const bindingId = this.selectedBindingId();
    const scope = this.location.scope();
    if (!bindingId || !scope) {
      return;
    }
    this.mappingLoading.set(true);
    this.mappingError.set(null);
    try {
      const [page, unmapped] = await Promise.all([
        firstValueFrom(
          this.mappingApi.list(
            { tenantId: scope.tenantId },
            bindingId,
            'PRODUCT',
            'ACTIVE',
            firstPage(),
          ),
        ),
        firstValueFrom(
          this.mappingApi.unmapped({ tenantId: scope.tenantId }, bindingId, 'PRODUCT'),
        ),
      ]);
      this.mappingRows.set(page.items);
      this.mappingUnmapped.set(unmapped);
    } catch (error) {
      this.mappingError.set(this.describe(error));
    } finally {
      this.mappingLoading.set(false);
    }
  }

  protected async onMappingLink(intent: MappingPaneLinkIntent): Promise<void> {
    const bindingId = this.selectedBindingId();
    const scope = this.location.scope();
    if (!bindingId || !scope || this.mappingBusy()) {
      return;
    }
    this.mappingBusy.set(true);
    this.mappingError.set(null);
    try {
      await firstValueFrom(
        this.mappingApi.create(
          { tenantId: scope.tenantId },
          bindingId,
          'PRODUCT',
          intent.horecaosId,
          intent.externalId,
          null,
        ),
      );
      await this.loadMapping();
    } catch (error) {
      this.mappingError.set(this.describe(error));
    } finally {
      this.mappingBusy.set(false);
    }
  }

  protected async onMappingUnlink(row: MappingPaneRow): Promise<void> {
    const scope = this.location.scope();
    if (!scope || this.mappingBusy()) {
      return;
    }
    this.mappingBusy.set(true);
    this.mappingError.set(null);
    try {
      await firstValueFrom(
        this.mappingApi.retire({ tenantId: scope.tenantId }, row.mappingId, row.version),
      );
      await this.loadMapping();
    } catch (error) {
      this.mappingError.set(this.describe(error));
    } finally {
      this.mappingBusy.set(false);
    }
  }

  protected async onMappingBulkAutoMatch(): Promise<void> {
    const bindingId = this.selectedBindingId();
    const scope = this.location.scope();
    if (!bindingId || !scope || this.mappingBusy()) {
      return;
    }
    this.mappingBusy.set(true);
    this.mappingError.set(null);
    try {
      const result = await firstValueFrom(
        this.mappingApi.bulkAutoMatch({ tenantId: scope.tenantId }, bindingId, 'PRODUCT'),
      );
      this.mappingConflicts.set(result.conflicts);
      await this.loadMapping();
    } catch (error) {
      this.mappingError.set(this.describe(error));
    } finally {
      this.mappingBusy.set(false);
    }
  }

  protected onMappingDismissConflict(conflict: MappingPaneConflict): void {
    this.mappingConflicts.update((conflicts) => conflicts.filter((c) => c !== conflict));
  }

  // ---------------------------------------------------------- row 4.5b: CSV import

  /**
   * Built once as a field, not a getter: the same choice {@code
   * CustomerImportPage}'s own {@code adapter} makes, and for the identical
   * reason — every closure below reads {@code this.location.scope()} and
   * {@code this.selectedCatalogId()} fresh on each call, so nothing here goes
   * stale even though the object itself is constructed only once.
   */
  protected readonly fileImportAdapter: ImportWizardAdapter = {
    previewColumns: [
      this.i18n.t('catalog.import.file.previewColumn.productCode'),
      this.i18n.t('catalog.import.file.previewColumn.categoryCode'),
      this.i18n.t('catalog.import.file.previewColumn.productName'),
      this.i18n.t('catalog.import.file.previewColumn.sku'),
      this.i18n.t('catalog.import.file.previewColumn.price'),
      this.i18n.t('catalog.import.file.previewColumn.status'),
    ],

    parsePreview: async (file: File): Promise<readonly ImportWizardRowPreview[]> => {
      const text = await readAsText(file);
      const lines = text.split(/\r?\n/).filter((line) => line.length > 0);
      if (lines.length === 0) {
        return [];
      }
      const header = splitCsvLine(lines[0]).map(normalizeHeader);
      const indices = FILE_PREVIEW_COLUMNS.map((column) => header.indexOf(column));
      return lines.slice(1).map((line, index) => {
        const cells = splitCsvLine(line);
        return {
          rowNumber: index + 1,
          cells: indices.map((i) => (i >= 0 ? (cells[i] ?? '') : '')),
        };
      });
    },

    submit: async (file: File, dryRun: boolean): Promise<string> => {
      const scope = this.location.scope();
      const catalogId = this.selectedCatalogId();
      if (!scope) {
        throw new Error(this.i18n.t('catalog.import.file.noLocation'));
      }
      if (!catalogId) {
        throw new Error(this.i18n.t('catalog.import.file.noCatalog'));
      }
      const content = await readAsText(file);
      const request: CatalogImportSubmitRequest = { catalogId, fileName: file.name, content };
      return this.fileApi.submit(scope, request, dryRun);
    },

    poll: async (jobId: string): Promise<ImportWizardJobSnapshot> => {
      const scope = this.location.scope();
      if (!scope) {
        throw new Error(this.i18n.t('catalog.import.file.noLocation'));
      }
      const status = await this.fileApi.status(scope, jobId);
      return {
        status: status.status,
        counts: {
          rowsTotal: status.rowsTotal,
          rowsProcessed: status.rowsProcessed,
          created: status.rowsCreated,
          // Matched combines UPDATED and SKIPPED: both mean an existing
          // product was found, whether or not anything about it changed.
          // The full breakdown is still visible per row below.
          matched: status.rowsUpdated + status.rowsSkipped,
          rejected: status.rowsError,
        },
        failureReason: status.failureReason,
      };
    },

    rows: async (jobId: string): Promise<readonly ImportWizardRowOutcome[]> => {
      const scope = this.location.scope();
      if (!scope) {
        throw new Error(this.i18n.t('catalog.import.file.noLocation'));
      }
      const rows = await this.fileApi.rows(scope, jobId);
      return rows.map((row) => ({
        rowNumber: row.rowNumber,
        outcome: this.i18n.t(FILE_OUTCOME_KEYS[row.outcome]),
        detail: row.errorReason
          ? this.i18n.t(FILE_ERROR_KEYS[row.errorReason] ?? 'catalog.import.file.error.OTHER')
          : null,
        tone: FILE_OUTCOME_TONES[row.outcome],
      }));
    },
  };

  private async loadCatalogs(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    this.catalogsLoading.set(true);
    this.fileError.set(null);
    try {
      const catalogs = await firstValueFrom(this.catalogApi.listCatalogs(scope));
      this.catalogOptions.set(catalogs);
      const current = this.selectedCatalogId();
      const stillValid = current && catalogs.some((c) => c.catalogId === current);
      this.selectedCatalogId.set(stillValid ? current : (catalogs[0]?.catalogId ?? null));
    } catch (error) {
      this.fileError.set(this.describe(error));
    } finally {
      this.catalogsLoading.set(false);
    }
  }

  protected selectCatalog(catalogId: string): void {
    this.selectedCatalogId.set(catalogId);
  }

  protected async downloadTemplate(): Promise<void> {
    const scope = this.location.scope();
    if (!scope || this.templateDownloading()) {
      return;
    }
    this.templateDownloading.set(true);
    this.fileError.set(null);
    try {
      const csv = await this.fileApi.template(scope);
      downloadCsvText(csv, 'catalog-import-template.csv');
    } catch (error) {
      this.fileError.set(this.describe(error));
    } finally {
      this.templateDownloading.set(false);
    }
  }

  protected async downloadExport(): Promise<void> {
    const scope = this.location.scope();
    const catalogId = this.selectedCatalogId();
    if (!scope || !catalogId || this.exportDownloading()) {
      return;
    }
    this.exportDownloading.set(true);
    this.fileError.set(null);
    try {
      const csv = await this.fileApi.export(scope, catalogId);
      downloadCsvText(csv, `catalog-export-${catalogId}.csv`);
    } catch (error) {
      this.fileError.set(this.describe(error));
    } finally {
      this.exportDownloading.set(false);
    }
  }

  protected onFileImportCompleted(): void {
    // No products grid lives on this page to refresh -- unlike
    // CustomerImportPage's docked pane, this tab is the whole screen.
  }

  protected timeLabel(iso: string | null | undefined): string {
    return iso ? formatDateTime(new Date(iso), PLACEHOLDER_TIME_ZONE) : '—';
  }

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }
}
